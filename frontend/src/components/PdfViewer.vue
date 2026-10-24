<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as pdfjsLib from 'pdfjs-dist'
import type { PDFDocumentProxy } from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import LoadingSpinner from './LoadingSpinner.vue'
import { pointerToFraction } from '@/utils/coordinates'

pdfjsLib.GlobalWorkerOptions.workerSrc = workerUrl

const props = withDefaults(
  defineProps<{
    /** The URL of the PDF. */
    src: string
    /** The zoom factor, 1 means "fit the width of the container". */
    zoom?: number
    /** The page that is scrolled into the view. */
    currentPage?: number
    /** A click on a page is reported with its fractions. */
    interactive?: boolean
    loadingLabel?: string
  }>(),
  { zoom: 1, currentPage: 1, interactive: false, loadingLabel: 'Rendering the document…' },
)

const emit = defineEmits<{
  loaded: [pageCount: number]
  error: [message: string]
  'page-click': [payload: { page: number; x: number; y: number }]
}>()

const container = ref<HTMLElement | null>(null)
const loading = ref(true)
const errorMessage = ref<string | null>(null)
const pageCount = ref(0)

const canvases = new Map<number, HTMLCanvasElement>()
/** The render tasks that are currently running, so that they can be cancelled. */
const renderTasks = new Map<number, { cancel: () => void }>()
let documentProxy: PDFDocumentProxy | null = null
let renderToken = 0
let resizeObserver: ResizeObserver | null = null
let lastRenderedWidth = 0

function setCanvasRef(page: number, element: Element | null): void {
  if (element instanceof HTMLCanvasElement) canvases.set(page, element)
  else canvases.delete(page)
}

async function load(): Promise<void> {
  const token = ++renderToken
  loading.value = true
  errorMessage.value = null
  canvases.clear()
  try {
    const response = await fetch(props.src, { credentials: 'include' })
    if (!response.ok) {
      throw new Error(
        response.status === 404
          ? 'The document is not available.'
          : `The document could not be loaded (status ${response.status}).`,
      )
    }
    const buffer = await response.arrayBuffer()
    if (token !== renderToken) return
    const task = pdfjsLib.getDocument({ data: new Uint8Array(buffer), useSystemFonts: true })
    documentProxy = await task.promise
    if (token !== renderToken) return
    pageCount.value = documentProxy.numPages
    emit('loaded', pageCount.value)
    // The page canvases only exist in the DOM once the loading state is over - rendering
    // before that finds no canvas and would silently leave every page blank.
    loading.value = false
    await nextTick()
    await renderAll(token)
  } catch (cause) {
    if (token !== renderToken) return
    const message = cause instanceof Error ? cause.message : 'The document could not be loaded.'
    errorMessage.value = message
    emit('error', message)
  } finally {
    if (token === renderToken) loading.value = false
  }
}

function containerWidth(): number {
  const width = container.value?.clientWidth ?? 0
  return width > 0 ? width : 720
}

async function renderAll(token: number): Promise<void> {
  if (!documentProxy) return
  for (let page = 1; page <= documentProxy.numPages; page += 1) {
    if (token !== renderToken) return
    await renderPage(page, token)
  }
}

async function renderPage(page: number, token: number): Promise<void> {
  if (!documentProxy) return
  // A re-render (zoom, resize) can start while Vue is still patching the DOM - wait for the
  // update so the canvas of this page is guaranteed to exist before it is looked up.
  await nextTick()
  const canvas = canvases.get(page)
  if (!canvas) return
  // A render that is still running has to be cancelled, otherwise pdf.js refuses to render
  // the same canvas twice ("Cannot use the same canvas during multiple render() operations").
  const pending = renderTasks.get(page)
  if (pending) {
    pending.cancel()
    renderTasks.delete(page)
  }
  try {
    const pdfPage = await documentProxy.getPage(page)
    if (token !== renderToken) return
    const unscaled = pdfPage.getViewport({ scale: 1 })
    const baseScale = containerWidth() / unscaled.width
    const viewport = pdfPage.getViewport({ scale: baseScale * props.zoom })
    const context = canvas.getContext('2d')
    if (!context) return
    canvas.width = Math.floor(viewport.width)
    canvas.height = Math.floor(viewport.height)
    canvas.style.width = `${Math.floor(viewport.width)}px`
    canvas.style.height = `${Math.floor(viewport.height)}px`
    const task = pdfPage.render({ canvasContext: context, viewport })
    renderTasks.set(page, task)
    try {
      await task.promise
    } finally {
      if (renderTasks.get(page) === task) renderTasks.delete(page)
    }
  } catch (cause) {
    if (token !== renderToken) return
    const name = cause instanceof Error ? cause.name : ''
    if (name === 'RenderingCancelledException') return
    errorMessage.value = cause instanceof Error ? cause.message : 'A page could not be rendered.'
  }
}

async function rerender(): Promise<void> {
  const token = ++renderToken
  await renderAll(token)
}

function scrollToPage(page: number): void {
  const element = container.value?.querySelector(`[data-pdf-page="${page}"]`)
  if (element instanceof HTMLElement) element.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function onPageClick(event: MouseEvent): void {
  if (!props.interactive) return
  const target = event.target
  if (!(target instanceof Element)) return
  const pageElement = target.closest('[data-pdf-page]')
  if (!(pageElement instanceof HTMLElement)) return
  const page = Number(pageElement.getAttribute('data-pdf-page') ?? '0')
  if (!page) return
  const rect = pageElement.getBoundingClientRect()
  const { x, y } = pointerToFraction({ clientX: event.clientX, clientY: event.clientY }, {
    left: rect.left,
    top: rect.top,
    width: rect.width,
    height: rect.height,
  })
  emit('page-click', { page, x, y })
}

watch(
  () => props.src,
  () => {
    void load()
  },
)

watch(
  () => props.zoom,
  async () => {
    if (!documentProxy) return
    await rerender()
  },
)

watch(
  () => props.currentPage,
  (page) => {
    if (page > 0) scrollToPage(page)
  },
)

onMounted(async () => {
  await load()
  // The container is rendered with the pages - make sure it exists before it is measured
  // and observed, so the viewer re-renders on later width changes.
  await nextTick()
  lastRenderedWidth = containerWidth()
  if (typeof ResizeObserver !== 'undefined' && container.value) {
    resizeObserver = new ResizeObserver(() => {
      const width = containerWidth()
      // Only a real change of the width needs a new render - otherwise the observer would
      // fight with the render that is already running.
      if (Math.abs(width - lastRenderedWidth) < 2) return
      lastRenderedWidth = width
      void rerender()
    })
    resizeObserver.observe(container.value)
  }
})

onBeforeUnmount(() => {
  renderToken += 1
  resizeObserver?.disconnect()
  resizeObserver = null
  void documentProxy?.destroy()
  documentProxy = null
})
</script>

<template>
  <div class="pdf-viewer">
    <div v-if="loading" class="pdf-state">
      <LoadingSpinner :label="loadingLabel" />
    </div>
    <div v-else-if="errorMessage" class="pdf-state">
      <div class="alert alert-error" role="alert">
        <span>{{ errorMessage }}</span>
      </div>
    </div>
    <div v-else ref="container" class="pdf-pages" @click="onPageClick">
      <div
        v-for="page in pageCount"
        :key="page"
        class="pdf-page"
        :data-pdf-page="page"
      >
        <canvas :ref="(element) => setCanvasRef(page, element as Element | null)" class="pdf-canvas" />
        <slot name="overlay" :page="page" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.pdf-viewer {
  width: 100%;
}

.pdf-state {
  padding: 28px 12px;
}

.pdf-pages {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 18px;
  padding: 6px;
}

.pdf-page {
  position: relative;
  background: #fff;
  box-shadow: 0 2px 10px rgba(16, 24, 40, 0.12);
  border-radius: 4px;
  overflow: hidden;
  line-height: 0;
}

.pdf-canvas {
  display: block;
  max-width: 100%;
}
</style>
