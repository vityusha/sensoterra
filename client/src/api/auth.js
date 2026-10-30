import axios from 'axios'

const api = axios.create({ baseURL: '/api', withCredentials: true })

api.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
    // Any API call is user activity: extend the session before the token runs out.
    refreshTokenIfNeeded()
  }
  return config
})

// Endpoints where 401 is a normal answer (wrong password etc.), not an expired session.
const AUTH_FORM_URL = /^\/?auth\/(login|bootstrap|register|password|oauth\/)/

api.interceptors.response.use(res => res, err => {
  const config = err.config || {}
  if (err.response?.status === 401 && config.headers?.Authorization && !AUTH_FORM_URL.test(config.url || '')) {
    sessionExpired()
  }
  return Promise.reject(err)
})

// --- Sliding session: JWT lives 24 h, an active client gets a new one past half of it ---

const REFRESH_WHEN_LEFT_MS = 12 * 3600 * 1000

let sessionExpiredHandler = null
let refreshing = null
// Set when a session ends by expiry, cleared on the next login: lets the router keep the
// "session expired" message even when it, not the handler, does the redirect.
let expiredFlag = false

export function wasSessionExpired() {
  return expiredFlag
}

/** main.js registers what to do when the session is over (logout + login page). */
export function onSessionExpired(handler) {
  sessionExpiredHandler = handler
}

/** Token rejected or expired: drop it and let the app show the login page once. */
export function sessionExpired() {
  if (!getToken()) return
  setToken(null)
  expiredFlag = true
  if (sessionExpiredHandler) sessionExpiredHandler()
}

/** exp of the JWT in ms; 0 if the token can't be read (treated as expired). */
export function tokenExpiresAt(token) {
  try {
    const payload = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    return JSON.parse(atob(payload)).exp * 1000
  } catch {
    return 0
  }
}

/**
 * Called on user activity (API calls, tab visible). Expired token -> session expired;
 * less than 12 h left -> POST /api/auth/refresh. The server refuses (401) after the
 * absolute session limit, which also ends the session.
 */
export function refreshTokenIfNeeded() {
  const token = getToken()
  if (!token || refreshing) return
  const left = tokenExpiresAt(token) - Date.now()
  if (left <= 0) {
    sessionExpired()
    return
  }
  if (left > REFRESH_WHEN_LEFT_MS) return
  // Plain axios: this request must not go through the interceptors above.
  refreshing = axios.post('/api/auth/refresh', {}, { headers: { Authorization: `Bearer ${token}` } })
    .then(({ data }) => {
      // A logout or another login meanwhile wins over this refresh.
      if (getToken() === token && data?.token) setToken(data.token)
    })
    .catch(e => {
      if (e.response?.status === 401) sessionExpired()
    })
    .finally(() => {
      refreshing = null
    })
}

export function setToken(token) {
  if (token) {
    expiredFlag = false
    api.defaults.headers.common['Authorization'] = `Bearer ${token}`
    localStorage.setItem('token', token)
  } else {
    delete api.defaults.headers.common['Authorization']
    localStorage.removeItem('token')
  }
}

export function getToken() {
  return localStorage.getItem('token')
}

export async function login(username, password) {
  const { data } = await api.post('/auth/login', { username, password })
  setToken(data.token)
  return data.user
}

export async function bootstrap(username, password) {
  const { data } = await api.post('/auth/bootstrap', { username, password })
  setToken(data.token)
  return data.user
}

export async function authStatus() {
  const { data } = await api.get('/auth/status')
  return data
}

export async function me() {
  const { data } = await api.get('/auth/me')
  return data
}

/**
 * Смена собственного пароля. Текущий пароль сервер проверяет сам: без него утёкший токен
 * позволил бы перехватить аккаунт.
 */
export async function changePassword(currentPassword, newPassword) {
  const { data } = await api.put('/auth/password', { currentPassword, newPassword })
  return data
}

/**
 * Самостоятельная регистрация покупателя. Если передан chipId + key с комплектного листа,
 * устройство привязывается к новому аккаунту сразу.
 */
export async function register(username, password, chipId = null, key = null) {
  const { data } = await axios.post('/api/auth/register', { username, password, chipId, key })
  return data
}

/** Привязать свой блок по паре «ID + ключ» с комплектного листа. */
export async function claimDevice(chipId, key) {
  const { data } = await api.post('/devices/claim', { chipId, key })
  return data
}

/**
 * Вход и регистрация через Google. id_token получает сам GIS на странице, сервер
 * проверяет подпись и отдаёт наш же JWT. Пара «ID + ключ» необязательна: если её
 * прислали, блок подтверждается в той же транзакции.
 */
export async function loginWithGoogle(idToken, chipId = null, key = null) {
  const { data } = await axios.post('/api/auth/oauth/google', { idToken, chipId, key })
  return data
}

/**
 * Yandex ID: one-time code from the redirect plus the PKCE verifier; the server exchanges
 * it with its own secret. The device pair works as in loginWithGoogle.
 */
export async function loginWithYandex(code, codeVerifier = null, chipId = null, key = null) {
  const { data } = await axios.post('/api/auth/oauth/yandex', { code, codeVerifier, chipId, key })
  return data
}

/** Ключ устройства для комплектного листа (только админ). */
export async function getDeviceKey(chipId) {
  const { data } = await api.get(`/devices/${chipId}/key`)
  return data
}

export async function regenerateDeviceKey(chipId) {
  const { data } = await api.post(`/devices/${chipId}/key/regenerate`)
  return data
}

// --- Оплата блока: срок общий для всех, кто имеет доступ к блоку ---

export async function getDevicePayment(chipId) {
  const { data } = await api.get(`/devices/${chipId}/payment`)
  return data
}

export async function getPrefs() {
  const { data } = await api.get('/prefs')
  return data
}

export async function updatePrefs(patch) {
  const { data } = await api.put('/prefs', patch)
  return data
}

/** Правка дефолтов подписей. Только админ: PUT /api/prefs/defaults. */
export async function saveFieldDefaults(patch) {
  const { data } = await api.put('/prefs/defaults', patch)
  return data
}

/** Раскатка дефолта тем, кто подпись не менял руками. Возвращает { updated, message }. */
export async function applyFieldDefaults(request) {
  const { data } = await api.post('/prefs/defaults/apply', request)
  return data
}

export async function clearRetainedSettings(chipId) {
  const { data } = await api.post(`/devices/${chipId}/retained/clear`, {})
  return data
}

export async function listPayments() {
  const { data } = await api.get('/devices/payment')
  return data
}

export async function setDevicePayment(chipId, plan) {
  const { data } = await api.post(`/devices/${chipId}/payment`, plan)
  return data
}

export async function extendDevicePayment(chipId, days) {
  const { data } = await api.post(`/devices/${chipId}/payment/extend`, { days })
  return data
}

export async function resetDeviceRegistrations(chipId) {
  const { data } = await api.post(`/devices/${chipId}/payment/reset`, {})
  return data
}

export async function getBlockPaymentDefaults() {
  const { data } = await api.get('/admin/settings/block-payment')
  return data
}

export async function setBlockPaymentDefaults(days, unlimited) {
  const { data } = await api.put('/admin/settings/block-payment', { days, unlimited })
  return data
}

export async function getUser(id) {
  const { data } = await api.get(`/admin/users/${id}`)
  return data
}

export async function listUsers() {
  const { data } = await api.get('/admin/users')
  return data
}

/** Админ заводит только других админов — пользователи регистрируются сами. */
export async function createAdmin(username, password) {
  const { data } = await api.post('/admin/users', { username, password, role: 'ADMIN' })
  return data
}

export async function updateUser(id, patch) {
  const { data } = await api.put(`/admin/users/${id}`, patch)
  return data
}

/** Платный доступ на срок в дней (без срока — бессрочно). */
// --- Приглашения на доступ к устройству ---

export async function listInvites(chipId) {
  const { data } = await api.get(`/devices/${chipId}/invites`)
  return data
}

/** accessRole: 'USER' — принявший будет управлять, 'VIEWER' — только читать. */
export async function createInvite(chipId, accessRole = 'USER') {
  const { data } = await api.post(`/devices/${chipId}/invites`, { accessRole })
  return data
}

export async function revokeInvite(chipId, inviteId) {
  await api.delete(`/devices/${chipId}/invites/${inviteId}`)
}

/** Убирает из списка завершённые приглашения (отозванные и истёкшие). Возвращает { removed }. */
export async function clearFinishedInvites(chipId) {
  const { data } = await api.delete(`/devices/${chipId}/invites/finished`)
  return data
}

/** Полная очистка приглашений устройства, включая живые ссылки. Возвращает { removed }. */
export async function clearAllInvites(chipId) {
  const { data } = await api.delete(`/devices/${chipId}/invites/all`)
  return data
}

/** Предпросмотр ссылки — анонимный, токен из локалстора не нужен. */
export async function getInvite(token) {
  const { data } = await axios.get(`/api/invites/${token}`)
  return data
}

/** Принять приглашение залогиненным аккаунтом. */
export async function acceptInvite(token) {
  await api.post(`/invites/${token}/accept`)
}

/** Зарегистрироваться по ссылке: аккаунт создаётся и сразу получает доступ. */
export async function registerByInvite(token, username, password) {
  const { data } = await axios.post(`/api/invites/${token}/register`, { username, password })
  return data
}

export async function listDeviceAccess(chipId) {
  const { data } = await api.get(`/devices/${chipId}/access`)
  return data
}

export async function grantDeviceAccess(chipId, userId, accessRole = 'USER') {
  const { data } = await api.post(`/devices/${chipId}/access`, { userId, accessRole })
  return data
}

export async function revokeDeviceAccess(chipId, userId) {
  await api.delete(`/devices/${chipId}/access/${userId}`)
}

export async function deleteUser(id) {
  await api.delete(`/admin/users/${id}`)
}

export async function getUserDevices(userId) {
  const { data } = await api.get(`/admin/users/${userId}/devices`)
  return data
}

export async function assignDevice(userId, chipId, deviceType, accessRole = 'USER') {
  const body = { chipId, accessRole }
  if (deviceType) body.deviceType = deviceType
  const { data } = await api.post(`/admin/users/${userId}/devices`, body)
  return data
}

export async function unassignDevice(userId, chipId) {
  await api.delete(`/admin/users/${userId}/devices/${chipId}`)
}

export async function listDevices() {
  const { data } = await api.get('/devices')
  return data
}

export async function deleteDevice(chipId) {
  const { data } = await api.delete(`/devices/${chipId}`)
  return data
}

export async function getDevice(chipId) {
  const { data } = await api.get(`/devices/${chipId}`)
  return data
}

export async function getDeviceRelays(chipId) {
  const { data } = await api.get(`/devices/${chipId}/relays`)
  return data
}

export async function getDeviceSensors(chipId) {
  const { data } = await api.get(`/devices/${chipId}/sensors`)
  return data
}

export async function getDeviceRegisteredSensors(chipId) {
  const { data } = await api.get(`/devices/${chipId}/registered-sensors`)
  return data
}

export async function getSensorCatalog() {
  const { data } = await api.get('/sensors/catalog')
  return data
}

export async function resolveSensorConflict(chipId, keepBlockId) {
  const { data } = await api.post(`/sensors/${chipId}/resolve-conflict`, { keepBlockId })
  return data
}

export async function getSensorCalibration(chipId) {
  const { data } = await api.get(`/sensors/${chipId}/calibration`)
  return data
}

export async function saveSensorCalibration(chipId, values) {
  const { data } = await api.put(`/sensors/${chipId}/calibration`, values)
  return data
}

/** Самостоятельная калибровка: правится только value_2 (0% — сухая почва). */
export async function saveSensorCalibrationValue2(chipId, value2) {
  const { data } = await api.put(`/sensors/${chipId}/calibration/value2`, { value2 })
  return data
}

export async function getCalibrationDefaults() {
  const { data } = await api.get('/prefs/defaults/calibration-defaults')
  return data
}

export async function saveCalibrationDefaults(patch) {
  const { data } = await api.put('/prefs/defaults/calibration-defaults', patch)
  return data
}

export async function getSensorTypes() {
  const { data } = await api.get('/sensors/types')
  return data
}

export async function getDeviceStatuses() {
  const { data } = await api.get('/devices/status')
  return data
}

export async function sendRelayParams(relayId, params) {
  const { data } = await api.post(`/relays/${relayId}/params`, params)
  return data
}

export async function sendSensorParams(sensorId, params) {
  const { data } = await api.post(`/sensors/${sensorId}/params`, params)
  return data
}

export async function getBlockTelemetry(deviceId, since, to) {
  const params = new URLSearchParams({ deviceId })
  if (since) params.set('since', since)
  if (to) params.set('to', to)
  const { data } = await api.get(`/telemetry/block?${params}`)
  return data
}

export async function getDeviceStatusLog(deviceId, since, to) {
  const params = new URLSearchParams({ deviceId })
  if (since) params.set('since', since)
  if (to) params.set('to', to)
  const { data } = await api.get(`/telemetry/status?${params}`)
  return data
}

export async function getRelayTelemetry(deviceId, relayNumber, since, to) {
  const params = new URLSearchParams({ deviceId })
  if (relayNumber != null) params.set('relayNumber', relayNumber)
  if (since) params.set('since', since)
  if (to) params.set('to', to)
  const { data } = await api.get(`/telemetry/relay?${params}`)
  return data
}

export async function getSensorTelemetry(deviceId, sensorChipId, since, to, limit) {
  const params = new URLSearchParams({})
  if (deviceId != null) params.set('deviceId', deviceId)
  if (sensorChipId != null) params.set('sensorChipId', sensorChipId)
  if (since) params.set('since', since)
  if (to) params.set('to', to)
  if (limit != null) params.set('limit', limit)
  const { data } = await api.get(`/telemetry/sensor?${params}`)
  return data
}

export async function getSensorFirstMessage(deviceId, sensorChipId) {
  const params = new URLSearchParams({})
  if (deviceId != null) params.set('deviceId', deviceId)
  if (sensorChipId != null) params.set('sensorChipId', sensorChipId)
  const { data } = await api.get(`/telemetry/sensor/start?${params}`)
  return data
}

export async function getServerStatus() {
  const { data } = await api.get('/status')
  return data
}

export async function getServerUptimeHistory() {
  const { data } = await api.get('/server/uptime')
  return data
}

export { api }

export async function getMoistureScale(chipId) {
  const { data } = await api.get(`/sensors/${chipId}/moisture-scale`)
  return data
}

export async function setMoistureScale(chipId, scale) {
  const { data } = await api.put(`/sensors/${chipId}/moisture-scale`, scale)
  return data
}

export async function clearMoistureScale(chipId) {
  await api.delete(`/sensors/${chipId}/moisture-scale`)
}

export async function getSensorScales(chipId) {
  const { data } = await api.get(`/sensors/${chipId}/scale`)
  return data
}

export async function getSensorScale(chipId, fieldKey) {
  const { data } = await api.get(`/sensors/${chipId}/scale/${fieldKey}`)
  return data
}

export async function setSensorScale(chipId, scale) {
  const { data } = await api.put(`/sensors/${chipId}/scale`, scale)
  return data
}

export async function clearSensorScale(chipId, fieldKey) {
  await api.delete(`/sensors/${chipId}/scale/${fieldKey}`)
}
