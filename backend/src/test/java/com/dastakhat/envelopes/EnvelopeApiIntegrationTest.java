package com.dastakhat.envelopes;

import com.dastakhat.support.Mailpit;
import com.dastakhat.support.TestDocuments;
import com.dastakhat.support.TestPki;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The end to end behaviour of the sender and signer API.
 *
 * <p>The database is a real PostgreSQL 16 (Testcontainers), the migrations of Flyway create
 * the schema and the whole flow is exercised through the HTTP layer, including the session
 * and the CSRF protection.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class EnvelopeApiIntegrationTest {

    private static final String EMAIL = "sender@example.test";
    private static final String PASSWORD = "ChangeMe!123";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private com.dastakhat.envelopes.EnvelopeRepository envelopeRepository;

    @Autowired
    private com.dastakhat.users.UserRepository userRepository;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private com.dastakhat.common.RateLimiter rateLimiter;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void pki() {
        TestPki.ensure();
    }

    @BeforeEach
    void reset() throws Exception {
        // The tests log in more often than a human would: the counters have to start empty.
        rateLimiter.reset();
        // A clean database for every test. The envelopes cascade to their children.
        // The append only trigger of the audit table has to be lifted for the clean up - the
        // same mechanism that is used to demonstrate the tampering detection.
        toggleAuditTrigger(false);
        envelopeRepository.deleteAll();
        toggleAuditTrigger(true);
        if (userRepository.findByEmailIgnoreCase("other@example.test").isEmpty()) {
            com.dastakhat.users.User other = new com.dastakhat.users.User();
            other.setEmail("other@example.test");
            other.setName("Other Sender");
            other.setPasswordHash(passwordEncoder.encode("OtherPass!123"));
            userRepository.save(other);
        }
    }

    private void toggleAuditTrigger(boolean enabled) {
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE audit_events "
                    + (enabled ? "ENABLE" : "DISABLE") + " TRIGGER trg_audit_events_append_only");
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Cannot toggle the append only trigger", e);
        }
    }

    @Test
    void theHealthEndpointIsPublic() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }

    @Test
    void aWrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aStateChangeWithoutTheCsrfTokenIsRejected() throws Exception {
        Session session = login();
        mvc.perform(post("/api/v1/envelopes/" + java.util.UUID.randomUUID() + "/send")
                        .session(session.httpSession()))
                .andExpect(status().isForbidden());
    }

    @Test
    void theWholeElectronicSigningFlowWorks() throws Exception {
        Session session = login();

        // 1. Upload the document.
        MvcResult upload = mvc.perform(multipart("/api/v1/envelopes")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "offer-letter.pdf", "application/pdf", TestDocuments.validPdf()))
                        .param("title", "Offer letter - Ravi")
                        .session(session.httpSession())
                        .header("X-CSRF-TOKEN", session.csrfToken())
                        .cookie(session.csrfCookie()))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode envelope = mapper.readTree(upload.getResponse().getContentAsString());
        String envelopeId = envelope.get("id").asText();
        assertEquals("draft", envelope.get("status").asText());
        assertEquals(2, envelope.get("pages").asInt());
        assertEquals(64, envelope.get("originalSha256").asText().length());

        // 2. Add a recipient.
        MvcResult recipients = mvc.perform(put("/api/v1/envelopes/" + envelopeId + "/recipients")
.session(session.httpSession())
.header("X-CSRF-TOKEN", session.csrfToken())
.cookie(session.csrfCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipients":[{"name":"Asha HR","email":"asha@example.test",
                                "method":"electronic","routingOrder":1}]}"""))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode recipient = mapper.readTree(recipients.getResponse().getContentAsString())
                .get("recipients").get(0);
        String recipientId = recipient.get("id").asText();

        // 3. Place a signature field.
        mvc.perform(put("/api/v1/envelopes/" + envelopeId + "/fields")
.session(session.httpSession())
.header("X-CSRF-TOKEN", session.csrfToken())
.cookie(session.csrfCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fields":[{"recipientId":"%s","type":"signature","page":1,
                                "x":0.1,"y":0.6,"w":0.3,"h":0.08,"required":true}]}""".formatted(recipientId)))
                .andExpect(status().isOk());

        // 4. Sending is only possible with a signature field.
        mvc.perform(post("/api/v1/envelopes/" + envelopeId + "/send")
                        .session(session.httpSession())
                        .header("X-CSRF-TOKEN", session.csrfToken())
                        .cookie(session.csrfCookie()))
                .andExpect(status().isAccepted());

        // 5. The signing link was sent by mail - the raw token is not stored in the database,
        //    so the test reads it from the mailpit API.
        String token = Mailpit.latestSigningToken("asha@example.test");
        assertNotNull(token, "the signing link must have been sent");

        // 6. The signer sees the document and its own field only.
        MvcResult view = mvc.perform(get("/api/v1/sign/" + token)).andExpect(status().isOk()).andReturn();
        JsonNode signerView = mapper.readTree(view.getResponse().getContentAsString());
        assertEquals("Asha HR", signerView.get("recipientName").asText());
        assertEquals("electronic", signerView.get("method").asText());
        assertEquals(1, signerView.get("fields").size());

        mvc.perform(get("/api/v1/sign/" + token + "/document")).andExpect(status().isOk());

        // 7. Signing without the consent is rejected.
        String fieldId = signerView.get("fields").get(0).get("id").asText();
        mvc.perform(post("/api/v1/sign/" + token + "/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consent":false,"fields":[{"fieldId":"%s","signatureImageBase64":"%s"}]}"""
                                .formatted(fieldId, signaturePng())))
                .andExpect(status().isUnprocessableEntity());

        // 8. With the consent the signature is accepted.
        MvcResult complete = mvc.perform(post("/api/v1/sign/" + token + "/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"consent":true,"fields":[{"fieldId":"%s","signatureImageBase64":"%s"}]}"""
                                .formatted(fieldId, signaturePng())))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode completeBody = mapper.readTree(complete.getResponse().getContentAsString());
        assertEquals("signed", completeBody.get("status").asText());
        assertEquals("completed", completeBody.get("envelopeStatus").asText());

        // 9. The link is single use.
        mvc.perform(get("/api/v1/sign/" + token)).andExpect(status().isGone());

        // 10. The envelope is completed and the final document exists.
        MvcResult detail = mvc.perform(get("/api/v1/envelopes/" + envelopeId)
                        .session(session.httpSession()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode completed = mapper.readTree(detail.getResponse().getContentAsString());
        assertEquals("completed", completed.get("status").asText());
        assertEquals(1, completed.get("signedCount").asInt());
        assertTrue(completed.get("finalAvailable").asBoolean());
        assertEquals(64, completed.get("finalSha256").asText().length());
        assertEquals(64, completed.get("preSealSha256").asText().length());

        mvc.perform(get("/api/v1/envelopes/" + envelopeId + "/documents/final")
                        .session(session.httpSession()))
                .andExpect(status().isOk());

        // 11. The audit trail is complete and the chain verifies.
        MvcResult audit = mvc.perform(get("/api/v1/envelopes/" + envelopeId + "/audit")
                        .session(session.httpSession()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode events = mapper.readTree(audit.getResponse().getContentAsString());
        assertTrue(events.size() >= 6, "expected several audit events but got " + events.size());
        assertEquals(1, events.get(0).get("seq").asInt());
        assertEquals("envelope_created", events.get(0).get("type").asText());

        MvcResult verify = mvc.perform(get("/api/v1/envelopes/" + envelopeId + "/audit/verify")
                        .session(session.httpSession()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode verification = mapper.readTree(verify.getResponse().getContentAsString());
        assertTrue(verification.get("intact").asBoolean(), "the audit chain must be intact");
        assertTrue(verification.get("brokenAtSeq").isNull(), "no broken event is reported");
    }

    @Test
    void anotherSenderCannotSeeTheEnvelope() throws Exception {
        Session owner = login();
        MvcResult upload = mvc.perform(multipart("/api/v1/envelopes")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "private.pdf", "application/pdf", TestDocuments.validPdf()))
                        .session(owner.httpSession())
                        .header("X-CSRF-TOKEN", owner.csrfToken())
                        .cookie(owner.csrfCookie()))
                .andExpect(status().isCreated())
                .andReturn();
        String envelopeId = mapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();

        Session other = login("other@example.test", "OtherPass!123");
        mvc.perform(get("/api/v1/envelopes/" + envelopeId).session(other.httpSession()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/envelopes/" + envelopeId + "/audit").session(other.httpSession()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/envelopes/" + envelopeId + "/documents/original")
                        .session(other.httpSession()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownSigningTokenIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/sign/this-token-does-not-exist-at-all"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFileThatIsNotAPdfIsRejected() throws Exception {
        Session session = login();
        mvc.perform(multipart("/api/v1/envelopes")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "virus.pdf", "application/pdf", TestDocuments.notAPdf()))
                        .session(session.httpSession())
                        .header("X-CSRF-TOKEN", session.csrfToken())
                        .cookie(session.csrfCookie()))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void aPdfWithActiveContentIsRejected() throws Exception {
        Session session = login();
        mvc.perform(multipart("/api/v1/envelopes")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "script.pdf", "application/pdf", TestDocuments.javascriptPdf()))
                        .session(session.httpSession())
                        .header("X-CSRF-TOKEN", session.csrfToken())
                        .cookie(session.csrfCookie()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anEnvelopeCannotBeSentWithoutASignatureField() throws Exception {
        Session session = login();
        MvcResult upload = mvc.perform(multipart("/api/v1/envelopes")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "offer.pdf", "application/pdf", TestDocuments.validPdf()))
                        .session(session.httpSession())
                        .header("X-CSRF-TOKEN", session.csrfToken())
                        .cookie(session.csrfCookie()))
                .andExpect(status().isCreated())
                .andReturn();
        String envelopeId = mapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(put("/api/v1/envelopes/" + envelopeId + "/recipients")
.session(session.httpSession())
.header("X-CSRF-TOKEN", session.csrfToken())
.cookie(session.csrfCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipients":[{"name":"Asha","email":"asha@example.test","method":"electronic"}]}"""))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/envelopes/" + envelopeId + "/send")
                        .session(session.httpSession())
                        .header("X-CSRF-TOKEN", session.csrfToken())
                        .cookie(session.csrfCookie()))
                .andExpect(status().isConflict());
    }

    @Test
    void aFieldOutsideOfThePageIsRejected() throws Exception {
        Session session = login();
        MvcResult upload = mvc.perform(multipart("/api/v1/envelopes")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "offer.pdf", "application/pdf", TestDocuments.validPdf()))
                        .session(session.httpSession())
                        .header("X-CSRF-TOKEN", session.csrfToken())
                        .cookie(session.csrfCookie()))
                .andExpect(status().isCreated())
                .andReturn();
        String envelopeId = mapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(put("/api/v1/envelopes/" + envelopeId + "/recipients")
.session(session.httpSession())
.header("X-CSRF-TOKEN", session.csrfToken())
.cookie(session.csrfCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipients":[{"name":"Asha","email":"asha@example.test","method":"electronic"}]}"""))
                .andExpect(status().isOk());
        MvcResult recipients = mvc.perform(put("/api/v1/envelopes/" + envelopeId + "/recipients")
.session(session.httpSession())
.header("X-CSRF-TOKEN", session.csrfToken())
.cookie(session.csrfCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipients":[{"name":"Asha","email":"asha@example.test","method":"electronic"}]}"""))
                .andExpect(status().isOk())
                .andReturn();
        String recipientId = mapper.readTree(recipients.getResponse().getContentAsString())
                .get("recipients").get(0).get("id").asText();

        mvc.perform(put("/api/v1/envelopes/" + envelopeId + "/fields")
.session(session.httpSession())
.header("X-CSRF-TOKEN", session.csrfToken())
.cookie(session.csrfCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fields":[{"recipientId":"%s","type":"signature","page":99,
                                "x":0.1,"y":0.6,"w":0.3,"h":0.08}]}""".formatted(recipientId)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aDeclinedEnvelopeStops() throws Exception {
        Session session = login();
        MvcResult upload = mvc.perform(multipart("/api/v1/envelopes")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "offer.pdf", "application/pdf", TestDocuments.validPdf()))
                        .session(session.httpSession())
                        .header("X-CSRF-TOKEN", session.csrfToken())
                        .cookie(session.csrfCookie()))
                .andExpect(status().isCreated())
                .andReturn();
        String envelopeId = mapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();
        MvcResult recipients = mvc.perform(put("/api/v1/envelopes/" + envelopeId + "/recipients")
.session(session.httpSession())
.header("X-CSRF-TOKEN", session.csrfToken())
.cookie(session.csrfCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipients":[{"name":"Asha","email":"asha@example.test","method":"electronic"}]}"""))
                .andExpect(status().isOk())
                .andReturn();
        String recipientId = mapper.readTree(recipients.getResponse().getContentAsString())
                .get("recipients").get(0).get("id").asText();
        mvc.perform(put("/api/v1/envelopes/" + envelopeId + "/fields")
.session(session.httpSession())
.header("X-CSRF-TOKEN", session.csrfToken())
.cookie(session.csrfCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fields":[{"recipientId":"%s","type":"signature","page":1,
                                "x":0.1,"y":0.6,"w":0.3,"h":0.08}]}""".formatted(recipientId)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/envelopes/" + envelopeId + "/send")
                        .session(session.httpSession())
                        .header("X-CSRF-TOKEN", session.csrfToken())
                        .cookie(session.csrfCookie()))
                .andExpect(status().isAccepted());

        String token = Mailpit.latestSigningToken("asha@example.test");
        mvc.perform(post("/api/v1/sign/" + token + "/decline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"I do not agree with the contract.\"}"))
                .andExpect(status().isOk());

        MvcResult detail = mvc.perform(get("/api/v1/envelopes/" + envelopeId)
                        .session(session.httpSession()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode declined = mapper.readTree(detail.getResponse().getContentAsString());
        assertEquals("declined", declined.get("status").asText());
        assertFalse(declined.get("finalAvailable").asBoolean());
    }

    // ------------------------------------------------------------- helpers

    private Session login() throws Exception {
        return login(EMAIL, PASSWORD);
    }

    private Session login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException("The login failed: "
                    + result.getResponse().getContentAsString());
        }
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        String csrfToken = body.get("csrfToken").asText();
        jakarta.servlet.http.Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        if (cookie == null) {
            cookie = new jakarta.servlet.http.Cookie("XSRF-TOKEN", csrfToken);
        }
        return new Session(session, csrfToken, cookie);
    }

    /** Adds the session, the CSRF header and the CSRF cookie to a request. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
            Session session, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        builder.session(session.httpSession()).header("X-CSRF-TOKEN", session.csrfToken());
        if (session.csrfCookie() != null) {
            builder.cookie(session.csrfCookie());
        }
        return builder;
    }

    private String signaturePng() throws Exception {
        BufferedImage image = new BufferedImage(240, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.setStroke(new java.awt.BasicStroke(2f));
        graphics.drawLine(20, 60, 60, 20);
        graphics.drawLine(60, 20, 100, 60);
        graphics.drawLine(100, 60, 140, 25);
        graphics.drawLine(140, 25, 200, 55);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** The session of an authenticated sender. */
    private record Session(MockHttpSession httpSession, String csrfToken, jakarta.servlet.http.Cookie csrfCookie) { }
}


