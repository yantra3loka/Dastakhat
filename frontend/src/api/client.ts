import type { ApiErrorBody, FieldErrorEntry } from './types'

/**
 * The typed `fetch` wrapper of the app.
 *
 * * every request is sent with `credentials: 'include'` so that the HttpOnly
 *   session cookie travels with it,
 * * every state changing request carries the CSRF token in the `X-CSRF-TOKEN`
 *   header (the token of the `XSRF-TOKEN` cookie or the one the login call
 *   returned),
 * * the error body `{status,error,message,fieldErrors}` of the backend is
 *   unwrapped into an {@link ApiError} with a readable message,
 * * a `401` on a protected endpoint sends the user back to the login page.
 */

/** The prefix of every backend route. */
export const API_BASE = '/api/v1'

/** The name of the cookie the backend writes the CSRF token into. */
export const CSRF_COOKIE = 'XSRF-TOKEN'

/** The name of the header the CSRF token has to be sent in. */
export const CSRF_HEADER = 'X-CSRF-TOKEN'

/** An error thrown by the API layer. */
export class ApiError extends Error {
  readonly status: number
  readonly error: string
  readonly fieldErrors: FieldErrorEntry[]
  readonly code: string | undefined

  constructor(status: number, message: string, error = 'Error', fieldErrors: FieldErrorEntry[] = [], code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.error = error
    this.fieldErrors = fieldErrors
    this.code = code
  }

  /** True when the request failed because of a validation problem. */
  get isValidation(): boolean {
    return this.status === 400 || this.status === 422
  }

  /** True when the request failed because of a conflict with the server state. */
  get isConflict(): boolean {
    return this.status === 409
  }

  /** True when the resource does not exist (or is not visible to the caller). */
  get isNotFound(): boolean {
    return this.status === 404
  }
}

let csrfOverride: string | null = null
let unauthorizedHandler: (() => void) | null = null
let csrfBootstrap: Promise<void> | null = null

/**
 * Registers the token the login call returned. It is preferred over the cookie
 * because it is the exact value the backend handed out.
 */
export function setCsrfToken(token: string | null): void {
  csrfOverride = token
}

/** The CSRF token that is currently known. */
export function getCsrfToken(): string | null {
  if (csrfOverride) return csrfOverride
  return readCookie(CSRF_COOKIE)
}

/** Registers the callback that is used when a protected call answers with 401. */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

/** Reads a cookie of the current document. */
export function readCookie(name: string): string | null {
  if (typeof document === 'undefined') return null
  const parts = document.cookie ? document.cookie.split(';') : []
  for (const part of parts) {
    const separator = part.indexOf('=')
    if (separator < 0) continue
    if (part.slice(0, separator).trim() !== name) continue
    const raw = part.slice(separator + 1).trim()
    try {
      return decodeURIComponent(raw)
    } catch {
      return raw
    }
  }
  return null
}

/** The options of a single request. */
export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  /** The JSON body of the request. */
  body?: unknown
  /** A multipart body, used for the document upload. */
  form?: FormData
  query?: Record<string, string | number | boolean | undefined | null>
  /** `false` for public endpoints - a 401 then does not trigger a redirect. */
  auth?: boolean
  signal?: AbortSignal
  headers?: Record<string, string>
}

/**
 * Makes sure that the backend has written the `XSRF-TOKEN` cookie. Spring
 * Security only writes it when a token was generated for the session, which
 * happens on the very first request of a browser.
 */
async function ensureCsrfCookie(): Promise<void> {
  if (getCsrfToken()) return
  if (!csrfBootstrap) {
    csrfBootstrap = fetch(`${API_BASE}/health`, { credentials: 'include', headers: { Accept: 'application/json' } })
      .then(() => undefined)
      .catch(() => undefined)
      .finally(() => {
        csrfBootstrap = null
      })
  }
  await csrfBootstrap
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = `${API_BASE}${path}`
  if (!query) return url
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    params.append(key, String(value))
  }
  const search = params.toString()
  return search ? `${url}?${search}` : url
}

/** Reads the body of a failed response and turns it into an {@link ApiError}. */
export async function toApiError(response: Response): Promise<ApiError> {
  let body: Partial<ApiErrorBody> | null = null
  try {
    const text = await response.text()
    if (text) {
      const parsed: unknown = JSON.parse(text)
      if (parsed && typeof parsed === 'object') body = parsed as Partial<ApiErrorBody>
    }
  } catch {
    body = null
  }
  const status = body?.status ?? response.status
  const error = body?.error ?? response.statusText ?? 'Error'
  const message =
    (typeof body?.message === 'string' && body.message.trim()) ||
    (typeof body?.error === 'string' && body.error.trim()) ||
    (response.statusText ? `The request failed with status ${response.status} (${response.statusText}).` : '') ||
    `The request failed with status ${response.status}.`
  const fieldErrors = Array.isArray(body?.fieldErrors) ? body.fieldErrors : []
  return new ApiError(status, message, error, fieldErrors, typeof body?.code === 'string' ? body.code : undefined)
}

/**
 * Performs a request against the backend and returns the parsed JSON body.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }

  if (method !== 'GET' && method !== 'DELETE') {
    await ensureCsrfCookie()
    const token = getCsrfToken()
    if (token) headers[CSRF_HEADER] = token
  }

  let body: BodyInit | undefined
  if (options.form) {
    body = options.form
  } else if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  let response: Response
  try {
    response = await fetch(buildUrl(path, options.query), {
      method,
      headers,
      body,
      credentials: 'include',
      signal: options.signal,
    })
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : 'The server could not be reached.'
    throw new ApiError(0, `The server could not be reached: ${message}`, 'Network error')
  }

  if (!response.ok) {
    const error = await toApiError(response)
    if (response.status === 401 && options.auth !== false) {
      unauthorizedHandler?.()
    }
    throw error
  }

  if (response.status === 204) return undefined as T
  const text = await response.text()
  if (!text) return undefined as T
  try {
    return JSON.parse(text) as T
  } catch {
    throw new ApiError(response.status, 'The server returned an unreadable response.', 'Parse error')
  }
}

/**
 * Downloads a binary resource (the PDFs) and returns it as a blob.
 */
export async function requestBlob(path: string, options: RequestOptions = {}): Promise<Blob> {
  const headers: Record<string, string> = { ...options.headers }
  if (options.method && options.method !== 'GET') {
    await ensureCsrfCookie()
    const token = getCsrfToken()
    if (token) headers[CSRF_HEADER] = token
  }
  let response: Response
  try {
    response = await fetch(buildUrl(path, options.query), {
      method: options.method ?? 'GET',
      headers,
      credentials: 'include',
      signal: options.signal,
    })
  } catch (cause) {
    const message = cause instanceof Error ? cause.message : 'The server could not be reached.'
    throw new ApiError(0, `The server could not be reached: ${message}`, 'Network error')
  }
  if (!response.ok) {
    const error = await toApiError(response)
    if (response.status === 401 && options.auth !== false) unauthorizedHandler?.()
    throw error
  }
  return response.blob()
}
