package com.dastakhat.esignmock;

import com.dastakhat.pki.SealKeyService;
import com.dastakhat.signing.PdfSignatureService;
import com.dastakhat.support.Mailpit;
import com.dastakhat.support.TestDocuments;
import com.dastakhat.support.TestPki;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MOCK eSign flow end to end.
 *
 * <p>The test needs the MOCK eSign provider on {@code http://localhost:9100} - the mock ESP
 * is part of the compose file. When it is not running, the test is skipped instead of
 * failing.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18080",
                "dastakhat.mock-esp-base-url=http://localhost:9100",
                "dastakhat.esign-callback-url=http://localhost:18080/api/v1/esign/callback"
        })
class EsignMockIntegrationTest {

    private static final String MOCK_ESP = "http://localhost:9100";
    private static final String BACKEND = "http://localhost:18080/api/v1";
    private static final String EMAIL = "sender@example.test";
    private static final String PASSWORD = "ChangeMe!123";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private com.dastakhat.envelopes.EnvelopeRepository envelopeRepository;

    @Autowired
    private SealKeyService sealKeys;

    @Autowired
    private com.dastakhat.common.RateLimiter rateLimiter;

    @Autowired
    private javax.sql.DataSource dataSource;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpHeaders headers = new HttpHeaders();

    @BeforeAll
    static void pki() {
        TestPki.ensure();
    }

    @BeforeEach
    void reset() throws Exception {
        Assumptions.assumeTrue(mockEspIsRunning(), "the MOCK eSign provider is not running");
        // The test signs more often than a human would: the counters have to start empty.
        rateLimiter.reset();
        // The append only trigger has to be lifted while the test data is removed - the same
        // mechanism the tampering test uses.
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE audit_events DISABLE TRIGGER trg_audit_events_append_only");
            envelopeRepository.deleteAll();
            statement.execute("ALTER TABLE audit_events ENABLE TRIGGER trg_audit_events_append_only");
        }
    }

    private boolean mockEspIsRunning() {
        try {
            ResponseEntity<String> response = rest.getForEntity(MOCK_ESP + "/esp/health", String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void theMockEsignFlowSignsTheDocument() throws Exception {
        // 1. Log in and remember the session and the CSRF token.
        ResponseEntity<String> login = rest.exchange(BACKEND + "/auth/login", HttpMethod.POST,
                new HttpEntity<>(jsonString(Map.of("email", EMAIL, "password", PASSWORD)), jsonHeaders()),
                String.class);
        assertEquals(HttpStatus.OK, login.getStatusCode(), login.getBody());
        headers = new HttpHeaders();
        headers.set("X-CSRF-TOKEN", mapper.readTree(login.getBody()).get("csrfToken").asText());
        StringBuilder cookies = new StringBuilder();
        if (login.getHeaders().get(HttpHeaders.SET_COOKIE) != null) {
            for (String cookie : login.getHeaders().get(HttpHeaders.SET_COOKIE)) {
                if (cookies.length() > 0) {
                    cookies.append("; ");
                }
                cookies.append(cookie.substring(0, cookie.indexOf(';')));
            }
        }
        headers.add("Cookie", cookies.toString());

        // 2. Upload a document.
        LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(TestDocuments.validPdf()) {
            @Override
            public String getFilename() {
                return "esign.pdf";
            }
        });
        body.add("title", "MOCK eSign document");
        ResponseEntity<String> upload = rest.exchange(BACKEND + "/envelopes", HttpMethod.POST,
                new HttpEntity<LinkedMultiValueMap<String, Object>>(body, headers), String.class);
        assertEquals(HttpStatus.CREATED, upload.getStatusCode(), upload.getBody());
        String envelopeId = mapper.readTree(upload.getBody()).get("id").asText();

        // 3. Add a MOCK eSign recipient.
        ResponseEntity<String> recipients = rest.exchange(BACKEND + "/envelopes/" + envelopeId + "/recipients",
                HttpMethod.PUT,
                new HttpEntity<>(jsonString(Map.of("recipients", List.of(
                        Map.of("name", "Meera", "email", "meera@example.test", "method", "esign_mock")))),
                        jsonWithAuth()),
                String.class);
        assertEquals(HttpStatus.OK, recipients.getStatusCode(), recipients.getBody());
        String recipientId = mapper.readTree(recipients.getBody()).get("recipients").get(0).get("id").asText();

        // 4. Place the signature field.
        ResponseEntity<String> fields = rest.exchange(BACKEND + "/envelopes/" + envelopeId + "/fields",
                HttpMethod.PUT,
                new HttpEntity<>(jsonString(Map.of("fields", List.of(Map.of(
                        "recipientId", recipientId, "type", "signature", "page", 1,
                        "x", 0.1, "y", 0.6, "w", 0.3, "h", 0.08, "required", true)))),
                        jsonWithAuth()),
                String.class);
        assertEquals(HttpStatus.OK, fields.getStatusCode(), fields.getBody());

        // 5. Send.
        ResponseEntity<String> send = rest.exchange(BACKEND + "/envelopes/" + envelopeId + "/send",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.ACCEPTED, send.getStatusCode(), send.getBody());

        // 6. The signing link was delivered.
        String token = Mailpit.latestSigningToken("meera@example.test");

        // 7. The signer starts the MOCK eSign flow.
        ResponseEntity<String> start = rest.exchange(BACKEND + "/sign/" + token + "/esign/start", HttpMethod.POST,
                new HttpEntity<>(jsonString(Map.of()), jsonHeaders()), String.class);
        assertEquals(HttpStatus.OK, start.getStatusCode(), start.getBody());
        JsonNode startBody = mapper.readTree(start.getBody());
        assertTrue(startBody.get("mock").asBoolean(), "the flow has to be marked as a mock");
        String txn = startBody.get("txn").asText();
        String requestXml = startBody.get("requestXml").asText();
        assertTrue(requestXml.contains("<Esign"), "the request has to be an eSign request");
        assertTrue(requestXml.contains("<Signature"), "the request has to be signed");

        // 8. The MOCK provider verifies the request and shows the consent page.
        HttpHeaders xmlHeaders = new HttpHeaders();
        xmlHeaders.setContentType(MediaType.APPLICATION_XML);
        ResponseEntity<String> espRequest = rest.exchange(MOCK_ESP + "/esp/esign", HttpMethod.POST,
                new HttpEntity<>(requestXml, xmlHeaders), String.class);
        assertEquals(HttpStatus.OK, espRequest.getStatusCode(), espRequest.getBody());
        assertEquals(txn, mapper.readTree(espRequest.getBody()).get("txn").asText());

        ResponseEntity<String> otpPage = rest.getForEntity(MOCK_ESP + "/esp/otp/" + txn, String.class);
        assertTrue(otpPage.getBody().contains("MOCK"), "the OTP page has to be marked as a mock");

        // 9. The signer confirms with the test OTP. The provider answers the backend.
        HttpHeaders formHeaders = new HttpHeaders();
        formHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<String> confirm = rest.exchange(MOCK_ESP + "/esp/otp/" + txn, HttpMethod.POST,
                new HttpEntity<>("otp=123456", formHeaders), String.class);
        assertEquals(HttpStatus.OK, confirm.getStatusCode(), confirm.getBody());
        assertEquals("signed", mapper.readTree(confirm.getBody()).get("status").asText());

        // 10. The backend has embedded the signature and completed the envelope.
        ResponseEntity<String> detail = rest.exchange(BACKEND + "/envelopes/" + envelopeId, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, detail.getStatusCode(), detail.getBody());
        JsonNode completed = mapper.readTree(detail.getBody());
        assertEquals("completed", completed.get("status").asText(), detail.getBody());
        assertNotNull(completed.get("finalSha256").asText());
        assertTrue(completed.get("finalAvailable").asBoolean());

        // 11. The link is used up.
        ResponseEntity<String> replay = rest.getForEntity(BACKEND + "/sign/" + token, String.class);
        assertEquals(HttpStatus.GONE, replay.getStatusCode());

        // 12. The audit trail contains the MOCK event.
        ResponseEntity<String> audit = rest.exchange(BACKEND + "/envelopes/" + envelopeId + "/audit",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertTrue(audit.getBody().contains("esign_signed"), "the audit trail has to record the MOCK signature");

        // 13. The final document carries a valid platform seal.
        ResponseEntity<byte[]> document = rest.exchange(
                BACKEND + "/envelopes/" + envelopeId + "/documents/final", HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class);
        assertEquals(HttpStatus.OK, document.getStatusCode());
        PdfSignatureService service = new PdfSignatureService();
        List<PdfSignatureService.SignatureValidation> validations =
                service.validate(document.getBody(), sealKeys.getTrustRoots());
        assertTrue(validations.stream().anyMatch(v -> v.intact() && v.valid()),
                "the platform seal of the final document has to verify");
    }

    private HttpHeaders jsonWithAuth() {
        HttpHeaders copy = new HttpHeaders();
        copy.putAll(headers);
        copy.setContentType(MediaType.APPLICATION_JSON);
        return copy;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        return jsonHeaders;
    }

    private String jsonString(Object body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

