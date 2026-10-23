"""The security helpers of the signing agent.

* the origin allowlist that decides which browser may talk to the agent,
* the rate limiter that limits the number of signing operations,
* the TLS material (self signed certificate for ``127.0.0.1``).
"""

from __future__ import annotations

import datetime as dt
import ipaddress
import os
import threading
import time
from pathlib import Path
from typing import Callable, Iterable, Sequence

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from .config import AgentConfig
from .errors import OriginRejected, RateLimited

#: The hosts the generated certificate is valid for.
LOCAL_HOSTS = ("127.0.0.1", "::1", "localhost")
#: How long the generated certificate is valid (browsers accept at most 825 days).
CERTIFICATE_VALID_DAYS = 825


class OriginPolicy:
    """Decides which ``Origin`` header may talk to the agent.

    A request without an ``Origin`` header (curl, a script, a health check) is
    only allowed when ``AGENT_ALLOW_NO_ORIGIN`` is switched on.
    """

    def __init__(self, allowed: Iterable[str], allow_no_origin: bool = False) -> None:
        self._allowed = frozenset(self._normalise(origin) for origin in allowed if origin)
        self.allow_no_origin = allow_no_origin

    @staticmethod
    def _normalise(origin: str) -> str:
        origin = origin.strip()
        if origin.endswith("/"):
            origin = origin[:-1]
        return origin.lower()

    @property
    def allowed(self) -> frozenset[str]:
        return self._allowed

    def is_allowed(self, origin: str | None) -> bool:
        if origin is None or not origin.strip():
            return self.allow_no_origin
        return self._normalise(origin) in self._allowed

    def check(self, origin: str | None) -> None:
        """Raise :class:`~dscagent.errors.OriginRejected` for a foreign origin."""
        if not self.is_allowed(origin):
            if origin is None or not origin.strip():
                raise OriginRejected(
                    "The request has no Origin header. Only requests from an allowed origin "
                    "are accepted - set AGENT_ALLOW_NO_ORIGIN=true for command line clients."
                )
            raise OriginRejected(
                f"The origin {origin!r} is not allowed to use the signing agent."
            )


class SlidingWindowRateLimiter:
    """Allows at most ``limit`` operations inside ``window_seconds``."""

    def __init__(
        self,
        limit: int,
        window_seconds: float = 60.0,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.limit = limit
        self.window_seconds = window_seconds
        self._clock = clock
        self._events: list[float] = []
        self._lock = threading.Lock()

    def _prune(self, now: float) -> None:
        threshold = now - self.window_seconds
        self._events = [moment for moment in self._events if moment > threshold]

    def check(self) -> None:
        """Raise :class:`~dscagent.errors.RateLimited` when the limit is reached."""
        with self._lock:
            now = self._clock()
            self._prune(now)
            if len(self._events) >= self.limit:
                retry_after = max(1, int(self.window_seconds - (now - self._events[0])))
                raise RateLimited(
                    f"Too many signing operations. At most {self.limit} signatures are allowed "
                    f"per minute, try again in {retry_after} seconds."
                )
            self._events.append(now)

    def reset(self) -> None:
        with self._lock:
            self._events.clear()


# --------------------------------------------------------------------- TLS


def generate_self_signed_certificate(
    cert_path: Path, key_path: Path, hosts: Sequence[str] = LOCAL_HOSTS
) -> tuple[Path, Path]:
    """Create a self signed certificate for the loopback interface.

    The key never leaves this machine and is written with the least possible
    permissions.  The certificate is *not* trusted by any browser, see the
    README for ``mkcert``.
    """
    cert_path.parent.mkdir(parents=True, exist_ok=True)
    key_path.parent.mkdir(parents=True, exist_ok=True)

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = issuer = x509.Name(
        [
            x509.NameAttribute(NameOID.COUNTRY_NAME, "IN"),
            x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Dastakhat"),
            x509.NameAttribute(NameOID.COMMON_NAME, "Dastakhat DSC Signing Agent (local)"),
        ]
    )
    now = dt.datetime.now(dt.timezone.utc)
    san_entries: list[x509.GeneralName] = []
    for host in hosts:
        try:
            san_entries.append(x509.IPAddress(ipaddress.ip_address(host)))
        except ValueError:
            san_entries.append(x509.DNSName(host))

    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=CERTIFICATE_VALID_DAYS))
        .add_extension(x509.SubjectAlternativeName(san_entries), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.ExtendedKeyUsage([x509.oid.ExtendedKeyUsageOID.SERVER_AUTH]), critical=False
        )
        .sign(key, hashes.SHA256())
    )

    key_path.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.PKCS8,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    try:
        os.chmod(key_path, 0o600)
    except OSError:  # pragma: no cover - not every file system supports it
        pass
    cert_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    try:
        os.chmod(cert_path, 0o644)
    except OSError:  # pragma: no cover - not every file system supports it
        pass
    return cert_path, key_path


def ensure_tls_material(config: AgentConfig) -> tuple[Path, Path]:
    """Return the certificate and the key the agent has to use.

    ``AGENT_TLS_CERT`` / ``AGENT_TLS_KEY`` win.  Otherwise a self signed
    certificate is generated once into the (git ignored) state directory.
    """
    if config.tls_cert and config.tls_key:
        if not config.tls_cert.is_file():
            raise FileNotFoundError(f"The TLS certificate {config.tls_cert} does not exist.")
        if not config.tls_key.is_file():
            raise FileNotFoundError(f"The TLS key {config.tls_key} does not exist.")
        return config.tls_cert, config.tls_key

    cert_path = config.generated_tls_cert
    key_path = config.generated_tls_key
    if cert_path.is_file() and key_path.is_file():
        return cert_path, key_path
    return generate_self_signed_certificate(cert_path, key_path)
