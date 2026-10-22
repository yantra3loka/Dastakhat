import { describe, expect, it } from 'vitest'
import {
  clampFractionBox,
  defaultFieldSize,
  fractionToMediaBox,
  fractionToPixels,
  pixelsToFraction,
  pointerToFraction,
  visiblePageSize,
  type PageRect,
} from '@/utils/coordinates'

const rect: PageRect = { left: 120, top: 60, width: 800, height: 1000 }

describe('the coordinate helpers', () => {
  it('converts a pixel box into page fractions', () => {
    const box = pixelsToFraction({ left: 200, top: 310, width: 160, height: 60 }, rect)
    expect(box.x).toBeCloseTo(0.1, 10)
    expect(box.y).toBeCloseTo(0.25, 10)
    expect(box.w).toBeCloseTo(0.2, 10)
    expect(box.h).toBeCloseTo(0.06, 10)
  })

  it('converts page fractions back into pixels', () => {
    const box = fractionToPixels({ x: 0.1, y: 0.25, w: 0.2, h: 0.06 }, rect)
    expect(box.left).toBeCloseTo(200, 10)
    expect(box.top).toBeCloseTo(310, 10)
    expect(box.width).toBeCloseTo(160, 10)
    expect(box.height).toBeCloseTo(60, 10)
  })

  it('survives a round trip so that a zoom does not move a field', () => {
    const original = { x: 0.42, y: 0.63, w: 0.11, h: 0.04 }
    const pixels = fractionToPixels(original, rect)
    const back = pixelsToFraction(pixels, rect)
    expect(back.x).toBeCloseTo(original.x, 10)
    expect(back.y).toBeCloseTo(original.y, 10)
    expect(back.w).toBeCloseTo(original.w, 10)
    expect(back.h).toBeCloseTo(original.h, 10)
  })

  it('keeps the position when the page is scrolled or resized', () => {
    const moved: PageRect = { left: 0, top: 250, width: 400, height: 500 }
    const scaled: PageRect = { left: 0, top: 250, width: 1200, height: 1500 }
    const original = { x: 0.2, y: 0.4, w: 0.3, h: 0.05 }

    const first = pixelsToFraction(fractionToPixels(original, moved), moved)
    const second = pixelsToFraction(fractionToPixels(original, scaled), scaled)

    expect(first.x).toBeCloseTo(original.x, 10)
    expect(first.y).toBeCloseTo(original.y, 10)
    expect(second.x).toBeCloseTo(original.x, 10)
    expect(second.y).toBeCloseTo(original.y, 10)
  })

  it('turns a pointer position into fractions and clamps it to the page', () => {
    expect(pointerToFraction({ clientX: 200, clientY: 310 }, rect)).toEqual({ x: 0.1, y: 0.25 })
    expect(pointerToFraction({ clientX: -50, clientY: 5000 }, rect)).toEqual({ x: 0, y: 1 })
  })

  it('keeps a field inside of the page', () => {
    const clamped = clampFractionBox({ x: 0.95, y: -0.2, w: 0.3, h: 0.05 })
    expect(clamped.x).toBeCloseTo(0.7, 10)
    expect(clamped.y).toBe(0)
    expect(clamped.w).toBeCloseTo(0.3, 10)
  })

  it('gives every field type a sensible default size', () => {
    expect(defaultFieldSize('signature').w).toBeGreaterThan(defaultFieldSize('initials').w)
    expect(defaultFieldSize('date').h).toBeLessThan(defaultFieldSize('signature').h)
  })
})

describe('a rotated page', () => {
  it('shows its sides swapped', () => {
    expect(visiblePageSize(612, 792, 0)).toEqual({ width: 612, height: 792 })
    expect(visiblePageSize(612, 792, 90)).toEqual({ width: 792, height: 612 })
    expect(visiblePageSize(612, 792, 180)).toEqual({ width: 612, height: 792 })
    expect(visiblePageSize(612, 792, 270)).toEqual({ width: 792, height: 612 })
  })

  it('normalises a negative or too large rotation', () => {
    expect(visiblePageSize(612, 792, -90)).toEqual({ width: 792, height: 612 })
    expect(visiblePageSize(612, 792, 450)).toEqual({ width: 792, height: 612 })
  })

  it('maps a fraction onto the media box of the unrotated page', () => {
    const box = { x: 0.1, y: 0.2, w: 0.3, h: 0.1 }

    const straight = fractionToMediaBox(612, 792, 0, box)
    expect(straight.x).toBeCloseTo(61.2, 8)
    expect(straight.y).toBeCloseTo(554.4, 8)
    expect(straight.width).toBeCloseTo(183.6, 8)
    expect(straight.height).toBeCloseTo(79.2, 8)

    const rotated = fractionToMediaBox(612, 792, 90, box)
    expect(rotated.x).toBeCloseTo(122.4, 8)
    expect(rotated.y).toBeCloseTo(79.2, 8)
    expect(rotated.width).toBeCloseTo(61.2, 8)
    expect(rotated.height).toBeCloseTo(237.6, 8)
  })

  it('keeps the fractions stable when the visible page changes', () => {
    const box = { x: 0.25, y: 0.5, w: 0.2, h: 0.1 }
    const visible = visiblePageSize(612, 792, 90)
    const pixels = fractionToPixels(box, { left: 0, top: 0, width: visible.width, height: visible.height })
    const back = pixelsToFraction(pixels, { left: 0, top: 0, width: visible.width, height: visible.height })
    expect(back.x).toBeCloseTo(box.x, 10)
    expect(back.y).toBeCloseTo(box.y, 10)
    expect(back.w).toBeCloseTo(box.w, 10)
    expect(back.h).toBeCloseTo(box.h, 10)
  })
})
