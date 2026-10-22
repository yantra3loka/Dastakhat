<script setup lang="ts">
import { Compass } from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
</script>

<template>
  <div class="not-found">
    <span class="icon" aria-hidden="true"><Compass :size="22" /></span>
    <h1>This page does not exist</h1>
    <p>The address you have opened does not match any page of Dastakhat.</p>
    <BaseButton :to="{ name: 'dashboard' }">Back to the dashboard</BaseButton>
  </div>
</template>

<style scoped>
.not-found {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  text-align: center;
  padding: 24px;
  background: var(--canvas);
}

.icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: var(--blue-100);
  color: var(--blue-600);
}
</style>
