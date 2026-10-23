"""The limits of the request body of the signing agent."""

from __future__ import annotations

import base64
import json

from conftest import TEST_ORIGIN


def test_a_body_bigger_than_the_limit_is_rejected(client):
    """A body of 2 MB must not be accepted (the limit is 1 MB)."""
    body = b"x" * (2 * 1024 * 1024)
    request = (
        b"POST /sign HTTP/1.1\r\n"
        b"Host: 127.0.0.1\r\n"
        b"Origin: " + TEST_ORIGIN.encode() + b"\r\n"
        b"Content-Type: application/json\r\n"
        b"Content-Length: " + str(len(body)).encode() + b"\r\n"
        b"Connection: close\r\n"
        b"\r\n" + body
    )
    answer = client.raw(request)
    assert client.status_of(answer) == 413
    assert b"body_too_large" in answer


def test_a_content_length_over_the_limit_is_rejected_without_reading_the_body(client):
    request = (
        b"POST /sign HTTP/1.1\r\n"
        b"Host: 127.0.0.1\r\n"
        b"Origin: " + TEST_ORIGIN.encode() + b"\r\n"
        b"Content-Type: application/json\r\n"
        b"Content-Length: 99999999\r\n"
        b"Connection: close\r\n"
        b"\r\n"
    )
    answer = client.raw(request)
    assert client.status_of(answer) == 413


def test_a_body_without_a_content_length_is_rejected(client):
    request = (
        b"POST /sign HTTP/1.1\r\n"
        b"Host: 127.0.0.1\r\n"
        b"Origin: " + TEST_ORIGIN.encode() + b"\r\n"
        b"Content-Type: application/json\r\n"
        b"Connection: close\r\n"
        b"\r\n"
    )
    answer = client.raw(request)
    assert client.status_of(answer) == 400


def test_a_chunked_body_is_rejected(client):
    request = (
        b"POST /sign HTTP/1.1\r\n"
        b"Host: 127.0.0.1\r\n"
        b"Origin: " + TEST_ORIGIN.encode() + b"\r\n"
        b"Content-Type: application/json\r\n"
        b"Transfer-Encoding: chunked\r\n"
        b"Connection: close\r\n"
        b"\r\n"
        b"0\r\n\r\n"
    )
    answer = client.raw(request)
    assert client.status_of(answer) == 400


def test_a_body_that_is_not_json_is_rejected(client, origin_headers):
    status, payload = client.post_json("/sign", b"{not json", headers=origin_headers)
    assert status == 400
    assert payload["code"] == "invalid_request"


def test_the_wrong_content_type_is_rejected(client, origin_headers):
    body = json.dumps({"to_be_signed_b64": base64.b64encode(b"x").decode()}).encode()
    status, payload = client.post_json(
        "/sign", body, headers={**origin_headers, "Content-Type": "text/plain"}
    )
    assert status == 415
    assert payload["code"] == "unsupported_media_type"


def test_an_unknown_endpoint_is_rejected(client, origin_headers):
    status, payload = client.get("/secrets", headers=origin_headers)
    assert status == 404
    assert payload["code"] == "not_found"


def test_a_wrong_method_is_rejected(client, origin_headers):
    status, payload = client.request("DELETE", "/sign", headers=origin_headers)
    assert status == 405
    assert payload["code"] == "method_not_allowed"
