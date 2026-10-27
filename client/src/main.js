import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { auth, logout } from './store/auth'
import { onSessionExpired, refreshTokenIfNeeded } from './api/auth'
import { reconnectWsNow } from './store/notifications'

// Expired or rejected session (API 401, WebSocket 1008, refresh refused): back to login.
onSessionExpired(() => {
  logout()
  router.push({ path: '/login', query: { expired: '1' } })
})

// An open, visible page counts as an active user: keep the session fresh, and reconnect the
// live-status socket right away when the user comes back.
const REFRESH_CHECK_MS = 10 * 60 * 1000
setInterval(() => {
  if (document.visibilityState === 'visible') refreshTokenIfNeeded()
}, REFRESH_CHECK_MS)
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible') return
  refreshTokenIfNeeded()
  reconnectWsNow()
})
window.addEventListener('online', reconnectWsNow)

const app = createApp(App)
app.use(router)
app.mount('#app')
