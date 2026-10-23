"""The validation of the request body of ``POST /sign``."""

from __future__ import annotations

import base64

import pytest

from dscagent.errors import ValidationError
from dscagent.validation import (
    is_sha256_hex,
    normalise_sha256,
    parse_sign_payload,
)

MAX = 64 * 1024


def payload(**overrides) -> bytes:
    body = {"to_be_signed_b64": base64.b64encode(b"signed attributes").decode("ascii")}
    body.update(overrides)
    import json

    return json.dumps(body).encode("utf-8")


def test_a_minimal_body_is_accepted():
    request = parse_sign_payload(payload(), max_to_be_signed_bytes=MAX)
    assert request.to_be_signed == b"signed attributes"
    assert request.cert_label is None
    assert request.doc_name is None
    assert request.doc_sha256 is None


def test_the_snake_case_contract_is_accepted():
    request = parse_sign_payload(
        payload(
            cert_label="intern-dsc",
            doc_name="Contract.pdf",
            doc_sha256="A" * 64,
        ),
        max_to_be_signed_bytes=MAX,
    )
    assert request.cert_label == "intern-dsc"
    assert request.doc_name == "Contract.pdf"
    assert request.doc_sha256 == "a" * 64


def test_the_camel_case_contract_of_the_frontend_is_accepted():
    request = parse_sign_payload(
        b'{"toBeSignedBase64": "aGVsbG8=", "certificateId": "test-dsc", '
        b'"docName": "Contract.pdf", "docSha256": "' + b"b" * 64 + b'"}',
        max_to_be_signed_bytes=MAX,
    )
    assert request.to_be_signed == b"hello"
    assert request.cert_label == "test-dsc"
    assert request.doc_name == "Contract.pdf"
    assert request.doc_sha256 == "b" * 64


def test_the_bytes_to_sign_are_required():
    with pytest.raises(ValidationError) as error:
        parse_sign_payload(b"{}", max_to_be_signed_bytes=MAX)
    assert "to_be_signed_b64" in str(error.value)


def test_an_unknown_field_is_rejected():
    with pytest.raises(ValidationError) as error:
        parse_sign_payload(payload(evil="1"), max_to_be_signed_bytes=MAX)
    assert "evil" in str(error.value)


def test_two_spellings_of_the_same_field_are_rejected():
    with pytest.raises(ValidationError) as error:
        parse_sign_payload(
            payload(to_be_signed_b64="aGk=", toBeSignedBase64="aGk="), max_to_be_signed_bytes=MAX
        )
    assert "same field" in str(error.value)


def test_the_body_has_to_be_a_json_object():
    with pytest.raises(ValidationError):
        parse_sign_payload(b"[1, 2, 3]", max_to_be_signed_bytes=MAX)
    with pytest.raises(ValidationError):
        parse_sign_payload(b"\"text\"", max_to_be_signed_bytes=MAX)


def test_a_broken_json_is_rejected():
    with pytest.raises(ValidationError):
        parse_sign_payload(b"{not json", max_to_be_signed_bytes=MAX)


def test_an_empty_body_is_rejected():
    with pytest.raises(ValidationError):
        parse_sign_payload(b"", max_to_be_signed_bytes=MAX)


def test_the_bytes_to_sign_have_to_be_base64():
    with pytest.raises(ValidationError) as error:
        parse_sign_payload(payload(to_be_signed_b64="not base64 !!"), max_to_be_signed_bytes=MAX)
    assert "base64" in str(error.value)


def test_the_bytes_to_sign_have_to_be_a_string():
    with pytest.raises(ValidationError):
        parse_sign_payload(payload(to_be_signed_b64=42), max_to_be_signed_bytes=MAX)


def test_the_bytes_to_sign_are_limited():
    big = base64.b64encode(b"x" * 5000).decode("ascii")
    with pytest.raises(ValidationError) as error:
        parse_sign_payload(payload(to_be_signed_b64=big), max_to_be_signed_bytes=1024)
    assert "1024" in str(error.value)


def test_the_bytes_to_sign_must_not_be_empty():
    with pytest.raises(ValidationError):
        parse_sign_payload(payload(to_be_signed_b64=""), max_to_be_signed_bytes=MAX)


def test_the_sha256_has_to_have_64_hexadecimal_characters():
    with pytest.raises(ValidationError) as error:
        parse_sign_payload(payload(doc_sha256="a" * 63), max_to_be_signed_bytes=MAX)
    assert "64" in str(error.value)

    with pytest.raises(ValidationError):
        parse_sign_payload(payload(doc_sha256="z" * 64), max_to_be_signed_bytes=MAX)

    with pytest.raises(ValidationError):
        parse_sign_payload(payload(doc_sha256=""), max_to_be_signed_bytes=MAX)


def test_a_hexadecimal_helper():
    assert is_sha256_hex("0" * 64)
    assert not is_sha256_hex("0" * 63)
    assert not is_sha256_hex("g" * 64)
    assert not is_sha256_hex(None)
    assert normalise_sha256("A" * 64) == "a" * 64


def test_the_document_name_has_to_be_printable():
    with pytest.raises(ValidationError):
        parse_sign_payload(payload(doc_name="Contract\n.pdf"), max_to_be_signed_bytes=MAX)
    with pytest.raises(ValidationError):
        parse_sign_payload(payload(doc_name="x" * 500), max_to_be_signed_bytes=MAX)


def test_the_document_name_is_trimmed():
    request = parse_sign_payload(payload(doc_name="  Contract.pdf  "), max_to_be_signed_bytes=MAX)
    assert request.doc_name == "Contract.pdf"


def test_the_pin_is_only_kept_when_it_is_allowed():
    body = payload(pin="123456")
    assert parse_sign_payload(body, max_to_be_signed_bytes=MAX).pin is None
    assert parse_sign_payload(body, max_to_be_signed_bytes=MAX, keep_pin=True).pin == "123456"
