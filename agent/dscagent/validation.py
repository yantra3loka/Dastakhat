"""The validation of the requests of the signing agent.

The validation is deliberately strict:

* the body has to be a JSON object,
* unknown fields are rejected instead of being ignored,
* the bytes that have to be signed are decoded with a strict base64 decoder and
  are limited in size,
* the SHA-256 of the document has to be exactly 64 hexadecimal characters,
* the name of the document has to be a short printable string.

The field names of the frontend (``toBeSignedBase64``, ``certificateId``) and
the field names of the API description (``to_be_signed_b64``, ``cert_label``)
are both accepted.
"""

from __future__ import annotations

import base64
import binascii
import json
from dataclasses import dataclass
from typing import Any, Mapping

from .errors import ValidationError

#: The maximum length of the document name that is shown to the operator.
MAX_DOC_NAME_LENGTH = 200
#: The maximum length of a certificate label.
MAX_CERT_LABEL_LENGTH = 200

_TBS_FIELDS = ("to_be_signed_b64", "toBeSignedBase64")
_CERT_FIELDS = ("cert_label", "certLabel", "certificateId", "certificate_id")
_DOC_NAME_FIELDS = ("doc_name", "docName", "document_name", "documentName")
_DOC_SHA_FIELDS = ("doc_sha256", "docSha256", "document_sha256", "documentSha256")

ALLOWED_FIELDS = frozenset(_TBS_FIELDS + _CERT_FIELDS + _DOC_NAME_FIELDS + _DOC_SHA_FIELDS + ("pin",))

_HEX_DIGITS = frozenset("0123456789abcdefABCDEF")


@dataclass(frozen=True)
class SignRequest:
    """A validated signing request."""

    to_be_signed: bytes
    cert_label: str | None = None
    doc_name: str | None = None
    doc_sha256: str | None = None
    pin: str | None = None

    @property
    def to_be_signed_length(self) -> int:
        return len(self.to_be_signed)


def is_sha256_hex(value: str) -> bool:
    """True when the value is exactly 64 hexadecimal characters."""
    if not isinstance(value, str) or len(value) != 64:
        return False
    return all(character in _HEX_DIGITS for character in value)


def normalise_sha256(value: str) -> str:
    """Lower case a SHA-256 and check that it is a valid one."""
    if not is_sha256_hex(value):
        raise ValidationError("doc_sha256 must be 64 hexadecimal characters.")
    return value.lower()


def _first_present(payload: Mapping[str, Any], names: tuple[str, ...]) -> tuple[str | None, Any]:
    """Return the first field of ``names`` that is present in the payload.

    It is an error to use two spellings of the same field, the request would
    be ambiguous.
    """
    present = [name for name in names if name in payload]
    if len(present) > 1:
        raise ValidationError(
            f"The fields {', '.join(repr(name) for name in present)} are the same field, "
            "please use only one of them."
        )
    if not present:
        return None, None
    name = present[0]
    return name, payload[name]


def _require_text(value: Any, field: str) -> str:
    if not isinstance(value, str):
        raise ValidationError(f"The field {field!r} must be a string.")
    text = value.strip()
    if not text:
        raise ValidationError(f"The field {field!r} must not be empty.")
    if not text.isprintable():
        raise ValidationError(f"The field {field!r} must not contain control characters.")
    return text


def _optional_text(value: Any, field: str, max_length: int) -> str | None:
    if value is None:
        return None
    text = _require_text(value, field)
    if len(text) > max_length:
        raise ValidationError(f"The field {field!r} must not be longer than {max_length} characters.")
    return text


def decode_to_be_signed(value: Any, *, field: str, max_bytes: int) -> bytes:
    """Decode the base64 payload and check its size."""
    if not isinstance(value, str):
        raise ValidationError(f"The field {field!r} must be a base64 encoded string.")
    compact = "".join(value.split())
    max_length = 4 * ((max_bytes + 2) // 3)
    if len(compact) > max_length:
        raise ValidationError(
            f"The field {field!r} is too long: at most {max_bytes} bytes can be signed."
        )
    try:
        raw = base64.b64decode(compact, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise ValidationError(f"The field {field!r} is not valid base64.") from exc
    if not raw:
        raise ValidationError(f"The field {field!r} must not be empty.")
    if len(raw) > max_bytes:
        raise ValidationError(
            f"The field {field!r} is too long: at most {max_bytes} bytes can be signed."
        )
    return raw


def parse_sign_payload(raw: bytes, *, max_to_be_signed_bytes: int, keep_pin: bool = False) -> SignRequest:
    """Validate the body of ``POST /sign`` and return the parsed request."""
    if not raw or not raw.strip():
        raise ValidationError("The request body is empty.")

    # A byte order mark is not part of JSON, some clients add one anyway.
    raw = raw.removeprefix(b"\xef\xbb\xbf")

    try:
        payload = json.loads(raw.decode("utf-8"))
    except UnicodeDecodeError as exc:
        raise ValidationError("The request body must be UTF-8 encoded JSON.") from exc
    except json.JSONDecodeError as exc:
        raise ValidationError(f"The request body is not valid JSON: {exc.msg}.") from exc

    if not isinstance(payload, dict):
        raise ValidationError("The request body must be a JSON object.")

    unknown = sorted(set(payload) - ALLOWED_FIELDS)
    if unknown:
        raise ValidationError(f"Unknown field in the request: {', '.join(unknown)}.")

    tbs_field, tbs_value = _first_present(payload, _TBS_FIELDS)
    if tbs_field is None:
        raise ValidationError("The field 'to_be_signed_b64' is required.")
    to_be_signed = decode_to_be_signed(
        tbs_value, field=tbs_field, max_bytes=max_to_be_signed_bytes
    )

    cert_field, cert_value = _first_present(payload, _CERT_FIELDS)
    cert_label = _optional_text(cert_value, cert_field or "cert_label", MAX_CERT_LABEL_LENGTH)

    name_field, name_value = _first_present(payload, _DOC_NAME_FIELDS)
    doc_name = _optional_text(name_value, name_field or "doc_name", MAX_DOC_NAME_LENGTH)

    sha_field, sha_value = _first_present(payload, _DOC_SHA_FIELDS)
    doc_sha256 = None
    if sha_value is not None:
        if not isinstance(sha_value, str):
            raise ValidationError(f"The field {sha_field!r} must be a string.")
        doc_sha256 = normalise_sha256(sha_value.strip())

    pin: str | None = None
    if "pin" in payload:
        pin = _optional_text(payload["pin"], "pin", 128)

    return SignRequest(
        to_be_signed=to_be_signed,
        cert_label=cert_label,
        doc_name=doc_name,
        doc_sha256=doc_sha256,
        pin=pin if keep_pin else None,
    )
