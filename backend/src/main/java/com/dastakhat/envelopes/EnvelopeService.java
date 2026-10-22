package com.dastakhat.envelopes;

import com.dastakhat.audit.AuditEventType;
import com.dastakhat.audit.AuditService;
import com.dastakhat.common.ConflictException;
import com.dastakhat.common.Hashing;
import com.dastakhat.common.NotFoundException;
import com.dastakhat.common.PayloadTooLargeException;
import com.dastakhat.common.UnprocessableException;
import com.dastakhat.config.AppProperties;
import com.dastakhat.documents.Document;
import com.dastakhat.documents.DocumentKind;
import com.dastakhat.documents.DocumentRepository;
import com.dastakhat.documents.PdfValidator;
import com.dastakhat.documents.StorageService;
import com.dastakhat.fields.Field;
import com.dastakhat.fields.FieldRepository;
import com.dastakhat.fields.FieldType;
import com.dastakhat.mail.MailService;
import com.dastakhat.recipients.Recipient;
import com.dastakhat.recipients.RecipientRepository;
import com.dastakhat.recipients.RecipientStatus;
import com.dastakhat.recipients.SigningMethod;
import com.dastakhat.signing.Signature;
import com.dastakhat.signing.SignatureRepository;
import com.dastakhat.users.User;
import com.dastakhat.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The sender facing workflow: create a draft, prepare it, send it, void it.
 */
@Service
public class EnvelopeService {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeService.class);

    private final EnvelopeRepository envelopes;
    private final UserRepository users;
    private final RecipientRepository recipients;
    private final FieldRepository fields;
    private final DocumentRepository documents;
    private final SignatureRepository signatures;
    private final StorageService storage;
    private final PdfValidator pdfValidator;
    private final AuditService audit;
    private final MailService mail;
    private final AppProperties properties;
    private final com.dastakhat.signing.TokenService tokens;

    public EnvelopeService(EnvelopeRepository envelopes,
                           UserRepository users,
                           RecipientRepository recipients,
                           FieldRepository fields,
                           DocumentRepository documents,
                           SignatureRepository signatures,
                           StorageService storage,
                           PdfValidator pdfValidator,
                           AuditService audit,
                           MailService mail,
                           AppProperties properties,
                           com.dastakhat.signing.TokenService tokens) {
        this.envelopes = envelopes;
        this.users = users;
        this.recipients = recipients;
        this.fields = fields;
        this.documents = documents;
        this.signatures = signatures;
        this.storage = storage;
        this.pdfValidator = pdfValidator;
        this.audit = audit;
        this.mail = mail;
        this.properties = properties;
        this.tokens = tokens;
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public Page<Envelope> list(UUID ownerId, EnvelopeStatus status, Pageable pageable) {
        expireOverdue(ownerId);
        if (status == null) {
            return envelopes.findByOwnerId(ownerId, pageable);
        }
        return envelopes.findByOwnerIdAndStatus(ownerId, status, pageable);
    }

    @Transactional(readOnly = true)
    public Envelope requireOwned(UUID ownerId, UUID envelopeId) {
        return envelopes.findByIdAndOwnerId(envelopeId, ownerId)
                .orElseThrow(() -> new NotFoundException("Envelope not found."));
    }

    @Transactional(readOnly = true)
    public Document requireDocument(UUID ownerId, UUID envelopeId, DocumentKind kind) {
        requireOwned(ownerId, envelopeId);
        return documents.findByEnvelopeIdAndKind(envelopeId, kind)
                .orElseThrow(() -> new NotFoundException("The document is not available."));
    }

    // ---------------------------------------------------------------- create

    @Transactional
    public Envelope createDraft(UUID ownerId, MultipartFile file, String title, String actor,
                                 String ip, String userAgent) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new UnprocessableException("Please choose a PDF file to upload.");
        }
        if (file.getSize() > properties.getMaxUploadBytes()) {
            throw new PayloadTooLargeException("The uploaded file is larger than the "
                    + (properties.getMaxUploadBytes() / (1024 * 1024)) + " MB limit.");
        }
        byte[] content = file.getBytes();
        PdfValidator.Inspection inspection = pdfValidator.validate(content);

        String filename = StorageService.sanitizeFilename(file.getOriginalFilename());
        String storageKey = storage.store(content, "pdf");

        User owner = users.findById(ownerId).orElseThrow(() -> new NotFoundException("Sender not found."));
        Envelope envelope = new Envelope();
        envelope.setOwner(owner);
        envelope.setTitle(title == null || title.isBlank() ? filename : title.trim());
        envelope.setStatus(EnvelopeStatus.DRAFT);
        envelope.setRouting(RoutingMode.SEQUENTIAL);
        envelope.setExpiresAt(Instant.now().plus(properties.getDefaultLinkExpiryDays(), ChronoUnit.DAYS));
        envelope.setOriginalSha256(inspection.sha256());
        envelope.setPages(inspection.pages());
        envelope.setLastActivityAt(Instant.now());
        envelope = envelopes.save(envelope);

        Document document = new Document();
        document.setEnvelope(envelope);
        document.setKind(DocumentKind.ORIGINAL);
        document.setStorageKey(storageKey);
        document.setFilename(filename);
        document.setSha256(inspection.sha256());
        document.setPages(inspection.pages());
        document.setSizeBytes(inspection.sizeBytes());
        documents.save(document);

        audit.append(envelope.getId(), AuditEventType.ENVELOPE_CREATED, actor, ip, userAgent,
                inspection.sha256(), Map.of("title", envelope.getTitle(), "pages", inspection.pages()));
        audit.append(envelope.getId(), AuditEventType.DOCUMENT_UPLOADED, actor, ip, userAgent,
                inspection.sha256(), Map.of("filename", filename, "sizeBytes", inspection.sizeBytes()));
        return envelope;
    }

    // ---------------------------------------------------------------- update

    @Transactional
    public Envelope update(UUID ownerId, UUID envelopeId, EnvelopeDtos.EnvelopeUpdateRequest request,
                           String actor, String ip, String userAgent) {
        Envelope envelope = requireOwned(ownerId, envelopeId);
        if (!envelope.isDraft()) {
            throw new ConflictException("Only draft envelopes can be edited.");
        }
        if (request.title() != null && !request.title().isBlank()) {
            envelope.setTitle(request.title().trim());
        }
        if (request.routing() != null && !request.routing().isBlank()) {
            envelope.setRouting(RoutingMode.fromValue(request.routing()));
        }
        if (request.expiresInDays() != null) {
            envelope.setExpiresAt(Instant.now().plus(request.expiresInDays(), ChronoUnit.DAYS));
        }
        envelope.setUpdatedAt(Instant.now());
        envelope.setLastActivityAt(Instant.now());
        envelopes.save(envelope);
        audit.append(envelope.getId(), AuditEventType.ENVELOPE_CREATED, actor, ip, userAgent,
                envelope.getOriginalSha256(), Map.of("updated", true, "title", envelope.getTitle()));
        return envelope;
    }

    @Transactional
    public Envelope replaceRecipients(UUID ownerId, UUID envelopeId,
                                       List<EnvelopeDtos.RecipientInput> input,
                                       String actor, String ip, String userAgent) {
        Envelope envelope = requireOwned(ownerId, envelopeId);
        if (!envelope.isDraft()) {
            throw new ConflictException("Only draft envelopes can be edited.");
        }
        Set<String> seen = new HashSet<>();
        for (EnvelopeDtos.RecipientInput recipientInput : input) {
            String email = recipientInput.email().trim().toLowerCase(Locale.ROOT);
            if (!seen.add(email)) {
                throw new UnprocessableException("The e-mail address " + email + " is used more than once.");
            }
            try {
                SigningMethod.fromValue(recipientInput.method());
            } catch (IllegalArgumentException e) {
                throw new UnprocessableException("Unknown signing method: " + recipientInput.method());
            }
        }

        fields.deleteByEnvelopeId(envelopeId);
        recipients.deleteAll(recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelopeId));

        List<Recipient> created = new ArrayList<>();
        int order = 1;
        for (EnvelopeDtos.RecipientInput recipientInput : input) {
            Recipient recipient = new Recipient();
            recipient.setEnvelope(envelope);
            recipient.setName(recipientInput.name().trim());
            recipient.setEmail(recipientInput.email().trim().toLowerCase(Locale.ROOT));
            recipient.setMethod(SigningMethod.fromValue(recipientInput.method()));
            recipient.setRoutingOrder(recipientInput.routingOrder() == null ? order : recipientInput.routingOrder());
            recipient.setStatus(RecipientStatus.PENDING);
            created.add(recipients.save(recipient));
            order++;
        }
        applyRoutingOrder(envelope, created);

        envelope.setUpdatedAt(Instant.now());
        envelope.setLastActivityAt(Instant.now());
        envelopes.save(envelope);
        audit.append(envelope.getId(), AuditEventType.RECIPIENTS_UPDATED, actor, ip, userAgent,
                envelope.getOriginalSha256(), Map.of("count", created.size()));
        return envelope;
    }

    /**
     * The routing order is normalised: electronic signers come first, then the DSC signers and
     * finally the MOCK eSign signers. Inside a group the order the sender chose is kept.
     */
    private void applyRoutingOrder(Envelope envelope, List<Recipient> list) {
        list.sort(Comparator
                .comparingInt((Recipient r) -> methodGroup(r.getMethod()))
                .thenComparingInt(Recipient::getRoutingOrder)
                .thenComparing(Recipient::getCreatedAt));
        int order = 1;
        for (Recipient recipient : list) {
            recipient.setRoutingOrder(order++);
            recipients.save(recipient);
        }
    }

    private int methodGroup(SigningMethod method) {
        return switch (method) {
            case ELECTRONIC -> 0;
            case DSC -> 1;
            case ESIGN_MOCK -> 2;
        };
    }

    @Transactional
    public Envelope replaceFields(UUID ownerId, UUID envelopeId, List<EnvelopeDtos.FieldInput> input,
                                  String actor, String ip, String userAgent) {
        Envelope envelope = requireOwned(ownerId, envelopeId);
        if (!envelope.isDraft()) {
            throw new ConflictException("Only draft envelopes can be edited.");
        }
        int pages = envelope.getPages() == null ? 1 : envelope.getPages();
        Set<UUID> knownRecipients = new HashSet<>();
        for (Recipient recipient : recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelopeId)) {
            knownRecipients.add(recipient.getId());
        }

        for (EnvelopeDtos.FieldInput fieldInput : input) {
            if (!knownRecipients.contains(fieldInput.recipientId())) {
                throw new UnprocessableException("A field references a recipient that is not part of this envelope.");
            }
            if (fieldInput.page() < 1 || fieldInput.page() > pages) {
                throw new UnprocessableException("A field is placed on page " + fieldInput.page()
                        + " but the document only has " + pages + " pages.");
            }
            if (fieldInput.x() < 0 || fieldInput.y() < 0 || fieldInput.w() <= 0 || fieldInput.h() <= 0) {
                throw new UnprocessableException("A field has an invalid size or position.");
            }
            if (fieldInput.x() + fieldInput.w() > 1.0001 || fieldInput.y() + fieldInput.h() > 1.0001) {
                throw new UnprocessableException("A field lies outside of the page.");
            }
            try {
                FieldType.fromValue(fieldInput.type());
            } catch (IllegalArgumentException e) {
                throw new UnprocessableException("Unknown field type: " + fieldInput.type());
            }
        }

        fields.deleteByEnvelopeId(envelopeId);
        for (EnvelopeDtos.FieldInput fieldInput : input) {
            Field field = new Field();
            field.setEnvelope(envelope);
            field.setRecipient(recipients.findById(fieldInput.recipientId())
                    .orElseThrow(() -> new UnprocessableException("Unknown recipient.")));
            field.setType(FieldType.fromValue(fieldInput.type()));
            field.setPage(fieldInput.page());
            field.setX(fieldInput.x());
            field.setY(fieldInput.y());
            field.setW(fieldInput.w());
            field.setH(fieldInput.h());
            field.setRequired(fieldInput.required() == null || fieldInput.required());
            field.setLabel(fieldInput.label());
            fields.save(field);
        }

        envelope.setUpdatedAt(Instant.now());
        envelope.setLastActivityAt(Instant.now());
        envelopes.save(envelope);
        audit.append(envelope.getId(), AuditEventType.FIELDS_UPDATED, actor, ip, userAgent,
                envelope.getOriginalSha256(), Map.of("count", input.size()));
        return envelope;
    }

    /** The counters of the dashboard. */
    @Transactional(readOnly = true)
    public Map<String, Integer> stats(UUID ownerId) {
        expireOverdue(ownerId);
        Map<String, Integer> stats = new LinkedHashMap<>();
        int total = 0;
        int actionNeeded = 0;
        int awaitingOthers = 0;
        for (EnvelopeStatus status : EnvelopeStatus.values()) {
            int count = (int) envelopes.findByOwnerIdAndStatus(ownerId, status,
                    org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE)).getTotalElements();
            stats.put(status.value(), count);
            total += count;
            if (status == EnvelopeStatus.DRAFT) {
                actionNeeded += count;
            }
            if (status == EnvelopeStatus.SENT) {
                awaitingOthers += count;
            }
        }
        stats.put("total", total);
        stats.put("actionNeeded", actionNeeded);
        stats.put("awaitingOthers", awaitingOthers);
        return stats;
    }

    // ------------------------------------------------------------------ send

    @Transactional
    public Envelope send(UUID ownerId, UUID envelopeId, String actor, String ip, String userAgent) {
        Envelope envelope = requireOwned(ownerId, envelopeId);
        if (envelope.getStatus() != EnvelopeStatus.DRAFT) {
            throw new ConflictException("This envelope has already been sent.");
        }
        List<Recipient> list = recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelopeId);
        if (list.isEmpty()) {
            throw new UnprocessableException("Add at least one recipient before sending.");
        }
        List<Field> allFields = fields.findByEnvelopeIdOrderByPageAsc(envelopeId);
        for (Recipient recipient : list) {
            long signatureFields = allFields.stream()
                    .filter(f -> f.getRecipient().getId().equals(recipient.getId()))
                    .filter(f -> f.getType() == FieldType.SIGNATURE)
                    .count();
            if (signatureFields == 0) {
                throw new ConflictException("The recipient " + recipient.getName()
                        + " does not have a signature field yet.");
            }
            if (recipient.getMethod().isCryptographic() && signatureFields != 1) {
                throw new ConflictException("The recipient " + recipient.getName()
                        + " uses a cryptographic signing method and must have exactly one signature field.");
            }
        }

        envelope.setStatus(EnvelopeStatus.SENT);
        envelope.setSentAt(Instant.now());
        envelope.setUpdatedAt(Instant.now());
        envelope.setLastActivityAt(Instant.now());
        envelopes.save(envelope);

        int notified = issueTokens(envelope, list);
        audit.append(envelope.getId(), AuditEventType.ENVELOPE_SENT, actor, ip, userAgent,
                envelope.getOriginalSha256(), Map.of("recipients", list.size(), "notified", notified));
        return envelope;
    }

    /**
     * Issues the signing tokens. In sequential mode only the first group receives a link, in
     * parallel mode everybody receives one immediately.
     */
    private int issueTokens(Envelope envelope, List<Recipient> list) {
        List<Recipient> toNotify = new ArrayList<>();
        if (envelope.getRouting() == RoutingMode.PARALLEL) {
            toNotify.addAll(list);
        } else {
            int firstOrder = list.stream().mapToInt(Recipient::getRoutingOrder).min().orElse(1);
            for (Recipient recipient : list) {
                if (recipient.getRoutingOrder() == firstOrder) {
                    toNotify.add(recipient);
                }
            }
        }
        Instant expiresAt = envelope.getExpiresAt();
        for (Recipient recipient : toNotify) {
            String rawToken = tokens.issue(recipient, envelope);
            String link = properties.getPublicBaseUrl() + "/sign/" + rawToken;
            mail.sendSigningLink(recipient.getEmail(), recipient.getName(),
                    envelope.getOwner().getName(), envelope.getTitle(), link, expiresAt);
        }
        return toNotify.size();
    }

    @Transactional
    public Envelope voidEnvelope(UUID ownerId, UUID envelopeId, String reason,
                                 String actor, String ip, String userAgent) {
        Envelope envelope = requireOwned(ownerId, envelopeId);
        if (envelope.getStatus() == EnvelopeStatus.COMPLETED) {
            throw new ConflictException("A completed envelope cannot be voided.");
        }
        envelope.setStatus(EnvelopeStatus.VOIDED);
        envelope.setVoidReason(reason);
        envelope.setUpdatedAt(Instant.now());
        envelope.setLastActivityAt(Instant.now());
        envelopes.save(envelope);
        for (Recipient recipient : recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelopeId)) {
            tokens.invalidate(recipient);
            recipients.save(recipient);
        }
        audit.append(envelope.getId(), AuditEventType.VOIDED, actor, ip, userAgent,
                envelope.getOriginalSha256(), Map.of("reason", reason == null ? "" : reason));
        return envelope;
    }

    /** Marks the envelopes whose signing links expired. */
    @Transactional
    public void expireOverdue(UUID ownerId) {
        List<Envelope> overdue = envelopes.findExpired(ownerId, EnvelopeStatus.SENT, Instant.now());
        for (Envelope envelope : overdue) {
            envelope.setStatus(EnvelopeStatus.EXPIRED);
            envelope.setUpdatedAt(Instant.now());
            envelopes.save(envelope);
            for (Recipient recipient : recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelope.getId())) {
                if (recipient.getStatus() != RecipientStatus.SIGNED
                        && recipient.getStatus() != RecipientStatus.DECLINED) {
                    tokens.invalidate(recipient);
                    recipients.save(recipient);
                }
            }
        }
    }

    // -------------------------------------------------------------- helpers

    /** The recipients of an envelope, ordered by the routing order. */
    @Transactional(readOnly = true)
    public List<Recipient> recipientsOf(UUID envelopeId) {
        return recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelopeId);
    }

    /** The fields of an envelope. */
    @Transactional(readOnly = true)
    public List<Field> fieldsOf(UUID envelopeId) {
        return fields.findByEnvelopeIdOrderByPageAsc(envelopeId);
    }

    /** The signature of a recipient, if there is one. */
    @Transactional(readOnly = true)
    public Signature signatureOf(UUID recipientId) {
        return signatures.findFirstByRecipientIdOrderByCreatedAtDesc(recipientId).orElse(null);
    }

    /** All signatures of an envelope, keyed by recipient id. */
    @Transactional(readOnly = true)
    public Map<UUID, Signature> signaturesOf(UUID envelopeId) {
        Map<UUID, Signature> map = new LinkedHashMap<>();
        for (Recipient recipient : recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelopeId)) {
            signatures.findFirstByRecipientIdOrderByCreatedAtDesc(recipient.getId())
                    .ifPresent(signature -> map.put(recipient.getId(), signature));
        }
        return map;
    }
}

