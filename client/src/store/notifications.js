import { reactive } from 'vue'
import { sessionExpired } from '../api/auth'

let nextId = 0

const MAX_NOTIFICATIONS = 5

export const notifications = reactive([])

const messageHandlers = new Set()

export function onWsMessage(handler) {
  messageHandlers.add(handler)
  return () => messageHandlers.delete(handler)
}

export function pushNotification(text, type = 'info') {
  const dup = notifications.findIndex(n => n.text === text)
  if (dup >= 0) notifications.splice(dup, 1)
  const item = { id: nextId++, text, type, time: Date.now() }
  notifications.push(item)
  if (notifications.length > MAX_NOTIFICATIONS) {
    notifications.shift()
  }
  return item.id
}

// Event notifications (server warnings). Sensor gaps and block offline/online are
// alarms now (store/alarms.js), so only the remaining server warnings use this.
export function pushEventNotification(text, type = 'info') {
  return pushNotification(text, type)
}

export function dismissNotification(id) {
  const idx = notifications.findIndex(n => n.id === id)
  if (idx >= 0) notifications.splice(idx, 1)
}

let ws = null
let reconnectTimer = null
// Token the server refused (close code 1008): don't retry with it, wait for a new login.
let rejectedToken = null
const CLOSE_POLICY_VIOLATION = 1008

export const connection = reactive({
  online: false,
})

function getToken() {
  return localStorage.getItem('token')
}

function wsUrl(token) {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  // Same-origin: /ws/status проксирует serve.py на :3000. Раньше жёстко был :8080 —
  // с телефона в локалке этот порт недоступен, live-обновления не работали.
  // Браузер не умеет слать Authorization в WebSocket, поэтому токен идёт query-параметром.
  return `${proto}://${location.host}/ws/status?token=${encodeURIComponent(token)}`
}

// Safe to call from any page: without a token (login page, after logout) it does nothing,
// so the server never sees anonymous connection attempts. Views call it again on mount,
// which connects right after a login.
export function connectWs() {
  const token = getToken()
  if (!token || token === rejectedToken) return
  if (ws && ws.readyState <= WebSocket.OPEN) return
  let socket
  try {
    socket = new WebSocket(wsUrl(token))
  } catch {
    connection.online = false
    scheduleReconnect()
    return
  }
  ws = socket
  socket.onopen = () => {
    connection.online = true
  }
  socket.onclose = (event) => {
    // Replaced or closed on purpose by disconnectWs().
    if (ws !== socket) return
    ws = null
    connection.online = false
    // The server rejected the token (expired, invalid, account disabled): retrying every
    // 5 s with the same token only floods its log. Reconnect after the token changes.
    if (event.code === CLOSE_POLICY_VIOLATION) {
      rejectedToken = token
      if (getToken() === token) {
        // Still our current token: the session is over -> login page with a message.
        sessionExpired()
      } else {
        // The token was refreshed meanwhile: retry with the new one.
        connectWs()
      }
      return
    }
    scheduleReconnect()
  }
  socket.onerror = () => {
    connection.online = false
    socket.close()
  }
  socket.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data)
      handleMessage(msg)
    } catch { /* ignore */ }
  }
}

function handleMessage(msg) {
  if (msg.type === 'status' && (msg.deviceId === undefined || msg.deviceId === null)) {
    return
  }
  if (msg.type === 'notify') {
    pushEventNotification(msg.message, 'warn')
  }
  for (const handler of messageHandlers) {
    try {
      handler(msg)
    } catch { /* ignore */ }
  }
}

function scheduleReconnect() {
  if (reconnectTimer) return
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    connectWs()
  }, 5000)
}

/**
 * Tab visible again / network back: reconnect now instead of waiting for the 5 s timer,
 * which browsers throttle heavily in background tabs.
 */
export function reconnectWsNow() {
  clearTimeout(reconnectTimer)
  reconnectTimer = null
  connectWs()
}

/** On logout: close the socket and stop reconnecting (connectWs() resumes after a new login). */
export function disconnectWs() {
  clearTimeout(reconnectTimer)
  reconnectTimer = null
  const socket = ws
  ws = null
  connection.online = false
  if (socket) socket.close()
}
