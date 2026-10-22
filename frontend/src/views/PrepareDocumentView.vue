<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import {
  ArrowDown,
  ArrowLeft,
  ArrowUp,
  Calendar,
  Check,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  PenLine,
  PenTool,
  Save,
  Send,
  Trash2,
  Type,
  User,
  ZoomIn,
  ZoomOut,
} from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
import BaseSelect from '@/components/BaseSelect.vue'
import BaseInput from '@/components/BaseInput.vue'
import FieldOverlay from '@/components/FieldOverlay.vue'
import LoadingSpinner from '@/components/LoadingSpinner.vue'
import PdfViewer from '@/components/PdfViewer.vue'
import ToggleSwitch from '@/components/ToggleSwitch.vue'
import { useEnvelopeStore } from '@/stores/envelopes'
import { useToastStore } from '@/stores/toast'
import { defaultFieldSize, type FractionBox } from '@/utils/coordinates'
import { fieldTypeLabel } from '@/utils/format'
import type { FieldType, SigningMethod } from '@/api/types'
import type { SelectOption } from '@/types/ui'

const props = defineProps<{ id: string }>()

const store = useEnvelopeStore()
const toast = useToastStore()
const router = useRouter()
const { recipients, fields, selectedFieldId, selectedRecipientId, loading, saving } = storeToRefs(store)

const zoom = ref(1)
const currentPage = ref(1)
const pageCount = ref(0)
const activePalette = ref<FieldType | null>(null)
const documentUrl = computed(() => `/api/v1/envelopes/${props.id}/documents/original`)

const DEFAULT_COLOUR = '#1f5eff'
const RECIPIENT_COLOURS = [DEFAULT_COLOUR, '#0f9d58', '#d97706', '#7c3aed', '#db2777', '#0891b2', '#be123c', '#4d7c0f']

const palette: Array<{ type: FieldType; label: string; icon: typeof PenTool }> = [
  { type: 'signature', label: 'Signature', icon: PenTool },
  { type: 'initials', label: 'Initials', icon: PenLine },
  { type: 'date', label: 'Date', icon: Calendar },
  { type: 'text', label: 'Text', icon: Type },
  { type: 'name', label: 'Name', icon: User },
]

const methodOptions: SelectOption[] = [
  { value: 'electronic', label: 'Electronic (drawn or typed)' },
  { value: 'dsc', label: 'DSC - digital signature certificate' },
  { value: 'esign_mock', label: 'Aadhaar eSign (MOCK)' },
]

const selectedField = computed(() => fields.value.find((field) => field.id === selectedFieldId.value) ?? null)
const selectedRecipient = computed(
  () => recipients.value.find((recipient) => recipient.id === selectedRecipientId.value) ?? null,
)

function colourOf(recipientId: string): string {
  const index = recipients.value.findIndex((recipient) => recipient.id === recipientId)
  const position = index < 0 ? 0 : index
  return RECIPIENT_COLOURS[position % RECIPIENT_COLOURS.length] ?? DEFAULT_COLOUR
}

function fieldsOnPage(page: number) {
  return fields.value.filter((field) => field.page === page)
}

function activatePalette(type: FieldType): void {
  activePalette.value = activePalette.value === type ? null : type
  if (activePalette.value && !selectedRecipientId.value && recipients.value.length > 0) {
    selectedRecipientId.value = recipients.value[0]?.id ?? null
  }
}

function onPageClick(payload: { page: number; x: number; y: number }): void {
  const type = activePalette.value
  if (!type) return
  const recipientId = selectedRecipientId.value
  if (!recipientId) {
    toast.error('Please add a recipient before you place a field.')
    return
  }
  const size = defaultFieldSize(type)
  const box: FractionBox = { x: payload.x, y: payload.y, w: size.w, h: size.h }
  store.addField({
    recipientId,
    type,
    page: payload.page,
    x: Math.min(box.x, 1 - box.w),
    y: Math.min(box.y, 1 - box.h),
    w: box.w,
    h: box.h,
    required: true,
    label: fieldTypeLabel(type),
  })
  activePalette.value = null
}

function updateFieldBox(fieldId: string, patch: Partial<FractionBox>): void {
  store.updateField(fieldId, patch)
}

function selectField(fieldId: string): void {
  store.selectField(fieldId)
}

function removeField(fieldId: string): void {
  store.removeField(fieldId)
}

function zoomIn(): void {
  zoom.value = Math.min(2.5, Number((zoom.value + 0.25).toFixed(2)))
}

function zoomOut(): void {
  zoom.value = Math.max(0.5, Number((zoom.value - 0.25).toFixed(2)))
}

function goToPage(page: number): void {
  currentPage.value = Math.min(Math.max(page, 1), Math.max(pageCount.value, 1))
}

async function saveDraft(): Promise<void> {
  const saved = await store.saveDraft(props.id)
  if (saved) toast.success('The draft was saved.')
  else toast.error(store.error ?? 'The draft could not be saved.')
}

async function reviewAndSend(): Promise<void> {
  const saved = await store.saveDraft(props.id)
  if (!saved) {
    toast.error(store.error ?? 'The draft could not be saved.')
    return
  }
  await router.push({ name: 'documents-review', params: { id: props.id } })
}

onMounted(async () => {
  const envelope = await store.fetchEnvelope(props.id)
  if (!envelope) {
    toast.error(store.error ?? 'The document could not be loaded.')
    return
  }
  store.loadEditor(envelope)
})
</script>

<template>
  <div class="editor">
    <aside class="pane pane-left" aria-label="Recipients and fields">
      <section class="pane-section">
        <div class="pane-header">
          <h2>Recipients</h2>
          <button type="button" class="btn btn-secondary btn-sm" @click="store.addRecipient()">
            Add
          </button>
        </div>

        <p v-if="recipients.length === 0" class="pane-hint">
          No recipients yet. Add everyone who has to sign the document.
        </p>

        <ul class="recipient-list">
          <li
            v-for="(recipient, index) in recipients"
            :key="recipient.id"
            class="recipient"
            :class="{ 'recipient-active': recipient.id === selectedRecipientId }"
          >
            <button
              type="button"
              class="recipient-head"
              :style="{ borderLeftColor: colourOf(recipient.id) }"
              :aria-pressed="recipient.id === selectedRecipientId ? 'true' : 'false'"
              @click="selectedRecipientId = recipient.id"
            >
              <span class="recipient-index" :style="{ backgroundColor: colourOf(recipient.id) }">{{ index + 1 }}</span>
              <span class="recipient-summary">
                <span class="recipient-name">{{ recipient.name || 'Unnamed recipient' }}</span>
                <span class="recipient-mail">{{ recipient.email || 'No e-mail address' }}</span>
              </span>
              <span class="recipient-count">{{ store.fieldsOfRecipient(recipient.id).length }} fields</span>
            </button>

            <div class="recipient-body">
              <BaseInput
                :model-value="recipient.name"
                label="Name"
                :id="`recipient-name-${recipient.id}`"
                :disabled="saving"
                @update:model-value="store.updateRecipient(recipient.id, { name: $event })"
              />
              <BaseInput
                :model-value="recipient.email"
                label="E-mail address"
                type="email"
                :id="`recipient-email-${recipient.id}`"
                :disabled="saving"
                @update:model-value="store.updateRecipient(recipient.id, { email: $event })"
              />
              <BaseSelect
                :model-value="recipient.method"
                label="Signing method"
                :options="methodOptions"
                :id="`recipient-method-${recipient.id}`"
                :disabled="saving"
                @update:model-value="store.updateRecipient(recipient.id, { method: $event as SigningMethod })"
              />
              <div class="recipient-actions">
                <button
                  type="button"
                  class="btn btn-ghost btn-icon"
                  :aria-label="`Move ${recipient.name || 'the recipient'} up`"
                  :disabled="index === 0"
                  @click="store.moveRecipient(recipient.id, -1)"
                >
                  <ArrowUp :size="15" aria-hidden="true" />
                </button>
                <button
                  type="button"
                  class="btn btn-ghost btn-icon"
                  :aria-label="`Move ${recipient.name || 'the recipient'} down`"
                  :disabled="index === recipients.length - 1"
                  @click="store.moveRecipient(recipient.id, 1)"
                >
                  <ArrowDown :size="15" aria-hidden="true" />
                </button>
                <button
                  type="button"
                  class="btn btn-ghost btn-icon danger"
                  :aria-label="`Remove ${recipient.name || 'the recipient'}`"
                  @click="store.removeRecipient(recipient.id)"
                >
                  <Trash2 :size="15" aria-hidden="true" />
                </button>
              </div>
            </div>
          </li>
        </ul>
      </section>

      <section class="pane-section">
        <div class="pane-header">
          <h2>Fields</h2>
        </div>
        <p class="pane-hint">
          Choose a field and then click the place in the document where it belongs.
        </p>
        <div class="palette">
          <button
            v-for="entry in palette"
            :key="entry.type"
            type="button"
            class="palette-button"
            :class="{ 'palette-active': activePalette === entry.type }"
            :aria-pressed="activePalette === entry.type ? 'true' : 'false'"
            @click="activatePalette(entry.type)"
          >
            <component :is="entry.icon" :size="15" aria-hidden="true" />
            {{ entry.label }}
          </button>
        </div>
      </section>
    </aside>

    <section class="pane pane-center" aria-label="Document">
      <div class="toolbar">
        <div class="toolbar-group">
          <button type="button" class="btn btn-ghost btn-icon" aria-label="Zoom out" @click="zoomOut">
            <ZoomOut :size="16" aria-hidden="true" />
          </button>
          <span class="zoom-label">{{ Math.round(zoom * 100) }}%</span>
          <button type="button" class="btn btn-ghost btn-icon" aria-label="Zoom in" @click="zoomIn">
            <ZoomIn :size="16" aria-hidden="true" />
          </button>
        </div>

        <div class="toolbar-group">
          <button
            type="button"
            class="btn btn-ghost btn-icon"
            aria-label="Previous page"
            :disabled="currentPage <= 1"
            @click="goToPage(currentPage - 1)"
          >
            <ChevronLeft :size="16" aria-hidden="true" />
          </button>
          <label class="sr-only" for="page-number">Page</label>
          <input
            id="page-number"
            class="input page-input"
            type="number"
            min="1"
            :max="Math.max(pageCount, 1)"
            :value="currentPage"
            @change="goToPage(Number(($event.target as HTMLInputElement).value))"
          />
          <span class="page-total">of {{ pageCount || '?' }}</span>
          <button
            type="button"
            class="btn btn-ghost btn-icon"
            aria-label="Next page"
            :disabled="currentPage >= pageCount"
            @click="goToPage(currentPage + 1)"
          >
            <ChevronRight :size="16" aria-hidden="true" />
          </button>
        </div>

        <div class="toolbar-group toolbar-end">
          <BaseButton variant="secondary" size="sm" :loading="saving" @click="saveDraft">
            <Save :size="15" aria-hidden="true" />
            Save draft
          </BaseButton>
          <BaseButton size="sm" :loading="saving" @click="reviewAndSend">
            Review &amp; send
            <Send :size="15" aria-hidden="true" />
          </BaseButton>
        </div>
      </div>

      <p v-if="activePalette" class="placement-hint" role="status">
        Click a place in the document to place the
        <strong>{{ fieldTypeLabel(activePalette) }}</strong> field for
        <strong>{{ selectedRecipient?.name || 'the selected recipient' }}</strong>.
      </p>

      <div v-if="loading" class="editor-loading">
        <LoadingSpinner label="Loading the document…" />
      </div>
      <div v-else class="viewer-scroll">
        <PdfViewer
          :src="documentUrl"
          :zoom="zoom"
          :current-page="currentPage"
          interactive
          @loaded="pageCount = $event"
          @page-click="onPageClick"
        >
          <template #overlay="{ page }">
            <FieldOverlay
              v-for="field in fieldsOnPage(page)"
              :key="field.id"
              :field="field"
              :color="colourOf(field.recipientId)"
              :selected="field.id === selectedFieldId"
              :label="field.label || fieldTypeLabel(field.type)"
              @update="updateFieldBox(field.id, $event)"
              @select="selectField(field.id)"
              @remove="removeField(field.id)"
            />
          </template>
        </PdfViewer>
      </div>
    </section>

    <aside class="pane pane-right" aria-label="Properties of the selected field">
      <div class="pane-header">
        <h2>Properties</h2>
      </div>

      <div v-if="!selectedField" class="pane-hint">
        Select a field in the document to change its recipient, its label or whether it is required.
      </div>

      <div v-else class="stack">
        <p class="field-type">
          <span class="field-type-dot" :style="{ backgroundColor: colourOf(selectedField.recipientId) }" />
          {{ fieldTypeLabel(selectedField.type) }}
        </p>

        <BaseSelect
          :model-value="selectedField.recipientId"
          label="Recipient"
          :options="recipients.map((recipient) => ({ value: recipient.id, label: recipient.name || recipient.email || 'Recipient' }))"
          @update:model-value="store.updateField(selectedField.id, { recipientId: $event })"
        />

        <BaseInput
          :model-value="selectedField.label"
          label="Label"
          placeholder="For example Signature of the customer"
          @update:model-value="store.updateField(selectedField.id, { label: $event })"
        />

        <ToggleSwitch
          :model-value="selectedField.required"
          label="Required field"
          description="The signer has to fill in this field."
          @update:model-value="store.updateField(selectedField.id, { required: $event })"
        />

        <dl class="position-grid">
          <div>
            <dt>X</dt>
            <dd>{{ (selectedField.x * 100).toFixed(1) }}%</dd>
          </div>
          <div>
            <dt>Y</dt>
            <dd>{{ (selectedField.y * 100).toFixed(1) }}%</dd>
          </div>
          <div>
            <dt>Width</dt>
            <dd>{{ (selectedField.w * 100).toFixed(1) }}%</dd>
          </div>
          <div>
            <dt>Height</dt>
            <dd>{{ (selectedField.h * 100).toFixed(1) }}%</dd>
          </div>
          <div>
            <dt>Page</dt>
            <dd>{{ selectedField.page }}</dd>
          </div>
        </dl>

        <BaseButton variant="danger" size="sm" @click="removeField(selectedField.id)">
          <Trash2 :size="15" aria-hidden="true" />
          Delete the field
        </BaseButton>
      </div>

      <section class="summary">
        <h3>Summary</h3>
        <ul class="summary-list">
          <li v-for="recipient in recipients" :key="recipient.id">
            <span class="summary-dot" :style="{ backgroundColor: colourOf(recipient.id) }" />
            <span class="grow">{{ recipient.name || recipient.email || 'Recipient' }}</span>
            <span class="muted text-sm">
              {{ store.signatureFieldsOfRecipient(recipient.id).length }} signature ·
              {{ store.fieldsOfRecipient(recipient.id).length }} fields
            </span>
          </li>
        </ul>
        <p v-if="recipients.length === 0" class="pane-hint">No recipients yet.</p>
      </section>

      <div class="pane-footer">
        <BaseButton variant="secondary" block size="sm" :to="{ name: 'documents', }">
          <ArrowLeft :size="15" aria-hidden="true" />
          Back to the documents
        </BaseButton>
        <p v-if="store.error" class="alert alert-error" role="alert">
          <CircleAlert :size="15" aria-hidden="true" />
          <span>{{ store.error }}</span>
        </p>
        <p v-else class="pane-hint">
          <Check :size="13" aria-hidden="true" />
          The positions are stored as fractions of the page, so they survive a zoom or a resize.
        </p>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.editor {
  display: grid;
  grid-template-columns: 290px minmax(0, 1fr) 290px;
  gap: 0;
  height: calc(100vh - var(--topbar-height));
  background: var(--canvas);
}

.pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  overflow-y: auto;
  background: var(--surface);
}

.pane-left {
  border-right: 1px solid var(--line);
}

.pane-right {
  border-left: 1px solid var(--line);
}

.pane-center {
  background: var(--canvas);
}

.pane-section {
  border-bottom: 1px solid var(--line);
  padding: 14px;
}

.pane-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 14px 8px;
}

.pane-section .pane-header {
  padding: 0 0 8px;
}

.pane-hint {
  font-size: 12px;
  color: var(--ink-500);
  padding: 0 14px 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.pane-section .pane-hint {
  padding: 0 0 10px;
}

.recipient-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.recipient {
  border: 1px solid var(--line);
  border-radius: var(--radius);
  overflow: hidden;
  background: var(--surface);
}

.recipient-active {
  border-color: var(--blue-500);
  box-shadow: 0 0 0 2px rgba(37, 99, 235, 0.12);
}

.recipient-head {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 9px 10px;
  background: var(--surface-muted);
  border: none;
  border-left: 3px solid transparent;
  cursor: pointer;
  text-align: left;
}

.recipient-index {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  color: #fff;
  font-size: 11px;
  font-weight: 650;
  flex: 0 0 auto;
}

.recipient-summary {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1 1 auto;
}

.recipient-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recipient-mail {
  font-size: 11.5px;
  color: var(--ink-500);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.recipient-count {
  font-size: 11px;
  color: var(--ink-500);
  white-space: nowrap;
}

.recipient-body {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 10px;
}

.recipient-actions {
  display: flex;
  gap: 4px;
  justify-content: flex-end;
}

.danger {
  color: var(--red-600);
}

.palette {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px;
  padding: 0 14px 14px;
}

.palette-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 8px 6px;
  border: 1px solid var(--line-strong);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--ink-700);
  font-size: 12.5px;
  font-weight: 550;
  cursor: pointer;
}

.palette-button:hover {
  border-color: var(--blue-500);
  color: var(--blue-600);
}

.palette-active {
  background: var(--blue-100);
  border-color: var(--blue-500);
  color: var(--blue-700);
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 9px 14px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
  flex-wrap: wrap;
  position: sticky;
  top: 0;
  z-index: 5;
}

.toolbar-group {
  display: flex;
  align-items: center;
  gap: 6px;
}

.toolbar-end {
  margin-left: auto;
}

.zoom-label,
.page-total {
  font-size: 12.5px;
  color: var(--ink-600);
  min-width: 44px;
  text-align: center;
}

.page-input {
  width: 66px;
  padding: 5px 8px;
}

.placement-hint {
  margin: 0;
  padding: 8px 16px;
  background: var(--blue-100);
  color: var(--blue-700);
  font-size: 12.5px;
}

.editor-loading {
  padding: 40px;
}

.viewer-scroll {
  flex: 1 1 auto;
  overflow: auto;
  padding: 14px;
}

.field-type {
  display: flex;
  align-items: center;
  gap: 7px;
  font-weight: 600;
  font-size: 13px;
  color: var(--ink-800);
}

.field-type-dot,
.summary-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex: 0 0 auto;
}

.position-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 12px;
  margin: 0;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--surface-muted);
}

.position-grid div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.position-grid dt {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.03em;
  color: var(--ink-400);
  font-weight: 600;
}

.position-grid dd {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-800);
  font-variant-numeric: tabular-nums;
}

.summary {
  padding: 14px;
  border-top: 1px solid var(--line);
}

.summary h3 {
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  color: var(--ink-500);
  margin-bottom: 8px;
}

.summary-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.summary-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
}

.pane-footer {
  margin-top: auto;
  padding: 14px;
  border-top: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 0 14px 14px;
}

@media (max-width: 1100px) {
  .editor {
    grid-template-columns: 260px minmax(0, 1fr);
    height: auto;
  }

  .pane-right {
    grid-column: 1 / -1;
    border-left: none;
    border-top: 1px solid var(--line);
  }
}

@media (max-width: 820px) {
  .editor {
    grid-template-columns: minmax(0, 1fr);
  }

  .pane-left {
    border-right: none;
    border-bottom: 1px solid var(--line);
  }
}
</style>
