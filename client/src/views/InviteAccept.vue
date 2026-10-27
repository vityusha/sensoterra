<template>
  <div class="invite">
    <div class="invite-card">
      <h1>Приглашение</h1>

      <div v-if="loading" class="muted">Загрузка...</div>

      <template v-else-if="invite">
        <div class="device-box">
          <div class="device-name">{{ invite.deviceName || `Устройство ${invite.chipId}` }}</div>
          <div class="device-meta">
            chip {{ invite.chipId }} · {{ deviceTypeLabel }} · пригласил {{ invite.invitedBy }}
          </div>
          <div class="device-meta" v-if="invite.expiresAt">Ссылка действует до {{ expiresText }}</div>
          <div class="device-meta" v-else>Ссылка бессрочная — без срока действия</div>
        </div>

        <!-- Аккаунт уже есть: забираем доступ -->
        <template v-if="isLoggedIn">
          <p v-if="isInviter" class="muted">
            Это приглашение создали вы ({{ auth.user.username }}) — доступ к устройству у вас уже есть.
          </p>
          <button v-else class="btn primary" @click="accept" :disabled="busy">
            {{ busy ? '...' : `Принять приглашение${auth.user?.username ? ` (${auth.user.username})` : ''}` }}
          </button>
        </template>

        <!-- Аккаунта нет: регистрация по этой же ссылке -->
        <template v-else>
          <p class="muted">Аккаунта ещё нет — придумайте логин и пароль, доступ к устройству вы получите сразу.</p>
          <form class="reg-form" @submit.prevent="register">
            <input v-model="username" placeholder="Логин" required autocapitalize="none"
              autocorrect="off" spellcheck="false" autocomplete="username" />
            <input v-model="password" type="password" placeholder="Пароль" required
              autocomplete="new-password" autocapitalize="none" spellcheck="false" />
            <input v-model="password2" :type="showPassword ? 'text' : 'password'" placeholder="Повторите пароль"
              required autocomplete="new-password" />
            <p class="muted">{{ PASSWORD_HINT }}</p>
            <button type="button" class="btn ghost eye" @click="showPassword = !showPassword"
              :aria-label="showPassword ? 'Скрыть пароль' : 'Показать пароль'">👁</button>
            <button type="submit" class="btn primary" :disabled="busy">
              {{ busy ? '...' : 'Зарегистрироваться и получить доступ' }}
            </button>
          </form>
          <template v-if="googleEnabled || yandexEnabled">
            <div class="divider"><span>или</span></div>
            <GoogleAuthButton v-if="googleEnabled" :disabled="busy" @credential="onGoogle" />
            <YandexAuthButton v-if="yandexEnabled" :disabled="busy" :prepare="yandexContext" />
          </template>
          <p class="muted small">
            Уже есть аккаунт? <router-link to="/login">Войти</router-link>, затем откройте эту ссылку снова.
          </p>
        </template>
      </template>

      <div v-else class="error">{{ error || 'Ссылка недействительна, истекла или уже использована' }}</div>

      <div v-if="actionError" class="error">{{ actionError }}</div>

      <button v-if="done" class="btn primary" @click="goHome">Перейти к устройствам</button>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getInvite, acceptInvite, authStatus, loginWithGoogle, registerByInvite } from '../api/auth'
import { getToken, setToken } from '../api/auth'
import { auth, enterSession, userFromAuthResponse } from '../store/auth'
import GoogleAuthButton from '../components/GoogleAuthButton.vue'
import YandexAuthButton from '../components/YandexAuthButton.vue'
import { PASSWORD_HINT, credentialsErrorText, passwordProblem, usernameProblem } from '../utils/credentials'

const route = useRoute()
const router = useRouter()

const invite = ref(null)
const loading = ref(true)
const busy = ref(false)
const done = ref(false)
const error = ref('')
const actionError = ref('')
const username = ref('')
const password = ref('')
const password2 = ref('')
const showPassword = ref(false)
const googleEnabled = ref(false)
const yandexEnabled = ref(false)

const token = computed(() => String(route.params.token || ''))
const isLoggedIn = computed(() => !!getToken() && !!auth.user)
// Ссылку часто открывают сами себе же — с другого аккаунта или того же. Кнопку в этом
// случае прячем: сервер всё равно откажет, а «Bad request» покупателю ничего не объясняет.
const isInviter = computed(() => !!invite.value?.invitedBy
  && invite.value.invitedBy === auth.user?.username)

const deviceTypeLabel = computed(() => {
  switch (invite.value?.deviceType) {
    case 'BLOCK': return 'блок управления'
    case 'INDIVIDUAL_SENSOR': return 'автономный датчик'
    case 'BLOCK_SENSOR': return 'датчик блока'
    default: return invite.value?.deviceType || 'устройство'
  }
})

const expiresText = computed(() => {
  const raw = invite.value?.expiresAt
  if (!raw) return '—'
  const d = new Date(raw)
  return `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}.${d.getFullYear()}`
})

onMounted(load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    invite.value = await getInvite(token.value)
    try {
      const status = await authStatus()
      googleEnabled.value = status.googleEnabled === true
      yandexEnabled.value = status.yandexEnabled === true
    } catch {
      googleEnabled.value = false
      yandexEnabled.value = false
    }
  } catch (e) {
    invite.value = null
    if (e.response?.status !== 410 && e.response?.status !== 404) {
      error.value = e.response?.data?.message || 'Не удалось загрузить приглашение'
    }
  } finally {
    loading.value = false
  }
}

async function accept() {
  busy.value = true
  actionError.value = ''
  try {
    await acceptInvite(token.value)
    done.value = true
  } catch (e) {
    actionError.value = acceptErrorText(e)
  } finally {
    busy.value = false
  }
}

/** Ответы сервера по-английски, покупателю нужны свои слова — иначе просто «Bad request». */
function acceptErrorText(e) {
  const status = e.response?.status
  if (status === 400) return 'Это приглашение создали вы сами — доступ к устройству уже есть'
  if (status === 409) return 'Доступ к этому устройству у вас уже есть'
  if (status === 410) return 'Ссылка недействительна, истекла или уже использована'
  if (status === 401) return 'Нужно войти в аккаунт, который пригласили'
  return 'Не удалось принять приглашение'
}

async function register() {
  const problem = usernameProblem(username.value) || passwordProblem(password.value, username.value)
  if (problem) {
    actionError.value = problem
    return
  }
  if (password.value !== password2.value) {
    actionError.value = 'Пароли не совпадают'
    return
  }
  busy.value = true
  actionError.value = ''
  try {
    const res = await registerByInvite(token.value, username.value.trim(), password.value)
    setToken(res.token)
    await enterSession(res.user)
    done.value = true
  } catch (e) {
    const status = e.response?.status
    actionError.value = status === 409 ? 'Такой логин уже занят'
      : status === 410 ? 'Ссылка недействительна, истекла или уже использована'
        : status === 400 ? credentialsErrorText(e) || 'Не удалось зарегистрироваться'
          : 'Не удалось зарегистрироваться'
  } finally {
    busy.value = false
  }
}

/** Вход через Google сразу забирает приглашение: пароль и логин придумывать не нужно. */
async function onGoogle(idToken) {
  busy.value = true
  actionError.value = ''
  try {
    const res = await loginWithGoogle(idToken)
    setToken(res.token)
    await enterSession(userFromAuthResponse(res))
    await acceptInvite(token.value)
    done.value = true
  } catch (e) {
    actionError.value = e.response?.data?.message || 'Не удалось войти через Google'
  } finally {
    busy.value = false
  }
}

/** After the Yandex redirect the callback view accepts this invite. */
function yandexContext() {
  actionError.value = ''
  return { intent: 'invite', returnTo: `/invite/${token.value}`, inviteToken: token.value }
}

function goHome() {
  router.push('/')
}
</script>

<style scoped>
.invite { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: var(--bg); padding: 1rem; }
.invite-card { background: var(--bg-card); padding: 2rem 1.5rem; border-radius: 16px; width: min(400px, 100%); text-align: center; }
h1 { color: var(--text-strong); font-size: 1.4rem; margin-bottom: 1.25rem; }
.device-box { background: var(--bg); border-radius: 12px; padding: 0.9rem; margin-bottom: 1.25rem; }
.device-name { color: var(--text); font-size: 1rem; font-weight: 600; margin-bottom: 0.35rem; }
.device-meta { color: var(--text-dim); font-size: 0.8rem; line-height: 1.5; }
.muted { color: var(--text-dim); font-size: 0.85rem; line-height: 1.5; }
.muted.small { font-size: 0.78rem; margin-top: 0.75rem; }
.muted a { color: var(--accent); }
.reg-form { display: flex; flex-direction: column; gap: 0.5rem; margin-top: 0.75rem; position: relative; }
.reg-form input { padding: 0.6rem; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--text); font-size: 0.95rem; }
.eye { position: absolute; right: 0.4rem; top: 0.3rem; }
.btn { padding: 0.7rem 1rem; border: none; border-radius: 8px; font-size: 0.95rem; cursor: pointer; }
.btn.primary { width: 100%; background: var(--success); color: #fff; margin-top: 0.75rem; }
.btn.ghost { background: transparent; color: var(--text-dim); padding: 0.3rem 0.5rem; font-size: 1rem; }
.btn:disabled { opacity: 0.6; }
.divider { display: flex; align-items: center; gap: 0.6rem; margin: 1rem 0 0.2rem; color: var(--muted-fg);
  font-size: 0.72rem; }
.divider::before, .divider::after { content: ''; flex: 1; height: 1px; background: var(--divider); }
.error { color: var(--danger); font-size: 0.85rem; margin-top: 0.75rem; }
</style>