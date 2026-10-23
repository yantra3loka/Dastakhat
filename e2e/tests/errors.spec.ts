import { expect, test } from '@playwright/test'
import { login } from './support'

/**
 * Error handling of the SPA (matrix E): the not found page, a clean browser
 * console without uncaught JavaScript errors, no answer with a server error
 * (5xx) during a normal session, and the loading and error states of the
 * signing page.
 */

test('shows the not found page for an unknown address', async ({ page }) => {
  await page.goto('/this-address-does-not-exist')
  await expect(page.getByRole('heading', { level: 1, name: 'This page does not exist' })).toBeVisible()
  await expect(page).toHaveTitle(/Page not found/)
  // The way back into the application is offered.
  await expect(page.getByRole('link', { name: 'Back to the dashboard' })).toBeVisible()
})

test('stays free of console errors and server errors during a session', async ({ page }) => {
  const consoleErrors: string[] = []
  const pageErrors: string[] = []
  const serverErrors: string[] = []

  page.on('console', (message) => {
    const text = message.text()
    // The favicon of the dev server is not part of the application under test.
    if (message.type() === 'error' && !text.includes('favicon')) consoleErrors.push(text)
  })
  page.on('pageerror', (error) => pageErrors.push(String(error)))
  page.on('response', (response) => {
    if (response.status() >= 500) serverErrors.push(`${response.status()} ${response.url()}`)
  })

  await login(page)
  await expect(page.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()

  await page.goto('/documents')
  await expect(page.getByRole('heading', { level: 1, name: 'Documents' })).toBeVisible()

  await page.goto('/documents/new')
  await expect(page.getByRole('heading', { level: 1, name: 'New document' })).toBeVisible()

  expect(serverErrors, 'no request may be answered with a server error').toEqual([])
  expect(pageErrors, 'no uncaught JavaScript error may happen').toEqual([])
  expect(consoleErrors, 'the browser console must stay clean').toEqual([])
})

test('shows the loading state and a friendly error for a broken signing link', async ({ page }) => {
  // The API answer is slowed down so the (short lived) loading state of the
  // signing page stays observable.
  await page.route('**/api/v1/sign/**', async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 1_500))
    await route.continue()
  })

  await page.goto('/sign/not-a-real-signing-token-0000000000')
  await expect(page.getByRole('status')).toContainText(/Loading the document/i)

  // A user friendly error instead of a stack trace or an empty page.
  await expect(page.getByRole('heading', { level: 1, name: 'This link is not valid' })).toBeVisible()
  await expect(page.getByText(/The signing link does not exist/i)).toBeVisible()
  await expect(page).toHaveURL(/\/sign\//)
})
