package com.dastakhat.documents;

import com.dastakhat.common.Hashing;
import com.dastakhat.common.PayloadTooLargeException;
import com.dastakhat.common.UnprocessableException;
import com.dastakhat.common.UnsupportedMediaTypeException;
import com.dastakhat.config.AppProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validates an uploaded PDF before it is stored.
 *
 * <p>The checks are deliberately strict and are performed on the real bytes - the file name
 * and the {@code Content-Type} of the multipart request are never trusted.</p>
 */
@Service
public class PdfValidator {

    /** Every PDF starts with these five bytes. */
    public static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    /**
     * Names that indicate active content. JavaScript, launch actions and embedded files are
     * rejected because they can execute code or smuggle payloads into the final document.
     */
    private static final Set<String> FORBIDDEN_NAMES = Set.of(
            "javascript", "js", "xfa", "richmedia", "embeddedfiles", "launch",
            "submitform", "importdata", "movie", "sound", "gotor", "ef");

    private final AppProperties properties;

    public PdfValidator(AppProperties properties) {
        this.properties = properties;
    }

    /** Result of a successful inspection. */
    public record Inspection(int pages, String sha256, long sizeBytes, List<String> findings) {
        public boolean hasFindings() {
            return !findings.isEmpty();
        }
    }

    /**
     * Validates the uploaded bytes.
     *
     * @throws UnsupportedMediaTypeException when the magic bytes do not match a PDF
     * @throws PayloadTooLargeException      when the file is bigger than the configured limit
     * @throws UnprocessableException        when the PDF is malformed, encrypted, too long or
     *                                       contains active content
     */
    public Inspection validate(byte[] content) {
        if (content == null || content.length == 0) {
            throw new UnprocessableException("The uploaded file is empty.");
        }
        if (content.length > properties.getMaxUploadBytes()) {
            throw new PayloadTooLargeException("The uploaded file is larger than the "
                    + (properties.getMaxUploadBytes() / (1024 * 1024)) + " MB limit.");
        }
        if (!hasPdfMagic(content)) {
            throw new UnsupportedMediaTypeException(
                    "The uploaded file is not a PDF. The file content does not start with the %PDF- header.");
        }

        String sha256 = Hashing.sha256Hex(content);
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                throw new UnprocessableException(
                        "Encrypted PDFs are not supported. Remove the password protection and upload the file again.");
            }
            int pages = document.getNumberOfPages();
            if (pages < 1) {
                throw new UnprocessableException("The PDF does not contain any page.");
            }
            if (pages > properties.getMaxPages()) {
                throw new UnprocessableException("The PDF has " + pages + " pages but at most "
                        + properties.getMaxPages() + " pages are allowed.");
            }
            List<String> findings = findActiveContent(document);
            if (!findings.isEmpty()) {
                throw new UnprocessableException("The PDF contains active content which is not allowed: "
                        + String.join(", ", findings) + ".");
            }
            return new Inspection(pages, sha256, content.length, List.of());
        } catch (InvalidPasswordException e) {
            throw new UnprocessableException(
                    "Encrypted PDFs are not supported. Remove the password protection and upload the file again.");
        } catch (UnprocessableException | PayloadTooLargeException | UnsupportedMediaTypeException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UnprocessableException("The file could not be read as a valid PDF. "
                    + "It may be damaged or truncated.");
        }
    }

    /** True when the bytes start with the PDF magic header. */
    public static boolean hasPdfMagic(byte[] content) {
        if (content.length < PDF_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC.length; i++) {
            if (content[i] != PDF_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Walks the object graph and reports every piece of active content that is found.
     * The parser is Apache PDFBox; anything it cannot parse is rejected earlier.
     */
    private List<String> findActiveContent(PDDocument document) {
        Set<String> findings = new java.util.LinkedHashSet<>();
        Map<COSBase, Boolean> visited = new IdentityHashMap<>();
        try {
            COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
            walk(catalog, findings, visited);
            COSDictionary trailer = document.getDocument().getTrailer();
            if (trailer != null) {
                walk(trailer, findings, visited);
            }
        } catch (RuntimeException e) {
            findings.add("unreadable-structure");
        }
        return new ArrayList<>(findings);
    }

    private void walk(COSBase base, Set<String> findings, Map<COSBase, Boolean> visited) {
        if (base == null || visited.put(base, Boolean.TRUE) != null) {
            return;
        }
        COSBase value = base;
        if (base instanceof COSObject object) {
            value = object.getObject();
            if (value == null || visited.put(value, Boolean.TRUE) != null) {
                return;
            }
        }
        if (value instanceof COSName name) {
            String lower = name.getName().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_NAMES.contains(lower)) {
                findings.add(lower);
            }
            return;
        }
        if (value instanceof COSArray array) {
            for (COSBase item : array) {
                walk(item, findings, visited);
            }
            return;
        }
        if (value instanceof COSDictionary dictionary) {
            for (COSName key : dictionary.keySet()) {
                String lower = key.getName().toLowerCase(Locale.ROOT);
                if (FORBIDDEN_NAMES.contains(lower)) {
                    findings.add(lower);
                }
                walk(dictionary.getItem(key), findings, visited);
            }
        }
    }

    /** Convenience helper used by the tests. */
    public boolean looksLikePdf(byte[] content) {
        return hasPdfMagic(content);
    }

    /** Used by the tests to build a minimal valid PDF. */
    public static COSName cosName(String name) {
        return COSName.getPDFName(name);
    }
}
