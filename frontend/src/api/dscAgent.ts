/**
 * The client of the local DSC signing agent.
 *
 * The agent is a small PKCS#11 helper that runs on the machine of the signer
 * and talks to the token. It is reachable under `https://127.0.0.1:51515` and
 * is **not** part of the Dastakhat backend - the backend only validates the
 * certificate and embeds the signature the token produced.
 *
 * The agent is optional: when it is not running the signer page shows a clear
 * "agent not running" state with the setup instructions instead of an error.
 */

/** The base URL of the local agent. */
export const DSC_AGENT_BASE_URL = 'https://127.0.0.1:51515'

/** The answer of `GET /health`. */
export interface DscAgentHealth {
  status: string
  version: string | null
  agent: string | null
}

/** A certificate the token holds. */
export interface DscCertificate {
  id: string
  subject: string
  issuer: string
  serialNumber: string
  validFrom: string | null
  validTo: string | null
  pem: string
  expired: boolean
  trusted: boolean
}

/** The body of `POST /sign`. */
export interface DscSignRequest {
  pin: string
  certificateId?: string
  /** The bytes that have to be signed, base64 encoded. */
  toBeSignedBase64?: string
}

/** The answer of `POST /sign`. */
export interface DscSignResult {
  signatureBase64: string
}

/** The reasons why the agent cannot be used. */
export type DscAgentErrorKind = 'unreachable' | 'unauthorized' | 'rejected' | 'malformed'

/** An error of the local agent with a readable message. */
export class DscAgentError extends Error {
  readonly kind: DscAgentErrorKind
  readonly status: number

  constructor(kind: DscAgentErrorKind, message: string, status = 0) {
    super(message)
    this.name = 'DscAgentError'
    this.kind = kind
    this.status = status
  }
}

function readString(source: Record<string, unknown>, keys: string[]): string | null {
  for (const key of keys) {
    const value = source[key]
    if (typeof value === 'string' && value.trim()) return value.trim()
  }
  return null
}

function readBoolean(source: Record<string, unknown>, keys: string[]): boolean | null {
  for (const key of keys) {
    const value = source[key]
    if (typeof value === 'boolean') return value
    if (value === 'true') return true
    if (value === 'false') return false
  }
  return null
}

function asRecord(value: unknown): Record<string, unknown> | null {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return value as Record<string, unknown>
  }
  return null
}

/** Normalises the answer of `GET /certificates`. */
export function parseCertificates(payload: unknown): DscCertificate[] {
  const list = Array.isArray(payload)
    ? payload
    : (asRecord(payload)?.['certificates'] ?? asRecord(payload)?.['items'] ?? [])
  if (!Array.isArray(list)) return []
  const certificates: DscCertificate[] = []
  for (const entry of list) {
    const record = asRecord(entry)
    if (!record) continue
    const pem = readString(record, ['pem', 'certificatePem', 'certPem', 'certificate']) ?? ''
    const validTo = readString(record, ['validTo', 'notAfter', 'expiry', 'expiresAt'])
    const expired = readBoolean(record, ['expired']) ?? (validTo ? new Date(validTo).getTime() < Date.now() : false)
    certificates.push({
      id: readString(record, ['id', 'alias', 'label', 'key']) ?? pem.slice(0, 32),
      subject: readString(record, ['subject', 'subjectDn', 'name', 'displayName']) ?? 'Unknown certificate',
      issuer: readString(record, ['issuer', 'issuerDn']) ?? '-',
      serialNumber: readString(record, ['serialNumber', 'serial']) ?? '-',
      validFrom: readString(record, ['validFrom', 'notBefore']),
      validTo,
      pem,
      expired,
      trusted: readBoolean(record, ['trusted', 'isTrusted']) ?? true,
    })
  }
  return certificates
}

/** Normalises the answer of `POST /sign`. */
export function parseSignResult(payload: unknown): DscSignResult {
  const record = asRecord(payload)
  const signature =
    (record ? readString(record, ['signatureBase64', 'signature', 'base64', 'signedData']) : null) ?? ''
  if (!signature) {
    throw new DscAgentError('malformed', 'The signing agent did not return a signature.')
  }
  return { signatureBase64: signature }
}

async function agentFetch(path: string, init?: RequestInit): Promise<unknown> {
  let response: Response
  try {
    response = await fetch(`${DSC_AGENT_BASE_URL}${path}`, {
      ...init,
      headers: { Accept: 'application/json', ...(init?.headers ?? {}) },
    })
  } catch {
    throw new DscAgentError(
      'unreachable',
      'The DSC agent is not running. Start it on this computer and try again.',
    )
  }
  if (response.status === 401 || response.status === 403) {
    throw new DscAgentError('unauthorized', 'The PIN of the token was not accepted.', response.status)
  }
  if (response.status === 409) {
    throw new DscAgentError('rejected', 'The signing was cancelled on the token.', response.status)
  }
  if (!response.ok) {
    throw new DscAgentError(
      'rejected',
      `The signing agent answered with status ${response.status}.`,
      response.status,
    )
  }
  const text = await response.text()
  if (!text) return null
  try {
    return JSON.parse(text) as unknown
  } catch {
    throw new DscAgentError('malformed', 'The signing agent returned an unreadable answer.')
  }
}

/** `GET /health` - is the agent running? */
export async function fetchAgentHealth(): Promise<DscAgentHealth> {
  const payload = await agentFetch('/health')
  const record = asRecord(payload)
  return {
    status: (record ? readString(record, ['status', 'state']) : null) ?? 'UP',
    version: record ? readString(record, ['version']) : null,
    agent: record ? readString(record, ['agent', 'name']) : null,
  }
}

/** `GET /certificates` - the certificates of the token. */
export async function fetchCertificates(): Promise<DscCertificate[]> {
  const payload = await agentFetch('/certificates')
  return parseCertificates(payload)
}

/** `POST /sign` - the token signs the given bytes. */
export async function signWithToken(request: DscSignRequest): Promise<DscSignResult> {
  const payload = await agentFetch('/sign', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  return parseSignResult(payload)
}
