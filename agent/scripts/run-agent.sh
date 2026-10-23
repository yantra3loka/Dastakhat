#!/usr/bin/env bash
#
# Starts the local DSC signing agent.
#
#   ./scripts/run-agent.sh                 # the configuration comes from the environment
#   ./scripts/run-agent.sh --mock          # without a token, with a software key
#
# The agent reads the PIN of the token from this terminal, so it has to be
# started in a terminal (never with "nohup" and without a tty).
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AGENT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

PYTHON="${PYTHON:-python3}"
if ! command -v "${PYTHON}" >/dev/null 2>&1; then
  if command -v python >/dev/null 2>&1; then
    PYTHON=python
  elif command -v py >/dev/null 2>&1; then
    PYTHON="py -3.12"
  else
    echo "error: no python interpreter was found (set PYTHON=...)" >&2
    exit 1
  fi
fi

cd "${AGENT_DIR}"

if [ -f "${AGENT_DIR}/.venv/bin/activate" ]; then
  # shellcheck disable=SC1091
  . "${AGENT_DIR}/.venv/bin/activate"
fi

echo "Starting the DSC signing agent from ${AGENT_DIR} ..."
exec ${PYTHON} "${AGENT_DIR}/agent.py" "$@"
