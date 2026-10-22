package com.dastakhat.signing;

import com.dastakhat.common.Hashing;
import com.dastakhat.common.Hex;
import com.dastakhat.pki.CmsBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.ExternalSigningSupport;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;


/**
 * Creates and validates the cryptographic signatures of a PDF.
 *
 * <p>The implementation replaces {@code pyHanko} from the original brief:</p>
 * <ul>
 *   <li>Apache PDFBox provides the incremental update, the reserved signature space and the
 *       byte range computation ({@code saveIncrementalForExternalSigning}).</li>
 *   <li>BouncyCastle provides the CMS / PKCS#7 structures and the verification.</li>
 * </ul>
 *
 * <p>Deferred signing (used for a DSC) works in two steps: {@link #prepareDeferred} reserves
 * the signature and returns the bytes that the token has to sign, {@link #finishDeferred}
 * embeds the resulting signature.</p>
 */
@Service
public class PdfSignatureService {

    /** Number of bytes that are reserved for the CMS inside the PDF. */
    public static final int RESERVED_SIGNATURE_SIZE = 16384;

    /** The state of a prepared (but not yet signed) signature. */
    public record PreparedSignature(byte[] pdfBytes,
                                    byte[] documentDigest,
                                    byte[] signedAttributesDer,
                                    int[] byteRange,
                                    Instant signingTime) { }

    /** The result of a finished signature. */
    public record SignedSignature(byte[] pdfBytes, int[] byteRange, byte[] cmsDer, String documentSha256) { }

    /** The result of validating one embedded signature. */
    public record SignatureValidation(String fieldName,
                                      boolean intact,
                                      boolean valid,
                                      boolean trusted,
                                      String subject,
                                      String digestAlgorithm,
                                      String signingTime,
                                      List<String> messages) { }

    /** A change that is applied to the document before the signature is reserved. */
    @FunctionalInterface
    public interface DocumentMutator {
        void apply(PDDocument document) throws IOException;
    }

    /**
     * Reserves a signature inside the document and returns the bytes that have to be signed.
     *
     * @param documentBytes the document to sign
     * @param fieldName     the name of the signature field
     * @param signingTime   the signing time that goes into the signed attributes
     * @param certificate   the signer certificate
     * @param chain         the additional certificates of the chain
     * @param mutator       optional changes to the document (for example the visible signature)
     */
    public PreparedSignature prepareDeferred(byte[] documentBytes,
                                             String fieldName,
                                             Instant signingTime,
                                             X509Certificate certificate,
                                             List<X509Certificate> chain,
                                             DocumentMutator mutator) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] toSign;
        try (PDDocument document = Loader.loadPDF(documentBytes)) {
            if (mutator != null) {
                mutator.apply(document);
            }
            PDSignature signature = newSignature(fieldName, signingTime);
            SignatureOptions options = new SignatureOptions();
            options.setPreferredSignatureSize(RESERVED_SIGNATURE_SIZE);
            document.addSignature(signature, options);

            ExternalSigningSupport support = document.saveIncrementalForExternalSigning(output);
            try (InputStream content = support.getContent()) {
                toSign = content.readAllBytes();
            }
            byte[] digest = sha256(toSign);
            CmsBuilder.Prepared prepared = CmsBuilder.prepare(digest, certificate, chain, signingTime);
            // Write a placeholder so that the file is complete. The real CMS is patched in later.
            support.setSignature(new byte[RESERVED_SIGNATURE_SIZE]);
            byte[] pdf = output.toByteArray();
            int[] byteRange = readByteRange(pdf);
            return new PreparedSignature(pdf, digest, prepared.signedAttributesDer(), byteRange, signingTime);
        }
    }

    /**
     * Embeds a raw signature (for example the one the DSC token produced) into a prepared
     * document.
     */
    public SignedSignature finishDeferred(PreparedSignature prepared,
                                          byte[] rawSignature,
                                          X509Certificate certificate,
                                          List<X509Certificate> chain) throws IOException {
        byte[] cms;
        try {
            cms = CmsBuilder.assemble(prepared.signedAttributesDer(), rawSignature, certificate, chain);
        } catch (Exception e) {
            throw new IOException("Cannot assemble the CMS signature", e);
        }
        return embedCms(prepared.pdfBytes(), cms);
    }

    /**
     * Embeds a complete CMS (for example the one the MOCK eSign provider produced) into a
     * prepared document.
     */
    public SignedSignature embedCms(byte[] preparedPdf, byte[] cms) throws IOException {
        byte[] patched = patchContents(preparedPdf, cms);
        int[] byteRange = readByteRange(patched);
        return new SignedSignature(patched, byteRange, cms, Hashing.sha256Hex(patched));
    }

    /**
     * Applies the platform seal. The seal key never leaves the backend.
     */
    public SignedSignature seal(byte[] documentBytes,
                                String fieldName,
                                Instant signingTime,
                                PrivateKey privateKey,
                                X509Certificate certificate,
                                List<X509Certificate> chain) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = Loader.loadPDF(documentBytes)) {
            PDSignature signature = newSignature(fieldName, signingTime);
            signature.setReason("Dastakhat completion seal (TEST CA)");
            SignatureOptions options = new SignatureOptions();
            options.setPreferredSignatureSize(RESERVED_SIGNATURE_SIZE);
            document.addSignature(signature, new SealSigner(privateKey, certificate, chain, signingTime), options);
            document.saveIncremental(output);
        }
        byte[] pdf = output.toByteArray();
        int[] byteRange = readByteRange(pdf);
        return new SignedSignature(pdf, byteRange, extractCms(pdf), Hashing.sha256Hex(pdf));
    }

    /** Validates every signature that is embedded in the document. */
    public List<SignatureValidation> validate(byte[] documentBytes, List<X509Certificate> trustRoots) {
        List<SignatureValidation> results = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(documentBytes)) {
            List<PDSignature> signatures = document.getSignatureDictionaries();
            for (PDSignature signature : signatures) {
                int[] byteRange = signature.getByteRange();
                byte[] contents = signature.getContents();
                byte[] cms = trimTrailingZeros(contents);
                String name = signature.getName();

                byte[] digest;
                try {
                    digest = digestOverByteRange(documentBytes, byteRange);
                } catch (RuntimeException e) {
                    results.add(new SignatureValidation(name, false, false, false, null, null, null,
                            List.of("The byte range of the signature is invalid: " + e.getMessage())));
                    continue;
                }
                CmsBuilder.Verification verification = CmsBuilder.verify(cms, digest, trustRoots);
                List<String> messages = new ArrayList<>(verification.messages());
                results.add(new SignatureValidation(name,
                        verification.digestMatches(),
                        verification.signatureValid(),
                        verification.trusted(),
                        verification.signerSubject(),
                        verification.digestAlgorithm(),
                        verification.signingTime(),
                        messages));
            }
        } catch (IOException e) {
            results.add(new SignatureValidation(null, false, false, false, null, null, null,
                    List.of("The signed document cannot be read: " + e.getMessage())));
        }
        return results;
    }

    /** Reads the byte range of the last signature of the document. */
    public int[] readByteRange(byte[] documentBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(documentBytes)) {
            List<PDSignature> signatures = document.getSignatureDictionaries();
            if (signatures.isEmpty()) {
                throw new IOException("The document does not contain a signature.");
            }
            return signatures.get(signatures.size() - 1).getByteRange();
        }
    }

    /** Extracts the CMS of the last signature. */
    public byte[] extractCms(byte[] documentBytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(documentBytes)) {
            List<PDSignature> signatures = document.getSignatureDictionaries();
            if (signatures.isEmpty()) {
                throw new IOException("The document does not contain a signature.");
            }
            return trimTrailingZeros(signatures.get(signatures.size() - 1).getContents());
        }
    }

    /**
     * Writes the CMS into the reserved space of the prepared document. The byte range does not
     * change because the reserved space has a fixed size.
     */
    private byte[] patchContents(byte[] preparedPdf, byte[] cms) throws IOException {
        int[] byteRange = readByteRange(preparedPdf);
        int start = byteRange[1];
        int end = byteRange[2];
        int hexCapacity = end - start - 2;
        String hex = Hex.encode(cms);
        if (hex.length() > hexCapacity) {
            throw new IOException("The signature is too large for the reserved space ("
                    + hex.length() + " > " + hexCapacity + ").");
        }
        byte[] result = preparedPdf.clone();
        byte[] hexBytes = hex.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(hexBytes, 0, result, start + 1, hexBytes.length);
        // pad the rest with '0' so that the length of the hex string stays the same
        for (int i = start + 1 + hexBytes.length; i < end - 1; i++) {
            result[i] = '0';
        }
        return result;
    }

    /** Computes the SHA-256 digest over the bytes that are covered by the signature. */
    public static byte[] digestOverByteRange(byte[] documentBytes, int[] byteRange) {
        if (byteRange == null || byteRange.length != 4) {
            throw new IllegalArgumentException("Invalid byte range");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(documentBytes, byteRange[0], byteRange[1] - byteRange[0]);
            digest.update(documentBytes, byteRange[2], byteRange[3]);
            return digest.digest();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute the digest", e);
        }
    }

    /** SHA-256 helper that turns the checked exception into an unchecked one. */
    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory but unavailable", e);
        }
    }

    private PDSignature newSignature(String fieldName, Instant signingTime) {
        PDSignature signature = new PDSignature();
        signature.setName(fieldName);
        signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.setTimeInMillis(signingTime.toEpochMilli());
        signature.setSignDate(calendar);
        return signature;
    }

    private byte[] trimTrailingZeros(byte[] content) {
        if (content == null) {
            return new byte[0];
        }
        int end = content.length;
        while (end > 0 && content[end - 1] == 0) {
            end--;
        }
        byte[] result = new byte[end];
        System.arraycopy(content, 0, result, 0, end);
        return result;
    }

    /** Signs the byte range content with the platform key and returns the CMS. */
    private static final class SealSigner implements SignatureInterface {
        private final PrivateKey privateKey;
        private final X509Certificate certificate;
        private final List<X509Certificate> chain;
        private final Instant signingTime;

        private SealSigner(PrivateKey privateKey, X509Certificate certificate,
                           List<X509Certificate> chain, Instant signingTime) {
            this.privateKey = privateKey;
            this.certificate = certificate;
            this.chain = chain;
            this.signingTime = signingTime;
        }

        @Override
        public byte[] sign(InputStream data) throws IOException {
            try {
                byte[] content = data.readAllBytes();
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
                CmsBuilder.Prepared prepared = CmsBuilder.prepare(digest, certificate, chain, signingTime);
                java.security.Signature signer = java.security.Signature.getInstance(
                        CmsBuilder.SIGNATURE_ALGORITHM, CmsBuilder.provider());
                signer.initSign(privateKey);
                signer.update(prepared.signedAttributesDer());
                byte[] signature = signer.sign();
                return CmsBuilder.assemble(prepared.signedAttributesDer(), signature, certificate, chain);
            } catch (Exception e) {
                throw new IOException("Cannot create the platform seal", e);
            }
        }
    }
}
