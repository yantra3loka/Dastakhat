import { describe, expect, it } from 'vitest'
import {
  DEFAULT_PRESENTATION_ORIGIN,
  normalizeProxiedOrigin,
  presentationOrigin,
} from '../proxyOrigin'

const TUNNEL_ORIGIN = 'https://organize-existed-sri-seem.trycloudflare.com'
const TUNNEL_HOST = 'organize-existed-sri-seem.trycloudflare.com'
const PRESENTATION = 'http://localhost:5173'

describe('presentationOrigin', () => {
  it('falls back to the backend default when nothing is configured', () => {
    expect(presentationOrigin(undefined)).toBe(DEFAULT_PRESENTATION_ORIGIN)
    expect(presentationOrigin('')).toBe(DEFAULT_PRESENTATION_ORIGIN)
    expect(presentationOrigin('   ')).toBe(DEFAULT_PRESENTATION_ORIGIN)
    expect(DEFAULT_PRESENTATION_ORIGIN).toBe('http://localhost:5173')
  })

  it('takes the first entry of the configured allow list', () => {
    expect(presentationOrigin('http://localhost:5173,http://127.0.0.1:5173')).toBe(
      'http://localhost:5173',
    )
    expect(presentationOrigin('https://sign.example.com, http://localhost:5173')).toBe(
      'https://sign.example.com',
    )
  })
})

describe('normalizeProxiedOrigin', () => {
  it('presents a same-origin tunnel request as the allow listed origin', () => {
    expect(normalizeProxiedOrigin(TUNNEL_ORIGIN, TUNNEL_HOST, PRESENTATION, 'same-origin')).toBe(
      PRESENTATION,
    )
  })

  it('matches on the Host the browser addressed when no Sec-Fetch-Site was sent', () => {
    expect(normalizeProxiedOrigin(TUNNEL_ORIGIN, TUNNEL_HOST, PRESENTATION)).toBe(PRESENTATION)
  })

  it('normalises the localhost dev origins', () => {
    expect(normalizeProxiedOrigin('http://localhost:5173', 'localhost:5173', PRESENTATION)).toBe(
      PRESENTATION,
    )
    expect(
      normalizeProxiedOrigin('http://127.0.0.1:5173', '127.0.0.1:5173', PRESENTATION, 'same-origin'),
    ).toBe(PRESENTATION)
  })

  it('normalises preview-server requests that the backend allow list cannot know', () => {
    expect(normalizeProxiedOrigin('http://localhost:4173', 'localhost:4173', PRESENTATION)).toBe(
      PRESENTATION,
    )
  })

  it('trusts Sec-Fetch-Site: same-origin when an intermediary rewrote the Host header', () => {
    expect(normalizeProxiedOrigin(TUNNEL_ORIGIN, 'localhost:5173', PRESENTATION, 'same-origin')).toBe(
      PRESENTATION,
    )
  })

  it('keeps cross-origin requests untouched so the backend allow list still applies', () => {
    // an unrelated site
    expect(
      normalizeProxiedOrigin('https://evil.example', TUNNEL_HOST, PRESENTATION, 'cross-site'),
    ).toBeUndefined()
    // another quick tunnel: same site, different origin
    expect(
      normalizeProxiedOrigin(
        'https://evil-tunnel.trycloudflare.com',
        TUNNEL_HOST,
        PRESENTATION,
        'same-site',
      ),
    ).toBeUndefined()
    // no browser signals at all
    expect(normalizeProxiedOrigin('https://evil.example', TUNNEL_HOST, PRESENTATION)).toBeUndefined()
    // a page on the dev port addressing the preview port: different origins
    expect(normalizeProxiedOrigin('http://localhost:4173', 'localhost:5173', PRESENTATION)).toBeUndefined()
  })

  it('never vouches for unusable origins', () => {
    expect(normalizeProxiedOrigin(undefined, TUNNEL_HOST, PRESENTATION, 'same-origin')).toBeUndefined()
    expect(normalizeProxiedOrigin('null', TUNNEL_HOST, PRESENTATION, 'same-origin')).toBeUndefined()
    expect(normalizeProxiedOrigin('', TUNNEL_HOST, PRESENTATION)).toBeUndefined()
    expect(normalizeProxiedOrigin(TUNNEL_ORIGIN, undefined, PRESENTATION, 'same-origin')).toBeUndefined()
    expect(normalizeProxiedOrigin(TUNNEL_ORIGIN, '   ', PRESENTATION)).toBeUndefined()
    expect(normalizeProxiedOrigin('not-an-origin', TUNNEL_HOST, PRESENTATION, 'same-origin')).toBeUndefined()
  })

  it('is case-insensitive for hosts and accepts repeated header values', () => {
    expect(
      normalizeProxiedOrigin('https://ABC.TryCloudflare.COM', 'abc.trycloudflare.com', PRESENTATION),
    ).toBe(PRESENTATION)
    expect(normalizeProxiedOrigin([TUNNEL_ORIGIN], [TUNNEL_HOST], PRESENTATION)).toBe(PRESENTATION)
    expect(normalizeProxiedOrigin(TUNNEL_ORIGIN, TUNNEL_HOST, PRESENTATION, ['same-origin'])).toBe(
      PRESENTATION,
    )
  })
})
