"""The signing flow of the agent.

The most important tests of this file: a signing that was not confirmed by the
operator never reaches the token.
"""

from __future__ import annotations

import base64
import logging

from conftest import TEST_ORIGIN, AgentClient, FakeTokenBackend, ScriptedConsole

TO_BE_SIGNED = b"the signed attributes of the cms message"
DOC_NAME = "Contract - Q3.pdf"
DOC_SHA256 = "3f786850e387550fdab836ed7e6dc881de23001b3f786850e387550fdab836ed"


def payload(**overrides) -> dict:
    body = {
        "to_be_signed_b64": base64.b64encode(TO_BE_SIGNED).decode("ascii"),
        "doc_name": DOC_NAME,
        "doc_sha256": DOC_SHA256,
    }
    body.update(overrides)
    return body


# ------------------------------------------------------------- the happy path


def test_a_confirmed_signing_returns_the_signature(client, origin_headers, backend):
    status, answer = client.post_json("/sign", payload(), headers=origin_headers)
    assert status == 200
    assert "signature_b64" in answer
    assert answer["signature_b64"] == answer["signatureBase64"]
    assert answer["doc_sha256"] == DOC_SHA256
    signature = base64.b64decode(answer["signature_b64"])
    assert backend.verify(TO_BE_SIGNED, signature)


def test_the_camel_case_contract_of_the_frontend_works(client, origin_headers, backend):
    body = {
        "toBeSignedBase64": base64.b64encode(TO_BE_SIGNED).decode("ascii"),
        "certificateId": "test-dsc",
        "pin": "123456",
    }
    status, answer = client.post_json("/sign", body, headers=origin_headers)
    assert status == 200
    assert base64.b64decode(answer["signatureBase64"])


def test_the_pin_is_asked_on_the_terminal(client, origin_headers, console):
    client.post_json("/sign", payload(), headers=origin_headers)
    assert console.asked_for_pin == 1


def test_the_operator_sees_the_document_and_the_hash(client, origin_headers, console):
    client.post_json("/sign", payload(), headers=origin_headers)
    assert console.announcements
    announcement = console.announcements[-1]
    assert announcement["doc_name"] == DOC_NAME
    assert announcement["doc_sha256"] == DOC_SHA256
    assert announcement["certificate_subject"]


# ----------------------------------------------------- the refused signing


def test_a_wrong_confirmation_aborts_the_signing(client, origin_headers, backend, console):
    console.confirmations = ["no"]
    status, answer = client.post_json("/sign", payload(), headers=origin_headers)
    assert status == 409
    assert answer["code"] == "cancelled"
    # The token was never asked to sign anything.
    assert backend.sign_calls == []
    assert "signature_b64" not in answer


def test_an_empty_confirmation_aborts_the_signing(client, origin_headers, backend, console):
    console.confirmations = [""]
    status, answer = client.post_json("/sign", payload(), headers=origin_headers)
    assert status == 409
    assert backend.sign_calls == []


def test_the_pin_is_not_asked_when_the_signing_was_cancelled(client, origin_headers, console):
    console.confirmations = ["no"]
    client.post_json("/sign", payload(), headers=origin_headers)
    assert console.asked_for_pin == 0


# ----------------------------------------------------------------- the PIN


def test_a_wrong_pin_is_reported_cleanly(client, origin_headers, backend, console):
    console.pins = ["999999"]
    status, answer = client.post_json("/sign", payload(), headers=origin_headers)
    assert status == 401
    assert answer["code"] == "wrong_pin"
    assert "999999" not in str(answer)


def test_the_pin_is_never_written_into_a_log(client, origin_headers, caplog):
    caplog.set_level(logging.DEBUG)
    status, _ = client.post_json("/sign", payload(), headers=origin_headers)
    assert status == 200
    for record in caplog.records:
        assert "123456" not in record.getMessage()


def test_the_bytes_to_sign_are_never_written_into_a_log(client, origin_headers, caplog):
    caplog.set_level(logging.DEBUG)
    client.post_json("/sign", payload(), headers=origin_headers)
    for record in caplog.records:
        assert base64.b64encode(TO_BE_SIGNED).decode() not in record.getMessage()
        assert "the signed attributes" not in record.getMessage()


# --------------------------------------------------------------- the limits


def test_an_unknown_certificate_is_rejected(client, origin_headers, backend):
    status, answer = client.post_json(
        "/sign", payload(cert_label="does-not-exist"), headers=origin_headers
    )
    assert status == 400
    assert backend.sign_calls == []


def test_the_rate_limit_is_enforced(config, backend, console, server_factory):
    from dataclasses import replace

    strict = replace(config, rate_limit_per_minute=2)
    server = server_factory(strict, backend, console)
    client = AgentClient("127.0.0.1", server.port)
    headers = {"Origin": TEST_ORIGIN}

    assert client.post_json("/sign", payload(), headers=headers)[0] == 200
    assert client.post_json("/sign", payload(), headers=headers)[0] == 200
    status, answer = client.post_json("/sign", payload(), headers=headers)
    assert status == 429
    assert answer["code"] == "rate_limited"
    assert len(backend.sign_calls) == 2


def test_the_rate_limit_does_not_count_the_other_endpoints(config, backend, console, server_factory):
    from dataclasses import replace

    strict = replace(config, rate_limit_per_minute=1)
    server = server_factory(strict, backend, console)
    client = AgentClient("127.0.0.1", server.port)
    headers = {"Origin": TEST_ORIGIN}

    for _ in range(5):
        assert client.get("/health", headers=headers)[0] == 200
        assert client.get("/certificates", headers=headers)[0] == 200
    assert client.post_json("/sign", payload(), headers=headers)[0] == 200
    assert client.post_json("/sign", payload(), headers=headers)[0] == 429
