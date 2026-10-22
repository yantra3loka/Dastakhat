<script setup lang="ts">
import { computed, ref } from 'vue'
import { GripVertical } from 'lucide-vue-next'
import type { EditorField } from '@/stores/envelopes'
import { MIN_FIELD_SIZE, clampFractionBox, type FractionBox } from '@/utils/coordinates'
import { fieldTypeLabel } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    field: EditorField
    color?: string
    selected?: boolean
    readonly?: boolean
    label?: string
  }>(),
  { color: '#1f5eff', selected: false, readonly: false, label: undefined },
)

const emit = defineEmits<{
  update: [patch: Partial<FractionBox>]
  select: []
  remove: []
}>()

const dragging = ref(false)
const resizing = ref(false)

const style = computed(() => ({
  left: `${props.field.x * 100}%`,
  top: `${props.field.y * 100}%`,
  width: `${props.field.w * 100}%`,
  height: `${props.field.h * 100}%`,
  borderColor: props.color,
  backgroundColor: props.selected ? `${props.color}22` : `${props.color}14`,
}))

const title = computed(() => props.label ?? fieldTypeLabel(props.field.type))

function pageRect(element: HTMLElement): DOMRect | null {
  const page = element.closest('[data-pdf-page]')
  if (!(page instanceof HTMLElement)) return null
  return page.getBoundingClientRect()
}

function onPointerDown(event: PointerEvent, mode: 'move' | 'resize'): void {
  if (props.readonly) return
  const target = event.currentTarget
  if (!(target instanceof HTMLElement)) return
  event.stopPropagation()
  event.preventDefault()
  target.setPointerCapture(event.pointerId)

  const rect = pageRect(target)
  if (!rect) return

  const startX = event.clientX
  const startY = event.clientY
  const start: FractionBox = { x: props.field.x, y: props.field.y, w: props.field.w, h: props.field.h }
  dragging.value = mode === 'move'
  resizing.value = mode === 'resize'
  emit('select')

  try {
    target.setPointerCapture(event.pointerId)
  } catch {
    // The pointer capture is not available, the drag still works with the
    // listeners on the element.
  }

  const onMove = (moveEvent: PointerEvent): void => {
    const dx = (moveEvent.clientX - startX) / (rect.width || 1)
    const dy = (moveEvent.clientY - startY) / (rect.height || 1)
    if (mode === 'move') {
      emit('update', clampFractionBox({ ...start, x: start.x + dx, y: start.y + dy }))
    } else {
      emit(
        'update',
        clampFractionBox({
          ...start,
          w: Math.max(MIN_FIELD_SIZE, start.w + dx),
          h: Math.max(MIN_FIELD_SIZE, start.h + dy),
        }),
      )
    }
  }

  const onUp = (): void => {
    dragging.value = false
    resizing.value = false
    target.removeEventListener('pointermove', onMove)
    target.removeEventListener('pointerup', onUp)
    target.removeEventListener('pointercancel', onUp)
  }

  target.addEventListener('pointermove', onMove)
  target.addEventListener('pointerup', onUp)
  target.addEventListener('pointercancel', onUp)
}

function onKeydown(event: KeyboardEvent): void {
  if (props.readonly) return
  const step = event.shiftKey ? 0.01 : 0.002
  let dx = 0
  let dy = 0
  if (event.key === 'ArrowLeft') dx = -step
  else if (event.key === 'ArrowRight') dx = step
  else if (event.key === 'ArrowUp') dy = -step
  else if (event.key === 'ArrowDown') dy = step
  else if (event.key === 'Delete' || event.key === 'Backspace') {
    event.preventDefault()
    emit('remove')
    return
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    emit('select')
    return
  } else {
    return
  }
  event.preventDefault()
  if (event.shiftKey) {
    emit('update', clampFractionBox({ ...props.field, w: props.field.w + dx, h: props.field.h + dy }))
  } else {
    emit('update', clampFractionBox({ ...props.field, x: props.field.x + dx, y: props.field.y + dy }))
  }
}
</script>

<template>
  <div
    class="field-overlay"
    :class="{ 'field-selected': selected, 'field-readonly': readonly, 'field-dragging': dragging || resizing }"
    :style="style"
    role="button"
    tabindex="0"
    :aria-label="`${title} field`"
    @pointerdown="onPointerDown($event, 'move')"
    @keydown="onKeydown"
    @click.stop="emit('select')"
  >
    <span class="field-tag" :style="{ backgroundColor: color }">{{ title }}</span>
    <span
      v-if="!readonly"
      class="field-handle"
      aria-hidden="true"
      @pointerdown="onPointerDown($event, 'resize')"
    >
      <GripVertical :size="12" />
    </span>
  </div>
</template>

<style scoped>
.field-overlay {
  position: absolute;
  border: 1.5px solid;
  border-radius: 3px;
  cursor: move;
  min-width: 12px;
  min-height: 12px;
  touch-action: none;
  transition: box-shadow 0.12s ease;
}

.field-overlay:hover {
  box-shadow: 0 0 0 2px rgba(31, 94, 255, 0.18);
}

.field-selected {
  box-shadow: 0 0 0 2px rgba(31, 94, 255, 0.45);
}

.field-readonly {
  cursor: default;
}

.field-dragging {
  box-shadow: 0 0 0 3px rgba(31, 94, 255, 0.35);
}

.field-tag {
  position: absolute;
  top: -1px;
  left: -1px;
  color: #fff;
  font-size: 9.5px;
  font-weight: 650;
  line-height: 1;
  padding: 2px 4px;
  border-radius: 3px 0 3px 0;
  white-space: nowrap;
  pointer-events: none;
  font-family: var(--font-sans);
}

.field-handle {
  position: absolute;
  right: -1px;
  bottom: -1px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  background: #fff;
  border: 1px solid currentColor;
  border-radius: 3px 0 0 0;
  cursor: nwse-resize;
  color: var(--ink-500);
}
</style>
