<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import {
  BadgeCheck,
  CircleAlert,
  CircleCheck,
  Info,
  ShieldCheck,
  Signature,
  TriangleAlert,
} from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
import DscSigningPanel from '@/components/DscSigningPanel.vue'
import EsignMockPanel from '@/components/EsignMockPanel.vue'
import MockBanner from '@/components/MockBanner.vue'
import Modal from '@/components/Modal.vue'
import PdfViewer from '@/components/PdfViewer.vue'
import SignaturePad from '@/components/SignaturePad.vue'
import { useSignerStore } from '@/stores/signer'
import { fieldTypeLabel, formatDateTimeIst, methodLabel } from '@/utils/format'

const props = defineProps<{ token: string }>()

const store = useSignerStore()
const { view, loading, errorKind, errorMessage, consent, consentRecorded, signatureImage, submitting, completed, declined } =
  storeToRefs(store)

const typedName = ref('')
const padMode = ref<'draw' | 'type'>('draw')
const declineOpen = ref(false)
const declineReason = ref('')
const currentPage = ref(1)
const pageCount = ref(0)
/** Set when a cryptographic flow (DSC or MOCK eSign) recorded the signature. */
const providerSigned = ref(false)

const documentUrl = computed(() => `/api/v1/sign/${encodeURIComponent(props.token)}/document`)
const isCryptographic = computed(() => view.value?.method === 'dsc' || view.value?.method === 'esign_mock')
const legalNotice = computed(
  () =>
    view.value?.legalNotice ??
    'Signing electronically means that you agree that the signature you place on this document is legally binding for the document. An electronic signature captured as a drawn or typed image is NOT a cryptographic digital signature and is not equivalent to a DSC or Aadhaar eSign.',
)
const textFields = computed(() => (view.value?.fields ?? []).filter((field) => field.type === 'text'))
const signatureFields = computed(() =>
  (view.value?.fields ?? []).filter((field) => field.type === 'signature' || field.type === 'initials'),
)
const otherFields = computed(() =>
  (view.value?.fields ?? []).filter((field) => field.type !== 'signature' && field.type !== 'initials'),
)

const missingSignature = computed(() => signatureFields.value.length > 0 && !signatureImage.value)
const canFinish = computed(() => {
  if (!consent.value || submitting.value) return false
  if (isCryptographic.value) return false
  if (missingSignature.value) return false
  return textFields.value.every((field) => (store.values[field.id ?? ''] ?? '').trim().length > 0)
})

const errorTitle = computed(() => {
  switch (errorKind.value) {
    case 'unknown-token':
      return 'This link is not valid'
    case 'gone':
      return 'This link cannot be used any more'
    case 'not-your-turn':
      return 'Please wait a moment'
    default:
      return 'The signing page could not be loaded'
  }
})

const errorText = computed(() => {
  switch (errorKind.value) {
    case 'unknown-token':
      return 'The signing link does not exist. Please ask the sender for a new link.'
    case 'gone':
      return errorMessage.value || 'This signing link has expired, it was already used or the envelope was cancelled.'
    case 'not-your-turn':
      return errorMessage.value || 'It is not your turn yet. You will receive an e-mail as soon as it is.'
    default:
      return errorMessage.value || 'Please try again in a moment.'
  }
})

async function onConsentChange(checked: boolean): Promise<void> {
  consent.value = checked
  if (!checked) return
  const ok = await store.recordConsent()
  if (!ok) {
    consent.value = false
  }
}

async function finish(): Promise<void> {
  const ok = await store.complete()
  if (!ok) return
}
async function decline(): Promise<void> {
  const reason = declineReason.value.trim()
  if (!reason) return
  const ok = await store.decline(reason)
  if (ok) {
    declineOpen.value = false
  }
}

watch(
  () => view.value?.recipientName,
  (name) => {
    if (name && !typedName.value) typedName.value = name
  },
)

onMounted(async () => {
  await store.load(props.token)
  if (store.view?.recipientName) typedName.value = store.view.recipientName
})
</script>

<template>
  <div class="sign-page">
    <header class="sign-header">
      <div class="sign-brand">
        <span class="brand-mark" aria-hidden="true"><ShieldCheck :size="17" /></span>
        <span class="brand-name">Dastakhat</span>
      </div>
      <p v-if="view" class="sign-document">{{ view.envelopeTitle }}</p>
    </header>

    <div v-if="loading" class="sign-state">
      <div class="spinner" aria-hidden="true" />
      <p role="status">Loading the document…</p>
    </div>

    <div v-else-if="errorKind" class="sign-state">
      <div class="state-card">
        <span class="state-icon" aria-hidden="true">
          <CircleAlert v-if="errorKind !== 'not-your-turn'" :size="22" />
          <Info v-else :size="22" />
        </span>
        <h1>{{ errorTitle }}</h1>
        <p>{{ errorText }}</p>
        <p class="text-sm muted">No document is shown on this page.</p>
      </div>
    </div>

    <div v-else-if="declined" class="sign-state">
      <div class="state-card">
        <span class="state-icon state-icon-amber" aria-hidden="true"><TriangleAlert :size="22" /></span>
        <h1>Thank you for the feedback</h1>
        <p>You declined to sign this document. The sender has been informed.</p>
      </div>
    </div>

    <div v-else-if="completed || providerSigned" class="sign-state">
      <div class="state-card">
        <span class="state-icon state-icon-green" aria-hidden="true"><CircleCheck :size="22" /></span>
        <h1>You have signed the document</h1>
        <p>
          <template v-if="store.completedResponse?.signedAt">
            The signature was recorded on {{ formatDateTimeIst(store.completedResponse.signedAt) }}.
          </template>
          <template v-else>
            The signature was recorded{{ isCryptographic ? ' by the signing provider' : '' }}.
          </template>
          You can close this page.
        </p>
        <p v-if="view" class="text-sm muted">
          The signed copy is sent to {{ view.senderEmail }} by e-mail.
        </p>
      </div>
    </div>

    <template v-else-if="view">
      <MockBanner
        v-if="store.isMock"
        message="MOCK - Aadhaar eSign simulation. It never contacts UIDAI and has no legal standing."
      />

      <div class="sign-layout">
        <section class="document-column" aria-label="The document">
          <div class="document-meta">
            <h1>{{ view.envelopeTitle }}</h1>
            <p class="text-sm muted">
              From {{ view.senderName }} ({{ view.senderEmail }}) · {{ view.document?.pages ?? '?' }} page(s) ·
              {{ methodLabel(view.method) }}
            </p>
          </div>
          <div class="document-frame">
            <PdfViewer
              :src="documentUrl"
              :current-page="currentPage"
              @loaded="pageCount = $event"
            >
              <template #overlay="{ page }">
                <div
                  v-for="field in (view.fields ?? []).filter((entry) => entry.page === page)"
                  :key="field.id"
                  class="sign-field-box"
                  :style="{
                    left: `${(field.x ?? 0) * 100}%`,
                    top: `${(field.y ?? 0) * 100}%`,
                    width: `${(field.w ?? 0) * 100}%`,
                    height: `${(field.h ?? 0) * 100}%`,
                  }"
                >
                  <span class="sign-field-tag">{{ fieldTypeLabel(field.type) }}</span>
                </div>
              </template>
            </PdfViewer>
          </div>
        </section>

        <aside class="sign-panel" aria-label="Sign the document">
          <section class="panel-block">
            <h2>Your signature</h2>
            <p class="text-sm muted">Hello {{ view.recipientName }}, please review the document and sign it.</p>
          </section>

          <section v-if="otherFields.length" class="panel-block">
            <h2>Fields</h2>
            <div v-for="field in otherFields" :key="field.id" class="field">
              <label class="field-label" :for="`field-${field.id}`">
                {{ field.label || fieldTypeLabel(field.type) }}
                <span v-if="field.required" class="required" aria-hidden="true">*</span>
              </label>
              <input
                v-if="field.type === 'text'"
                :id="`field-${field.id}`"
                class="input"
                type="text"
                :value="store.values[field.id ?? ''] ?? ''"
                :required="field.required"
                :aria-required="field.required ? 'true' : undefined"
                @input="store.setFieldValue(String(field.id), ($event.target as HTMLInputElement).value)"
              />
              <input
                v-else-if="field.type === 'name'"
                :id="`field-${field.id}`"
                class="input"
                type="text"
                readonly
                :value="store.values[field.id ?? ''] ?? view.recipientName"
              />
              <input
                v-else
                :id="`field-${field.id}`"
                class="input"
                type="text"
                readonly
                :value="store.values[field.id ?? ''] ?? ''"
              />
              <p v-if="field.type === 'date'" class="field-hint">The date is filled in automatically when you sign.</p>
            </div>
          </section>

          <section v-if="signatureFields.length && !isCryptographic" class="panel-block">
            <h2>Signature</h2>
            <SignaturePad
              v-model="signatureImage"
              v-model:mode="padMode"
              v-model:typed-name="typedName"
            />
            <p v-if="missingSignature" class="field-hint">
              Please add a signature before you finish.
            </p>
          </section>

          <DscSigningPanel
            v-if="view.method === 'dsc'"
            :token="token"
            :document-name="view.document?.filename ?? view.envelopeTitle ?? 'document'"
            :document-sha256="view.document?.sha256 ?? ''"
            @finished="providerSigned = true"
            @failed="store.errorMessage = $event"
          />

          <EsignMockPanel
            v-if="view.method === 'esign_mock'"
            :token="token"
            @finished="providerSigned = true"
            @failed="store.errorMessage = $event"
          />

          <section class="panel-block">
            <label class="consent">
              <input
                type="checkbox"
                :checked="consent"
                :disabled="submitting"
                @change="onConsentChange(($event.target as HTMLInputElement).checked)"
              />
              <span>
                I agree to sign electronically. I have read the document and I accept that my electronic signature is
                legally binding for this document.
              </span>
            </label>
            <p v-if="consentRecorded" class="text-xs muted">
              <BadgeCheck :size="13" aria-hidden="true" />
              Your consent has been recorded.
            </p>
          </section>

          <section class="panel-block legal">
            <h2>Legal notice</h2>
            <p>{{ legalNotice }}</p>
          </section>

          <p v-if="store.errorMessage" class="alert alert-error" role="alert">
            <CircleAlert :size="16" aria-hidden="true" />
            <span>{{ store.errorMessage }}</span>
          </p>

          <div class="panel-actions">
            <BaseButton v-if="!isCryptographic" :loading="submitting" :disabled="!canFinish" @click="finish">
              <Signature :size="15" aria-hidden="true" />
              Finish signing
            </BaseButton>
            <BaseButton variant="secondary" :disabled="submitting" @click="declineOpen = true">
              Decline
            </BaseButton>
          </div>
          <p v-if="isCryptographic" class="text-xs muted">
            The signature of this document is added by the signing provider above, not by the button below.
          </p>
        </aside>
      </div>

      <Modal
        :open="declineOpen"
        title="Decline to sign"
        description="The sender is informed about the reason."
        @close="declineOpen = false"
      >
        <div class="field">
          <label class="field-label" for="decline-reason">Reason</label>
          <textarea
            id="decline-reason"
            v-model="declineReason"
            class="input"
            rows="4"
            maxlength="500"
            placeholder="Why do you not sign this document?"
          />
        </div>
        <template #footer>
          <BaseButton variant="secondary" @click="declineOpen = false">Cancel</BaseButton>
          <BaseButton variant="danger" :disabled="!declineReason.trim()" :loading="submitting" @click="decline">
            Decline the signing
          </BaseButton>
        </template>
      </Modal>
    </template>
  </div>
</template>

<style scoped>
.sign-page {
  min-height: 100vh;
  background: var(--canvas);
  display: flex;
  flex-direction: column;
}

.sign-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 22px;
  background: var(--navy-700);
  color: #fff;
  flex-wrap: wrap;
}

.sign-brand {
  display: flex;
  align-items: center;
  gap: 9px;
  color: #fff;
  font-weight: 650;
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--blue-600);
}

.sign-document {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.75);
}

.sign-state {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px 20px;
  text-align: center;
}

.state-card {
  max-width: 520px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  padding: 30px 28px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.state-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: var(--red-100);
  color: var(--red-700);
}

.state-icon-green {
  background: var(--green-100);
  color: var(--green-600);
}

.state-icon-amber {
  background: var(--amber-100);
  color: var(--amber-700);
}

.spinner {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: 2px solid var(--line);
  border-top-color: var(--blue-600);
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.sign-layout {
  flex: 1 1 auto;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 380px;
  gap: 20px;
  padding: 20px 22px 40px;
  align-items: start;
}

.document-column {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}

.document-meta h1 {
  font-size: 18px;
}

.document-frame {
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  padding: 10px;
  max-height: calc(100vh - 190px);
  overflow: auto;
}

.sign-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  position: sticky;
  top: 16px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  padding: 18px;
  max-height: calc(100vh - 40px);
  overflow: auto;
}

.panel-block {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--line);
}

.panel-block:last-of-type {
  border-bottom: none;
  padding-bottom: 0;
}

.panel-block h2 {
  font-size: 14px;
}

.required {
  color: var(--red-600);
}

.consent {
  display: flex;
  gap: 9px;
  align-items: flex-start;
  font-size: 12.5px;
  color: var(--ink-700);
  cursor: pointer;
}

.consent input {
  margin-top: 2px;
  width: 16px;
  height: 16px;
  accent-color: var(--blue-600);
  flex: 0 0 auto;
}

.legal p {
  font-size: 11.5px;
  color: var(--ink-500);
  line-height: 1.55;
}

.panel-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.sign-field-box {
  position: absolute;
  border: 1.5px dashed var(--blue-600);
  background: rgba(31, 94, 255, 0.08);
  border-radius: 3px;
  pointer-events: none;
}

.sign-field-tag {
  position: absolute;
  top: -1px;
  left: -1px;
  background: var(--blue-600);
  color: #fff;
  font-size: 9.5px;
  font-weight: 650;
  padding: 2px 4px;
  border-radius: 3px 0 3px 0;
  white-space: nowrap;
}

textarea.input {
  resize: vertical;
  font-family: inherit;
}

@media (max-width: 1000px) {
  .sign-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .sign-panel {
    position: static;
    max-height: none;
  }
}
</style>
