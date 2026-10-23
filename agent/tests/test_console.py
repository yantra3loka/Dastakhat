"""The dialogue with the operator."""

from __future__ import annotations

import io
import sys

import pytest

from dscagent.console import OperatorConsole
from dscagent.errors import OperatorUnavailable


class _NotATty:
    """A stdin that is not a terminal."""

    def isatty(self) -> bool:
        return False

    def readline(self) -> str:
        return ""


def console(answers=("SIGN",), pins=("123456",), require_tty=False) -> OperatorConsole:
    answers = list(answers)
    pins = list(pins)
    return OperatorConsole(
        stream=io.StringIO(),
        input_reader=lambda prompt: answers.pop(0) if answers else "",
        pin_reader=lambda prompt: pins.pop(0) if pins else "",
        confirm_word="SIGN",
        require_tty=require_tty,
    )


def test_the_confirmation_is_accepted():
    operator = console(answers=("SIGN",))
    assert operator.confirm(doc_name="a.pdf", doc_sha256="a" * 64, certificate_subject="CN=x") is True


def test_the_confirmation_is_accepted_in_lower_case():
    operator = console(answers=("sign",))
    assert operator.confirm(doc_name="a.pdf", doc_sha256="a" * 64, certificate_subject="CN=x") is True


def test_everything_but_sign_cancels_the_signing():
    for answer in ("", "no", "n", "maybe", "SIGNING"):
        operator = console(answers=(answer,))
        assert (
            operator.confirm(doc_name="a.pdf", doc_sha256="a" * 64, certificate_subject="CN=x")
            is False
        )


def test_the_document_and_the_hash_are_shown_to_the_operator():
    stream = io.StringIO()
    operator = OperatorConsole(
        stream=stream,
        input_reader=lambda prompt: "SIGN",
        pin_reader=lambda prompt: "123456",
        require_tty=False,
    )
    operator.confirm(doc_name="Contract.pdf", doc_sha256="b" * 64, certificate_subject="CN=Signer")
    output = stream.getvalue()
    assert "Contract.pdf" in output
    assert "b" * 64 in output
    assert "CN=Signer" in output


def test_the_pin_is_read_from_the_terminal():
    operator = console(pins=("123456",))
    assert operator.ask_pin() == "123456"


def test_without_a_terminal_the_agent_refuses_to_work(monkeypatch):
    monkeypatch.setattr(sys, "stdin", _NotATty())
    operator = OperatorConsole(stream=io.StringIO(), require_tty=True)
    with pytest.raises(OperatorUnavailable):
        operator.confirm(doc_name="a.pdf", doc_sha256="a" * 64, certificate_subject="CN=x")
    with pytest.raises(OperatorUnavailable):
        operator.ask_pin()
