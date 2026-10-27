import { reactive } from 'vue'
import { getDeviceRelays, sessionExpired } from '../api/auth'
import { getBlockName, getIndividualName, areAlertsEnabled } from './settings'

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

// Событийные уведомления (оффлайн блока, реле, серверные предупреждения,
// «датчик не передаёт данные»). Гасятся переключателем в Настройках.
// Отклик о действиях («настройки отправлены», ошибки) идёт через pushNotification
// и показывается всегда.
export function pushEventNotification(text, type = 'info') {
  if (!areAlertsEnabled()) return null
  return pushNotification(text, type)
}

const MISSED_SESSIONS_THRESHOLD = 2
const MISSED_CHECK_INTERVAL_MS = 30000
// Смещение часов телефона относительно сервера, мс. lastTs приходит с сервера,
// поэтому при разнице часов устройства «пропуски» считались бы неверно.
let serverClockSkew = 0

export function initMissedTimings(entries) {
  const known = new Set()
  for (const e of entries || []) {
    if (e.serverNow) {
      const skew = Date.now() - e.serverNow
      if (Math.abs(skew) > 60000) serverClockSkew = skew
    }
    if (e.send == null || e.send <= 0 || e.lastTs == null) continue
    known.add(String(e.chipId))
    sensorTimings[String(e.chipId)] = { send: e.send, lastTs: e.lastTs, name: blockSensorName(e) }
  }
  for (const chipId of Object.keys(sensorTimings)) {
    if (!known.has(chipId)) delete sensorTimings[chipId]
  }
  if (timingInterval == null) {
    timingInterval = setInterval(checkMissedNow, MISSED_CHECK_INTERVAL_MS)
  }
  checkMissedNow()
}

function blockSensorName(e) {
  if (e.name && !/^Sens\d*$/i.test(e.name) && !/moisture sensor|moisture sensor/i.test(e.name)) return e.name
  const slot = e.blockRoles?.[0]?.slot
  return slot != null ? `Датчик S${slot}` : null
}

function pluralSession(n) {
  const mod10 = n % 10
  const mod100 = n % 100
  if (mod10 === 1 && mod100 !== 11) return 'сеанс'
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return 'сеанса'
  return 'сеансов'
}

function sensorMissedLabel(chipId) {
  const candidates = [getIndividualName(chipId), sensorTimings[String(chipId)]?.name]
  for (const candidate of candidates) {
    if (candidate && String(candidate) !== String(chipId)) return `${candidate} (${chipId})`
  }
  return String(chipId)
}

function checkMissedNow() {
  const now = Date.now() - serverClockSkew
  for (const chipId of Object.keys(sensorTimings)) {
    const t = sensorTimings[chipId]
    const diffMs = now - t.lastTs
    const missed = Math.floor(diffMs / 1000 / t.send)
    if (diffMs > MISSED_SESSIONS_THRESHOLD * t.send * 1000) {
      if (!timingBlocked[chipId] && lastTimingNotified[chipId] !== missed) {
        removeTimingToast(chipId)
        // Пока уведомления выключены — не помечаем как отправленные:
        // при включении покажем актуальное состояние, а не молча проглотим.
        if (!areAlertsEnabled()) continue
        timingBlocked[chipId] = true
        lastTimingNotified[chipId] = missed
        const id = pushEventNotification(
          `Датчик ${sensorMissedLabel(chipId)} не передает данные уже ${missed} ${pluralSession(missed)} (интервал ${t.send} с)`,
          'warn')
        const item = notifications.find(n => n.id === id)
        if (item) item._timingChip = String(chipId)
        lastTimingToast[chipId] = id
      }
    } else {
      lastTimingNotified[chipId] = 0
      timingBlocked[chipId] = false
      delete lastTimingToast[chipId]
      removeTimingToast(chipId)
    }
  }
}

function removeTimingToast(chipId) {
  const idx = notifications.findIndex(n => n._timingChip === String(chipId))
  if (idx >= 0) notifications.splice(idx, 1)
}

let sensorTimings = {}
let timingInterval = null
let lastTimingNotified = {}
let lastTimingToast = {}
let timingBlocked = {}


function sensorLabel(chipId) {
  const ind = getIndividualName(chipId)
  return ind ? `${ind} (${chipId})` : String(chipId)
}

export function dismissNotification(id) {
  const idx = notifications.findIndex(n => n.id === id)
  if (idx >= 0) notifications.splice(idx, 1)
}

// Выключатель в Настройках: снимаем текущие уведомления и сбрасываем защиту от дублей,
// чтобы после повторного включения показать актуальное состояние датчиков.
export function clearEventNotifications() {
  notifications.splice(0, notifications.length)
  for (const chipId of Object.keys(sensorTimings)) {
    delete lastTimingToast[chipId]
    delete lastTimingNotified[chipId]
    timingBlocked[chipId] = false
  }
}

export function refreshEventNotifications() {
  checkMissedNow()
}

let relayNameCache = {}
let blockStatusCache = {}
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

function relayName(deviceId, relay) {
  const key = deviceId + ':' + relay
  if (relayNameCache[key] !== undefined) return relayNameCache[key]
  relayNameCache[key] = null
  const done = () => {
    if (relayNameCache[key] === null) delete relayNameCache[key]
    updateRelayToast(key)
  }
  getDeviceRelays(deviceId).then(list => {
    const r = (list || []).find(x => String(x.relayNumber) === String(relay))
    relayNameCache[key] = (r && r.name) ? r.name : null
    done()
  }).catch(done)
  return null
}

export function prefetchRelayNames(devices) {
  for (const d of devices || []) {
    if (d.deviceType !== 'BLOCK') continue
    const deviceId = d.chipId
    getDeviceRelays(deviceId).then(list => {
      for (const r of list || []) {
        const key = deviceId + ':' + r.relayNumber
        relayNameCache[key] = r.name || null
      }
    }).catch(() => { /* ignore */ })
  }
}

function updateRelayToast(key) {
  const parts = key.split(':')
  for (const n of notifications) {
    if (n._relayKey === key) {
      n.text = relayText(Number(parts[0]), Number(parts[1]), n._state)
      return
    }
  }
}

function relayText(deviceId, relay, state) {
  const name = relayNameCache[deviceId + ':' + relay]
  const rn = name ? `${relay} (${name})` : String(relay)
  const blockName = getBlockName(deviceId)
  const bid = blockName ? `${blockName} (${deviceId})` : String(deviceId)
  const verb = state === 1 ? 'включен' : 'выключен'
  return `Канал R${rn} блока ${bid} ${verb}`
}

function handleMessage(msg) {
  if (msg.type === 'status' && (msg.deviceId === undefined || msg.deviceId === null)) {
    return
  }
  if (msg.type === 'status' && (msg.status === 'online' || msg.status === 'offline')) {
    const prev = blockStatusCache[msg.deviceId]
    blockStatusCache[msg.deviceId] = msg.status
    if (prev !== msg.status) {
      const b = getBlockName(msg.deviceId)
      pushEventNotification(
        `Блок ${b ? `${b} (${msg.deviceId})` : msg.deviceId} ${msg.status === 'offline' ? 'оффлайн' : 'онлайн'}`,
        msg.status === 'offline' ? 'error' : 'success'
      )
    }
  }
  if (msg.type === 'relayEvent' && areAlertsEnabled()) {
    const toastId = pushNotification(relayText(msg.deviceId, msg.relay, msg.state === 1 ? 1 : 0), 'info')
    const key = msg.deviceId + ':' + msg.relay
    const n = notifications.find(x => x.id === toastId)
    if (n) { n._relayKey = key; n._state = msg.state === 1 ? 1 : 0 }
    relayName(msg.deviceId, msg.relay)
  }
  if (msg.type === 'notify') {
    pushEventNotification(msg.message, 'warn')
  }
  if (msg.type === 'sensor' && msg.chipId !== undefined && msg.chipId !== null) {
    const t = sensorTimings[msg.chipId]
    if (t && msg.timestamp) {
      t.lastTs = msg.timestamp
      sensorTimings[msg.chipId] = { ...t }
      lastTimingNotified[msg.chipId] = 0
      timingBlocked[msg.chipId] = false
      if (lastTimingToast[msg.chipId]) {
        dismissNotification(lastTimingToast[msg.chipId])
        lastTimingToast[msg.chipId] = null
      }
    }
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
