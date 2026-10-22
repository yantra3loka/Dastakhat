<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import {
  ArrowLeft,
  CalendarClock,
  CircleAlert,
  Info,
  Send,
  ShieldCheck,
} from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
import BaseSelect from '@/components/BaseSelect.vue'
import BaseInput from '@/components/BaseInput.vue'
import LoadingSpinner from '@/components/LoadingSpinner.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useEnvelopeStore } from '@/stores/envelopes'
import { useToastStore } from '@/stores/toast'
import { updateEnvelope } from '@/api/envelopes'
import { ApiError } from '@/api/client'
import { daysUntil, fieldTypeLabel, formatDateTimeIst, methodLabel } from '@/utils/format'
import type { RoutingMode } from '@/api/types'
import type { SelectOption } from '@/types/ui'

const props = defineProps<{ id: string }>()

const store = useEnvelopeStore()
const toast = useToastStore()
const router = useRouter()
const { current, loading, saving } = storeToRefs(store)

const routing = ref<RoutingMode>('sequential')
const expiryDays = ref<number>(7)
const sendError = ref<string | null>(null)
const busy = ref(false)

const routingOptions: SelectOption[] = [
  { value: 'sequential', label: 'Sequential - one after another' },
  { value: 'parallel', label: 'Parallel - everybody at once' },
]

const recipients = computed(() => current.value?.recipients ?? [])
const fields = computed(() => current.value?.fields ?? [])

const recipientsWithoutSignature = computed(() =>
  recipients.value
    .filter(
      (recipient) =>
        !fields.value.some((field) => field.recipientId === recipient.id && field.type === 'signature'),
    )
    .map((recipient) => recipient.name || recipient.email || 'A recipient'),
)

function fieldsOfRecipient(recipientId: string | undefined) {
  if (!recipientId) return []
  return fields.value.filter((field) => field.recipientId === recipientId)
}

async function saveSettings(): Promise<void> {
  busy.value = true
  try {
    const updated = await updateEnvelope(props.id, {
      routing: routing.value,
      expiresInDays: expiryDays.value,
    })
    current.value = updated
    toast.success('The settings were saved.')
  } catch (cause) {
    toast.error(cause instanceof Error ? cause.message : 'The settings could not be saved.')
  } finally {
    busy.value = false
  }
}

async function send(): Promise<void> {
  sendError.value = null
  busy.value = true
  try {
    const sent = await store.send(props.id)
    if (!sent) {
      sendError.value = store.error ?? 'The envelope could not be sent.'
      return
    }
    toast.success('The envelope was sent. Every recipient received a signing link.')
    await router.push({ name: 'document-detail', params: { id: props.id } })
  } catch (cause) {
    sendError.value =
      cause instanceof ApiError
        ? cause.message
        : 'The envelope could not be sent. Please try again.'
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  const envelope = await store.fetchEnvelope(props.id)
  if (!envelope) {
    toast.error(store.error ?? 'The document could not be loaded.')
    return
  }
  routing.value = envelope.routing ?? 'sequential'
  const remaining = daysUntil(envelope.expiresAt)
  expiryDays.value = remaining && remaining > 0 ? Math.min(remaining, 90) : 7
})
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div class="page-title-group">
        <h1>Review and send</h1>
        <p class="page-subtitle">Check everything once more before the signing links go out.</p>
      </div>
      <BaseButton variant="secondary" :to="{ name: 'documents-prepare', params: { id } }">
        <ArrowLeft :size="15" aria-hidden="true" />
        Back to the editor
      </BaseButton>
    </header>

    <LoadingSpinner v-if="loading" label="Loading the envelope…" />

    <template v-else-if="current">
      <section class="card">
        <div class="card-header">
          <h2>Document</h2>
          <StatusChip :status="current.status" />
        </div>
        <div class="card-body">
          <dl class="info-grid">
            <div>
              <dt>Name</dt>
              <dd>{{ current.title }}</dd>
            </div>
            <div>
              <dt>File</dt>
              <dd>{{ current.document?.filename }}</dd>
            </div>
            <div>
              <dt>Pages</dt>
              <dd>{{ current.pages ?? current.document?.pages ?? '-' }}</dd>
            </div>
            <div>
              <dt>Created</dt>
              <dd>{{ formatDateTimeIst(current.createdAt) }}</dd>
            </div>
            <div>
              <dt>SHA-256 of the original</dt>
              <dd class="mono">{{ current.originalSha256 ?? '-' }}</dd>
            </div>
          </dl>
        </div>
      </section>

      <div class="columns">
        <section class="card">
          <div class="card-header">
            <h2>Recipients and fields</h2>
          </div>
          <ul class="recipient-cards">
            <li v-for="(recipient, index) in recipients" :key="recipient.id" class="recipient-card">
              <div class="recipient-card-head">
                <span class="recipient-order">{{ index + 1 }}</span>
                <div class="grow">
                  <p class="recipient-name">{{ recipient.name }}</p>
                  <p class="recipient-mail">{{ recipient.email }}</p>
                </div>
                <span class="method-chip" :class="`method-${recipient.method}`">
                  {{ methodLabel(recipient.method) }}
                </span>
              </div>
              <div v-if="fieldsOfRecipient(recipient.id).length" class="field-chips">
                <span
                  v-for="field in fieldsOfRecipient(recipient.id)"
                  :key="field.id"
                  class="field-chip"
                >
                  {{ fieldTypeLabel(field.type) }}
                  <span class="field-page">p. {{ field.page }}</span>
                  <span v-if="!field.required" class="field-optional">optional</span>
                </span>
              </div>
              <p v-else class="no-fields">No fields have been placed for this recipient.</p>
              <p
                v-if="!fieldsOfRecipient(recipient.id).some((field) => field.type === 'signature')"
                class="alert alert-warning"
                role="alert"
              >
                <CircleAlert :size="15" aria-hidden="true" />
                <span>This recipient has no signature field. The envelope cannot be sent yet.</span>
              </p>
            </li>
          </ul>
          <p v-if="recipients.length === 0" class="empty-note">
            There are no recipients yet. Go back to the editor and add them.
          </p>
        </section>

        <section class="card">
          <div class="card-header">
            <h2>Settings</h2>
          </div>
          <div class="card-body stack">
            <BaseSelect
              v-model="routing"
              label="Routing"
              :options="routingOptions"
              hint="In the sequential mode everybody receives the link after the previous signer has finished."
            />

            <BaseInput
              :model-value="expiryDays"
              label="Link expires after (days)"
              type="number"
              :min="1"
              :max="90"
              hint="Between 1 and 90 days."
              @update:model-value="expiryDays = Number($event)"
            />

            <p class="expiry-hint">
              <CalendarClock :size="14" aria-hidden="true" />
              <span>Currently valid until {{ formatDateTimeIst(current.expiresAt) }}.</span>
            </p>

            <BaseButton variant="secondary" :loading="busy" @click="saveSettings">
              <ShieldCheck :size="15" aria-hidden="true" />
              Save the settings
            </BaseButton>
          </div>
        </section>
      </div>

      <section class="card send-card">
        <div class="card-body send-body">
          <div class="send-info">
            <h2>Send for signature</h2>
            <p class="text-sm muted">
              <Info :size="14" aria-hidden="true" />
              Every recipient receives a single use link by e-mail. The envelope stays editable until it is sent.
            </p>
            <p v-if="recipientsWithoutSignature.length" class="alert alert-error" role="alert">
              <CircleAlert :size="15" aria-hidden="true" />
              <span>
                Please place a signature field for
                {{ recipientsWithoutSignature.join(', ') }} before you send the envelope.
              </span>
            </p>
            <p v-else-if="sendError" class="alert alert-error" role="alert">
              <CircleAlert :size="15" aria-hidden="true" />
              <span>{{ sendError }}</span>
            </p>
          </div>
          <BaseButton
            :loading="busy || saving"
            :disabled="recipientsWithoutSignature.length > 0 || recipients.length === 0"
            @click="send"
          >
            <Send :size="15" aria-hidden="true" />
            Send for signature
          </BaseButton>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.columns {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(280px, 1fr);
  gap: 16px;
  align-items: start;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 12px 20px;
  margin: 0;
}

.info-grid dt {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--ink-400);
  font-weight: 600;
  margin-bottom: 2px;
}

.info-grid dd {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink-800);
  overflow-wrap: anywhere;
}

.recipient-cards {
  list-style: none;
  margin: 0;
  padding: 0;
}

.recipient-card {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 15px 18px;
  border-bottom: 1px solid var(--line);
}

.recipient-card:last-child {
  border-bottom: none;
}

.recipient-card-head {
  display: flex;
  align-items: center;
  gap: 11px;
}

.recipient-order {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--navy-700);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  flex: 0 0 auto;
}

.recipient-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-900);
}

.recipient-mail {
  font-size: 12px;
  color: var(--ink-500);
}

.method-chip {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: var(--radius-pill);
  background: var(--slate-100);
  color: var(--slate-700);
  white-space: nowrap;
}

.method-dsc {
  background: var(--violet-100);
  color: var(--violet-700);
}

.method-esign_mock {
  background: var(--orange-100);
  color: var(--orange-700);
}

.field-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.field-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  font-weight: 550;
  padding: 3px 9px;
  border-radius: var(--radius-pill);
  background: var(--surface-muted);
  border: 1px solid var(--line);
  color: var(--ink-700);
}

.field-page {
  color: var(--ink-400);
  font-weight: 500;
}

.field-optional {
  color: var(--ink-400);
  font-weight: 500;
}

.no-fields {
  font-size: 12.5px;
  color: var(--ink-500);
  font-style: italic;
}

.empty-note {
  padding: 16px 18px;
  font-size: 13px;
  color: var(--ink-500);
}

.stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.expiry-hint {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  color: var(--ink-500);
}

.send-card {
  border-color: var(--blue-500);
}

.send-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
}

.send-info {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 260px;
  flex: 1 1 auto;
}

.send-info .text-sm {
  display: flex;
  align-items: center;
  gap: 6px;
}

@media (max-width: 1000px) {
  .columns {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
