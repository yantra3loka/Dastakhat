import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { router } from './router'
import { setUnauthorizedHandler } from './api/client'
import { useAuthStore } from './stores/auth'
import './styles/main.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

const auth = useAuthStore(pinia)

setUnauthorizedHandler(() => {
  // The session is gone - the user has to sign in again.
  auth.clearSession()
  const current = router.currentRoute.value
  if (current.name !== 'login') {
    void router.push({
      name: 'login',
      query: current.fullPath === '/' ? {} : { redirect: current.fullPath },
    })
  }
})

app.mount('#app')
