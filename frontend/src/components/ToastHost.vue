<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { CircleAlert, CircleCheck, Info, X } from 'lucide-vue-next'
import { useToastStore } from '@/stores/toast'

const store = useToastStore()
const { toasts } = storeToRefs(store)
</script>

<template>
  <div class="toast-host" aria-live="polite" aria-atomic="false">
    <div
      v-for="toast in toasts"
      :key="toast.id"
      class="toast"
      :class="`toast-${toast.type}`"
      :role="toast.type === 'error' ? 'alert' : 'status'"
    >
      <CircleCheck v-if="toast.type === 'success'" :size="17" aria-hidden="true" />
      <CircleAlert v-else-if="toast.type === 'error'" :size="17" aria-hidden="true" />
      <Info v-else :size="17" aria-hidden="true" />
      <span class="toast-message">{{ toast.message }}</span>
      <button
        type="button"
        class="btn btn-ghost btn-icon toast-close"
        aria-label="Dismiss the notification"
        @click="store.dismiss(toast.id)"
      >
        <X :size="14" aria-hidden="true" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.toast-message {
  flex: 1 1 auto;
}

.toast-close {
  flex: 0 0 auto;
  color: var(--ink-500);
}
</style>
