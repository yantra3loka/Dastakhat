<script setup lang="ts">
import { computed, useId } from 'vue'
import type { SelectOption } from '@/types/ui'

const props = withDefaults(
  defineProps<{
    modelValue: string
    options: SelectOption[]
    label?: string
    error?: string | null
    hint?: string
    name?: string
    required?: boolean
    disabled?: boolean
    id?: string
  }>(),
  {
    label: undefined,
    error: null,
    hint: undefined,
    name: undefined,
    required: false,
    disabled: false,
    id: undefined,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const generatedId = useId()
const selectId = computed(() => props.id ?? generatedId)
const errorId = computed(() => `${selectId.value}-error`)
const hintId = computed(() => `${selectId.value}-hint`)

function onChange(event: Event): void {
  const target = event.target as HTMLSelectElement
  emit('update:modelValue', target.value)
}
</script>

<template>
  <div class="field">
    <label v-if="label" class="field-label" :for="selectId">{{ label }}</label>
    <select
      :id="selectId"
      class="select"
      :value="modelValue"
      :name="name"
      :required="required"
      :disabled="disabled"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="error ? errorId : hint ? hintId : undefined"
      @change="onChange"
    >
      <option v-for="option in options" :key="option.value" :value="option.value" :disabled="option.disabled">
        {{ option.label }}
      </option>
    </select>
    <p v-if="error" :id="errorId" class="field-error" role="alert">{{ error }}</p>
    <p v-else-if="hint" :id="hintId" class="field-hint">{{ hint }}</p>
  </div>
</template>
