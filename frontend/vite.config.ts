import { fileURLToPath, URL } from 'node:url'
import type { IncomingMessage } from 'node:http'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { normalizeProxiedOrigin, presentationOrigin } from './proxyOrigin'

/**
 * Dastakhat frontend.
 *
 * The dev server proxies `/api` to the Spring Boot backend so that the SPA is
 * same origin with the API. That keeps the session cookie first party and
 * avoids CORS problems during development.
 */

/** Origin Spring Boot is told to expect from proxied same-origin requests. */
const presentedOrigin = presentationOrigin(process.env.DASTAKHAT_CORS_ORIGINS)

/**
 * `ProxyOptions.bypass` runs for every `/api` request *before* Vite forwards
 * it - returning nothing lets the proxy continue - which makes it the pre-
 * proxy hook at which the forwarded headers can still be normalised.
 *
 * A browser request to `/api` is same-origin by construction: the SPA is
 * served by this very server (`localhost:5173`, or a `*.trycloudflare.com`
 * quick tunnel in front of it). Spring Boot however sees `Host:
 * localhost:8080` next to the browser's public `Origin` and rejects the
 * request as a CORS violation (`403 Invalid CORS request`), because the
 * `dastakhat.cors-origins` allow list can only contain stable localhost
 * origins during development.
 *
 * Requests the browser itself marked same-origin therefore get the allow
 * listed presentation origin (the first `DASTAKHAT_CORS_ORIGINS` entry) when
 * they reach the backend. Requests from any other site keep their real
 * `Origin` header and are still judged by the backend allow list - CORS for
 * cross-origin callers stays exactly as strict as before.
 */
function presentSameOriginToBackend(req: IncomingMessage): void {
  const next = normalizeProxiedOrigin(
    req.headers.origin,
    req.headers.host,
    presentedOrigin,
    req.headers['sec-fetch-site'],
  )
  if (next !== undefined) {
    req.headers.origin = next
  }
}

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: false,
    // Vite rejects unknown Host headers (DNS-rebinding protection). A Cloudflare quick
    // tunnel reaches this dev server through a *.trycloudflare.com hostname, so only
    // that domain is opened up - every other host stays blocked.
    allowedHosts: ['.trycloudflare.com'],
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        bypass: presentSameOriginToBackend,
      },
    },
  },
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        bypass: presentSameOriginToBackend,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1200,
  },
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['tests/**/*.spec.ts'],
    restoreMocks: true,
  },
})
