<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CircleAlert, CircleCheck, KeyRound, Laptop, ShieldCheck, Terminal } from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
import BaseInput from '@/components/BaseInput.vue'
import MockBanner from '@/components/MockBanner.vue'
import {
  DscAgentError,
  fetchAgentHealth,
  fetchCertificates,
  signWithToken,
  type DscCertificate,
} from '@/api/dscAgent'
import { dscFinish, dscPrepare } from '@/api/signer'

const props = defineProps<{
  token: string
  documentName: string
  documentSha256: string
}>()

const emit = defineEmits<{ finished: []; failed: [message: string] }>()

type AgentState = 'checking' | 'unavailable' | 'ready' | 'error'

const state = ref<AgentState>('checking')
const certificates = ref<DscCertificate[]>([])
const selectedId = ref<string>('')
const pin = ref('')
const step = ref<'choose' | 'confirm' | 'signing' | 'done'>('choose')
const busy = ref(false)
const error = ref<string | null>(null)
const agentVersion = ref<string | null>(null)

const selected = computed(() => certificates.value.find((entry) => entry.id === selectedId.value) ?? null)

async function checkAgent(): Promise<void> {
  state.value = 'checking'
  error.value = null
  try {
    const health = await fetchAgentHealth()
    agentVersion.value = health.version
    const list = await fetchCertificates()
    certificates.value = list
    selectedId.value = list[0]?.id ?? ''
    state.value = list.length === 0 ? 'ready' : 'ready'
    if (list.length === 0) {
      error.value = 'The token does not contain a usable certificate. Please insert your DSC token.'
    }
  } catch (cause) {
    if (cause instanceof DscAgentError && cause.kind === 'unreachable') {
      state.value = 'unavailable'
      error.value = cause.message
      return
    }
    state.value = 'error'
    error.value = cause instanceof Error ? cause.message : 'The signing agent could not be reached.'
  }
}

async function signWithDsc(): Promise<void> {
  error.value = null
  const certificate = selected.value
  if (!certificate) {
    error.value = 'Please choose a certificate.'
    return
  }
  if (!pin.value) {
    error.value = 'Please enter the PIN of your token.'
    return
  }
  step.value = 'signing'
  busy.value = true
  try {
    const prepared = await dscPrepare(props.token, certificate.pem)
    const result = await signWithToken({
      pin: pin.value,
      certificateId: certificate.id,
      toBeSignedBase64: prepared.toBeSignedBase64,
    })
    const finished = await dscFinish(props.token, String(prepared.sessionId), result.signatureBase64)
    if (finished.validated === false) {
      error.value = 'The signature could not be validated by the server.'
      step.value = 'confirm'
      emit('failed', error.value)
      return
    }
    step.value = 'done'
    emit('finished')
  } catch (cause) {
    if (cause instanceof DscAgentError) {
      error.value = cause.message
    } else if (cause instanceof Error) {
      error.value = cause.message
    } else {
      error.value = 'The DSC signing failed.'
    }
    step.value = 'confirm'
    emit('failed', error.value)
  } finally {
    busy.value = false
  }
}

onMounted(checkAgent)
</script>

<template>
  <section class="dsc-panel" aria-labelledby="dsc-heading">
    <h3 id="dsc-heading" class="panel-title">
      <ShieldCheck :size="16" aria-hidden="true" />
      Sign with a DSC
    </h3>

    <MockBanner
      message="The DSC agent runs on this computer. The certificate that is used here is a development (TEST) certificate."
      title="Local token"
    />

    <div v-if="state === 'checking'" class="panel-state">
      <Terminal :size="16" aria-hidden="true" />
      <span>Checking the local signing agent…</span>
    </div>

    <div v-else-if="state === 'unavailable'" class="agent-missing">
      <div class="alert alert-warning" role="alert">
        <CircleAlert :size="16" aria-hidden="true" />
        <div>
          <p><strong>The signing agent is not running.</strong></p>
          <p class="text-sm">
            The agent is a small helper that talks to the USB token of this computer. Start it and reload this page.
          </p>
        </div>
      </div>
      <div class="setup">
        <p class="setup-title">How to start the agent</p>
        <ol class="setup-steps">
          <li>Insert your DSC token into the USB port of this computer.</li>
          <li>
            Start the agent on
            <code>https://127.0.0.1:51515</code> - the certificate has to be trusted by the operating system.
          </li>
          <li>Open this page again. The agent is detected automatically.</li>
        </ol>
        <BaseButton variant="secondary" size="sm" @click="checkAgent">
          <Laptop :size="15" aria-hidden="true" />
          Try again
        </BaseButton>
      </div>
    </div>

    <div v-else class="panel-body">
      <p v-if="error" class="alert alert-error" role="alert">
        <CircleAlert :size="16" aria-hidden="true" />
        <span>{{ error }}</span>
      </p>

      <fieldset class="certificates">
        <legend class="field-label">Certificate of the token</legend>
        <p v-if="certificates.length === 0" class="text-sm muted">
          No certificate was found. Please insert your token and try again.
        </p>
        <label
          v-for="certificate in certificates"
          :key="certificate.id"
          class="certificate"
          :class="{ 'certificate-selected': certificate.id === selectedId, 'certificate-invalid': certificate.expired }"
        >
          <input v-model="selectedId" type="radio" name="dsc-certificate" :value="certificate.id" />
          <span class="grow">
            <span class="certificate-subject">{{ certificate.subject }}</span>
            <span class="certificate-meta">
              Serial {{ certificate.serialNumber }} · Issuer {{ certificate.issuer }}
            </span>
            <span v-if="certificate.expired" class="certificate-warning">This certificate is expired.</span>
          </span>
        </label>
      </fieldset>

      <div v-if="step === 'choose'" class="stack">
        <BaseInput
          v-model="pin"
          label="PIN of the token"
          type="password"
          autocomplete="off"
          placeholder="Your token PIN"
        />
        <BaseButton
          :disabled="!selected || !pin"
          @click="step = 'confirm'"
        >
          <KeyRound :size="15" aria-hidden="true" />
          Continue
        </BaseButton>
      </div>

      <div v-else-if="step === 'confirm'" class="confirm">
        <div class="confirm-box">
          <p class="confirm-title">Please confirm the signing</p>
          <dl class="confirm-list">
            <div>
              <dt>Document</dt>
              <dd>{{ documentName }}</dd>
            </div>
            <div>
              <dt>SHA-256</dt>
              <dd class="mono">{{ documentSha256 }}</dd>
            </div>
            <div>
              <dt>Certificate</dt>
              <dd>{{ selected?.subject }}</dd>
            </div>
          </dl>
          <p class="text-sm muted">
            The signature is created with your private key inside the token. The private key never leaves the token.
          </p>
        </div>
        <div class="row">
          <BaseButton variant="secondary" @click="step = 'choose'">Back</BaseButton>
          <BaseButton :loading="busy" @click="signWithDsc">
            <ShieldCheck :size="15" aria-hidden="true" />
            Sign with the DSC
          </BaseButton>
        </div>
      </div>

      <div v-else-if="step === 'signing'" class="panel-state">
        <Terminal :size="16" aria-hidden="true" />
        <span>The token is signing the document…</span>
      </div>

      <div v-else class="panel-state panel-success">
        <CircleCheck :size="16" aria-hidden="true" />
        <span>The document was signed with your certificate.</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.dsc-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface-muted);
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.panel-state {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--ink-600);
}

.panel-success {
  color: var(--green-600);
}

.agent-missing {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.setup {
  border: 1px dashed var(--line-strong);
  border-radius: var(--radius);
  padding: 12px 14px;
  background: var(--surface);
}

.setup-title {
  font-size: 12px;
  font-weight: 650;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--ink-500);
  margin-bottom: 6px;
}

.setup-steps {
  margin: 0 0 10px;
  padding-left: 18px;
  font-size: 12.5px;
  color: var(--ink-600);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.setup-steps code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  background: var(--surface-muted);
  border: 1px solid var(--line);
  border-radius: 4px;
  padding: 0 4px;
}

.certificates {
  border: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.certificate {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 9px 11px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  background: var(--surface);
  cursor: pointer;
}

.certificate-selected {
  border-color: var(--blue-500);
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.12);
}

.certificate-invalid {
  border-color: var(--red-600);
}

.certificate-subject {
  display: block;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
}

.certificate-meta {
  display: block;
  font-size: 11.5px;
  color: var(--ink-500);
}

.certificate-warning {
  display: block;
  font-size: 11.5px;
  color: var(--red-700);
}

.stack {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.confirm {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.confirm-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface);
  padding: 12px 14px;
}

.confirm-title {
  font-size: 13px;
  font-weight: 650;
  color: var(--ink-900);
}

.confirm-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
}

.confirm-list div {
  display: flex;
  gap: 10px;
  justify-content: space-between;
}

.confirm-list dt {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--ink-400);
  font-weight: 600;
}

.confirm-list dd {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-800);
  text-align: right;
  overflow-wrap: anywhere;
}
</style>
