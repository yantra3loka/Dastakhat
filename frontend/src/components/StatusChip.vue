<script setup lang="ts">
import { computed } from 'vue'
import { statusLabel } from '@/utils/format'

const props = withDefaults(
  defineProps<{
    status?: string
    label?: string
    size?: 'sm' | 'md'
  }>(),
  { status: 'draft', label: undefined, size: 'md' },
)

const tone = computed(() => {
  switch (props.status) {
    case 'draft':
    case 'pending':
      return 'chip-slate'
    case 'sent':
    case 'notified':
    case 'viewed':
      return 'chip-amber'
    case 'completed':
    case 'signed':
      return 'chip-green'
    case 'declined':
      return 'chip-red'
    case 'voided':
      return 'chip-grey'
    case 'expired':
      return 'chip-violet'
    default:
      return 'chip-slate'
  }
})

const text = computed(() => props.label ?? statusLabel(props.status))
</script>

<template>
  <span class="chip" :class="[tone, size === 'sm' ? 'chip-sm' : '']">
    <span class="chip-dot" aria-hidden="true" />
    {{ text }}
  </span>
</template>

<style scoped>
.chip-slate {
  background: var(--slate-100);
  color: var(--slate-700);
}

.chip-amber {
  background: var(--amber-100);
  color: var(--amber-700);
}

.chip-green {
  background: var(--green-100);
  color: var(--green-600);
}

.chip-red {
  background: var(--red-100);
  color: var(--red-700);
}

.chip-grey {
  background: var(--grey-100);
  color: var(--grey-700);
}

.chip-violet {
  background: var(--violet-100);
  color: var(--violet-700);
}

.chip-sm {
  font-size: 11px;
  padding: 2px 8px;
}
</style>
