import { expect, test } from '@playwright/test'

/**
 * The login of the sender.
 *
 * The seeded development account is `sender@example.test` / `ChangeMe!123`.
 */

test.beforeEach(async ({ page }) => {
  await page.goto('/login')
})

test('shows the login form with the development hint', async ({ page }) => {
  await expect(page.getByRole('heading', { name: 'Dastakhat' })).toBeVisible()
  await expect(page.getByLabel('E-mail address')).toBeVisible()
  await expect(page.getByLabel('Password')).toBeVisible()
  await expect(page.getByText('sender@example.test').first()).toBeVisible()
})

test('rejects a wrong password with an inline error', async ({ page }) => {
  await page.getByLabel('E-mail address').fill('sender@example.test')
  await page.getByLabel('Password').fill('this-is-wrong')
  await page.getByRole('button', { name: 'Sign in' }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  await expect(alert).toContainText(/wrong|incorrect|failed/i)
  await expect(page).toHaveURL(/\/login/)
})

test('signs the sender in and opens the dashboard', async ({ page }) => {
  await page.getByLabel('E-mail address').fill('sender@example.test')
  await page.getByLabel('Password').fill('ChangeMe!123')
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page).toHaveURL(/\/dashboard/)
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
  await expect(page.getByText('Total')).toBeVisible()
  await expect(page.getByText('Action needed')).toBeVisible()
  await expect(page.getByText('Pending')).toBeVisible()
  await expect(page.getByText('Completed')).toBeVisible()
  await expect(page.getByText('Drafts')).toBeVisible()
})

test('protects the sender pages', async ({ page }) => {
  await page.goto('/documents')
  await expect(page).toHaveURL(/\/login/)
})
