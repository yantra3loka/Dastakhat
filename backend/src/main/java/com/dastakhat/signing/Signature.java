package com.dastakhat.signing;

import com.dastakhat.fields.Field;
import com.dastakhat.recipients.Recipient;
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
 * A captured signature.
 *
 * <p>For drawn and typed signatures only the image (a PNG under a random storage key) and the
 * metadata are stored - they are <em>not</em> cryptographic signatures. For DSC and MOCK
 * eSign the signer certificate and the SHA-256 of the embedded CMS are kept so that the
 * certificate of completion can reference them.</p>
 */
@Entity
@Table(name = "signatures")
@Getter
@Setter
@NoArgsConstructor
public class Signature {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private Recipient recipient;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "field_id")
    private Field field;

    @Convert(converter = com.dastakhat.common.EnumConverters.SignatureKindConverter.class)
    @Column(name = "kind", nullable = false, length = 20)
    private SignatureKind kind = SignatureKind.DRAWN;

    /** Storage key of the PNG. Null for pure cryptographic signatures. */
    @Column(name = "image_key", length = 80)
    private String imageKey;

    /** The typed name, if the signature was typed rather than drawn. */
    @Column(name = "typed_name", length = 200)
    private String typedName;

    @Column(name = "signer_cert_pem", columnDefinition = "text")
    private String signerCertPem;

    @Column(name = "signer_cert_sha256", length = 64)
    private String signerCertSha256;

    @Column(name = "signer_cert_subject", length = 300)
    private String signerCertSubject;

    @Column(name = "cms_sha256", length = 64)
    private String cmsSha256;

    @Column(name = "signature_sha256", length = 64)
    private String signatureSha256;

    @Column(name = "validation_report", columnDefinition = "text")
    private String validationReport;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}

