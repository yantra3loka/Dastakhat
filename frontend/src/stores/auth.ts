import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as authApi from '@/api/auth'
import { ApiError, setCsrfToken } from '@/api/client'
import type { User } from '@/api/types'

/**
 * The session of the sender.
 *
 * The session cookie itself is HttpOnly and therefore handled by the browser.
 * The store keeps the user and the CSRF token that the login call returned.
 */
export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const csrfToken = ref<string | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  const isAuthenticated = computed(() => user.value !== null)
  const displayName = computed(() => user.value?.name || user.value?.email || 'Sender')

  async function login(email: string, password: string): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      const response = await authApi.login(email, password)
      user.value = response.user ?? { email }
      csrfToken.value = response.csrfToken ?? null
      setCsrfToken(csrfToken.value)
      return true
    } catch (cause) {
      if (cause instanceof ApiError) {
        error.value = cause.status === 401 ? 'The e-mail address or the password is wrong.' : cause.message
      } else {
        error.value = 'The login failed. Please try again.'
      }
      return false
    } finally {
      loading.value = false
    }
  }

  /** Restores the session after a page reload. */
  async function restore(): Promise<boolean> {
    try {
      const current = await authApi.me()
      user.value = current && current.email ? current : null
      return user.value !== null
    } catch {
      user.value = null
      return false
    }
  }

  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } catch {
      // The session is dropped locally even when the server call fails.
    }
    user.value = null
    csrfToken.value = null
    setCsrfToken(null)
  }

  /** Drops the session locally, without calling the backend. */
  function clearSession(): void {
    user.value = null
    csrfToken.value = null
    setCsrfToken(null)
  }

  function clearError(): void {
    error.value = null
  }

  return {
    user,
    csrfToken,
    loading,
    error,
    isAuthenticated,
    displayName,
    login,
    restore,
    logout,
    clearSession,
    clearError,
  }
})
