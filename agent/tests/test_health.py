"""``GET /health`` and ``GET /certificates``."""

from __future__ import annotations

import json


def test_the_health_endpoint_answers(client, origin_headers):
    status, answer = client.get("/health", headers=origin_headers)
    assert status == 200
    assert answer["status"] == "UP"
    assert answer["version"]
    assert answer["agent"]
    assert answer["token_present"] is True
    assert answer["mock"] is False


def test_the_health_endpoint_works_without_a_token(config, backend, console, server_factory):
    from dataclasses import replace

    from conftest import AgentClient

    backend._present = False
    server = server_factory(config, backend, console)
    client = AgentClient("127.0.0.1", server.port)
    status, answer = client.get("/health", headers={"Origin": "http://localhost:5173"})
    assert status == 200
    assert answer["token_present"] is False


def test_the_certificates_endpoint_lists_the_certificates_of_the_token(client, origin_headers):
    status, answer = client.get("/certificates", headers=origin_headers)
    assert status == 200
    assert isinstance(answer, list)
    assert len(answer) == 1
    certificate = answer[0]
    assert certificate["label"] == "test-dsc"
    assert certificate["subject"]
    assert certificate["pem"].startswith("-----BEGIN CERTIFICATE-----")
    assert certificate["expired"] is False


def test_the_certificates_endpoint_is_empty_without_a_token(config, backend, console, server_factory):
    from dataclasses import replace

    from conftest import AgentClient

    backend._present = False
    server = server_factory(config, backend, console)
    client = AgentClient("127.0.0.1", server.port)
    status, answer = client.get("/certificates", headers={"Origin": "http://localhost:5173"})
    assert status == 200
    assert answer == []


def test_the_answer_of_the_health_endpoint_is_json(client, origin_headers):
    status, answer = client.get("/health", headers=origin_headers)
    assert status == 200
    assert isinstance(answer, dict)
    json.dumps(answer)
