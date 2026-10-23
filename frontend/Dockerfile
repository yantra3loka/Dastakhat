# ---------------------------------------------------------------------------
# The Vue frontend. The dev server proxies /api to the backend so that the
# browser only ever talks to one origin (no CORS in development).
# ---------------------------------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY frontend ./
RUN npm run build

FROM node:22-alpine
WORKDIR /app
COPY --from=build /app/dist ./dist
COPY frontend/vite.config.ts ./vite.config.ts
RUN npm install --no-audit --no-fund --omit=dev vite
EXPOSE 5173
CMD ["npx", "vite", "preview", "--host", "0.0.0.0", "--port", "5173"]
