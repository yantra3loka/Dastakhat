/**
 * The helpers of the signature capture.
 *
 * A signature is sent to the backend as a PNG data URL. The backend stores the
 * bytes and stamps them into the final PDF, so the export has to produce a
 * real PNG (the magic bytes are checked by the backend).
 */

/** The maximum size of a signature image, the backend rejects everything bigger. */
export const MAX_SIGNATURE_BYTES = 200 * 1024

/** The font stack that is used for a typed signature. */
export const SCRIPT_FONT_STACK =
  "'Segoe Script', 'Bradley Hand', 'Brush Script MT', 'Snell Roundhand', 'Lucida Handwriting', cursive"

/** The options of the typed signature renderer. */
export interface TypedSignatureOptions {
  width?: number
  height?: number
  fontSize?: number
  color?: string
  padding?: number
}

/** Creates a canvas element. It can be replaced in the tests. */
export type CanvasFactory = (width: number, height: number) => HTMLCanvasElement

const defaultCanvasFactory: CanvasFactory = (width, height) => {
  const canvas = document.createElement('canvas')
  canvas.width = width
  canvas.height = height
  return canvas
}

/** True when the value is a PNG data URL. */
export function isPngDataUrl(value: string | null | undefined): boolean {
  return typeof value === 'string' && /^data:image\/png;base64,/i.test(value.trim())
}

/** The number of bytes a data URL carries. */
export function dataUrlByteLength(value: string): number {
  const comma = value.indexOf(',')
  const base64 = comma >= 0 ? value.slice(comma + 1) : value
  const cleaned = base64.replace(/\s/g, '')
  if (!cleaned) return 0
  const padding = cleaned.endsWith('==') ? 2 : cleaned.endsWith('=') ? 1 : 0
  return Math.floor((cleaned.length * 3) / 4) - padding
}

/** True when the signature fits into the limit of the backend. */
export function isWithinSignatureLimit(value: string, maxBytes = MAX_SIGNATURE_BYTES): boolean {
  return dataUrlByteLength(value) <= maxBytes
}

/**
 * Exports the content of a canvas as a PNG data URL.
 */
export function exportCanvasAsPng(canvas: Pick<HTMLCanvasElement, 'toDataURL'>): string {
  const dataUrl = canvas.toDataURL('image/png')
  if (!isPngDataUrl(dataUrl)) {
    throw new Error('The signature image could not be exported as PNG.')
  }
  return dataUrl
}

/**
 * Renders a typed name in a script style font and returns it as a PNG data URL.
 */
export function renderTypedSignature(
  text: string,
  options: TypedSignatureOptions = {},
  createCanvas: CanvasFactory = defaultCanvasFactory,
): string {
  const name = (text ?? '').trim()
  if (!name) throw new Error('Please enter the name that should be signed.')

  const width = options.width ?? 480
  const height = options.height ?? 160
  const fontSize = options.fontSize ?? 64
  const padding = options.padding ?? 16
  const color = options.color ?? '#101828'

  const canvas = createCanvas(width, height)
  const context = canvas.getContext('2d')
  if (!context) throw new Error('The browser does not support the canvas element.')

  context.clearRect(0, 0, width, height)
  context.fillStyle = color
  context.textAlign = 'center'
  context.textBaseline = 'middle'
  context.font = `italic ${fontSize}px ${SCRIPT_FONT_STACK}`

  let fontSizeUsed = fontSize
  let metrics = context.measureText(name)
  const available = width - padding * 2
  if (metrics.width > available && available > 0) {
    fontSizeUsed = Math.max(12, Math.floor(fontSize * (available / metrics.width)))
    context.font = `italic ${fontSizeUsed}px ${SCRIPT_FONT_STACK}`
    metrics = context.measureText(name)
  }

  context.fillText(name, width / 2, height / 2, available)
  return exportCanvasAsPng(canvas)
}

/**
 * Removes the transparent border of a drawn signature.
 *
 * When the canvas does not support reading the pixels the original image is
 * returned unchanged.
 */
export function trimCanvasToInk(
  canvas: HTMLCanvasElement,
  createCanvas: CanvasFactory = defaultCanvasFactory,
): HTMLCanvasElement {
  try {
    const context = canvas.getContext('2d')
    if (!context) return canvas
    const { width, height } = canvas
    const pixels = context.getImageData(0, 0, width, height).data
    let minX = width
    let minY = height
    let maxX = -1
    let maxY = -1
    for (let y = 0; y < height; y += 1) {
      for (let x = 0; x < width; x += 1) {
        const alpha = pixels[(y * width + x) * 4 + 3] ?? 0
        if (alpha > 8) {
          if (x < minX) minX = x
          if (x > maxX) maxX = x
          if (y < minY) minY = y
          if (y > maxY) maxY = y
        }
      }
    }
    if (maxX < 0 || maxY < 0) return canvas
    const padding = 6
    const left = Math.max(0, minX - padding)
    const top = Math.max(0, minY - padding)
    const croppedWidth = Math.min(width - left, maxX - minX + 1 + padding * 2)
    const croppedHeight = Math.min(height - top, maxY - minY + 1 + padding * 2)
    if (croppedWidth <= 0 || croppedHeight <= 0) return canvas

    const target = createCanvas(croppedWidth, croppedHeight)
    const targetContext = target.getContext('2d')
    if (!targetContext) return canvas
    targetContext.drawImage(canvas, left, top, croppedWidth, croppedHeight, 0, 0, croppedWidth, croppedHeight)
    return target
  } catch {
    return canvas
  }
}
