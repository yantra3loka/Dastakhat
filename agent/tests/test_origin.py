"""The origin allowlist of the signing agent."""

from __future__ import annotations

import pytest

from conftest import FOREIGN_ORIGIN, TEST_ORIGIN


def test_an_allowed_origin_may_use_the_agent(client, origin_headers):
    status, payload = client.get("/health", headers=origin_headers)
    assert status == 200
    assert payload["status"] == "UP"


def test_a_foreign_origin_is_rejected(client):
    status, payload = client.get("/health", headers={"Origin": FOREIGN_ORIGIN})
    assert status == 403
    assert payload["code"] == "origin_not_allowed"


def test_a_request_without_an_origin_is_rejected_by_default(client):
    status, payload = client.get("/health")
    assert status == 403
    assert payload["code"] == "origin_not_allowed"


def test_a_request_without_an_origin_may_be_allowed(config, backend, console):
    from dscagent.config import AgentConfig
    from dscagent.security import ensure_tls_material
    from dscagent.server import AgentServer
    from dscagent.service import AgentService
    import threading

    from conftest import AgentClient

    relaxed = AgentConfig(
        host="127.0.0.1",
        port=0,
        allowed_origins=(),
        allow_no_origin=True,
        state_dir=config.state_dir,
        pin_source="terminal",
    )
    service = AgentService(relaxed, backend, console)
    cert_path, key_path = ensure_tls_material(relaxed)
    server = AgentServer(relaxed, service, cert_path, key_path)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        client = AgentClient("127.0.0.1", server.port)
        status, payload = client.get("/health")
        assert status == 200
        assert payload["status"] == "UP"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def test_a_foreign_origin_may_not_sign(client, backend):
    status, payload = client.post_json(
        "/sign",
        {"to_be_signed_b64": "aGVsbG8="},
        headers={"Origin": FOREIGN_ORIGIN},
    )
    assert status == 403
    assert backend.sign_calls == []


def test_the_cors_preflight_of_a_foreign_origin_is_rejected(client):
    status, _ = client.request(
        "OPTIONS",
        "/sign",
        headers={
            "Origin": FOREIGN_ORIGIN,
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "content-type",
        },
    )
    assert status == 403


def test_the_cors_preflight_of_an_allowed_origin_is_accepted(client, origin_headers):
    status, _ = client.request(
        "OPTIONS",
        "/sign",
        headers={
            "Origin": TEST_ORIGIN,
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "content-type",
        },
    )
    assert status == 204


@pytest.mark.parametrize(
    "origin",
    ["http://localhost:5173/", "HTTP://LOCALHOST:5173"],
)
def test_the_origin_is_compared_without_the_trailing_slash(config, backend, console, origin):
    from dscagent.security import OriginPolicy

    policy = OriginPolicy(config.allowed_origins, config.allow_no_origin)
    assert policy.is_allowed(origin)
