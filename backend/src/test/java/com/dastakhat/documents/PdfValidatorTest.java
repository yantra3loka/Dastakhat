package com.dastakhat.documents;

import com.dastakhat.common.PayloadTooLargeException;
import com.dastakhat.common.UnprocessableException;
import com.dastakhat.common.UnsupportedMediaTypeException;
import com.dastakhat.config.AppProperties;
import com.dastakhat.support.TestDocuments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
