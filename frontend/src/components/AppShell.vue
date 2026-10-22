<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { FileText, LayoutDashboard, LogOut, PenLine, ShieldCheck } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import ToastHost from './ToastHost.vue'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const links = [
  { name: 'dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { name: 'documents', label: 'Documents', icon: FileText },
  { name: 'documents-new', label: 'New document', icon: PenLine },
]

function isActive(name: string): boolean {
  const current = String(route.name ?? '')
  if (name === 'documents') {
    return current === 'documents' || current.startsWith('documents-') || current.startsWith('document-')
  }
  return current === name
}

const initials = computed(() => {
  const source = auth.displayName.trim()
  if (!source) return 'D'
  const parts = source.split(/\s+/).slice(0, 2)
  return parts.map((part) => part.charAt(0).toUpperCase()).join('') || 'D'
})

async function onLogout(): Promise<void> {
  await auth.logout()
  await router.push({ name: 'login' })
}
</script>

<template>
  <div class="shell">
    <aside class="sidebar" aria-label="Main navigation">
      <RouterLink :to="{ name: 'dashboard' }" class="brand">
        <span class="brand-mark" aria-hidden="true"><ShieldCheck :size="18" /></span>
        <span class="brand-name">Dastakhat</span>
      </RouterLink>

      <nav class="nav">
        <RouterLink
          v-for="link in links"
          :key="link.name"
          :to="{ name: link.name }"
          class="nav-link"
          :class="{ 'nav-link-active': isActive(link.name) }"
          :aria-current="isActive(link.name) ? 'page' : undefined"
        >
          <component :is="link.icon" :size="17" aria-hidden="true" />
          <span class="nav-label">{{ link.label }}</span>
        </RouterLink>
      </nav>

      <div class="sidebar-footer">
        <div class="user">
          <span class="avatar" aria-hidden="true">{{ initials }}</span>
          <span class="user-text">
            <span class="user-name">{{ auth.displayName }}</span>
            <span class="user-mail">{{ auth.user?.email }}</span>
          </span>
        </div>
        <button type="button" class="nav-link nav-logout" aria-label="Log out" @click="onLogout">
          <LogOut :size="17" aria-hidden="true" />
          <span class="nav-label">Log out</span>
        </button>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="topbar-title">
          <span class="topbar-app">Dastakhat</span>
          <span class="topbar-tagline">e-signature workspace</span>
        </div>
        <div class="topbar-user">
          <span class="topbar-mail">{{ auth.user?.email }}</span>
          <button type="button" class="btn btn-secondary btn-sm" @click="onLogout">
            <LogOut :size="15" aria-hidden="true" />
            <span class="topbar-logout-label">Log out</span>
          </button>
        </div>
      </header>

      <main class="content">
        <RouterView />
      </main>
    </div>

    <ToastHost />
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  min-height: 100vh;
  background: var(--canvas);
}

.sidebar {
  width: var(--sidebar-width);
  flex: 0 0 var(--sidebar-width);
  background: var(--navy-700);
  color: #fff;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 18px 14px;
  position: sticky;
  top: 0;
  height: 100vh;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #fff;
  padding: 6px 8px 14px;
  text-decoration: none;
}

.brand:hover {
  text-decoration: none;
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: var(--blue-600);
  color: #fff;
  flex: 0 0 auto;
}

.brand-name {
  font-size: 16px;
  font-weight: 680;
  letter-spacing: -0.01em;
}

.nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1 1 auto;
}

.nav-link {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 10px;
  border-radius: var(--radius-sm);
  color: rgba(255, 255, 255, 0.76);
  font-size: 13.5px;
  font-weight: 500;
  border-left: 3px solid transparent;
  background: transparent;
  cursor: pointer;
  text-align: left;
  width: 100%;
  transition:
    background-color 0.15s ease,
    color 0.15s ease;
}

.nav-link:hover {
  background: rgba(255, 255, 255, 0.07);
  color: #fff;
  text-decoration: none;
}

.nav-link-active {
  background: var(--navy-500);
  color: #fff;
  border-left-color: var(--blue-600);
}

.sidebar-footer {
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  padding-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 6px;
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--navy-500);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  flex: 0 0 auto;
}

.user-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.user-name {
  font-size: 13px;
  font-weight: 550;
  color: #fff;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-mail {
  font-size: 11.5px;
  color: rgba(255, 255, 255, 0.55);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-logout {
  color: rgba(255, 255, 255, 0.7);
}

.main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: var(--topbar-height);
  padding: 0 28px;
  background: var(--surface);
  border-bottom: 1px solid var(--line);
}

.topbar-title {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.topbar-app {
  font-size: 14px;
  font-weight: 650;
  color: var(--ink-900);
}

.topbar-tagline {
  font-size: 12.5px;
  color: var(--ink-500);
}

.topbar-user {
  display: flex;
  align-items: center;
  gap: 12px;
}

.topbar-mail {
  font-size: 12.5px;
  color: var(--ink-600);
}

.content {
  flex: 1 1 auto;
  min-width: 0;
}

@media (max-width: 900px) {
  .shell {
    flex-direction: column;
  }

  .sidebar {
    width: 100%;
    flex: 0 0 auto;
    height: auto;
    position: sticky;
    top: 0;
    z-index: 30;
    flex-direction: row;
    align-items: center;
    gap: 12px;
    padding: 10px 12px;
  }

  .brand {
    padding: 0;
    flex: 0 0 auto;
  }

  .brand-name {
    display: none;
  }

  .nav {
    flex-direction: row;
    flex: 1 1 auto;
    gap: 4px;
    overflow-x: auto;
  }

  .nav-label {
    display: none;
  }

  .nav-link {
    border-left: none;
    border-bottom: 3px solid transparent;
    border-radius: var(--radius-sm);
    padding: 8px 10px;
    justify-content: center;
  }

  .nav-link-active {
    border-bottom-color: var(--blue-600);
  }

  .sidebar-footer {
    border-top: none;
    padding-top: 0;
    flex-direction: row;
    align-items: center;
    flex: 0 0 auto;
  }

  .user-text,
  .topbar-tagline,
  .topbar-mail,
  .topbar-logout-label {
    display: none;
  }

  .topbar {
    padding: 0 16px;
    height: 52px;
  }
}
</style>
