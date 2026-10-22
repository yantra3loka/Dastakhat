package com.dastakhat.audit;

import com.dastakhat.envelopes.Envelope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Convert;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only audit event.
 *
 * <p>Every event carries the SHA-256 of the previous event, so the history forms a hash
 * chain. Editing a single row breaks the chain from that row onwards and the verification
 * endpoint reports the first broken sequence number.</p>
 *
 * <p>The chain is tamper <em>evident</em>, not tamper proof: a privileged database
 * administrator can still rewrite the whole history. The database role used by the
 * application is therefore not allowed to update or delete rows of this table.</p>
 */
@Entity
@Table(name = "audit_events")
@Getter
@Setter
@NoArgsConstructor
public class AuditEvent {

    public static final String GENESIS_HASH = "0".repeat(64);

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "envelope_id", nullable = false)
    private Envelope envelope;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Convert(converter = com.dastakhat.common.EnumConverters.AuditEventTypeConverter.class)
    @Column(name = "type", nullable = false, length = 40)
    private AuditEventType type;

    @Column(name = "actor", nullable = false, length = 200)
    private String actor;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "doc_sha256", length = 64)
    private String docSha256;

    /** Canonical JSON payload of the event. */
    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash = GENESIS_HASH;

    @Column(name = "hash", nullable = false, length = 64)
    private String hash;

    @Column(name = "at", nullable = false)
    private Instant at = Instant.now();
}

