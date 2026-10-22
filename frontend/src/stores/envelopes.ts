import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as envelopesApi from '@/api/envelopes'
import type {
  Envelope,
  EnvelopeStats,
  EnvelopeStatus,
  FieldType,
  SigningMethod,
} from '@/api/types'
import { newId } from '@/utils/id'

/** The status filter of the document list. */
export type EnvelopeFilter = 'all' | EnvelopeStatus

/** A recipient while the envelope is edited. */
export interface EditorRecipient {
  id: string
  name: string
  email: string
  method: SigningMethod
  routingOrder: number
}

/** A placed field while the envelope is edited. */
export interface EditorField {
  id: string
  recipientId: string
  type: FieldType
  page: number
  x: number
  y: number
  w: number
  h: number
  required: boolean
  label: string
}

/**
 * The envelopes of the sender: the list with its filters and pagination, the
 * counters of the dashboard and the draft that the editor works on.
 */
export const useEnvelopeStore = defineStore('envelopes', () => {
  const items = ref<Envelope[]>([])
  const page = ref(0)
  const size = ref(25)
  const totalElements = ref(0)
  const totalPages = ref(0)
  const statusFilter = ref<EnvelopeFilter>('all')
  const search = ref('')
  const stats = ref<EnvelopeStats | null>(null)
  const current = ref<Envelope | null>(null)
  const loading = ref(false)
  const saving = ref(false)
  const error = ref<string | null>(null)

  const recipients = ref<EditorRecipient[]>([])
  const fields = ref<EditorField[]>([])
  const selectedFieldId = ref<string | null>(null)
  const selectedRecipientId = ref<string | null>(null)

  /** The items of the current page that match the search term. */
  const filteredItems = computed<Envelope[]>(() => {
    const term = search.value.trim().toLowerCase()
    if (!term) return items.value
    return items.value.filter((envelope) => {
      if ((envelope.title ?? '').toLowerCase().includes(term)) return true
      return (envelope.recipients ?? []).some(
        (recipient) =>
          (recipient.name ?? '').toLowerCase().includes(term) ||
          (recipient.email ?? '').toLowerCase().includes(term),
      )
    })
  })

  const hasPreviousPage = computed(() => page.value > 0)
  const hasNextPage = computed(() => page.value + 1 < totalPages.value)

  /** The fields of a recipient of the edited draft. */
  function fieldsOfRecipient(recipientId: string): EditorField[] {
    return fields.value.filter((field) => field.recipientId === recipientId)
  }

  /** The signature fields of a recipient of the edited draft. */
  function signatureFieldsOfRecipient(recipientId: string): EditorField[] {
    return fieldsOfRecipient(recipientId).filter((field) => field.type === 'signature')
  }

  // ------------------------------------------------------------- list

  async function fetchList(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const result = await envelopesApi.listEnvelopes({
        status: statusFilter.value,
        page: page.value,
        size: size.value,
      })
      items.value = result.content ?? []
      page.value = result.page ?? page.value
      size.value = result.size ?? size.value
      totalElements.value = result.totalElements ?? items.value.length
      totalPages.value = result.totalPages ?? 0
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'The documents could not be loaded.'
    } finally {
      loading.value = false
    }
  }

  async function fetchStats(): Promise<void> {
    try {
      stats.value = await envelopesApi.getEnvelopeStats()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'The counters could not be loaded.'
    }
  }

  async function fetchEnvelope(envelopeId: string): Promise<Envelope | null> {
    loading.value = true
    error.value = null
    try {
      current.value = await envelopesApi.getEnvelope(envelopeId)
      return current.value
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'The document could not be loaded.'
      return null
    } finally {
      loading.value = false
    }
  }

  /** Changes the status filter and reloads the first page. */
  async function setFilter(status: EnvelopeFilter): Promise<void> {
    statusFilter.value = status
    page.value = 0
    await fetchList()
  }

  /** Changes the search term. The filter runs on the current page. */
  function setSearch(term: string): void {
    search.value = term
  }

  /** Jumps to a page of the list. */
  async function goToPage(target: number): Promise<void> {
    if (target < 0 || target >= Math.max(totalPages.value, 1)) return
    page.value = target
    await fetchList()
  }

  async function nextPage(): Promise<void> {
    if (hasNextPage.value) await goToPage(page.value + 1)
  }

  async function previousPage(): Promise<void> {
    if (hasPreviousPage.value) await goToPage(page.value - 1)
  }

  // ------------------------------------------------------------- editor

  /** Copies an envelope into the editor state. */
  function loadEditor(envelope: Envelope): void {
    recipients.value = (envelope.recipients ?? []).map((recipient, index) => ({
      id: recipient.id ?? newId(),
      name: recipient.name ?? '',
      email: recipient.email ?? '',
      method: recipient.method ?? 'electronic',
      routingOrder: recipient.routingOrder ?? index + 1,
    }))
    fields.value = (envelope.fields ?? []).map((field) => ({
      id: field.id ?? newId(),
      recipientId: field.recipientId ?? '',
      type: field.type ?? 'text',
      page: field.page ?? 1,
      x: field.x ?? 0,
      y: field.y ?? 0,
      w: field.w ?? 0.1,
      h: field.h ?? 0.03,
      required: field.required ?? true,
      label: field.label ?? '',
    }))
    selectedFieldId.value = null
    selectedRecipientId.value = recipients.value[0]?.id ?? null
  }

  /** Adds an empty recipient to the editor. */
  function addRecipient(): EditorRecipient {
    const recipient: EditorRecipient = {
      id: newId(),
      name: '',
      email: '',
      method: 'electronic',
      routingOrder: recipients.value.length + 1,
    }
    recipients.value = [...recipients.value, recipient]
    selectedRecipientId.value = recipient.id
    return recipient
  }

  /** Removes a recipient and the fields that belong to it. */
  function removeRecipient(recipientId: string): void {
    recipients.value = recipients.value
      .filter((recipient) => recipient.id !== recipientId)
      .map((recipient, index) => ({ ...recipient, routingOrder: index + 1 }))
    fields.value = fields.value.filter((field) => field.recipientId !== recipientId)
    if (selectedRecipientId.value === recipientId) {
      selectedRecipientId.value = recipients.value[0]?.id ?? null
    }
  }

  /** Changes a recipient of the editor. */
  function updateRecipient(recipientId: string, patch: Partial<Omit<EditorRecipient, 'id'>>): void {
    recipients.value = recipients.value.map((recipient) =>
      recipient.id === recipientId ? { ...recipient, ...patch } : recipient,
    )
  }

  /** Moves a recipient one position up or down. */
  function moveRecipient(recipientId: string, direction: -1 | 1): void {
    const index = recipients.value.findIndex((recipient) => recipient.id === recipientId)
    const target = index + direction
    if (index < 0 || target < 0 || target >= recipients.value.length) return
    const copy = [...recipients.value]
    const [moved] = copy.splice(index, 1)
    if (!moved) return
    copy.splice(target, 0, moved)
    recipients.value = copy.map((recipient, position) => ({ ...recipient, routingOrder: position + 1 }))
  }

  /** Adds a placed field to the editor. */
  function addField(field: Omit<EditorField, 'id'>): EditorField {
    const created: EditorField = { ...field, id: newId() }
    fields.value = [...fields.value, created]
    selectedFieldId.value = created.id
    return created
  }

  /** Changes a placed field. */
  function updateField(fieldId: string, patch: Partial<Omit<EditorField, 'id'>>): void {
    fields.value = fields.value.map((field) => (field.id === fieldId ? { ...field, ...patch } : field))
  }

  /** Removes a placed field. */
  function removeField(fieldId: string): void {
    fields.value = fields.value.filter((field) => field.id !== fieldId)
    if (selectedFieldId.value === fieldId) selectedFieldId.value = null
  }

  function selectField(fieldId: string | null): void {
    selectedFieldId.value = fieldId
    const field = fields.value.find((entry) => entry.id === fieldId)
    if (field) selectedRecipientId.value = field.recipientId
  }

  /**
   * Saves the draft.
   *
   * The recipients are written first because the backend deletes the fields of
   * the envelope when the recipient list is replaced. Every recipient gets a
   * new id, so the fields are remapped onto the new ids before they are
   * written.
   */
  async function saveDraft(envelopeId: string): Promise<Envelope | null> {
    saving.value = true
    error.value = null
    try {
      const withRecipients = await envelopesApi.replaceRecipients(
        envelopeId,
        recipients.value.map((recipient, index) => ({
          name: recipient.name.trim(),
          email: recipient.email.trim().toLowerCase(),
          method: recipient.method,
          routingOrder: index + 1,
        })),
      )

      const idByEmail = new Map<string, string>()
      for (const recipient of withRecipients.recipients ?? []) {
        const key = (recipient.email ?? '').trim().toLowerCase()
        if (recipient.id) idByEmail.set(key, recipient.id)
      }
      const remapped = new Map<string, string>()
      for (const recipient of recipients.value) {
        const serverId = idByEmail.get(recipient.email.trim().toLowerCase())
        if (serverId) remapped.set(recipient.id, serverId)
      }

      const payload = fields.value.map((field) => ({
        recipientId: remapped.get(field.recipientId) ?? field.recipientId,
        type: field.type,
        page: field.page,
        x: field.x,
        y: field.y,
        w: field.w,
        h: field.h,
        required: field.required,
        label: field.label.trim() ? field.label.trim() : undefined,
      }))

      const saved = await envelopesApi.replaceFields(envelopeId, payload)
      loadEditor(saved)
      current.value = saved
      return saved
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'The draft could not be saved.'
      return null
    } finally {
      saving.value = false
    }
  }

  /** Sends the envelope to the recipients. */
  async function send(envelopeId: string): Promise<Envelope | null> {
    saving.value = true
    error.value = null
    try {
      const sent = await envelopesApi.sendEnvelope(envelopeId)
      current.value = sent
      return sent
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'The envelope could not be sent.'
      return null
    } finally {
      saving.value = false
    }
  }

  /** Voids the envelope. */
  async function voidEnvelope(envelopeId: string, reason?: string): Promise<Envelope | null> {
    saving.value = true
    error.value = null
    try {
      const voided = await envelopesApi.voidEnvelope(envelopeId, reason)
      current.value = voided
      return voided
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : 'The envelope could not be voided.'
      return null
    } finally {
      saving.value = false
    }
  }

  return {
    items,
    filteredItems,
    page,
    size,
    totalElements,
    totalPages,
    statusFilter,
    search,
    stats,
    current,
    loading,
    saving,
    error,
    recipients,
    fields,
    selectedFieldId,
    selectedRecipientId,
    hasPreviousPage,
    hasNextPage,
    fieldsOfRecipient,
    signatureFieldsOfRecipient,
    fetchList,
    fetchStats,
    fetchEnvelope,
    setFilter,
    setSearch,
    goToPage,
    nextPage,
    previousPage,
    loadEditor,
    addRecipient,
    removeRecipient,
    updateRecipient,
    moveRecipient,
    addField,
    updateField,
    removeField,
    selectField,
    saveDraft,
    send,
    voidEnvelope,
  }
})
