import { defineStore } from 'pinia'
import { ref } from 'vue'
import { newId } from '@/utils/id'

/** The kind of a toast. */
export type ToastType = 'success' | 'error' | 'info'

/** A short lived notification. */
export interface Toast {
  id: string
  type: ToastType
  message: string
}

/** How long a toast stays on the screen. */
const TOAST_TIMEOUT = 6000

/**
 * The notification queue of the app. The `ToastHost` component renders it.
 */
export const useToastStore = defineStore('toast', () => {
  const toasts = ref<Toast[]>([])

  function dismiss(id: string): void {
    toasts.value = toasts.value.filter((toast) => toast.id !== id)
  }

  function push(type: ToastType, message: string): string {
    const id = newId()
    toasts.value = [...toasts.value, { id, type, message }]
    if (typeof window !== 'undefined') {
      window.setTimeout(() => dismiss(id), TOAST_TIMEOUT)
    }
    return id
  }

  function success(message: string): string {
    return push('success', message)
  }

  function error(message: string): string {
    return push('error', message)
  }

  function info(message: string): string {
    return push('info', message)
  }

  return { toasts, push, success, error, info, dismiss }
})
