import { expect, test } from '@playwright/test'
import { MOCK_ESP, createDraft, latestSigningLink, login } from './support'

/**
 * Signing with the MOCK Aadhaar eSign provider (matrix D).
 *
 * What is mocked: the eSign provider itself. The `mock-esp` service
 * (docker compose, or `cd mock-esp && mvn spring-boot:run`) simulates a CCA
 * style eSign gateway: it never contacts UIDAI, never asks for a real
 * Aadhaar number and signs the document hash with a certificate of the TEST
 * MOCK CA. Its consent page shows the fixed test OTP 123456. No real signing
 * credential is used anywhere in this test.
 *
 * The test skips itself when the provider is not running:
 *
 *   docker compose up -d db mailpit mock-esp
 */

test('signs a document with the MOCK eSign provider', async ({ page, request }) => {
  const health = await request.get(`${MOCK_ESP}/esp/health`).catch(() => null)
  test.skip(
    health === null || !health.ok(),
    `the MOCK eSign provider is not reachable on ${MOCK_ESP} - start it with: docker compose up -d mock-esp`,
  )

  const stamp = Date.now()
  const recipientEmail = `esign-${stamp}@example.test`

  await login(page)

  // 1..5: upload, recipient with the MOCK method, field, send.
  await createDraft(page, 'e2e/fixtures/offer-letter.pdf', `MOCK eSign ${stamp}`)
  await page.getByRole('button', { name: 'Add' }).click()
  await page.getByLabel('Name').fill('Meera eSign')
  await page.getByLabel('E-mail address').fill(recipientEmail)
  await page.getByLabel('Signing method').selectOption('esign_mock')
  await page.getByRole('button', { name: 'Signature', exact: true }).click()
  await page.locator('.pdf-canvas').first().click({ position: { x: 120, y: 140 } })
  await expect(page.locator('.field-overlay').first()).toBeVisible()
  await page.getByRole('button', { name: 'Save draft' }).click()
  await page.getByRole('button', { name: 'Review & send' }).click()
  await expect(page).toHaveURL(/\/documents\/[0-9a-f-]+\/review/)
  await page.getByRole('button', { name: /Send for signature/i }).click()
  await expect(page).toHaveURL(/\/documents\/[0-9a-f-]+$/)

  // 6: the signer opens the signing link from Mailpit.
  const link = await latestSigningLink(request, recipientEmail)
  const signer = await page.context().newPage()
  await signer.goto(link)
  await expect(signer.getByText('Meera eSign').first()).toBeVisible()

  // 7: consent, then start the MOCK provider flow.
  await signer.getByRole('checkbox').first().check()
  await signer.getByRole('button', { name: /Sign with Aadhaar eSign \(MOCK\)/i }).click()

  const waiting = signer.getByRole('status').filter({ hasText: /transaction/i })
  await expect(waiting).toBeVisible()
  const txn = (await waiting.innerText()).match(/transaction\s+([A-Za-z0-9_-]+)/)?.[1]
  expect(txn, 'the MOCK panel did not show a transaction id').toBeTruthy()

  // 8: the provider answers with its consent page (fixed test OTP).
  const provider = await page.context().newPage()
  await provider.goto(`${MOCK_ESP}/esp/otp/${txn}`)
  await expect(provider.getByRole('heading', { name: /MOCK eSign - consent/ })).toBeVisible()
  await provider.getByLabel('Enter the test OTP').fill('123456')
  await provider.getByRole('button', { name: /Sign the document \(MOCK\)/i }).click()
  await expect(provider.locator('body')).toContainText('"status":"signed"')

  // 9: the panel notices the recorded signature and the signer sees the
  //    completion state of the application (poll every two seconds).
  await expect(
    signer.getByRole('heading', { level: 1, name: 'You have signed the document' }),
  ).toBeVisible({ timeout: 30_000 })
  await provider.close()
  await signer.close()
})
