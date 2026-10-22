package com.dastakhat.fields;

import com.dastakhat.envelopes.Envelope;
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
 * A field placed on a PDF page.
 *
 * <p>The position is stored as a fraction (0..1) of the page so that it survives zooming,
 * resizing and reloading in the browser. The backend converts the fractions to PDF points
 * using the MediaBox and the /Rotate entry of the page.</p>
 */
@Entity
@Table(name = "fields")
@Getter
@Setter
@NoArgsConstructor
public class Field {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "envelope_id", nullable = false)
    private Envelope envelope;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private Recipient recipient;

    @Convert(converter = com.dastakhat.common.EnumConverters.FieldTypeConverter.class)
    @Column(name = "type", nullable = false, length = 20)
    private FieldType type = FieldType.SIGNATURE;

    @Column(name = "page", nullable = false)
    private int page;

    /** Left edge as a fraction of the page width (0..1). */
    @Column(name = "x", nullable = false)
    private double x;

    /** Top edge as a fraction of the page height (0..1). */
    @Column(name = "y", nullable = false)
    private double y;

    /** Width as a fraction of the page width (0..1). */
    @Column(name = "w", nullable = false)
    private double w;

    /** Height as a fraction of the page height (0..1). */
    @Column(name = "h", nullable = false)
    private double h;

    @Column(name = "required", nullable = false)
    private boolean required = true;

    @Column(name = "value", length = 2000)
    private String value;

    @Column(name = "label", length = 120)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}

