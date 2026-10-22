package com.dastakhat.audit;

import com.dastakhat.common.CanonicalJson;
import com.dastakhat.common.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Append-only audit trail with a SHA-256 hash chain.
 *
 * <p>The hash of an event is {@code SHA-256(prev_hash + canonical_json(event_without_hash))}.
 * The canonical JSON is deterministic (sorted keys, no whitespace) so that the chain can be
 * recomputed later, by a different process or a different implementation.</p>
 */
@Service
public class AuditService {

    /** Events are appended under a per envelope lock so that the sequence never gaps. */
    private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

    private final AuditEventRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    /**
     * Appends an event to the chain of the given envelope.
     *
     * @param payload arbitrary, JSON serialisable details of the event
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public AuditEvent append(UUID envelopeId,
                             AuditEventType type,
                             String actor,
                             String ip,
                             String userAgent,
                             String docSha256,
                             Map<String, Object> payload) {
        Object lock = locks.computeIfAbsent(envelopeId, k -> new Object());
        synchronized (lock) {
            AuditEvent last = repository.findFirstByEnvelopeIdOrderBySeqDesc(envelopeId).orElse(null);
            String prevHash = last == null ? AuditEvent.GENESIS_HASH : last.getHash();
            int seq = last == null ? 1 : last.getSeq() + 1;
            Instant at = Instant.now().truncatedTo(ChronoUnit.SECONDS);

            ObjectNode body = mapper.createObjectNode();
            body.put("seq", seq);
            body.put("at", at.toString());
            body.put("type", type.value());
            body.put("actor", actor == null ? "system" : actor);
            body.put("ip", ip);
            body.put("user_agent", userAgent);
            body.put("doc_sha256", docSha256);
            body.put("prev_hash", prevHash);
            body.set("payload", payload == null ? mapper.createObjectNode() : mapper.valueToTree(payload));

            String hash = chainHash(prevHash, body);

            AuditEvent event = new AuditEvent();
            event.setEnvelope(new com.dastakhat.envelopes.Envelope());
            event.getEnvelope().setId(envelopeId);
            event.setSeq(seq);
            event.setType(type);
            event.setActor(actor == null ? "system" : actor);
            event.setIp(ip);
            event.setUserAgent(userAgent);
            event.setDocSha256(docSha256);
            event.setPayload(CanonicalJson.canonicalize(body.get("payload")));
            event.setPrevHash(prevHash);
            event.setHash(hash);
            event.setAt(at);
            return repository.save(event);
        }
    }

    /** Recomputes the chain and returns the sequence number of the first broken event. */
    public int verifyChain(List<AuditEvent> events) {
        String prev = AuditEvent.GENESIS_HASH;
        for (AuditEvent event : events) {
            ObjectNode body = bodyOf(event);
            if (!prev.equals(event.getPrevHash())) {
                return event.getSeq();
            }
            String expected = chainHash(prev, body);
            if (!Hashing.constantTimeEquals(expected, event.getHash())) {
                return event.getSeq();
            }
            prev = event.getHash();
        }
        return -1;
    }

    /** Builds the canonical body of an event exactly the way it was hashed when appended. */
    public ObjectNode bodyOf(AuditEvent event) {
        ObjectNode body = mapper.createObjectNode();
        body.put("seq", event.getSeq());
        body.put("at", event.getAt().truncatedTo(ChronoUnit.SECONDS).toString());
        body.put("type", event.getType().value());
        body.put("actor", event.getActor());
        body.put("ip", event.getIp());
        body.put("user_agent", event.getUserAgent());
        body.put("doc_sha256", event.getDocSha256());
        body.put("prev_hash", event.getPrevHash());
        JsonNode payload = payloadOf(event);
        body.set("payload", payload);
        return body;
    }

    private JsonNode payloadOf(AuditEvent event) {
        if (event.getPayload() == null || event.getPayload().isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(event.getPayload());
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    static String chainHash(String prevHash, JsonNode body) {
        // hash = SHA-256(prev_hash + canonical_json(event)) - the same construction the
        // original brief used, so a chain written by either implementation verifies.
        byte[] prev = prevHash.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] canonical = CanonicalJson.canonicalize(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] combined = new byte[prev.length + canonical.length];
        System.arraycopy(prev, 0, combined, 0, prev.length);
        System.arraycopy(canonical, 0, combined, prev.length, canonical.length);
        return Hashing.sha256Hex(combined);
    }

    /** Convenience helper that returns the ordered events of an envelope. */
    @Transactional(readOnly = true)
    public List<AuditEvent> eventsOf(UUID envelopeId) {
        return repository.findByEnvelopeIdOrderBySeqAsc(envelopeId);
    }

    /** Returns the hash of the newest event or the genesis hash. */
    @Transactional(readOnly = true)
    public String headHash(UUID envelopeId) {
        return repository.findFirstByEnvelopeIdOrderBySeqDesc(envelopeId)
                .map(AuditEvent::getHash)
                .orElse(AuditEvent.GENESIS_HASH);
    }

    public List<AuditEvent> emptyList() {
        return new ArrayList<>();
    }
}
