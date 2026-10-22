import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: 'Sign in' },
  },
  {
    path: '/sign/:token',
    name: 'sign',
    component: () => import('@/views/SignView.vue'),
    props: true,
    meta: { public: true, title: 'Sign a document' },
  },
  {
    path: '/download/:token',
    name: 'download',
    component: () => import('@/views/DownloadView.vue'),
    props: true,
    meta: { public: true, title: 'Download the signed document' },
  },
  {
    path: '/',
    component: () => import('@/components/AppShell.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: { name: 'dashboard' } },
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: 'Dashboard' },
      },
      {
        path: 'documents',
        name: 'documents',
        component: () => import('@/views/DocumentsView.vue'),
        meta: { title: 'Documents' },
      },
      {
        path: 'documents/new',
        name: 'documents-new',
        component: () => import('@/views/NewDocumentView.vue'),
        meta: { title: 'New document' },
      },
      {
        path: 'documents/:id/prepare',
        name: 'documents-prepare',
        component: () => import('@/views/PrepareDocumentView.vue'),
        props: true,
        meta: { title: 'Prepare document' },
      },
      {
        path: 'documents/:id/review',
        name: 'documents-review',
        component: () => import('@/views/ReviewDocumentView.vue'),
        props: true,
        meta: { title: 'Review and send' },
      },
      {
        path: 'documents/:id/audit',
        name: 'documents-audit',
        component: () => import('@/views/AuditDocumentView.vue'),
        props: true,
        meta: { title: 'Audit trail' },
      },
      {
        path: 'documents/:id',
        name: 'document-detail',
        component: () => import('@/views/DocumentDetailView.vue'),
        props: true,
        meta: { title: 'Document' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true, title: 'Page not found' },
  },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    await auth.restore()
    if (!auth.isAuthenticated) {
      return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
    }
  }
  if (to.name === 'login' && auth.isAuthenticated) {
    return { name: 'dashboard' }
  }
  return true
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : undefined
  document.title = title ? `${title} - Dastakhat` : 'Dastakhat'
})
