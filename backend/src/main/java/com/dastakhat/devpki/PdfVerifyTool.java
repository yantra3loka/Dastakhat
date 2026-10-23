package com.dastakhat.devpki;

import com.dastakhat.signing.PdfSignatureService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the signatures of a signed PDF from the command line.
 *
 * <p>This is the tool that is used to check the final document of the demonstration:</p>
 * <pre>
 *   java ... com.dastakhat.devpki.PdfVerifyTool verify final.pdf --trust ../devpki/out/test-root-ca.pem
 *   java ... com.dastakhat.devpki.PdfVerifyTool tamper final.pdf modified.pdf 400
 * </pre>
 */
public final class PdfVerifyTool {

    private PdfVerifyTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: verify <file.pdf> [--trust <root.pem>] | tamper <in.pdf> <out.pdf> <byteOffset>");
            System.exit(2);
        }
        switch (args[0]) {
            case "verify" -> System.exit(verify(args));
            case "tamper" -> tamper(args);
            default -> {
                System.err.println("Unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }

    private static int verify(String[] args) throws Exception {
        Path file = Path.of(args[1]);
        List<X509Certificate> roots = new ArrayList<>();
        for (int i = 2; i < args.length - 1; i++) {
            if (args[i].equals("--trust")) {
                roots.addAll(readCertificates(Path.of(args[i + 1])));
            }
        }
        if (roots.isEmpty()) {
            Path defaultRoot = Path.of("..", "devpki", "out", "test-root-ca.pem");
            if (Files.exists(defaultRoot)) {
                roots.addAll(readCertificates(defaultRoot));
            }
        }

        byte[] content = Files.readAllBytes(file);
        PdfSignatureService service = new PdfSignatureService();
        List<PdfSignatureService.SignatureValidation> validations = service.validate(content, roots);

        System.out.println("File:      " + file.toAbsolutePath());
        System.out.println("Size:      " + content.length + " bytes");
        System.out.println("SHA-256:   " + com.dastakhat.common.Hashing.sha256Hex(content));
        System.out.println("Signatures: " + validations.size());
        boolean allValid = !validations.isEmpty();
        for (PdfSignatureService.SignatureValidation validation : validations) {
            System.out.println("  - " + validation.fieldName());
            System.out.println("      intact  : " + validation.intact());
            System.out.println("      valid   : " + validation.valid());
            System.out.println("      trusted : " + validation.trusted());
            System.out.println("      subject : " + validation.subject());
            System.out.println("      digest  : " + validation.digestAlgorithm());
            System.out.println("      time    : " + validation.signingTime());
            for (String message : validation.messages()) {
                System.out.println("      note    : " + message);
            }
            allValid = allValid && validation.intact() && validation.valid();
        }
        try (PDDocument document = Loader.loadPDF(content)) {
            System.out.println("Pages:     " + document.getNumberOfPages());
        }
        System.out.println(allValid ? "RESULT: VALID" : "RESULT: INVALID");
        return allValid ? 0 : 1;
    }

    /** Creates a copy of the document with one byte changed - used to show the detection. */
    private static void tamper(String[] args) throws Exception {
        Path input = Path.of(args[1]);
        Path output = Path.of(args[2]);
        int offset = Integer.parseInt(args[3]);
        byte[] content = Files.readAllBytes(input);
        content[offset] ^= 0x01;
        Files.write(output, content);
        System.out.println("Wrote the modified copy " + output.toAbsolutePath()
                + " (byte " + offset + " was flipped)");
    }

    private static List<X509Certificate> readCertificates(Path file) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certificates = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            for (java.security.cert.Certificate certificate : factory.generateCertificates(in)) {
                if (certificate instanceof X509Certificate x509) {
                    certificates.add(x509);
                }
            }
        }
        return certificates;
    }
}
