<template>
  <div class="register">
    <form class="card" @submit.prevent="submit">
      <h2>Регистрация</h2>
      <p class="lead">
        Заведите аккаунт и подтвердите свой блок. На листе, который шёл вместе с устройством,
        напечатаны ID и ключ.
      </p>

      <label>Логин</label>
      <input v-model="form.username" autocomplete="username" autocapitalize="none" spellcheck="false" required />

      <label>Пароль</label>
      <div class="pwd">
        <input v-model="form.password" :type="showPwd ? 'text' : 'password'"
          autocomplete="new-password" required />
        <button type="button" class="eye" :aria-label="showPwd ? 'Скрыть пароль' : 'Показать пароль'"
          :title="showPwd ? 'Скрыть пароль' : 'Показать пароль'" @click="showPwd = !showPwd">
          <svg v-if="!showPwd" viewBox="0 0 24 24" width="18" height="18" fill="none"
            stroke="currentColor" stroke-width="1.8" aria-hidden="true">
            <path d="M2 12s3.6-6.5 10-6.5S22 12 22 12s-3.6 6.5-10 6.5S2 12 2 12z" />
            <circle cx="12" cy="12" r="2.6" />
          </svg>
          <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none"
            stroke="currentColor" stroke-width="1.8" aria-hidden="true">
            <path d="M4 4l16 16" />
            <path d="M9.9 5.7A9.9 9.9 0 0112 5.5c6.4 0 10 6.5 10 6.5a17 17 0 01-3.2 4.1M6.6 6.6A17 17 0 002 12s3.6 6.5 10 6.5c1.2 0 2.3-.2 3.3-.6" />
            <path d="M9.5 9.7a2.6 2.6 0 003.8 3.7" />
          </svg>
        </button>
      </div>

      <label>Повторите пароль</label>
      <input v-model="form.repeat" :type="showPwd ? 'text' : 'password'" autocomplete="new-password" required />

      <div class="device-box">
        <div class="device-title">Свой блок (если уже купили)</div>
        <p class="hint">
          Оставьте пустым, если блока пока нет. Позже можно привязать его в разделе устройств.
        </p>
        <input v-model="form.chipId" placeholder="ID устройства, напр. 13034338" inputmode="numeric" />
        <input v-model="form.key" placeholder="Ключ с листа: XXXX-XXXX-XXXX"
          autocapitalize="characters" autocorrect="off" spellcheck="false" />
      </div>

      <div v-if="error" class="error">{{ error }}</div>

      <button type="submit" class="btn" :disabled="busy">
        {{ busy ? 'Создаём...' : 'Создать аккаунт' }}
      </button>

      <template v-if="googleEnabled">
        <div class="divider"><span>или</span></div>
        <GoogleAuthButton text="signup_with" :disabled="busy" @credential="onGoogle" />
        <p class="hint">
          Аккаунт создастся сам, а блок подтвердится по ID и ключу, если они заполнены выше.
        </p>
      </template>

      <p class="switch"><RouterLink to="/login">Уже есть аккаунт? Войти</RouterLink></p>
    </form>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { authStatus, loginWithGoogle, register, setToken } from '../api/auth'
import { enterSession } from '../store/auth'
import { pushNotification } from '../store/notifications'
import GoogleAuthButton from '../components/GoogleAuthButton.vue'

const router = useRouter()
const form = reactive({ username: '', password: '', repeat: '', chipId: '', key: '' })
const showPwd = ref(false)
const busy = ref(false)
const error = ref('')
const googleEnabled = ref(false)

onMounted(async () => {
  try {
    googleEnabled.value = (await authStatus()).googleEnabled === true
  } catch {
    googleEnabled.value = false
  }
})

/** Пара «ID + ключ»: заполнена целиком или никак, ровно как при регистрации по паролю. */
function devicePair() {
  const chipId = form.chipId.trim()
  const key = form.key.trim()
  if (chipId && !key) {
    error.value = 'Введите ключ с листа вместе с ID'
    return null
  }
  if (key && !chipId) {
    error.value = 'Введите ID устройства вместе с ключом'
    return null
  }
  return { chipId: chipId ? Number(chipId) : null, key: key || null }
}

async function onGoogle(idToken) {
  error.value = ''
  const pair = devicePair()
  if (!pair) return
  busy.value = true
  try {
    const data = await loginWithGoogle(idToken, pair.chipId, pair.key)
    setToken(data.token)
    await enterSession({ id: data.id, username: data.username, role: data.role,
      })
    pushNotification(data.deviceBound ? 'Аккаунт создан, блок привязан' : 'Аккаунт создан')
    router.push('/')
  } catch (e) {
    error.value = e.response?.data?.message
      || (pair.chipId ? 'ID или ключ не подошли — аккаунт не создан' : 'Не удалось войти через Google')
  } finally {
    busy.value = false
  }
}

async function submit() {
  error.value = ''
  if (form.password !== form.repeat) {
    error.value = 'Пароли не совпадают'
    return
  }
  // Пара «ID + ключ» либо заполнена целиком, либо не заполнена вовсе: половина листа
  // ничего не подтверждает, а ошибку об id vs ключе сервер намеренно не различает.
  const chipId = form.chipId.trim()
  const key = form.key.trim()
  if (chipId && !key) {
    error.value = 'Введите ключ с листа вместе с ID'
    return
  }
  if (key && !chipId) {
    error.value = 'Введите ID устройства вместе с ключом'
    return
  }
  busy.value = true
  try {
    // Регистрация сразу отдаёт токен — второй запрос логина не нужен.
    const data = await register(form.username.trim(), form.password, chipId ? Number(chipId) : null,
      key || null)
    setToken(data.token)
    await enterSession({ id: data.id, username: data.username, role: data.role,
      })
    pushNotification(data.deviceBound ? 'Аккаунт создан, блок привязан' : 'Аккаунт создан')
    router.push('/')
  } catch (e) {
    error.value = e.response?.data?.message
      || (chipId ? 'ID или ключ не подошли — аккаунт не создан' : 'Не удалось создать аккаунт')
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.register { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 1rem; }
.card { width: 100%; max-width: 380px; background: var(--bg-card); border-radius: 14px; padding: 1.5rem; }
h2 { color: var(--text); margin: 0 0 0.5rem; font-size: 1.2rem; }
.lead { color: var(--text-muted); font-size: 0.8rem; line-height: 1.45; margin: 0 0 1rem; }
label { display: block; color: var(--text-muted); font-size: 0.72rem; margin: 0.7rem 0 0.25rem; }
input { width: 100%; box-sizing: border-box; padding: 0.55rem 0.65rem; border-radius: 6px;
  border: 1px solid var(--border); background: var(--bg); color: var(--text); font-size: 0.9rem; }
input:focus { outline: none; border-color: var(--border-soft); }
.pwd { position: relative; }
.pwd input { padding-right: 2.4rem; }
.eye { position: absolute; right: 0; top: 0; bottom: 0; width: 2.2rem; border: none; background: none;
  color: var(--text-muted); cursor: pointer; display: flex; align-items: center; justify-content: center; }
.eye:hover { color: var(--text); }
.device-box { margin-top: 1rem; padding: 0.85rem; background: var(--bg-card); border-radius: 10px; }
.device-title { color: var(--warn); font-size: 0.8rem; margin-bottom: 0.25rem; }
.hint { color: var(--muted-fg); font-size: 0.72rem; line-height: 1.4; margin: 0 0 0.6rem; }
.device-box input { margin-bottom: 0.45rem; font-family: monospace; }
.error { color: var(--on-accent); font-size: 0.8rem; margin-top: 0.75rem; }
.btn { width: 100%; margin-top: 1rem; padding: 0.6rem; border: none; border-radius: 8px;
  background: var(--warn); color: var(--bg-card); font-size: 0.9rem; font-weight: 600; cursor: pointer; }
.btn:disabled { opacity: 0.6; cursor: default; }
.divider { display: flex; align-items: center; gap: 0.6rem; margin: 1rem 0 0.2rem; color: var(--muted-fg);
  font-size: 0.72rem; }
.divider::before, .divider::after { content: ''; flex: 1; height: 1px; background: var(--divider); }
.hint { color: var(--muted-fg); font-size: 0.72rem; line-height: 1.4; margin: 0.6rem 0 0; }
.switch { text-align: center; margin: 0.9rem 0 0; font-size: 0.8rem; }
.switch a { color: var(--link); text-decoration: none; }
</style>