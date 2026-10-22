<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Eraser, PenLine, Type } from 'lucide-vue-next'
import { exportCanvasAsPng, renderTypedSignature, trimCanvasToInk } from '@/utils/signature'

const props = withDefaults(
  defineProps<{
    modelValue: string | null
    mode?: 'draw' | 'type'
    typedName?: string
    disabled?: boolean
  }>(),
  { mode: 'draw', typedName: '', disabled: false },
)

const emit = defineEmits<{
  'update:modelValue': [value: string | null]
  'update:mode': [value: 'draw' | 'type']
  'update:typedName': [value: string]
}>()

const canvas = ref<HTMLCanvasElement | null>(null)
const drawing = ref(false)
const hasInk = ref(false)
const error = ref<string | null>(null)

const preview = computed(() => props.modelValue)

function context(): CanvasRenderingContext2D | null {
  return canvas.value?.getContext('2d') ?? null
}

function prepareCanvas(): void {
  const element = canvas.value
  if (!element) return
  const ratio = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1
  const rect = element.getBoundingClientRect()
  const width = Math.max(rect.width, 320)
  const height = Math.max(rect.height, 140)
  element.width = Math.round(width * ratio)
  element.height = Math.round(height * ratio)
  const ctx = element.getContext('2d')
  if (!ctx) return
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
  ctx.lineWidth = 2.2
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  ctx.strokeStyle = '#101828'
}

function position(event: PointerEvent): { x: number; y: number } {
  const element = canvas.value
  if (!element) return { x: 0, y: 0 }
  const rect = element.getBoundingClientRect()
  return { x: event.clientX - rect.left, y: event.clientY - rect.top }
}

function onPointerDown(event: PointerEvent): void {
  if (props.disabled) return
  const ctx = context()
  if (!ctx) return
  event.preventDefault()
  const { x, y } = position(event)
  ctx.beginPath()
  ctx.moveTo(x, y)
  ctx.lineTo(x + 0.01, y + 0.01)
  ctx.stroke()
  drawing.value = true
  hasInk.value = true
  const target = event.currentTarget
  if (target instanceof HTMLElement && typeof target.setPointerCapture === 'function') {
    try {
      target.setPointerCapture(event.pointerId)
    } catch {
      // The pointer capture is not available, the drawing still works.
    }
  }
}

function onPointerMove(event: PointerEvent): void {
  if (!drawing.value || props.disabled) return
  const ctx = context()
  if (!ctx) return
  event.preventDefault()
  const { x, y } = position(event)
  ctx.lineTo(x, y)
  ctx.stroke()
}

function onPointerUp(): void {
  if (!drawing.value) return
  drawing.value = false
  exportSignature()
}

function exportSignature(): void {
  const element = canvas.value
  if (!element) return
  try {
    const trimmed = trimCanvasToInk(element)
    emit('update:modelValue', exportCanvasAsPng(trimmed))
    error.value = null
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'The signature could not be exported.'
  }
}

function clear(): void {
  const element = canvas.value
  const ctx = context()
  if (element && ctx) {
    ctx.clearRect(0, 0, element.width, element.height)
  }
  hasInk.value = false
  emit('update:modelValue', null)
}

function applyTyped(): void {
  const name = props.typedName.trim()
  if (!name) {
    error.value = 'Please type the name that should be signed.'
    return
  }
  try {
    emit('update:modelValue', renderTypedSignature(name))
    error.value = null
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : 'The signature could not be created.'
  }
}

function selectMode(mode: 'draw' | 'type'): void {
  emit('update:mode', mode)
}

watch(
  () => props.mode,
  async (mode) => {
    if (mode === 'draw') {
      await Promise.resolve()
      prepareCanvas()
    }
  },
)

onMounted(() => {
  prepareCanvas()
})
</script>

<template>
  <div class="signature-pad">
    <div class="tabs" role="tablist" aria-label="How the signature is created">
      <button
        type="button"
        role="tab"
        class="tab"
        :class="{ 'tab-active': mode === 'draw' }"
        :aria-selected="mode === 'draw' ? 'true' : 'false'"
        :disabled="disabled"
        @click="selectMode('draw')"
      >
        <PenLine :size="15" aria-hidden="true" />
        Draw
      </button>
      <button
        type="button"
        role="tab"
        class="tab"
        :class="{ 'tab-active': mode === 'type' }"
        :aria-selected="mode === 'type' ? 'true' : 'false'"
        :disabled="disabled"
        @click="selectMode('type')"
      >
        <Type :size="15" aria-hidden="true" />
        Type
      </button>
    </div>

    <div v-show="mode === 'draw'" class="pad-draw">
      <canvas
        ref="canvas"
        class="pad-canvas"
        aria-label="Draw your signature with the mouse or a finger"
        role="img"
        @pointerdown="onPointerDown"
        @pointermove="onPointerMove"
        @pointerup="onPointerUp"
        @pointerleave="onPointerUp"
      />
      <div class="pad-actions">
        <button type="button" class="btn btn-secondary btn-sm" :disabled="disabled || !hasInk" @click="clear">
          <Eraser :size="14" aria-hidden="true" />
          Clear
        </button>
      </div>
    </div>

    <div v-show="mode === 'type'" class="pad-type">
      <div class="field">
        <label class="field-label" for="signature-typed-name">Type your full name</label>
        <input
          id="signature-typed-name"
          class="input"
          type="text"
          :value="typedName"
          :disabled="disabled"
          autocomplete="name"
          placeholder="Your name"
          @input="emit('update:typedName', ($event.target as HTMLInputElement).value)"
          @blur="applyTyped"
        />
      </div>
      <div class="typed-preview">
        <img v-if="preview" :src="preview" alt="The signature that will be placed on the document" />
        <p v-else class="muted text-sm">The typed signature appears here.</p>
      </div>
      <div class="pad-actions">
        <button type="button" class="btn btn-secondary btn-sm" :disabled="disabled" @click="applyTyped">
          Use this signature
        </button>
      </div>
    </div>

    <p v-if="error" class="field-error" role="alert">{{ error }}</p>
  </div>
</template>

<style scoped>
.signature-pad {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tabs {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  width: fit-content;
}

.tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: none;
  background: transparent;
  border-radius: var(--radius-sm);
  padding: 7px 13px;
  font-size: 13px;
  font-weight: 550;
  color: var(--ink-600);
  cursor: pointer;
}

.tab-active {
  background: var(--surface);
  color: var(--blue-600);
  box-shadow: var(--shadow-sm);
}

.pad-draw,
.pad-type {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.pad-canvas {
  width: 100%;
  height: 160px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius);
  background: #fff;
  touch-action: none;
  cursor: crosshair;
}

.typed-preview {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  padding: 12px;
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius);
  background: #fff;
}

.typed-preview img {
  max-width: 100%;
  max-height: 110px;
}

.pad-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
