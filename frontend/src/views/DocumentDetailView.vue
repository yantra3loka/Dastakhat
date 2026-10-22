<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import {
  BadgeCheck,
  CircleAlert,
  Download,
  FileSignature,
  FileText,
  History,
  PenLine,
  ShieldCheck,
} from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
import LoadingSpinner from '@/components/LoadingSpinner.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useEnvelopeStore } from '@/stores/envelopes'
import { useToastStore } from '@/stores/toast'
import { downloadEnvelopeDocument, verifyAuditChain } from '@/api/envelopes'
import type { AuditVerification, Envelope } from '@/api/types'
import { formatDateTimeIst, methodLabel, recipientStatusLabel, shortHash } from '@/utils/format'

const props = defineProps<{ id: string }>()

const store = useEnvelopeStore()
const toast = useToastStore()
const { current, loading } = storeToRefs(store)

const verification = ref<AuditVerification | null>(null)
const verifying = ref(false)
const downloading = ref<string | null>(null)

const recipients = computed(() => current.value?.recipients ?? [])
const isDraft = computed(() => current.value?.status === 'draft')

async function download(kind: 'original' | 'working' | 'final'): Promise<void> {
  downloading.value = kind
  try {
    const blob = await downloadEnvelopeDocument(props.id, kind)
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${current.value?.document?.filename ?? 'document'}-${kind}.pdf`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
    toast.success(`The ${kind} PDF was downloaded.`)
  } catch (cause) {
    toast.error(cause instanceof Error ? cause.message : 'The PDF could not be downloaded.')
  } finally {
    downloading.value = null
  }
}

async function verify(): Promise<void> {
  verifying.value = true
  try {
    verification.value = await verifyAuditChain(props.id)
  } catch (cause) {
    toast.error(cause instanceof Error ? cause.message : 'The audit chain could not be verified.')
  } finally {
    verifying.value = false
  }
}

function signatureKind(envelope: Envelope | null): string {
  const kinds = (envelope?.recipients ?? [])
    .map((recipient) => recipient.signature?.kind)
    .filter((kind): kind is NonNullable<typeof kind> => typeof kind === 'string')
  if (kinds.length === 0) return '-'
  return [...new Set(kinds)].join(', ')
}

onMounted(async () => {
  const envelope = await store.fetchEnvelope(props.id)
  if (!envelope) toast.error(store.error ?? 'The document could not be loaded.')
})
</script>

<template>
  <div class="page">
    <LoadingSpinner v-if="loading && !current" label="Loading the document…" />

    <template v-else-if="current">
      <header class="page-header">
        <div class="page-title-group">
          <div class="title-row">
            <h1>{{ current.title }}</h1>
            <StatusChip :status="current.status" />
          </div>
          <p class="page-subtitle">
            Created {{ formatDateTimeIst(current.createdAt) }} ·
            {{ recipients.length }} recipient(s) ·
            {{ current.signedCount ?? 0 }} signature(s)
          </p>
        </div>
        <div class="row">
          <BaseButton v-if="isDraft" variant="secondary" :to="{ name: 'documents-prepare', params: { id } }">
            <PenLine :size="15" aria-hidden="true" />
            Edit the draft
          </BaseButton>
          <BaseButton variant="secondary" :to="{ name: 'documents-audit', params: { id } }">
            <History :size="15" aria-hidden="true" />
            Audit trail
          </BaseButton>
        </div>
      </header>

      <div class="columns">
        <section class="card">
          <div class="card-header">
            <h2>Recipients</h2>
          </div>
          <ul class="recipients">
            <li v-for="(recipient, index) in recipients" :key="recipient.id" class="recipient">
              <span class="recipient-order">{{ index + 1 }}</span>
              <div class="grow">
                <p class="recipient-name">{{ recipient.name }}</p>
                <p class="recipient-mail">{{ recipient.email }}</p>
              </div>
              <div class="recipient-meta">
                <span class="method-chip" :class="`method-${recipient.method}`">
                  {{ methodLabel(recipient.method) }}
                </span>
                <StatusChip :status="recipient.status ?? 'pending'" :label="recipientStatusLabel(recipient.status)" />
              </div>
              <ol class="timeline">
                <li class="timeline-step" :class="{ 'step-done': recipient.notifiedAt }">
                  <span class="step-dot" aria-hidden="true" />
                  Notified
                  <span class="step-time">{{ formatDateTimeIst(recipient.notifiedAt) }}</span>
                </li>
                <li class="timeline-step" :class="{ 'step-done': recipient.viewedAt }">
                  <span class="step-dot" aria-hidden="true" />
                  Viewed
                  <span class="step-time">{{ formatDateTimeIst(recipient.viewedAt) }}</span>
                </li>
                <li class="timeline-step" :class="{ 'step-done': recipient.signedAt }">
                  <span class="step-dot" aria-hidden="true" />
                  Signed
                  <span class="step-time">{{ formatDateTimeIst(recipient.signedAt) }}</span>
                </li>
              </ol>
            </li>
          </ul>
          <p v-if="recipients.length === 0" class="empty-note">This envelope has no recipients.</p>
        </section>

        <div class="side">
          <section class="card">
            <div class="card-header">
              <h2>Files</h2>
            </div>
            <div class="card-body stack">
              <BaseButton variant="secondary" block :loading="downloading === 'original'" @click="download('original')">
                <FileText :size="15" aria-hidden="true" />
                Download the original
              </BaseButton>
              <BaseButton
                variant="secondary"
                block
                :loading="downloading === 'final'"
                :disabled="!current.finalAvailable"
                @click="download('final')"
              >
                <Download :size="15" aria-hidden="true" />
                Download the signed PDF
              </BaseButton>
              <p v-if="!current.finalAvailable" class="text-xs muted">
                The signed PDF exists as soon as every recipient has signed.
              </p>
            </div>
          </section>

          <section class="card">
            <div class="card-header">
              <h2>Audit chain</h2>
            </div>
            <div class="card-body stack">
              <BaseButton variant="secondary" block :loading="verifying" @click="verify">
                <ShieldCheck :size="15" aria-hidden="true" />
                Verify audit chain
              </BaseButton>
              <p
                v-if="verification"
                class="alert"
                :class="verification.intact ? 'alert-success' : 'alert-error'"
                role="alert"
              >
                <BadgeCheck v-if="verification.intact" :size="16" aria-hidden="true" />
                <CircleAlert v-else :size="16" aria-hidden="true" />
                <span v-if="verification.intact">
                  Chain intact - {{ verification.eventCount }} events, checked {{ formatDateTimeIst(verification.checkedAt) }}.
                </span>
                <span v-else>
                  Chain broken at event {{ verification.brokenAtSeq ?? '?' }} of {{ verification.eventCount }}.
                </span>
              </p>
            </div>
          </section>
        </div>
      </div>

      <section class="card">
        <div class="card-header">
          <h2>Certificate of completion</h2>
          <FileSignature :size="17" aria-hidden="true" />
        </div>
        <div class="card-body">
          <dl class="certificate">
            <div>
              <dt>Document</dt>
              <dd>{{ current.title }}</dd>
            </div>
            <div>
              <dt>File</dt>
              <dd>{{ current.document?.filename }}</dd>
            </div>
            <div>
              <dt>Pages</dt>
              <dd>{{ current.pages ?? '-' }}</dd>
            </div>
            <div>
              <dt>Created</dt>
              <dd>{{ formatDateTimeIst(current.createdAt) }}</dd>
            </div>
            <div>
              <dt>Completed</dt>
              <dd>{{ formatDateTimeIst(current.completedAt) }}</dd>
            </div>
            <div>
              <dt>Last activity</dt>
              <dd>{{ formatDateTimeIst(current.lastActivityAt) }}</dd>
            </div>
            <div>
              <dt>Routing</dt>
              <dd>{{ current.routing }}</dd>
            </div>
            <div>
              <dt>Signature kind</dt>
              <dd>{{ signatureKind(current) }}</dd>
            </div>
            <div class="wide">
              <dt>SHA-256 of the original</dt>
              <dd class="mono">{{ current.originalSha256 ?? '-' }}</dd>
            </div>
            <div class="wide">
              <dt>SHA-256 of the signed document</dt>
              <dd class="mono">{{ current.finalSha256 ?? '-' }}</dd>
            </div>
            <div class="wide">
              <dt>SHA-256 before the seal</dt>
              <dd class="mono">{{ current.preSealSha256 ?? '-' }}</dd>
            </div>
            <div class="wide">
              <dt>Head of the audit chain</dt>
              <dd class="mono">{{ shortHash(verification?.headHash ?? '-', 24) }}</dd>
            </div>
          </dl>
          <p class="text-xs muted note">
            The completion data is derived from the envelope and its audit trail. A drawn or typed signature is not a
            cryptographic digital signature.
          </p>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.columns {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(280px, 1fr);
  gap: 16px;
  align-items: start;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.recipients {
  list-style: none;
  margin: 0;
  padding: 0;
}

.recipient {
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
  padding: 14px 18px;
  border-bottom: 1px solid var(--line);
}

.recipient:last-child {
  border-bottom: none;
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

.recipient-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
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

.timeline {
  grid-column: 2 / -1;
  list-style: none;
  margin: 6px 0 0;
  padding: 0;
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
}

.timeline-step {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  color: var(--ink-400);
}

.step-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--line-strong);
}

.step-done {
  color: var(--ink-700);
}

.step-done .step-dot {
  background: var(--green-600);
}

.step-time {
  color: var(--ink-400);
}

.empty-note {
  padding: 16px 18px;
  font-size: 13px;
  color: var(--ink-500);
}

.stack {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.certificate {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 12px 20px;
  margin: 0;
}

.certificate .wide {
  grid-column: 1 / -1;
}

.certificate dt {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--ink-400);
  font-weight: 600;
  margin-bottom: 2px;
}

.certificate dd {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink-800);
  overflow-wrap: anywhere;
}

.note {
  margin-top: 14px;
}

@media (max-width: 1000px) {
  .columns {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 720px) {
  .recipient {
    grid-template-columns: 24px minmax(0, 1fr);
  }

  .recipient-meta {
    grid-column: 2 / -1;
  }
}
</style>
