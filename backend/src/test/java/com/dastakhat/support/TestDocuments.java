package com.dastakhat.support;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Builds the PDF fixtures of the tests.
 *
 * <p>The fixtures are generated instead of being committed as binary files: they contain no
 * personal data and their content is obvious from the code.</p>
 */
public final class TestDocuments {

    private TestDocuments() {
    }

    /** A valid two page document. */
    public static byte[] validPdf() {
        return build(false, false, 2, 0);
    }

    /** A valid one page document. */
    public static byte[] validOnePagePdf() {
        return build(false, false, 1, 0);
    }

    /** A document with a JavaScript action in the open action of the catalogue. */
    public static byte[] javascriptPdf() {
        return build(true, false, 1, 0);
    }

    /** A document with a launch action. */
    public static byte[] launchActionPdf() {
        return build(false, true, 1, 0);
    }

    /**
     * A one page document that carries an embedded attachment in the {@code /Names /EmbeddedFiles}
     * name tree and in the catalogue {@code /AF} array - the same shape as the C2PA "Content
     * Credentials" manifests that PDF producers attach to otherwise text-only documents.
     */
    public static byte[] embeddedFilePdf() {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                stream.newLineAtOffset(60, 700);
                stream.showText("Dastakhat test document with an attachment");
                stream.endText();
            }
            PDEmbeddedFile embedded = new PDEmbeddedFile(document,
                    new ByteArrayInputStream("secret payload".getBytes(StandardCharsets.UTF_8)));
            PDComplexFileSpecification filespec = new PDComplexFileSpecification();
            filespec.setFile("secret-plan.txt");
            filespec.setFileUnicode("secret-plan.txt");
            filespec.setEmbeddedFile(embedded);

            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of("secret-plan.txt", filespec));
            if (document.getDocumentCatalog().getNames() == null) {
                document.getDocumentCatalog().setNames(new PDDocumentNameDictionary(document.getDocumentCatalog()));
            }
            document.getDocumentCatalog().getNames().setEmbeddedFiles(tree);

            // PDF 2.0 associated files, like the /AF entry of the reported document.
            COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
            COSArray associatedFiles = new COSArray();
            associatedFiles.add(filespec.getCOSObject());
            catalog.setItem(COSName.getPDFName("AF"), associatedFiles);

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build the test document", e);
        }
    }

    /**
     * A clean one page document whose font resource is <em>named</em> {@code /EF}. The name is a
     * plain resource name and must never be mistaken for the embedded-file entry of a file
     * specification.
     */
    public static byte[] resourceNamedEfPdf() {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("EF"), font);
            page.setResources(resources);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font, 18);
                stream.appendRawCommands("/EF 18 Tf\n");
                stream.newLineAtOffset(60, 700);
                stream.showText("Dastakhat test document - the font resource is called /EF");
                stream.endText();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build the test document", e);
        }
    }

    /** A password protected document. */
    public static byte[] encryptedPdf() {
        return build(false, false, 1, 1);
    }

    /** A file that is not a PDF at all. */
    public static byte[] notAPdf() {
        return "This is definitely not a PDF, it is a renamed text file.".getBytes();
    }

    /** A truncated file that starts like a PDF. */
    public static byte[] malformedPdf() {
        return "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n".getBytes();
    }

    /** A file that is larger than the upload limit. */
    public static byte[] oversizedPdf(long size) {
        byte[] content = new byte[(int) size];
        byte[] header = "%PDF-1.7".getBytes();
        System.arraycopy(header, 0, content, 0, header.length);
        return content;
    }

    private static byte[] build(boolean javascript, boolean launch, int pages, int encrypted) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                    stream.newLineAtOffset(60, 700);
                    stream.showText("Dastakhat test document - page " + (i + 1));
                    stream.endText();
                }
            }
            if (javascript) {
                PDActionJavaScript action = new PDActionJavaScript("app.alert('JavaScript in a PDF');");
                document.getDocumentCatalog().setOpenAction(action);
            }
            if (launch) {
                document.getDocumentCatalog().setOpenAction(new org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch());
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (encrypted == 1) {
                AccessPermission permissions = new AccessPermission();
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret",
                        "user-secret", permissions);
                document.protect(policy);
                document.save(output);
            } else {
                document.save(output);
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot build the test document", e);
        }
    }

    /** The number of pages of a PDF. */
    public static int pageCount(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the test document", e);
        }
    }
}
