package com.dastakhat.envelopes;

import com.dastakhat.users.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Convert;
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

/** A document that travels through a signing workflow. */
@Entity
@Table(name = "envelopes")
@Getter
@Setter
@NoArgsConstructor
public class Envelope {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Convert(converter = com.dastakhat.common.EnumConverters.EnvelopeStatusConverter.class)
    @Column(name = "status", nullable = false, length = 20)
    private EnvelopeStatus status = EnvelopeStatus.DRAFT;

    @Convert(converter = com.dastakhat.common.EnumConverters.RoutingModeConverter.class)
    @Column(name = "routing", nullable = false, length = 20)
    private RoutingMode routing = RoutingMode.SEQUENTIAL;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @Column(name = "original_sha256", length = 64)
    private String originalSha256;

    @Column(name = "final_sha256", length = 64)
    private String finalSha256;

    @Column(name = "pre_seal_sha256", length = 64)
    private String preSealSha256;

    @Column(name = "pages")
    private Integer pages;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    public boolean isDraft() {
        return status == EnvelopeStatus.DRAFT;
    }
}



