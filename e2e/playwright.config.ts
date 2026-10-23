import { defineConfig, devices } from '@playwright/test'

/**
 * The end to end tests of Dastakhat.
 *
 * The specs drive the SPA in a browser. The backend has to run on
 * `http://localhost:8080`, the dev server proxies `/api` to it.
 *
 *   npx playwright test --config e2e/playwright.config.ts
 */
const frontendUrl = process.env['DASTAKHAT_FRONTEND_URL'] ?? 'http://localhost:5173'

export default defineConfig({
  testDir: './tests',
  // Relative paths are resolved against THIS config file (e2e/), so the
  // artifacts land in e2e/artifacts at the repository root - the folder the
  // CI uploads and `npm run test:e2e:report` opens.
  outputDir: 'artifacts/results',
  fullyParallel: false,
  forbidOnly: Boolean(process.env['CI']),
  retries: process.env['CI'] ? 1 : 0,
  workers: 1,
  // The HTML report (with the traces of failed tests) lands in
  // e2e/artifacts/html-report - open it with `npm run test:e2e:report`.
  // CI uploads the whole e2e/artifacts folder, see .github/workflows/ci.yml.
  reporter: [['list'], ['html', { outputFolder: 'artifacts/html-report', open: 'never' }]],
  timeout: 60_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: frontendUrl,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: process.env['DASTAKHAT_NO_WEBSERVER']
    ? undefined
    : {
        command: 'npm run dev',
        cwd: '../frontend',
        url: frontendUrl,
        reuseExistingServer: true,
        timeout: 120_000,
      },
})
