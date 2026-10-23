#!/usr/bin/env python3
"""Start the local DSC signing agent.

Usage::

    python agent.py                 # the configuration comes from the environment
    python agent.py --mock          # without a token, with a software key
    python agent.py --port 51515    # the default

The agent listens on ``127.0.0.1`` only.  Every signing has to be confirmed on
the terminal of this process, the PIN of the token is read from the same
terminal and is never part of a request, a URL or a log line.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

if __package__ in (None, ""):  # pragma: no cover - "python agent.py"
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from dscagent.config import AGENT_NAME, AGENT_VERSION, AgentConfig
from dscagent.console import OperatorConsole
from dscagent.errors import AgentError
from dscagent.security import ensure_tls_material
from dscagent.server import AgentServer
from dscagent.service import AgentService
from dscagent.tokens import create_backend

log = logging.getLogger("dscagent")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="agent.py",
        description="The local DSC signing agent of the Dastakhat platform.",
    )
    parser.add_argument("--host", help="the interface to listen on (default 127.0.0.1)")
    parser.add_argument("--port", type=int, help="the port to listen on (default 51515)")
    parser.add_argument(
        "--module",
        dest="pkcs11_module",
        help="the path of the PKCS#11 module of the token",
    )
    parser.add_argument("--token-label", help="the label of the token (default: the first one)")
    parser.add_argument("--mock", action="store_true", help="use a software key instead of a token")
    parser.add_argument(
        "--allowed-origin",
        action="append",
        dest="allowed_origins",
        help="an origin that may use the agent, can be given more than once",
    )
    parser.add_argument(
        "--allow-no-origin",
        action="store_true",
        help="also accept requests without an Origin header (curl, scripts)",
    )
    parser.add_argument("--state-dir", help="where the TLS material and the mock key are stored")
    parser.add_argument(
        "--pin-source",
        choices=("terminal", "body"),
        help="where the PIN comes from, 'terminal' is the secure default",
    )
    parser.add_argument("--log-level", help="DEBUG, INFO, WARNING, ERROR")
    parser.add_argument("--version", action="version", version=f"{AGENT_NAME} {AGENT_VERSION}")
    parser.add_argument(
        "--check",
        action="store_true",
        help="only check the configuration and the TLS material, then exit",
    )
    return parser


def apply_overrides(config: AgentConfig, args: argparse.Namespace) -> AgentConfig:
    """The command line wins over the environment."""
    from dataclasses import replace

    changes: dict[str, object] = {}
    if args.host:
        changes["host"] = args.host
    if args.port is not None:
        changes["port"] = args.port
    if args.pkcs11_module:
        changes["pkcs11_module"] = args.pkcs11_module
    if args.token_label:
        changes["token_label"] = args.token_label
    if args.mock:
        changes["mock"] = True
    if args.allowed_origins:
        changes["allowed_origins"] = tuple(args.allowed_origins)
    if args.allow_no_origin:
        changes["allow_no_origin"] = True
    if args.state_dir:
        changes["state_dir"] = Path(args.state_dir)
    if args.pin_source:
        changes["pin_source"] = args.pin_source
    if args.log_level:
        changes["log_level"] = args.log_level.upper()
    updated = replace(config, **changes)
    updated.validate()
    return updated


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level, logging.INFO),
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        stream=sys.stderr,
    )


def print_banner(config: AgentConfig, backend, cert_path: Path, key_path: Path, base_url: str) -> None:
    """Show the operator what the agent is doing."""
    token_state = "present" if backend.is_present() else "not inserted"
    if getattr(backend, "is_mock", False):
        token_state = "MOCK (software key, no token)"
    print("")
    print(f"  {AGENT_NAME} {AGENT_VERSION}")
    print(f"  URL             : {base_url}")
    print(f"  TLS certificate : {cert_path}")
    print(f"  TLS key         : {key_path} (never leave this machine)")
    print(f"  Allowed origins : {', '.join(config.allowed_origins) or '(none)'}")
    print(f"  No origin header: {'allowed' if config.allow_no_origin else 'rejected'}")
    print(f"  Token           : {token_state}")
    print(f"  PIN             : read from this terminal")
    print(f"  Rate limit      : {config.rate_limit_per_minute} signatures per minute")
    if config.pin_source == "body":
        print("  WARNING         : the PIN is read from the request body (legacy mode).")
    print("")
    print("  Every signing has to be confirmed on this terminal.")
    print("")
    try:
        sys.stdout.flush()
    except (AttributeError, ValueError):  # pragma: no cover - closed stream
        pass


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    try:
        config = apply_overrides(AgentConfig.from_env(), args)
    except AgentError as exc:
        print(f"Configuration error: {exc.message}", file=sys.stderr)
        return 2

    configure_logging(config.log_level)

    console = OperatorConsole(confirm_word=config.confirm_word)
    backend = create_backend(config, console)
    service = AgentService(config, backend, console)

    try:
        cert_path, key_path = ensure_tls_material(config)
    except (OSError, FileNotFoundError) as exc:
        print(f"The TLS material could not be prepared: {exc}", file=sys.stderr)
        return 2

    if args.check:
        print_banner(config, backend, cert_path, key_path, config.base_url)
        print(json.dumps(config.describe(), indent=2))
        return 0

    try:
        server = AgentServer(config, service, cert_path, key_path)
    except OSError as exc:
        print(f"The server could not be started: {exc}", file=sys.stderr)
        return 1

    print_banner(config, backend, cert_path, key_path, server.base_url)
    log.info("The signing agent is listening on %s", server.base_url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("")
        print("The signing agent was stopped.")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
