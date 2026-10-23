/**
 * `Origin` normalisation for the `/api` dev/preview proxy.
 *
 * The browser and the dev server always talk to a single origin - either
 * `http://localhost:5173` locally or, through a Cloudflare quick tunnel, some
 * `https://<random>.trycloudflare.com`. The Vite proxy forwards `/api` to
 * Spring Boot on `localhost:8080`, so on the backend side the very same
 * request *looks* cross-origin: the `Origin` header still carries the public
 * origin the browser sent while `Host` becomes `localhost:8080`. Spring Boot
 * validates `Origin` against the `dastakhat.cors-origins` allow list (which
 * can only hold stable localhost origins during development) and answers
 * `403 Invalid CORS request` - even though the browser considered the request
 * same-origin (`Sec-Fetch-Site: same-origin`, no preflight).
 *
 * These helpers rewrite the forwarded `Origin` **only** for requests the
 * browser itself marked same origin:
 *
 * - the `Origin` authority equals the `Host` the browser addressed, or
 * - `Sec-Fetch-Site: same-origin` (`Sec-Fetch-*` are forbidden headers that
 *   pages cannot forge; this also covers intermediaries that rewrite `Host`).
 *
 * Genuinely cross-origin requests keep their original `Origin` and are still
 * judged by the backend allow list, exactly as if they had hit the backend
 * directly - CORS stays enforced for every caller that is not the SPA itself.
 */

/** A request header as Node delivers it (repeated headers arrive as arrays). */
export type HeaderValue = string | readonly string[] | undefined

/** Matches the default of `dastakhat.cors-origins` in `application.yml`. */
export const DEFAULT_PRESENTATION_ORIGIN = 'http://localhost:5173'

/**
 * The origin the proxy presents to the backend: the first entry of the
 * configured allow list (`DASTAKHAT_CORS_ORIGINS`, same variable the backend
 * reads), falling back to the application default. Taking the value from the
 * same configuration guarantees the presented origin is allow listed.
 */
export function presentationOrigin(configured: string | undefined): string {
  const first = configured?.split(',')[0]?.trim()
  return first && first.length > 0 ? first : DEFAULT_PRESENTATION_ORIGIN
}

/**
 * The `Origin` that should be forwarded to the backend for this request, or
 * `undefined` when the header must be left exactly as the browser sent it.
 *
 * @param origin     the `Origin` header of the browser request
 * @param host       the `Host` header the browser addressed (before the
 *                   proxy rewrites it to the target)
 * @param presentation the origin that is allow listed by the backend
 * @param secFetchSite the `Sec-Fetch-Site` header, when the browser sent one
 */
export function normalizeProxiedOrigin(
  origin: HeaderValue,
  host: HeaderValue,
  presentation: string,
  secFetchSite?: HeaderValue,
): string | undefined {
  const originValue = singleValue(origin)
  const hostValue = singleValue(host)?.trim()
  if (!originValue || !hostValue) {
    // No provenance to reason about - leave the request untouched.
    return undefined
  }

  const schemeEnd = originValue.indexOf('://')
  if (schemeEnd < 0) {
    // `Origin: null` or a malformed value: never vouch for it, let the backend
    // keep rejecting it.
    return undefined
  }

  const originAuthority = originValue.slice(schemeEnd + 3).toLowerCase()
  if (originAuthority === hostValue.toLowerCase()) {
    // The browser addressed exactly this origin: the request is same-origin by
    // definition, the proxy only made it *look* cross-origin for Spring Boot.
    return presentation
  }

  if (singleValue(secFetchSite)?.toLowerCase() === 'same-origin') {
    // The browser vouches for a same-origin request (forbidden header, cannot
    // be forged by pages) while some intermediary rewrote `Host`.
    return presentation
  }

  // A caller from another origin: keep its real `Origin` so the backend's
  // allow list still sees who is asking.
  return undefined
}

/** First value of a possibly repeated header. */
function singleValue(value: HeaderValue): string | undefined {
  if (value === undefined) {
    return undefined
  }
  if (typeof value === 'string') {
    return value
  }
  return value[0]
}
