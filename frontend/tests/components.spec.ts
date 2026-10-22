import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import SignaturePad from '@/components/SignaturePad.vue'
import StatusChip from '@/components/StatusChip.vue'
import ToggleSwitch from '@/components/ToggleSwitch.vue'
import { isPngDataUrl } from '@/utils/signature'

const PNG = 'data:image/png;base64,iVBORw0KGgo='

function fakeContext(): CanvasRenderingContext2D {
  const context = {
    font: '',
    fillStyle: '',
    textAlign: '',
    textBaseline: '',
    lineWidth: 0,
    lineCap: '',
    lineJoin: '',
    strokeStyle: '',
    fillText: vi.fn(),
    measureText: vi.fn(() => ({ width: 100 })),
    clearRect: vi.fn(),
    beginPath: vi.fn(),
    moveTo: vi.fn(),
    lineTo: vi.fn(),
    stroke: vi.fn(),
    drawImage: vi.fn(),
    getImageData: vi.fn(() => ({ data: new Uint8ClampedArray(16) })),
    setTransform: vi.fn(),
  }
  return context as unknown as CanvasRenderingContext2D
}

describe('the components', () => {
  beforeEach(() => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(
      (() => fakeContext()) as unknown as HTMLCanvasElement['getContext'],
    )
    vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockImplementation(() => PNG)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('exports the drawn signature as a PNG data URL', async () => {
    const wrapper = mount(SignaturePad, { props: { modelValue: null } })
    const canvas = wrapper.get('canvas')

    canvas.element.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true, clientX: 20, clientY: 30 }))
    canvas.element.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientX: 60, clientY: 55 }))
    canvas.element.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }))
    await wrapper.vm.$nextTick()

    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const value = emitted?.[0]?.[0]
    expect(isPngDataUrl(String(value))).toBe(true)
  })

  it('switches between drawing and typing', async () => {
    const wrapper = mount(SignaturePad, { props: { modelValue: null } })

    const tabs = wrapper.findAll('[role="tab"]')
    expect(tabs).toHaveLength(2)

    await tabs[1]?.trigger('click')
    expect(wrapper.emitted('update:mode')?.[0]?.[0]).toBe('type')
  })

  it('labels a status chip and colours it', () => {
    const completed = mount(StatusChip, { props: { status: 'completed' } })
    expect(completed.text()).toContain('Completed')
    expect(completed.classes()).toContain('chip-green')

    const sent = mount(StatusChip, { props: { status: 'sent' } })
    expect(sent.text()).toContain('Waiting')
    expect(sent.classes()).toContain('chip-amber')

    const expired = mount(StatusChip, { props: { status: 'expired' } })
    expect(expired.classes()).toContain('chip-violet')
  })

  it('exposes the toggle as a switch', async () => {
    const wrapper = mount(ToggleSwitch, { props: { modelValue: false, label: 'Required field' } })
    const button = wrapper.get('[role="switch"]')

    expect(button.attributes('aria-checked')).toBe('false')
    await button.trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]?.[0]).toBe(true)
  })
})
