<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { Loader2 } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
    size?: 'md' | 'sm'
    type?: 'button' | 'submit' | 'reset'
    disabled?: boolean
    loading?: boolean
    block?: boolean
    to?: string | { name: string; params?: Record<string, string> }
    ariaLabel?: string
  }>(),
  {
    variant: 'primary',
    size: 'md',
    type: 'button',
    disabled: false,
    loading: false,
    block: false,
    to: undefined,
    ariaLabel: undefined,
  },
)

const classes = computed(() => [
  `btn-${props.variant}`,
  props.size === 'sm' ? 'btn-sm' : '',
  props.block ? 'btn-block' : '',
])
</script>

<template>
  <RouterLink
    v-if="to"
    :to="to"
    class="btn"
    :class="classes"
    :aria-label="ariaLabel"
    :aria-disabled="disabled ? 'true' : undefined"
    :tabindex="disabled ? -1 : undefined"
  >
    <slot />
  </RouterLink>
  <button
    v-else
    :type="type"
    class="btn"
    :class="classes"
    :disabled="disabled || loading"
    :aria-label="ariaLabel"
    :aria-busy="loading ? 'true' : undefined"
    :style="block ? 'width: 100%' : undefined"
  >
    <Loader2 v-if="loading" :size="15" class="spin" aria-hidden="true" />
    <slot />
  </button>
</template>

<style scoped>
.btn-block {
  width: 100%;
}

.spin {
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
