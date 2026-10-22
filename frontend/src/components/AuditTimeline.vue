<script setup lang="ts">
import { computed } from 'vue'
import { FileDown, FileSignature, ShieldCheck, UserCheck, Users, Eye, Ban, FileUp } from 'lucide-vue-next'
import type { AuditEvent } from '@/api/types'
import { formatDateTimeIst } from '@/utils/format'
import EmptyState from './EmptyState.vue'

const props = withDefaults(
  defineProps<{
    events: AuditEvent[]
    loading?: boolean
  }>(),
  { loading: false },
)

const entries = computed(() => props.events ?? [])

function iconFor(type: string | undefined) {
  switch (type) {
    case 'envelope_created':
      return FileSignature
    case 'document_uploaded':
      return FileUp
    case 'recipients_updated':
    case 'fields_updated':
      return Users
    case 'envelope_sent':
      return UserCheck
    case 'document_viewed':
      return Eye
    case 'signed':
    case 'dsc_signed':
    case 'esign_signed':
      return ShieldCheck
    case 'declined':
    case 'voided':
      return Ban
    case 'downloaded':
      return FileDown
    default:
      return ShieldCheck
  }
}

function labelFor(type: string | undefined): string {
  switch (type) {
    case 'envelope_created':
      return 'Envelope created'
    case 'document_uploaded':
      return 'Document uploaded'
    case 'recipients_updated':
      return 'Recipients updated'
    case 'fields_updated':
      return 'Fields updated'
    case 'envelope_sent':
      return 'Envelope sent'
    case 'document_viewed':
      return 'Document viewed'
    case 'consent_recorded':
      return 'Consent recorded'
    case 'signed':
      return 'Signed'
    case 'declined':
      return 'Declined'
    case 'sealed':
      return 'Document sealed'
    case 'downloaded':
      return 'Downloaded'
    case 'voided':
      return 'Voided'
    case 'dsc_signed':
      return 'Signed with a DSC'
    case 'esign_signed':
      return 'Signed with MOCK eSign'
    default:
      return type ?? 'Event'
  }
}

function payloadEntries(payload: Record<string, unknown> | undefined): Array<[string, string]> {
  if (!payload) return []
  return Object.entries(payload).map(([key, value]) => [
    key,
    typeof value === 'string' ? value : JSON.stringify(value),
  ])
}
</script>

<template>
  <div class="timeline">
    <EmptyState
      v-if="!loading && entries.length === 0"
      title="No audit events yet"
      description="Every step of the envelope is written into an append only audit trail."
    />

    <ol v-else class="timeline-list">
      <li v-for="(event, index) in entries" :key="`${event.seq ?? index}-${event.hash ?? index}`" class="event">
        <span class="event-marker" aria-hidden="true">
          <component :is="iconFor(event.type)" :size="14" />
        </span>
        <div class="event-body">
          <div class="event-head">
            <span class="event-title">{{ labelFor(event.type) }}</span>
            <span class="event-seq">#{{ event.seq ?? index + 1 }}</span>
            <time class="event-time" :datetime="event.at" :title="`${event.at ?? ''} (UTC)`">
              {{ formatDateTimeIst(event.at) }}
            </time>
          </div>

          <dl class="event-grid">
            <div class="event-cell">
              <dt>Actor</dt>
              <dd>{{ event.actor || '-' }}</dd>
            </div>
            <div class="event-cell">
              <dt>IP address</dt>
              <dd>{{ event.ip || '-' }}</dd>
            </div>
            <div class="event-cell">
              <dt>User agent</dt>
              <dd class="event-agent">{{ event.userAgent || '-' }}</dd>
            </div>
            <div class="event-cell">
              <dt>Document hash</dt>
              <dd class="mono">{{ event.docSha256 || '-' }}</dd>
            </div>
            <div class="event-cell">
              <dt>Event hash</dt>
              <dd class="mono">{{ event.hash || '-' }}</dd>
            </div>
            <div class="event-cell">
              <dt>Previous hash</dt>
              <dd class="mono">{{ event.prevHash || '-' }}</dd>
            </div>
          </dl>

          <dl v-if="payloadEntries(event.payload).length" class="event-payload">
            <div v-for="[key, value] in payloadEntries(event.payload)" :key="key" class="event-cell">
              <dt>{{ key }}</dt>
              <dd>{{ value }}</dd>
            </div>
          </dl>
        </div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.timeline-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}

.event {
  display: flex;
  gap: 14px;
  padding: 0 0 18px;
  position: relative;
}

.event:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 13px;
  top: 28px;
  bottom: 0;
  width: 2px;
  background: var(--line);
}

.event-marker {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  border-radius: 50%;
  background: var(--blue-100);
  color: var(--blue-600);
  z-index: 1;
}

.event-body {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.event-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  flex-wrap: wrap;
}

.event-title {
  font-weight: 600;
  color: var(--ink-900);
  font-size: 13.5px;
}

.event-seq {
  font-size: 11.5px;
  color: var(--ink-400);
  font-variant-numeric: tabular-nums;
}

.event-time {
  margin-left: auto;
  font-size: 12px;
  color: var(--ink-600);
}

.event-grid,
.event-payload {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 6px 18px;
  margin: 0;
}

.event-cell {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.event-cell dt {
  font-size: 10.5px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--ink-400);
  font-weight: 600;
}

.event-cell dd {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-700);
  overflow-wrap: anywhere;
}

.event-agent {
  max-height: 3.2em;
  overflow: hidden;
}

.event-payload {
  border-top: 1px dashed var(--line);
  padding-top: 8px;
}
</style>
