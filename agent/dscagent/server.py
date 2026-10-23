"""The HTTP layer of the signing agent.

Only the standard library is used (``http.server`` + ``ssl``).  The server
listens on the loopback interface with TLS, it answers three endpoints:

* ``GET  /health``       - is the agent running, is a token plugged in,
* ``GET  /certificates`` - the certificates of the token,
* ``POST /sign``         - sign the bytes of the caller with the token.

Every request is checked against the origin allowlist before anything else
happens.
"""

from __future__ import annotations

import json
import logging
import socket
import ssl
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlsplit

from .config import AgentConfig
from .errors import (
    AgentError,
    BodyTooLarge,
    MethodNotAllowed,
    NotFound,
    OriginRejected,
    UnsupportedMediaType,
    ValidationError,
)
from .security import OriginPolicy
from .validation import parse_sign_payload

log = logging.getLogger("dscagent.server")

JSON_CONTENT_TYPE = "application/json; charset=utf-8"


class AgentRequestHandler(BaseHTTPRequestHandler):
    """Handles the requests of the frontend."""

    protocol_version = "HTTP/1.1"
    server_version = "DastakhatDscAgent"
    sys_version = ""
    timeout = 30

    # ------------------------------------------------------------ helpers

    @property
    def service(self):
        return self.server.service  # type: ignore[attr-defined]

    @property
    def origin_policy(self) -> OriginPolicy:
        return self.server.origin_policy  # type: ignore[attr-defined]

    @property
    def config(self) -> AgentConfig:
        return self.server.config  # type: ignore[attr-defined]

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        """Send the access log to the logger of the agent.

        The request line and the status are logged, never a request body and
        never a PIN.
        """
        log.info("%s %s", self.address_string(), format % args)

    def finish(self) -> None:
        """Close the connection without destroying an unread answer.

        When the agent rejects a request (a body that is too large for
        example) the peer may still be sending data.  A plain close would
        send a TCP reset and the peer would never see the answer.  The
        "lingering close" below drains the rest of the request so that the
        answer reaches the client.
        """
        try:
            if self.close_connection:
                self._linger_close()
        finally:
            super().finish()

    def _linger_close(self) -> None:
        try:
            self.wfile.flush()
        except (OSError, ValueError):  # pragma: no cover - broken connection
            pass
        try:
            self.connection.shutdown(socket.SHUT_WR)
        except OSError:  # pragma: no cover - already closed
            pass
        try:
            self.connection.settimeout(1.0)
            while self.connection.recv(65536):
                pass
        except (OSError, ValueError):  # pragma: no cover - normal end
            pass

    def _origin(self) -> str | None:
        return self.headers.get("Origin")

    def _cors_headers(self) -> None:
        origin = self._origin()
        if origin and self.origin_policy.is_allowed(origin):
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")

    def _send_json(self, status: int, payload: Any) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", JSON_CONTENT_TYPE)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self._cors_headers()
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _send_error(self, error: AgentError) -> None:
        self._send_json(error.status, error.to_dict())

    # ------------------------------------------------------- the routing

    def do_OPTIONS(self) -> None:  # noqa: N802 - the name is fixed by http.server
        """The CORS preflight of the browser."""
        try:
            self.origin_policy.check(self._origin())
        except OriginRejected as exc:
            self._send_error(exc)
            return
        self.send_response(204)
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Max-Age", "600")
        if (self.headers.get("Access-Control-Request-Private-Network") or "").strip().lower() == "true":
            # The private network access preflight of Chrome (and the local
            # network access permission of Chrome 142 and newer).
            self.send_header("Access-Control-Allow-Private-Network", "true")
        self._cors_headers()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch("GET")

    def do_HEAD(self) -> None:  # noqa: N802
        self._dispatch("HEAD")

    def do_POST(self) -> None:  # noqa: N802
        self._dispatch("POST")

    def do_PUT(self) -> None:  # noqa: N802
        self._dispatch("PUT")

    def do_DELETE(self) -> None:  # noqa: N802
        self._dispatch("DELETE")

    def do_PATCH(self) -> None:  # noqa: N802
        self._dispatch("PATCH")

    def _dispatch(self, method: str) -> None:
        try:
            self.origin_policy.check(self._origin())
            path = urlsplit(self.path).path.rstrip("/") or "/"
            if method in ("GET", "HEAD"):
                if path == "/health":
                    self._send_json(200, self.service.health())
                    return
                if path == "/certificates":
                    self._send_json(200, self.service.certificates())
                    return
                raise NotFound(f"There is no endpoint {path!r}.")
            if method == "POST":
                if path == "/sign":
                    self._send_json(200, self._sign())
                    return
                raise NotFound(f"There is no endpoint {path!r}.")
            raise MethodNotAllowed(f"The endpoint {path!r} does not support {method}.")
        except AgentError as exc:
            self._send_error(exc)
        except Exception:  # noqa: BLE001 - never leak a stack trace to the caller
            log.exception("The signing agent failed to answer a request.")
            self._send_json(
                500,
                {"error": "The signing agent failed to answer the request.", "code": "internal_error"},
            )

    # ----------------------------------------------------------- signing

    def _sign(self) -> dict[str, Any]:
        content_type = (self.headers.get("Content-Type") or "").split(";")[0].strip().lower()
        if content_type != "application/json":
            raise UnsupportedMediaType(
                "The endpoint /sign only accepts 'application/json'."
            )
        raw = self._read_body()
        request = parse_sign_payload(
            raw,
            max_to_be_signed_bytes=self.config.max_to_be_signed_bytes,
            keep_pin=self.config.pin_source == "body",
        )
        return self.service.sign(request)

    def _read_body(self) -> bytes:
        encoding = (self.headers.get("Transfer-Encoding") or "").strip().lower()
        if encoding and encoding != "identity":
            self.close_connection = True
            raise ValidationError("The agent does not accept a chunked request body.")
        raw_length = self.headers.get("Content-Length")
        if raw_length is None:
            self.close_connection = True
            raise ValidationError("The request needs a 'Content-Length' header.")
        try:
            length = int(raw_length)
        except (TypeError, ValueError) as exc:
            self.close_connection = True
            raise ValidationError("The 'Content-Length' header is not a number.") from exc
        if length < 0:
            self.close_connection = True
            raise ValidationError("The 'Content-Length' header is not a number.")
        if length > self.config.max_body_bytes:
            # The body is not read on purpose, the connection is closed.
            self.close_connection = True
            raise BodyTooLarge(
                f"The request body must not be bigger than {self.config.max_body_bytes} bytes."
            )
        if length == 0:
            raise ValidationError("The request body is empty.")
        data = self.rfile.read(length)
        if len(data) != length:
            self.close_connection = True
            raise ValidationError("The request body was incomplete.")
        return data


class _AgentHTTPServer(ThreadingHTTPServer):
    """The HTTP server of the agent.

    A client that closes the connection early (a browser that cancels a
    request, a test that sends a body that is too large) must not produce a
    stack trace on the console of the operator.
    """

    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request, client_address) -> None:
        error = sys.exc_info()[1]
        if isinstance(error, OSError):
            log.debug("The connection of the client %s ended: %s", client_address, error)
            return
        log.exception("The signing agent failed to handle a connection.")


class AgentServer:
    """The HTTPS server of the agent."""

    def __init__(
        self,
        config: AgentConfig,
        service,
        certfile,
        keyfile,
        origin_policy: OriginPolicy | None = None,
    ) -> None:
        self.config = config
        self._httpd = _AgentHTTPServer((config.host, config.port), AgentRequestHandler)
        self._httpd.service = service  # type: ignore[attr-defined]
        self._httpd.config = config  # type: ignore[attr-defined]
        self._httpd.origin_policy = origin_policy or OriginPolicy(
            config.allowed_origins, config.allow_no_origin
        )

        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(str(certfile), str(keyfile))
        self._httpd.socket = context.wrap_socket(self._httpd.socket, server_side=True)

    @property
    def port(self) -> int:
        return int(self._httpd.server_address[1])

    @property
    def base_url(self) -> str:
        host = self.config.host if ":" not in self.config.host else f"[{self.config.host}]"
        return f"https://{host}:{self.port}"

    def serve_forever(self) -> None:
        self._httpd.serve_forever(poll_interval=0.1)

    def shutdown(self) -> None:
        self._httpd.shutdown()

    def server_close(self) -> None:
        self._httpd.server_close()
