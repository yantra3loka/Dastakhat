package com.dastakhat.recipients;

import com.dastakhat.envelopes.Envelope;
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

/**
 * A person who has to sign an envelope.
 *
 * <p>Only the SHA-256 of the signing token is stored. The raw token exists exactly once, in
 * the signing link that is e-mailed to the recipient.</p>
 */
@Entity
@Table(name = "recipients")
@Getter
@Setter
@NoArgsConstructor
public class Recipient {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "envelope_id", nullable = false)
    private Envelope envelope;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Convert(converter = com.dastakhat.common.EnumConverters.SigningMethodConverter.class)
    @Column(name = "method", nullable = false, length = 20)
    private SigningMethod method = SigningMethod.ELECTRONIC;

    @Column(name = "routing_order", nullable = false)
    private int routingOrder = 1;

    @Convert(converter = com.dastakhat.common.EnumConverters.RecipientStatusConverter.class)
    @Column(name = "status", nullable = false, length = 20)
    private RecipientStatus status = RecipientStatus.PENDING;

    /** SHA-256 hex of the signing token. The raw token is never stored. */
    @Column(name = "token_hash", unique = true, length = 64)
    private String tokenHash;

    /**
     * SHA-256 of a token that has already been used. It is kept so that the platform can
     * answer "this link has already been used" instead of pretending that it never existed.
     */
    @Column(name = "used_token_hash", length = 64)
    private String usedTokenHash;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "viewed_at")
    private Instant viewedAt;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "declined_at")
    private Instant declinedAt;

    @Column(name = "decline_reason", length = 500)
    private String declineReason;

    @Column(name = "ip", length = 64)
    private String ip;

    @Column(name = "user_agent", length = 400)
    private String userAgent;

    @Column(name = "consent_recorded_at")
    private Instant consentRecordedAt;

    @Column(name = "consent_text", length = 500)
    private String consentText;

    /** SHA-256 of the download token that is sent with the completion notification. */
    @Column(name = "download_token_hash", length = 64)
    private String downloadTokenHash;

    @Column(name = "download_token_expires_at")
    private Instant downloadTokenExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}



