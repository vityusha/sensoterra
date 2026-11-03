<template>
  <div class="login-container">
    <div class="login-card">
      <img class="logo" src="/favicon.svg" alt="" width="72" height="72" />
      <h1>Sens-O-Terra</h1>
      <p class="subtitle">{{ subtitle }}</p>

      <div v-if="sessionExpiredNotice && !error && step === 'password'" class="notice">Сессия истекла, войдите снова</div>
      <div v-if="error" class="error">{{ error }}</div>

      <!-- Admin 2FA: code from the authenticator app (or a recovery code). -->
      <form v-if="step === 'mfa'" @submit.prevent="submitCode">
        <label v-if="!useRecovery">
          <span>Код из приложения-аутентификатора</span>
          <input ref="codeInput" v-model="code" class="code" type="text" inputmode="numeric" maxlength="6"
            autocomplete="one-time-code" pattern="[0-9 ]*" required />
        </label>
        <label v-else>
          <span>Резервный код</span>
          <input ref="codeInput" v-model="recoveryCode" type="text" required autocomplete="off"
            autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="xxxxx-xxxxx" />
        </label>
        <button type="submit" :disabled="loading">{{ loading ? '...' : 'Подтвердить' }}</button>
        <button type="button" class="link" @click="toggleRecovery">
          {{ useRecovery ? 'Ввести код из приложения' : 'Нет телефона? Войти по резервному коду' }}
        </button>
        <button type="button" class="link" @click="restart">Отмена</button>
      </form>

      <!-- Admin 2FA enrollment on the first sign-in. -->
      <form v-else-if="step === 'setup'" @submit.prevent="submitEnroll">
        <p class="hint left">
          Для администратора обязателен второй фактор. Отсканируйте QR-код приложением
          (Google Authenticator, Яндекс Ключ, Aegis, 1Password…) и введите код из него.
        </p>
        <img v-if="qrDataUrl" class="qr" :src="qrDataUrl" alt="QR-код для приложения-аутентификатора" width="200" height="200" />
        <p v-if="setupSecret" class="secret">
          <span>Ключ для ручного ввода</span>
          <code>{{ formattedSecret }}</code>
        </p>
        <label>
          <span>Код из приложения</span>
          <input v-model="code" class="code" type="text" inputmode="numeric" maxlength="6"
            autocomplete="one-time-code" pattern="[0-9 ]*" required />
        </label>
        <button type="submit" :disabled="loading || !setupSecret">{{ loading ? '...' : 'Включить' }}</button>
        <button type="button" class="link" @click="restart">Отмена</button>
      </form>

      <!-- Recovery codes: shown once, right after enrollment. -->
      <div v-else-if="step === 'codes'">
        <p class="hint left">
          Резервные коды — на случай потери телефона. Каждый срабатывает один раз.
          Сохраните их сейчас: больше они показаны не будут.
        </p>
        <ul class="codes">
          <li v-for="c in recoveryCodes" :key="c"><code>{{ c }}</code></li>
        </ul>
        <button type="button" class="secondary" @click="copyCodes">{{ copied ? 'Скопировано' : 'Скопировать' }}</button>
        <button type="button" @click="finishEnrollment">Я сохранил коды</button>
      </div>

      <form v-else @submit.prevent="submit">
        <label>
          <span>Логин</span>
          <input v-model="username" type="text" required autocomplete="username"
            autocapitalize="none" autocorrect="off" spellcheck="false" />
        </label>
        <label>
          <span>Пароль</span>
          <div class="password-field">
            <input v-model="password" :type="showPassword ? 'text' : 'password'" required
              autocomplete="current-password" autocapitalize="none" autocorrect="off" spellcheck="false" />
            <button type="button" class="toggle-password" :aria-label="showPassword ? 'Скрыть пароль' : 'Показать пароль'"
              :title="showPassword ? 'Скрыть пароль' : 'Показать пароль'" @click="showPassword = !showPassword">
              <svg v-if="showPassword" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                <line x1="1" y1="1" x2="23" y2="23" />
              </svg>
              <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                <circle cx="12" cy="12" r="3" />
              </svg>
            </button>
          </div>
        </label>
        <button type="submit" :disabled="loading">
          {{ loading ? '...' : 'Войти' }}
        </button>
      </form>

      <template v-if="step === 'password' && (googleEnabled || yandexEnabled)">
        <div class="divider"><span>или</span></div>
        <GoogleAuthButton v-if="googleEnabled" :disabled="loading" @credential="onGoogle" @error="onGoogleError" />
        <YandexAuthButton v-if="yandexEnabled" :disabled="loading" :prepare="yandexContext" />
        <p class="hint">Через {{ externalNames }} аккаунт заводится сразу — пароль придумывать не нужно.</p>
      </template>

      <p v-if="step === 'password'" class="reg-link">
        Получили блок? <RouterLink to="/register">Зарегистрируйтесь</RouterLink> —
        по ID и ключу с комплектного листа.
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import QRCode from 'qrcode'
import {
  login, authStatus, loginWithGoogle, setToken,
  needsMfa, takePendingMfa, mfaSetup, mfaEnroll, mfaVerify,
} from '../api/auth'
import { enterSession, userFromAuthResponse } from '../store/auth'
import { pushNotification } from '../store/notifications'
import GoogleAuthButton from '../components/GoogleAuthButton.vue'
import YandexAuthButton from '../components/YandexAuthButton.vue'
import { credentialsErrorText } from '../utils/credentials'

const router = useRouter()
const route = useRoute()
// Set by the session-expired handler (main.js) and the router guard.
const sessionExpiredNotice = computed(() => route.query.expired === '1')
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const showPassword = ref(false)
// password -> (admins) mfa | setup -> codes
const step = ref('password')
const mfaToken = ref('')
const code = ref('')
const recoveryCode = ref('')
const useRecovery = ref(false)
const codeInput = ref(null)
const setupSecret = ref('')
const qrDataUrl = ref('')
const recoveryCodes = ref([])
const copied = ref(false)
let enrolled = null
const subtitle = computed(() => ({
  mfa: 'Двухфакторная проверка',
  setup: 'Настройка двухфакторной проверки',
  codes: 'Резервные коды',
}[step.value] || 'Вход'))
const formattedSecret = computed(() => setupSecret.value.replace(/(.{4})/g, '$1 ').trim())
const googleEnabled = ref(false)
const yandexEnabled = ref(false)
const externalNames = computed(() => [googleEnabled.value && 'Google', yandexEnabled.value && 'Яндекс ID']
  .filter(Boolean).join(' или '))

onMounted(async () => {
  // Google/Yandex sign-in of an admin on another page (Register, invite, Yandex callback).
  const pending = route.query.mfa === '1' ? takePendingMfa() : null
  if (pending?.mfaToken) {
    router.replace({ path: '/login' })
    await beginMfa({ mfaToken: pending.mfaToken, mfaSetupRequired: pending.setup })
  }
  try {
    const res = await authStatus()
    googleEnabled.value = res.googleEnabled === true
    yandexEnabled.value = res.yandexEnabled === true
  } catch {
    // Status is only for the external sign-in buttons.
  }
})

/** The first factor passed: a session, or the admin's second step. */
async function afterFirstFactor(data, user) {
  if (needsMfa(data)) {
    await beginMfa(data)
    return
  }
  setToken(data.token)
  await enterSession(user)
  router.push('/')
}

async function beginMfa(data) {
  mfaToken.value = data.mfaToken
  code.value = ''
  recoveryCode.value = ''
  useRecovery.value = false
  if (data.mfaSetupRequired) {
    step.value = 'setup'
    try {
      const setup = await mfaSetup(data.mfaToken)
      setupSecret.value = setup.secret
      qrDataUrl.value = await QRCode.toDataURL(setup.otpauthUri, { margin: 1, width: 200 })
    } catch (e) {
      mfaFailed(e)
    }
  } else {
    step.value = 'mfa'
    await nextTick()
    codeInput.value?.focus()
  }
}

function mfaFailed(e) {
  const status = e.response?.status
  const message = e.response?.data?.message || ''
  if (status === 401 && /expired/i.test(message)) {
    restart()
    error.value = 'Время на ввод кода истекло — войдите снова'
  } else if (status === 401) {
    error.value = 'Неверный код'
  } else {
    error.value = message || 'Ошибка проверки кода'
  }
}

function restart() {
  step.value = 'password'
  mfaToken.value = ''
  setupSecret.value = ''
  qrDataUrl.value = ''
  code.value = ''
  recoveryCode.value = ''
  error.value = ''
}

async function toggleRecovery() {
  useRecovery.value = !useRecovery.value
  error.value = ''
  await nextTick()
  codeInput.value?.focus()
}

async function submitCode() {
  loading.value = true
  error.value = ''
  try {
    const data = await mfaVerify(mfaToken.value, useRecovery.value
      ? { recoveryCode: recoveryCode.value.trim() }
      : { code: code.value.replace(/\s/g, '') })
    setToken(data.token)
    await enterSession(data.user)
    if (useRecovery.value) {
      pushNotification(`Вход по резервному коду. Осталось кодов: ${data.recoveryCodesLeft}`)
    }
    router.push('/')
  } catch (e) {
    mfaFailed(e)
  } finally {
    loading.value = false
  }
}

async function submitEnroll() {
  loading.value = true
  error.value = ''
  try {
    enrolled = await mfaEnroll(mfaToken.value, code.value.replace(/\s/g, ''))
    recoveryCodes.value = enrolled.recoveryCodes
    step.value = 'codes'
  } catch (e) {
    mfaFailed(e)
  } finally {
    loading.value = false
  }
}

async function copyCodes() {
  try {
    await navigator.clipboard.writeText(recoveryCodes.value.join('\n'))
    copied.value = true
  } catch {
    error.value = 'Не удалось скопировать — перепишите коды вручную'
  }
}

async function finishEnrollment() {
  setToken(enrolled.token)
  await enterSession(enrolled.user)
  enrolled = null
  router.push('/')
}

async function onGoogle(idToken) {
  loading.value = true
  error.value = ''
  try {
    const data = await loginWithGoogle(idToken)
    await afterFirstFactor(data, userFromAuthResponse(data))
  } catch (e) {
    error.value = e.response?.data?.message || 'Не удалось войти через Google'
  } finally {
    loading.value = false
  }
}

function yandexContext() {
  return { intent: 'login', returnTo: '/login' }
}

function onGoogleError() {
  error.value = 'Вход через Google не удался'
}

async function submit() {
  loading.value = true
  error.value = ''
  // На телефоне клавиатура часто подмешивает пробел в конце — логин сравнивается точно.
  const user_ = username.value.trim()
  try {
    const data = await login(user_, password.value)
    await afterFirstFactor(data, data.user)
  } catch (e) {
    error.value = credentialsErrorText(e) || 'Ошибка входа'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-container { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: var(--bg); }
.login-card { background: var(--bg-card); padding: 2rem; border-radius: 16px; width: min(320px, 100%); text-align: center; }
.logo { display: block; width: 72px; height: 72px; margin: 0 auto 0.75rem; border-radius: 16px; }
h1 { color: var(--text-strong); font-size: 1.6rem; margin-bottom: 0.5rem; }
.subtitle { color: var(--text-dim); font-size: 0.9rem; margin-bottom: 1.5rem; }
label { display: block; text-align: left; margin-bottom: 1rem; }
label span { color: var(--text-muted); font-size: 0.85rem; display: block; margin-bottom: 0.3rem; }
input { width: 100%; padding: 0.6rem; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--text); font-size: 0.95rem; box-sizing: border-box; }
.password-field { position: relative; }
.password-field input { padding-right: 2.6rem; }
.toggle-password { position: absolute; top: 50%; right: 0.35rem; transform: translateY(-50%); width: auto; margin: 0; padding: 0.35rem; border: none; border-radius: 6px; background: transparent; color: var(--text-dim); cursor: pointer; line-height: 0; }
.toggle-password:hover { color: var(--text); }
.toggle-password:active { background: rgba(255,255,255,0.08); }
button { width: 100%; padding: 0.7rem; border: none; border-radius: 8px; background: var(--success); color: #fff; font-size: 1rem; cursor: pointer; margin-top: 0.5rem; }
button:disabled { opacity: 0.6; }
button.link { background: transparent; color: var(--link); font-size: 0.82rem; padding: 0.4rem; margin-top: 0.3rem; }
button.secondary { background: var(--bg); color: var(--text); border: 1px solid var(--border); }
input.code { font-size: 1.4rem; letter-spacing: 0.3em; text-align: center; font-variant-numeric: tabular-nums; }
.hint.left { text-align: left; font-size: 0.8rem; margin: 0 0 1rem; }
.qr { display: block; margin: 0 auto 0.75rem; border-radius: 8px; background: #fff; width: 200px; max-width: 100%; height: auto; }
.secret { text-align: left; margin: 0 0 1rem; }
.secret span { color: var(--text-muted); font-size: 0.8rem; display: block; margin-bottom: 0.25rem; }
.secret code, .codes code { font-family: ui-monospace, monospace; color: var(--text-strong); font-size: 0.9rem; word-break: break-all; }
.codes { list-style: none; padding: 0; margin: 0 0 1rem; display: grid; grid-template-columns: 1fr 1fr; gap: 0.4rem; }
.codes li { background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; padding: 0.35rem; }
.error { color: var(--danger); font-size: 0.85rem; margin-bottom: 1rem; }
.notice { color: var(--text-dim); font-size: 0.85rem; margin-bottom: 1rem; }
.divider { display: flex; align-items: center; gap: 0.6rem; margin: 1.1rem 0 0.2rem; color: var(--text-faint); font-size: 0.75rem; }
.divider::before, .divider::after { content: ''; flex: 1; height: 1px; background: var(--border-soft); }
.hint { color: var(--text-dim); font-size: 0.72rem; line-height: 1.4; margin: 0.6rem 0 0; }
.reg-link { color: var(--text-dim); font-size: 0.8rem; line-height: 1.4; margin: 1rem 0 0; }
.reg-link a { color: var(--link); text-decoration: none; }
/* Совсем узкий экран: меньше отступов, иначе карточка упирается в края. */
@media (max-width: 360px) {
  .login-container { padding: 0.75rem; }
  .login-card { padding: 1.25rem 1rem; border-radius: 12px; }
  .logo { width: 56px; height: 56px; margin-bottom: 0.5rem; }
  h1 { font-size: 1.35rem; }
  .subtitle { margin-bottom: 1rem; }
}
</style>
