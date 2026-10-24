package com.dastakhat.documents;

import com.dastakhat.common.PayloadTooLargeException;
import com.dastakhat.common.UnprocessableException;
import com.dastakhat.common.UnsupportedMediaTypeException;
import com.dastakhat.config.AppProperties;
import com.dastakhat.support.TestDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The upload validation has to look at the real bytes and never at the file name. */
class PdfValidatorTest {

    private AppProperties properties;
    private PdfValidator validator;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.setMaxUploadBytes(10 * 1024 * 1024);
        properties.setMaxPages(50);
        validator = new PdfValidator(properties);
    }

    @Test
    void aValidPdfIsAccepted() {
        PdfValidator.Inspection inspection = validator.validate(TestDocuments.validPdf());
        assertEquals(2, inspection.pages());
        assertEquals(64, inspection.sha256().length());
        assertTrue(inspection.sizeBytes() > 0);
    }

    @Test
    void aRenamedTextFileIsRejected() {
        UnsupportedMediaTypeException exception = assertThrows(UnsupportedMediaTypeException.class,
                () -> validator.validate(TestDocuments.notAPdf()));
        assertTrue(exception.getMessage().contains("not a PDF"));
    }

    @Test
    void anEncryptedPdfIsRejected() {
        UnprocessableException exception = assertThrows(UnprocessableException.class,
                () -> validator.validate(TestDocuments.encryptedPdf()));
        assertTrue(exception.getMessage().contains("Encrypted"));
    }

    @Test
    void aMalformedPdfIsRejected() {
        UnprocessableException exception = assertThrows(UnprocessableException.class,
                () -> validator.validate(TestDocuments.malformedPdf()));
        assertTrue(exception.getMessage().contains("valid PDF"));
    }

    @Test
    void anOversizedFileIsRejected() {
        properties.setMaxUploadBytes(1024);
        assertThrows(PayloadTooLargeException.class,
                () -> validator.validate(TestDocuments.oversizedPdf(4096)));
    }

    @Test
    void aPdfWithJavaScriptIsRejected() {
        UnprocessableException exception = assertThrows(UnprocessableException.class,
                () -> validator.validate(TestDocuments.javascriptPdf()));
        assertTrue(exception.getMessage().toLowerCase().contains("active content"));
    }

    @Test
    void aPdfWithALaunchActionIsRejected() {
        assertThrows(UnprocessableException.class,
                () -> validator.validate(TestDocuments.launchActionPdf()));
    }

    @Test
    void aPdfWithAnEmbeddedAttachmentIsRejected() {
        UnprocessableException exception = assertThrows(UnprocessableException.class,
                () -> validator.validate(TestDocuments.embeddedFilePdf()));
        String message = exception.getMessage().toLowerCase();
        assertTrue(message.contains("active content"), message);
        assertTrue(message.contains("embedded-attachment"), message);
        assertTrue(message.contains("embedded-file"), message);
        assertTrue(message.contains("associated-files"), message);
        assertTrue(message.contains("secret-plan.txt"), "the message names the attachment: " + message);
    }

    /** Regression: the C2PA "Content Credentials" manifest of the bug report is metadata. */
    @Test
    void aContentCredentialsManifestIsAccepted() {
        PdfValidator.Inspection inspection = validator.validate(TestDocuments.c2paCredentialsPdf());
        assertEquals(1, inspection.pages());
        assertEquals(64, inspection.sha256().length());
        assertTrue(inspection.findings().isEmpty(), "no finding for provenance metadata");
    }

    /** The exception is narrow: a real attachment next to the manifest is still rejected. */
    @Test
    void aRealAttachmentNextToAContentCredentialsManifestIsStillRejected() {
        UnprocessableException exception = assertThrows(UnprocessableException.class,
                () -> validator.validate(TestDocuments.c2paPlusAttachmentPdf()));
        String message = exception.getMessage().toLowerCase();
        assertTrue(message.contains("invoice-data.zip"), "the message names the attachment: " + message);
        assertFalse(message.contains("content credentials"), "the manifest is not reported: " + message);
    }

    @Test
    void aLargerMultiPagePdfIsAccepted() {
        PdfValidator.Inspection inspection = validator.validate(TestDocuments.multiPagePdf(24));
        assertEquals(24, inspection.pages());
        assertTrue(inspection.findings().isEmpty());
    }

    @Test
    void aPdfWithLegitimateMetadataIsAccepted() {
        PdfValidator.Inspection inspection = validator.validate(TestDocuments.metadataPdf());
        assertEquals(1, inspection.pages());
        assertTrue(inspection.findings().isEmpty());
    }

    /** A genuinely large (about 6 MB) valid document has to pass the validation. */
    @Test
    void aLargeValidPdfIsAccepted() {
        PdfValidator.Inspection inspection = validator.validate(TestDocuments.largePdf(6L * 1024 * 1024));
        assertEquals(1, inspection.pages());
        assertTrue(inspection.sizeBytes() > 5L * 1024 * 1024);
    }

    @Test
    void aPdfExactlyAtTheSizeLimitIsAccepted() {
        byte[] document = TestDocuments.largePdf(2L * 1024 * 1024);
        properties.setMaxUploadBytes(document.length);
        assertEquals(document.length, validator.validate(document).sizeBytes());
    }

    @Test
    void aFileBeyondTheSizeLimitIsRejected() {
        byte[] document = TestDocuments.largePdf(2L * 1024 * 1024);
        properties.setMaxUploadBytes(document.length - 1);
        assertThrows(PayloadTooLargeException.class, () -> validator.validate(document));
    }

    @Test
    void aPdfWithExactlyThePageLimitIsAccepted() {
        PdfValidator.Inspection inspection = validator.validate(TestDocuments.multiPagePdf(50));
        assertEquals(50, inspection.pages());
    }

    @Test
    void aPdfBeyondThePageLimitIsRejected() {
        UnprocessableException exception = assertThrows(UnprocessableException.class,
                () -> validator.validate(TestDocuments.multiPagePdf(51)));
        assertTrue(exception.getMessage().contains("pages"));
    }

    @Test
    void anOrdinaryDocumentWithAResourceNamedEfIsAccepted() {
        PdfValidator.Inspection inspection = validator.validate(TestDocuments.resourceNamedEfPdf());
        assertEquals(1, inspection.pages());
        assertEquals(64, inspection.sha256().length());
    }

    @Test
    void aTextOnlyPdfWithoutActiveContentHasNoFindings() {
        PdfValidator.Inspection inspection = validator.validate(TestDocuments.validOnePagePdf());
        assertEquals(1, inspection.pages());
        assertTrue(inspection.findings().isEmpty());
    }

    @Test
    void anEmptyFileIsRejected() {
        assertThrows(UnprocessableException.class, () -> validator.validate(new byte[0]));
    }

    @Test
    void tooManyPagesAreRejected() {
        properties.setMaxPages(1);
        UnprocessableException exception = assertThrows(UnprocessableException.class,
                () -> validator.validate(TestDocuments.validPdf()));
        assertTrue(exception.getMessage().contains("pages"));
    }
}
