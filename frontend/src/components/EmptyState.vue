<script setup lang="ts">
import type { Component } from 'vue'

withDefaults(
  defineProps<{
    title: string
    description?: string
    icon?: Component
  }>(),
  { description: undefined, icon: undefined },
)
</script>

<template>
  <div class="empty-state">
    <div v-if="icon" class="empty-icon" aria-hidden="true">
      <component :is="icon" :size="22" />
    </div>
    <h3 class="empty-title">{{ title }}</h3>
    <p v-if="description" class="empty-description">{{ description }}</p>
    <div v-if="$slots.default" class="empty-actions">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  text-align: center;
  padding: 44px 24px;
}

.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: var(--blue-100);
  color: var(--blue-600);
}

.empty-title {
  font-size: 15px;
}

.empty-description {
  max-width: 420px;
  color: var(--ink-500);
  font-size: 13px;
}

.empty-actions {
  margin-top: 6px;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: center;
}
</style>
