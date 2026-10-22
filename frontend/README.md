# Dastakhat frontend

The Vue 3 single page application of **Dastakhat**, a self hosted mini
e-signature platform. The backend is a Spring Boot service, its contract lives in
`../contract/openapi.yaml`.

## Stack

* Vue 3 (`<script setup lang="ts">`), TypeScript, Vite
* Pinia for the state, Vue Router 4/5 for the routing
* `pdfjs-dist` renders the PDF documents
* `lucide-vue-next` for the icons
* Vitest and `@vue/test-utils` for the unit tests
* Plain CSS - there is no UI framework and no Tailwind

## Commands

```bash
npm install        # install the dependencies
npm run dev        # dev server on http://localhost:5173
npm run build      # vue-tsc type check + production build into dist/
npm run typecheck  # only the type check
npm run test       # vitest run
npm run preview    # serve the production build
```

The dev server proxies `/api` to `http://localhost:8080`, so the SPA and the
API share the origin and the session cookie stays first party.

## Development account

The backend seeds a sender for local development:
`sender@example.test` / `ChangeMe!123`. The login page shows the hint, no other
credential is stored in the frontend.

## Structure

```
src/
  api/          typed fetch client, the API types and one module per endpoint group
  components/   the reusable building blocks (AppShell, PdfViewer, FieldOverlay, ...)
  router/       the routes and the authentication guard
  stores/       Pinia stores: auth, envelopes, signer, toast
  styles/       the design system (CSS variables and the base styles)
  types/        the shared UI types
  utils/        the coordinate helpers, the formatting and the signature helpers
  views/        one component per route
tests/          the unit tests (vitest)
```

## Notes

* A placed field is stored as a fraction (0..1) of the page with a **top left
  origin**, so the position survives a zoom, a resize and a reload. The
  conversion helpers live in `src/utils/coordinates.ts`.
* The CSRF token is read from the `XSRF-TOKEN` cookie or from the login
  response and is sent in the `X-CSRF-TOKEN` header with every state changing
  request.
* The **Aadhaar eSign** flow is a mock. Every screen of that flow shows an
  orange banner, it never contacts UIDAI and has no legal standing.
* A drawn or typed signature is **not** a cryptographic digital signature. The
  signing page shows that notice as well.
