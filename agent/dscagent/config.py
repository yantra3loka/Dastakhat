"""The configuration of the local DSC signing agent.

Everything is read from the environment so that the agent can be started
without any command line flag.  The defaults are the safe ones:

* the agent listens on ``127.0.0.1`` only,
* only the origins of the development frontend are accepted,
* the PIN is read from the terminal of the operator, never from the request.
"""

from __future__ import annotations

import ipaddress
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from .errors import ConfigError

AGENT_NAME = "dastakhat-dsc-agent"
AGENT_VERSION = "1.0.0"

DEFAULT_HOST = "127.0.0.1"
DEFAULT_PORT = 51515
DEFAULT_ALLOWED_ORIGINS = ("http://localhost:5173", "http://127.0.0.1:5173")
DEFAULT_MAX_BODY_BYTES = 1024 * 1024
DEFAULT_MAX_TO_BE_SIGNED_BYTES = 64 * 1024
DEFAULT_RATE_LIMIT_PER_MINUTE = 30
RATE_LIMIT_WINDOW_SECONDS = 60.0
DEFAULT_CONFIRM_WORD = "SIGN"

PIN_SOURCE_TERMINAL = "terminal"
PIN_SOURCE_BODY = "body"

_TRUTHY = frozenset({"1", "true", "yes", "on"})
_FALSY = frozenset({"0", "false", "no", "off", ""})


def _env_bool(env: Mapping[str, str], name: str, default: bool) -> bool:
    raw = env.get(name)
    if raw is None:
        return default
    value = raw.strip().lower()
    if value in _TRUTHY:
        return True
    if value in _FALSY:
        return False
    raise ConfigError(f"The environment variable {name} must be a boolean, got {raw!r}.")


def _env_int(env: Mapping[str, str], name: str, default: int, *, minimum: int = 1) -> int:
    raw = env.get(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw.strip())
    except ValueError as exc:
        raise ConfigError(f"The environment variable {name} must be a number, got {raw!r}.") from exc
    if value < minimum:
        raise ConfigError(f"The environment variable {name} must be at least {minimum}, got {value}.")
    return value


def _env_list(env: Mapping[str, str], name: str, default: tuple[str, ...]) -> tuple[str, ...]:
    raw = env.get(name)
    if raw is None:
        return default
    items = tuple(part.strip() for part in raw.split(",") if part.strip())
    return items


def is_loopback(host: str) -> bool:
    """True when the given host can only be reached from this machine."""
    if host.strip().lower() in ("localhost", "localhost.localdomain"):
        return True
    try:
        return ipaddress.ip_address(host.strip()).is_loopback
    except ValueError:
        return False


@dataclass(frozen=True)
class AgentConfig:
    """The effective configuration of one agent process."""

    host: str = DEFAULT_HOST
    port: int = DEFAULT_PORT
    allowed_origins: tuple[str, ...] = DEFAULT_ALLOWED_ORIGINS
    allow_no_origin: bool = False
    allow_non_loopback: bool = False
    max_body_bytes: int = DEFAULT_MAX_BODY_BYTES
    max_to_be_signed_bytes: int = DEFAULT_MAX_TO_BE_SIGNED_BYTES
    rate_limit_per_minute: int = DEFAULT_RATE_LIMIT_PER_MINUTE
    rate_limit_window_seconds: float = RATE_LIMIT_WINDOW_SECONDS
    state_dir: Path = Path("state")
    tls_cert: Path | None = None
    tls_key: Path | None = None
    pkcs11_module: str | None = None
    token_label: str | None = None
    pin_source: str = PIN_SOURCE_TERMINAL
    mock: bool = False
    confirm_word: str = DEFAULT_CONFIRM_WORD
    require_doc_metadata: bool = False
    log_level: str = "INFO"
    version: str = AGENT_VERSION
    name: str = AGENT_NAME

    # ------------------------------------------------------------- helpers

    @property
    def base_url(self) -> str:
        """The URL the frontend has to talk to."""
        host = self.host if ":" not in self.host else f"[{self.host}]"
        return f"https://{host}:{self.port}"

    @property
    def tls_dir(self) -> Path:
        return self.state_dir / "tls"

    @property
    def generated_tls_cert(self) -> Path:
        return self.tls_dir / "agent-cert.pem"

    @property
    def generated_tls_key(self) -> Path:
        return self.tls_dir / "agent-key.pem"

    def describe(self) -> dict[str, object]:
        """A safe summary of the configuration (no secrets in here)."""
        return {
            "host": self.host,
            "port": self.port,
            "allowed_origins": list(self.allowed_origins),
            "allow_no_origin": self.allow_no_origin,
            "max_body_bytes": self.max_body_bytes,
            "max_to_be_signed_bytes": self.max_to_be_signed_bytes,
            "rate_limit_per_minute": self.rate_limit_per_minute,
            "pin_source": self.pin_source,
            "mock": self.mock,
            "pkcs11_module": self.pkcs11_module,
            "token_label": self.token_label,
        }

    # ----------------------------------------------------------- factories

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "AgentConfig":
        """Build the configuration from the environment."""
        env = os.environ if env is None else env

        default_state = Path(__file__).resolve().parents[1] / "state"
        state_dir = Path(env.get("AGENT_STATE_DIR") or default_state).expanduser()

        tls_cert = env.get("AGENT_TLS_CERT")
        tls_key = env.get("AGENT_TLS_KEY")
        if bool(tls_cert) != bool(tls_key):
            raise ConfigError(
                "AGENT_TLS_CERT and AGENT_TLS_KEY have to be set together - "
                "the agent never generates a key next to a certificate that was given."
            )

        pin_source = (env.get("AGENT_PIN_SOURCE") or PIN_SOURCE_TERMINAL).strip().lower()
        if pin_source not in (PIN_SOURCE_TERMINAL, PIN_SOURCE_BODY):
            raise ConfigError(
                "AGENT_PIN_SOURCE must be 'terminal' (default) or 'body' (legacy, insecure)."
            )

        mock = _env_bool(env, "AGENT_MOCK", False)
        module = (env.get("AGENT_PKCS11_MODULE") or "").strip() or None
        if mock and module:
            raise ConfigError("AGENT_MOCK and AGENT_PKCS11_MODULE must not be used together.")

        config = cls(
            host=(env.get("AGENT_HOST") or DEFAULT_HOST).strip(),
            port=_env_int(env, "AGENT_PORT", DEFAULT_PORT, minimum=0),
            allowed_origins=_env_list(env, "AGENT_ALLOWED_ORIGINS", DEFAULT_ALLOWED_ORIGINS),
            allow_no_origin=_env_bool(env, "AGENT_ALLOW_NO_ORIGIN", False),
            allow_non_loopback=_env_bool(env, "AGENT_ALLOW_NON_LOOPBACK", False),
            max_body_bytes=_env_int(env, "AGENT_MAX_BODY_BYTES", DEFAULT_MAX_BODY_BYTES, minimum=1024),
            max_to_be_signed_bytes=_env_int(
                env, "AGENT_MAX_TO_BE_SIGNED_BYTES", DEFAULT_MAX_TO_BE_SIGNED_BYTES, minimum=64
            ),
            rate_limit_per_minute=_env_int(
                env, "AGENT_RATE_LIMIT_PER_MINUTE", DEFAULT_RATE_LIMIT_PER_MINUTE, minimum=1
            ),
            state_dir=state_dir,
            tls_cert=Path(tls_cert).expanduser() if tls_cert else None,
            tls_key=Path(tls_key).expanduser() if tls_key else None,
            pkcs11_module=module,
            token_label=(env.get("AGENT_TOKEN_LABEL") or "").strip() or None,
            pin_source=pin_source,
            mock=mock,
            confirm_word=(env.get("AGENT_CONFIRM_WORD") or DEFAULT_CONFIRM_WORD).strip() or DEFAULT_CONFIRM_WORD,
            require_doc_metadata=_env_bool(env, "AGENT_REQUIRE_DOC_METADATA", False),
            log_level=(env.get("AGENT_LOG_LEVEL") or "INFO").strip().upper() or "INFO",
        )
        config.validate()
        return config

    def validate(self) -> None:
        """Make sure that the configuration does not open the machine up."""
        if not self.host:
            raise ConfigError("AGENT_HOST must not be empty.")
        if not is_loopback(self.host) and not self.allow_non_loopback:
            raise ConfigError(
                f"The agent refuses to listen on {self.host!r}: it is not a loopback address. "
                "Set AGENT_ALLOW_NON_LOOPBACK=true if you really want to expose the agent."
            )
        if not self.allowed_origins and not self.allow_no_origin:
            raise ConfigError(
                "AGENT_ALLOWED_ORIGINS is empty and AGENT_ALLOW_NO_ORIGIN is false - "
                "every request would be rejected. Set one of them."
            )
        for origin in self.allowed_origins:
            if not origin.lower().startswith(("http://", "https://")):
                raise ConfigError(f"An allowed origin must start with http:// or https://, got {origin!r}.")
