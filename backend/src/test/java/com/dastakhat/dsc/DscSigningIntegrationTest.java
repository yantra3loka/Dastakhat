package com.dastakhat.dsc;

import com.dastakhat.signing.PdfSignatureService;
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

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The DSC signing flow from the point of view of the backend.
 *
 * <p>The browser asks the local agent to sign the bytes that the backend prepared. The test
 * plays the role of the agent: it signs the same bytes with the TEST DSC key of the devpki
 * tool. The operator confirmation of the agent (a human typing SIGN and the PIN in a terminal)
 * cannot be automated and is covered by the test suite of the agent itself.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class DscSigningIntegrationTest {

    private static final String EMAIL = "sender@example.test";
    private static final String PASSWORD = "ChangeMe!123";
    /** The TEST DSC key of the test PKI - the same one the agent signs with. */
    private static final Path DSC_KEYSTORE = TestPki.DIRECTORY.resolve("dsc-signer.p12");
    private static final Path DSC_CERTIFICATE = TestPki.DIRECTORY.resolve("dsc-signer-cert.pem");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private com.dastakhat.envelopes.EnvelopeRepository envelopeRepository;

    @Autowired
    private com.dastakhat.common.RateLimiter rateLimiter;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private com.dastakhat.pki.SealKeyService sealKeys;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void pki() {
        TestPki.ensure();
    }

    @BeforeEach
    void reset() throws Exception {
        rateLimiter.reset();
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE audit_events DISABLE TRIGGER trg_audit_events_append_only");
            envelopeRepository.deleteAll();
            statement.execute("ALTER TABLE audit_events ENABLE TRIGGER trg_audit_events_append_only");
        }
    }

    @Test
    void theWholeDscSigningFlowWorks() throws Exception {
        String recipientEmail = "ravi-" + System.currentTimeMillis() + "@example.test";
        Session session = login();

        // 1. Upload the document.
        MvcResult upload = mvc.perform(auth(session, multipart("/api/v1/envelopes")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "offer-letter.pdf", "application/pdf", TestDocuments.validPdf()))
                        .param("title", "Offer letter signed with a DSC")))
                .andExpect(status().isCreated())
                .andReturn();
        String envelopeId = mapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();

        // 2. Add a DSC recipient and a signature field.
        MvcResult recipients = mvc.perform(auth(session, put("/api/v1/envelopes/" + envelopeId + "/recipients")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recipients\":[{\"name\":\"Ravi Kumar\",\"email\":\"" + recipientEmail
                                + "\",\"method\":\"dsc\"}]}")))
                .andExpect(status().isOk())
                .andReturn();
        String recipientId = mapper.readTree(recipients.getResponse().getContentAsString())
                .get("recipients").get(0).get("id").asText();
        mvc.perform(auth(session, put("/api/v1/envelopes/" + envelopeId + "/fields")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":[{\"recipientId\":\"" + recipientId
                                + "\",\"type\":\"signature\",\"page\":1,\"x\":0.1,\"y\":0.6,"
                                + "\"w\":0.3,\"h\":0.08,\"required\":true}]}")))
                .andExpect(status().isOk());

        // 3. Send the envelope.
        mvc.perform(auth(session, post("/api/v1/envelopes/" + envelopeId + "/send")))
                .andExpect(status().isAccepted());

        // 4. The signer opens the link that was delivered by mail.
        String token = Mailpit.latestSigningToken(recipientEmail);

        // 5. The signer asks for the bytes to sign, with the certificate of the token.
        String certificatePem = Files.readString(DSC_CERTIFICATE);
        MvcResult prepare = mvc.perform(post("/api/v1/sign/" + token + "/dsc/prepare")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"certificatePem\":" + mapper.writeValueAsString(certificatePem) + "}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode prepared = mapper.readTree(prepare.getResponse().getContentAsString());
        String sessionId = prepared.get("sessionId").asText();
        assertNotNull(sessionId);
        assertEquals(64, prepared.get("documentSha256").asText().length());
        assertTrue(prepared.get("signatureAlgorithm").asText().contains("RSA"),
                "the algorithm has to name the key type: " + prepared.get("signatureAlgorithm").asText());

        // 6. The token signs the bytes - the private key never leaves the agent.
        byte[] toBeSigned = Base64.getDecoder().decode(prepared.get("toBeSignedBase64").asText());
        byte[] signature = signWithDscKey(toBeSigned);

        // 7. The signature is submitted and embedded.
        MvcResult finish = mvc.perform(post("/api/v1/sign/" + token + "/dsc/finish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\",\"signatureBase64\":\""
                                + Base64.getEncoder().encodeToString(signature) + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode finished = mapper.readTree(finish.getResponse().getContentAsString());
        assertEquals("signed", finished.get("status").asText(), finish.getResponse().getContentAsString());
        assertTrue(finished.get("validated").asBoolean(), "the signature has to validate");
        assertTrue(finished.get("trusted").asBoolean(), "the DSC chain has to be trusted");
        assertEquals("completed", finished.get("envelopeStatus").asText());

        // 8. The link is used up.
        mvc.perform(get("/api/v1/sign/" + token)).andExpect(status().isGone());

        // 9. The final document carries the platform seal and the DSC signature.
        MvcResult finalPdf = mvc.perform(auth(session,
                        get("/api/v1/envelopes/" + envelopeId + "/documents/final")))
                .andExpect(status().isOk())
                .andReturn();
        PdfSignatureService service = new PdfSignatureService();
        List<PdfSignatureService.SignatureValidation> validations =
                service.validate(finalPdf.getResponse().getContentAsByteArray(), sealKeys.getTrustRoots());
        assertTrue(validations.size() >= 2, "the seal and the DSC signature are expected");
        assertTrue(validations.stream().anyMatch(v -> v.intact() && v.valid()),
                "every signature of the final document has to verify");

        // 10. The audit trail records the DSC signature.
        MvcResult audit = mvc.perform(auth(session,
                        get("/api/v1/envelopes/" + envelopeId + "/audit")))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(audit.getResponse().getContentAsString().contains("dsc_signed"),
                "the audit trail has to record the DSC signature");
        MvcResult verify = mvc.perform(auth(session,
                        get("/api/v1/envelopes/" + envelopeId + "/audit/verify")))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(mapper.readTree(verify.getResponse().getContentAsString()).get("intact").asBoolean());
    }

    /** Signs with the TEST DSC key - the same operation the token performs in the agent. */
    private byte[] signWithDscKey(byte[] content) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(DSC_KEYSTORE)) {
            store.load(in, "changeit".toCharArray());
        }
        PrivateKey key = (PrivateKey) store.getKey("dsc-signer", "changeit".toCharArray());
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update(content);
        return signer.sign();
    }

    // ------------------------------------------------------------- helpers

    private Session login() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        String csrfToken = mapper.readTree(result.getResponse().getContentAsString())
                .get("csrfToken").asText();
        jakarta.servlet.http.Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        if (cookie == null) {
            cookie = new jakarta.servlet.http.Cookie("XSRF-TOKEN", csrfToken);
        }
        return new Session(session, csrfToken, cookie);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
            Session session, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.session(session.httpSession())
                .header("X-CSRF-TOKEN", session.csrfToken())
                .cookie(session.csrfCookie());
    }

    private record Session(MockHttpSession httpSession, String csrfToken,
                           jakarta.servlet.http.Cookie csrfCookie) {
    }
}
