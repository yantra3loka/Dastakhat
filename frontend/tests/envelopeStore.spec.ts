import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useEnvelopeStore } from '@/stores/envelopes'
import type { Envelope } from '@/api/types'

const listEnvelopes = vi.fn()
const replaceRecipients = vi.fn()
const replaceFields = vi.fn()

vi.mock('@/api/envelopes', () => ({
  listEnvelopes: (...args: unknown[]) => listEnvelopes(...args),
  getEnvelopeStats: vi.fn(async () => ({ total: 0 })),
  getEnvelope: vi.fn(async () => null),
  createDraft: vi.fn(),
  updateEnvelope: vi.fn(),
  replaceRecipients: (...args: unknown[]) => replaceRecipients(...args),
  replaceFields: (...args: unknown[]) => replaceFields(...args),
  sendEnvelope: vi.fn(),
  voidEnvelope: vi.fn(),
  downloadEnvelopeDocument: vi.fn(),
  getAuditEvents: vi.fn(async () => []),
  verifyAuditChain: vi.fn(),
}))

function envelope(overrides: Partial<Envelope> = {}): Envelope {
  return {
    id: 'env-1',
    title: 'Service agreement',
    status: 'sent',
    routing: 'sequential',
    createdAt: '2026-01-05T10:00:00Z',
    signedCount: 1,
    recipientCount: 2,
    recipients: [
      { id: 'r-1', name: 'Asha', email: 'asha@example.test', method: 'electronic', routingOrder: 1 },
      { id: 'r-2', name: 'Bilal', email: 'bilal@example.test', method: 'dsc', routingOrder: 2 },
    ],
    fields: [],
    ...overrides,
  }
}

describe('the envelope store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    listEnvelopes.mockReset()
    replaceRecipients.mockReset()
    replaceFields.mockReset()
  })

  it('loads the first page with the given status filter', async () => {
    listEnvelopes.mockResolvedValue({
      content: [envelope()],
      page: 0,
      size: 25,
      totalElements: 1,
      totalPages: 1,
    })
    const store = useEnvelopeStore()

    await store.setFilter('sent')

    expect(listEnvelopes).toHaveBeenCalledWith({ status: 'sent', page: 0, size: 25 })
    expect(store.items).toHaveLength(1)
    expect(store.statusFilter).toBe('sent')
  })

  it('goes back to the first page when the filter changes', async () => {
    listEnvelopes.mockResolvedValue({ content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 })
    const store = useEnvelopeStore()
    store.page = 3

    await store.setFilter('draft')

    expect(store.page).toBe(0)
    expect(listEnvelopes).toHaveBeenLastCalledWith({ status: 'draft', page: 0, size: 25 })
  })

  it('walks through the pages', async () => {
    listEnvelopes.mockImplementation(async (options: { page: number }) => ({
      content: [envelope()],
      page: options.page,
      size: 25,
      totalElements: 60,
      totalPages: 3,
    }))
    const store = useEnvelopeStore()

    await store.fetchList()
    expect(store.totalPages).toBe(3)
    expect(store.hasNextPage).toBe(true)
    expect(store.hasPreviousPage).toBe(false)

    await store.nextPage()
    expect(store.page).toBe(1)
    expect(listEnvelopes).toHaveBeenLastCalledWith({ status: 'all', page: 1, size: 25 })

    await store.previousPage()
    expect(store.page).toBe(0)

    await store.goToPage(9)
    expect(store.page).toBe(0)
  })

  it('filters the current page by the search term', async () => {
    listEnvelopes.mockResolvedValue({
      content: [
        envelope({
          id: 'env-1',
          title: 'Service agreement',
          recipients: [{ id: 'r-1', name: 'Asha', email: 'asha@example.test', method: 'electronic' }],
        }),
        envelope({
          id: 'env-2',
          title: 'Invoice 2026',
          recipients: [{ id: 'r-2', name: 'Bilal', email: 'bilal@example.test', method: 'electronic' }],
        }),
        envelope({
          id: 'env-3',
          title: 'NDA',
          recipients: [{ id: 'r-3', name: 'Chandra', email: 'chandra@example.test', method: 'electronic' }],
        }),
      ],
      page: 0,
      size: 25,
      totalElements: 3,
      totalPages: 1,
    })
    const store = useEnvelopeStore()
    await store.fetchList()

    store.setSearch('nda')
    expect(store.filteredItems.map((item) => item.id)).toEqual(['env-3'])

    store.setSearch('asha')
    expect(store.filteredItems.map((item) => item.id)).toEqual(['env-1'])

    store.setSearch('agreement')
    expect(store.filteredItems.map((item) => item.id)).toEqual(['env-1'])

    store.setSearch('')
    expect(store.filteredItems).toHaveLength(3)
  })

  it('adds, edits and removes the recipients of the draft', async () => {
    const store = useEnvelopeStore()
    store.loadEditor(envelope())

    const recipient = store.addRecipient()
    expect(store.recipients).toHaveLength(3)
    expect(store.selectedRecipientId).toBe(recipient.id)

    store.updateRecipient(recipient.id, { name: 'Chandra', email: 'chandra@example.test' })
    expect(store.recipients[2]?.name).toBe('Chandra')

    store.removeRecipient(recipient.id)
    expect(store.recipients).toHaveLength(2)
  })

  it('renumbers the routing order when a recipient is removed', async () => {
    const store = useEnvelopeStore()
    store.loadEditor(
      envelope({
        recipients: [
          { id: 'r-1', name: 'Asha', email: 'asha@example.test', method: 'electronic', routingOrder: 1 },
          { id: 'r-2', name: 'Bilal', email: 'bilal@example.test', method: 'electronic', routingOrder: 2 },
          { id: 'r-3', name: 'Chandra', email: 'chandra@example.test', method: 'electronic', routingOrder: 3 },
        ],
      }),
    )
    const first = store.recipients[0]
    expect(first).toBeDefined()

    store.removeRecipient(String(first?.id))

    expect(store.recipients).toHaveLength(2)
    expect(store.recipients.map((recipient) => recipient.routingOrder)).toEqual([1, 2])
  })

  it('remaps the fields onto the new recipient ids when the draft is saved', async () => {
    const store = useEnvelopeStore()
    store.loadEditor(envelope())
    const recipient = store.recipients[0]
    store.addField({
      recipientId: String(recipient?.id),
      type: 'signature',
      page: 1,
      x: 0.1,
      y: 0.2,
      w: 0.3,
      h: 0.05,
      required: true,
      label: 'Signature',
    })

    replaceRecipients.mockResolvedValue(
      envelope({
        recipients: [
          { id: 'new-1', name: 'Asha', email: 'asha@example.test', method: 'electronic', routingOrder: 1 },
          { id: 'new-2', name: 'Bilal', email: 'bilal@example.test', method: 'dsc', routingOrder: 2 },
        ],
      }),
    )
    replaceFields.mockResolvedValue(
      envelope({
        id: 'env-1',
        recipients: [
          { id: 'new-1', name: 'Asha', email: 'asha@example.test', method: 'electronic', routingOrder: 1 },
          { id: 'new-2', name: 'Bilal', email: 'bilal@example.test', method: 'dsc', routingOrder: 2 },
        ],
        fields: [
          {
            id: 'field-9',
            recipientId: 'new-1',
            type: 'signature',
            page: 1,
            x: 0.1,
            y: 0.2,
            w: 0.3,
            h: 0.05,
            required: true,
          },
        ],
      }),
    )

    const saved = await store.saveDraft('env-1')

    expect(saved).not.toBeNull()
    const payload = replaceFields.mock.calls[0]?.[1] as Array<{ recipientId: string }>
    expect(payload[0]?.recipientId).toBe('new-1')
    expect(store.recipients[0]?.id).toBe('new-1')
    expect(store.fields[0]?.recipientId).toBe('new-1')
  })

  it('keeps the error message when the draft cannot be saved', async () => {
    const store = useEnvelopeStore()
    store.loadEditor(envelope())
    replaceRecipients.mockRejectedValue(new Error('Add at least one recipient before sending.'))

    const saved = await store.saveDraft('env-1')

    expect(saved).toBeNull()
    expect(store.error).toBe('Add at least one recipient before sending.')
  })
})
