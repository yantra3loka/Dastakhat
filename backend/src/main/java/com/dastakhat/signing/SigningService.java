package com.dastakhat.signing;

import com.dastakhat.audit.AuditEventType;
import com.dastakhat.audit.AuditService;
import com.dastakhat.common.ApiException;
import com.dastakhat.common.ConflictException;
import com.dastakhat.common.Hashing;
import com.dastakhat.common.NotFoundException;
import com.dastakhat.common.RateLimiter;
import com.dastakhat.common.UnprocessableException;
import com.dastakhat.config.AppProperties;
import com.dastakhat.documents.Document;
import com.dastakhat.documents.DocumentKind;
import com.dastakhat.documents.DocumentRepository;
import com.dastakhat.documents.StorageService;
import com.dastakhat.envelopes.Envelope;
import com.dastakhat.envelopes.EnvelopeRepository;
import com.dastakhat.envelopes.EnvelopeStatus;
import com.dastakhat.envelopes.EnvelopeService;
import com.dastakhat.envelopes.RoutingMode;
import com.dastakhat.fields.Field;
import com.dastakhat.fields.FieldRepository;
import com.dastakhat.fields.FieldType;
import com.dastakhat.mail.MailService;
import com.dastakhat.recipients.Recipient;
import com.dastakhat.recipients.RecipientRepository;
import com.dastakhat.recipients.RecipientStatus;
import com.dastakhat.recipients.SigningMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The signer facing workflow. Signers never have an account, they use a single use token.
 */
@Service
public class SigningService {

    private static final Logger log = LoggerFactory.getLogger(SigningService.class);

    private static final Duration SIGN_WINDOW = Duration.ofMinutes(30);
    private static final int SIGN_REQUESTS = 120;
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss").withZone(DISPLAY_ZONE);

    private final RecipientRepository recipients;
    private final EnvelopeRepository envelopes;
    private final FieldRepository fields;
    private final DocumentRepository documents;
    private final SignatureRepository signatures;
    private final StorageService storage;
    private final AuditService audit;
    private final MailService mail;
    private final RateLimiter rateLimiter;
    private final AppProperties properties;
    private final FinalizationService finalization;
    private final TokenService tokens;

    public SigningService(RecipientRepository recipients,
                          EnvelopeRepository envelopes,
                          FieldRepository fields,
                          DocumentRepository documents,
                          SignatureRepository signatures,
                          StorageService storage,
                          AuditService audit,
                          MailService mail,
                          RateLimiter rateLimiter,
                          AppProperties properties,
                          FinalizationService finalization,
                          TokenService tokens) {
        this.recipients = recipients;
        this.envelopes = envelopes;
        this.fields = fields;
        this.documents = documents;
        this.signatures = signatures;
        this.storage = storage;
        this.audit = audit;
        this.mail = mail;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.finalization = finalization;
        this.tokens = tokens;
    }

    /** Resolves a raw token into the recipient it belongs to. */
    @Transactional(readOnly = true)
    public Recipient resolve(String rawToken) {
        if (rawToken == null || rawToken.length() < 16 || rawToken.length() > 256) {
            throw new NotFoundException("This signing link is not valid.");
        }
        Optional<Recipient> found = recipients.findByTokenHash(Hashing.sha256Hex(rawToken));
        if (found.isEmpty()) {
            // A token that was already used is reported as such, a token that never existed
            // is not found at all.
            if (recipients.existsByUsedTokenHash(Hashing.sha256Hex(rawToken))) {
                throw new GoneException("This signing link has already been used.");
            }
            throw new NotFoundException("This signing link is not valid.");
        }
        Recipient recipient = found.get();
        Envelope envelope = recipient.getEnvelope();

        if (envelope.getStatus() == EnvelopeStatus.VOIDED) {
            throw new GoneException("This envelope has been cancelled.");
        }
        if (envelope.getStatus() == EnvelopeStatus.COMPLETED) {
            throw new GoneException("This envelope has already been completed.");
        }
        if (recipient.getStatus() == RecipientStatus.SIGNED) {
            throw new GoneException("This signing link has already been used.");
        }
        if (recipient.getStatus() == RecipientStatus.DECLINED) {
            throw new GoneException("This signing link has already been used.");
        }
        if (recipient.getTokenExpiresAt() == null || recipient.getTokenExpiresAt().isBefore(Instant.now())) {
            throw new GoneException("This signing link has expired.");
        }
        return recipient;
    }

    /** 410 - the link exists but cannot be used any more. */
    public static class GoneException extends ApiException {
        public GoneException(String message) {
            super(410, "gone", message);
        }
    }

    /** 425 - it is not this recipient's turn yet. */
    public static class NotYourTurnException extends ApiException {
        public NotYourTurnException(String message) {
            super(425, "not_your_turn", message);
        }
    }

    /** The view model of the signer page. */
    @Transactional
    public SignerDtos.SignerView signerView(String rawToken, String ip, String userAgent) {
        rateLimiter.tryAcquire("sign:" + ip, SIGN_REQUESTS, SIGN_WINDOW);
        Recipient recipient = resolve(rawToken);
        Envelope envelope = recipient.getEnvelope();
        List<Recipient> all = recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelope.getId());
        requireTurn(recipient, all);

        if (recipient.getStatus() == RecipientStatus.PENDING) {
            recipient.setStatus(RecipientStatus.VIEWED);
        }
        if (recipient.getViewedAt() == null) {
            recipient.setViewedAt(Instant.now());
            recipients.save(recipient);
            audit.append(envelope.getId(), AuditEventType.DOCUMENT_VIEWED, recipient.getEmail(), ip, userAgent,
                    envelope.getOriginalSha256(), Map.of("recipient", recipient.getName()));
        }

        List<SignerDtos.FieldView> fieldViews = new ArrayList<>();
        for (Field field : fields.findByRecipientId(recipient.getId())) {
            fieldViews.add(new SignerDtos.FieldView(field.getId(), field.getType().value(), field.getPage(),
                    field.getX(), field.getY(), field.getW(), field.getH(), field.isRequired(),
                    field.getLabel(), field.getValue()));
        }
        Document original = documents.findByEnvelopeIdAndKind(envelope.getId(), DocumentKind.ORIGINAL).orElse(null);
        SignerDtos.DocumentInfo documentInfo = original == null ? null : new SignerDtos.DocumentInfo(
                original.getFilename(), original.getSha256(), original.getPages(), original.getSizeBytes());

        return new SignerDtos.SignerView(
                envelope.getId(),
                envelope.getTitle(),
                envelope.getOwner().getName(),
                envelope.getOwner().getEmail(),
                recipient.getName(),
                recipient.getEmail(),
                recipient.getMethod().value(),
                recipient.getTokenExpiresAt(),
                recipient.getConsentRecordedAt() != null,
                documentInfo,
                fieldViews,
                "/api/v1/sign/" + rawToken + "/document",
                SignerDtos.LEGAL_NOTICE,
                recipient.getMethod() == SigningMethod.ESIGN_MOCK);
    }

    /** The document this signer is allowed to see. */
    @Transactional(readOnly = true)
    public byte[] document(String rawToken) {
        rateLimiter.tryAcquire("sign-doc:" + rawToken.hashCode(), SIGN_REQUESTS, SIGN_WINDOW);
        Recipient recipient = resolve(rawToken);
        Envelope envelope = recipient.getEnvelope();
        List<Recipient> all = recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelope.getId());
        requireTurn(recipient, all);
        return finalization.currentDocument(envelope);
    }

    /** Records the consent of the signer. */
    @Transactional
    public void consent(String rawToken, boolean consent, String consentText, String ip, String userAgent) {
        rateLimiter.tryAcquire("sign-consent:" + rawToken.hashCode(), SIGN_REQUESTS, SIGN_WINDOW);
        Recipient recipient = resolve(rawToken);
        Envelope envelope = recipient.getEnvelope();
        List<Recipient> all = recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelope.getId());
        requireTurn(recipient, all);
        if (!consent) {
            throw new UnprocessableException("The consent has to be given before signing.");
        }
        recipient.setConsentRecordedAt(Instant.now());
        recipient.setConsentText(consentText == null ? SignerDtos.LEGAL_NOTICE : consentText);
        recipients.save(recipient);
        audit.append(envelope.getId(), AuditEventType.CONSENT_RECORDED, recipient.getEmail(), ip, userAgent,
                envelope.getOriginalSha256(), Map.of("recipient", recipient.getName()));
    }

    /** Stores the signature of a recipient. */
    @Transactional
    public SignerDtos.CompleteResponse complete(String rawToken, SignerDtos.CompleteRequest request,
                                                String ip, String userAgent) {
        rateLimiter.tryAcquire("sign-complete:" + rawToken.hashCode(), 20, SIGN_WINDOW);
        Recipient recipient = resolve(rawToken);
        Envelope envelope = recipient.getEnvelope();
        List<Recipient> all = recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelope.getId());
        requireTurn(recipient, all);

        if (recipient.getConsentRecordedAt() == null && !request.consent()) {
            throw new UnprocessableException("Please give your consent before signing.");
        }
        if (recipient.getConsentRecordedAt() == null) {
            recipient.setConsentRecordedAt(Instant.now());
            recipient.setConsentText(SignerDtos.LEGAL_NOTICE);
            recipients.save(recipient);
            audit.append(envelope.getId(), AuditEventType.CONSENT_RECORDED, recipient.getEmail(), ip, userAgent,
                    envelope.getOriginalSha256(), Map.of("recipient", recipient.getName()));
        }

        if (recipient.getMethod().isCryptographic()) {
            throw new ConflictException("This recipient has to sign with the "
                    + (recipient.getMethod() == SigningMethod.DSC ? "DSC agent" : "MOCK eSign provider") + ".");
        }

        List<Field> recipientFields = fields.findByRecipientId(recipient.getId());
        Map<UUID, SignerDtos.FieldValue> submitted = new java.util.HashMap<>();
        for (SignerDtos.FieldValue value : request.fields()) {
            submitted.put(value.fieldId(), value);
        }

        // Every required field needs a value and the signature field needs an image.
        for (Field field : recipientFields) {
            SignerDtos.FieldValue value = submitted.get(field.getId());
            boolean hasImage = value != null && value.signatureImageBase64() != null
                    && !value.signatureImageBase64().isBlank();
            boolean hasText = value != null && value.value() != null && !value.value().isBlank();
            if (field.getType() == FieldType.SIGNATURE || field.getType() == FieldType.INITIALS) {
                if (field.isRequired() && !hasImage) {
                    throw new UnprocessableException("Please add your signature before finishing.");
                }
            } else if (field.isRequired() && !hasText) {
                throw new UnprocessableException("Please fill in the field \"" + describe(field) + "\".");
            }
        }

        // Store the signature image (at most one per recipient).
        byte[] imageBytes = null;
        String typedName = null;
        SignatureKind kind = SignatureKind.DRAWN;
        for (Field field : recipientFields) {
            SignerDtos.FieldValue value = submitted.get(field.getId());
            if (value == null || value.signatureImageBase64() == null || value.signatureImageBase64().isBlank()) {
                continue;
            }
            if (field.getType() != FieldType.SIGNATURE && field.getType() != FieldType.INITIALS) {
                continue;
            }
            byte[] png = decodeImage(value.signatureImageBase64());
            imageBytes = png;
            typedName = value.value();
            kind = typedName != null && !typedName.isBlank() ? SignatureKind.TYPED : SignatureKind.DRAWN;
            break;
        }

        Signature signature = null;
        if (imageBytes != null) {
            signature = new Signature();
            signature.setRecipient(recipient);
            signature.setKind(kind);
            signature.setImageKey(storage.store(imageBytes, "png"));
            signature.setTypedName(typedName);
            signature.setCreatedAt(Instant.now());
            signatures.save(signature);
        }

        // Store the field values.
        Instant signedAt = Instant.now();
        for (Field field : recipientFields) {
            SignerDtos.FieldValue value = submitted.get(field.getId());
            switch (field.getType()) {
                case DATE -> field.setValue(DISPLAY_FORMAT.format(signedAt));
                case NAME -> field.setValue(recipient.getName());
                case TEXT -> {
                    if (value != null && value.value() != null) {
                        field.setValue(value.value());
                    }
                }
                case SIGNATURE, INITIALS -> {
                    if (signature != null && value != null && value.value() != null
                            && !value.value().isBlank() && field.getType() == FieldType.INITIALS) {
                        field.setValue(value.value());
                    }
                }
                default -> { }
            }
            fields.save(field);
        }

        recipient.setStatus(RecipientStatus.SIGNED);
        recipient.setSignedAt(signedAt);
        recipient.setIp(ip);
        recipient.setUserAgent(userAgent);
        tokens.invalidate(recipient);
        recipients.save(recipient);

        audit.append(envelope.getId(), AuditEventType.SIGNED, recipient.getEmail(), ip, userAgent,
                envelope.getOriginalSha256(), Map.of(
                        "recipient", recipient.getName(),
                        "method", recipient.getMethod().value(),
                        "signedAt", signedAt.toString()));

        // Rebuild the working document with the new values.
        List<Field> allFields = fields.findByEnvelopeIdOrderByPageAsc(envelope.getId());
        Map<UUID, Signature> signatureMap = signatureMap(envelope.getId());
        byte[] working = finalization.rebuildWorkingDocument(envelope, all, allFields, signatureMap);
        finalization.replaceWorkingDocument(envelope, working);

        int notified = 0;
        if (allSigned(all)) {
            finalization.finalizeEnvelope(envelope, all, allFields, signatureMap,
                    "system", ip, userAgent);
            notified = notifyCompletion(envelope, all);
        } else {
            notified = advanceRouting(envelope, all);
        }
        return new SignerDtos.CompleteResponse("signed", recipient.getId(), signedAt,
                envelopes.findById(envelope.getId()).map(e -> e.getStatus().value()).orElse("sent"), notified);
    }

    /** Declines to sign. The whole workflow stops. */
    @Transactional
    public void decline(String rawToken, String reason, String ip, String userAgent) {
        rateLimiter.tryAcquire("sign-decline:" + rawToken.hashCode(), 20, SIGN_WINDOW);
        Recipient recipient = resolve(rawToken);
        Envelope envelope = recipient.getEnvelope();
        List<Recipient> all = recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelope.getId());
        requireTurn(recipient, all);

        recipient.setStatus(RecipientStatus.DECLINED);
        recipient.setDeclinedAt(Instant.now());
        recipient.setDeclineReason(reason);
        recipient.setIp(ip);
        recipient.setUserAgent(userAgent);
        tokens.invalidate(recipient);
        recipients.save(recipient);

        envelope.setStatus(EnvelopeStatus.DECLINED);
        envelope.setUpdatedAt(Instant.now());
        envelope.setLastActivityAt(Instant.now());
        envelopes.save(envelope);

        for (Recipient other : all) {
            tokens.invalidate(other);
        }
        audit.append(envelope.getId(), AuditEventType.DECLINED, recipient.getEmail(), ip, userAgent,
                envelope.getOriginalSha256(), Map.of("recipient", recipient.getName(), "reason", reason));
    }

    // ------------------------------------------------------------- helpers

    /** Throws when it is not the turn of the given recipient. */
    public void requireTurn(Recipient recipient, List<Recipient> all) {
        Envelope envelope = recipient.getEnvelope();

        // A cryptographic signature may only be added after every electronic signature, because
        // the document must be flattened before the first cryptographic signature.
        if (recipient.getMethod().isCryptographic()) {
            boolean electronicDone = all.stream()
                    .filter(r -> r.getMethod() == SigningMethod.ELECTRONIC)
                    .allMatch(r -> r.getStatus() == RecipientStatus.SIGNED);
            if (!electronicDone) {
                throw new NotYourTurnException("Please wait until the other signers have finished.");
            }
        }

        if (envelope.getRouting() == RoutingMode.SEQUENTIAL) {
            int activeOrder = all.stream()
                    .filter(r -> r.getStatus() != RecipientStatus.SIGNED && r.getStatus() != RecipientStatus.DECLINED)
                    .mapToInt(Recipient::getRoutingOrder)
                    .min()
                    .orElse(recipient.getRoutingOrder());
            if (recipient.getRoutingOrder() != activeOrder) {
                throw new NotYourTurnException("Please wait until the other signers have finished.");
            }
        }
    }

    /** Issues the links of the next routing group. */
    public int advanceRouting(Envelope envelope, List<Recipient> all) {
        if (envelope.getRouting() != RoutingMode.SEQUENTIAL) {
            return 0;
        }
        int activeOrder = all.stream()
                .filter(r -> r.getStatus() != RecipientStatus.SIGNED && r.getStatus() != RecipientStatus.DECLINED)
                .mapToInt(Recipient::getRoutingOrder)
                .min()
                .orElse(-1);
        if (activeOrder < 0) {
            return 0;
        }
        int notified = 0;
        for (Recipient recipient : all) {
            if (recipient.getRoutingOrder() != activeOrder) {
                continue;
            }
            String rawToken = tokens.issue(recipient, envelope);
            String link = properties.getPublicBaseUrl() + "/sign/" + rawToken;
            mail.sendSigningLink(recipient.getEmail(), recipient.getName(),
                    envelope.getOwner().getName(), envelope.getTitle(), link, recipient.getTokenExpiresAt());
            notified++;
        }
        return notified;
    }

    /** Notifies everybody that the envelope is complete. */
    private int notifyCompletion(Envelope envelope, List<Recipient> all) {
        int notified = 0;
        for (Recipient recipient : all) {
            String token = Hashing.newToken();
            recipient.setDownloadTokenHash(Hashing.sha256Hex(token));
            recipient.setDownloadTokenExpiresAt(Instant.now().plus(7, java.time.temporal.ChronoUnit.DAYS));
            recipients.save(recipient);
            String link = properties.getPublicBaseUrl() + "/download/" + token;
            mail.sendCompletion(recipient.getEmail(), recipient.getName(), envelope.getTitle(), link);
            notified++;
        }
        return notified;
    }

    /** Resolves a download token to the recipient it belongs to. */
    @Transactional(readOnly = true)
    public Recipient resolveDownloadToken(String rawToken) {
        if (rawToken == null || rawToken.length() < 16) {
            throw new NotFoundException("This download link is not valid.");
        }
        Optional<Recipient> found = recipients.findAll().stream()
                .filter(r -> Hashing.constantTimeEquals(r.getDownloadTokenHash(), Hashing.sha256Hex(rawToken)))
                .findFirst();
        if (found.isEmpty()) {
            throw new NotFoundException("This download link is not valid.");
        }
        Recipient recipient = found.get();
        if (recipient.getDownloadTokenExpiresAt() == null
                || recipient.getDownloadTokenExpiresAt().isBefore(Instant.now())) {
            throw new GoneException("This download link has expired.");
        }
        return recipient;
    }

    /** The final document for a download token. */
    @Transactional
    public byte[] finalDocumentForDownload(String rawToken, String ip, String userAgent) {
        Recipient recipient = resolveDownloadToken(rawToken);
        Envelope envelope = recipient.getEnvelope();
        Document finalDocument = documents.findByEnvelopeIdAndKind(envelope.getId(), DocumentKind.FINAL)
                .orElseThrow(() -> new NotFoundException("The final document is not available."));
        audit.append(envelope.getId(), AuditEventType.DOWNLOADED, recipient.getEmail(), ip, userAgent,
                finalDocument.getSha256(), Map.of("kind", "final", "recipient", recipient.getName()));
        return storage.read(finalDocument.getStorageKey());
    }

    private Map<UUID, Signature> signatureMap(UUID envelopeId) {
        Map<UUID, Signature> map = new java.util.LinkedHashMap<>();
        for (Recipient recipient : recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelopeId)) {
            signatures.findFirstByRecipientIdOrderByCreatedAtDesc(recipient.getId())
                    .ifPresent(signature -> map.put(recipient.getId(), signature));
        }
        return map;
    }

    /** The recipients of an envelope. */
    @Transactional(readOnly = true)
    public List<Recipient> recipientsOfEnvelope(UUID envelopeId) {
        return recipients.findByEnvelopeIdOrderByRoutingOrderAsc(envelopeId);
    }

    /** The signatures of an envelope, keyed by the recipient id. */
    @Transactional(readOnly = true)
    public Map<UUID, Signature> signatureMapOf(UUID envelopeId) {
        return signatureMap(envelopeId);
    }

    /** Persists a recipient that was changed by another service. */
    @Transactional
    public void saveRecipient(Recipient recipient) {
        recipients.save(recipient);
    }

    private boolean allSigned(List<Recipient> all) {
        return all.stream().allMatch(r -> r.getStatus() == RecipientStatus.SIGNED);
    }

    /** Decodes and validates a signature image (a PNG data URL). */
    byte[] decodeImage(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            throw new UnprocessableException("The signature image is missing.");
        }
        String base64 = dataUrl;
        int comma = dataUrl.indexOf(',');
        if (dataUrl.startsWith("data:") && comma > 0) {
            String header = dataUrl.substring(5, comma).toLowerCase();
            if (!header.contains("png")) {
                throw new UnprocessableException("The signature image has to be a PNG.");
            }
            base64 = dataUrl.substring(comma + 1);
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new UnprocessableException("The signature image could not be decoded.");
        }
        if (bytes.length > properties.getMaxSignatureBytes()) {
            throw new UnprocessableException("The signature image is larger than "
                    + (properties.getMaxSignatureBytes() / 1024) + " KB.");
        }
        if (bytes.length < 8 || (byte) 0x89 != bytes[0] || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
            throw new UnprocessableException("The signature image is not a valid PNG.");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
                throw new UnprocessableException("The signature image could not be read.");
            }
            if (image.getWidth() > 4000 || image.getHeight() > 4000) {
                throw new UnprocessableException("The signature image has unexpected dimensions.");
            }
        } catch (IOException e) {
            throw new UnprocessableException("The signature image could not be read.");
        }
        return bytes;
    }

    private String describe(Field field) {
        if (field.getLabel() != null && !field.getLabel().isBlank()) {
            return field.getLabel();
        }
        return field.getType().value();
    }
}

