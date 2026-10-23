import { expect, test } from '@playwright/test'
import { login } from './support'

/**
 * The document list of the sender.
 */

test.beforeEach(async ({ page }) => {
  await login(page)
})

test('lists the envelopes of the sender', async ({ page }) => {
  await page.goto('/documents')
  await expect(page.getByRole('heading', { name: 'Documents' })).toBeVisible()
  await expect(page.getByPlaceholder(/Search by name or recipient/i)).toBeVisible()
  await expect(page.getByRole('button', { name: 'All' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Waiting' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Completed' })).toBeVisible()
})

test('filters the list by the status', async ({ page }) => {
  await page.goto('/documents')
  await page.getByRole('button', { name: 'Draft' }).click()
  await expect(page.getByRole('button', { name: 'Draft' })).toHaveAttribute('aria-pressed', 'true')
})

test('offers the upload of a document', async ({ page }) => {
  await page.goto('/documents/new')
  await expect(page.getByRole('heading', { name: 'New document' })).toBeVisible()
  await expect(page.getByLabel('Document name')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Next' })).toBeDisabled()
})

test('rejects a file that is not a PDF', async ({ page }) => {
  await page.goto('/documents/new')
  await page.setInputFiles('input[type="file"]', {
    name: 'notes.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('this is not a pdf'),
  })
  await expect(page.getByRole('alert')).toContainText(/pdf/i)
})
