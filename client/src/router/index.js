import { createRouter, createWebHistory } from 'vue-router'
import { getToken, tokenExpiresAt, wasSessionExpired } from '../api/auth'
import { auth, deviceAccess, logout } from '../store/auth'

const isAdminRole = () => auth.user?.role === 'ADMIN'
import Login from '../views/Login.vue'

const routes = [
  { path: '/login', name: 'login', component: Login },
  {
    // Покупатель регистрируется сам: аккаунт + пара «ID + ключ» с комплектного листа.
    path: '/register',
    name: 'register',
    component: () => import('../views/Register.vue')
  },
  {
    // Ссылка-приглашение: открыта и без входа — по ней можно принять приглашение
    // или зарегистрироваться и получить доступ сразу.
    path: '/invite/:token',
    name: 'invite',
    component: () => import('../views/InviteAccept.vue')
  },
  {
    // Return point from Yandex ID; registered as Redirect URI in the Yandex app.
    path: '/oauth/yandex',
    name: 'oauth-yandex',
    component: () => import('../views/YandexCallback.vue')
  },
  {
    path: '/',
    name: 'dashboard',
    component: () => import('../views/Dashboard.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/settings',
    name: 'settings',
    component: () => import('../views/Settings.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('../views/Profile.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/admin/users',
    name: 'admin-users',
    component: () => import('../views/AdminUsers.vue'),
    meta: { requiresAuth: true, requiresAdmin: true }
  },
  {
    path: '/device/:chipId',
    name: 'device',
    component: () => import('../views/DeviceDetail.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/device/:chipId/relay/:relayNumber',
    name: 'relay-settings',
    component: () => import('../views/RelaySettings.vue'),
    meta: { requiresAuth: true, requiresControl: true }
  },
  {
    path: '/device/:chipId/sensor/:slot',
    name: 'sensor-settings',
    component: () => import('../views/SensorSettings.vue'),
    meta: { requiresAuth: true, requiresControl: true }
  },
  {
    path: '/sensor/:chipId',
    name: 'sensor',
    component: () => import('../views/SensorStats.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/sensor/:chipId/moisture-gradient',
    name: 'moisture-gradient',
    component: () => import('../views/MoistureGradient.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/sensor/:chipId/field-gradient/:fieldKey',
    name: 'field-gradient',
    component: () => import('../views/FieldGradient.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/sensor/:chipId/calibrate',
    name: 'sensor-calibrate',
    component: () => import('../views/SensorCalibrate.vue'),
    meta: { requiresAuth: true, requiresControl: true }
  },
  {
    path: '/sensor/:chipId/settings',
    name: 'sensor-standalone-settings',
    component: () => import('../views/SensorSettings.vue'),
    meta: { requiresAuth: true, requiresControl: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = getToken()
  if (to.meta.requiresAuth && !token) {
    next(wasSessionExpired() ? { path: '/login', query: { expired: '1' } } : '/login')
    return
  }
  // Tab reopened after the session ran out: say so instead of showing stale data.
  if (to.meta.requiresAuth && tokenExpiresAt(token) <= Date.now()) {
    logout()
    next({ path: '/login', query: { expired: '1' } })
    return
  }
  const role = auth.user?.role
  // Пока /api/auth/me ещё не загружен, проверку роли пропускаем — сервер всё равно
  // не отдаст чужие устройства и отвергнет команду.
  if (to.meta.requiresAdmin && role && role !== 'ADMIN') {
    next('/')
    return
  }
  // Права «только чтение» живут на устройстве: закрываем настройки, если роль
  // этого блока уже известна. Если роль ещё не загружена, не блокируем —
  // сервер всё равно вернёт 403 на запись.
  if (to.meta.requiresControl && !isAdminRole() && to.params.chipId
    && deviceAccess[to.params.chipId] === 'VIEWER') {
    next('/')
    return
  }
  next()
})

export default router
