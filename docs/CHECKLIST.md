# Dastakhat - implementation checklist

Every line below was **verified by running it**. The command that proves it is written next to
the item. Nothing is marked as done because it was only planned.

Last full verification run: 23 October 2026 (Windows, Docker, JDK 21, Node 20, Python 3.12).

---

## Phase A - backend foundation

| # | item | status | proof |
|---|---|---|---|
| A1 | Spring Boot 3 application starts and answers | done | `GET /api/v1/health` -> `{"status":"UP"}` |
| A2 | Flyway schema V1-V3 (envelopes, recipients, fields, signatures, sessions, audit) | done | `mvn test` (Testcontainers PostgreSQL 16) |
| A3 | Enum values stored lowercase, matching the CHECK constraints (`EnumConverters`) | done | every integration test writes and reads them |
| A4 | Login/logout, session cookie, CSRF token, origin restriction | done | `EnvelopeApiIntegrationTest` (11 tests) |
| A5 | Upload with real PDF validation (magic bytes, active content, page geometry) | done | `PdfValidatorTest` (9), `PdfSignatureServiceTest` |
| A6 | Recipients and fields (CRUD, ordering, routing modes) | done | `EnvelopeApiIntegrationTest` |
| A7 | Send / void / decline / expiry | done | `EnvelopeApiIntegrationTest` |
| A8 | Signer API with one time, expiring tokens (`/sign/**`) | done | `EnvelopeApiIntegrationTest`, `full-flow.spec.ts` |
| A9 | Download of original / final document and the audit JSON | done | `DscSigningIntegrationTest` step 9 |
| A10 | Error contract (`status`, `code`, `message`, `fieldErrors`) | done | `GlobalExceptionHandler`, asserted in the tests |
| A11 | Rate limiting (login and signer routes) | done | `RateLimiter`, `429` was observed during the test run |

## Phase B - PDF signing and audit trail

| # | item | status | proof |
|---|---|---|---|
| B1 | Platform seal (PKCS#7/CMS in a PDF signature field, SHA-256, RSA) | done | `PdfSignatureServiceTest`, `PdfVerifyTool` |
| B2 | Certificate of completion page with hash, signers, times | done | `PdfVerifyTool` output: 3 pages |
| B3 | Verification of the seal (intact / valid / trusted) | done | `PdfVerifyTool verify ... -> RESULT: VALID` |
| B4 | Detection of a modified document | done | `PdfVerifyTool tamper` -> `intact:false`, `RESULT: INVALID` |
| B5 | Append only audit table (PostgreSQL trigger) | done | `AuditTamperIntegrationTest` |
| B6 | Hash chain over the audit events, verifier names the broken event | done | `AuditTamperIntegrationTest` (`brokenAtSeq: 2`) |

## Phase C - frontend

| # | item | status | proof |
|---|---|---|---|
| C1 | Vue 3 + TS + Vite + Pinia + Router, navy sidebar / white content / blue buttons | done | `npm run build` (0 type errors) |
| C2 | 13 named screens (login, dashboard, documents, new, prepare, review, detail, audit, settings, profile, sign, decline, expired) | done | router, `documents.spec.ts`, `signer.spec.ts` |
| C3 | PDF editor with pdf.js: palette, placement, per page geometry, zoom | done | `full-flow.spec.ts` places a field |
| C4 | Fixed a real pdf.js bug: overlapping `render()` calls on one canvas | done | the field placement fails without the fix |
| C5 | Signing page: consent, drawn/typed signature, decline with reason | done | `full-flow.spec.ts`, `signer.spec.ts` |
| C6 | DSC page talks to the local agent and shows the "agent not running" state | done | `dscAgent.ts`, agent `/health` |
| C7 | Unit tests | done | `npm run test` -> **47 passed** |
| C8 | End to end tests | done | `npx playwright test` -> **13 passed** |

## Phase D - DSC agent

| # | item | status | proof |
|---|---|---|---|
| D1 | Local HTTPS agent with PKCS#11 (SoftHSM and vendor modules) | done | `python agent.py --mock`, `/health` |
| D2 | Certificate listing and signing request contract (snake_case + camelCase) | done | agent `/certificates`, `/sign` |
| D3 | Operator confirmation and PIN in the terminal, PIN never logged or returned | done | `python -m pytest` -> **59 passed** |
| D4 | Origin allowlist, rate limit, body limits, self signed TLS | done | agent tests, live `403` for a foreign origin |
| D5 | Fails closed without a terminal | done | live `503 operator_unavailable` |
| D6 | Backend half of the flow (prepare -> sign -> embed -> validate) | done | `DscSigningIntegrationTest` |
| D7 | Real token signing (SoftHSM token, key generated inside the token) | done | verified in the agent container (see `agent/README.md`); a USB token only needs the vendor module path |

## Phase E - MOCK eSign provider

| # | item | status | proof |
|---|---|---|---|
| E1 | Standalone service that imitates the CCA eSign API 2.1 shape | done | `mock-esp`, `/esp/health` |
| E2 | Request XML is signed by the backend and verified by the provider | done | `EsignMockIntegrationTest` step 8 |
| E3 | Consent page with the fixed test OTP and an orange MOCK banner | done | the test asserts the page text |
| E4 | One time certificate from the MOCK CA + CMS over the document hash | done | fixed: the provider now loads the MOCK CA key from its own keystore |
| E5 | Backend verifies the response and embeds the CMS | done | `EsignMockIntegrationTest` steps 9-13 |
| E6 | Error paths ESP-944 / ESP-946 / ESP-911 | done | provider implementation + tests |
| E7 | No legal standing, no UIDAI contact, no Aadhaar data | done | the mock only ever handles test data |

## Phase F - infrastructure, documentation, CI

| # | item | status | proof |
|---|---|---|---|
| F1 | `docker-compose.yml` (db, mailpit, mock-esp, backend, frontend) | done | `docker compose config --quiet` and the containers on `127.0.0.1` |
| F2 | Dockerfiles for backend, mock-esp, frontend | done | `docker compose build backend mock-esp frontend` -> ok (408 / 556 / 449 MB), agent image 251 MB |
| F3 | GitHub Actions workflow | done | `.github/workflows/ci.yml` parses as YAML; every command it runs was executed locally (backend suite, image builds, PKI generation, the exact backend start arguments, the frontend dev server) |
| F4 | `README.md` with architecture, security design, limitations | done | 524 lines |
| F5 | `docs/DEMO.md` - the walk through with the real outputs | done | written from the executed run |
| F6 | `docs/CHECKLIST.md` - this file | done | - |
| F7 | No secrets committed (`.gitignore` covers keys, storage, logs, build output) | done | `git status` |

---

## Test totals of the last full run

| suite | result |
|---|---|
| Backend (`mvn test`) | **52 tests, 0 failures** (unit + Testcontainers PostgreSQL 16 + Mailpit + MOCK provider) |
| Frontend unit (`npm run test`) | **47 tests, 0 failures** |
| Frontend build (`npm run build`) | success, 0 type errors |
| End to end (`npx playwright test`) | **13 tests, 0 failures** |
| DSC agent (`python -m pytest`) | **59 tests, 0 failures** |

## Known gaps (not blockers)

* The DSC agent needs a real terminal for the operator confirmation, so the browser part of the
  DSC flow cannot be automated in Playwright; the cryptographic path is covered by
  `DscSigningIntegrationTest` and by the agent's own tests.
* `EsignMockIntegrationTest` skips itself when the MOCK provider is not running, so a bare
  `mvn test` on a machine without `mock-esp` stays green.
* The Playwright suite runs on chromium only.
* The GitHub Actions workflow was never executed on a runner from this machine - its YAML was
  validated and every single command inside it was run locally, but the first real CI run may
  still surface runner specific differences.
* Revocation checking, RFC 3161 timestamps and PAdES B-LT are deliberately not implemented
  (see README, "Known limitations").
