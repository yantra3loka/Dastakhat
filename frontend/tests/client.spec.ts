import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  CSRF_HEADER,
  getCsrfToken,
  readCookie,
  request,
  requestBlob,
  setCsrfToken,
  setUnauthorizedHandler,
  toApiError,
} from '@/api/client'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('the API client', () => {
  beforeEach(() => {
    setCsrfToken(null)
    setUnauthorizedHandler(null)
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('reads the CSRF token from the cookie', () => {
    document.cookie = 'XSRF-TOKEN=cookie-token; path=/'
    expect(readCookie('XSRF-TOKEN')).toBe('cookie-token')
    expect(getCsrfToken()).toBe('cookie-token')
  })

  it('prefers the token of the store over the cookie', () => {
    document.cookie = 'XSRF-TOKEN=cookie-token; path=/'
    setCsrfToken('store-token')
    expect(getCsrfToken()).toBe('store-token')
  })

  it('unwraps the error body of the backend into an ApiError', async () => {
    const response = jsonResponse(
      {
        status: 422,
        error: 'Unprocessable Entity',
        message: 'The PDF contains JavaScript actions, which are not allowed.',
        fieldErrors: [{ field: 'file', message: 'not allowed' }],
        code: 'unprocessable',
        timestamp: '2026-01-01T00:00:00Z',
      },
      422,
    )

    const error = await toApiError(response)

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(422)
    expect(error.message).toBe('The PDF contains JavaScript actions, which are not allowed.')
    expect(error.error).toBe('Unprocessable Entity')
    expect(error.fieldErrors).toEqual([{ field: 'file', message: 'not allowed' }])
    expect(error.code).toBe('unprocessable')
    expect(error.isValidation).toBe(true)
  })

  it('falls back to the status text when the body is not readable', async () => {
    const response = new Response('not json', { status: 500, statusText: 'Internal Server Error' })
    const error = await toApiError(response)
    expect(error.status).toBe(500)
    expect(error.message).toContain('Internal Server Error')
  })

  it('throws the unwrapped error for a failed request', async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse({ status: 409, error: 'Conflict', message: 'The recipient Asha does not have a signature field yet.' }, 409),
    )
    vi.stubGlobal('fetch', fetchMock)

    await expect(request('/envelopes/env-1/send', { method: 'POST' })).rejects.toThrowError(
      'The recipient Asha does not have a signature field yet.',
    )
  })

  it('sends the CSRF token on every state changing request', async () => {
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => jsonResponse({ ok: true }))
    vi.stubGlobal('fetch', fetchMock)
    document.cookie = 'XSRF-TOKEN=cookie-token; path=/'

    await request('/envelopes', { method: 'POST', body: { title: 'x' } })

    const init = fetchMock.mock.calls[0]?.[1]
    const headers = (init?.headers ?? {}) as Record<string, string>
    expect(headers[CSRF_HEADER]).toBe('cookie-token')
    expect(init?.credentials).toBe('include')
    expect(init?.method).toBe('POST')
  })

  it('does not send a CSRF token on a GET request', async () => {
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => jsonResponse({ ok: true }))
    vi.stubGlobal('fetch', fetchMock)
    document.cookie = 'XSRF-TOKEN=cookie-token; path=/'

    await request('/envelopes')

    const init = fetchMock.mock.calls[0]?.[1]
    const headers = (init?.headers ?? {}) as Record<string, string>
    expect(headers[CSRF_HEADER]).toBeUndefined()
  })

  it('calls the unauthorized handler when a protected endpoint answers with 401', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ status: 401, error: 'Unauthorized', message: 'Authentication required.' }, 401))
    vi.stubGlobal('fetch', fetchMock)
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)

    await expect(request('/envelopes')).rejects.toThrowError('Authentication required.')
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })

  it('does not redirect when a public endpoint answers with 401', async () => {
    const fetchMock = vi.fn(async () => jsonResponse({ status: 401, error: 'Unauthorized', message: 'nope' }, 401))
    vi.stubGlobal('fetch', fetchMock)
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)

    await expect(request('/sign/token', { auth: false })).rejects.toThrowError('nope')
    expect(onUnauthorized).not.toHaveBeenCalled()
  })

  it('builds the query string and skips empty values', async () => {
    const fetchMock = vi.fn(async (_url: string, _init?: RequestInit) => jsonResponse({ content: [] }))
    vi.stubGlobal('fetch', fetchMock)

    await request('/envelopes', { query: { status: 'sent', page: 2, size: undefined } })

    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/envelopes?status=sent&page=2')
  })

  it('returns a blob for a PDF download', async () => {
    const fetchMock = vi.fn(async () => new Response(new Blob(['%PDF-1.4']), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    const blob = await requestBlob('/envelopes/env-1/documents/final')

    expect(blob.size).toBeGreaterThan(0)
  })

  it('reports a network error in a readable way', async () => {
    const fetchMock = vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(request('/envelopes')).rejects.toThrowError(/server could not be reached/i)
  })
})
