<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, CircleAlert, FileText } from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
import BaseInput from '@/components/BaseInput.vue'
import FileDropzone from '@/components/FileDropzone.vue'
import { createDraft } from '@/api/envelopes'
import { ApiError } from '@/api/client'
import { useToastStore } from '@/stores/toast'
import { formatBytes } from '@/utils/format'

const router = useRouter()
const toast = useToastStore()

const file = ref<File | null>(null)
const title = ref('')
const titleTouched = ref(false)
const busy = ref(false)
const error = ref<string | null>(null)
const errorDetails = ref<string[]>([])

const titleError = computed(() => {
  if (!titleTouched.value) return null
  if (title.value.trim().length === 0) return 'Please enter a name for the document.'
  if (title.value.length > 200) return 'The name may not be longer than 200 characters.'
  return null
})

function onFiles(files: File[]): void {
  error.value = null
  errorDetails.value = []
  const selected = files[0]
  if (!selected) return
  if (!selected.name.toLowerCase().endsWith('.pdf') && selected.type !== 'application/pdf') {
    error.value = 'Only PDF files can be uploaded.'
    return
  }
  file.value = selected
  if (!titleTouched.value) title.value = selected.name.replace(/\.pdf$/i, '')
}

async function onNext(): Promise<void> {
  titleTouched.value = true
  error.value = null
  errorDetails.value = []
  if (!file.value) {
    error.value = 'Please choose a PDF file first.'
    return
  }
  if (titleError.value) return

  busy.value = true
  try {
    const envelope = await createDraft(file.value, title.value.trim())
    toast.success('The draft was created. Now add the recipients and the fields.')
    await router.push({ name: 'documents-prepare', params: { id: String(envelope.id) } })
  } catch (cause) {
    if (cause instanceof ApiError) {
      error.value = cause.message
      errorDetails.value = cause.fieldErrors
        .map((entry) => (entry.field ? `${entry.field}: ${entry.message ?? ''}` : (entry.message ?? '')))
        .filter((entry) => entry.length > 0)
      if (cause.status === 413) {
        error.value = 'The file is larger than the 10 MB limit of the server.'
      } else if (cause.status === 415) {
        error.value = 'The file is not a PDF. Please choose a PDF document.'
      } else if (cause.status === 422) {
        error.value = cause.message
      }
    } else {
      error.value = 'The document could not be uploaded.'
    }
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="page narrow">
    <header class="page-header">
      <div class="page-title-group">
        <h1>New document</h1>
        <p class="page-subtitle">Upload the PDF that should be signed.</p>
      </div>
      <BaseButton variant="secondary" :to="{ name: 'documents' }">
        <ArrowLeft :size="15" aria-hidden="true" />
        Back
      </BaseButton>
    </header>

    <section class="card">
      <div class="card-body stack">
        <div v-if="error" class="alert alert-error" role="alert">
          <CircleAlert :size="16" aria-hidden="true" />
          <div class="stack-tight">
            <span>{{ error }}</span>
            <ul v-if="errorDetails.length" class="error-list">
              <li v-for="detail in errorDetails" :key="detail">{{ detail }}</li>
            </ul>
          </div>
        </div>

        <FileDropzone :disabled="busy" @files="onFiles" />

        <div v-if="file" class="file-card">
          <span class="file-icon" aria-hidden="true"><FileText :size="16" /></span>
          <div class="grow">
            <p class="file-name">{{ file.name }}</p>
            <p class="file-meta">{{ formatBytes(file.size) }} · {{ file.type || 'PDF document' }}</p>
          </div>
        </div>

        <BaseInput
          v-model="title"
          label="Document name"
          name="title"
          placeholder="For example Service agreement 2026"
          hint="The name is shown to the recipients and in the list of documents."
          :error="titleError"
          @blur="titleTouched = true"
        />
      </div>

      <footer class="card-footer">
        <BaseButton variant="secondary" :to="{ name: 'documents' }">Cancel</BaseButton>
        <BaseButton :loading="busy" :disabled="!file" @click="onNext">
          Next
          <ArrowLeft :size="15" class="flip" aria-hidden="true" />
        </BaseButton>
      </footer>
    </section>

    <p class="hint-text">
      The server validates every upload: only unencrypted PDFs without JavaScript and without more than 50 pages are
      accepted.
    </p>
  </div>
</template>

<style scoped>
.narrow {
  max-width: 760px;
}

.card-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 18px;
  border-top: 1px solid var(--line);
}

.file-card {
  display: flex;
  align-items: center;
  gap: 11px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 11px 13px;
  background: var(--surface-muted);
}

.file-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--blue-100);
  color: var(--blue-600);
  flex: 0 0 auto;
}

.file-name {
  font-size: 13.5px;
  font-weight: 550;
  color: var(--ink-800);
  word-break: break-all;
}

.file-meta {
  font-size: 12px;
  color: var(--ink-500);
}

.stack-tight {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.error-list {
  margin: 0;
  padding-left: 16px;
  font-size: 12.5px;
}

.hint-text {
  font-size: 12.5px;
  color: var(--ink-500);
}

.flip {
  transform: rotate(180deg);
}
</style>
