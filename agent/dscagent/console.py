"""The dialogue with the operator of the signing agent.

Nothing that is typed here is ever written into a log file or returned to the
caller of the HTTP API:

* the confirmation word (``SIGN``) authorises *one* signature,
* the PIN is read without echo so that it does not appear on the screen and
  never in the scroll back of the terminal.
"""

from __future__ import annotations

import os
import sys
from typing import Callable, TextIO

from .errors import OperatorUnavailable

#: The answers that accept the signing.
ACCEPT_ANSWERS = ("sign", "y", "yes")


class OperatorConsole:
    """Asks the operator to confirm the signing and to type the token PIN."""

    def __init__(
        self,
        stream: TextIO | None = None,
        input_reader: Callable[[str], str] | None = None,
        pin_reader: Callable[[str], str] | None = None,
        confirm_word: str = "SIGN",
        require_tty: bool = True,
    ) -> None:
        self._stream = stream if stream is not None else sys.stdout
        self._input_reader = input_reader or input
        self._own_pin_reader = pin_reader is None
        self._pin_reader = pin_reader or self._read_secret
        self.confirm_word = confirm_word or "SIGN"
        self.require_tty = require_tty

    # ------------------------------------------------------------ helpers

    def _check_terminal(self) -> None:
        """The agent only talks to a human that sits in front of a terminal."""
        if self.require_tty and not sys.stdin.isatty():
            raise OperatorUnavailable(
                "The agent cannot ask the operator because no terminal is attached. "
                "Start the agent in a terminal (or with 'docker run -it')."
            )

    def write(self, text: str = "") -> None:
        self._stream.write(text + "\n")
        try:
            self._stream.flush()
        except (AttributeError, ValueError):  # pragma: no cover - closed stream
            pass

    # ----------------------------------------------------------- dialogue

    def announce(
        self,
        *,
        doc_name: str | None,
        doc_sha256: str | None,
        certificate_subject: str | None,
    ) -> None:
        """Show what is going to be signed."""
        self.write("")
        self.write("=" * 68)
        self.write("  DSC SIGNING REQUEST - the private key stays inside the token")
        self.write("=" * 68)
        self.write(f"  Document    : {doc_name or '(the caller did not send a name)'}")
        self.write(f"  SHA-256     : {doc_sha256 or '(the caller did not send a hash)'}")
        self.write(f"  Certificate : {certificate_subject or '(the token certificate)'}")
        self.write("=" * 68)

    def confirm(
        self,
        *,
        doc_name: str | None,
        doc_sha256: str | None,
        certificate_subject: str | None,
    ) -> bool:
        """Ask the operator to confirm the signing.

        Returns ``False`` when the operator cancels the signing.
        """
        self.announce(
            doc_name=doc_name,
            doc_sha256=doc_sha256,
            certificate_subject=certificate_subject,
        )
        self._check_terminal()
        try:
            answer = self._input_reader(f"Type {self.confirm_word} to sign, anything else cancels: ")
        except EOFError:
            self.write("No answer on the terminal - the signing is cancelled.")
            return False
        except OperatorUnavailable:
            raise
        answer = (answer or "").strip().lower()
        if answer in ACCEPT_ANSWERS:
            return True
        self.write("The signing was cancelled.")
        return False

    def ask_pin(self, label: str = "Token PIN") -> str:
        """Ask for the PIN of the token.  The PIN is never logged."""
        pin = self._pin_reader(f"{label}: ")
        if pin is None:
            raise OperatorUnavailable("No PIN was entered.")
        pin = pin.strip()
        if not pin:
            raise OperatorUnavailable("An empty PIN cannot be used.")
        return pin

    def report(self, message: str) -> None:
        """Show a short note about the result of the operation."""
        self.write(message)

    # ---------------------------------------------------------- the secret

    def _read_secret(self, prompt: str) -> str:
        """Read a line without echoing it."""
        self._check_terminal()
        if os.name == "nt":
            return self._read_secret_windows(prompt)
        return self._read_secret_posix(prompt)

    def _read_secret_posix(self, prompt: str) -> str:
        """Read a line without echoing it (POSIX).

        The line is read from ``sys.stdin`` and **not** from ``/dev/tty``:
        the confirmation is read from ``sys.stdin`` as well and a second
        reader on the terminal would swallow the input of the first one.
        """
        try:
            import termios
        except ImportError:  # pragma: no cover - not a POSIX system
            import getpass

            return getpass.getpass(prompt, stream=sys.stderr)

        descriptor = sys.stdin.fileno()
        old = termios.tcgetattr(descriptor)
        new = termios.tcgetattr(descriptor)
        new[3] &= ~termios.ECHO
        termios.tcsetattr(descriptor, termios.TCSADRAIN, new)
        try:
            sys.stderr.write(prompt)
            sys.stderr.flush()
            line = sys.stdin.readline()
        finally:
            termios.tcsetattr(descriptor, termios.TCSADRAIN, old)
            sys.stderr.write("\n")
            sys.stderr.flush()
        return line.strip()

    def _read_secret_windows(self, prompt: str) -> str:
        """Read a line without echoing it (the console of Windows)."""
        import msvcrt

        sys.stderr.write(prompt)
        sys.stderr.flush()
        characters: list[str] = []
        while True:
            char = msvcrt.getwch()
            if char in ("\r", "\n"):
                break
            if char == "\x03":  # Ctrl+C
                raise KeyboardInterrupt
            if char == "\x08":  # backspace
                if characters:
                    characters.pop()
                continue
            characters.append(char)
        sys.stderr.write("\n")
        sys.stderr.flush()
        return "".join(characters).strip()
