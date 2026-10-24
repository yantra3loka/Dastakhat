# End to end tests (Playwright)

Browser tests of Dastakhat. They drive the real SPA in Chromium against the
local development stack - nothing is stubbed on the frontend or backend side.

## Run them

Prerequisites: the local stack is up (see the root `README.md`), i.e.

```bash
docker compose up -d db mailpit mock-esp   # database, test mailbox, MOCK eSign provider
# backend on :8080 (exec jar or `npm run ...`), frontend dev server on :5173
#   (Playwright starts it for you and reuses it when it already runs)
```

Commands (from the repository root):

```bash
npm run test:e2e          # run all specs headless
npm run test:e2e:headed   # run them in a visible browser
npm run test:e2e:report   # open the HTML report of the last run
```

The first run needs the browser once: `npm run install:e2e`.

## Report and traces

Every run writes an HTML report and, for failed tests, a screenshot and a
trace to:

```text
e2e/artifacts/html-report/index.html   # open directly, or: npm run test:e2e:report
e2e/artifacts/results/                 # screenshots + traces of failures
```

## What is mocked, what is real

| Part | Real / mocked |
| --- | --- |
| Frontend, backend, PostgreSQL, PDF validation, sealing, audit chain | **real** |
| Outgoing mail | **Mailpit** (`docker compose`, REST API on `:8025`) - no mail ever leaves the machine |
| Aadhaar eSign provider | **`mock-esp`** service - a CCA style simulator: no UIDAI contact, no Aadhaar number, fixed test OTP `123456`, signatures come from the TEST MOCK CA |
| DSC signing | **not automated** - it needs a USB token on the host; covered by `DscSigningIntegrationTest` (backend) and the manual `dsc-demo.ps1` |
| Account | the seeded development sender `sender@example.test` / `ChangeMe!123` only |

Test data: every run creates its own envelopes with a `Date.now()` stamp and
recipient addresses like `signer-<stamp>@example.test`, so runs never depend
on each other. No production data and no real signing credential is used.

## Note on the sign in rate limit

The backend allows 10 sign in attempts per 5 minutes per IP
(`AuthService`). The suite therefore signs in **once** and reuses the saved
session (`e2e/.auth/session.json`, gitignored) for every spec that does not
test the login itself - `login.spec.ts` always signs in for real. The saved
session is validated with `GET /auth/me` first; when it is stale the helper
signs in again.

## Specs and the test matrix

| Spec | Matrix | Covers |
| --- | --- | --- |
| `login.spec.ts` | A | form, development hint, wrong password error, successful sign in, protected pages |
| `dashboard.spec.ts` | A, B | dashboard elements, main navigation, logout, re-authentication |
| `documents.spec.ts` | B, C | document list, status filter, upload form, non-PDF rejection |
| `upload-validation.spec.ts` | C | valid PDF, 10 MB limit (413), JavaScript (422), Launch (422), genuine embedded attachment (422), C2PA Content Credentials manifest (accepted) |
| `full-flow.spec.ts` | D | upload → recipient → field → send → sign via Mailpit link → sealed PDF → audit chain verification |
| `esign-mock.spec.ts` | D | signing with the MOCK Aadhaar eSign provider (skips itself when `mock-esp` is not running) |
| `signer.spec.ts` | E | friendly errors for unknown sign / download tokens |
| `errors.spec.ts` | E | 404 page, clean console, no 5xx answers, loading state of the signing page |

Fixtures (`e2e/fixtures/`): `offer-letter.pdf` (valid), plus the hand written
`javascript.pdf`, `launch-action.pdf` and `embedded-attachment.pdf` (embedded
`tool.exe`) used to prove the backend rejects active content, and
`c2pa-credentials.pdf` (the C2PA "Content Credentials" provenance manifest that
Acrobat attaches) which has to be **accepted**. The oversized file for the 10 MB
test is generated at runtime in the spec. PDF security checks are **not**
relaxed anywhere for these tests.
