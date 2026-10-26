import { reactive } from 'vue'
import { me, getToken } from '../api/auth'
import { loadPrefs, settings } from './settings'
import { connectWs, disconnectWs } from './notifications'

export const auth = reactive({
  user: null,
  loading: true
})

export async function loadUser() {
  if (!getToken()) {
    auth.user = null
    auth.loading = false
    return
  }
  try {
    auth.user = await me()
    // Настройки блоков и датчиков живут в БД — тянем сразу вместе с пользователем,
    // иначе до первого запроса графики успеют отрисоваться со старыми значениями.
    await loadPrefs()
    connectWs()
  } catch {
    auth.user = null
  } finally {
    auth.loading = false
  }
}

export function setUser(user) {
  auth.user = user
}

/**
 * Вход в приложение: пользователь плюс его настройки отображения из БД. Все страницы,
 * где выдаётся токен, идут через это место, иначе настройки блоков подтянулись бы
 * не везде одинаково.
 */
export async function enterSession(user) {
  setUser(user)
  await loadPrefs()
  connectWs()
}

/**
 * Session user from a sign-in/sign-up response (/register, /oauth/*): same fields as /me,
 * so Profile knows about an external account without a reload.
 */
export function userFromAuthResponse(data) {
  return { id: data.id, username: data.username, email: data.email, role: data.role,
    authProvider: data.authProvider ?? null }
}

export function isAdmin() {
  return auth.user?.role === 'ADMIN'
}

/**
 * Роль аккаунта — только ADMIN или USER. «Только чтение» — свойство пары
 * (человек, устройство), поэтому кэшируется здесь по chipId тем, кто уже
 * открывал страницу устройства или список доступа.
 */
export const deviceAccess = reactive({})

export function rememberDeviceAccess(chipId, accessRole) {
  deviceAccess[chipId] = accessRole
}

export function forgetDeviceAccess(chipId) {
  delete deviceAccess[chipId]
}

/**
 * Оплата живёт на блоке, а не на аккаунте: срок один и тот же у всех, кто имеет доступ,
 * поэтому здесь кэшируем ответ /devices/{id}/payment, а не флаг пользователя.
 */
export const devicePayment = reactive({})

export function rememberPayment(info) {
  if (info?.chipId) devicePayment[info.chipId] = info
}

export function forgetPayment(chipId) {
  delete devicePayment[chipId]
}

/** Блок оплачен? Неизвестное считаем оплаченным: сервер всё равно вернёт 402. */
export function isDevicePaid(chipId) {
  const info = devicePayment[chipId]
  if (!info) return true
  if (info.chargeable === false) return true
  return info.paid !== false
}

/** Права на конкретный блок: ADMIN — всегда, иначе роль из device_access. */
export function canControlDevice(chipId) {
  if (!auth.user) return false
  if (auth.user.role === 'ADMIN') return true
  return deviceAccess[chipId] === 'USER'
}

export function logout() {
  auth.user = null
  localStorage.removeItem('token')
  disconnectWs()
  settings.devices = {}
  settings.sensors = {}
  settings.loaded = false
}
