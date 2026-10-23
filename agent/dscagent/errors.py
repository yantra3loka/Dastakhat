"""The errors of the DSC signing agent.

Every error that can reach the HTTP layer carries a status code and a stable
machine readable code.  The messages are written for the operator, they never
contain a PIN, a private key or the bytes that have to be signed.
"""

from __future__ import annotations


class AgentError(Exception):
    """The base class of every error the agent reports to its caller."""

    status: int = 500
    code: str = "error"

    def __init__(self, message: str, *, code: str | None = None, status: int | None = None) -> None:
        super().__init__(message)
        self.message = message
        if code is not None:
            self.code = code
        if status is not None:
            self.status = status

    def to_dict(self) -> dict[str, str]:
        """The JSON body of the error."""
        return {"error": self.message, "code": self.code}


class ConfigError(AgentError):
    """The agent was started with a broken configuration."""

    status = 500
    code = "configuration_error"


class ValidationError(AgentError):
    """The request body did not pass the validation."""

    status = 400
    code = "invalid_request"


class OriginRejected(AgentError):
    """The ``Origin`` header is not on the allowlist."""

    status = 403
    code = "origin_not_allowed"


class NotFound(AgentError):
    """There is no such endpoint."""

    status = 404
    code = "not_found"


class MethodNotAllowed(AgentError):
    """The endpoint does not support this HTTP method."""

    status = 405
    code = "method_not_allowed"


class UnsupportedMediaType(AgentError):
    """The request did not use ``application/json``."""

    status = 415
    code = "unsupported_media_type"


class BodyTooLarge(AgentError):
    """The request body is bigger than the configured limit."""

    status = 413
    code = "body_too_large"


class RateLimited(AgentError):
    """Too many signing operations in the last minute."""

    status = 429
    code = "rate_limited"


class ConfirmationCancelled(AgentError):
    """The operator did not confirm the signing on the terminal."""

    status = 409
    code = "cancelled"


class WrongPin(AgentError):
    """The token did not accept the PIN.

    The PIN itself is never part of the message and never written to a log.
    """

    status = 401
    code = "wrong_pin"


class TokenUnavailable(AgentError):
    """There is no usable token (not inserted, locked, no module, ...)."""

    status = 503
    code = "token_unavailable"


class OperatorUnavailable(AgentError):
    """The agent cannot ask the operator (no terminal attached)."""

    status = 503
    code = "operator_unavailable"


class TokenFailure(AgentError):
    """The token refused to sign."""

    status = 500
    code = "token_failure"
