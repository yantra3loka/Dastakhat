import { expect } from '@playwright/test'
import type { APIRequestContext, BrowserContext, Page } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'

/**
 * Shared helpers of the Dastakhat end to end tests.
 *
 * Everything here talks to the local development stack only: the seeded
 * development sender and Mailpit, the test mail box of docker compose.
 * Every run creates its own envelopes with a unique stamp, so the specs may
 * run repeatedly without depending on previous runs.
 */

/** The seeded development account of the local stack. */
export const SEED_EMAIL = 'sender@example.test'
export const SEED_PASSWORD = 'ChangeMe!123'

/** The REST API of Mailpit - every signing link of a test run lands there. */
export const MAILPIT = process.env['MAILPIT_API'] ?? 'http://localhost:8025/api/v1'

/** The base URL of the MOCK eSign provider (service `mock-esp`). */
export const MOCK_ESP = process.env['MOCK_ESP_URL'] ?? 'http://localhost:9100'

type SessionState = Awaited<ReturnType<BrowserContext['storageState']>>

/**
 * One saved session of the seeded sender.
 *
 * The backend limits sign in attempts (AuthService: 10 attempts per 5 minutes
 * and IP) - correct behaviour that must not be weakened for the tests. A
 * suite that signs in once per test would trip that limit, so the suite signs
 * in once and reuses the session for every spec that does not test the login
 * itself (e2e/tests/login.spec.ts always signs in for real).
 *
 * The file is validated with `GET /auth/me` before it is reused: when the
 * session has expired the helper signs in again, so no test ever depends on
 * a previous run.
 */
const SESSION_FILE = path.resolve('e2e', '.auth', 'session.json')

async function reuseSession(page: Page): Promise<boolean> {
  if (!fs.existsSync(SESSION_FILE)) return false
  try {
    const state = JSON.parse(fs.readFileSync(SESSION_FILE, 'utf8')) as SessionState
    if (state.cookies.length === 0) return false
    await page.context().addCookies(state.cookies)
    // The session probe - it does not count against the sign in limit.
    const me = await page.request.get('/api/v1/auth/me')
    if (me.ok()) {
      await page.goto('/dashboard')
      await expect(page.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()
      return true
    }
    await page.context().clearCookies()
    fs.rmSync(SESSION_FILE, { force: true })
    return false
  } catch {
    // A stale or damaged file - fall through to a real sign in.
    fs.rmSync(SESSION_FILE, { force: true })
    return false
  }
}

async function saveSession(page: Page): Promise<void> {
  const state = await page.context().storageState()
  fs.mkdirSync(path.dirname(SESSION_FILE), { recursive: true })
  fs.writeFileSync(SESSION_FILE, JSON.stringify(state))
}

/** Signs the seeded sender in (or restores the saved session) and waits for the dashboard. */
export async function login(page: Page): Promise<void> {
  if (await reuseSession(page)) return

  await page.goto('/login')
  await page.getByLabel('E-mail address').fill(SEED_EMAIL)
  await page.getByLabel('Password').fill(SEED_PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/dashboard/)
  await saveSession(page)
}

/**
 * Signs the sender out through the UI (the first visible logout button) and
 * drops the saved session - the session of the sender is invalidated by the
 * server, so the next `login` has to sign in again.
 */
export async function logout(page: Page): Promise<void> {
  fs.rmSync(SESSION_FILE, { force: true })
  await page.getByRole('button', { name: 'Log out' }).first().click()
  await expect(page).toHaveURL(/\/login/)
}

/** The signing link of the most recent mail to the given address. */
export async function latestSigningLink(request: APIRequestContext, email: string): Promise<string> {
  const list = await (await request.get(`${MAILPIT}/messages?limit=50`)).json()
  const message = list.messages.find((entry: { To: { Address: string }[] }) =>
    entry.To.some((to) => to.Address.toLowerCase() === email.toLowerCase()),
  )
  expect(message, `no mail was sent to ${email}`).toBeTruthy()
  const full = await (await request.get(`${MAILPIT}/message/${message.ID}`)).json()
  const match = full.Text.match(/https?:\/\/[^\s"<>]+\/sign\/([A-Za-z0-9_-]+)/)
  expect(match, 'the mail does not contain a signing link').toBeTruthy()
  return match![0]
}

/**
 * Uploads a PDF through the new document form and waits for the prepare page.
 *
 * `fixture` is a path relative to the repository root (the specs run there).
 */
export async function createDraft(page: Page, fixture: string, title: string): Promise<void> {
  await page.goto('/documents/new')
  await page.setInputFiles('input[type="file"]', fixture)
  await page.getByLabel('Document name').fill(title)
  await page.getByRole('button', { name: 'Next' }).click()
  await expect(page).toHaveURL(/\/documents\/[0-9a-f-]+\/prepare/)
}
