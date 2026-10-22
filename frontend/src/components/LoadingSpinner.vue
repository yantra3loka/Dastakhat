<script setup lang="ts">
withDefaults(defineProps<{ label?: string; inline?: boolean }>(), { label: undefined, inline: false })
</script>

<template>
  <div class="loading" :class="{ 'loading-inline': inline }" role="status" aria-live="polite">
    <span class="spinner" aria-hidden="true" />
    <span v-if="label" class="loading-label">{{ label }}</span>
    <span v-else class="sr-only">Loading</span>
  </div>
</template>

<style scoped>
.loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 36px 16px;
  color: var(--ink-500);
  font-size: 13px;
}

.loading-inline {
  flex-direction: row;
  padding: 0;
}

.spinner {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2px solid var(--line);
  border-top-color: var(--blue-600);
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .spinner {
    animation-duration: 2.4s;
  }
}
</style>
