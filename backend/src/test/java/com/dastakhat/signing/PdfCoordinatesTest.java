package com.dastakhat.signing;

import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conversion between the page fractions of the browser and the points of the PDF.
 *
 * <p>The reference is the viewport transformation of pdf.js, because the browser renders the
 * pages with pdf.js. The mapping of the top left corner of a field is:</p>
 * <pre>
 *   rotation   0 : x = x0 + fx*W       y = y1 - (fy+fh)*H
 *   rotation  90 : x = x0 + fy*W       y = y1 - (fx+fw)*H
 *   rotation 180 : x = x1 - (fx+fw)*W  y = y0 + fy*H
 *   rotation 270 : x = x1 - (fy+fh)*W  y = y0 + fx*H
 * </pre>
 */
class PdfCoordinatesTest {

    private final PdfCoordinates coordinates = new PdfCoordinates();

    private static final double WIDTH = 595;
    private static final double HEIGHT = 842;
    private static final double DELTA = 0.001;

    @Test
    void topLeftOfAnUprightPage() {
        PdfCoordinates.PdfRect rect = coordinates.toPdfPoints(WIDTH, HEIGHT, 0, 0, 0, 0.2, 0.1);
        assertEquals(0, rect.x(), DELTA);
        assertEquals(HEIGHT - 0.1 * HEIGHT, rect.y(), DELTA);
        assertEquals(0.2 * WIDTH, rect.width(), DELTA);
        assertEquals(0.1 * HEIGHT, rect.height(), DELTA);
    }

    @Test
    void bottomRightOfAnUprightPage() {
        PdfCoordinates.PdfRect rect = coordinates.toPdfPoints(WIDTH, HEIGHT, 0, 0.5, 0.5, 0.25, 0.25);
        assertEquals(0.5 * WIDTH, rect.x(), DELTA);
        assertEquals(HEIGHT - 0.75 * HEIGHT, rect.y(), DELTA);
        assertEquals(0.25 * WIDTH, rect.width(), DELTA);
        assertEquals(0.25 * HEIGHT, rect.height(), DELTA);
    }

    @Test
    void aPageRotatedByNinetyDegreesSwapsWidthAndHeight() {
        // The visible page is 842 x 595 points wide.
        PdfCoordinates.PdfRect rect = coordinates.toPdfPoints(WIDTH, HEIGHT, 90, 0, 0, 0.2, 0.1);
        assertEquals(0, rect.x(), DELTA);
        assertEquals(HEIGHT - 0.2 * HEIGHT, rect.y(), DELTA);
        assertEquals(0.1 * WIDTH, rect.width(), DELTA);
        assertEquals(0.2 * HEIGHT, rect.height(), DELTA);
    }

    @Test
    void aPageRotatedByNinetyDegreesMapsTheCentre() {
        PdfCoordinates.PdfRect rect = coordinates.toPdfPoints(WIDTH, HEIGHT, 90, 0.5, 0.5, 0.1, 0.1);
        assertEquals(0.5 * WIDTH, rect.x(), DELTA);
        assertEquals(HEIGHT - 0.6 * HEIGHT, rect.y(), DELTA);
        assertEquals(0.1 * WIDTH, rect.width(), DELTA);
        assertEquals(0.1 * HEIGHT, rect.height(), DELTA);
    }

    @Test
    void aPageRotatedByOneHundredAndEightyDegrees() {
        PdfCoordinates.PdfRect rect = coordinates.toPdfPoints(WIDTH, HEIGHT, 180, 0.5, 0.5, 0.2, 0.1);
        assertEquals(WIDTH - 0.7 * WIDTH, rect.x(), DELTA);
        assertEquals(0.5 * HEIGHT, rect.y(), DELTA);
        assertEquals(0.2 * WIDTH, rect.width(), DELTA);
        assertEquals(0.1 * HEIGHT, rect.height(), DELTA);
    }

    @Test
    void aPageRotatedByTwoHundredAndSeventyDegrees() {
        PdfCoordinates.PdfRect rect = coordinates.toPdfPoints(WIDTH, HEIGHT, 270, 0.5, 0.5, 0.2, 0.1);
        assertEquals(WIDTH - 0.6 * WIDTH, rect.x(), DELTA);
        assertEquals(0.5 * HEIGHT, rect.y(), DELTA);
        assertEquals(0.1 * WIDTH, rect.width(), DELTA);
        assertEquals(0.2 * HEIGHT, rect.height(), DELTA);
    }

    @Test
    void theTopLeftCornerOfARotatedPageIsTheTopLeftOfTheUnrotatedPage() {
        // pdf.js renders the top left of the view as the top left of the page for a 90 degree
        // rotation, so the field that starts there has to start at the top of the page too.
        PdfCoordinates.PdfRect rect = coordinates.toPdfPoints(WIDTH, HEIGHT, 90, 0, 0, 0.1, 0.1);
        assertEquals(0, rect.x(), DELTA);
        assertEquals(HEIGHT - 0.1 * HEIGHT, rect.y(), DELTA);
    }

    @Test
    void theVisibleSizeDependsOnTheRotation() {
        PDPage page = new PDPage(PDRectangle.A4);
        double width = page.getMediaBox().getWidth();
        double height = page.getMediaBox().getHeight();
        assertEquals(width, coordinates.visibleSize(page)[0], DELTA);
        assertEquals(height, coordinates.visibleSize(page)[1], DELTA);
        page.setRotation(90);
        assertEquals(height, coordinates.visibleSize(page)[0], DELTA);
        assertEquals(width, coordinates.visibleSize(page)[1], DELTA);
    }

    @Test
    void theRectangleStaysInsideThePage() {
        for (int rotation : new int[]{0, 90, 180, 270}) {
            PdfCoordinates.PdfRect rect = coordinates.toPdfPoints(WIDTH, HEIGHT, rotation, 0, 0, 1, 1);
            assertTrue(rect.x() >= -DELTA && rect.y() >= -DELTA,
                    "the rectangle must stay inside the page (rotation " + rotation + ")");
            assertTrue(rect.x() + rect.width() <= WIDTH + DELTA);
            assertTrue(rect.y() + rect.height() <= HEIGHT + DELTA);
            assertTrue(rect.width() > 0 && rect.height() > 0);
        }
    }
}
