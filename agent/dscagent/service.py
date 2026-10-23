"""The business logic of the signing agent.

The service is the only place that knows the order of the steps of a signing:

1. the request is validated by the HTTP layer,
2. the rate limit is checked,
3. the operator confirms the signing on the terminal,
4. the operator types the PIN of the token,
5. the token signs the bytes,
6. only the raw signature is returned to the caller.
"""

from __future__ import annotations

import base64
import datetime as dt
import logging
from typing import Sequence

from .config import PIN_SOURCE_BODY, AgentConfig
from .errors import (
    ConfirmationCancelled,
    TokenUnavailable,
    ValidationError,
    WrongPin,
)
from .security import SlidingWindowRateLimiter
from .tokens import CertificateInfo, TokenBackend
from .validation import SignRequest

log = logging.getLogger("dscagent.service")


class AgentService:
    """The use cases of the agent."""

    def __init__(
        self,
        config: AgentConfig,
        backend: TokenBackend,
        console,
        limiter: SlidingWindowRateLimiter | None = None,
    ) -> None:
        self.config = config
        self.backend = backend
        self.console = console
        self.limiter = limiter or SlidingWindowRateLimiter(
            config.rate_limit_per_minute, config.rate_limit_window_seconds
        )

    # ------------------------------------------------------------ health

    def health(self) -> dict[str, object]:
        """The answer of ``GET /health``."""
        try:
            token_present = self.backend.is_present()
        except Exception:  # noqa: BLE001 - the health check never fails
            token_present = False
        return {
            "status": "UP",
            "version": self.config.version,
            "agent": self.config.name,
            "token_present": token_present,
            "mock": bool(getattr(self.backend, "is_mock", False)),
            "pkcs11_module": self.config.pkcs11_module,
            "token_label": self.config.token_label,
        }

    # ------------------------------------------------------ certificates

    def certificates(self) -> list[dict[str, object]]:
        """The answer of ``GET /certificates``."""
        try:
            certificates = self.backend.list_certificates()
        except TokenUnavailable as exc:
            log.warning("The certificates cannot be read: %s", exc.message)
            return []
        return [certificate.to_dict() for certificate in certificates]

    # ----------------------------------------------------------- signing

    def sign(self, request: SignRequest) -> dict[str, object]:
        """Sign the bytes of the request with the key inside the token."""
        self.limiter.check()

        if self.config.require_doc_metadata and not (request.doc_name and request.doc_sha256):
            raise ValidationError(
                "The fields 'doc_name' and 'doc_sha256' are required by this installation."
            )

        certificates = self._certificates_or_fail()
        selected = self._select_certificate(certificates, request.cert_label)

        confirmed = self.console.confirm(
            doc_name=request.doc_name,
            doc_sha256=request.doc_sha256,
            certificate_subject=selected.subject if selected else None,
        )
        if not confirmed:
            log.info(
                "A signing was cancelled by the operator (document=%s, sha256=%s).",
                request.doc_name or "-",
                request.doc_sha256 or "-",
            )
            raise ConfirmationCancelled(
                "The signing was cancelled, the token did not sign anything."
            )

        pin = self._pin(request)
        try:
            signature = self.backend.sign(
                request.to_be_signed,
                cert_label=selected.id if selected else request.cert_label,
                pin=pin,
            )
        finally:
            # Drop the reference to the PIN as soon as possible.  Python cannot
            # wipe an immutable string, that is the reason why the PIN is read
            # from the terminal and never travels through the HTTP layer.
            del pin

        encoded = base64.b64encode(signature).decode("ascii")
        subject = selected.subject if selected else None
        log.info(
            "A document was signed (document=%s, sha256=%s, certificate=%s, signature=%d bytes).",
            request.doc_name or "-",
            request.doc_sha256 or "-",
            subject or "-",
            len(signature),
        )
        signed_at = dt.datetime.now(dt.timezone.utc).isoformat()
        return {
            # The snake_case names of the API description ...
            "signature_b64": encoded,
            "doc_sha256": request.doc_sha256,
            # ... and the camelCase names of the frontend.
            "signatureBase64": encoded,
            "docSha256": request.doc_sha256,
            "certificate": subject,
            "algorithm": "SHA256withRSA",
            "signed_at": signed_at,
        }

    # ------------------------------------------------------------ helpers

    def _certificates_or_fail(self) -> list[CertificateInfo]:
        try:
            return self.backend.list_certificates()
        except TokenUnavailable:
            raise
        except Exception as exc:  # noqa: BLE001
            raise TokenUnavailable(f"The token could not be read: {exc}") from exc

    @staticmethod
    def _select_certificate(
        certificates: Sequence[CertificateInfo], cert_label: str | None
    ) -> CertificateInfo | None:
        if not cert_label:
            return certificates[0] if certificates else None
        for certificate in certificates:
            if cert_label in (certificate.id, certificate.label, certificate.subject):
                return certificate
        raise ValidationError(
            f"The token does not hold a certificate with the label {cert_label!r}."
        )

    def _pin(self, request: SignRequest) -> str:
        if self.config.pin_source == PIN_SOURCE_BODY:
            if not request.pin:
                raise WrongPin("No PIN was supplied by the caller.")
            return request.pin
        return self.console.ask_pin()
