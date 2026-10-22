<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    /** The progress between 0 and 1. */
    value: number
    label?: string
    showValue?: boolean
    complete?: boolean
  }>(),
  { label: undefined, showValue: false, complete: false },
)

const percent = computed(() => {
  const raw = Number.isFinite(props.value) ? props.value : 0
  return Math.round(Math.min(Math.max(raw, 0), 1) * 100)
})
</script>

<template>
  <div class="progress-group">
    <div v-if="label || showValue" class="progress-meta">
      <span v-if="label" class="progress-label">{{ label }}</span>
      <span v-if="showValue" class="progress-value">{{ percent }}%</span>
    </div>
    <div
      class="progress"
      role="progressbar"
      :aria-valuenow="percent"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-label="label ?? 'Progress'"
    >
      <div class="progress-bar" :class="{ 'progress-bar-complete': complete }" :style="{ width: `${percent}%` }" />
    </div>
  </div>
</template>

<style scoped>
.progress-group {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 120px;
}

.progress-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: var(--ink-500);
}

.progress-value {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
  color: var(--ink-700);
}
</style>
