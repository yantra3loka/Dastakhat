import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { createRouter, createWebHistory } from 'vue-router'
import App from '@/App.vue'
import LoginView from '@/views/LoginView.vue'

describe('the application', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })),
    )
  })

  it('mounts and renders the login page', async () => {
    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/', redirect: '/login' },
        { path: '/login', name: 'login', component: LoginView },
      ],
    })
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router] } })
    await wrapper.vm.$nextTick()

    expect(wrapper.find('h1').text()).toBe('Dastakhat')
    expect(wrapper.find('input[type="email"]').exists()).toBe(true)
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
    expect(wrapper.get('form').attributes('novalidate')).toBeDefined()
  })

  it('marks the e-mail address of the development account', async () => {
    const router = createRouter({
      history: createWebHistory(),
      routes: [{ path: '/login', name: 'login', component: LoginView }],
    })
    await router.push('/login')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [router] } })
    await wrapper.vm.$nextTick()

    const email = wrapper.get('input[type="email"]')
    expect((email.element as HTMLInputElement).value).toBe('sender@example.test')
    expect(wrapper.text()).toContain('ChangeMe!123')
  })
})
