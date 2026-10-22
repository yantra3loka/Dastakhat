<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { CircleAlert, CircleCheck, Fingerprint, Loader2, Send } from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
import MockBanner from '@/components/MockBanner.vue'
import { esignStart, getSignerView } from '@/api/signer'
import { ApiError } from '@/api/client'

const props = defineProps<{ token: string }>()

const emit = defineEmits<{ finished: []; failed: [message: string] }>()

type Phase = 'idle' | 'starting' | 'waiting' | 'done' | 'error'

const phase = ref<Phase>('idle')
const error = ref<string | null>(null)
const banner = ref<string | null>(null)
const txn = ref<string | null>(null)
const espUrl = ref<string | null>(null)
const pollTimer = ref<number | null>(null)
const attempts = ref(0)

const MAX_ATTEMPTS = 30

function stopPolling(): void {
  if (pollTimer.value !== null) {
    window.clearTimeout(pollTimer.value)
    pollTimer.value = null
  }
}

async function pollForSignature(): Promise<void> {
  if (attempts.value >= MAX_ATTEMPTS) {
    phase.value = 'error'
    error.value =
      'The MOCK eSign provider did not confirm the signature in time. Please reload the page and try again.'
    emit('failed', error.value)
    return
  }
  attempts.value += 1
  try {
    // While the recipient is still pending the view can be loaded, as soon as the
    // token has been consumed the signature was recorded.
    await getSignerView(props.token)
    pollTimer.value = window.setTimeout(() => void pollForSignature(), 2000)
  } catch (cause) {
    if (cause instanceof ApiError && (cause.status === 404 || cause.status === 410)) {
      phase.value = 'done'
      stopPolling()
      emit('finished')
      return
    }
    if (cause instanceof ApiError && cause.status === 425) {
      phase.value = 'error'
      error.value = cause.message
      stopPolling()
      emit('failed', error.value)
      return
    }
    pollTimer.value = window.setTimeout(() => void pollForSignature(), 2000)
  }
}

async function postToEsp(url: string, xml: string): Promise<void> {
  try {
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/xml' },
      body: xml,
    })
    if (!response.ok) {
      throw new Error(`The MOCK provider answered with status ${response.status}.`)
    }
    return
  } catch {
    // The provider may not allow a cross origin request, in that case the XML
    // is posted with a form into a hidden frame.
    const frame = document.createElement('iframe')
    frame.style.display = 'none'
    frame.name = 'dastakhat-mock-esp'
    document.body.appendChild(frame)
    const form = document.createElement('form')
    form.method = 'post'
    form.action = url
    form.target = frame.name
    form.style.display = 'none'
    const field = document.createElement('textarea')
    field.name = 'responseXml'
    field.value = xml
    form.appendChild(field)
    document.body.appendChild(form)
    form.submit()
    window.setTimeout(() => {
      form.remove()
      frame.remove()
    }, 60_000)
  }
}

async function start(): Promise<void> {
  phase.value = 'starting'
  error.value = null
  try {
    const response = await esignStart(props.token)
    banner.value = response.banner ?? null
    txn.value = response.txn ?? null
    espUrl.value = response.espUrl ?? null
    if (!response.espUrl || !response.requestXml) {
      throw new Error('The server did not return a MOCK eSign request.')
    }
    phase.value = 'waiting'
    await postToEsp(response.espUrl, response.requestXml)
    await pollForSignature()
  } catch (cause) {
    phase.value = 'error'
    error.value = cause instanceof Error ? cause.message : 'The MOCK eSign flow could not be started.'
    emit('failed', error.value)
  }
}

onBeforeUnmount(stopPolling)
</script>

<template>
  <section class="esign-panel" aria-labelledby="esign-heading">
    <h3 id="esign-heading" class="panel-title">
      <Fingerprint :size="16" aria-hidden="true" />
      Aadhaar eSign
    </h3>

    <MockBanner
      :message="
        banner ??
        'MOCK - Aadhaar eSign simulation. It never contacts UIDAI, never asks for an Aadhaar number and has no legal standing.'
      "
    />

    <p v-if="error" class="alert alert-error" role="alert">
      <CircleAlert :size="16" aria-hidden="true" />
      <span>{{ error }}</span>
    </p>

    <div class="row">
      <BaseButton
        :loading="phase === 'starting'"
        :disabled="phase === 'waiting' || phase === 'done'"
        @click="start"
      >
        <Fingerprint :size="15" aria-hidden="true" />
        Sign with Aadhaar eSign (MOCK)
      </BaseButton>
    </div>

    <p v-if="phase === 'waiting'" class="waiting" role="status">
      <Loader2 :size="15" class="spin" aria-hidden="true" />
      <span>
        The MOCK provider is signing the document. Please keep this page open
        <template v-if="txn">(transaction {{ txn }}).</template>
      </span>
    </p>

    <p v-if="phase === 'done'" class="alert alert-success" role="status">
      <CircleCheck :size="16" aria-hidden="true" />
      <span>The document was signed with the MOCK eSign provider.</span>
    </p>

    <p v-if="espUrl" class="text-xs muted">
      <Send :size="12" aria-hidden="true" />
      The request was sent to the MOCK provider at <code>{{ espUrl }}</code
      >.
    </p>
  </section>
</template>

<style scoped>
.esign-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid #fedf89;
  border-radius: var(--radius);
  background: var(--orange-100);
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.waiting {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--orange-700);
}

.spin {
  animation: spin 0.9s linear infinite;
}

code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  word-break: break-all;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
