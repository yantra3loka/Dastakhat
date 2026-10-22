import { expect, test } from '@playwright/test'

/**
 * The public signing page. It never asks for a login.
 */

test('shows a friendly error for an unknown signing token', async ({ page }) => {
  await page.goto('/sign/this-token-does-not-exist-at-all-0000000000')

  await expect(page.getByRole('heading', { name: 'This link is not valid' })).toBeVisible()
  await expect(page.getByText(/No document is shown on this page/i)).toBeVisible()
  await expect(page.locator('canvas')).toHaveCount(0)
})

test('does not ask the signer to log in', async ({ page }) => {
  await page.goto('/sign/this-token-does-not-exist-at-all-0000000000')
  await expect(page.getByLabel('E-mail address')).toHaveCount(0)
  await expect(page.getByLabel('Password')).toHaveCount(0)
})

test('shows an error for an unknown download token', async ({ page }) => {
  await page.goto('/download/this-token-does-not-exist-at-all-00000000')
  await expect(page.getByRole('heading', { name: /cannot be downloaded/i })).toBeVisible()
})
