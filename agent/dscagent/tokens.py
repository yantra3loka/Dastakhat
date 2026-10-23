"""The PKCS#11 layer of the signing agent.

The module hides the token behind a small interface so that the rest of the
agent (and the tests) never have to know whether a real token, SoftHSM or the
software mock is used.

The most important rule of this module: **the private key never leaves the
token**.  The only thing that comes back from :meth:`TokenBackend.sign` are the
raw signature bytes.
"""

from __future__ import annotations

import abc
import datetime as dt
import hashlib
import logging
from dataclasses import dataclass
from pathlib import Path

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.x509.oid import NameOID

from .errors import (
    TokenFailure,
    TokenUnavailable,
    ValidationError,
    WrongPin,
)

log = logging.getLogger("dscagent.tokens")


@dataclass(frozen=True)
class CertificateInfo:
    """One certificate of the token."""

    id: str
    label: str
    subject: str
    issuer: str
    serial_number: str
    valid_from: str | None
    valid_to: str | None
    pem: str
    expired: bool
    trusted: bool = False

    def to_dict(self) -> dict[str, object]:
        """The JSON representation that the frontend understands."""
        return {
            "id": self.id,
            "label": self.label,
            "subject": self.subject,
            "issuer": self.issuer,
            "serialNumber": self.serial_number,
            "validFrom": self.valid_from,
            "validTo": self.valid_to,
            "pem": self.pem,
            "expired": self.expired,
            # The agent cannot know the trust store of the browser, the backend
            # validates the chain of the certificate.
            "trusted": self.trusted,
        }


class TokenBackend(abc.ABC):
    """What the agent expects from a token."""

    name: str = "token"
    is_mock: bool = False

    @abc.abstractmethod
    def is_present(self) -> bool:
        """True when a token is plugged in and can be used."""

    @abc.abstractmethod
    def list_certificates(self) -> list[CertificateInfo]:
        """The certificates of the token."""

    @abc.abstractmethod
    def sign(self, data: bytes, *, cert_label: str | None, pin: str) -> bytes:
        """Sign ``data`` inside the token and return the raw signature."""


# --------------------------------------------------------------- helpers


def _certificate_from_der(der: bytes) -> x509.Certificate:
    return x509.load_der_x509_certificate(der)


def _not_before(certificate: x509.Certificate) -> dt.datetime:
    value = getattr(certificate, "not_valid_before_utc", None)
    if value is None:  # pragma: no cover - cryptography < 42
        value = certificate.not_valid_before.replace(tzinfo=dt.timezone.utc)
    return value


def _not_after(certificate: x509.Certificate) -> dt.datetime:
    value = getattr(certificate, "not_valid_after_utc", None)
    if value is None:  # pragma: no cover - cryptography < 42
        value = certificate.not_valid_after.replace(tzinfo=dt.timezone.utc)
    return value


def describe_certificate(der: bytes, label: str = "", object_id: bytes | None = None) -> CertificateInfo:
    """Build the :class:`CertificateInfo` of a DER encoded certificate."""
    certificate = _certificate_from_der(der)
    subject = certificate.subject.rfc4514_string()
    issuer = certificate.issuer.rfc4514_string()
    serial = format(certificate.serial_number, "X")
    not_before = _not_before(certificate)
    not_after = _not_after(certificate)
    pem = certificate.public_bytes(serialization.Encoding.PEM).decode("ascii")
    label = label or ""
    if label:
        identifier = label
    elif object_id:
        identifier = "id:" + bytes(object_id).hex()
    else:
        identifier = "sha256:" + hashlib.sha256(der).hexdigest()[:16]
    return CertificateInfo(
        id=identifier,
        label=label or identifier,
        subject=subject,
        issuer=issuer,
        serial_number=serial,
        valid_from=not_before.isoformat(),
        valid_to=not_after.isoformat(),
        pem=pem,
        expired=dt.datetime.now(dt.timezone.utc) > not_after,
        trusted=False,
    )


def _safe_attribute(obj, attribute, default=None):
    """Read a PKCS#11 attribute without blowing up on a lazy token."""
    try:
        return obj[attribute]
    except Exception:  # noqa: BLE001 - tokens are creative with errors
        return default


def _as_text(value) -> str:
    if value is None:
        return ""
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace").strip()
    return str(value).strip()


# ------------------------------------------------------------ PKCS#11


class Pkcs11Backend(TokenBackend):
    """Talks to a real token through ``python-pkcs11``."""

    name = "pkcs11"

    def __init__(self, module_path: str, token_label: str | None = None, console=None) -> None:
        self.module_path = module_path
        self.token_label = token_label
        self.console = console
        self._lib = None
        self._token = None

    # ---------------------------------------------------------- plumbing

    def _load_library(self):
        if self._lib is not None:
            return self._lib
        path = Path(self.module_path)
        if not path.exists():
            raise TokenUnavailable(f"The PKCS#11 module {self.module_path} does not exist.")
        try:
            import pkcs11  # imported lazily so that the agent starts without the module
        except ImportError as exc:  # pragma: no cover - depends on the installation
            raise TokenUnavailable(
                "The package 'python-pkcs11' is not installed, the agent cannot use a token."
            ) from exc
        try:
            self._lib = pkcs11.lib(str(path))
        except Exception as exc:  # noqa: BLE001 - the C loader raises many things
            raise TokenUnavailable(
                f"The PKCS#11 module {self.module_path} could not be loaded."
            ) from exc
        return self._lib

    def _find_token(self):
        if self._token is not None:
            return self._token
        library = self._load_library()
        from pkcs11.exceptions import NoSuchToken, TokenNotPresent

        try:
            if self.token_label:
                token = library.get_token(token_label=self.token_label)
            else:
                tokens = list(library.get_tokens())
                if not tokens:
                    raise TokenUnavailable("No PKCS#11 token was found.")
                token = tokens[0]
        except (NoSuchToken, TokenNotPresent) as exc:
            label = self.token_label or "<any>"
            raise TokenUnavailable(f"No PKCS#11 token with the label {label!r} was found.") from exc
        except TokenUnavailable:
            raise
        except Exception as exc:  # noqa: BLE001
            raise TokenUnavailable(f"The PKCS#11 tokens could not be read: {exc}") from exc
        self._token = token
        return token

    def is_present(self) -> bool:
        try:
            self._find_token()
        except Exception:  # noqa: BLE001 - the health check never fails
            return False
        return True

    # ------------------------------------------------------ certificates

    def list_certificates(self) -> list[CertificateInfo]:
        from pkcs11 import Attribute, ObjectClass
        from pkcs11.exceptions import PKCS11Error, UserNotLoggedIn

        token = self._find_token()
        try:
            with token.open() as session:
                return self._read_certificates(session)
        except UserNotLoggedIn:
            # Some tokens only hand out the certificates after a login.
            pin = self._ask_pin()
            with token.open(user_pin=pin) as session:
                return self._read_certificates(session)
        except PKCS11Error as exc:
            if getattr(exc, "value", None) == 0x000000E1:  # CKR_USER_NOT_LOGGED_IN
                pin = self._ask_pin()
                with token.open(user_pin=pin) as session:
                    return self._read_certificates(session)
            raise TokenUnavailable(f"The certificates of the token could not be read.") from exc

    def _read_certificates(self, session) -> list[CertificateInfo]:
        from pkcs11 import Attribute, ObjectClass

        certificates: list[CertificateInfo] = []
        for obj in session.get_objects({Attribute.CLASS: ObjectClass.CERTIFICATE}):
            der = _safe_attribute(obj, Attribute.VALUE)
            if not der:
                continue
            label = _as_text(_safe_attribute(obj, Attribute.LABEL))
            object_id = _safe_attribute(obj, Attribute.ID)
            try:
                certificates.append(describe_certificate(bytes(der), label, object_id))
            except Exception:  # noqa: BLE001 - a broken certificate must not kill the agent
                log.warning("A certificate of the token could not be parsed and is skipped.")
        return certificates

    # ----------------------------------------------------------- signing

    def _ask_pin(self) -> str:
        if self.console is None:
            raise TokenUnavailable("The token needs a login, but the agent has no terminal.")
        return self.console.ask_pin("PIN of the token")

    def _find_private_key(self, session, cert_label: str | None):
        from pkcs11 import Attribute, KeyType, ObjectClass

        keys = list(
            session.get_objects(
                {Attribute.CLASS: ObjectClass.PRIVATE_KEY, Attribute.KEY_TYPE: KeyType.RSA}
            )
        )
        if not keys:
            raise TokenUnavailable("The token does not contain an RSA private key.")

        if cert_label:
            wanted_ids: set[bytes] = set()
            for certificate in session.get_objects({Attribute.CLASS: ObjectClass.CERTIFICATE}):
                label = _as_text(_safe_attribute(certificate, Attribute.LABEL))
                identifier = _as_text(_safe_attribute(certificate, Attribute.ID))
                if cert_label in (label, identifier):
                    certificate_id = _safe_attribute(certificate, Attribute.ID)
                    if certificate_id:
                        wanted_ids.add(bytes(certificate_id))
            for key in keys:
                key_id = _safe_attribute(key, Attribute.ID)
                if key_id and bytes(key_id) in wanted_ids:
                    return key
            for key in keys:
                if _as_text(_safe_attribute(key, Attribute.LABEL)) == cert_label:
                    return key
            raise ValidationError(
                f"The token does not hold a private key for the certificate {cert_label!r}."
            )
        return keys[0]

    def sign(self, data: bytes, *, cert_label: str | None, pin: str) -> bytes:
        from pkcs11 import Mechanism
        from pkcs11.exceptions import (
            PinExpired,
            PinIncorrect,
            PinLocked,
            PKCS11Error,
            UserNotLoggedIn,
        )

        token = self._find_token()
        try:
            with token.open(user_pin=pin) as session:
                key = self._find_private_key(session, cert_label)
                signature = key.sign(data, mechanism=Mechanism.SHA256_RSA_PKCS)
                return bytes(signature)
        except (PinIncorrect, UserNotLoggedIn) as exc:
            raise WrongPin("The token did not accept the PIN.") from exc
        except (PinLocked, PinExpired) as exc:
            raise TokenUnavailable("The PIN of the token is locked or has expired.") from exc
        except TokenUnavailable:
            raise
        except ValidationError:
            raise
        except PKCS11Error as exc:
            raise TokenFailure(f"The token refused to sign: {exc}") from exc
        except Exception as exc:  # noqa: BLE001
            raise TokenFailure(f"The token refused to sign: {exc}") from exc


# ---------------------------------------------------------------- mock


class MockBackend(TokenBackend):
    """A software key for the development installation without a token.

    The key lives in the (git ignored) state directory, it is a throwaway test
    artefact and it has no legal standing.  The operator still has to confirm
    every signature.
    """

    name = "mock"
    is_mock = True

    def __init__(self, state_dir: Path | None = None) -> None:
        self.state_dir = Path(state_dir) if state_dir else Path("state") / "mock"
        self.state_dir.mkdir(parents=True, exist_ok=True)
        self._key_path = self.state_dir / "mock-key.pem"
        self._cert_path = self.state_dir / "mock-cert.pem"
        self._private_key = self._load_or_create_key()
        self._certificate = self._load_or_create_certificate()

    # ---------------------------------------------------------- plumbing

    def _load_or_create_key(self):
        if self._key_path.is_file():
            key = serialization.load_pem_private_key(self._key_path.read_bytes(), password=None)
            return key
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self._key_path.write_bytes(
            key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.PKCS8,
                encryption_algorithm=serialization.NoEncryption(),
            )
        )
        return key

    def _load_or_create_certificate(self):
        if self._cert_path.is_file():
            return x509.load_pem_x509_certificate(self._cert_path.read_bytes())
        subject = x509.Name(
            [
                x509.NameAttribute(NameOID.COUNTRY_NAME, "IN"),
                x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Dastakhat"),
                x509.NameAttribute(NameOID.COMMON_NAME, "Dastakhat DSC Signer (MOCK)"),
            ]
        )
        now = dt.datetime.now(dt.timezone.utc)
        certificate = (
            x509.CertificateBuilder()
            .subject_name(subject)
            .issuer_name(subject)
            .public_key(self._private_key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - dt.timedelta(days=1))
            .not_valid_after(now + dt.timedelta(days=365))
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .sign(self._private_key, hashes.SHA256())
        )
        self._cert_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
        return certificate

    # ------------------------------------------------------------- api

    def is_present(self) -> bool:
        return True

    def list_certificates(self) -> list[CertificateInfo]:
        der = self._certificate.public_bytes(serialization.Encoding.DER)
        return [describe_certificate(der, "mock-dsc")]

    def sign(self, data: bytes, *, cert_label: str | None, pin: str) -> bytes:
        # The mock has no PIN, the argument only exists to keep the interface
        # of the real token.
        return self._private_key.sign(data, padding.PKCS1v15(), hashes.SHA256())


def create_backend(config, console=None) -> TokenBackend:
    """Build the backend that fits the configuration."""
    if config.mock or not config.pkcs11_module:
        if not config.mock:
            log.warning(
                "No PKCS#11 module is configured - the agent runs in mock mode with a "
                "software key. Set AGENT_PKCS11_MODULE to use a real token."
            )
        return MockBackend(config.state_dir / "mock")
    return Pkcs11Backend(config.pkcs11_module, config.token_label, console)
