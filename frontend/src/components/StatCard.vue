<script setup lang="ts">
import type { Component } from 'vue'
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    label: string
    value: string | number
    icon?: Component
    tone?: 'blue' | 'green' | 'amber' | 'red' | 'violet' | 'slate'
    hint?: string
    active?: boolean
  }>(),
  { icon: undefined, tone: 'blue', hint: undefined, active: false },
)

const toneClass = computed(() => `stat-${props.tone}`)
</script>

<template>
  <div class="card stat" :class="[toneClass, { 'stat-active': active }]">
    <div class="stat-main">
      <p class="stat-label">{{ label }}</p>
      <p class="stat-value">{{ value }}</p>
      <p v-if="hint" class="stat-hint">{{ hint }}</p>
    </div>
    <div v-if="icon" class="stat-icon" aria-hidden="true">
      <component :is="icon" :size="18" />
    </div>
  </div>
</template>

<style scoped>
.stat {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px;
}

.stat-label {
  font-size: 12.5px;
  font-weight: 550;
  color: var(--ink-500);
  text-transform: uppercase;
  letter-spacing: 0.04em;
}

.stat-value {
  margin-top: 6px;
  font-size: 26px;
  font-weight: 680;
  color: var(--ink-900);
  line-height: 1.1;
}

.stat-hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--ink-500);
}

.stat-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--blue-100);
  color: var(--blue-600);
  flex: 0 0 auto;
}

.stat-active {
  border-color: var(--blue-500);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.stat-green .stat-icon {
  background: var(--green-100);
  color: var(--green-600);
}
.stat-amber .stat-icon {
  background: var(--amber-100);
  color: var(--amber-600);
}
.stat-red .stat-icon {
  background: var(--red-100);
  color: var(--red-600);
}
.stat-violet .stat-icon {
  background: var(--violet-100);
  color: var(--violet-700);
}
.stat-slate .stat-icon {
  background: var(--slate-100);
  color: var(--slate-700);
}
</style>
