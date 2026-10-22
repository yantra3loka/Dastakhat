package com.dastakhat.signing;

import com.dastakhat.recipients.Recipient;
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
 * Deferred signing state between {@code prepare} and {@code finish}.
 *
 * <p>For a DSC the private key never leaves the token: the backend reserves room for the
 * signature inside the PDF, computes the digest and hands the signed attributes to the
 * local agent. The agent returns the raw signature which the backend wraps into CMS and
 * embeds.</p>
 */
@Entity
@Table(name = "signing_sessions")
@Getter
@Setter
@NoArgsConstructor
public class SigningSession {

    public enum Kind { DSC, ESIGN_MOCK }

    public enum Status { PREPARED, FINISHED, EXPIRED, FAILED }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private Recipient recipient;

    @Convert(converter = com.dastakhat.common.EnumConverters.SessionKindConverter.class)
    @Column(name = "kind", nullable = false, length = 20)
    private Kind kind = Kind.DSC;

    /** Storage key of the PDF that was prepared (with the reserved signature). */
    @Column(name = "prepared_key", nullable = false, length = 80)
    private String preparedKey;

    /** SHA-256 of the document that was prepared. */
    @Column(name = "document_digest", nullable = false, length = 64)
    private String documentDigest;

    /** SHA-256 of the whole prepared document (used for the confirmation prompt). */
    @Column(name = "document_sha256", nullable = false, length = 64)
    private String documentSha256;

    /** DER encoded CMS signed attributes - exactly the bytes the token has to sign. */
    @Column(name = "signed_attrs_der", columnDefinition = "bytea")
    private byte[] signedAttrsDer;

    /** The transaction id for the MOCK eSign flow. */
    @Column(name = "txn", length = 64)
    private String txn;

    @Convert(converter = com.dastakhat.common.EnumConverters.SessionStatusConverter.class)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PREPARED;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt = Instant.now().plusSeconds(600);

    /** PEM of the certificate the signer selected on the token. */
    @Column(name = "cert_pem", columnDefinition = "text")
    private String certPem;

    @Column(name = "cert_sha256", length = 64)
    private String certSha256;

    @Column(name = "signature_algorithm", length = 40)
    private String signatureAlgorithm;

    /** JSON array with the four byte range numbers of the prepared PDF. */
    @Column(name = "byte_range", columnDefinition = "text")
    private String byteRange;

    /** The CMS that was embedded once the signature was available. */
    @Column(name = "cms_der", columnDefinition = "bytea")
    private byte[] cmsDer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "used_at")
    private Instant usedAt;
}



