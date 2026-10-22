package com.dastakhat.signing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the captured signatures and field values onto the PDF pages.
 *
 * <p>This is the Java replacement for the {@code reportlab + pypdf} stamping step of the
 * original brief. Apache PDFBox writes directly into the page content stream, so no
 * intermediate rendering engine is needed.</p>
 */
@Service
public class StampingService {

    /** One thing that has to be drawn on a page. */
    public record Stamp(int pageIndex,
                         double x,
                         double y,
                         double width,
                         double height,
                         List<String> lines,
                         byte[] imagePng,
                         String label) { }

    /**
     * Draws all stamps onto the document.
     *
     * @param document the document that is modified in place
     * @param stamps   the stamps, already converted to PDF points
     */
    public void stamp(PDDocument document, List<Stamp> stamps) throws IOException {
        if (stamps.isEmpty()) {
            return;
        }
        for (Stamp stamp : stamps) {
            if (stamp.pageIndex() < 0 || stamp.pageIndex() >= document.getNumberOfPages()) {
                continue;
            }
            PDPage page = document.getPage(stamp.pageIndex());
            try (PDPageContentStream stream = new PDPageContentStream(document, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                draw(document, stream, stamp);
            }
        }
    }

    private void draw(PDDocument document, PDPageContentStream stream, Stamp stamp) throws IOException {
        float x = (float) stamp.x();
        float y = (float) stamp.y();
        float width = (float) stamp.width();
        float height = (float) stamp.height();

        // A light frame makes the position visible in the final document.
        stream.setLineWidth(0.6f);
        stream.setNonStrokingColor(0.85f, 0.85f, 0.85f);
        stream.addRect(x, y, width, height);
        stream.stroke();
        stream.setNonStrokingColor(0f, 0f, 0f);

        float cursorY = y + height - 12;
        if (stamp.imagePng() != null && stamp.imagePng().length > 0) {
            PDImageXObject image = PDImageXObject.createFromByteArray(document, stamp.imagePng(), "png");
            float imageWidth = width;
            float imageHeight = image.getHeight() * imageWidth / image.getWidth();
            float maxHeight = height * 0.6f;
            if (imageHeight > maxHeight) {
                imageHeight = maxHeight;
                imageWidth = image.getWidth() * imageHeight / image.getHeight();
            }
            stream.drawImage(image,
                    x + (width - imageWidth) / 2,
                    y + height - imageHeight - 4,
                    imageWidth, imageHeight);
            cursorY = y + height - imageHeight - 10;
        }

        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        if (stamp.lines() != null) {
            for (String line : stamp.lines()) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                stream.beginText();
                stream.setFont(bold, 8);
                stream.newLineAtOffset(x + 2, cursorY);
                stream.showText(sanitize(line, bold));
                stream.endText();
                cursorY -= 10;
            }
        }
    }

    /** Removes every interactive form field so that the final PDF cannot be edited. */
    public void flatten(PDDocument document) throws IOException {
        PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
        if (acroForm != null) {
            try {
                acroForm.flatten();
            } catch (RuntimeException | IOException e) {
                // A broken appearance stream must not stop the flattening - the widgets are
                // removed below in any case.
            }
        }
        for (PDPage page : document.getPages()) {
            List<PDAnnotation> annotations = new ArrayList<>(page.getAnnotations());
            for (PDAnnotation annotation : annotations) {
                if (annotation instanceof PDAnnotationWidget) {
                    page.getAnnotations().remove(annotation);
                }
            }
        }
        document.getDocumentCatalog().setAcroForm(null);
    }

    /** Replaces characters that the standard fonts cannot render. */
    private String sanitize(String text, PDType1Font font) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c < 32) {
                sb.append(' ');
            } else if (isRenderable(font, c)) {
                sb.append(c);
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }

    private boolean isRenderable(PDType1Font font, char c) {
        try {
            return font.getStringWidth(String.valueOf(c)) > 0;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /** Convenience: the media box of a page. */
    public PDRectangle mediaBox(PDPage page) {
        return page.getMediaBox();
    }

    /** Unused import guard for Matrix (kept for future rotation support). */
    static Matrix identity() {
        return Matrix.getTranslateInstance(0, 0);
    }
}
