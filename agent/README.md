# The local DSC signing agent

A small local helper that signs documents with a **DSC token** (PKCS#11) for the
Dastakhat platform.  It runs on the computer of the signer, it talks to the
token and it returns the raw signature that the backend embeds into the PDF.

```
 browser (frontend)                backend (Java)              agent (Python)
 ───────────────────               ─────────────────           ────────────────
 POST /sign/{token}/dsc/prepare  ──▶  builds the CMS signed
                                   attributes (DER)      ┐
                                                              │
 GET  /certificates  ─────────────────────────────────────────┤
                                                              ▼
 POST /sign  ─────────────────────────────────────────────▶  https://127.0.0.1:51515
   to_be_signed_b64 = base64(signed attributes)                    │
                                                               ├─ prints the document name
                                                               ├─ prints the SHA-256
   signature_b64 = base64(raw signature)  ◀────────────────────┤
                                                               ├─ asks: type SIGN
                                                               ├─ asks: the PIN of the token
                                                               └─ the token signs, the private
                                                                  key never leaves the token
```

The agent is **not** part of the backend.  The backend only validates the
certificate and embeds the signature the token produced.

---

## 1. What it does

| Endpoint | Purpose |
|---|---|
| `GET /health` | `{"status": "UP", "version": "…", "agent": "…", "token_present": true, "mock": false}` |
| `GET /certificates` | The certificates of the token (JSON array with `id`, `label`, `subject`, `issuer`, `serialNumber`, `validFrom`, `validTo`, `pem`, `expired`, `trusted`). |
| `POST /sign` | Signs the given bytes with the key inside the token and answers `{"signature_b64": "…", "doc_sha256": "…"}`. |

`POST /sign` accepts **both** spellings of the contract, so the existing
frontend (`frontend/src/api/dscAgent.ts`) works unchanged:

| Purpose | API description (snake_case) | Frontend (camelCase) |
|---|---|---|
| the bytes that have to be signed | `to_be_signed_b64` | `toBeSignedBase64` |
| the certificate of the token | `cert_label` | `certificateId` |
| the name of the document | `doc_name` | `docName` |
| the SHA-256 of the document | `doc_sha256` | `docSha256` |

The answer contains both spellings as well (`signature_b64` **and**
`signatureBase64`), so the frontend parser of `dscAgent.ts` finds what it
expects.

---

## 2. The security boundary

The agent is a **local, single user helper**.  Everything that protects the
private key is implemented in the agent, nothing is delegated to the browser
or to the backend.

| # | Rule | How it is implemented |
|---|---|---|
| 1 | The agent only listens on `127.0.0.1` | `AGENT_HOST` defaults to `127.0.0.1`; a non loopback address is refused unless `AGENT_ALLOW_NON_LOOPBACK=true`. |
| 2 | The traffic is encrypted | HTTPS with TLS 1.2+.  A self signed certificate for `127.0.0.1` / `localhost` is generated into `agent/state/tls/` (git ignored) or taken from `AGENT_TLS_CERT` / `AGENT_TLS_KEY`.  The key is never committed. |
| 3 | Only the frontend may talk to the agent | The `Origin` header has to be in `AGENT_ALLOWED_ORIGINS` (default `http://localhost:5173,http://127.0.0.1:5173`), otherwise the answer is **403**.  A request **without** an `Origin` header (curl, a script) is only allowed when `AGENT_ALLOW_NO_ORIGIN=true`. |
| 4 | The requests are validated | The body of `POST /sign` is limited to 1 MB, it has to be a JSON object with known fields only, `to_be_signed_b64` has to be strict base64 of at most 64 KB, `doc_sha256` has to be exactly 64 hexadecimal characters and `doc_name` has to be a short printable string. |
| 5 | Every signature is confirmed by a human | The agent prints the document name and the SHA-256 on its terminal and waits for `SIGN` (or `y`).  Only then it asks for the PIN of the token (with `getpass`, so it is not echoed).  A cancelled confirmation aborts **without** signing (409), a wrong PIN fails cleanly (401).  The PIN never travels through the HTTP layer, never appears in a URL and never in a log line. |
| 6 | The private key never leaves the token | The signature is created by the token (`CKM_SHA256_RSA_PKCS`), only the raw signature bytes are returned.  The agent has no code path that exports a private key. |
| 7 | No secrets in the log | The log contains the document name, the SHA-256, the certificate and the size of the signature - never the PIN and never the bytes that have to be signed. |
| 8 | The agent cannot be flooded | At most 30 signing operations per minute (`AGENT_RATE_LIMIT_PER_MINUTE`), the other endpoints are not rate limited. |

### The trust chain

* The **browser** may ask the agent to sign.  It may *not* decide *what* is
  signed without the operator and it may *not* supply the PIN (in the default
  mode).
* The **agent** shows the operator what is going to be signed and asks for the
  confirmation and the PIN.  It trusts nothing else.
* The **token** holds the private key and produces the signature.
* The **backend** validates the certificate chain, the key usage and the
  signature before it embeds it into the PDF.

The agent does **not** check whether the certificate is trusted
(`"trusted": false` in `/certificates`) - that is the job of the backend,
which knows the trust store of the platform.

---

## 3. Running it on the host

### Requirements

* Python 3.12 (the standard library is enough for the HTTP layer)
* `cryptography` (the TLS certificate, the X.509 helpers)
* `python-pkcs11` (the binding of the token - JDK 21 removed `SunPKCS11` and
  there is no maintained Java binding)

```bash
cd agent
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements.txt
```

### Start it

```bash
# with a real token
AGENT_PKCS11_MODULE=/usr/lib/softhsm/libsofthsm2.so \
AGENT_TOKEN_LABEL=intern-dsc \
./scripts/run-agent.sh

# without a token (development only, a software key is used)
./scripts/run-agent.sh --mock
```

On Windows:

```powershell
cd agent
py -3.12 -m pip install -r requirements.txt
$env:AGENT_PKCS11_MODULE = "C:\Program Files\OpenSC Project\PKCS11\opensc-pkcs11.dll"
$env:AGENT_TOKEN_LABEL  = "intern-dsc"
py -3.12 agent.py
```

The agent prints what it is doing:

```
  dastakhat-dsc-agent 1.0.0
  URL             : https://127.0.0.1:51515
  TLS certificate : ...\agent\state\tls\agent-cert.pem
  TLS key         : ...\agent\state\tls\agent-key.pem (never leave this machine)
  Allowed origins : http://localhost:5173, http://127.0.0.1:5173
  No origin header: rejected
  Token           : present
  PIN             : read from this terminal
  Rate limit      : 30 signatures per minute
```

### Try it out

```bash
curl -k -H "Origin: http://localhost:5173" https://127.0.0.1:51515/health
curl -k -H "Origin: http://localhost:5173" https://127.0.0.1:51515/certificates
```

The frontend expects the agent under `https://127.0.0.1:51515`
(`frontend/src/api/dscAgent.ts`), that is the default port.

---

## 4. The development token (SoftHSM2)

SoftHSM2 is a software PKCS#11 token - it replaces a USB token during the
development.  Everything it creates is **test data**.

> **The PINs of the development token are published test values:**
> the user PIN is `123456`, the security officer PIN is `12345678`.
> They are written in `scripts/softhsm-init.sh` and in this README on purpose.
> They must never be used on anything but a development machine.

```bash
cd agent

# 1. the token, the key pair inside the token and the exported public key
./scripts/softhsm-init.sh init

# 2. a certificate for that public key, issued by the TEST root CA of the backend
./scripts/softhsm-init.sh issue state/spki.der state/dsc-signer-cert.pem

# 3. the certificate into the token (same id, same label as the key)
./scripts/softhsm-init.sh import state/dsc-signer-cert.pem
```

or simply:

```bash
./scripts/softhsm-init.sh all
```

The script uses

* `softhsm2-util --init-token --free --label intern-dsc --pin 123456 --so-pin 12345678`
  for the token,
* `pkcs11-tool --keypairgen --key-type rsa:2048 --label dsc-signer --id 01`
  for the key pair **inside** the token,
* `pkcs11-tool --read-object --type pubkey` for the exported public key
  (converted into the X.509 `SubjectPublicKeyInfo` the backend expects),
* `pkcs11-tool --write-object --type cert --id 01 --label dsc-signer` for the
  certificate.

Step 2 calls the tool of the backend:

```bash
cd backend
mvn -q exec:java \
  -Dexec.mainClass=com.dastakhat.devpki.DevPkiTool \
  -Dexec.args="issue-dsc ../agent/state/spki.der ../agent/state/dsc-signer-cert.pem ../devpki/out/platform-ca.p12 changeit"
```

The repository already contains a **TEST** DSC certificate
(`devpki/out/dsc-signer-cert.pem`, issued by the TEST root CA with the key
usage `digitalSignature` + `nonRepudiation`) and the matching public key
(`devpki/out/dsc-signer-pubkey.der`).  That certificate belongs to the key in
`devpki/out/dsc-signer.p12`; it can be written into the token with
`./scripts/softhsm-init.sh import ../devpki/out/dsc-signer-cert.pem`, but the
signature will only verify if the token holds the matching private key.  For a
token generated key pair, issue a new certificate with the `issue` step above.

---

## 5. Running it in Docker

```bash
docker build -t dastakhat-dsc-agent agent/

docker run --rm -it \
  -p 127.0.0.1:51515:51515 \
  -v dastakhat-agent-state:/state \
  dastakhat-dsc-agent
```

* `-it` is **mandatory**, the agent reads the confirmation and the PIN from the
  terminal of the container.
* The port is published on the loopback interface of the host
  (`127.0.0.1:51515:51515`) - never publish it as `0.0.0.0:51515:51515`.
* The image sets `AGENT_ALLOW_NON_LOOPBACK=true` because inside a container
  `127.0.0.1` is the container itself; the isolation is done by the published
  port.

The token of the container is prepared in three steps - the certificate is
issued by the backend on the host, because the Maven build is not part of the
image.  The volume `dastakhat-agent-state` is a directory of the host, the
paths of the steps below are the paths **on the host**:

```bash
# 1. the token and the key pair inside the token (the state is a volume)
docker run --rm -v dastakhat-agent-state:/state \
  --entrypoint sh dastakhat-dsc-agent -c "./scripts/softhsm-init.sh init"

# 2. the certificate for the exported public key, issued by the TEST root CA
cd backend
mvn -q exec:java \
  -Dexec.mainClass=com.dastakhat.devpki.DevPkiTool \
  -Dexec.args="issue-dsc <host-path>/spki.der <host-path>/dsc-signer-cert.pem ../devpki/out/platform-ca.p12 changeit"

# 3. the certificate into the token
docker run --rm -v dastakhat-agent-state:/state \
  --entrypoint sh dastakhat-dsc-agent -c "./scripts/softhsm-init.sh import /state/dsc-signer-cert.pem"
```

---

## 6. Moving to a real USB token

Nothing but the path of the PKCS#11 module changes:

```bash
# OpenSC (the driver of most USB tokens)
AGENT_PKCS11_MODULE=/usr/lib/x86_64-linux-gnu/opensc-pkcs11.so \
AGENT_TOKEN_LABEL="My DSC Token" \
./scripts/run-agent.sh

# a vendor specific module
AGENT_PKCS11_MODULE=/opt/vendor/lib/libpkcs11.so ./scripts/run-agent.sh
```

On Windows the module of OpenSC is
`C:\Program Files\OpenSC Project\PKCS11\opensc-pkcs11.dll`.

The agent uses the standards of PKCS#11:

* the certificate objects of the token (`CKA_CLASS = CKO_CERTIFICATE`),
* the private key that belongs to the chosen certificate (matched by `CKA_ID`,
  otherwise by the label),
* `CKM_SHA256_RSA_PKCS` for the signature - exactly the `SHA256withRSA` the
  backend verifies the signature with.

---

## 7. Known limitations of the browser

### The self signed certificate has to be trusted

The agent uses a self signed certificate for `127.0.0.1`.  The browser shows a
warning for it and the `fetch()` of the frontend fails until the certificate
is trusted.  Two ways to get rid of the warning:

* **`mkcert`** (recommended for the development):

  ```bash
  mkcert -install                     # install the local root CA
  mkcert -cert-file agent-cert.pem -key-file agent-key.pem 127.0.0.1 localhost
  export AGENT_TLS_CERT=$PWD/agent-cert.pem
  export AGENT_TLS_KEY=$PWD/agent-key.pem
  ./scripts/run-agent.sh
  ```

* **the trust store of the operating system**: import `agent/state/tls/agent-cert.pem`
  into the trust store of the machine (on Windows into
  `certmgr` → *Trusted Root Certification Authorities*).

`AGENT_TLS_CERT` and `AGENT_TLS_KEY` always win over the generated
certificate, and the key material is never committed (`agent/.gitignore`
ignores `state/`, `*.pem` and `*.key`).

### "Local Network Access" in Chrome 142 and newer

Chrome 142 and newer ask the user for the permission **"Local network
access"** when a page of a public address talks to a device of the local
network.  If the frontend is opened as `http://localhost:5173` both addresses
are local and no permission is needed.  If the frontend is opened with the
address of the machine in the network (`http://192.168.0.10:5173`), Chrome
shows the permission dialog for `https://127.0.0.1:51515` - the signing works
after the permission was granted.  The agent answers the preflight of that
mechanism with `Access-Control-Allow-Private-Network: true`.

### The PIN of the frontend

The current frontend (`DscSigningPanel.vue`) asks the operator for the PIN in
the browser and sends it to the agent.  In the default mode
(`AGENT_PIN_SOURCE=terminal`) the agent **ignores** that field and asks for the
PIN on its own terminal - the browser never learns the PIN of the token and a
program on the machine cannot use the browser to sign something.

For a legacy installation the old behaviour can be switched on with
`AGENT_PIN_SOURCE=body`; then the PIN of the request body is used and the
terminal is not asked for it.  **This is less secure** (the PIN travels
through the browser and through the TLS connection of the agent) and is only
meant for the transition.

---

## 8. The configuration

| Variable | Default | Meaning |
|---|---|---|
| `AGENT_HOST` | `127.0.0.1` | The interface of the agent.  A non loopback address is refused. |
| `AGENT_PORT` | `51515` | The port of the agent (the frontend expects 51515). |
| `AGENT_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | The origins that may use the agent. |
| `AGENT_ALLOW_NO_ORIGIN` | `false` | Also accept requests without an `Origin` header (curl). |
| `AGENT_ALLOW_NON_LOOPBACK` | `false` | Allow a non loopback address (only useful in a container). |
| `AGENT_MAX_BODY_BYTES` | `1048576` | The maximum size of the body of `POST /sign`. |
| `AGENT_MAX_TO_BE_SIGNED_BYTES` | `65536` | The maximum size of the bytes that are signed. |
| `AGENT_RATE_LIMIT_PER_MINUTE` | `30` | The maximum number of signing operations per minute. |
| `AGENT_STATE_DIR` | `agent/state` | Where the TLS material and the mock key are stored (git ignored). |
| `AGENT_TLS_CERT` / `AGENT_TLS_KEY` | – | An existing certificate and key, e.g. from `mkcert`. |
| `AGENT_PKCS11_MODULE` | – | The path of the PKCS#11 module of the token. |
| `AGENT_TOKEN_LABEL` | – | The label of the token (the first one is used when it is empty). |
| `AGENT_PIN_SOURCE` | `terminal` | `terminal` (secure) or `body` (legacy). |
| `AGENT_MOCK` | `false` | Use a software key instead of a token. |
| `AGENT_CONFIRM_WORD` | `SIGN` | The word the operator has to type. |
| `AGENT_REQUIRE_DOC_METADATA` | `false` | Make `doc_name` and `doc_sha256` mandatory. |
| `AGENT_LOG_LEVEL` | `INFO` | The log level of the agent. |

---

## 9. The tests

The tests need **no token** - the PKCS#11 layer and the dialogue with the
operator are replaced by test doubles, the HTTP layer, the TLS layer, the
validation and the rate limiter are the real ones.

```bash
cd agent
python -m pip install -r requirements.txt
python -m pytest -q
```

The suite covers the request validation, the origin allowlist, the limit of the
body, the validation of the hexadecimal hash, the rate limiter, the health and
the certificate endpoint and - most important - that **a signing that was not
confirmed on the terminal never reaches the token**.

---

## 10. The files

```
agent/
├── agent.py                 the entry point (python agent.py --help)
├── dscagent/
│   ├── config.py            the configuration from the environment
│   ├── console.py           the dialogue with the operator (confirmation + PIN)
│   ├── errors.py            the errors with their status code
│   ├── security.py          the origin allowlist, the rate limiter, the TLS material
│   ├── server.py            the HTTPS server (http.server + ssl)
│   ├── service.py           the order of the steps of a signing
│   ├── tokens.py            the PKCS#11 layer and the software mock
│   └── validation.py        the strict validation of the request
├── scripts/
│   ├── run-agent.sh         starts the agent
│   └── softhsm-init.sh      creates the development token
├── tests/                   the pytest suite (no token needed)
├── Dockerfile               python:3.12-slim + softhsm2 + opensc
├── requirements.txt
└── README.md
```
