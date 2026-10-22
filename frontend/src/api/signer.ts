import { request, requestBlob } from './client'
import type {
  CompleteRequest,
  CompleteResponse,
  ConsentRequest,
  DscFinishResponse,
  DscPrepareResponse,
  EsignStartResponse,
  SignerView,
} from './types'

/**
 * The public signing endpoints. Signers never authenticate, they use the single
 * use token of the signing link.
 */

/** `GET /sign/{token}` - the view model of the signer. */
export async function getSignerView(token: string): Promise<SignerView> {
  return request<SignerView>(`/sign/${encodeURIComponent(token)}`, { auth: false })
}

/** `GET /sign/{token}/document` - the PDF the signer is allowed to see. */
export async function getSignerDocument(token: string): Promise<Blob> {
  return requestBlob(`/sign/${encodeURIComponent(token)}/document`, { auth: false })
}

/** `POST /sign/{token}/consent` - records the consent to sign electronically. */
export async function postConsent(token: string, consent: boolean, consentText?: string): Promise<void> {
  const body: ConsentRequest = { consent }
  if (consentText) body.consentText = consentText
  await request<void>(`/sign/${encodeURIComponent(token)}/consent`, {
    method: 'POST',
    body,
    auth: false,
  })
}

/** `POST /sign/{token}/complete` - submits the field values and the signature image. */
export async function completeSigning(token: string, body: CompleteRequest): Promise<CompleteResponse> {
  return request<CompleteResponse>(`/sign/${encodeURIComponent(token)}/complete`, {
    method: 'POST',
    body,
    auth: false,
  })
}

/** `POST /sign/{token}/decline` - declines to sign. */
export async function declineSigning(token: string, reason: string): Promise<void> {
  await request<void>(`/sign/${encodeURIComponent(token)}/decline`, {
    method: 'POST',
    body: { reason },
    auth: false,
  })
}

/** `POST /sign/{token}/dsc/prepare` - reserves the PDF signature for a DSC token. */
export async function dscPrepare(token: string, certificatePem: string): Promise<DscPrepareResponse> {
  return request<DscPrepareResponse>(`/sign/${encodeURIComponent(token)}/dsc/prepare`, {
    method: 'POST',
    body: { certificatePem },
    auth: false,
  })
}

/** `POST /sign/{token}/dsc/finish` - embeds the signature the token produced. */
export async function dscFinish(
  token: string,
  sessionId: string,
  signatureBase64: string,
): Promise<DscFinishResponse> {
  return request<DscFinishResponse>(`/sign/${encodeURIComponent(token)}/dsc/finish`, {
    method: 'POST',
    body: { sessionId, signatureBase64 },
    auth: false,
  })
}

/** `POST /sign/{token}/esign/start` - builds the signed MOCK eSign request. */
export async function esignStart(token: string): Promise<EsignStartResponse> {
  return request<EsignStartResponse>(`/sign/${encodeURIComponent(token)}/esign/start`, {
    method: 'POST',
    auth: false,
  })
}

/** `GET /download/{token}` - the final PDF of a completed envelope. */
export async function downloadByToken(token: string): Promise<Blob> {
  return requestBlob(`/download/${encodeURIComponent(token)}`, { auth: false })
}
