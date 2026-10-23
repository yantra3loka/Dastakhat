import { expect, test } from '@playwright/test'
import { login } from './support'

/**
 * The upload validation of the new document form (matrix C).
 *
 * The fixtures in e2e/fixtures are tiny hand written PDFs:
 *
 *   offer-letter.pdf         valid, one empty page
 *   javascript.pdf           JavaScript OpenAction
 *   launch-action.pdf        Launch OpenAction (starts an application)
 *   embedded-attachment.pdf  embedded file attachment "Content Credentials"
 *
 * The backend validates every upload: the active content is rejected with
 * HTTP 422 (PDF validator), the 10 MB limit with HTTP 413 (Spring multipart).
 * The PDF security checks are NOT relaxed for these tests.
 */

test.beforeEach(async ({ page }) => {
  await login(page)
})

test('accepts a valid PDF and opens the preparation', async ({ page }) => {
  await page.goto('/documents/new')
  await page.setInputFiles('input[type="file"]', 'e2e/fixtures/offer-letter.pdf')
  await expect(page.getByText('offer-letter.pdf')).toBeVisible()
  await page.getByLabel('Document name').fill(`Valid upload ${Date.now()}`)
  await page.getByRole('button', { name: 'Next' }).click()
  await expect(page).toHaveURL(/\/documents\/[0-9a-f-]+\/prepare/)
  await expect(page.getByRole('button', { name: 'Add' })).toBeVisible()
})

test('rejects a file that is larger than the 10 MB limit', async ({ page }) => {
  await page.goto('/documents/new')
  // A PDF that only pretends to be valid: the header plus filler over 10 MB.
  const oversized = Buffer.concat([Buffer.from('%PDF-1.4\n'), Buffer.alloc(11 * 1024 * 1024, 0x20)])
  await page.setInputFiles('input[type="file"]', {
    name: 'oversized.pdf',
    mimeType: 'application/pdf',
    buffer: oversized,
  })
  await page.getByRole('button', { name: 'Next' }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  await expect(alert).toContainText(/10 MB limit/i)
  await expect(page).toHaveURL(/\/documents\/new/)
})

test('rejects a PDF that contains JavaScript', async ({ page }) => {
  await page.goto('/documents/new')
  await page.setInputFiles('input[type="file"]', 'e2e/fixtures/javascript.pdf')
  await page.getByRole('button', { name: 'Next' }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  await expect(alert).toContainText(/active content/i)
  await expect(alert).toContainText(/javascript/i)
  await expect(page).toHaveURL(/\/documents\/new/)
})

test('rejects a PDF with a launch action', async ({ page }) => {
  await page.goto('/documents/new')
  await page.setInputFiles('input[type="file"]', 'e2e/fixtures/launch-action.pdf')
  await page.getByRole('button', { name: 'Next' }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  await expect(alert).toContainText(/active content/i)
  await expect(alert).toContainText(/launch/i)
  await expect(page).toHaveURL(/\/documents\/new/)
})

test('rejects a PDF with an embedded attachment and names it', async ({ page }) => {
  await page.goto('/documents/new')
  await page.setInputFiles('input[type="file"]', 'e2e/fixtures/embedded-attachment.pdf')
  await page.getByRole('button', { name: 'Next' }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  await expect(alert).toContainText(/active content/i)
  // The message names the attachment so the sender knows what to remove.
  await expect(alert).toContainText(/Content Credentials/i)
  await expect(page).toHaveURL(/\/documents\/new/)
})
