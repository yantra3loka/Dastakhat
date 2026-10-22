package com.dastakhat.esignmock;

import com.dastakhat.audit.AuditEventType;
import com.dastakhat.audit.AuditService;
import com.dastakhat.common.ConflictException;
import com.dastakhat.common.Hashing;
import com.dastakhat.common.NotFoundException;
import com.dastakhat.common.UnprocessableException;
import com.dastakhat.config.AppProperties;
import com.dastakhat.fields.Field;
import com.dastakhat.fields.FieldRepository;
import com.dastakhat.fields.FieldType;
import com.dastakhat.pki.XmlSigner;
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
import com.dastakhat.signing.SigningSession;
import com.dastakhat.signing.SigningSessionRepository;
import com.dastakhat.signing.StampingService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stage 3 - Aadhaar eSign <b>MOCK</b>.
 *
 * <p>This service imitates the shape of the CCA eSign API 2.1 exchange: the application
 * service provider (ASP) signs an XML request that carries the document hash, the MOCK
 * provider answers with a signed XML response that contains a one time certificate and a CMS
 * signature.</p>
 *
 * <p><b>This is a simulation.</b> It never contacts UIDAI or a real eSign service provider,
 * it never asks for an Aadhaar number and it has no legal standing. All identities and all
 * certificates are test data of the MOCK CA.</p>
 */
@Service
public class EsignMockService {

    private static final Logger log = LoggerFactory.getLogger(EsignMockService.class);

    /** The banner that every screen of this flow has to show. */
    public static final String MOCK_BANNER =
            "MOCK - Aadhaar eSign simulation. No real Aadhaar authentication happens here.";

    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final SigningService signing;
    private final SigningSessionRepository sessions;
    private final FieldRepository fields;
    private final SignatureRepository signatures;
    private final FinalizationService finalization;
    private final AuditService audit;
    private final AppProperties properties;
    private final PdfSignatureService pdfSignatures;
    private final PdfCoordinates coordinates;
    private final StampingService stamping;
    private final com.dastakhat.signing.TokenService tokens;

    public EsignMockService(SigningService signing,
                            SigningSessionRepository sessions,
                            FieldRepository fields,
                            SignatureRepository signatures,
                            FinalizationService finalization,
                            AuditService audit,
                            AppProperties properties,
                            PdfSignatureService pdfSignatures,
                            PdfCoordinates coordinates,
                            StampingService stamping,
                            com.dastakhat.signing.TokenService tokens) {
        this.signing = signing;
        this.sessions = sessions;
        this.fields = fields;
        this.signatures = signatures;
        this.finalization = finalization;
        this.audit = audit;
        this.properties = properties;
        this.pdfSignatures = pdfSignatures;
        this.coordinates = coordinates;
        this.stamping = stamping;
        this.tokens = tokens;
    }

    /**
     * Builds the signed MOCK eSign request. The document signature is reserved first so that
     * the provider signs exactly the bytes that end up in the PDF.
     */
    @Transactional
    public SignerDtos.EsignStartResponse start(String rawToken, String ip, String userAgent) throws IOException {
        Recipient recipient = signing.resolve(rawToken);
        if (recipient.getMethod() != SigningMethod.ESIGN_MOCK) {
            throw new ConflictException("This recipient does not use the MOCK eSign provider.");
        }
        List<Recipient> all = signing.recipientsOfEnvelope(recipient.getEnvelope().getId());
        signing.requireTurn(recipient, all);

        Field signatureField = fields.findByRecipientId(recipient.getId()).stream()
                .filter(f -> f.getType() == FieldType.SIGNATURE)
                .findFirst()
                .orElseThrow(() -> new UnprocessableException("This recipient has no signature field."));

        byte[] working = finalization.currentDocument(recipient.getEnvelope());
        Instant signingTime = Instant.now();

        // The MOCK provider signs the digest of the PDF byte range, exactly like a real ESP
        // signs the document hash.
        PdfSignatureService.PreparedSignature prepared = pdfSignatures.prepareDeferred(
                working, "DastakhatMockEsignSignature", signingTime, mockUserCertificate(), List.of(),
                document -> drawSignatureBlock(document, recipient.getEnvelope(), recipient, signatureField));

        String preparedKey = finalization.storePreparedDocument(recipient.getEnvelope(), prepared.pdfBytes());
        String txn = "DSK-" + UUID.randomUUID().toString().substring(0, 18).toUpperCase();

        SigningSession session = new SigningSession();
        session.setRecipient(recipient);
        session.setKind(SigningSession.Kind.ESIGN_MOCK);
        session.setPreparedKey(preparedKey);
        session.setDocumentDigest(Hashing.toHex(prepared.documentDigest()));
        session.setDocumentSha256(Hashing.sha256Hex(prepared.pdfBytes()));
        session.setSignedAttrsDer(prepared.signedAttributesDer());
        session.setTxn(txn);
        session.setStatus(SigningSession.Status.PREPARED);
        session.setExpiresAt(Instant.now().plusSeconds(600));
        session.setSignatureAlgorithm("SHA256withRSA");
        session.setByteRange(java.util.Arrays.toString(prepared.byteRange()));
        sessions.save(session);

        String requestXml;
        try {
            requestXml = buildRequest(txn, prepared.documentDigest(), recipient);
        } catch (Exception e) {
            throw new UnprocessableException("The MOCK eSign request could not be created: " + e.getMessage());
        }
        audit.append(recipient.getEnvelope().getId(), AuditEventType.DOCUMENT_VIEWED, recipient.getEmail(),
                ip, userAgent, session.getDocumentSha256(), Map.of("esignMockStart", true, "txn", txn));

        return new SignerDtos.EsignStartResponse(txn,
                properties.getMockEspBaseUrl() + "/esp/esign", requestXml, true, MOCK_BANNER);
    }

    /**
     * Handles the signed response of the MOCK provider. The XML signature, the transaction id
     * and the document hash are verified before the signature is accepted.
     */
    @Transactional
    public SignerDtos.EsignCallbackResponse callback(byte[] responseXml, String ip, String userAgent)
            throws Exception {
        if (responseXml == null || responseXml.length == 0) {
            throw new UnprocessableException("The response of the MOCK provider is empty.");
        }
        X509Certificate espCertificate = loadEspCertificate();
        boolean espSignatureValid;
        try {
            espSignatureValid = XmlSigner.verify(responseXml, espCertificate.getPublicKey(), espCertificate);
        } catch (Exception e) {
            throw new UnprocessableException("The XML signature of the MOCK provider could not be verified: "
                    + e.getMessage());
        }
        if (!espSignatureValid) {
            throw new UnprocessableException("The XML signature of the MOCK provider could not be verified.");
        }

        Document document = parse(responseXml);
        Element root = document.getDocumentElement();
        String txn = root.getAttribute("txn");
        if (txn == null || txn.isBlank()) {
            throw new UnprocessableException("The response of the MOCK provider has no transaction id.");
        }
        SigningSession session = sessions.findAll().stream()
                .filter(s -> txn.equals(s.getTxn()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Unknown MOCK eSign transaction."));
        if (session.getStatus() != SigningSession.Status.PREPARED) {
            throw new UnprocessableException("This MOCK eSign transaction has already been used.");
        }
        if (session.getExpiresAt().isBefore(Instant.now())) {
            session.setStatus(SigningSession.Status.EXPIRED);
            sessions.save(session);
            throw new UnprocessableException("This MOCK eSign transaction has expired.");
        }

        Element signaturesElement = XmlSigner.child(root, "Signatures");
        if (signaturesElement == null) {
            throw new UnprocessableException("The response of the MOCK provider contains no signature.");
        }
        Element docSignature = XmlSigner.child(signaturesElement, "DocSignature");
        if (docSignature == null) {
            throw new UnprocessableException("The response of the MOCK provider contains no document signature.");
        }
        byte[] cms = Base64.getMimeDecoder().decode(docSignature.getTextContent().trim());

        Element userCertElement = XmlSigner.child(root, "UserX509Certificate");
        X509Certificate userCertificate = null;
        if (userCertElement != null) {
            userCertificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(
                            Base64.getMimeDecoder().decode(userCertElement.getTextContent().trim())));
            // The one time certificate has to be issued by the MOCK CA.
            if (!isIssuedByMockCa(userCertificate)) {
                throw new UnprocessableException("The certificate of the MOCK provider is not issued "
                        + "by the MOCK CA.");
            }
        }

        byte[] preparedPdf = finalization.readPreparedDocument(session.getPreparedKey());
        PdfSignatureService.SignedSignature signed = pdfSignatures.embedCms(preparedPdf, cms);

        List<PdfSignatureService.SignatureValidation> validations =
                pdfSignatures.validate(signed.pdfBytes(), java.util.List.of());
        PdfSignatureService.SignatureValidation validation = validations.stream()
                .filter(v -> "DastakhatMockEsignSignature".equals(v.fieldName()))
                .findFirst()
                .orElse(validations.isEmpty() ? null : validations.get(validations.size() - 1));
        if (validation == null || !validation.intact() || !validation.valid()) {
            throw new UnprocessableException("The MOCK eSign signature does not verify against the document: "
                    + (validation == null ? "no signature found" : String.join("; ", validation.messages())));
        }

        finalization.replaceWorkingDocument(recipient_envelope(session), signed.pdfBytes());

        Recipient recipient = session.getRecipient();
        Signature signature = new Signature();
        signature.setRecipient(recipient);
        signature.setKind(SignatureKind.ESIGN_MOCK);
        if (userCertificate != null) {
            signature.setSignerCertPem(pem(userCertificate));
            signature.setSignerCertSha256(certHash(userCertificate));
            signature.setSignerCertSubject(userCertificate.getSubjectX500Principal().getName());
        }
        signature.setCmsSha256(Hashing.sha256Hex(cms));
        signature.setSignatureSha256(Hashing.sha256Hex(cms));
        signature.setCreatedAt(Instant.now());
        signatures.save(signature);

        session.setStatus(SigningSession.Status.FINISHED);
        session.setUsedAt(Instant.now());
        session.setCmsDer(cms);
        sessions.save(session);

        recipient.setStatus(RecipientStatus.SIGNED);
        recipient.setSignedAt(Instant.now());
        recipient.setIp(ip);
        recipient.setUserAgent(userAgent);
        tokens.invalidate(recipient);
        signing.saveRecipient(recipient);

        audit.append(recipient.getEnvelope().getId(), AuditEventType.ESIGN_SIGNED, recipient.getEmail(),
                ip, userAgent, signed.documentSha256(), Map.of(
                        "recipient", recipient.getName(),
                        "txn", txn,
                        "mock", true,
                        "certificateSubject", userCertificate == null ? "" : userCertificate.getSubjectX500Principal().getName()));

        advance(recipient.getEnvelope(), ip, userAgent);
        return new SignerDtos.EsignCallbackResponse("signed", true,
                signing.recipientsOfEnvelope(recipient.getEnvelope().getId()).stream()
                        .allMatch(r -> r.getStatus() == RecipientStatus.SIGNED) ? "completed" : "sent");
    }

    // ------------------------------------------------------------- helpers

    private void advance(com.dastakhat.envelopes.Envelope envelope, String ip, String userAgent) {
        List<Recipient> all = signing.recipientsOfEnvelope(envelope.getId());
        if (all.stream().allMatch(r -> r.getStatus() == RecipientStatus.SIGNED)) {
            List<Field> allFields = fields.findByEnvelopeIdOrderByPageAsc(envelope.getId());
            finalization.finalizeEnvelope(envelope, all, allFields, signing.signatureMapOf(envelope.getId()),
                    "system", ip, userAgent);
        } else {
            signing.advanceRouting(envelope, all);
        }
    }

    private com.dastakhat.envelopes.Envelope recipient_envelope(SigningSession session) {
        return session.getRecipient().getEnvelope();
    }

    /** Builds the signed MOCK eSign request. */
    private String buildRequest(String txn, byte[] documentDigest, Recipient recipient) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().newDocument();

        Element esign = document.createElement("Esign");
        esign.setAttribute("ver", "2.1");
        esign.setAttribute("sc", "Y");
        esign.setAttribute("ts", TS_FORMAT.format(Instant.now()));
        esign.setAttribute("txn", txn);
        esign.setAttribute("ekycIdType", "A");
        esign.setAttribute("aspId", properties.getEsign().getAspId());
        esign.setAttribute("AuthMode", "1");
        esign.setAttribute("responseSigType", "pkcs7");
        esign.setAttribute("responseUrl", properties.getEsignCallbackUrl());
        document.appendChild(esign);

        Element docs = document.createElement("Docs");
        esign.appendChild(docs);
        Element inputHash = document.createElement("InputHash");
        inputHash.setAttribute("id", "1");
        inputHash.setAttribute("hashAlgorithm", "SHA256");
        inputHash.setAttribute("docInfo", recipient.getEnvelope().getTitle());
        inputHash.setTextContent(Hashing.toHex(documentDigest));
        docs.appendChild(inputHash);

        // The request is signed with the TEST key of the ASP.
        KeyStore keystore = loadAspKeystore();
        String alias = keystore.aliases().nextElement();
        PrivateKey privateKey = (PrivateKey) keystore.getKey(alias,
                properties.getEsign().getAspKeystorePassword().toCharArray());
        X509Certificate certificate = (X509Certificate) keystore.getCertificate(alias);
        XmlSigner.sign(esign, privateKey, certificate);

        return toXml(document);
    }

    private KeyStore loadAspKeystore() throws Exception {
        java.nio.file.Path path = java.nio.file.Path.of(properties.getEsign().getAspKeystore());
        if (!java.nio.file.Files.exists(path)) {
            throw new IllegalStateException("The MOCK ASP keystore " + path + " does not exist. "
                    + "Run the devpki tool to create the TEST certificates.");
        }
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
            keystore.load(in, properties.getEsign().getAspKeystorePassword().toCharArray());
        }
        return keystore;
    }

    private X509Certificate loadEspCertificate() {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(properties.getEsign().getEspCertificate());
            if (!java.nio.file.Files.exists(path)) {
                throw new IllegalStateException("The MOCK ESP certificate " + path + " does not exist. "
                        + "Run the devpki tool to create the TEST certificates.");
            }
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
                return (X509Certificate) factory.generateCertificate(in);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new UnprocessableException("The certificate of the MOCK provider could not be read.");
        }
    }

    /** The certificate of the (test) user of the MOCK provider. */
    private X509Certificate mockUserCertificate() {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(properties.getEsign().getMockCaCertificate());
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
                return (X509Certificate) factory.generateCertificate(in);
            }
        } catch (Exception e) {
            throw new IllegalStateException("The MOCK CA certificate could not be read. "
                    + "Run the devpki tool to create the TEST certificates.", e);
        }
    }

    private boolean isIssuedByMockCa(X509Certificate certificate) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(properties.getEsign().getMockCaCertificate());
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate ca;
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(path)) {
                ca = (X509Certificate) factory.generateCertificate(in);
            }
            if (!certificate.getIssuerX500Principal().equals(ca.getSubjectX500Principal())) {
                return false;
            }
            java.security.Signature verifier = java.security.Signature.getInstance(
                    certificate.getSigAlgName(), com.dastakhat.pki.CmsBuilder.provider());
            verifier.initVerify(ca.getPublicKey());
            verifier.update(certificate.getTBSCertificate());
            return verifier.verify(certificate.getSignature());
        } catch (Exception e) {
            return false;
        }
    }

    private String pem(X509Certificate certificate) {
        try {
            String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded());
            return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
        } catch (Exception e) {
            throw new UnprocessableException("The certificate of the MOCK provider could not be read.");
        }
    }

    private String certHash(X509Certificate certificate) {
        try {
            return Hashing.sha256Hex(certificate.getEncoded());
        } catch (Exception e) {
            throw new UnprocessableException("The certificate of the MOCK provider could not be read.");
        }
    }

    private Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private String toXml(Document document) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    private void drawSignatureBlock(PDDocument document, com.dastakhat.envelopes.Envelope envelope,
                                    Recipient recipient, Field field) throws IOException {
        if (field.getPage() < 1 || field.getPage() > document.getNumberOfPages()) {
            return;
        }
        PDPage page = document.getPage(field.getPage() - 1);
        PdfCoordinates.PdfRect rect = coordinates.toPdfPoints(page, field.getX(), field.getY(),
                field.getW(), field.getH());
        List<String> lines = List.of(recipient.getName(),
                "eSign MOCK - simulated Aadhaar eSign (no legal standing)",
                "Signed at " + java.time.format.DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss")
                        .withZone(java.time.ZoneId.of("Asia/Kolkata")).format(Instant.now()));
        stamping.stamp(document, List.of(new StampingService.Stamp(field.getPage() - 1,
                rect.x(), rect.y(), rect.width(), rect.height(), lines, null, "MOCK eSign signature")));
    }
}

