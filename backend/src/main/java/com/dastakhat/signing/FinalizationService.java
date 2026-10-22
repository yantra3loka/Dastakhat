package com.dastakhat.signing;

import com.dastakhat.audit.AuditEventType;
import com.dastakhat.audit.AuditService;
import com.dastakhat.common.Hashing;
import com.dastakhat.documents.Document;
import com.dastakhat.documents.DocumentKind;
import com.dastakhat.documents.DocumentRepository;
import com.dastakhat.documents.StorageService;
import com.dastakhat.envelopes.Envelope;
import com.dastakhat.envelopes.EnvelopeRepository;
import com.dastakhat.envelopes.EnvelopeStatus;
import com.dastakhat.fields.Field;
import com.dastakhat.fields.FieldType;
import com.dastakhat.pki.SealKeyService;
import com.dastakhat.recipients.Recipient;
import com.dastakhat.recipients.RecipientStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the working document, the certificate of completion and the sealed final document.
 *
 * <p>The order matters: the drawn and typed signatures are stamped and flattened <em>before</em>
 * the first cryptographic signature is added. Every later step (DSC signature, MOCK eSign
 * signature, certificate page, platform seal) is an incremental update, so the signatures that
 * were added earlier stay valid.</p>
 */
@Service
public class FinalizationService {

    private static final Logger log = LoggerFactory.getLogger(FinalizationService.class);

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss").withZone(DISPLAY_ZONE);

    private final EnvelopeRepository envelopes;
    private final DocumentRepository documents;
    private final StorageService storage;
    private final StampingService stamping;
    private final PdfCoordinates coordinates;
    private final CertificatePageService certificatePage;
    private final PdfSignatureService signatures;
    private final SealKeyService sealKeys;
    private final AuditService audit;

    public FinalizationService(EnvelopeRepository envelopes,
                               DocumentRepository documents,
                               StorageService storage,
                               StampingService stamping,
                               PdfCoordinates coordinates,
                               CertificatePageService certificatePage,
                               PdfSignatureService signatures,
                               SealKeyService sealKeys,
                               AuditService audit) {
        this.envelopes = envelopes;
        this.documents = documents;
        this.storage = storage;
        this.stamping = stamping;
        this.coordinates = coordinates;
        this.certificatePage = certificatePage;
        this.signatures = signatures;
        this.sealKeys = sealKeys;
        this.audit = audit;
    }

    /** The document the signers are allowed to see. */
    @Transactional(readOnly = true)
    public byte[] currentDocument(Envelope envelope) {
        Document working = documents.findByEnvelopeIdAndKind(envelope.getId(), DocumentKind.WORKING).orElse(null);
        if (working != null) {
            return storage.read(working.getStorageKey());
        }
        Document original = documents.findByEnvelopeIdAndKind(envelope.getId(), DocumentKind.ORIGINAL)
                .orElseThrow(() -> new IllegalStateException("The envelope has no document."));
        return storage.read(original.getStorageKey());
    }

    /**
     * Rebuilds the working document from the original file and the values that were captured by
     * the electronic signers. This is only possible while no cryptographic signature exists -
     * afterwards the document must only be appended to.
     */
    @Transactional
    public byte[] rebuildWorkingDocument(Envelope envelope, List<Recipient> recipients,
                                         List<Field> fields, Map<UUID, Signature> signatureMap) {
        Document original = documents.findByEnvelopeIdAndKind(envelope.getId(), DocumentKind.ORIGINAL)
                .orElseThrow(() -> new IllegalStateException("The envelope has no document."));
        byte[] originalBytes = storage.read(original.getStorageKey());

        List<StampingService.Stamp> stamps = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(originalBytes)) {
            for (Field field : fields) {
                Recipient recipient = field.getRecipient();
                Signature signature = signatureMap.get(recipient.getId());
                boolean isSignatureField = field.getType() == FieldType.SIGNATURE
                        || field.getType() == FieldType.INITIALS;
                if (isSignatureField && (signature == null || signature.getImageKey() == null)) {
                    continue;
                }
                if (!isSignatureField && (field.getValue() == null || field.getValue().isBlank())) {
                    continue;
                }
                if (field.getPage() < 1 || field.getPage() > document.getNumberOfPages()) {
                    continue;
                }
                PDPage page = document.getPage(field.getPage() - 1);
                PdfCoordinates.PdfRect rect = coordinates.toPdfPoints(page,
                        field.getX(), field.getY(), field.getW(), field.getH());

                List<String> lines = new ArrayList<>();
                byte[] image = null;
                switch (field.getType()) {
                    case SIGNATURE -> {
                        image = storage.read(signature.getImageKey());
                        lines.add(signature.getKind() == SignatureKind.TYPED
                                ? "Typed signature: " + safe(signature.getTypedName())
                                : "Drawn signature");
                        lines.add(signature.getRecipient().getName() + " - "
                                + DISPLAY_FORMAT.format(signature.getCreatedAt()));
                    }
                    case INITIALS -> {
                        image = storage.read(signature.getImageKey());
                        lines.add("Initials of " + safe(recipient.getName()));
                    }
                    case DATE -> lines.add("Signed: " + DISPLAY_FORMAT.format(signature == null
                            ? Instant.now() : signature.getCreatedAt()));
                    case NAME -> lines.add(safe(recipient.getName()));
                    case TEXT -> lines.add(safe(field.getValue()));
                    default -> { }
                }
                stamps.add(new StampingService.Stamp(field.getPage() - 1, rect.x(), rect.y(),
                        rect.width(), rect.height(), lines, image, field.getLabel()));
            }
            stamping.flatten(document);
            stamping.stamp(document, stamps);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build the working document", e);
        }
    }

    /** Stores the working document. */
    @Transactional
    public Document storeWorkingDocument(Envelope envelope, byte[] pdf) {
        Document working = documents.findByEnvelopeIdAndKind(envelope.getId(), DocumentKind.WORKING)
                .orElseGet(Document::new);
        String key = working.getStorageKey();
        if (key == null) {
            key = storage.store(pdf, "pdf");
        } else {
            storage.store(pdf, "pdf");
        }
        working.setEnvelope(envelope);
        working.setKind(DocumentKind.WORKING);
        working.setStorageKey(key);
        Document original = documents.findByEnvelopeIdAndKind(envelope.getId(), DocumentKind.ORIGINAL).orElse(null);
        working.setFilename(original == null ? "document.pdf" : original.getFilename());
        working.setSha256(Hashing.sha256Hex(pdf));
        working.setPages(original == null ? null : original.getPages());
        working.setSizeBytes(pdf.length);
        return documents.save(working);
    }

    /** Stores a new working document, replacing the previous one. */
    @Transactional
    public Document replaceWorkingDocument(Envelope envelope, byte[] pdf) {
        Document working = documents.findByEnvelopeIdAndKind(envelope.getId(), DocumentKind.WORKING)
                .orElseGet(Document::new);
        if (working.getStorageKey() != null) {
            storage.delete(working.getStorageKey());
        }
        String key = storage.store(pdf, "pdf");
        working.setEnvelope(envelope);
        working.setKind(DocumentKind.WORKING);
        working.setStorageKey(key);
        Document original = documents.findByEnvelopeIdAndKind(envelope.getId(), DocumentKind.ORIGINAL).orElse(null);
        working.setFilename(original == null ? "document.pdf" : original.getFilename());
        working.setSha256(Hashing.sha256Hex(pdf));
        working.setPages(original == null ? null : original.getPages());
        working.setSizeBytes(pdf.length);
        return documents.save(working);
    }

    /** Stores a document that is prepared for a deferred signature and returns its storage key. */
    @Transactional
    public String storePreparedDocument(Envelope envelope, byte[] pdf) {
        return storage.store(pdf, "pdf");
    }

    /** Reads a prepared document. */
    @Transactional(readOnly = true)
    public byte[] readPreparedDocument(String key) {
        return storage.read(key);
    }

    /**
     * Completes the envelope: the certificate of completion is appended, the platform seal is
     * applied and the final document is stored.
     */
    @Transactional
    public void finalizeEnvelope(Envelope envelope,
                                 List<Recipient> recipients,
                                 List<Field> fields,
                                 Map<UUID, Signature> signatureMap,
                                 String actor, String ip, String userAgent) {
        byte[] working = currentDocument(envelope);
        String signedDocumentSha256 = Hashing.sha256Hex(working);

        List<CertificatePageService.SignerRow> rows = new ArrayList<>();
        for (Recipient recipient : recipients) {
            Signature signature = signatureMap.get(recipient.getId());
            String method = switch (recipient.getMethod()) {
                case ELECTRONIC -> "Electronic";
                case DSC -> "DSC (TEST)";
                case ESIGN_MOCK -> "eSign MOCK";
            };
            rows.add(new CertificatePageService.SignerRow(recipient.getName(), recipient.getEmail(), method,
                    signature == null ? recipient.getSignedAt() : signature.getCreatedAt(), recipient.getIp()));
        }

        Document original = documents.findByEnvelopeIdAndKind(envelope.getId(), DocumentKind.ORIGINAL).orElse(null);
        CertificatePageService.CertificateData data = new CertificatePageService.CertificateData(
                envelope.getId().toString(),
                envelope.getTitle(),
                original == null ? "document.pdf" : original.getFilename(),
                envelope.getPages() == null ? 0 : envelope.getPages(),
                envelope.getOriginalSha256(),
                signedDocumentSha256,
                rows,
                audit.headHash(envelope.getId()),
                "Dastakhat Platform Seal (TEST CA)");

        byte[] withCertificate;
        try {
            withCertificate = certificatePage.append(working, data);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot append the certificate of completion", e);
        }
        String preSealSha256 = Hashing.sha256Hex(withCertificate);

        if (!sealKeys.isAvailable()) {
            sealKeys.load();
        }
        if (!sealKeys.isAvailable()) {
            throw new IllegalStateException("The development seal key is not available. "
                    + "Run the devpki tool to create the TEST certificates.");
        }

        PdfSignatureService.SignedSignature sealed;
        try {
            sealed = signatures.seal(withCertificate, "DastakhatPlatformSeal", Instant.now(),
                    sealKeys.getPrivateKey(), sealKeys.getCertificate(), sealKeys.getChain());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot apply the platform seal", e);
        }

        // The seal has to validate before the document is accepted as final.
        List<PdfSignatureService.SignatureValidation> validations =
                signatures.validate(sealed.pdfBytes(), sealKeys.getTrustRoots());
        boolean sealValid = validations.stream().anyMatch(v -> v.intact() && v.valid());
        if (!sealValid) {
            String reasons = validations.stream().flatMap(v -> v.messages().stream()).toList().toString();
            throw new IllegalStateException("The platform seal does not validate: " + reasons);
        }

        Document finalDocument = documents.findByEnvelopeIdAndKind(envelope.getId(), DocumentKind.FINAL)
                .orElseGet(Document::new);
        if (finalDocument.getStorageKey() != null) {
            storage.delete(finalDocument.getStorageKey());
        }
        finalDocument.setEnvelope(envelope);
        finalDocument.setKind(DocumentKind.FINAL);
        finalDocument.setStorageKey(storage.store(sealed.pdfBytes(), "pdf"));
        finalDocument.setFilename(original == null ? "document.pdf" : original.getFilename());
        finalDocument.setSha256(sealed.documentSha256());
        finalDocument.setPages(envelope.getPages());
        finalDocument.setSizeBytes(sealed.pdfBytes().length);
        documents.save(finalDocument);

        envelope.setStatus(EnvelopeStatus.COMPLETED);
        envelope.setCompletedAt(Instant.now());
        envelope.setPreSealSha256(preSealSha256);
        envelope.setFinalSha256(sealed.documentSha256());
        envelope.setUpdatedAt(Instant.now());
        envelope.setLastActivityAt(Instant.now());
        envelopes.save(envelope);

        audit.append(envelope.getId(), AuditEventType.SEALED, actor, ip, userAgent,
                sealed.documentSha256(), Map.of(
                        "preSealSha256", preSealSha256,
                        "finalSha256", sealed.documentSha256(),
                        "signedDocumentSha256", signedDocumentSha256,
                        "sealedWith", "Dastakhat Platform Seal (TEST CA)"));
        log.info("Envelope {} sealed: final sha256 {}", envelope.getId(), sealed.documentSha256());
    }

    /** True when every recipient has signed. */
    public static boolean allSigned(List<Recipient> recipients) {
        return recipients.stream().allMatch(r -> r.getStatus() == RecipientStatus.SIGNED);
    }

    private String safe(String value) {
        return value == null ? "" : value.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
