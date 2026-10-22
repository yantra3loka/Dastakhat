<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    label?: string
    description?: string
    disabled?: boolean
    id?: string
  }>(),
  {
    label: undefined,
    description: undefined,
    disabled: false,
    id: undefined,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const describedBy = computed(() => (props.description ? `${props.id ?? 'toggle'}-description` : undefined))

function toggle(): void {
  if (props.disabled) return
  emit('update:modelValue', !props.modelValue)
}
</script>

<template>
  <div class="toggle-field">
    <button
      :id="id"
      type="button"
      role="switch"
      class="toggle"
      :class="{ 'toggle-on': modelValue }"
      :aria-checked="modelValue ? 'true' : 'false'"
      :aria-describedby="describedBy"
      :disabled="disabled"
      @click="toggle"
    >
      <span class="toggle-knob" aria-hidden="true" />
    </button>
    <span class="toggle-text">
      <span v-if="label" class="toggle-label">{{ label }}</span>
      <span v-if="description" :id="describedBy" class="toggle-description">{{ description }}</span>
    </span>
  </div>
</template>

<style scoped>
.toggle-field {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.toggle {
  position: relative;
  width: 40px;
  height: 22px;
  flex: 0 0 auto;
  border-radius: 999px;
  border: 1px solid var(--line-strong);
  background: var(--ink-300);
  cursor: pointer;
  padding: 0;
  transition:
    background-color 0.18s ease,
    border-color 0.18s ease;
}

.toggle-on {
  background: var(--blue-600);
  border-color: var(--blue-600);
}

.toggle:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.toggle-knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  box-shadow: var(--shadow-sm);
  transition: transform 0.18s ease;
}

.toggle-on .toggle-knob {
  transform: translateX(18px);
}

.toggle-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.toggle-label {
  font-size: 13.5px;
  font-weight: 550;
  color: var(--ink-800);
}

.toggle-description {
  font-size: 12px;
  color: var(--ink-500);
}
</style>
