import { expect, test, type Page } from '@playwright/test'
import { login } from './support'

/**
 * The upload validation of the new document form (matrix C).
 *
 * The fixtures in e2e/fixtures are tiny hand written PDFs:
 *
 *   offer-letter.pdf          valid, one empty page
 *   javascript.pdf            JavaScript OpenAction
 *   launch-action.pdf         Launch OpenAction (starts an application)
 *   embedded-attachment.pdf   embedded executable "tool.exe"
 *   c2pa-credentials.pdf      C2PA "Content Credentials" provenance manifest (accepted)
 *
 * The backend validates every upload: the active content is rejected with
 * HTTP 422 (PDF validator), the 10 MB limit with HTTP 413 (Spring multipart).
 * The PDF security checks are NOT relaxed for these tests.
 */

test.beforeEach(async ({ page }) => {
  await login(page)
})

/**
 * The viewer has to paint the real page content, not just the white page frame.
 * An unpainted canvas stays at the browser default 300x150 and fully transparent
 * (regression: the render ran before the canvases existed and every page was blank).
 */
async function expectRenderedCanvases(page: Page): Promise<void> {
  await expect
    .poll(async () =>
      page.evaluate(() => {
        const canvases = [...document.querySelectorAll('canvas')]
        if (canvases.length === 0) return 'no canvas yet'
        const unpainted = canvases.filter((canvas) => {
          const context = canvas.getContext('2d')
          if (!context || canvas.width < 100) return true
          const pixels = context.getImageData(0, 0, 32, 32).data
          let opaque = 0
          for (let i = 3; i < pixels.length; i += 4) if (pixels[i] > 0) opaque += 1
          return opaque < 1000
        })
        return unpainted.length === 0 ? 'painted' : `${unpainted.length} canvas(es) not painted`
      }),
      { timeout: 10_000 },
    )
    .toBe('painted')
}

test('accepts a valid PDF and opens the preparation', async ({ page }) => {
  await page.goto('/documents/new')
  await page.setInputFiles('input[type="file"]', 'e2e/fixtures/offer-letter.pdf')
  await expect(page.getByText('offer-letter.pdf')).toBeVisible()
  await page.getByLabel('Document name').fill(`Valid upload ${Date.now()}`)
  await page.getByRole('button', { name: 'Next' }).click()
  await expect(page).toHaveURL(/\/documents\/[0-9a-f-]+\/prepare/)
  await expect(page.getByRole('button', { name: 'Add' })).toBeVisible()
  // The page canvas shows the actual PDF content, not a blank white frame.
  await expectRenderedCanvases(page)
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

test('rejects a PDF with a genuine embedded attachment and names it', async ({ page }) => {
  await page.goto('/documents/new')
  await page.setInputFiles('input[type="file"]', 'e2e/fixtures/embedded-attachment.pdf')
  await page.getByRole('button', { name: 'Next' }).click()

  const alert = page.getByRole('alert')
  await expect(alert).toBeVisible()
  await expect(alert).toContainText(/active content/i)
  // The message names the attachment so the sender knows what to remove.
  await expect(alert).toContainText(/tool\.exe/i)
  await expect(page).toHaveURL(/\/documents\/new/)
})

test('accepts a PDF that carries a C2PA Content Credentials manifest', async ({ page }) => {
  // Regression: Acrobat and other producers attach this provenance manifest to many
  // documents (often the larger, real-world ones). It is metadata, not active content,
  // so the upload has to pass validation and reach the preparation step.
  await page.goto('/documents/new')
  await page.setInputFiles('input[type="file"]', 'e2e/fixtures/c2pa-credentials.pdf')
  await page.getByLabel('Document name').fill(`C2PA manifest ${Date.now()}`)
  await page.getByRole('button', { name: 'Next' }).click()

  await expect(page).toHaveURL(/\/documents\/[0-9a-f-]+\/prepare/)
  await expect(page.getByRole('button', { name: 'Add' })).toBeVisible()
  // The manifest is metadata - the pages themselves still have to render.
  await expectRenderedCanvases(page)
})
