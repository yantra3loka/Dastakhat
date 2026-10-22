<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import {
  ArrowRight,
  CheckCircle2,
  Clock,
  FileSignature,
  FileText,
  PenLine,
  Plus,
  Send,
  TriangleAlert,
  Users,
} from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
import DataTable from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import LoadingSpinner from '@/components/LoadingSpinner.vue'
import StatCard from '@/components/StatCard.vue'
import StatusChip from '@/components/StatusChip.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import { useEnvelopeStore } from '@/stores/envelopes'
import { formatDateIst } from '@/utils/format'
import type { DataTableColumn } from '@/types/ui'

const store = useEnvelopeStore()
const { stats, items, loading } = storeToRefs(store)

const columns: DataTableColumn[] = [
  { key: 'title', label: 'Name' },
  { key: 'status', label: 'Status', width: '130px' },
  { key: 'recipients', label: 'Recipients', width: '190px' },
  { key: 'createdAt', label: 'Created on', width: '140px' },
]

const steps = [
  { icon: FileText, title: 'Upload a PDF', text: 'Start with the document you want to get signed.' },
  { icon: Users, title: 'Add the recipients', text: 'Name, e-mail address and the signing method.' },
  { icon: PenLine, title: 'Place the fields', text: 'Drop the signature, date and text fields onto the pages.' },
  { icon: Send, title: 'Send it out', text: 'Every signer receives a single use link.' },
]

const recent = computed(() => items.value.slice(0, 5))

onMounted(async () => {
  await Promise.all([store.fetchStats(), store.fetchList()])
})
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div class="page-title-group">
        <h1>Dashboard</h1>
        <p class="page-subtitle">Everything that is going on with your envelopes.</p>
      </div>
      <BaseButton :to="{ name: 'documents-new' }">
        <Plus :size="15" aria-hidden="true" />
        New
      </BaseButton>
    </header>

    <section class="stats" aria-label="Overview of the envelopes">
      <StatCard label="Total" :value="stats?.total ?? 0" :icon="FileSignature" tone="blue" />
      <StatCard
        label="Action needed"
        :value="stats?.actionNeeded ?? 0"
        :icon="TriangleAlert"
        tone="amber"
        hint="Waiting for you"
      />
      <StatCard label="Pending" :value="stats?.waiting ?? 0" :icon="Clock" tone="violet" hint="Waiting for a signer" />
      <StatCard label="Completed" :value="stats?.completed ?? 0" :icon="CheckCircle2" tone="green" />
      <StatCard label="Drafts" :value="stats?.draft ?? 0" :icon="PenLine" tone="slate" />
    </section>

    <div class="columns">
      <section class="card recent" aria-labelledby="recent-heading">
        <div class="card-header">
          <h2 id="recent-heading">Recent documents</h2>
          <RouterLink :to="{ name: 'documents' }" class="link">
            View all
            <ArrowRight :size="14" aria-hidden="true" />
          </RouterLink>
        </div>
        <LoadingSpinner v-if="loading && recent.length === 0" label="Loading the documents…" />
        <EmptyState
          v-else-if="recent.length === 0"
          title="No documents yet"
          description="Upload a PDF to send it out for signature."
          :icon="FileText"
        >
          <BaseButton :to="{ name: 'documents-new' }">
            <Plus :size="15" aria-hidden="true" />
            Upload a document
          </BaseButton>
        </EmptyState>
        <DataTable
          v-else
          :columns="columns"
          :rows="recent"
          row-key="id"
          caption="The five most recent envelopes"
        >
          <template #cell-title="{ row }">
            <RouterLink :to="{ name: 'document-detail', params: { id: String(row.id) } }" class="doc-link">
              {{ row.title }}
            </RouterLink>
          </template>
          <template #cell-status="{ row }">
            <StatusChip :status="row.status" />
          </template>
          <template #cell-recipients="{ row }">
            <ProgressBar
              :value="Number(row.signedCount ?? 0) / Math.max(Number(row.recipientCount ?? 0), 1)"
              :label="`${row.signedCount ?? 0} of ${row.recipientCount ?? 0} signed`"
              show-value
            />
          </template>
          <template #cell-createdAt="{ row }">
            {{ formatDateIst(row.createdAt) }}
          </template>
        </DataTable>
      </section>

      <section class="card get-started" aria-labelledby="get-started-heading">
        <div class="card-header">
          <h2 id="get-started-heading">Get started</h2>
        </div>
        <div class="card-body">
          <ol class="steps">
            <li v-for="(step, index) in steps" :key="step.title" class="step">
              <span class="step-icon" aria-hidden="true">
                <component :is="step.icon" :size="16" />
              </span>
              <div class="step-text">
                <p class="step-title">
                  <span class="step-number">{{ index + 1 }}</span>
                  {{ step.title }}
                </p>
                <p class="step-description">{{ step.text }}</p>
              </div>
            </li>
          </ol>
          <BaseButton :to="{ name: 'documents-new' }" block>
            <Plus :size="15" aria-hidden="true" />
            Upload a document
          </BaseButton>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 14px;
}

.columns {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(280px, 1fr);
  gap: 16px;
  align-items: start;
}

.link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 13px;
  font-weight: 550;
}

.doc-link {
  font-weight: 550;
  color: var(--ink-900);
}

.doc-link:hover {
  color: var(--blue-600);
}

.steps {
  list-style: none;
  margin: 0 0 16px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.step {
  display: flex;
  gap: 11px;
  align-items: flex-start;
}

.step-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  border-radius: 9px;
  background: var(--blue-100);
  color: var(--blue-600);
}

.step-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-weight: 600;
  font-size: 13.5px;
  color: var(--ink-900);
}

.step-number {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--navy-700);
  color: #fff;
  font-size: 10.5px;
  font-weight: 650;
}

.step-description {
  font-size: 12.5px;
  color: var(--ink-500);
}

@media (max-width: 1000px) {
  .columns {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
