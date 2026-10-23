#!/usr/bin/env bash
#
# Creates the development token of the DSC signing agent with SoftHSM2.
#
#   ./scripts/softhsm-init.sh init          # token + key pair inside the token
#   ./scripts/softhsm-init.sh issue <spki.der> <out.pem>
#   ./scripts/softhsm-init.sh import <cert.pem> [id] [label]
#   ./scripts/softhsm-init.sh all
#
# The PINs of this script are TEST values, they are published in the README and
# in the source code.  They must never be used for anything else than a
# development machine.
#
set -euo pipefail

# ----------------------------------------------------------------- settings

TOKEN_LABEL="${TOKEN_LABEL:-intern-dsc}"
# TEST PIN - never use these values outside of a development machine.
USER_PIN="${USER_PIN:-123456}"
# TEST SO PIN - never use these values outside of a development machine.
SO_PIN="${SO_PIN:-12345678}"

AGENT_STATE_DIR="${AGENT_STATE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/state}"
TOKENDIR="${TOKENDIR:-${AGENT_STATE_DIR}/tokens}"
SOFTHSM_CONF="${SOFTHSM_CONF:-${AGENT_STATE_DIR}/softhsm2.conf}"
SOFTHSM_MODULE="${SOFTHSM_MODULE:-}"
KEY_LABEL="${KEY_LABEL:-dsc-signer}"
KEY_ID="${KEY_ID:-01}"
CKA_ID="${CKA_ID:-01}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
DEVPKI_DIR="${DEVPKI_DIR:-${REPO_ROOT}/devpki/out}"
BACKEND_DIR="${BACKEND_DIR:-${REPO_ROOT}/backend}"
CA_KEYSTORE="${CA_KEYSTORE:-${DEVPKI_DIR}/platform-ca.p12}"
CA_PASSWORD="${CA_PASSWORD:-changeit}"

# ------------------------------------------------------------------ helpers

die() {
  echo "error: $*" >&2
  exit 1
}

need() {
  command -v "$1" >/dev/null 2>&1 || die "the program '$1' is not installed"
}

find_module() {
  if [ -n "${SOFTHSM_MODULE}" ]; then
    echo "${SOFTHSM_MODULE}"
    return
  fi
  local candidate
  for candidate in \
    /usr/lib/softhsm/libsofthsm2.so \
    /usr/lib/x86_64-linux-gnu/softhsm/libsofthsm2.so \
    /usr/lib64/softhsm/libsofthsm2.so \
    /usr/local/lib/softhsm/libsofthsm2.so; do
    if [ -f "${candidate}" ]; then
      echo "${candidate}"
      return
    fi
  done
  die "the SoftHSM2 module was not found, set SOFTHSM_MODULE"
}

write_config() {
  mkdir -p "${TOKENDIR}"
  chmod 700 "${TOKENDIR}"
  cat > "${SOFTHSM_CONF}" <<EOF
# The configuration of the development token of the DSC agent.
directories.tokendir = ${TOKENDIR}
objectstore.backend = file
log.level = INFO
slots.removable = false
EOF
  export SOFTHSM2_CONF="${SOFTHSM_CONF}"
  echo "SoftHSM2 configuration : ${SOFTHSM_CONF}"
  echo "Token directory        : ${TOKENDIR}"
}

token_exists() {
  local module
  module="$(find_module)"
  pkcs11-tool --module "${module}" --list-token-slots 2>/dev/null | grep -q "${TOKEN_LABEL}"
}

# ------------------------------------------------------------------ commands

cmd_init() {
  need softhsm2-util
  need pkcs11-tool
  write_config

  local module
  module="$(find_module)"

  if token_exists; then
    echo "The token '${TOKEN_LABEL}' already exists."
  else
    echo "Creating the token '${TOKEN_LABEL}' (TEST PIN ${USER_PIN}) ..."
    softhsm2-util --init-token --free \
      --label "${TOKEN_LABEL}" \
      --pin "${USER_PIN}" \
      --so-pin "${SO_PIN}"
  fi

  echo "Generating a RSA-2048 key pair inside the token ..."
  pkcs11-tool --module "${module}" --login --pin "${USER_PIN}" \
    --keypairgen --key-type rsa:2048 --label "${KEY_LABEL}" --id "${CKA_ID}"

  local out="${AGENT_STATE_DIR}/public-key.der"
  mkdir -p "${AGENT_STATE_DIR}"
  pkcs11-tool --module "${module}" --read-object --type pubkey --id "${CKA_ID}" \
    --output-file "${out}"
  echo "The raw public key was written to ${out}"

  # pkcs11-tool writes the PKCS#1 public key, the backend expects the
  # X.509 SubjectPublicKeyInfo of the key.
  local spki="${AGENT_STATE_DIR}/spki.der"
  python3 - "${out}" "${spki}" <<'PY'
import sys
from cryptography.hazmat.primitives import serialization

with open(sys.argv[1], "rb") as handle:
    key = serialization.load_der_public_key(handle.read())
with open(sys.argv[2], "wb") as handle:
    handle.write(key.public_bytes(serialization.Encoding.DER,
                                   serialization.PublicFormat.SubjectPublicKeyInfo))
PY
  echo "The SubjectPublicKeyInfo was written to ${spki}"
  echo ""
  echo "Next step: issue a certificate for that public key and import it:"
  echo "  ./scripts/softhsm-init.sh issue ${spki} ${AGENT_STATE_DIR}/dsc-signer-cert.pem"
  echo "  ./scripts/softhsm-init.sh import ${AGENT_STATE_DIR}/dsc-signer-cert.pem"
}

cmd_issue() {
  local spki="${1:-}"
  local out="${2:-${AGENT_STATE_DIR}/dsc-signer-cert.pem}"
  [ -n "${spki}" ] || die "usage: softhsm-init.sh issue <spki.der> [out.pem]"
  [ -f "${spki}" ] || die "the file ${spki} does not exist"
  [ -f "${CA_KEYSTORE}" ] || die "the CA keystore ${CA_KEYSTORE} does not exist"

  need mvn
  echo "Issuing the DSC certificate with the backend ..."
  (
    cd "${BACKEND_DIR}"
    mvn -q exec:java \
      -Dexec.mainClass=com.dastakhat.devpki.DevPkiTool \
      -Dexec.args="issue-dsc ${spki} ${out} ${CA_KEYSTORE} ${CA_PASSWORD}"
  )
  echo "The certificate was written to ${out}"
}

cmd_import() {
  need pkcs11-tool
  local cert="${1:-}"
  local id="${2:-${CKA_ID}}"
  local label="${3:-${KEY_LABEL}}"
  [ -n "${cert}" ] || die "usage: softhsm-init.sh import <cert.pem> [id] [label]"
  [ -f "${cert}" ] || die "the file ${cert} does not exist"

  write_config
  local module
  module="$(find_module)"

  # An object with the same id has to be removed first.
  pkcs11-tool --module "${module}" --login --pin "${USER_PIN}" \
    --delete-object --type cert --id "${id}" >/dev/null 2>&1 || true

  pkcs11-tool --module "${module}" --login --pin "${USER_PIN}" \
    --write-object "${cert}" --type cert --id "${id}" --label "${label}"

  echo "The certificate ${cert} was written into the token (id ${id}, label ${label})."
}

cmd_all() {
  cmd_init
  cmd_issue "${AGENT_STATE_DIR}/spki.der" "${AGENT_STATE_DIR}/dsc-signer-cert.pem"
  cmd_import "${AGENT_STATE_DIR}/dsc-signer-cert.pem"
  echo ""
  echo "The development token is ready."
  echo "Start the agent with:"
  echo "  AGENT_PKCS11_MODULE=\$(cd \"$(dirname \"${BASH_SOURCE[0]}\")\" && pwd)/../state/softhsm2.conf \\"
  echo "  ./scripts/run-agent.sh"
}

case "${1:-all}" in
  init) shift; cmd_init "$@" ;;
  issue) shift; cmd_issue "$@" ;;
  import) shift; cmd_import "$@" ;;
  all) shift; cmd_all "$@" ;;
  *)
    echo "usage: $0 {init|issue <spki.der> [out.pem]|import <cert.pem> [id] [label]|all}" >&2
    exit 2
    ;;
esac
