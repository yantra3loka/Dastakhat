# Dastakhat - demonstration

Everything below was executed on a Windows machine with Docker, JDK 21, Node 20 and Python
3.12. No real Aadhaar number, no real certificate and no real credential is used anywhere: the
platform runs against a TEST PKI, the MOCK eSign provider and the DSC agent in mock mode.

The commands are repeated in the same order in which the platform is used.

---

## 0. What has to run

| service | address | how it is started |
|---|---|---|
| PostgreSQL 16 | `127.0.0.1:5432` | `docker compose up -d db` (or the `dastakhat-db` container) |
| Mailpit | <http://localhost:8025> | `docker compose up -d mailpit` |
| MOCK eSign provider | <http://localhost:9100> | `cd mock-esp && mvn spring-boot:run` |
| Backend | <http://localhost:8080> | `cd backend && mvn spring-boot:run` (or the exec jar) |
| Frontend | <http://localhost:5173> | `cd frontend && npm run dev` |
| DSC agent (optional) | <https://127.0.0.1:51515> | `cd agent && python agent.py --mock` |

```bash
cd backend
mvn -q exec:java -Dexec.mainClass=com.dastakhat.devpki.DevPkiTool \
    -Dexec.args="all ../devpki/out"     # the TEST PKI: root CA, seal, DSC, MOCK CA/ASP/ESP
```

The health endpoints answer:

```text
GET /api/v1/health  -> {"status":"UP","service":"dastakhat-backend","version":"1.0.0",...}
GET /esp/health     -> {"status":"UP","service":"dastakhat-mock-esp","mock":true,"keysReady":true}
GET /health (agent) -> {"status":"UP","agent":"dastakhat-dsc-agent","token_present":true,"mock":true}
```

---

## 1. Log in

The development seed user is `sender@example.test` / `ChangeMe!123`. A wrong password shows an
inline error, the sender pages redirect to `/login`, and the login hands out the CSRF token that
every later state changing request carries.

## 2. Upload a document

`/documents/new` → choose `e2e/fixtures/offer-letter.pdf` (2 pages) → **Next**.

* A renamed `.exe` is rejected: the server checks the magic bytes, not the file name (415
  `unsupported_media_type`).
* A PDF with JavaScript or an `/OpenAction` is rejected as active content (422).

## 3. Add the recipients and the fields

Three recipients, one for every signing method:

| name | method | what happens |
|---|---|---|
| Asha HR | Electronic | drawn or typed signature in the browser |
| Ravi Kumar | DSC | the private key stays in the PKCS#11 token (local agent) |
| Meera Iyer | Aadhaar eSign **MOCK** | the MOCK provider issues a one time certificate |

One signature field per recipient is placed on the page: choose the field in the palette, click
the place in the document, adjust it in the properties pane. **Save draft** → **Review & send**.

## 4. Send

The routing is *sequential*: only Asha receives a mail. The envelope moves to `sent`, the audit
trail records `envelope_sent`.

## 5. Asha signs (electronic signature)

The signing link comes from Mailpit (<http://localhost:8025>): open the newest message, copy the
`/sign/<token>` link. The signer does **not** log in.

* the consent checkbox has to be ticked (`consent_recorded` in the audit trail);
* the signature is drawn on a canvas or typed (the typed signature is rendered by the browser);
* **Finish signing** uploads the PNG and the completed hash.

The signer page shows "410 - this link was already used" when the same link is opened again.

## 6. Ravi signs with the DSC agent

The agent has to run on the machine of the signer:

```bash
cd agent
python -m pip install -r requirements.txt
python agent.py --mock                      # development, software key
# or with a real token:
AGENT_PKCS11_MODULE=/usr/lib/softhsm/libsofthsm2.so AGENT_TOKEN_LABEL=intern-dsc ./scripts/run-agent.sh
```

The agent only accepts requests from the origin of the frontend, and **every signing has to be
confirmed in the terminal of the signer** (`Type SIGN to sign`, then the token PIN, without
echo). Without a terminal the agent fails closed (`503 operator_unavailable`) - this is intended.

The flow in the browser: *Sign with DSC* → the agent lists the certificates → choose one →
type the PIN → the backend receives the signature, embeds it and validates it against the
TEST root CA.

## 7. Meera signs with the MOCK eSign provider

*Sign with Aadhaar eSign (MOCK)* → the backend signs an `<Esign ver="2.1">` request with the
document hash and posts it to the provider → the provider verifies the signature and shows an
orange **MOCK** consent page with the fixed test OTP `123456` → the provider issues a one time
certificate from the MOCK CA, signs the document hash and posts a signed `<EsignResp>` back →
the backend verifies the XML signature and the transaction, embeds the CMS and validates it.

The error paths are part of the demonstration as well: `ESP-944` (wrong OTP / cancelled) and
`ESP-946` (the consent page expired).

## 8. The final document

When every recipient has signed, the envelope is `completed`:

```text
GET /api/v1/envelopes/<id>              -> "status":"completed","finalAvailable":true
GET /api/v1/envelopes/<id>/documents/final
```

The final PDF carries the signatures of the recipients and the platform seal
(`CN=Dastakhat Platform Seal (TEST CA)`), plus a certificate of completion page with the
document hash, the signers and the signing times.

## 9. Verify the seal - and watch tampering fail

```bash
cd backend
mvn -q compile exec:java -Dexec.mainClass=com.dastakhat.devpki.PdfVerifyTool \
    -Dexec.args="verify storage/<id>.pdf --trust ../devpki/out/test-root-ca.pem"
```

Real output of the demonstration run (a `completed` envelope of the walk through):

```text
File:      E:\CyberF\DAY 6\backend\storage\e60078dfc05f50410b593867ad5aeaf3.pdf
Size:      38804 bytes
SHA-256:   62bc63ce79fd32a384b4d7a6d15141ae7e1f543760d5347824c729ea047732aa
Signatures: 1
  - DastakhatPlatformSeal
      intact  : true
      valid   : true
      trusted : true
      subject : C=IN,O=Dastakhat,CN=Dastakhat Platform Seal (TEST CA)
      digest  : 2.16.840.1.101.3.4.2.1
      time    : 2026-10-23T04:18:39Z
Pages:     3
RESULT: VALID
```

One byte is flipped and the same command answers `RESULT: INVALID` (exit code 1):

```bash
mvn -q exec:java -Dexec.mainClass=com.dastakhat.devpki.PdfVerifyTool \
    -Dexec.args="tamper storage/<id>.pdf <out.pdf> 400"
mvn -q exec:java -Dexec.mainClass=com.dastakhat.devpki.PdfVerifyTool \
    -Dexec.args="verify <out.pdf> --trust ../devpki/out/test-root-ca.pem"
```

```text
Wrote the modified copy <out.pdf> (byte 400 was flipped)
Signatures: 1
  - DastakhatPlatformSeal
      intact  : false
      valid   : true
      trusted : true
      note    : The message digest attribute does not match the digest of the signed bytes.
Pages:     3
RESULT: INVALID
```

The certificate chain and the signing time still verify - only the covered bytes changed, and
that alone is enough to reject the document.

## 10. The audit trail

`/documents/<id>/audit` lists the events, `/audit/verify` recomputes the hash chain:

```json
{ "intact": true, "eventCount": 23, "brokenAtSeq": null, "checkedAt": "..." }
```

The table is append only: a PostgreSQL trigger refuses `UPDATE` and `DELETE`. A database
administrator can lift the trigger - and the chain verifier then names the broken event
(`intact:false`, `brokenAtSeq: 2`). That test is automated in
`AuditTamperIntegrationTest`.

---

## Automated equivalents

| step | automated test |
|---|---|
| the whole electronic flow | `EnvelopeApiIntegrationTest.theWholeElectronicSigningFlowWorks` and `e2e/tests/full-flow.spec.ts` |
| the DSC flow | `DscSigningIntegrationTest.theWholeDscSigningFlowWorks` (+ 59 tests in `agent/tests`) |
| the MOCK eSign flow | `EsignMockIntegrationTest.theMockEsignFlowSignsTheDocument` (skipped when the provider is not running) |
| the audit tampering | `AuditTamperIntegrationTest.aTamperedAuditRowIsDetectedAndLocated` |
| a modified document | `PdfSignatureServiceTest` (tampered byte, wrong digest, broken seal) |
| one time links | `full-flow.spec.ts` "the signing link cannot be used twice" |
