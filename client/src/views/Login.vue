<template>
  <div class="login-container">
    <div class="login-card">
      <img class="logo" src="/favicon.svg" alt="" width="72" height="72" />
      <h1>Sens-O-Terra</h1>
      <p class="subtitle">{{ isBootstrap ? 'Первый запуск — создайте админа' : 'Вход' }}</p>

      <div v-if="error" class="error">{{ error }}</div>

      <form @submit.prevent="submit">
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
          {{ loading ? '...' : (isBootstrap ? 'Создать' : 'Войти') }}
        </button>
      </form>

      <template v-if="!isBootstrap && googleEnabled">
        <div class="divider"><span>или</span></div>
        <GoogleAuthButton :disabled="loading" @credential="onGoogle" @error="onGoogleError" />
        <p class="hint">Через Google аккаунт заводится сразу — пароль придумывать не нужно.</p>
      </template>

      <p v-if="!isBootstrap" class="reg-link">
        Получили блок? <RouterLink to="/register">Зарегистрируйтесь</RouterLink> —
        по ID и ключу с комплектного листа.
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { login, bootstrap, authStatus, loginWithGoogle, setToken } from '../api/auth'
import { enterSession } from '../store/auth'
import GoogleAuthButton from '../components/GoogleAuthButton.vue'

const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const isBootstrap = ref(false)
const showPassword = ref(false)
const googleEnabled = ref(false)

onMounted(async () => {
  try {
    const res = await authStatus()
    isBootstrap.value = res.bootstrapNeeded === true
    googleEnabled.value = res.googleEnabled === true
  } catch {
    isBootstrap.value = false
  }
})

async function onGoogle(idToken) {
  loading.value = true
  error.value = ''
  try {
    const data = await loginWithGoogle(idToken)
    setToken(data.token)
    await enterSession({ id: data.id, username: data.username, role: data.role,
      })
    router.push('/')
  } catch (e) {
    error.value = e.response?.data?.message || 'Не удалось войти через Google'
  } finally {
    loading.value = false
  }
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
    const user = isBootstrap.value
      ? await bootstrap(user_, password.value)
      : await login(user_, password.value)
    await enterSession(user)
    router.push('/')
  } catch (e) {
    error.value = e.response?.data?.message || 'Ошибка входа'
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
.error { color: var(--danger); font-size: 0.85rem; margin-bottom: 1rem; }
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
