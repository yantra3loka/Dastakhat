<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CircleAlert, Info, Lock, ShieldCheck } from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
import BaseInput from '@/components/BaseInput.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const email = ref('sender@example.test')
const password = ref('')
const submitted = ref(false)

const emailError = computed(() =>
  submitted.value && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.value.trim())
    ? 'Please enter a valid e-mail address.'
    : null,
)

const passwordError = computed(() =>
  submitted.value && password.value.length === 0 ? 'Please enter the password.' : null,
)

async function onSubmit(): Promise<void> {
  submitted.value = true
  if (emailError.value || passwordError.value) return
  const ok = await auth.login(email.value.trim(), password.value)
  if (!ok) return
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
  await router.push(redirect)
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-brand">
        <span class="login-mark" aria-hidden="true"><ShieldCheck :size="20" /></span>
        <div>
          <h1 class="login-title">Dastakhat</h1>
          <p class="login-subtitle">Self hosted e-signature workspace</p>
        </div>
      </div>

      <form class="login-form" novalidate @submit.prevent="onSubmit">
        <BaseInput
          v-model="email"
          label="E-mail address"
          type="email"
          name="email"
          autocomplete="username"
          placeholder="you@example.com"
          :error="emailError"
          required
        />
        <BaseInput
          v-model="password"
          label="Password"
          type="password"
          name="password"
          autocomplete="current-password"
          placeholder="Your password"
          :error="passwordError"
          required
        />

        <p v-if="auth.error" class="alert alert-error" role="alert">
          <CircleAlert :size="16" aria-hidden="true" />
          <span>{{ auth.error }}</span>
        </p>

        <BaseButton type="submit" :loading="auth.loading" block>
          <Lock :size="15" aria-hidden="true" />
          Sign in
        </BaseButton>
      </form>

      <div class="login-note">
        <Info :size="14" aria-hidden="true" />
        <p>
          <strong>Development account</strong>
          <span class="login-note-body">
            The seeded sender is <code>sender@example.test</code> with the password
            <code>ChangeMe!123</code>. It only exists in a local development setup.
          </span>
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(circle at 12% 12%, rgba(31, 94, 255, 0.28), transparent 42%),
    radial-gradient(circle at 88% 82%, rgba(31, 94, 255, 0.16), transparent 46%),
    var(--navy-800);
}

.login-card {
  width: min(430px, 100%);
  background: var(--surface);
  border-radius: 16px;
  box-shadow: var(--shadow-lg);
  padding: 28px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.login-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 11px;
  background: var(--blue-600);
  color: #fff;
}

.login-title {
  font-size: 20px;
}

.login-subtitle {
  font-size: 12.5px;
  color: var(--ink-500);
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.login-note {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  border-top: 1px dashed var(--line);
  padding-top: 14px;
  font-size: 12px;
  color: var(--ink-500);
}

.login-note-body {
  display: block;
  margin-top: 2px;
}

.login-note code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 0 4px;
  color: var(--ink-700);
}
</style>
