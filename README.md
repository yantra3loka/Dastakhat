# Dastakhat — a self hosted mini e-signature platform

Dastakhat lets a sender upload a PDF, place signature fields, send secure links and collect
signatures. When every signer has finished, the platform produces one flattened PDF that
carries a certificate of completion, a cryptographic platform seal and a tamper evident audit
trail.

> **Read this before you use it**
>
> * A signature that is drawn or typed is **not** a cryptographic digital signature and it is
>   **not** legally equivalent to a DSC or to Aadhaar eSign. It is evidence of agreement.
> * The "Aadhaar eSign" integration in this repository is a **MOCK**. It never contacts UIDAI
>   or a real eSign service provider, it never asks for an Aadhaar number and it has no legal
>   standing.
> * All certificates that are used in development are **TEST** certificates of a throwaway
>   test CA. They are not trusted by any browser or PDF reader.
> * Get legal advice before you use any of this with real documents.

---

## Table of contents

1. [What it does](#1-what-it-does)
2. [Architecture](#2-architecture)
3. [Technology stack](#3-technology-stack)
4. [Repository layout](#4-repository-layout)
5. [Database](#5-database)
6. [Getting started](#6-getting-started)
7. [Test commands](#7-test-commands)
8. [Docker Compose](#8-docker-compose)
9. [The DSC agent](#9-the-dsc-agent)
10. [The MOCK eSign provider](#10-the-mock-esign-provider)
11. [Security design](#11-security-design)
12. [API documentation](#12-api-documentation)
13. [Demonstration](#13-demonstration)
14. [Known limitations](#14-known-limitations)

---

## 1. What it does

| Stage | Feature | State |
|---|---|---|
| 1 | Electronic signing (draw / type), sequential and parallel routing, expiring single use links | implemented |
| 1 | Flattened final PDF, certificate of completion, PAdES platform seal, hash chained audit trail | implemented |
| 2 | DSC signing through a local PKCS#11 agent (SoftHSM2 in development) | implemented, agent needs a PKCS#11 token |
| 3 | Aadhaar eSign | **MOCK only** |

---

## 2. Architecture

```
                      ┌──────────────────────────────────────────────┐
   Browser (SPA)      │  Vue 3 + TypeScript + Vite + Pinia          │
                      │  pdf.js rendering, drag & drop field editor   │
                      └───────┬───────────────────────┬──────────────┘
                              │ /api/v1 (session +    │ https://127.0.0.1:51515
                              │ CSRF, same origin)    │ (PKCS#11 agent)
                              ▼                       ▼
                      ┌────────────────────┐   ┌──────────────────────┐
                      │  Spring Boot 3.5   │   │  Local DSC agent     │
                      │  Java 21           │   │  (Python, PKCS#11)   │
                      │  REST API          │   │  SoftHSM2 / USB DSC  │
                      └──────┬───────┬─────┘   └──────────────────────┘
                             │       │
              ┌──────────────┘       └───────────────┐
              ▼                                      ▼
      ┌───────────────┐                    ┌──────────────────────┐
      │ PostgreSQL 16 │                    │ MOCK eSign provider  │
      │ Flyway        │                    │ (Spring Boot, :9100) │
      └───────────────┘                    └──────────┬───────────┘
              ▲                                          │ signed XML response
              │                                          ▼
      ┌───────┴────────┐                         ┌──────────────────┐
      │  storage/      │                         │  backend         │
      │  (PDFs, PNGs)  │◄────────────────────────│  /esign/callback │
      └────────────────┘                         └──────────────────┘

      ┌───────────────────────────────────────────────────────────┐
      │  Mailpit (:1025 SMTP, :8025 UI + REST API) - dev only     │
      └───────────────────────────────────────────────────────────┘
```

**The three signing methods end up in one document.**

```
  electronic signature  ──┐
  (drawn / typed image)   │
                          ├──► flatten ──► DSC signature ──► MOCK eSign ──► certificate ──► platform seal
  DSC signature ──────────┤    (once)      (incremental)      (incremental)   (append)      (incremental)
  MOCK eSign ─────────────┘
```

Flattening happens **before** the first cryptographic signature. Every later step is an
incremental update, so the signatures that were added earlier stay valid. This is why the
DSC and the MOCK eSign recipients are routed after the electronic ones.

---

## 3. Technology stack

| Part | Choice | Notes |
|---|---|---|
| Backend | Java 21, Spring Boot 3.5, Maven | the only API server |
| Persistence | Spring Data JPA, Hibernate, Flyway, PostgreSQL 16 | Flyway owns the schema |
| Security | Spring Security (session + CSRF), BCrypt | see [security design](#11-security-design) |
| PDF | Apache PDFBox 3.0.8 | rendering, stamping, flattening, incremental updates |
| Crypto | BouncyCastle 1.86 | CMS / PKCS#7, PAdES, XML-DSig, certificate chains |
| Frontend | Vue 3, TypeScript, Vite, Pinia, Vue Router, pdf.js | |
| Tests | JUnit 5, Mockito, Spring Boot Test, Testcontainers, Vitest, Playwright | |
| E-mail | Mailpit (SMTP) | nothing leaves the machine in development |
| DSC agent | Python + `python-pkcs11` + SoftHSM2 | see [DSC agent](#9-the-dsc-agent) |

### Library substitutions (Python → Java)

The original brief was written for Python. The backend is Java, so the cryptography was
translated:

| Brief (Python) | This project (Java) | Why |
|---|---|---|
| `pyHanko` | Apache PDFBox 3.0.8 + BouncyCastle 1.86 | PDFBox provides `saveIncrementalForExternalSigning()`: it reserves the signature, computes the byte range and writes the placeholder. BouncyCastle builds the detached CMS and verifies it. Both are Apache/BSD licensed and actively maintained. |
| `reportlab` + `pypdf` for stamping | Apache PDFBox `PDPageContentStream` | PDFBox writes directly into the page content stream, so no intermediate renderer is needed. |
| `signxml` + `lxml` | JDK `javax.xml.crypto.dsig` | XML-DSig is part of the JDK, no dependency needed. |
| `python-pkcs11` in the agent | `python-pkcs11` in the agent | **Kept as Python on purpose**: JDK 21 removed the built in `SunPKCS11` provider and there is no maintained Java PKCS#11 binding. The agent is an isolated local helper, the backend stays Java. |
| `python-jose` / `passlib` | BouncyCastle + `BCryptPasswordEncoder` | |

---

## 4. Repository layout

```
dastakhat/
├── backend/                 Spring Boot API server (Java 21)
│   └── src/main/java/com/dastakhat/
│       ├── audit/           append only hash chain
│       ├── auth/ users/     sender accounts and sessions
│       ├── common/          errors, hashing, canonical JSON, rate limiter
│       ├── config/          settings and the Spring Security configuration
│       ├── devpki/          generator for the TEST certificates
│       ├── documents/       upload validation, safe storage, downloads
│       ├── dsc/             Stage 2: deferred signing with a DSC
│       ├── envelopes/       the sender workflow
│       ├── esignmock/       Stage 3: the MOCK eSign flow
│       ├── fields/          placed fields (page fractions)
│       ├── mail/            signing links and notifications
│       ├── pki/             CMS, XML-DSig, certificate chains, seal key
│       ├── recipients/      routing and signing tokens
│       ├── security/        the authenticated sender
│       ├── signing/         stamping, flattening, sealing, certificates
│       └── system/          health, readiness, development bootstrap
├── frontend/                Vue 3 single page application
├── agent/                   the local DSC signing agent (Python, PKCS#11)
├── mock-esp/                the MOCK eSign provider (Spring Boot)
├── devpki/                  the generated TEST certificates (git ignored)
├── contract/openapi.yaml    the API contract
├── e2e/                     the Playwright end to end tests, the DSC demo script
├── docs/                    the walk through, the checklist and the decision log
├── docker-compose.yml       the local stack
└── .env.example             every setting with its development default
```

---

## 5. Database

```
 users ──┬──< envelopes ──┬──< recipients ──┬──< fields
         │                 │                 │
         │                 │                 └──< signatures
         │                 ├──< documents
         │                 └──< audit_events
         │
         └──< signing_sessions (belongs to a recipient)
```

| Table | Purpose | Important rules |
|---|---|---|
| `users` | the senders | BCrypt hash, unique e-mail |
| `envelopes` | one document in a workflow | status and routing are constrained, only drafts are editable |
| `documents` | original / working / final | random storage key, SHA-256, unique per envelope and kind |
| `recipients` | who has to sign | **only the hash** of the signing token is stored, the used token is kept separately so that a replay answers "410" |
| `fields` | the placed fields | positions are page fractions (0..1), a check constraint keeps them inside the page |
| `signatures` | the captured signatures | drawn/typed images or the CMS of a DSC / MOCK eSign |
| `signing_sessions` | the deferred signing state | expires after 10 minutes, single use |
| `audit_events` | the append only trail | `hash = SHA-256(prev_hash + canonical_json(event))`, a trigger rejects `UPDATE` and `DELETE` |

The ER diagram in detail:

```
┌────────────────────────┐          ┌────────────────────────────┐
│ users                  │ 1      n │ envelopes                  │
│  id            uuid pk │──────────│  id              uuid pk   │
│  email         unique  │          │  owner_id        uuid fk   │
│  name                  │          │  title                     │
│  password_hash         │          │  status  (draft|sent|…)    │
│  role        'sender'  │          │  routing (sequential|…)    │
│  enabled               │          │  expires_at                │
│  created_at            │          │  created_at/updated_at     │
└────────────────────────┘          │  sent_at/completed_at      │
                                    │  original_sha256           │
                                    │  final_sha256              │
                                    │  pre_seal_sha256           │
                                    │  pages                     │
                                    └────────────┬───────────────┘
                                                 │ 1
                          ┌──────────────────────┼──────────────────────┐
                          │ n                    │ n                    │ n
             ┌────────────▼──────────┐  ┌────────▼─────────┐  ┌────────▼────────────┐
             │ recipients            │  │ documents        │  │ audit_events        │
             │  token_hash  unique   │  │  kind            │  │  seq   unique per   │
             │  used_token_hash      │  │  storage_key     │  │       envelope      │
             │  token_expires_at     │  │  sha256          │  │  type, actor, ip    │
             │  method               │  │  pages, size     │  │  payload (json)     │
             │  routing_order        │  │  filename        │  │  prev_hash, hash    │
             │  status               │  └──────────────────┘  │  at                 │
             │  signed_at/viewed_at  │                         └─────────────────────┘
             └──────┬───────────────┘
                    │ 1
        ┌───────────┴────────────┐
        │ n                      │ n
 ┌──────▼───────────┐   ┌────────▼──────────┐      ┌────────────────────┐
 │ fields           │   │ signatures        │      │ signing_sessions   │
 │  type, page      │   │  kind             │      │  kind (dsc|mock)   │
 │  x,y,w,h         │   │  image_key        │      │  prepared_key      │
 │  required, value │   │  signer_cert_pem  │      │  document_digest   │
 └──────────────────┘   │  cms_sha256       │      │  signed_attrs_der  │
                        └───────────────────┘      │  status, expires   │
                                                   └────────────────────┘
```

---

## 6. Getting started

### Prerequisites

| Tool | Version |
|---|---|
| JDK | 21 LTS |
| Maven | 3.9+ |
| Node.js | 20 LTS or newer |
| Docker | with Compose (for PostgreSQL and Mailpit) |
| Python | 3.11+ (only for the DSC agent) |

### 6.1 Start the infrastructure

```bash
cd dastakhat
cp .env.example .env          # adjust if you like, the defaults work locally
docker compose up -d db mailpit
```

PostgreSQL is then on `127.0.0.1:5432`, Mailpit's web UI on <http://localhost:8025>.

### 6.2 Create the TEST certificates

```bash
cd backend
mvn -q exec:java -Dexec.mainClass=com.dastakhat.devpki.DevPkiTool -Dexec.args="all ../devpki/out"
```

This writes the throwaway test CA, the platform seal certificate, a DSC signer certificate
and the MOCK eSign certificates into `devpki/out/` (git ignored). **They are test data.**

### 6.3 Start the backend

```bash
cd backend
mvn spring-boot:run
```

The API is on <http://localhost:8080>, the OpenAPI UI on
<http://localhost:8080/swagger-ui.html>. The migrations run automatically, and a development
sender is created:

| | |
|---|---|
| e-mail | `sender@example.test` |
| password | `ChangeMe!123` |

Change both with `DASTAKHAT_SEED_EMAIL` and `DASTAKHAT_SEED_PASSWORD`, or switch the seed off
completely with `DASTAKHAT_SEED_ENABLED=false`.

### 6.4 Start the frontend

```bash
cd frontend
npm install
npm run dev
```

The SPA is on <http://localhost:5173>. The Vite dev server proxies `/api` to the backend, so
the browser only ever talks to one origin.

### 6.5 (Optional) The MOCK eSign provider

```bash
cd mock-esp
mvn spring-boot:run
```

It listens on <http://localhost:9100>. The backend talks to it through
`MOCK_ESP_BASE_URL`.

The provider signs the one time certificate of a "user" with the MOCK CA key, so it needs two
keystores (both are generated by the devpki tool, both are TEST material):

| setting | default | meaning |
|---|---|---|
| `MOCK_ESP_KEYSTORE` | `../devpki/out/mock-esp.p12` | the key and certificate of the provider itself |
| `MOCK_ESP_CA_KEYSTORE` | `../devpki/out/mock-ca.p12` | the MOCK CA that issues the one time certificate |
| `MOCK_ESP_CA` | `../devpki/out/mock-ca.pem` | the same CA certificate as PEM, for verification |

If the CA key is missing the provider refuses to start the signing path instead of signing with
the wrong key (`/esp/health` then reports `keysReady:false`).

### 6.6 (Optional) The DSC agent

See [the DSC agent](#9-the-dsc-agent).

---

## 7. Test commands

```bash
# Backend: unit tests and the integration tests against a PostgreSQL 16 container
cd backend
mvn test

# Only the fast unit tests
mvn test -Dtest='HashingTest,CanonicalJsonTest,PdfCoordinatesTest,PdfValidatorTest,StorageServiceTest'

# The two signing flows on their own (both need Mailpit; the MOCK test also needs the provider)
mvn test -Dtest='DscSigningIntegrationTest'
mvn test -Dtest='EsignMockIntegrationTest'

# Frontend: type check, production build and the unit tests
cd frontend
npm run build      # vue-tsc type check + vite build
npm run test       # vitest

# End to end (the backend, the frontend and Mailpit have to run)
npm install
npx playwright install chromium
npm run test:e2e

# DSC agent
cd agent
python -m pip install -r requirements.txt
python -m pytest
```

The backend tests need Docker: the integration tests start a PostgreSQL 16 container through
Testcontainers and read the signing links from Mailpit (`localhost:8025`).
`EsignMockIntegrationTest` skips itself when the MOCK provider is not running, so a plain
`mvn test` stays green without it. The current results of every suite are listed in
[`docs/CHECKLIST.md`](docs/CHECKLIST.md).

---

## 8. Docker Compose

```bash
docker compose up -d db mailpit mock-esp          # infrastructure
docker compose --profile app up -d backend frontend # the application
docker compose down                                # stop everything
```

Everything binds to `127.0.0.1`, so nothing is reachable from outside the machine. The DSC
agent is deliberately **not** part of the compose file: it needs direct access to a USB
token and therefore runs on the host.

---

## 9. The DSC agent

The agent is a small local HTTPS server on `https://127.0.0.1:51515` that talks to a
PKCS#11 token.

| Route | Purpose |
|---|---|
| `GET /health` | is the agent running, is a token present |
| `GET /certificates` | the certificates that are on the token (PEM + label + subject) |
| `POST /sign` | signs the given bytes with the token and returns the raw signature |

**Security boundary**

* it only binds to the loopback interface;
* it only accepts requests from an allowlisted origin;
* it validates every request (body size, JSON shape, SHA-256 format);
* it asks the operator to confirm the document name and the SHA-256 **before** every
  signature and asks for the token PIN on its own terminal;
* the PIN never reaches the browser, the backend or a log file;
* the private key never leaves the token, only the signature bytes are returned;
* the signing operations are rate limited.

**Moving to a real token** only changes the PKCS#11 module path (`PKCS11_MODULE`) and the
certificate that the vendor issued. The certificate has to allow `digitalSignature` and
`nonRepudiation`; strict validators reject a certificate without `nonRepudiation`.

See `agent/README.md` for the details.

---

## 10. The MOCK eSign provider

The provider imitates the shape of the CCA eSign API 2.1:

1. the backend signs an XML request (`<Esign ver="2.1">`) that contains the document hash;
2. the provider verifies the XML signature and shows a consent page with a **fixed test OTP**;
3. the provider creates a one time certificate from the MOCK CA, signs the document hash with
   it and answers with a signed `<EsignResp>` that carries the certificate and the CMS;
4. the backend verifies the XML signature, the transaction id and the document hash, embeds the
   CMS and validates it.

The provider signs the one time certificate of a "user" with the MOCK CA key (see the table in
[6.5](#65-optional-the-mock-esign-provider)) and answers with the CMS over the document hash.

Every screen of the flow carries an orange **MOCK** banner, the identity is a fixed test
identity and the OTP is printed on the page. The error paths `ESP-944` (cancelled),
`ESP-946` (expired) and `ESP-911` (the request signature is invalid) are implemented, and the
provider fails loudly when the backend refuses the callback.

The whole exchange is exercised end to end (request XML → OTP page → callback → embedded CMS →
sealed PDF → replay → audit) by `EsignMockIntegrationTest`.

**The mock never contacts UIDAI or a real provider and has no legal standing.**

---

## 11. Security design

### Sessions and CSRF

* The sender session lives in an HttpOnly cookie (`DASTAKHAT_SESSION`), `SameSite=Lax`, and
  `Secure` when `DASTAKHAT_COOKIE_SECURE=true`.
* The password is hashed with BCrypt (cost 12).
* Every state changing request of the sender API needs the CSRF token in the `X-CSRF-TOKEN`
  header. The token is handed out by the login and is also written to the readable
  `XSRF-TOKEN` cookie.
* The login itself and the signer API are exempt from CSRF: the login creates the session and
  the signer API is authorised by the signing token, which is a secret that never leaves the
  recipient.
* The origin of the browser is restricted to the configured frontend origin.

### Signing links

* 256 bit random tokens, only the SHA-256 is stored.
* Single use: after signing, the hash moves to `used_token_hash` and the active hash is
  cleared, so a replay answers "410 - already used" instead of signing again.
* The links expire (7 days by default) and are invalidated when the envelope is voided.
* Unknown tokens answer with a generic 404, so the existence of a link is never revealed.
* The signer routes are rate limited per IP.

### Uploads

* The magic bytes `%PDF-` are checked, never the file name or the content type.
* Maximum 10 MB and 50 pages.
* Encrypted PDFs are rejected.
* Active content (JavaScript, launch actions, embedded files, XFA, …) is detected by walking
  the object graph and rejected.
* The file is stored under a random key outside of any web root; a path is never built from a
  user supplied name.
* The SHA-256 of every document is recorded.

### Audit trail

* Every event is appended with a sequence number, the actor, the IP, the user agent, the
  document hash and the SHA-256 of the previous event.
* The chain is verified on demand and reports the first broken sequence number.
* The database rejects `UPDATE` and `DELETE` on `audit_events` with a trigger.

> The hash chain makes tampering **visible**, it does not make it impossible. A privileged
> database administrator can still rewrite the whole history - that is why the trigger exists
> and why the application role has no `UPDATE` or `DELETE` grant on the table.

### Response headers

`Content-Security-Policy`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer`, `Strict-Transport-Security` and a restrictive
`Cache-Control` on every response.

### What is deliberately **not** claimed

* A drawn or typed signature is not a cryptographic signature.
* The platform seal proves that the document has not changed since the seal was applied. It
  does not prove who signed it - the audit trail and the stamped signatures do.
* The development certificates are not trusted by anybody.

---

## 12. API documentation

* The contract: [`contract/openapi.yaml`](contract/openapi.yaml)
* The generated documentation of the running backend: <http://localhost:8080/swagger-ui.html>
* The raw OpenAPI document: <http://localhost:8080/v3/api-docs>

All routes live under `/api/v1`.

| Area | Routes |
|---|---|
| Auth | `POST /auth/login`, `POST /auth/logout`, `GET /auth/me` |
| Envelopes | `GET /envelopes`, `GET /envelopes/stats`, `POST /envelopes`, `GET/PATCH /envelopes/{id}`, `PUT /envelopes/{id}/recipients`, `PUT /envelopes/{id}/fields`, `POST /envelopes/{id}/send`, `POST /envelopes/{id}/void` |
| Documents | `GET /envelopes/{id}/documents/{kind}`, `GET /download/{token}` |
| Audit | `GET /envelopes/{id}/audit`, `GET /envelopes/{id}/audit/verify` |
| Signer | `GET /sign/{token}`, `GET /sign/{token}/document`, `POST /sign/{token}/consent`, `POST /sign/{token}/complete`, `POST /sign/{token}/decline` |
| DSC | `POST /sign/{token}/dsc/prepare`, `POST /sign/{token}/dsc/finish` |
| MOCK eSign | `POST /sign/{token}/esign/start`, `POST /esign/callback` |
| System | `GET /health`, `GET /ready` |

---

## 13. Demonstration

The full walk through with the real captured output is in [`docs/DEMO.md`](docs/DEMO.md), the
per phase status with the proof command for every item is in
[`docs/CHECKLIST.md`](docs/CHECKLIST.md). In short:

1. `docker compose up -d db mailpit mock-esp`, start the backend and the frontend.
2. Log in as `sender@example.test`.
3. Upload a two page PDF (a renamed `.exe` is rejected with 415).
4. Add three recipients: Asha (Electronic), Ravi (DSC), Meera (Aadhaar eSign **MOCK**).
5. Place a signature field for each of them.
6. Send the envelope. In sequential mode only Asha receives a mail.
7. Open the link from Mailpit (<http://localhost:8025>), draw the signature, finish.
8. Ravi signs with the DSC agent, Meera signs with the MOCK eSign provider.
9. Download the final PDF and the audit JSON.
10. Verify the seal, flip one byte and watch the verification fail, edit one audit row and
    watch the chain verifier name the broken event.

The same steps are scripted for a terminal run: `powershell -File e2e/dsc-demo.ps1` drives the
DSC half (login → upload → recipient → send → Mailpit → `/dsc/prepare` → the agent signs) against
the running stack, and `e2e/tests/full-flow.spec.ts` drives the electronic half in a browser.

---

## 14. Known limitations

* **The DSC flow needs a PKCS#11 token.** SoftHSM2 in a container is the development path; a
  real USB token only needs the vendor module path.
* **The Aadhaar eSign flow is a mock.** A real integration needs a licensed eSign service
  provider (eMudhra, Protean, C-DAC e-Hastakshar or an aggregator) under a commercial
  agreement.
* **The revocation check of the certificate chains is not performed.** The development PKI has
  no CRL distribution points. A production deployment needs revocation checking or PAdES
  B-LT.
* **The audit chain is tamper evident, not tamper proof** (see above).
* **The rate limiter lives in the memory of one node.** A multi node deployment needs a shared
  store.
* **There is no RFC 3161 timestamp.** The seal is not timestamped, so it cannot prove that the
  signature existed before the certificate expired.
* **The typed signature is rendered by the browser** and stored as a PNG. The platform does not
  interpret it.
* **The legal note of the original brief applies**: get advice from a qualified lawyer before
  you rely on any of this.

---

*Dastakhat is an intern project. "Dastakhat" is a working codename - run a trademark and
domain check before any public release.*
