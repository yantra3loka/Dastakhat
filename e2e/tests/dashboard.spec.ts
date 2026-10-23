import { expect, test } from '@playwright/test'
import { login, logout } from './support'

/**
 * The dashboard, the navigation and the logout of the sender
 * (matrix B, plus the logout of matrix A).
 */

test('shows the dashboard of the sender after the login', async ({ page }) => {
  await login(page)

  await expect(page.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()

  // The statistics of the sender.
  await expect(page.getByText('Total')).toBeVisible()
  await expect(page.getByText('Action needed')).toBeVisible()
  await expect(page.getByText('Pending')).toBeVisible()
  await expect(page.getByText('Completed')).toBeVisible()
  await expect(page.getByText('Drafts')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Recent documents' })).toBeVisible()

  // The signed in sender is visible in the shell.
  await expect(page.getByText('Dastakhat Sender').first()).toBeVisible()
  await expect(page.getByText('sender@example.test').first()).toBeVisible()
})

test('navigates between the pages with the main navigation', async ({ page }) => {
  await login(page)
  const nav = page.getByRole('navigation')

  await nav.getByRole('link', { name: 'Documents' }).click()
  await expect(page).toHaveURL(/\/documents$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Documents' })).toBeVisible()

  await nav.getByRole('link', { name: 'New document' }).click()
  await expect(page).toHaveURL(/\/documents\/new/)
  await expect(page.getByRole('heading', { level: 1, name: 'New document' })).toBeVisible()

  await nav.getByRole('link', { name: 'Dashboard' }).click()
  await expect(page).toHaveURL(/\/dashboard/)
  await expect(page.getByRole('heading', { level: 1, name: 'Dashboard' })).toBeVisible()
})

test('logs out and the protected pages require a login again', async ({ page }) => {
  await login(page)

  // The sidebar and the topbar both offer a logout - the first one is used.
  await logout(page)
  await expect(page.getByLabel('Password')).toBeVisible()

  // A protected address redirects to the login after the logout.
  await page.goto('/documents')
  await expect(page).toHaveURL(/\/login/)
})
