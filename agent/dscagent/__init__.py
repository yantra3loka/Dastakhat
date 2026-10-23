"""The local DSC signing agent of the Dastakhat platform.

The agent is a small helper that runs on the computer of the signer.  It talks
to the PKCS#11 token, it signs the bytes the backend prepared and it returns
the raw signature.  The private key never leaves the token.

The agent is **not** part of the backend - the backend only validates the
certificate and embeds the signature the token produced.
"""

from __future__ import annotations

from .config import AGENT_NAME, AGENT_VERSION, AgentConfig
from .errors import AgentError
from .security import OriginPolicy, SlidingWindowRateLimiter
from .server import AgentServer
from .service import AgentService
from .tokens import TokenBackend, create_backend

__all__ = [
    "AGENT_NAME",
    "AGENT_VERSION",
    "AgentConfig",
    "AgentError",
    "AgentServer",
    "AgentService",
    "OriginPolicy",
    "SlidingWindowRateLimiter",
    "TokenBackend",
    "create_backend",
]
