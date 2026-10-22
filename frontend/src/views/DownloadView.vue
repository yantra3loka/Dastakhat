<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { CircleAlert, CircleCheck, Download, FileText } from 'lucide-vue-next'
import BaseButton from '@/components/BaseButton.vue'
import { downloadByToken } from '@/api/signer'
import { ApiError } from '@/api/client'

const props = defineProps<{ token: string }>()

const state = ref<'loading' | 'ready' | 'error'>('loading')
const message = ref('')
const downloadUrl = ref<string | null>(null)
const fileName = ref('document-signed.pdf')

async function download(): Promise<void> {
  state.value = 'loading'
  message.value = ''
  try {
    const blob = await downloadByToken(props.token)
    if (downloadUrl.value) URL.revokeObjectURL(downloadUrl.value)
    downloadUrl.value = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = downloadUrl.value
    anchor.download = fileName.value
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    state.value = 'ready'
  } catch (cause) {
    state.value = 'error'
    if (cause instanceof ApiError) {
      if (cause.status === 404) {
        message.value = 'This download link is not valid. Please ask the sender for a new link.'
      } else if (cause.status === 410) {
        message.value = 'This download link has expired. Please ask the sender for a new link.'
      } else {
        message.value = cause.message
      }
    } else {
      message.value = 'The document could not be downloaded.'
    }
  }
}

onMounted(download)
</script>

<template>
  <div class="download-page">
    <div class="download-card">
      <span class="download-icon" aria-hidden="true">
        <FileText :size="22" />
      </span>

      <template v-if="state === 'loading'">
        <h1>Preparing your download</h1>
        <p role="status">The signed document is being fetched…</p>
        <div class="spinner" aria-hidden="true" />
      </template>

      <template v-else-if="state === 'ready'">
        <span class="download-icon download-icon-green" aria-hidden="true"><CircleCheck :size="22" /></span>
        <h1>Your document is ready</h1>
        <p>The download of the signed PDF has started. If it did not start, use the button below.</p>
        <BaseButton v-if="downloadUrl" @click.prevent="download">
          <Download :size="15" aria-hidden="true" />
          Download again
        </BaseButton>
      </template>

      <template v-else>
        <span class="download-icon download-icon-red" aria-hidden="true"><CircleAlert :size="22" /></span>
        <h1>The document cannot be downloaded</h1>
        <p role="alert">{{ message }}</p>
      </template>
    </div>
  </div>
</template>

<style scoped>
.download-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--canvas);
}

.download-card {
  width: min(460px, 100%);
  background: var(--surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow);
  padding: 30px 26px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  text-align: center;
}

.download-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: var(--blue-100);
  color: var(--blue-600);
}

.download-icon-green {
  background: var(--green-100);
  color: var(--green-600);
}

.download-icon-red {
  background: var(--red-100);
  color: var(--red-700);
}

.spinner {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 2px solid var(--line);
  border-top-color: var(--blue-600);
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
