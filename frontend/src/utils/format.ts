import type { EnvelopeStatus, FieldType, RecipientStatus, RoutingMode, SigningMethod } from '@/api/types'

/** The time zone every timestamp of the app is displayed in. */
export const DISPLAY_TIME_ZONE = 'Asia/Kolkata'

const dateTimeFormatter = new Intl.DateTimeFormat('en-IN', {
  timeZone: DISPLAY_TIME_ZONE,
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

const dateFormatter = new Intl.DateTimeFormat('en-IN', {
  timeZone: DISPLAY_TIME_ZONE,
  day: '2-digit',
  month: 'short',
  year: 'numeric',
})

/**
 * Formats an ISO timestamp in the Indian Standard Time (Asia/Kolkata).
 */
export function formatDateTimeIst(value: string | null | undefined): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return `${dateTimeFormatter.format(date)} IST`
}

/** Formats an ISO timestamp as a date in IST. */
export function formatDateIst(value: string | null | undefined): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return dateFormatter.format(date)
}

/** The label of an envelope status. */
export function statusLabel(status: EnvelopeStatus | string | undefined): string {
  switch (status) {
    case 'draft':
      return 'Draft'
    case 'sent':
      return 'Waiting'
    case 'completed':
      return 'Completed'
    case 'declined':
      return 'Declined'
    case 'voided':
      return 'Voided'
    case 'expired':
      return 'Expired'
    default:
      return 'Unknown'
  }
}

/** The label of a recipient status. */
export function recipientStatusLabel(status: RecipientStatus | string | undefined): string {
  switch (status) {
    case 'pending':
      return 'Pending'
    case 'notified':
      return 'Notified'
    case 'viewed':
      return 'Viewed'
    case 'signed':
      return 'Signed'
    case 'declined':
      return 'Declined'
    default:
      return 'Unknown'
  }
}

/** The label of a signing method. */
export function methodLabel(method: SigningMethod | string | undefined): string {
  switch (method) {
    case 'electronic':
      return 'Electronic'
    case 'dsc':
      return 'DSC (token)'
    case 'esign_mock':
      return 'Aadhaar eSign (MOCK)'
    default:
      return 'Unknown'
  }
}

/** The label of a field type. */
export function fieldTypeLabel(type: FieldType | string | undefined): string {
  switch (type) {
    case 'signature':
      return 'Signature'
    case 'initials':
      return 'Initials'
    case 'date':
      return 'Date'
    case 'text':
      return 'Text'
    case 'name':
      return 'Name'
    default:
      return 'Unknown'
  }
}

/** The label of the routing mode. */
export function routingLabel(routing: RoutingMode | string | undefined): string {
  switch (routing) {
    case 'sequential':
      return 'Sequential - one after another'
    case 'parallel':
      return 'Parallel - everybody at once'
    default:
      return 'Unknown'
  }
}

/** Formats a number of bytes. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

/** Shortens a hash for the table view. */
export function shortHash(hash: string | null | undefined, length = 12): string {
  if (!hash) return '-'
  if (hash.length <= length) return hash
  return `${hash.slice(0, length)}…`
}

/** The number of days between now and the given timestamp, rounded up. */
export function daysUntil(value: string | null | undefined): number | null {
  if (!value) return null
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return null
  const diff = date.getTime() - Date.now()
  return Math.ceil(diff / (24 * 60 * 60 * 1000))
}

/** The relative time of a timestamp, for example "3 hours ago". */
export function relativeTime(value: string | null | undefined): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  const seconds = Math.round((date.getTime() - Date.now()) / 1000)
  const absolute = Math.abs(seconds)
  const units: Array<[Intl.RelativeTimeFormatUnit, number]> = [
    ['year', 60 * 60 * 24 * 365],
    ['month', 60 * 60 * 24 * 30],
    ['day', 60 * 60 * 24],
    ['hour', 60 * 60],
    ['minute', 60],
    ['second', 1],
  ]
  const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' })
  for (const [unit, size] of units) {
    if (absolute >= size || unit === 'second') {
      return formatter.format(Math.round(seconds / size), unit)
    }
  }
  return '-'
}
