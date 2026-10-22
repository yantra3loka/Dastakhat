package com.dastakhat.dsc;

import com.dastakhat.audit.AuditEventType;
import com.dastakhat.audit.AuditService;
import com.dastakhat.common.ConflictException;
import com.dastakhat.common.Hashing;
import com.dastakhat.common.UnprocessableException;
import com.dastakhat.documents.DocumentRepository;
import com.dastakhat.envelopes.Envelope;
import com.dastakhat.envelopes.EnvelopeRepository;
import com.dastakhat.envelopes.EnvelopeStatus;
import com.dastakhat.fields.Field;
import com.dastakhat.fields.FieldRepository;
import com.dastakhat.fields.FieldType;
import com.dastakhat.pki.CertificateChainValidator;
import com.dastakhat.pki.SealKeyService;
import com.dastakhat.recipients.Recipient;
import com.dastakhat.recipients.RecipientStatus;
import com.dastakhat.recipients.SigningMethod;
import com.dastakhat.signing.FinalizationService;
import com.dastakhat.signing.PdfCoordinates;
import com.dastakhat.signing.PdfSignatureService;
import com.dastakhat.signing.Signature;
import com.dastakhat.signing.SignatureKind;
import com.dastakhat.signing.SignatureRepository;
import com.dastakhat.signing.SignerDtos;
import com.dastakhat.signing.SigningService;
import com.dastakhat.signing.StampingService;
import com.dastakhat.signing.TokenService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stage 2: signing with a Digital Signature Certificate through the local PKCS#11 agent.
 *
 * <p>The private key never leaves the token. The backend reserves the signature inside the PDF,
 * hands the signed attributes to the agent and embeds the returned signature afterwards.</p>
 */
@Service
public class DscService {

    private static final Logger log = LoggerFactory.getLogger(DscService.class);

    private final com.dastakhat.signing.SigningSessionRepository sessions;
    private final SigningService signing;
    private final FinalizationService finalization;
    private final FieldRepository fields;
    private final DocumentRepository documents;
    private final EnvelopeRepository envelopes;
    private final SignatureRepository signatures;
    private final SealKeyService sealKeys;
    private final PdfSignatureService pdfSignatures;
    private final PdfCoordinates coordinates;
    private final StampingService stamping;
    private final AuditService audit;
    private final TokenService tokens;

    public DscService(com.dastakhat.signing.SigningSessionRepository sessions,
                       SigningService signing,
                       FinalizationService finalization,
                       FieldRepository fields,
                       DocumentRepository documents,
                       EnvelopeRepository envelopes,
                       SignatureRepository signatures,
                       SealKeyService sealKeys,
                       PdfSignatureService pdfSignatures,
                       PdfCoordinates coordinates,
                       StampingService stamping,
                       AuditService audit,
                       TokenService tokens) {
        this.sessions = sessions;
        
        this.signing = signing;
        this.finalization = finalization;
        this.fields = fields;
        this.documents = documents;
        this.envelopes = envelopes;
        this.signatures = signatures;
        this.sealKeys = sealKeys;
        this.pdfSignatures = pdfSignatures;
        this.coordinates = coordinates;
        this.stamping = stamping;
        this.audit = audit;
        this.tokens = tokens;
    }

    /** Prepares the signature and returns the bytes the token has to sign. */
    @Transactional
    public SignerDtos.DscPrepareResponse prepare(String rawToken, String certificatePem,
                                                  String ip, String userAgent) throws IOException {        Recipient recipient = signing.resolve(rawToken);
        Envelope envelope = recipient.getEnvelope();
        if (recipient.getMethod() != SigningMethod.DSC) {
            throw new ConflictException("This recipient does not sign with a DSC.");
        }
        List<Recipient> all = signing.recipientsOfEnvelope(envelope.getId());
        signing.requireTurn(recipient, all);

        X509Certificate certificate = parseCertificate(certificatePem);
        validateSignerCertificate(certificate);

        byte[] working = finalization.currentDocument(envelope);
        Instant signingTime = Instant.now();
        List<Field> recipientFields = fields.findByRecipientId(recipient.getId());
        Field signatureField = recipientFields.stream()
                .filter(f -> f.getType() == FieldType.SIGNATURE)
                .findFirst()
                .orElseThrow(() -> new UnprocessableException("This recipient has no signature field."));

        PdfSignatureService.PreparedSignature prepared = pdfSignatures.prepareDeferred(
                working, "DastakhatDscSignature", signingTime, certificate, List.of(),
                document -> drawSignatureBlock(document, envelope, recipient, signatureField, "DSC (TEST)"));

        // Store the prepared document.
        String preparedKey = finalization.storePreparedDocument(envelope, prepared.pdfBytes());

        com.dastakhat.signing.SigningSession session = new com.dastakhat.signing.SigningSession();
        session.setRecipient(recipient);
        session.setKind(com.dastakhat.signing.SigningSession.Kind.DSC);
        session.setPreparedKey(preparedKey);
        session.setDocumentDigest(Hashing.toHex(prepared.documentDigest()));
        session.setDocumentSha256(Hashing.sha256Hex(prepared.pdfBytes()));
        session.setSignedAttrsDer(prepared.signedAttributesDer());
        session.setStatus(com.dastakhat.signing.SigningSession.Status.PREPARED);
        session.setExpiresAt(Instant.now().plusSeconds(600));
        session.setCertPem(certificatePem);
        session.setCertSha256(certSha256(certificate));
        session.setSignatureAlgorithm(com.dastakhat.pki.CmsBuilder.SIGNATURE_ALGORITHM);
        session.setByteRange(java.util.Arrays.toString(prepared.byteRange()));
        session = sessions.save(session);

        audit.append(envelope.getId(), AuditEventType.DOCUMENT_VIEWED, recipient.getEmail(), ip, userAgent,
                session.getDocumentSha256(), Map.of("dscPrepare", true, "session", session.getId().toString()));

        return new SignerDtos.DscPrepareResponse(
                session.getId(),
                Base64.getEncoder().encodeToString(prepared.signedAttributesDer()),
                session.getDocumentSha256(),
                envelope.getTitle(),
                "SHA256withRSA",
                session.getExpiresAt());
    }

    /** Embeds the signature of the token and validates the result. */
    @Transactional
    public SignerDtos.DscFinishResponse finish(String rawToken, UUID sessionId, String signatureBase64,
                                                String ip, String userAgent) throws IOException {
        Recipient recipient = signing.resolve(rawToken);
        Envelope envelope = recipient.getEnvelope();
        if (recipient.getMethod() != SigningMethod.DSC) {
            throw new ConflictException("This recipient does not sign with a DSC.");
        }
        com.dastakhat.signing.SigningSession session = sessions.findByIdAndRecipientId(sessionId, recipient.getId())
                .orElseThrow(() -> new UnprocessableException("The signing session does not exist."));
        if (session.getStatus() != com.dastakhat.signing.SigningSession.Status.PREPARED) {
            throw new UnprocessableException("The signing session has already been used.");
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            session.setStatus(com.dastakhat.signing.SigningSession.Status.EXPIRED);
            sessions.save(session);
            throw new UnprocessableException("The signing session has expired. Please start again.");
        }

        byte[] rawSignature;
        try {
            rawSignature = Base64.getDecoder().decode(signatureBase64 == null ? "" : signatureBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new UnprocessableException("The signature of the token could not be decoded.");
        }
        if (rawSignature.length == 0) {
            throw new UnprocessableException("The token did not return a signature.");
        }

        X509Certificate certificate = parseCertificate(session.getCertPem());

        // The signature has to verify against the certificate before anything is embedded.
        try {
            java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA", com.dastakhat.pki.CmsBuilder.provider());
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(session.getSignedAttrsDer());
            if (!verifier.verify(rawSignature)) {
                throw new UnprocessableException("The signature of the token does not verify with "
                        + "the selected certificate.");
            }
        } catch (UnprocessableException e) {
            throw e;
        } catch (Exception e) {
            throw new UnprocessableException("The signature of the token could not be verified.");
        }

        byte[] preparedPdf = finalization.readPreparedDocument(session.getPreparedKey());
        PdfSignatureService.PreparedSignature prepared = new PdfSignatureService.PreparedSignature(
                preparedPdf,
                Hashing.hexToBytes(session.getDocumentDigest()),
                session.getSignedAttrsDer(),
                parseByteRange(session.getByteRange()),
                session.getCreatedAt());

        PdfSignatureService.SignedSignature signed = pdfSignatures.finishDeferred(prepared, rawSignature,
                certificate, List.of());

        List<PdfSignatureService.SignatureValidation> validations =
                pdfSignatures.validate(signed.pdfBytes(), sealKeys.getTrustRoots());
        PdfSignatureService.SignatureValidation dscValidation = validations.stream()
                .filter(v -> "DastakhatDscSignature".equals(v.fieldName()))
                .findFirst()
                .orElse(validations.isEmpty() ? null : validations.get(validations.size() - 1));
        if (dscValidation == null || !dscValidation.intact() || !dscValidation.valid()) {
            throw new UnprocessableException("The signature could not be validated after embedding: "
                    + (dscValidation == null ? "no signature found" : String.join("; ", dscValidation.messages())));
        }

        // The signed document becomes the new working document.
        finalization.replaceWorkingDocument(envelope, signed.pdfBytes());

        Signature signature = new Signature();
        signature.setRecipient(recipient);
        signature.setKind(SignatureKind.DSC);
        signature.setSignerCertPem(session.getCertPem());
        signature.setSignerCertSha256(session.getCertSha256());
        signature.setSignerCertSubject(certificate.getSubjectX500Principal().getName());
        signature.setCmsSha256(Hashing.sha256Hex(signed.cmsDer()));
        signature.setSignatureSha256(Hashing.sha256Hex(rawSignature));
        signature.setCreatedAt(Instant.now());
        signatures.save(signature);

        session.setStatus(com.dastakhat.signing.SigningSession.Status.FINISHED);
        session.setUsedAt(Instant.now());
        session.setCmsDer(signed.cmsDer());
        sessions.save(session);

        recipient.setStatus(RecipientStatus.SIGNED);
        recipient.setSignedAt(Instant.now());
        recipient.setIp(ip);
        recipient.setUserAgent(userAgent);
        tokens.invalidate(recipient);
        recipientsSave(recipient);

        audit.append(envelope.getId(), AuditEventType.DSC_SIGNED, recipient.getEmail(), ip, userAgent,
                signed.documentSha256(), Map.of(
                        "recipient", recipient.getName(),
                        "certificateSubject", certificate.getSubjectX500Principal().getName(),
                        "trusted", dscValidation.trusted()));

        advance(envelope, recipient, ip, userAgent);

        return new SignerDtos.DscFinishResponse("signed",
                Hashing.sha256Hex(rawSignature), true, dscValidation.trusted(),
                envelopes.findById(envelope.getId()).map(e -> e.getStatus().value()).orElse("sent"));
    }

    // ------------------------------------------------------------- helpers

    private void advance(Envelope envelope, Recipient recipient, String ip, String userAgent) {
        List<Recipient> all = signing.recipientsOfEnvelope(envelope.getId());
        if (all.stream().allMatch(r -> r.getStatus() == RecipientStatus.SIGNED)) {
            List<Field> allFields = fields.findByEnvelopeIdOrderByPageAsc(envelope.getId());
            finalization.finalizeEnvelope(envelope, all, allFields, signing.signatureMapOf(envelope.getId()),
                    "system", ip, userAgent);
        } else {
            signing.advanceRouting(envelope, all);
        }
    }

    private void recipientsSave(Recipient recipient) {
        // The recipient is managed by the signing service, this keeps the transaction consistent.
        signing.saveRecipient(recipient);
    }

    private X509Certificate parseCertificate(String pem) {        if (pem == null || pem.isBlank()) {
            throw new UnprocessableException("The certificate of the token is missing.");
        }
        String base64 = pem.replaceAll("-----BEGIN CERTIFICATE-----", "")
                .replaceAll("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(base64);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new UnprocessableException("The certificate of the token could not be read.");
        }
    }

    /** The SHA-256 of the DER encoded certificate. */
    private String certSha256(X509Certificate certificate) {
        try {
            return Hashing.sha256Hex(certificate.getEncoded());
        } catch (Exception e) {
            throw new UnprocessableException("The certificate of the token could not be read.");
        }
    }

    /** Rejects a certificate that is expired, untrusted or has the wrong key usage. */
    private void validateSignerCertificate(X509Certificate certificate) {
        Date now = new Date();
        if (!CertificateChainValidator.isValidAt(certificate, now)) {
            throw new UnprocessableException("The certificate of the token is expired or not yet valid.");
        }
        if (!CertificateChainValidator.hasSigningKeyUsage(certificate, true)) {
            throw new UnprocessableException("The certificate of the token must allow digitalSignature "
                    + "and nonRepudiation.");
        }
        List<X509Certificate> roots = sealKeys.getTrustRoots();
        if (!roots.isEmpty()) {
            boolean trusted = roots.stream().anyMatch(root -> isIssuedBy(certificate, root));
            if (!trusted) {
                throw new UnprocessableException("The certificate of the token was not issued by a trusted "
                        + "certificate authority.");
            }
        }
    }

    /** True when the certificate was signed by the given issuer. */
    private boolean isIssuedBy(X509Certificate certificate, X509Certificate issuer) {
        if (!certificate.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())) {
            return false;
        }
        try {
            java.security.Signature verifier = java.security.Signature.getInstance(certificate.getSigAlgName(),
                    com.dastakhat.pki.CmsBuilder.provider());
            verifier.initVerify(issuer.getPublicKey());
            verifier.update(certificate.getTBSCertificate());
            return verifier.verify(certificate.getSignature());
        } catch (Exception e) {
            return false;
        }
    }

    private int[] parseByteRange(String value) {
        if (value == null) {
            return new int[]{0, 0, 0, 0};
        }
        String cleaned = value.replace("[", "").replace("]", "").trim();
        String[] parts = cleaned.split(",\\s*");
        int[] range = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            range[i] = Integer.parseInt(parts[i].trim());
        }
        return range;
    }

    /** Draws the visible signature block of the DSC signer. */
    private void drawSignatureBlock(PDDocument document, Envelope envelope, Recipient recipient,
                                    Field field, String methodLabel) throws IOException {
        if (field.getPage() < 1 || field.getPage() > document.getNumberOfPages()) {
            return;
        }
        PDPage page = document.getPage(field.getPage() - 1);
        PdfCoordinates.PdfRect rect = coordinates.toPdfPoints(page, field.getX(), field.getY(),
                field.getW(), field.getH());
        List<String> lines = new ArrayList<>();
        lines.add(recipient.getName());
        lines.add(methodLabel + " - signed with a digital signature certificate");
        lines.add("Signed at " + java.time.format.DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Kolkata")).format(Instant.now()));
        stamping.stamp(document, List.of(new StampingService.Stamp(field.getPage() - 1,
                rect.x(), rect.y(), rect.width(), rect.height(), lines, null, "DSC signature")));
    }
}

