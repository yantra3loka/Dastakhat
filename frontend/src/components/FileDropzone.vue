<script setup lang="ts">
import { computed, ref } from 'vue'
import { FileUp, Upload } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    accept?: string
    disabled?: boolean
    label?: string
    hint?: string
  }>(),
  {
    accept: 'application/pdf,.pdf',
    disabled: false,
    label: 'Drop a PDF here',
    hint: 'or click to choose a file - one PDF, up to 10 MB and 50 pages',
  },
)

const emit = defineEmits<{ files: [files: File[]] }>()

const dragging = ref(false)
const input = ref<HTMLInputElement | null>(null)

const acceptList = computed(() =>
  props.accept
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean)
    .join(','),
)

function isAccepted(file: File): boolean {
  if (!props.accept) return true
  const name = file.name.toLowerCase()
  return acceptList.value
    .split(',')
    .some((entry) => (entry.startsWith('.') ? name.endsWith(entry) : file.type === entry))
}

function emitFiles(files: FileList | null): void {
  if (!files || files.length === 0) return
  const list = Array.from(files).filter(isAccepted)
  if (list.length === 0) return
  emit('files', list)
}

function onDrop(event: DragEvent): void {
  dragging.value = false
  if (props.disabled) return
  emitFiles(event.dataTransfer?.files ?? null)
}

function onPick(event: Event): void {
  const target = event.target as HTMLInputElement
  emitFiles(target.files)
  target.value = ''
}

function openPicker(): void {
  if (props.disabled) return
  input.value?.click()
}
</script>

<template>
  <div
    class="dropzone"
    :class="{ 'dropzone-active': dragging, 'dropzone-disabled': disabled }"
    role="button"
    tabindex="0"
    :aria-disabled="disabled ? 'true' : undefined"
    aria-label="Upload a PDF document"
    @click="openPicker"
    @keydown.enter.prevent="openPicker"
    @keydown.space.prevent="openPicker"
    @dragover.prevent="dragging = true"
    @dragleave.prevent="dragging = false"
    @drop.prevent="onDrop"
  >
    <input
      ref="input"
      class="dropzone-input"
      type="file"
      :accept="acceptList"
      :disabled="disabled"
      aria-hidden="true"
      tabindex="-1"
      @change="onPick"
    />
    <span class="dropzone-icon" aria-hidden="true">
      <FileUp :size="22" />
    </span>
    <p class="dropzone-title">{{ label }}</p>
    <p class="dropzone-hint">{{ hint }}</p>
    <span class="dropzone-action">
      <Upload :size="14" aria-hidden="true" />
      Choose file
    </span>
  </div>
</template>

<style scoped>
.dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  text-align: center;
  border: 1.5px dashed var(--line-strong);
  border-radius: var(--radius-lg);
  background: var(--surface-muted);
  padding: 34px 20px;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    background-color 0.15s ease;
}

.dropzone:hover,
.dropzone:focus-visible {
  border-color: var(--blue-500);
  background: var(--blue-50);
}

.dropzone-active {
  border-color: var(--blue-600);
  background: var(--blue-100);
}

.dropzone-disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.dropzone-input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.dropzone-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: var(--blue-100);
  color: var(--blue-600);
}

.dropzone-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-800);
}

.dropzone-hint {
  font-size: 12.5px;
  color: var(--ink-500);
  max-width: 420px;
}

.dropzone-action {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 4px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--blue-600);
}
</style>
