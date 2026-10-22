<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { ArrowLeft, BadgeCheck, CircleAlert, History, ShieldCheck } from 'lucide-vue-next'
import AuditTimeline from '@/components/AuditTimeline.vue'
import BaseButton from '@/components/BaseButton.vue'
import LoadingSpinner from '@/components/LoadingSpinner.vue'
import { getAuditEvents, verifyAuditChain } from '@/api/envelopes'
import { useEnvelopeStore } from '@/stores/envelopes'
import { useToastStore } from '@/stores/toast'
import type { AuditEvent, AuditVerification } from '@/api/types'
import { formatDateTimeIst } from '@/utils/format'

const props = defineProps<{ id: string }>()

const store = useEnvelopeStore()
const toast = useToastStore()
const { current } = storeToRefs(store)

const events = ref<AuditEvent[]>([])
const verification = ref<AuditVerification | null>(null)
const loading = ref(true)
const verifying = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    const [envelope, audit] = await Promise.all([
      store.fetchEnvelope(props.id),
      getAuditEvents(props.id),
    ])
    if (!envelope) toast.error(store.error ?? 'The document could not be loaded.')
    events.value = audit
  } catch (cause) {
    toast.error(cause instanceof Error ? cause.message : 'The audit trail could not be loaded.')
  } finally {
    loading.value = false
  }
}

async function verify(): Promise<void> {
  verifying.value = true
  try {
    verification.value = await verifyAuditChain(props.id)
    if (verification.value.intact) {
      toast.success('The audit chain is intact.')
    } else {
      toast.error(`The audit chain is broken at event ${verification.value.brokenAtSeq ?? '?'}.`)
    }
  } catch (cause) {
    toast.error(cause instanceof Error ? cause.message : 'The audit chain could not be verified.')
  } finally {
    verifying.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div class="page-title-group">
        <h1>Audit trail</h1>
        <p class="page-subtitle">
          {{ current?.title ?? 'The envelope' }} - every event is chained with the hash of its predecessor.
        </p>
      </div>
      <div class="row">
        <BaseButton variant="secondary" :to="{ name: 'document-detail', params: { id } }">
          <ArrowLeft :size="15" aria-hidden="true" />
          Back
        </BaseButton>
        <BaseButton :loading="verifying" @click="verify">
          <ShieldCheck :size="15" aria-hidden="true" />
          Verify chain
        </BaseButton>
      </div>
    </header>

    <p
      v-if="verification"
      class="alert"
      :class="verification.intact ? 'alert-success' : 'alert-error'"
      role="alert"
    >
      <BadgeCheck v-if="verification.intact" :size="17" aria-hidden="true" />
      <CircleAlert v-else :size="17" aria-hidden="true" />
      <span v-if="verification.intact">
        Chain intact - {{ verification.eventCount }} events were recomputed successfully. Checked
        {{ formatDateTimeIst(verification.checkedAt) }}.
      </span>
      <span v-else>
        Chain broken at event {{ verification.brokenAtSeq ?? '?' }} of {{ verification.eventCount }}. The head hash is
        {{ verification.headHash }}.
      </span>
    </p>

    <section class="card">
      <div class="card-header">
        <h2><History :size="16" aria-hidden="true" /> Events</h2>
        <span class="muted text-sm">{{ events.length }} event(s)</span>
      </div>
      <div class="card-body">
        <LoadingSpinner v-if="loading" label="Loading the audit trail…" />
        <AuditTimeline v-else :events="events" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.card-header h2 {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
