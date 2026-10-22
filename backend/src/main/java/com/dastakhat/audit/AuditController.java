package com.dastakhat.audit;

import com.dastakhat.envelopes.Envelope;
import com.dastakhat.envelopes.EnvelopeRepository;
import com.dastakhat.security.CurrentUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The audit trail of an envelope. */
@RestController
@RequestMapping("/api/v1/envelopes/{envelopeId}/audit")
public class AuditController {

    private final AuditService audit;
    private final EnvelopeRepository envelopes;
    private final CurrentUser currentUser;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditController(AuditService audit, EnvelopeRepository envelopes, CurrentUser currentUser) {
        this.audit = audit;
        this.envelopes = envelopes;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<AuditEventView> events(@PathVariable UUID envelopeId) {
        requireOwned(envelopeId);
        List<AuditEventView> views = new ArrayList<>();
        for (AuditEvent event : audit.eventsOf(envelopeId)) {
            views.add(toView(event));
        }
        return views;
    }

    @GetMapping("/verify")
    @Transactional(readOnly = true)
    public ResponseEntity<AuditVerification> verify(@PathVariable UUID envelopeId) {
        requireOwned(envelopeId);
        List<AuditEvent> events = audit.eventsOf(envelopeId);
        int brokenAt = audit.verifyChain(events);
        AuditVerification result = new AuditVerification(
                brokenAt < 0,
                brokenAt < 0 ? null : brokenAt,
                events.size(),
                events.isEmpty() ? AuditEvent.GENESIS_HASH : events.get(events.size() - 1).getHash(),
                Instant.now());
        return ResponseEntity.ok(result);
    }

    private void requireOwned(UUID envelopeId) {
        UUID ownerId = currentUser.requireId();
        envelopes.findByIdAndOwnerId(envelopeId, ownerId)
                .orElseThrow(() -> new com.dastakhat.common.NotFoundException("Envelope not found."));
    }

    private AuditEventView toView(AuditEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (event.getPayload() != null && !event.getPayload().isBlank()) {
            try {
                JsonNode node = mapper.readTree(event.getPayload());
                if (node.isObject()) {
                    node.fields().forEachRemaining(entry -> payload.put(entry.getKey(),
                            entry.getValue().isTextual() ? entry.getValue().textValue() : entry.getValue()));
                }
            } catch (Exception ignored) {
                payload.put("raw", event.getPayload());
            }
        }
        return new AuditEventView(event.getSeq(), event.getType().value(), event.getActor(), event.getIp(),
                event.getUserAgent(), event.getDocSha256(), payload, event.getAt(),
                event.getPrevHash(), event.getHash());
    }

    public record AuditEventView(int seq, String type, String actor, String ip, String userAgent,
                                 String docSha256, Map<String, Object> payload, Instant at,
                                 String prevHash, String hash) { }

    public record AuditVerification(boolean intact, Integer brokenAtSeq, int eventCount, String headHash,
                                    Instant checkedAt) { }
}
