"""The test fixtures of the signing agent.

No test needs a real token: the PKCS#11 layer is replaced by
:class:`FakeTokenBackend` and the dialogue with the operator by
:class:`ScriptedConsole`.  The HTTP layer, the TLS layer, the validation and
the rate limiter are the real ones.
"""

from __future__ import annotations

import datetime as dt
import http.client
import json
import socket
import ssl
import threading
from typing import Any, Iterable

import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import NameOID

from dscagent.config import AgentConfig
from dscagent.errors import WrongPin
from dscagent.security import ensure_tls_material
from dscagent.server import AgentServer
from dscagent.service import AgentService
from dscagent.tokens import CertificateInfo, TokenBackend, describe_certificate

TEST_PIN = "123456"
TEST_ORIGIN = "http://localhost:5173"
FOREIGN_ORIGIN = "http://evil.example"


class FakeTokenBackend(TokenBackend):
    """A token that lives in the memory of the test."""

    name = "fake"

    def __init__(self, is_mock: bool = False, present: bool = True) -> None:
        self._key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self._certificate = self._build_certificate()
        self._present = present
        self.is_mock = is_mock
        self.sign_calls: list[dict[str, Any]] = []
        self.certificate_calls = 0

    def _build_certificate(self) -> x509.Certificate:
        subject = x509.Name(
            [
                x509.NameAttribute(NameOID.COUNTRY_NAME, "IN"),
                x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Dastakhat"),
                x509.NameAttribute(NameOID.COMMON_NAME, "Dastakhat DSC Signer (TEST CA)"),
            ]
        )
        now = dt.datetime.now(dt.timezone.utc)
        return (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(subject)
            .public_key(self._key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - dt.timedelta(days=1))
            .not_valid_after(now + dt.timedelta(days=365))
            .sign(self._key, hashes.SHA256())
        )

    # ------------------------------------------------------------- api

    def is_present(self) -> bool:
        return self._present

    def list_certificates(self) -> list[CertificateInfo]:
        self.certificate_calls += 1
        if not self._present:
            return []
        der = self._certificate.public_bytes(serialization.Encoding.DER)
        return [describe_certificate(der, "test-dsc")]

    def sign(self, data: bytes, *, cert_label: str | None, pin: str) -> bytes:
        self.sign_calls.append({"length": len(data), "cert_label": cert_label, "pin": pin})
        if pin != TEST_PIN:
            raise WrongPin("The token did not accept the PIN.")
        return self._key.sign(data, padding.PKCS1v15(), hashes.SHA256())

    # --------------------------------------------------------- helpers

    @property
    def public_key(self):
        return self._key.public_key()

    def verify(self, data: bytes, signature: bytes) -> bool:
        from cryptography.exceptions import InvalidSignature

        try:
            self.public_key.verify(signature, data, padding.PKCS1v15(), hashes.SHA256())
        except InvalidSignature:
            return False
        return True


class ScriptedConsole:
    """Replaces the dialogue with the operator.

    The last answer is repeated as long as the script is not exhausted.
    """

    def __init__(self, confirmations: Iterable[str] = ("SIGN",), pins: Iterable[str] = (TEST_PIN,)):
        self.confirmations = list(confirmations)
        self.pins = list(pins)
        self.announcements: list[dict[str, Any]] = []
        self.asked_for_pin = 0

    def announce(self, *, doc_name, doc_sha256, certificate_subject) -> None:
        self.announcements.append(
            {
                "doc_name": doc_name,
                "doc_sha256": doc_sha256,
                "certificate_subject": certificate_subject,
            }
        )

    def confirm(self, *, doc_name, doc_sha256, certificate_subject) -> bool:
        self.announce(
            doc_name=doc_name,
            doc_sha256=doc_sha256,
            certificate_subject=certificate_subject,
        )
        if self.confirmations:
            answer = self.confirmations.pop(0)
        else:
            answer = "SIGN"
        return answer.strip().lower() in ("sign", "y", "yes")

    def ask_pin(self, label: str = "Token PIN") -> str:
        self.asked_for_pin += 1
        if self.pins:
            return self.pins.pop(0)
        return TEST_PIN

    def write(self, text: str = "") -> None:
        pass

    def report(self, message: str) -> None:
        pass


class AgentClient:
    """A tiny HTTPS client for the tests."""

    def __init__(self, host: str, port: int) -> None:
        self.host = host
        self.port = port
        self._context = ssl._create_unverified_context()

    def _connection(self) -> http.client.HTTPSConnection:
        return http.client.HTTPSConnection(
            self.host, self.port, context=self._context, timeout=10
        )

    def request(
        self,
        method: str,
        path: str,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, Any]:
        connection = self._connection()
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            payload = response.read()
            try:
                parsed = json.loads(payload.decode("utf-8")) if payload else None
            except (UnicodeDecodeError, json.JSONDecodeError):
                parsed = payload
            return response.status, parsed
        finally:
            connection.close()

    def get(self, path: str, headers: dict[str, str] | None = None) -> tuple[int, Any]:
        return self.request("GET", path, headers=headers)

    def post_json(
        self,
        path: str,
        payload: Any,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, Any]:
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        merged = {"Content-Type": "application/json"}
        merged.update(headers or {})
        return self.request("POST", path, body=body, headers=merged)

    def raw(self, request: bytes) -> bytes:
        """Send a hand crafted request and read the whole answer.

        The request has to use ``Connection: close``, the answer is read until
        the server closes the connection.
        """
        with socket.create_connection((self.host, self.port), timeout=10) as raw_socket:
            with self._context.wrap_socket(raw_socket, server_hostname=self.host) as tls_socket:
                try:
                    tls_socket.sendall(request)
                except (BrokenPipeError, ConnectionResetError, ssl.SSLError, OSError):
                    # The server may answer and close the connection before the
                    # whole body was sent - that is exactly what the tests of
                    # the body limit check.
                    pass
                chunks: list[bytes] = []
                while True:
                    try:
                        chunk = tls_socket.recv(4096)
                    except (ssl.SSLError, OSError):
                        break
                    if not chunk:
                        break
                    chunks.append(chunk)
                return b"".join(chunks)

    @staticmethod
    def status_of(answer: bytes) -> int:
        """The status code of a raw answer."""
        first_line = answer.split(b"\r\n", 1)[0]
        parts = first_line.split(b" ")
        if len(parts) < 2:
            return 0
        return int(parts[1])


@pytest.fixture
def backend() -> FakeTokenBackend:
    return FakeTokenBackend()


@pytest.fixture
def console() -> ScriptedConsole:
    return ScriptedConsole()


@pytest.fixture
def config(tmp_path) -> AgentConfig:
    return AgentConfig(
        host="127.0.0.1",
        port=0,
        allowed_origins=(TEST_ORIGIN, "http://127.0.0.1:5173"),
        allow_no_origin=False,
        state_dir=tmp_path / "state",
        pin_source="terminal",
        mock=False,
        confirm_word="SIGN",
    )


@pytest.fixture
def service(config: AgentConfig, backend: FakeTokenBackend, console: ScriptedConsole) -> AgentService:
    return AgentService(config, backend, console)


@pytest.fixture
def server_factory():
    """Starts servers with a custom configuration."""
    created: list[tuple[AgentServer, threading.Thread]] = []

    def factory(config: AgentConfig, backend, console) -> AgentServer:
        service = AgentService(config, backend, console)
        cert_path, key_path = ensure_tls_material(config)
        agent_server = AgentServer(config, service, cert_path, key_path)
        thread = threading.Thread(target=agent_server.serve_forever, daemon=True)
        thread.start()
        created.append((agent_server, thread))
        return agent_server

    yield factory

    for agent_server, thread in created:
        agent_server.shutdown()
        agent_server.server_close()
        thread.join(timeout=5)


@pytest.fixture
def server(config: AgentConfig, service: AgentService, server_factory):
    return server_factory(config, service.backend, service.console)


@pytest.fixture
def client(server) -> AgentClient:
    return AgentClient("127.0.0.1", server.port)


@pytest.fixture
def origin_headers() -> dict[str, str]:
    return {"Origin": TEST_ORIGIN}
