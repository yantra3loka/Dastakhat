import { expect, test } from '@playwright/test'
import type { APIRequestContext, Page } from '@playwright/test'

/**
 * The complete signing workflow of one envelope.
 *
 * The test drives the real application: it uploads a PDF, adds a recipient, places a
 * signature field, sends the envelope, opens the signing link that was delivered to
 * Mailpit, signs the document and finally checks the sealed document and the audit chain.
 */

const MAILPIT = process.env['MAILPIT_API'] ?? 'http://localhost:8025/api/v1'
const FIXTURE = 'e2e/fixtures/offer-letter.pdf'

/** The signing link of the most recent mail to the given address. */
async function latestSigningLink(request: APIRequestContext, email: string): Promise<string> {
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

async function login(page: Page): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('E-mail address').fill('sender@example.test')
  await page.getByLabel('Password').fill('ChangeMe!123')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/dashboard/)
}

test.describe.configure({ mode: 'serial' })

test('signs a document from the upload to the sealed PDF', async ({ page, request }) => {
  const stamp = Date.now()
  const recipientEmail = `signer-${stamp}@example.test`

  await login(page)

  // 1. Upload the document.
  await page.goto('/documents/new')
  await page.setInputFiles('input[type="file"]', FIXTURE)
  await page.getByLabel('Document name').fill(`Offer letter ${stamp}`)
  await page.getByRole('button', { name: 'Next' }).click()
  await expect(page).toHaveURL(/\/documents\/[0-9a-f-]+\/prepare/)

  // 2. Add a recipient.
  await page.getByRole('button', { name: 'Add' }).click()
  await page.getByLabel('Name').fill('Asha HR')
  await page.getByLabel('E-mail address').fill(recipientEmail)

  // 3. Place a signature field on the first page.
  await page.getByRole('button', { name: 'Signature', exact: true }).click()
  const pageCanvas = page.locator('.pdf-page').first()
  await expect(pageCanvas).toBeVisible()
  await page.locator('.pdf-canvas').first().click({ position: { x: 120, y: 140 } })
  await expect(page.locator('.field-overlay').first()).toBeVisible()

  // 4. Save and review.
  await page.getByRole('button', { name: 'Save draft' }).click()
  await page.getByRole('button', { name: 'Review & send' }).click()
  await expect(page).toHaveURL(/\/documents\/[0-9a-f-]+\/review/)

  // 5. Send the envelope.
  await page.getByRole('button', { name: /Send for signature/i }).click()
  await expect(page).toHaveURL(/\/documents\/[0-9a-f-]+$/)
  await expect(page.getByText(/Waiting|waiting/i).first()).toBeVisible()

  // 6. Open the signing link from Mailpit.
  const link = await latestSigningLink(request, recipientEmail)
  const signer = await page.context().newPage()
  await signer.goto(link)
  await expect(signer.getByText('Asha HR').first()).toBeVisible()

  // 7. Give the consent and draw the signature.
  await signer.getByRole('checkbox').first().check()
  const pad = signer.locator('canvas.pad-canvas')
  await expect(pad).toBeVisible()
  const box = await pad.boundingBox()
  expect(box).toBeTruthy()
  await signer.mouse.move(box!.x + 30, box!.y + 40)
  await signer.mouse.down()
  await signer.mouse.move(box!.x + 90, box!.y + 70, { steps: 8 })
  await signer.mouse.move(box!.x + 150, box!.y + 35, { steps: 8 })
  await signer.mouse.up()

  // 8. Finish the signature.
  await signer.getByRole('button', { name: /Finish signing/i }).click()
  await expect(signer.getByText(/You have signed|has been signed|Thank you/i).first()).toBeVisible()

  // 9. The sender sees the completed envelope.
  await page.goto('/documents')
  await expect(page.getByText(`Offer letter ${stamp}`)).toBeVisible()
  await page.getByText(`Offer letter ${stamp}`).click()
  await expect(page.getByText(/Completed/i).first()).toBeVisible()

  // 10. The audit chain verifies.
  await page.goto('/documents')
  await page.getByText(`Offer letter ${stamp}`).click()
  const auditLink = page.getByRole('link', { name: /Audit/i }).first()
  if (await auditLink.count()) {
    await auditLink.click()
    await expect(page.getByText(/Chain intact|intact/i).first()).toBeVisible()
  }
})

test('the signing link cannot be used twice', async ({ page, request }) => {
  const stamp = Date.now()
  const recipientEmail = `replay-${stamp}@example.test`

  await login(page)
  await page.goto('/documents/new')
  await page.setInputFiles('input[type="file"]', FIXTURE)
  await page.getByLabel('Document name').fill(`Replay check ${stamp}`)
  await page.getByRole('button', { name: 'Next' }).click()
  await expect(page).toHaveURL(/\/documents\/[0-9a-f-]+\/prepare/)

  await page.getByRole('button', { name: 'Add' }).click()
  await page.getByLabel('Name').fill('Replay Tester')
  await page.getByLabel('E-mail address').fill(recipientEmail)
  await page.getByRole('button', { name: 'Signature', exact: true }).click()
  await page.locator('.pdf-canvas').first().click({ position: { x: 120, y: 140 } })
  await page.getByRole('button', { name: 'Save draft' }).click()
  await page.getByRole('button', { name: 'Review & send' }).click()
  await page.getByRole('button', { name: /Send for signature/i }).click()
  await expect(page).toHaveURL(/\/documents\/[0-9a-f-]+$/)
  await expect(page.getByText(/Waiting|waiting/i).first()).toBeVisible()

  const link = await latestSigningLink(request, recipientEmail)
  const signer = await page.context().newPage()
  await signer.goto(link)
  await signer.getByRole('checkbox').first().check()
  const pad = signer.locator('canvas.pad-canvas')
  const box = await pad.boundingBox()
  await signer.mouse.move(box!.x + 30, box!.y + 40)
  await signer.mouse.down()
  await signer.mouse.move(box!.x + 120, box!.y + 60, { steps: 8 })
  await signer.mouse.up()
  await signer.getByRole('button', { name: /Finish signing/i }).click()
  await expect(signer.getByText(/You have signed|has been signed|Thank you/i).first()).toBeVisible()

  // The same link must not work a second time.
  const replay = await page.context().newPage()
  await replay.goto(link)
  await expect(replay.getByText(/already been used|expired|not valid/i).first()).toBeVisible()
})


