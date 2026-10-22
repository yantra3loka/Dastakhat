<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import {
  Ban,
  ChevronLeft,
  ChevronRight,
  Download,
  EllipsisVertical,
  Eye,
  FileText,
  History,
  Plus,
  Search,
} from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
import DataTable from '@/components/DataTable.vue'
import EmptyState from '@/components/EmptyState.vue'
import Modal from '@/components/Modal.vue'
import ProgressBar from '@/components/ProgressBar.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useEnvelopeStore } from '@/stores/envelopes'
import { useToastStore } from '@/stores/toast'
import { downloadEnvelopeDocument } from '@/api/envelopes'
import { formatDateIst } from '@/utils/format'
import type { DataTableColumn } from '@/types/ui'
import type { Envelope, EnvelopeStatus } from '@/api/types'

const store = useEnvelopeStore()
const toast = useToastStore()
const { items, filteredItems, loading, page, totalPages, totalElements, statusFilter } = storeToRefs(store)

const searchInput = ref('')
const menuFor = ref<string | null>(null)
const voidTarget = ref<Envelope | null>(null)
const voidReason = ref('')
const busy = ref(false)

const filters: Array<{ value: 'all' | EnvelopeStatus; label: string }> = [
  { value: 'all', label: 'All' },
  { value: 'draft', label: 'Draft' },
  { value: 'sent', label: 'Waiting' },
  { value: 'completed', label: 'Completed' },
  { value: 'declined', label: 'Declined' },
  { value: 'voided', label: 'Voided' },
  { value: 'expired', label: 'Expired' },
]

const columns: DataTableColumn[] = [
  { key: 'title', label: 'Name' },
  { key: 'status', label: 'Status', width: '130px' },
  { key: 'recipients', label: 'Recipients', width: '200px' },
  { key: 'createdAt', label: 'Created on', width: '140px' },
  { key: 'actions', label: 'Actions', width: '60px', align: 'right' },
]

const pageStart = computed(() => (totalElements.value === 0 ? 0 : page.value * store.size + 1))
const pageEnd = computed(() => Math.min((page.value + 1) * store.size, totalElements.value))

function onSearch(): void {
  store.setSearch(searchInput.value)
}

function clearSearch(): void {
  searchInput.value = ''
  store.setSearch('')
}

async function applyFilter(value: 'all' | EnvelopeStatus): Promise<void> {
  menuFor.value = null
  await store.setFilter(value)
}

function toggleMenu(id: string | undefined): void {
  if (!id) return
  menuFor.value = menuFor.value === id ? null : id
}

/** Closes the row menu when the click happened outside of it. */
function onDocumentClick(event: MouseEvent): void {
  if (menuFor.value === null) return
  const target = event.target
  if (target instanceof Element && target.closest('.row-actions')) return
  menuFor.value = null
}

async function downloadSigned(envelope: Envelope): Promise<void> {
  menuFor.value = null
  busy.value = true
  try {
    const blob = await downloadEnvelopeDocument(String(envelope.id), 'final')
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `${envelope.document?.filename ?? 'document'}-signed.pdf`
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    URL.revokeObjectURL(url)
    toast.success('The signed PDF was downloaded.')
  } catch (cause) {
    toast.error(cause instanceof Error ? cause.message : 'The PDF could not be downloaded.')
  } finally {
    busy.value = false
  }
}

async function confirmVoid(): Promise<void> {
  const target = voidTarget.value
  if (!target?.id) return
  busy.value = true
  const result = await store.voidEnvelope(String(target.id), voidReason.value.trim() || undefined)
  busy.value = false
  voidTarget.value = null
  voidReason.value = ''
  if (result) {
    toast.success('The envelope was voided and all signing links were invalidated.')
    await store.fetchList()
    await store.fetchStats()
  } else {
    toast.error(store.error ?? 'The envelope could not be voided.')
  }
}

onMounted(() => {
  document.addEventListener('click', onDocumentClick)
  void store.fetchList()
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocumentClick)
})
</script>

<template>
  <div class="page">
    <header class="page-header">
      <div class="page-title-group">
        <h1>Documents</h1>
        <p class="page-subtitle">All envelopes you have sent or prepared.</p>
      </div>
      <BaseButton :to="{ name: 'documents-new' }">
        <Plus :size="15" aria-hidden="true" />
        New
      </BaseButton>
    </header>

    <section class="card">
      <div class="toolbar">
        <div class="search">
          <Search :size="16" class="search-icon" aria-hidden="true" />
          <label class="sr-only" for="document-search">Search the documents</label>
          <input
            id="document-search"
            v-model="searchInput"
            class="input search-input"
            type="search"
            placeholder="Search by name or recipient…"
            @input="onSearch"
            @keydown.enter="onSearch"
            @search="onSearch"
          />
          <button v-if="searchInput" type="button" class="search-clear" aria-label="Clear the search" @click="clearSearch">
            ×
          </button>
        </div>

        <div class="filters" role="group" aria-label="Filter by status">
          <button
            v-for="filter in filters"
            :key="filter.value"
            type="button"
            class="filter"
            :class="{ 'filter-active': statusFilter === filter.value }"
            :aria-pressed="statusFilter === filter.value ? 'true' : 'false'"
            @click="applyFilter(filter.value)"
          >
            {{ filter.label }}
          </button>
        </div>
      </div>

      <DataTable
        :columns="columns"
        :rows="filteredItems"
        row-key="id"
        :loading="loading"
        empty-title="No documents here"
        empty-description="Upload a PDF to create your first envelope."
        caption="The envelopes of the sender"
      >
        <template #empty>
          <BaseButton :to="{ name: 'documents-new' }">
            <Plus :size="15" aria-hidden="true" />
            Upload Document
          </BaseButton>
        </template>

        <template #cell-title="{ row }">
          <RouterLink :to="{ name: 'document-detail', params: { id: String(row.id) } }" class="doc-link">
            {{ row.title }}
          </RouterLink>
          <p class="doc-meta">{{ row.document?.filename }}</p>
        </template>

        <template #cell-status="{ row }">
          <StatusChip :status="row.status" />
        </template>

        <template #cell-recipients="{ row }">
          <ProgressBar
            :value="Number(row.signedCount ?? 0) / Math.max(Number(row.recipientCount ?? 0), 1)"
            :label="`${row.signedCount ?? 0} of ${row.recipientCount ?? 0} signed`"
            :complete="row.status === 'completed'"
            show-value
          />
        </template>

        <template #cell-createdAt="{ row }">
          {{ formatDateIst(row.createdAt) }}
        </template>

        <template #cell-actions="{ row }">
          <div class="row-actions">
            <button
              type="button"
              class="btn btn-ghost btn-icon"
              :aria-label="`Actions for ${row.title ?? 'the document'}`"
              :aria-expanded="menuFor === row.id ? 'true' : 'false'"
              @click="toggleMenu(row.id)"
            >
              <EllipsisVertical :size="16" aria-hidden="true" />
            </button>
            <div v-if="menuFor === row.id" class="menu" role="menu">
              <RouterLink
                :to="{ name: 'document-detail', params: { id: String(row.id) } }"
                class="menu-item"
                role="menuitem"
                @click="menuFor = null"
              >
                <Eye :size="15" aria-hidden="true" />
                View
              </RouterLink>
              <button
                v-if="row.status === 'completed'"
                type="button"
                class="menu-item"
                role="menuitem"
                @click="downloadSigned(row)"
              >
                <Download :size="15" aria-hidden="true" />
                Download signed PDF
              </button>
              <RouterLink
                :to="{ name: 'documents-audit', params: { id: String(row.id) } }"
                class="menu-item"
                role="menuitem"
                @click="menuFor = null"
              >
                <History :size="15" aria-hidden="true" />
                Audit trail
              </RouterLink>
              <button
                v-if="row.status !== 'completed' && row.status !== 'voided'"
                type="button"
                class="menu-item menu-item-danger"
                role="menuitem"
                @click="voidTarget = row; menuFor = null"
              >
                <Ban :size="15" aria-hidden="true" />
                Void
              </button>
            </div>
          </div>
        </template>
      </DataTable>

      <footer v-if="totalElements > 0" class="pagination">
        <p class="pagination-info">
          {{ pageStart }}–{{ pageEnd }} of {{ totalElements }}
        </p>
        <div class="pagination-buttons">
          <button
            type="button"
            class="btn btn-secondary btn-sm"
            :disabled="page === 0"
            aria-label="Previous page"
            @click="store.previousPage()"
          >
            <ChevronLeft :size="15" aria-hidden="true" />
            Previous
          </button>
          <span class="pagination-position">Page {{ page + 1 }} of {{ Math.max(totalPages, 1) }}</span>
          <button
            type="button"
            class="btn btn-secondary btn-sm"
            :disabled="page + 1 >= totalPages"
            aria-label="Next page"
            @click="store.nextPage()"
          >
            Next
            <ChevronRight :size="15" aria-hidden="true" />
          </button>
        </div>
      </footer>
    </section>

    <EmptyState
      v-if="!loading && filteredItems.length === 0 && items.length > 0"
      title="Nothing matches the search"
      description="Try a different name or clear the search."
      :icon="FileText"
    >
      <BaseButton variant="secondary" @click="clearSearch">Clear the search</BaseButton>
    </EmptyState>

    <Modal
      :open="voidTarget !== null"
      title="Void this envelope?"
      description="Every signing link of the envelope is invalidated immediately."
      @close="voidTarget = null"
    >
      <div class="stack">
        <p class="text-sm muted">
          The envelope <strong>{{ voidTarget?.title }}</strong> will be set to <em>voided</em>. The recipients
          cannot sign any more.
        </p>
        <div class="field">
          <label class="field-label" for="void-reason">Reason (optional)</label>
          <textarea
            id="void-reason"
            v-model="voidReason"
            class="input"
            rows="3"
            maxlength="500"
            placeholder="Why is the envelope voided?"
          />
        </div>
      </div>
      <template #footer>
        <BaseButton variant="secondary" @click="voidTarget = null">Cancel</BaseButton>
        <BaseButton variant="danger" :loading="busy" @click="confirmVoid">Void the envelope</BaseButton>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
}

.search {
  position: relative;
  flex: 1 1 260px;
  max-width: 380px;
}

.search-icon {
  position: absolute;
  left: 11px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--ink-400);
  pointer-events: none;
}

.search-input {
  padding-left: 34px;
  padding-right: 30px;
}

.search-clear {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  border: none;
  background: transparent;
  color: var(--ink-500);
  font-size: 17px;
  line-height: 1;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 4px;
}

.filters {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}

.filter {
  border: 1px solid transparent;
  background: transparent;
  color: var(--ink-600);
  border-radius: var(--radius-pill);
  padding: 6px 12px;
  font-size: 12.5px;
  font-weight: 550;
  cursor: pointer;
}

.filter:hover {
  background: var(--surface-muted);
}

.filter-active {
  background: var(--blue-100);
  color: var(--blue-700);
  border-color: #cddcff;
}

.doc-link {
  font-weight: 550;
  color: var(--ink-900);
}

.doc-link:hover {
  color: var(--blue-600);
}

.doc-meta {
  font-size: 11.5px;
  color: var(--ink-400);
}

.row-actions {
  position: relative;
  display: flex;
  justify-content: flex-end;
}

.menu {
  position: absolute;
  right: 0;
  top: 34px;
  z-index: 20;
  min-width: 210px;
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow-lg);
  padding: 5px;
  display: flex;
  flex-direction: column;
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 8px 10px;
  border-radius: var(--radius-sm);
  font-size: 13px;
  color: var(--ink-700);
  background: transparent;
  border: none;
  cursor: pointer;
  text-align: left;
  width: 100%;
}

.menu-item:hover {
  background: var(--surface-muted);
  color: var(--ink-900);
  text-decoration: none;
}

.menu-item-danger {
  color: var(--red-700);
}

.menu-item-danger:hover {
  background: var(--red-100);
  color: var(--red-700);
}

.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-top: 1px solid var(--line);
  flex-wrap: wrap;
}

.pagination-info,
.pagination-position {
  font-size: 12.5px;
  color: var(--ink-500);
}

.pagination-buttons {
  display: flex;
  align-items: center;
  gap: 10px;
}

textarea.input {
  resize: vertical;
  font-family: inherit;
}

@media (max-width: 720px) {
  .search {
    max-width: none;
  }
}
</style>
