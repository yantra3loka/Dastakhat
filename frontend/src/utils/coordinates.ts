/**
 * The coordinate helpers of the document editor.
 *
 * A placed field is stored as fractions (0..1) of the rendered page with a
 * **top left origin**. The fractions therefore survive a zoom, a resize of the
 * browser window and a reload of the page.
 *
 * The conversion from a pixel position to a fraction always uses the bounding
 * box of the rendered page element, never the width of the PDF page, because
 * the rendered element already contains the rotation of the page.
 */

/** The bounding box of the rendered page element in viewport pixels. */
export interface PageRect {
  left: number
  top: number
  width: number
  height: number
}

/** A rectangle in page fractions (0..1) with a top left origin. */
export interface FractionBox {
  x: number
  y: number
  w: number
  h: number
}

/** A rectangle in viewport pixels. */
export interface PixelBox {
  left: number
  top: number
  width: number
  height: number
}

/** The smallest size a field may have, as a fraction of the page. */
export const MIN_FIELD_SIZE = 0.01

function clamp(value: number, min: number, max: number): number {
  if (Number.isNaN(value)) return min
  if (value < min) return min
  if (value > max) return max
  return value
}

/**
 * Moves a fraction box so that it stays inside the page and keeps its size.
 */
export function clampFractionBox(box: FractionBox): FractionBox {
  const w = clamp(box.w, MIN_FIELD_SIZE, 1)
  const h = clamp(box.h, MIN_FIELD_SIZE, 1)
  const x = clamp(box.x, 0, Math.max(0, 1 - w))
  const y = clamp(box.y, 0, Math.max(0, 1 - h))
  return { x, y, w, h }
}

/**
 * Converts a rectangle of viewport pixels into page fractions.
 *
 * @param box    the rectangle in viewport pixels
 * @param rect   the bounding box of the rendered page element
 */
export function pixelsToFraction(box: PixelBox, rect: PageRect): FractionBox {
  const width = rect.width || 1
  const height = rect.height || 1
  return {
    x: (box.left - rect.left) / width,
    y: (box.top - rect.top) / height,
    w: box.width / width,
    h: box.height / height,
  }
}

/**
 * Converts a rectangle of page fractions into viewport pixels.
 */
export function fractionToPixels(box: FractionBox, rect: PageRect): PixelBox {
  return {
    left: rect.left + box.x * rect.width,
    top: rect.top + box.y * rect.height,
    width: box.w * rect.width,
    height: box.h * rect.height,
  }
}

/**
 * The position of a pointer event relative to the page element, as fractions.
 */
export function pointerToFraction(
  point: { clientX: number; clientY: number },
  rect: PageRect,
): { x: number; y: number } {
  const width = rect.width || 1
  const height = rect.height || 1
  return {
    x: clamp((point.clientX - rect.left) / width, 0, 1),
    y: clamp((point.clientY - rect.top) / height, 0, 1),
  }
}

/**
 * The size of the page as it is displayed. A page that carries a `/Rotate`
 * entry of 90 or 270 degrees shows its sides swapped.
 */
export function visiblePageSize(
  mediaWidth: number,
  mediaHeight: number,
  rotation: number,
): { width: number; height: number } {
  const normalised = ((Math.trunc(rotation) % 360) + 360) % 360
  if (normalised === 90 || normalised === 270) {
    return { width: mediaHeight, height: mediaWidth }
  }
  return { width: mediaWidth, height: mediaHeight }
}

/**
 * Converts a rectangle of page fractions into the PDF user space rectangle
 * (points, origin bottom left) of the unrotated media box.
 *
 * The formula is the counterpart of `PdfCoordinates` in the backend, it is used
 * to make sure that the frontend and the backend agree on where a field ends
 * up - especially for a rotated page.
 */
export function fractionToMediaBox(
  mediaWidth: number,
  mediaHeight: number,
  rotation: number,
  box: FractionBox,
): { x: number; y: number; width: number; height: number } {
  const normalised = ((Math.trunc(rotation) % 360) + 360) % 360
  const { x: fx, y: fy, w: fw, h: fh } = box
  switch (normalised) {
    case 90:
      return {
        x: fy * mediaWidth,
        y: fx * mediaHeight,
        width: fh * mediaWidth,
        height: fw * mediaHeight,
      }
    case 180:
      return {
        x: mediaWidth - (fx + fw) * mediaWidth,
        y: mediaHeight + fy * mediaHeight,
        width: fw * mediaWidth,
        height: fh * mediaHeight,
      }
    case 270:
      return {
        x: mediaWidth - (fy + fh) * mediaWidth,
        y: mediaHeight - (fx + fw) * mediaHeight,
        width: fh * mediaWidth,
        height: fw * mediaHeight,
      }
    default:
      return {
        x: fx * mediaWidth,
        y: mediaHeight - (fy + fh) * mediaHeight,
        width: fw * mediaWidth,
        height: fh * mediaHeight,
      }
  }
}

/**
 * The default size of a new field, as fractions of the page.
 */
export function defaultFieldSize(type: 'signature' | 'initials' | 'date' | 'text' | 'name'): FractionBox {
  switch (type) {
    case 'signature':
      return { x: 0, y: 0, w: 0.28, h: 0.06 }
    case 'initials':
      return { x: 0, y: 0, w: 0.1, h: 0.04 }
    case 'date':
      return { x: 0, y: 0, w: 0.16, h: 0.035 }
    case 'name':
      return { x: 0, y: 0, w: 0.22, h: 0.035 }
    default:
      return { x: 0, y: 0, w: 0.24, h: 0.04 }
  }
}
