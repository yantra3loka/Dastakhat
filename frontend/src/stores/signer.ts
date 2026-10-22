import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ApiError } from '@/api/client'
import * as signerApi from '@/api/signer'
import type { CompleteResponse, FieldValue, SignerView } from '@/api/types'
import { formatDateTimeIst } from '@/utils/format'
import { isPngDataUrl } from '@/utils/signature'

/** The reason why the signing page cannot be shown. */
export type SignerErrorKind = 'unknown-token' | 'gone' | 'not-your-turn' | 'failed'

/** The state of the public signing page. */
export const useSignerStore = defineStore('signer', () => {
  const token = ref<string>('')
  const view = ref<SignerView | null>(null)
  const loading = ref(false)
  const errorKind = ref<SignerErrorKind | null>(null)
  const errorMessage = ref<string>('')

  const consent = ref(false)
  const consentRecorded = ref(false)
  const signatureImage = ref<string | null>(null)
  const signatureMode = ref<'draw' | 'type'>('draw')
  const typedName = ref('')
  const values = ref<Record<string, string>>({})

  const submitting = ref(false)
  const completed = ref(false)
  const completedResponse = ref<CompleteResponse | null>(null)
  const declined = ref(false)

  const isMock = computed(() => view.value?.method === 'esign_mock' || view.value?.mock === true)
  const hasSignatureField = computed(() =>
    (view.value?.fields ?? []).some((field) => field.type === 'signature' || field.type === 'initials'),
  )
  const canSubmit = computed(
    () => consent.value && !submitting.value && (signatureImage.value !== null || !hasSignatureField.value),
  )

  function reset(): void {
    token.value = ''
    view.value = null
    loading.value = false
    errorKind.value = null
    errorMessage.value = ''
    consent.value = false
    consentRecorded.value = false
    signatureImage.value = null
    signatureMode.value = 'draw'
    typedName.value = ''
    values.value = {}
    submitting.value = false
    completed.value = false
    completedResponse.value = null
    declined.value = false
  }

  function classify(cause: unknown): { kind: SignerErrorKind; message: string } {
    if (cause instanceof ApiError) {
      if (cause.status === 404) {
        return { kind: 'unknown-token', message: 'This signing link is not valid.' }
      }
      if (cause.status === 410) {
        return { kind: 'gone', message: cause.message }
      }
      if (cause.status === 425) {
        return { kind: 'not-your-turn', message: cause.message }
      }
      return { kind: 'failed', message: cause.message }
    }
    return { kind: 'failed', message: 'The signing page could not be loaded.' }
  }

  /** Loads the view model of the signer. */
  async function load(rawToken: string): Promise<void> {
    token.value = rawToken
    loading.value = true
    errorKind.value = null
    errorMessage.value = ''
    try {
      const result = await signerApi.getSignerView(rawToken)
      view.value = result
      consentRecorded.value = result.consentRecorded === true
      typedName.value = result.recipientName ?? ''
      const initial: Record<string, string> = {}
      for (const field of result.fields ?? []) {
        if (!field.id) continue
        if (field.type === 'date') {
          initial[field.id] = formatDateTimeIst(new Date().toISOString()).replace(' IST', '')
        } else if (field.type === 'name') {
          initial[field.id] = result.recipientName ?? ''
        } else {
          initial[field.id] = field.value ?? ''
        }
      }
      values.value = initial
    } catch (cause) {
      const classified = classify(cause)
      errorKind.value = classified.kind
      errorMessage.value = classified.message
    } finally {
      loading.value = false
    }
  }

  /** Records the consent of the signer on the backend. */
  async function recordConsent(): Promise<boolean> {
    if (!token.value) return false
    submitting.value = true
    try {
      await signerApi.postConsent(token.value, true, view.value?.legalNotice)
      consentRecorded.value = true
      return true
    } catch (cause) {
      const classified = classify(cause)
      errorMessage.value = classified.message
      errorKind.value = classified.kind === 'failed' ? null : classified.kind
      return false
    } finally {
      submitting.value = false
    }
  }

  function setSignatureImage(dataUrl: string | null): void {
    signatureImage.value = dataUrl && isPngDataUrl(dataUrl) ? dataUrl : null
  }

  function setFieldValue(fieldId: string, value: string): void {
    values.value = { ...values.value, [fieldId]: value }
  }

  /** The values that are sent to the backend. */
  function buildFieldValues(): FieldValue[] {
    const payload: FieldValue[] = []
    for (const field of view.value?.fields ?? []) {
      if (!field.id) continue
      const value = values.value[field.id] ?? ''
      if (field.type === 'signature' || field.type === 'initials') {
        payload.push({
          fieldId: field.id,
          value: field.type === 'initials' ? value : undefined,
          signatureImageBase64: signatureImage.value ?? undefined,
        })
      } else {
        payload.push({ fieldId: field.id, value })
      }
    }
    return payload
  }

  /** Sends the signature and the field values to the backend. */
  async function complete(): Promise<boolean> {
    if (!token.value) return false
    submitting.value = true
    errorMessage.value = ''
    try {
      const response = await signerApi.completeSigning(token.value, {
        consent: true,
        fields: buildFieldValues(),
      })
      completedResponse.value = response
      completed.value = true
      return true
    } catch (cause) {
      const classified = classify(cause)
      errorMessage.value = classified.message
      return false
    } finally {
      submitting.value = false
    }
  }

  /** Declines to sign. */
  async function decline(reason: string): Promise<boolean> {
    if (!token.value) return false
    submitting.value = true
    errorMessage.value = ''
    try {
      await signerApi.declineSigning(token.value, reason)
      declined.value = true
      return true
    } catch (cause) {
      const classified = classify(cause)
      errorMessage.value = classified.message
      return false
    } finally {
      submitting.value = false
    }
  }

  return {
    token,
    view,
    loading,
    errorKind,
    errorMessage,
    consent,
    consentRecorded,
    signatureImage,
    signatureMode,
    typedName,
    values,
    submitting,
    completed,
    completedResponse,
    declined,
    isMock,
    hasSignatureField,
    canSubmit,
    reset,
    load,
    recordConsent,
    setSignatureImage,
    setFieldValue,
    buildFieldValues,
    complete,
    decline,
  }
})
