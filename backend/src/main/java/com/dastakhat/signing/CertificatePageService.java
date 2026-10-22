package com.dastakhat.signing;

import com.dastakhat.common.Hashing;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the certificate of completion that is appended to the final document.
 *
 * <p>The page is appended with an incremental update so that the signatures that were added
 * earlier stay valid.</p>
 */
@Service
public class CertificatePageService {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM uuuu HH:mm:ss").withZone(DISPLAY_ZONE);

    /** One row of the signer table. */
    public record SignerRow(String name, String email, String method, Instant signedAt, String ip) { }

    /** Everything that is printed on the certificate page. */
    public record CertificateData(String envelopeId,
                                   String envelopeTitle,
                                   String filename,
                                   int pages,
                                   String originalSha256,
                                   String signedDocumentSha256,
                                   List<SignerRow> signers,
                                   String auditHeadHash,
                                   String sealDescription) { }

    /**
     * Appends the certificate of completion to the working document.
     *
     * @param workingPdf the document that carries all the signatures
     * @param data       the content of the certificate
     * @return the document including the new page
     */
    public byte[] append(byte[] workingPdf, CertificateData data) throws IOException {
        String preSeal = Hashing.sha256Hex(workingPdf);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = Loader.loadPDF(workingPdf)) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            draw(document, page, data, preSeal);
            document.saveIncremental(output);
        }
        return output.toByteArray();
    }

    private void draw(PDDocument document, PDPage page, CertificateData data, String preSealSha256)
            throws IOException {
        PDType1Font title = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDType1Font normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font mono = new PDType1Font(Standard14Fonts.FontName.COURIER);

        float margin = 50;
        float width = page.getMediaBox().getWidth();
        float y = page.getMediaBox().getHeight() - margin;

        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.beginText();
            stream.setFont(title, 16);
            stream.newLineAtOffset(margin, y);
            stream.showText("CERTIFICATE OF COMPLETION");
            stream.endText();
            y -= 30;

            y = line(stream, normal, margin, y, "Envelope: " + data.envelopeId());
            y = line(stream, normal, margin, y, "Title: " + data.envelopeTitle());
            y = line(stream, normal, margin, y,
                    "Document: " + data.filename() + " (" + data.pages() + " pages)");
            y = line(stream, normal, margin, y, "Original SHA-256: " + data.originalSha256());
            y = line(stream, normal, margin, y, "Signed document SHA-256: " + data.signedDocumentSha256());
            y = line(stream, normal, margin, y, "Audit chain head: " + data.auditHeadHash());
            y = line(stream, normal, margin, y, "Sealed by: " + data.sealDescription());
            y -= 10;

            // Table header
            float[] columns = {margin, margin + 110, margin + 240, margin + 350, margin + 470};
            stream.beginText();
            stream.setFont(title, 10);
            stream.newLineAtOffset(columns[0], y);
            stream.showText("Signer");
            stream.newLineAtOffset(columns[1] - columns[0], 0);
            stream.showText("Email");
            stream.newLineAtOffset(columns[2] - columns[1], 0);
            stream.showText("Method");
            stream.newLineAtOffset(columns[3] - columns[2], 0);
            stream.showText("Signed (IST)");
            stream.newLineAtOffset(columns[4] - columns[3], 0);
            stream.showText("IP");
            stream.endText();
            y -= 6;
            stream.setLineWidth(0.5f);
            stream.addRect(margin - 4, y - 2, width - 2 * margin + 8, 14);
            stream.stroke();
            y -= 16;

            for (SignerRow signer : data.signers()) {
                stream.beginText();
                stream.setFont(normal, 9);
                stream.newLineAtOffset(columns[0], y);
                stream.showText(clip(signer.name(), 18));
                stream.newLineAtOffset(columns[1] - columns[0], 0);
                stream.showText(clip(signer.email(), 26));
                stream.newLineAtOffset(columns[2] - columns[1], 0);
                stream.showText(clip(signer.method(), 16));
                stream.newLineAtOffset(columns[3] - columns[2], 0);
                stream.showText(signer.signedAt() == null ? "-" : DISPLAY_FORMAT.format(signer.signedAt()));
                stream.newLineAtOffset(columns[4] - columns[3], 0);
                stream.showText(signer.ip() == null ? "-" : clip(signer.ip(), 16));
                stream.endText();
                y -= 14;
            }

            y -= 16;
            stream.beginText();
            stream.setFont(normal, 8);
            stream.newLineAtOffset(margin, y);
            stream.showText("Electronic signatures captured as an image are NOT cryptographic digital signatures.");
            stream.endText();
            y -= 11;
            stream.beginText();
            stream.setFont(normal, 8);
            stream.newLineAtOffset(margin, y);
            stream.showText("The MOCK eSign provider is a simulation and has no legal standing.");
            stream.endText();
            y -= 11;
            stream.beginText();
            stream.setFont(mono, 8);
            stream.newLineAtOffset(margin, y);
            stream.showText("Generated by Dastakhat - development build with TEST certificates.");
            stream.endText();
        }
    }

    private float line(PDPageContentStream stream, PDType1Font font, float margin, float y, String text)
            throws IOException {
        stream.beginText();
        stream.setFont(font, 10);
        stream.newLineAtOffset(margin, y);
        stream.showText(clip(text, 120));
        stream.endText();
        return y - 16;
    }

    private String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.length() > max ? text.substring(0, max - 1) + "…" : text;
        return value.replaceAll("[^\\x20-\\x7E]", "?");
    }

    /** Formats an instant for the certificate page. */
    public static String formatSignedAt(Instant instant) {
        return instant == null ? "-" : DISPLAY_FORMAT.format(instant);
    }

    /** The rows that are shown on the certificate. */
    public static List<SignerRow> rows(List<SignerRow> rows) {
        return new ArrayList<>(rows);
    }
}
