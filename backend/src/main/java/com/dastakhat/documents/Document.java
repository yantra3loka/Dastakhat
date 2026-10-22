package com.dastakhat.documents;

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
 * A stored file. The file itself lives on disk under a random key; the database only knows
 * the key, never a path that was built from a user supplied file name.
 */
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
public class Document {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "envelope_id", nullable = false)
    private Envelope envelope;

    @Convert(converter = com.dastakhat.common.EnumConverters.DocumentKindConverter.class)
    @Column(name = "kind", nullable = false, length = 20)
    private DocumentKind kind = DocumentKind.ORIGINAL;

    /** Random, opaque key. Never derived from the uploaded file name. */
    @Column(name = "storage_key", nullable = false, unique = true, length = 80)
    private String storageKey;

    /** Sanitised display name of the original upload. */
    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "pages")
    private Integer pages;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}

