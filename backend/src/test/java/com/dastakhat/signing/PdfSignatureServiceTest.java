package com.dastakhat.signing;

import com.dastakhat.pki.CmsBuilder;
import com.dastakhat.pki.SealKeyService;
import com.dastakhat.support.TestDocuments;
import com.dastakhat.support.TestPki;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cryptographic core: the platform seal and the deferred (DSC style) signing have to
 * produce a PDF signature that verifies, and a modified document has to be detected.
 */
class PdfSignatureServiceTest {

    private static final String PASSWORD = "changeit";

    private PdfSignatureService service;
    private PrivateKey privateKey;
    private X509Certificate certificate;
    private List<X509Certificate> trustRoots;

    @BeforeAll
    static void createPki() {
        TestPki.ensure();
    }

    @BeforeEach
    void setUp() throws Exception {
        service = new PdfSignatureService();
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = open(TestPki.DIRECTORY.resolve("platform-seal.p12"))) {
            keystore.load(in, PASSWORD.toCharArray());
        }
        String alias = keystore.aliases().nextElement();
        privateKey = (PrivateKey) keystore.getKey(alias, PASSWORD.toCharArray());
        certificate = (X509Certificate) keystore.getCertificate(alias);

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (java.io.InputStream in = open(TestPki.DIRECTORY.resolve("test-root-ca.pem"))) {
            trustRoots = List.of((X509Certificate) factory.generateCertificate(in));
        }
    }

    @Test
    void theSealedDocumentContainsAValidSignature() throws Exception {
        byte[] sealed = service.seal(TestDocuments.validPdf(), "DastakhatPlatformSeal", Instant.now(),
                privateKey, certificate, List.of(certificate)).pdfBytes();

        List<PdfSignatureService.SignatureValidation> validations = service.validate(sealed, trustRoots);
        assertEquals(1, validations.size());
        PdfSignatureService.SignatureValidation validation = validations.get(0);
        assertTrue(validation.intact(), "the document must be intact: " + validation.messages());
        assertTrue(validation.valid(), "the signature must verify: " + validation.messages());
        assertTrue(validation.trusted(), "the certificate must chain to the test root: " + validation.messages());
        assertEquals("DastakhatPlatformSeal", validation.fieldName());
        assertNotNull(validation.subject());
    }

    @Test
    void aModifiedDocumentIsDetected() throws Exception {
        byte[] sealed = service.seal(TestDocuments.validPdf(), "DastakhatPlatformSeal", Instant.now(),
                privateKey, certificate, List.of(certificate)).pdfBytes();

        // Flip a byte inside the signed byte range.
        byte[] tampered = sealed.clone();
        int[] byteRange = service.readByteRange(tampered);
        int position = byteRange[1] / 2;
        tampered[position] ^= 0x01;

        List<PdfSignatureService.SignatureValidation> validations = service.validate(tampered, trustRoots);
        assertFalse(validations.get(0).intact(), "a modified document must not be intact");
    }

    @Test
    void theDeferredFlowProducesTheSameResult() throws Exception {
        byte[] original = TestDocuments.validPdf();

        PdfSignatureService.PreparedSignature prepared = service.prepareDeferred(original,
                "DastakhatDscSignature", Instant.now(), certificate, List.of(), null);
        assertNotNull(prepared.signedAttributesDer());
        assertEquals(32, prepared.documentDigest().length);

        // The token signs exactly the signed attributes.
        Signature signer = Signature.getInstance("SHA256withRSA", CmsBuilder.provider());
        signer.initSign(privateKey);
        signer.update(prepared.signedAttributesDer());
        byte[] rawSignature = signer.sign();

        PdfSignatureService.SignedSignature finished = service.finishDeferred(prepared, rawSignature,
                certificate, List.of());

        List<PdfSignatureService.SignatureValidation> validations = service.validate(finished.pdfBytes(),
                trustRoots);
        assertTrue(validations.get(0).intact());
        assertTrue(validations.get(0).valid());
    }

    @Test
    void aWrongSignatureIsRejected() throws Exception {
        PdfSignatureService.PreparedSignature prepared = service.prepareDeferred(TestDocuments.validPdf(),
                "DastakhatDscSignature", Instant.now(), certificate, List.of(), null);

        byte[] wrong = new byte[256];
        PdfSignatureService.SignedSignature finished = service.finishDeferred(prepared, wrong, certificate,
                List.of());

        List<PdfSignatureService.SignatureValidation> validations = service.validate(finished.pdfBytes(),
                trustRoots);
        assertFalse(validations.get(0).valid(), "a wrong signature must not verify");
    }

    @Test
    void theDigestOfTheSignedRangeMatchesThePreparation() throws Exception {
        byte[] original = TestDocuments.validPdf();
        PdfSignatureService.PreparedSignature prepared = service.prepareDeferred(original,
                "Test", Instant.now(), certificate, List.of(), null);
        byte[] recomputed = PdfSignatureService.digestOverByteRange(prepared.pdfBytes(), prepared.byteRange());
        assertTrue(MessageDigest.isEqual(recomputed, prepared.documentDigest()));
    }

    /** Small helper so that the test does not depend on the file system layout. */
    private static java.io.InputStream open(java.nio.file.Path path) throws java.io.IOException {
        return java.nio.file.Files.newInputStream(path);
    }
}
