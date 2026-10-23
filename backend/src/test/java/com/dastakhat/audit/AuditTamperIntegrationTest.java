package com.dastakhat.audit;

import com.dastakhat.support.TestDocuments;
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
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The audit trail has to detect a tampered row.
 *
 * <p>The database refuses to change an audit row (the append only trigger). A privileged
 * administrator can lift the trigger - that is exactly what this test does, and the chain
 * verifier has to name the broken event afterwards.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditTamperIntegrationTest {

    private static final String EMAIL = "sender@example.test";
    private static final String PASSWORD = "ChangeMe!123";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private com.dastakhat.envelopes.EnvelopeRepository envelopeRepository;

    @Autowired
    private com.dastakhat.common.RateLimiter rateLimiter;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void pki() {
        com.dastakhat.support.TestPki.ensure();
    }

    @BeforeEach
    void reset() throws Exception {
        // The tests log in more often than a human would: the counters have to start empty.
        rateLimiter.reset();
        toggleAuditTrigger(false);
        envelopeRepository.deleteAll();
        toggleAuditTrigger(true);
    }

    @Test
    void aTamperedAuditRowIsDetectedAndLocated() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        String csrfToken = mapper.readTree(login.getResponse().getContentAsString()).get("csrfToken").asText();

        MvcResult upload = mvc.perform(multipart("/api/v1/envelopes")
                        .file(new org.springframework.mock.web.MockMultipartFile(
                                "file", "audit.pdf", "application/pdf", TestDocuments.validPdf()))
                        .session(session)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .cookie(login.getResponse().getCookie("XSRF-TOKEN")))
                .andExpect(status().isCreated())
                .andReturn();
        String envelopeId = mapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();

        // The chain is intact before the tampering.
        JsonNode before = mapper.readTree(mvc.perform(get("/api/v1/envelopes/" + envelopeId + "/audit/verify")
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(before.get("intact").asBoolean());
        int eventCount = before.get("eventCount").asInt();
        assertTrue(eventCount >= 2, "expected at least two audit events");

        // Tamper with the second event: a privileged administrator lifts the trigger.
        toggleAuditTrigger(false);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE audit_events SET actor = 'somebody else' "
                    + "WHERE envelope_id = '" + envelopeId + "' AND seq = 2");
        }
        toggleAuditTrigger(true);

        // The verifier names the broken event.
        JsonNode after = mapper.readTree(mvc.perform(get("/api/v1/envelopes/" + envelopeId + "/audit/verify")
                        .session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertFalse(after.get("intact").asBoolean(), "the chain must be reported as broken");
        assertEquals(2, after.get("brokenAtSeq").asInt(), "the second event must be named");
    }

    private void toggleAuditTrigger(boolean enabled) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE audit_events "
                    + (enabled ? "ENABLE" : "DISABLE") + " TRIGGER trg_audit_events_append_only");
        }
    }
}
