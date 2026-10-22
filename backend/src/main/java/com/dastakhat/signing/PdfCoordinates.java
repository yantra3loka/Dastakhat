package com.dastakhat.signing;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.stereotype.Component;

/**
 * Converts the browser side page fractions into PDF user space points.
 *
 * <p>The SPA stores a field as a fraction (0..1) of the rendered page, measured from the top
 * left corner, so the position survives zooming and resizing. PDF user space has its origin
 * in the bottom left corner and is measured in points. Pages can additionally carry a
 * {@code /Rotate} entry which swaps the visible width and height, so the conversion has to
 * take the rotation into account.</p>
 */
@Component
public class PdfCoordinates {

    /** A rectangle in PDF user space (points), origin bottom left. */
    public record PdfRect(double x, double y, double width, double height) { }

    /**
     * Converts a field rectangle given in page fractions into PDF points.
     *
     * @param page the PDF page (its MediaBox and rotation are used)
     * @param fx   left edge as a fraction of the visible page width (0..1)
     * @param fy   top edge as a fraction of the visible page height (0..1)
     * @param fw   width as a fraction of the visible page width (0..1)
     * @param fh   height as a fraction of the visible page height (0..1)
     */
    public PdfRect toPdfPoints(PDPage page, double fx, double fy, double fw, double fh) {
        PDRectangle box = page.getMediaBox();
        double x0 = box.getLowerLeftX();
        double y0 = box.getLowerLeftY();
        double x1 = box.getUpperRightX();
        double y1 = box.getUpperRightY();
        double pageWidth = x1 - x0;
        double pageHeight = y1 - y0;

        int rotation = normaliseRotation(page.getRotation());

        // The visible (rotated) page has swapped sides for 90 and 270 degrees.
        double visibleWidth = (rotation == 90 || rotation == 270) ? pageHeight : pageWidth;
        double visibleHeight = (rotation == 90 || rotation == 270) ? pageWidth : pageHeight;

        // The corner of the field in the visible page, in PDF user space. The mapping is the
        // one of the pdf.js viewport, because the browser renders the pages with pdf.js.
        //
        //   rotation   0  : x = x0 + fx*W          y = y1 - (fy+fh)*H
        //   rotation  90  : x = x0 + fy*W          y = y1 - (fx+fw)*H
        //   rotation 180  : x = x1 - (fx+fw)*W     y = y0 + fy*H
        //   rotation 270  : x = x1 - (fy+fh)*W     y = y0 + fx*H
        double px;
        double py;
        switch (rotation) {
            case 90 -> {
                px = x0 + fy * pageWidth;
                py = y1 - (fx + fw) * pageHeight;
            }
            case 180 -> {
                px = x1 - (fx + fw) * pageWidth;
                py = y0 + fy * pageHeight;
            }
            case 270 -> {
                px = x1 - (fy + fh) * pageWidth;
                py = y0 + fx * pageHeight;
            }
            default -> {
                px = x0 + fx * pageWidth;
                py = y1 - (fy + fh) * pageHeight;
            }
        }

        double width = (rotation == 90 || rotation == 270) ? fh * pageWidth : fw * pageWidth;
        double height = (rotation == 90 || rotation == 270) ? fw * pageHeight : fh * pageHeight;

        return new PdfRect(px, py, width, height);
    }

    /** Convenience overload for a page that is not rotated. */
    public PdfRect toPdfPoints(double mediaWidth, double mediaHeight, double fx, double fy, double fw, double fh) {
        return toPdfPoints(mediaWidth, mediaHeight, 0, fx, fy, fw, fh);
    }

    /** Conversion with an explicit rotation, used by the unit tests. */
    public PdfRect toPdfPoints(double mediaWidth, double mediaHeight, int rotation,
                               double fx, double fy, double fw, double fh) {
        double x0 = 0;
        double y0 = 0;
        double x1 = mediaWidth;
        double y1 = mediaHeight;
        rotation = normaliseRotation(rotation);

        double pageWidth = x1 - x0;
        double pageHeight = y1 - y0;

        double px;
        double py;
        switch (rotation) {
            case 90 -> {
                px = x0 + fy * pageWidth;
                py = y1 - (fx + fw) * pageHeight;
            }
            case 180 -> {
                px = x1 - (fx + fw) * pageWidth;
                py = y0 + fy * pageHeight;
            }
            case 270 -> {
                px = x1 - (fy + fh) * pageWidth;
                py = y0 + fx * pageHeight;
            }
            default -> {
                px = x0 + fx * pageWidth;
                py = y1 - (fy + fh) * pageHeight;
            }
        }
        double width = (rotation == 90 || rotation == 270) ? fh * pageWidth : fw * pageWidth;
        double height = (rotation == 90 || rotation == 270) ? fw * pageHeight : fh * pageHeight;
        return new PdfRect(px, py, width, height);
    }

    /** The visible size of a page in points. */
    public double[] visibleSize(PDPage page) {
        PDRectangle box = page.getMediaBox();
        double width = box.getWidth();
        double height = box.getHeight();
        int rotation = normaliseRotation(page.getRotation());
        if (rotation == 90 || rotation == 270) {
            return new double[]{height, width};
        }
        return new double[]{width, height};
    }

    private int normaliseRotation(int rotation) {
        int r = rotation % 360;
        if (r < 0) {
            r += 360;
        }
        return r;
    }
}
