import { request, requestBlob } from './client'
import type {
  AuditEvent,
  AuditVerification,
  Envelope,
  EnvelopePage,
  EnvelopeStats,
  EnvelopeStatus,
  EnvelopeUpdateRequest,
  FieldInput,
  RecipientInput,
} from './types'

/**
 * The sender facing envelope endpoints.
 */

/** `GET /envelopes` - one page of the envelope list. */
export async function listEnvelopes(options: {
  status?: EnvelopeStatus | 'all'
  page?: number
  size?: number
} = {}): Promise<EnvelopePage> {
  return request<EnvelopePage>('/envelopes', {
    query: {
      status: options.status && options.status !== 'all' ? options.status : undefined,
      page: options.page ?? 0,
      size: options.size ?? 25,
    },
  })
}

/** `GET /envelopes/stats` - the counters of the dashboard. */
export async function getEnvelopeStats(): Promise<EnvelopeStats> {
  return request<EnvelopeStats>('/envelopes/stats')
}

/** `GET /envelopes/{id}` - a single envelope with recipients, fields and signatures. */
export async function getEnvelope(envelopeId: string): Promise<Envelope> {
  return request<Envelope>(`/envelopes/${envelopeId}`)
}

/** `POST /envelopes` - uploads a PDF and creates a draft envelope. */
export async function createDraft(file: File, title?: string): Promise<Envelope> {
  const form = new FormData()
  form.append('file', file, file.name)
  if (title && title.trim()) form.append('title', title.trim())
  return request<Envelope>('/envelopes', { method: 'POST', form })
}

/** `PATCH /envelopes/{id}` - updates title, routing or expiry of a draft. */
export async function updateEnvelope(
  envelopeId: string,
  body: EnvelopeUpdateRequest,
): Promise<Envelope> {
  return request<Envelope>(`/envelopes/${envelopeId}`, { method: 'PATCH', body })
}

/** `PUT /envelopes/{id}/recipients` - replaces the recipient list of a draft. */
export async function replaceRecipients(
  envelopeId: string,
  recipients: RecipientInput[],
): Promise<Envelope> {
  return request<Envelope>(`/envelopes/${envelopeId}/recipients`, {
    method: 'PUT',
    body: { recipients },
  })
}

/** `PUT /envelopes/{id}/fields` - replaces the placed fields of a draft. */
export async function replaceFields(envelopeId: string, fields: FieldInput[]): Promise<Envelope> {
  return request<Envelope>(`/envelopes/${envelopeId}/fields`, {
    method: 'PUT',
    body: { fields },
  })
}

/** `POST /envelopes/{id}/send` - sends the envelope to the recipients. */
export async function sendEnvelope(envelopeId: string): Promise<Envelope> {
  return request<Envelope>(`/envelopes/${envelopeId}/send`, { method: 'POST' })
}

/** `POST /envelopes/{id}/void` - voids the envelope and invalidates the links. */
export async function voidEnvelope(envelopeId: string, reason?: string): Promise<Envelope> {
  return request<Envelope>(`/envelopes/${envelopeId}/void`, {
    method: 'POST',
    query: reason ? { reason } : undefined,
  })
}

/** `GET /envelopes/{id}/documents/{kind}` - the original, working or final PDF. */
export async function downloadEnvelopeDocument(envelopeId: string, kind: 'original' | 'working' | 'final'): Promise<Blob> {
  return requestBlob(`/envelopes/${envelopeId}/documents/${kind}`)
}

/** `GET /envelopes/{id}/audit` - the audit trail of the envelope. */
export async function getAuditEvents(envelopeId: string): Promise<AuditEvent[]> {
  return request<AuditEvent[]>(`/envelopes/${envelopeId}/audit`)
}

/** `GET /envelopes/{id}/audit/verify` - recomputes the audit hash chain. */
export async function verifyAuditChain(envelopeId: string): Promise<AuditVerification> {
  return request<AuditVerification>(`/envelopes/${envelopeId}/audit/verify`)
}
