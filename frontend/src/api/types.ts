/**
 * The TypeScript mirror of `contract/openapi.yaml`.
 *
 * Every interface below corresponds to a schema of the OpenAPI contract. The
 * optional markers reflect the fact that the backend may omit null values
 * while it serialises the records.
 */

/** The lifecycle of an envelope. */
export type EnvelopeStatus = 'draft' | 'sent' | 'completed' | 'declined' | 'voided' | 'expired'

/** The order in which the recipients are asked to sign. */
export type RoutingMode = 'sequential' | 'parallel'

/** How a recipient signs. `esign_mock` is a simulation without legal standing. */
export type SigningMethod = 'electronic' | 'dsc' | 'esign_mock'

/** The state of a single recipient. */
export type RecipientStatus = 'pending' | 'notified' | 'viewed' | 'signed' | 'declined'

/** The kind of a placed field. */
export type FieldType = 'signature' | 'initials' | 'date' | 'text' | 'name'

/** The kind of a captured signature. */
export type SignatureKind = 'drawn' | 'typed' | 'dsc' | 'esign_mock'

/** The kind of an audit event. */
export type AuditEventType =
  | 'envelope_created'
  | 'document_uploaded'
  | 'recipients_updated'
  | 'fields_updated'
  | 'envelope_sent'
  | 'document_viewed'
  | 'consent_recorded'
  | 'signed'
  | 'declined'
  | 'sealed'
  | 'downloaded'
  | 'voided'
  | 'dsc_signed'
  | 'esign_signed'

/** The error body every failing endpoint returns. */
export interface ApiErrorBody {
  status: number
  error: string
  message: string
  fieldErrors?: FieldErrorEntry[]
  code?: string
  timestamp?: string
}

/** A single field level validation problem. */
export interface FieldErrorEntry {
  field?: string
  message?: string
}

/** The logged in sender. */
export interface User {
  id?: string
  email?: string
  name?: string
  role?: 'sender'
}

/** The response of `POST /auth/login`. */
export interface LoginResponse {
  user?: User
  csrfToken?: string
}

/** The metadata of a stored PDF. */
export interface DocumentInfo {
  filename?: string
  sha256?: string
  pages?: number
  sizeBytes?: number
}

/** Information about a captured signature. */
export interface SignatureInfo {
  id?: string
  kind?: SignatureKind
  signerCertSha256?: string | null
  cmsSha256?: string | null
  createdAt?: string
}

/** A recipient of an envelope. */
export interface Recipient extends RecipientInput {
  id?: string
  status?: RecipientStatus
  signedAt?: string | null
  viewedAt?: string | null
  notifiedAt?: string | null
  tokenExpiresAt?: string | null
  signature?: SignatureInfo | null
}

/** The payload of `PUT /envelopes/{id}/recipients`. */
export interface RecipientInput {
  name: string
  email: string
  method: SigningMethod
  routingOrder?: number
}

/** The request body of `PUT /envelopes/{id}/recipients`. */
export interface RecipientsRequest {
  recipients: RecipientInput[]
}

/** A placed field, positioned as fractions of the page with a top left origin. */
export interface Field extends FieldInput {
  id?: string
  value?: string | null
}

/** The payload of `PUT /envelopes/{id}/fields`. */
export interface FieldInput {
  recipientId: string
  type: FieldType
  page: number
  x: number
  y: number
  w: number
  h: number
  required?: boolean
  label?: string
}

/** The request body of `PUT /envelopes/{id}/fields`. */
export interface FieldsRequest {
  fields: FieldInput[]
}

/** A full envelope with its recipients, fields and signatures. */
export interface Envelope {
  id?: string
  title?: string
  status?: EnvelopeStatus
  routing?: RoutingMode
  expiresAt?: string
  createdAt?: string
  updatedAt?: string
  completedAt?: string | null
  lastActivityAt?: string | null
  signedCount?: number
  recipientCount?: number
  originalSha256?: string | null
  finalSha256?: string | null
  preSealSha256?: string | null
  pages?: number
  document?: DocumentInfo
  recipients?: Recipient[]
  fields?: Field[]
  finalAvailable?: boolean
}

/** One page of the envelope list. */
export interface EnvelopePage {
  content?: Envelope[]
  page?: number
  size?: number
  totalElements?: number
  totalPages?: number
}

/** The body of `PATCH /envelopes/{id}`. */
export interface EnvelopeUpdateRequest {
  title?: string
  routing?: RoutingMode
  expiresInDays?: number
  status?: 'draft' | 'sent' | 'voided'
}

/** The counts of the dashboard. */
export interface EnvelopeStats {
  total?: number
  draft?: number
  waiting?: number
  completed?: number
  declined?: number
  voided?: number
  expired?: number
  actionNeeded?: number
  awaitingOthers?: number
}

/** A field as the signer sees it. */
export interface SignerField {
  id?: string
  type?: FieldType
  page?: number
  x?: number
  y?: number
  w?: number
  h?: number
  required?: boolean
  label?: string | null
  value?: string | null
}

/** The view model of the public signer page. */
export interface SignerView {
  envelopeId?: string
  envelopeTitle?: string
  senderName?: string
  senderEmail?: string
  recipientName?: string
  recipientEmail?: string
  method?: SigningMethod
  expiresAt?: string
  consentRecorded?: boolean
  document?: DocumentInfo
  fields?: SignerField[]
  documentUrl?: string
  legalNotice?: string
  /** The backend adds this flag, it is true for the MOCK eSign flow. */
  mock?: boolean
}

/** The body of `POST /sign/{token}/consent`. */
export interface ConsentRequest {
  consent: boolean
  consentText?: string
}

/** One value of a placed field. */
export interface FieldValue {
  fieldId: string
  value?: string
  signatureImageBase64?: string
}

/** The body of `POST /sign/{token}/complete`. */
export interface CompleteRequest {
  consent: boolean
  fields: FieldValue[]
}

/** The response of `POST /sign/{token}/complete`. */
export interface CompleteResponse {
  status?: string
  recipientId?: string
  signedAt?: string
  envelopeStatus?: string
  nextRecipientsNotified?: number
}

/** The response of `POST /sign/{token}/dsc/prepare`. */
export interface DscPrepareResponse {
  sessionId?: string
  toBeSignedBase64?: string
  documentSha256?: string
  documentName?: string
  signatureAlgorithm?: string
  expiresAt?: string
}

/** The response of `POST /sign/{token}/dsc/finish`. */
export interface DscFinishResponse {
  status?: string
  signatureSha256?: string
  validated?: boolean
  trusted?: boolean
  envelopeStatus?: string
}

/** The response of `POST /sign/{token}/esign/start`. */
export interface EsignStartResponse {
  txn?: string
  espUrl?: string
  requestXml?: string
  mock?: boolean
  banner?: string
}

/** The response of the MOCK eSign provider callback. */
export interface EsignCallbackResponse {
  status?: string
  validated?: boolean
  envelopeStatus?: string
}

/** One entry of the audit trail. */
export interface AuditEvent {
  seq?: number
  type?: AuditEventType
  actor?: string
  ip?: string | null
  userAgent?: string | null
  docSha256?: string | null
  payload?: Record<string, unknown>
  at?: string
  prevHash?: string
  hash?: string
}

/** The result of the audit chain verification. */
export interface AuditVerification {
  intact?: boolean
  brokenAtSeq?: number | null
  eventCount?: number
  headHash?: string
  checkedAt?: string
}

/** The liveness probe. */
export interface Health {
  status?: string
  service?: string
  version?: string
  time?: string
}

/** The readiness probe. */
export interface Readiness {
  status?: string
  checks?: Record<string, string>
}
