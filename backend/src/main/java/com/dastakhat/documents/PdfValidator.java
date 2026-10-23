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
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
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
     * Keys that can only appear inside the structures which carry active content. They are
     * reserved PDF names, so a benign document never uses them.
     *
     * <p>Matching a key is <em>not</em> enough to reject a file on its own: the decision is made
     * on the object that hangs below the key (an embedded file stream, an action, a form).</p>
     */
    private static final Set<String> STRUCTURAL_KEYS = Set.of(
            "ef", "af", "xfa", "js", "javascript", "embeddedfiles", "richmediacontent");

    /** The {@code /S} subtypes of actions that execute code, play media or leave the document. */
    private static final Set<String> FORBIDDEN_ACTIONS = Set.of(
            "javascript", "launch", "submitform", "importdata", "gotor", "gotoe",
            "movie", "sound", "rendition", "richtext");

    /** {@code /Type} values that mark active content. */
    private static final Set<String> FORBIDDEN_TYPES = Set.of("embeddedfile", "richmediacontent");

    /** {@code /Subtype} values of annotations that carry attachments or media. */
    private static final Set<String> FORBIDDEN_SUBTYPES = Set.of(
            "fileattachment", "movie", "sound", "screen");

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

    /** What the object graph walk found, plus the names of the attachments for the message. */
    private static final class Report {
        private final Set<String> findings = new LinkedHashSet<>();
        private final Set<String> attachments = new LinkedHashSet<>();

        private void add(String finding) {
            findings.add(finding);
        }

        private void attachment(String fileName) {
            findings.add("embedded-attachment");
            if (fileName != null && !fileName.isBlank()) {
                attachments.add(fileName);
            }
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
            Report report = findActiveContent(document);
            if (!report.findings.isEmpty()) {
                throw new UnprocessableException("The PDF contains active content which is not allowed: "
                        + String.join(", ", report.findings) + "."
                        + (report.attachments.isEmpty() ? "" : " Embedded attachments: "
                                + String.join(", ", report.attachments) + ".")
                        + " Remove the attachment or the active element and upload the file again.");
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
     *
     * <p>The detection is structural: a bare name is never enough to reject a document (a font or
     * an image may legitimately be <em>named</em> {@code /EF} or {@code /JS}). A document is only
     * rejected when the object graph actually contains an embedded file stream, a filespec with an
     * {@code /EF} entry, an associated-file ({@code /AF}) reference, an XFA form, a JavaScript
     * name tree or an action whose {@code /S} subtype executes code, plays media or leaves the
     * document. Everything the parser cannot read is rejected earlier, so this walk fails closed
     * as well.</p>
     *
     * <p>The parser is Apache PDFBox; anything it cannot parse is rejected earlier.</p>
     */
    private Report findActiveContent(PDDocument document) {
        Report report = new Report();
        Map<COSBase, Boolean> visited = new IdentityHashMap<>();
        try {
            COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
            walk(catalog, report, visited);
            COSDictionary trailer = document.getDocument().getTrailer();
            if (trailer != null) {
                walk(trailer, report, visited);
            }
        } catch (RuntimeException e) {
            report.add("unreadable-structure");
        }
        return report;
    }

    private void walk(COSBase base, Report report, Map<COSBase, Boolean> visited) {
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
        if (value instanceof COSName) {
            // A bare name carries no behaviour. Its meaning comes from the key it sits under,
            // which is decided in walkDictionary - that is what keeps ordinary resource names
            // such as a font called /EF out of the findings.
            return;
        }
        if (value instanceof COSArray array) {
            for (COSBase item : array) {
                walk(item, report, visited);
            }
            return;
        }
        if (value instanceof COSDictionary dictionary) {
            walkDictionary(dictionary, report, visited);
        }
    }

    private void walkDictionary(COSDictionary dictionary, Report report, Map<COSBase, Boolean> visited) {
        for (COSName key : dictionary.keySet()) {
            String lower = key.getName().toLowerCase(Locale.ROOT);
            COSBase item = dictionary.getItem(key);

            if (STRUCTURAL_KEYS.contains(lower)) {
                switch (lower) {
                    // /EF is only an embedded file when it sits in a file specification. Any other
                    // dictionary (a font or image resource, for example) may use the name freely.
                    case "ef" -> {
                        if (isFileSpecification(dictionary)) {
                            report.attachment(filespecName(dictionary));
                        }
                    }
                    case "af" -> report.add("associated-files");
                    case "xfa" -> report.add("xfa-form");
                    case "js", "javascript" -> report.add("javascript");
                    case "embeddedfiles" -> report.add("embedded-attachment");
                    case "richmediacontent" -> report.add("rich-media");
                    default -> { }
                }
            } else if ("s".equals(lower)) {
                String action = asLowerName(item);
                if (action != null && FORBIDDEN_ACTIONS.contains(action)) {
                    report.add(action);
                }
            } else if ("type".equals(lower)) {
                String type = asLowerName(item);
                if (type != null && FORBIDDEN_TYPES.contains(type)) {
                    report.add("embeddedfile".equals(type) ? "embedded-file" : "rich-media");
                }
            } else if ("subtype".equals(lower)) {
                String subtype = asLowerName(item);
                if (subtype != null && FORBIDDEN_SUBTYPES.contains(subtype)) {
                    report.add("fileattachment".equals(subtype) ? "file-attachment" : subtype + "-annotation");
                }
            }
            walk(item, report, visited);
        }
    }

    /** The lowercased name behind a value, or {@code null} when it is not a name. */
    private static String asLowerName(COSBase value) {
        COSBase unwrapped = value instanceof COSObject object ? object.getObject() : value;
        return unwrapped instanceof COSName name ? name.getName().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * True when the dictionary is a file specification ({@code /Type /Filespec} or a file name
     * entry). Only then does an {@code /EF} key mean "embedded file".
     */
    private static boolean isFileSpecification(COSDictionary dictionary) {
        if ("filespec".equals(asLowerName(dictionary.getDictionaryObject(COSName.TYPE)))) {
            return true;
        }
        return asString(dictionary, COSName.F) != null || asString(dictionary, COSName.UF) != null;
    }

    /** The human readable file name of a filespec, used to tell the user what was rejected. */
    private static String filespecName(COSDictionary filespec) {
        String name = asString(filespec, COSName.UF);
        return name != null ? name : asString(filespec, COSName.F);
    }

    /** A text entry of a dictionary, unwrapping indirect objects. */
    private static String asString(COSDictionary dictionary, COSName key) {
        COSBase value = dictionary.getDictionaryObject(key);
        return value instanceof COSString text ? text.getString() : null;
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
