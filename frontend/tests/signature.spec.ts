import { describe, expect, it, vi } from 'vitest'
import {
  dataUrlByteLength,
  exportCanvasAsPng,
  isPngDataUrl,
  isWithinSignatureLimit,
  MAX_SIGNATURE_BYTES,
  renderTypedSignature,
  trimCanvasToInk,
  type CanvasFactory,
} from '@/utils/signature'

const PNG = 'data:image/png;base64,iVBORw0KGgo='

interface FakeContext {
  font: string
  fillStyle: string
  textAlign: string
  textBaseline: string
  fillText: ReturnType<typeof vi.fn>
  measureText: ReturnType<typeof vi.fn>
  clearRect: ReturnType<typeof vi.fn>
  beginPath: ReturnType<typeof vi.fn>
  moveTo: ReturnType<typeof vi.fn>
  lineTo: ReturnType<typeof vi.fn>
  stroke: ReturnType<typeof vi.fn>
  drawImage: ReturnType<typeof vi.fn>
  getImageData: ReturnType<typeof vi.fn>
  setTransform: ReturnType<typeof vi.fn>
}

function fakeContext(): FakeContext {
  return {
    font: '',
    fillStyle: '',
    textAlign: '',
    textBaseline: '',
    fillText: vi.fn(),
    measureText: vi.fn(() => ({ width: 120 })),
    clearRect: vi.fn(),
    beginPath: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    stroke: vi.fn(),
    drawImage: vi.fn(),
    getImageData: vi.fn(() => ({ data: new Uint8ClampedArray(16) })),
    setTransform: vi.fn(),
  }
}

function fakeCanvas(context: FakeContext): HTMLCanvasElement {
  return {
    width: 480,
    height: 160,
    getContext: () => context as unknown as CanvasRenderingContext2D,
    toDataURL: () => PNG,
  } as unknown as HTMLCanvasElement
}

describe('the signature helpers', () => {
  it('recognises a PNG data URL', () => {
    expect(isPngDataUrl(PNG)).toBe(true)
    expect(isPngDataUrl('data:image/jpeg;base64,AAAA')).toBe(false)
    expect(isPngDataUrl('')).toBe(false)
    expect(isPngDataUrl(null)).toBe(false)
  })

  it('calculates the size of a signature image', () => {
    expect(dataUrlByteLength('data:image/png;base64,AAAA')).toBe(3)
    expect(dataUrlByteLength('data:image/png;base64,AAA=')).toBe(2)
    expect(dataUrlByteLength('data:image/png;base64,AA==')).toBe(1)
    expect(dataUrlByteLength('')).toBe(0)
  })

  it('knows when a signature is too big for the backend', () => {
    const small = `data:image/png;base64,${'A'.repeat(100)}`
    const big = `data:image/png;base64,${'A'.repeat(MAX_SIGNATURE_BYTES * 2)}`
    expect(isWithinSignatureLimit(small)).toBe(true)
    expect(isWithinSignatureLimit(big)).toBe(false)
  })

  it('exports a canvas as a PNG data URL', () => {
    const exported = exportCanvasAsPng({ toDataURL: () => PNG })
    expect(exported).toBe(PNG)
  })

  it('refuses to export something that is not a PNG', () => {
    expect(() => exportCanvasAsPng({ toDataURL: () => 'data:image/jpeg;base64,AAAA' })).toThrowError(/PNG/)
  })

  it('renders a typed signature in a script font', () => {
    const context = fakeContext()
    const factory: CanvasFactory = () => fakeCanvas(context)

    const exported = renderTypedSignature('Asha Rao', { fontSize: 64 }, factory)

    expect(isPngDataUrl(exported)).toBe(true)
    expect(context.font).toContain('italic')
    expect(context.font).toContain('64px')
    expect(context.fillText).toHaveBeenCalledTimes(1)
    expect(context.fillText.mock.calls[0]?.[0]).toBe('Asha Rao')
  })

  it('shrinks a long name so that it fits into the image', () => {
    const context = fakeContext()
    context.measureText = vi.fn(() => ({ width: 900 }))
    const factory: CanvasFactory = () => fakeCanvas(context)

    renderTypedSignature('A very long name that does not fit', { fontSize: 64 }, factory)

    expect(context.font).not.toContain('64px')
    const size = Number(context.font.match(/(\d+)px/)?.[1] ?? '0')
    expect(size).toBeLessThan(64)
    expect(size).toBeGreaterThan(0)
  })

  it('refuses to render an empty name', () => {
    expect(() => renderTypedSignature('   ', {}, () => fakeCanvas(fakeContext()))).toThrowError(/name/i)
  })

  it('keeps the canvas when the pixels cannot be read', () => {
    const context = fakeContext()
    context.getImageData = vi.fn(() => {
      throw new Error('no pixels')
    })
    const canvas = fakeCanvas(context)
    expect(trimCanvasToInk(canvas, () => fakeCanvas(fakeContext()))).toBe(canvas)
  })

  it('cuts the transparent border away', () => {
    const context = fakeContext()
    const pixels = new Uint8ClampedArray(4 * 4 * 4)
    // The ink sits in the middle of the image.
    pixels[(1 * 4 + 1) * 4 + 3] = 255
    context.getImageData = vi.fn(() => ({ data: pixels }))
    const source = fakeCanvas(context)
    Object.defineProperty(source, 'width', { value: 4 })
    Object.defineProperty(source, 'height', { value: 4 })

    const targetContext = fakeContext()
    const trimmed = trimCanvasToInk(source, () => fakeCanvas(targetContext))

    expect(trimmed).not.toBe(source)
    expect(targetContext.drawImage).toHaveBeenCalled()
  })
})
