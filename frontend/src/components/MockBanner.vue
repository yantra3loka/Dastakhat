<script setup lang="ts">
import { TriangleAlert } from 'lucide-vue-next'

withDefaults(
  defineProps<{
    message?: string
    title?: string
  }>(),
  {
    message: 'MOCK - this flow is a simulation. It has no legal standing and never contacts a real provider.',
    title: 'MOCK',
  },
)
</script>

<template>
  <div class="mock-banner" role="note" aria-label="Mock integration warning">
    <TriangleAlert :size="17" aria-hidden="true" />
    <span>
      <strong>{{ title }}</strong>
      <span class="mock-text">{{ message }}</span>
    </span>
  </div>
</template>

<style scoped>
.mock-text {
  margin-left: 6px;
  font-weight: 450;
}
</style>
