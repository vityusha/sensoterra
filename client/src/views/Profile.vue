<template>
  <div class="profile">
    <div class="header-row">
      <button @click="$router.push('/')" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
      <h2>Профиль</h2>
    </div>

    <section class="section">
      <h3>Аккаунт</h3>
      <dl class="account">
        <div class="account-row">
          <dt>Логин</dt>
          <dd>{{ user?.username }}</dd>
        </div>
        <div class="account-row">
          <dt>Роль</dt>
          <dd>{{ roleTitle }}</dd>
        </div>
        <div class="account-row" v-if="user?.email">
          <dt>Почта</dt>
          <dd>{{ user.email }}</dd>
        </div>
      </dl>
      <p class="hint">
        Логин и почту здесь поменять нельзя: по логину аккаунт находится при входе, а почта
        сейчас ни для чего не используется. Смена пароля — ниже.
      </p>
    </section>

    <section class="section">
      <h3>Пароль</h3>

      <p class="note" v-if="isExternal">
        Аккаунт создан через {{ providerTitle }}: пароля у него нет, вместо него —
        вход на стороне Google. Своего пароля здесь не существует, поэтому и менять нечего.
        Если нужен вход без Google — заведите пароль администратору через «Пользователи».
      </p>

      <template v-else>
        <form v-if="!changed" @submit.prevent="submit">
          <label>
            <span>Текущий пароль</span>
            <div class="password-field">
              <input v-model="form.current" :type="show.current ? 'text' : 'password'"
                autocomplete="current-password" autocapitalize="none" autocorrect="off" spellcheck="false"
                required />
              <button type="button" class="toggle-password"
                :aria-label="show.current ? 'Скрыть пароль' : 'Показать пароль'"
                :title="show.current ? 'Скрыть пароль' : 'Показать пароль'"
                @click="show.current = !show.current">
                <svg v-if="show.current" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
                  stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path
                    d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                  <line x1="1" y1="1" x2="23" y2="23" />
                </svg>
                <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
                  stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
              </button>
            </div>
          </label>

          <label>
            <span>Новый пароль</span>
            <div class="password-field">
              <input v-model="form.next" :type="show.next ? 'text' : 'password'" autocomplete="new-password"
                autocapitalize="none" autocorrect="off" spellcheck="false" :minlength="MIN_LENGTH" required />
              <button type="button" class="toggle-password"
                :aria-label="show.next ? 'Скрыть пароль' : 'Показать пароль'"
                :title="show.next ? 'Скрыть пароль' : 'Показать пароль'"
                @click="show.next = !show.next">
                <svg v-if="show.next" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
                  stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path
                    d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                  <line x1="1" y1="1" x2="23" y2="23" />
                </svg>
                <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
                  stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
              </button>
            </div>
          </label>

          <label>
            <span>Новый пароль ещё раз</span>
            <div class="password-field">
              <input v-model="form.repeat" :type="show.repeat ? 'text' : 'password'" autocomplete="new-password"
                autocapitalize="none" autocorrect="off" spellcheck="false" required />
              <button type="button" class="toggle-password"
                :aria-label="show.repeat ? 'Скрыть пароль' : 'Показать пароль'"
                :title="show.repeat ? 'Скрыть пароль' : 'Показать пароль'"
                @click="show.repeat = !show.repeat">
                <svg v-if="show.repeat" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
                  stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path
                    d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                  <line x1="1" y1="1" x2="23" y2="23" />
                </svg>
                <svg v-else viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
                  stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                  <circle cx="12" cy="12" r="3" />
                </svg>
              </button>
            </div>
          </label>

          <p class="hint">Не короче {{ MIN_LENGTH }} символов.</p>
          <div v-if="error" class="error">{{ error }}</div>
          <button type="submit" :disabled="busy">
            {{ busy ? '...' : 'Сменить пароль' }}
          </button>
        </form>

        <p class="note" v-else>
          Пароль изменён. Дальше входить нужно уже с ним.
          <button class="btn small" @click="reset">Сменить ещё раз</button>
        </p>
      </template>
    </section>
  </div>
</template>

<script setup>
import { reactive, ref, computed } from 'vue'
import { auth } from '../store/auth'
import { changePassword } from '../api/auth'
import { pushNotification } from '../store/notifications'

const MIN_LENGTH = 8

const form = reactive({ current: '', next: '', repeat: '' })
const show = reactive({ current: false, next: false, repeat: false })
const busy = ref(false)
const error = ref('')
const changed = ref(false)

const user = computed(() => auth.user)

/** Внешний вход (Google): своего пароля у такого аккаунта нет вовсе. */
const isExternal = computed(() => !!user.value?.authProvider)

const providerTitle = computed(() => (user.value?.authProvider === 'google' ? 'Google' : 'внешнего провайдера'))

const roleTitle = computed(() => (user.value?.role === 'ADMIN' ? 'администратор' : 'пользователь'))

/**
 * Micronaut кладёт подробности в _embedded.errors[0].message, а в message кладёт общий
 * текст статуса («Bad Request»), поэтому берём именно оттуда.
 */
function detailMessage(err) {
  const data = err.response?.data
  return data?._embedded?.errors?.[0]?.message || data?.message || ''
}

/**
 * Сервер отвечает по-английски, а форма русская. Переводим известные причины, чтобы
 * пользователь не гадал; неизвестное отдаём как есть — там может быть что-то важное.
 */
function errorText(err) {
  if (err.response?.status === 401) return 'Текущий пароль указан неверно'
  const message = detailMessage(err)
  if (message.includes('at least')) return `Новый пароль должен быть не короче ${MIN_LENGTH} символов`
  if (message.includes('must differ')) return 'Новый пароль должен отличаться от текущего'
  if (message.includes('signs in with')) return 'Этот аккаунт входит через внешнего провайдера, пароля у него нет'
  return message || 'Не удалось сменить пароль'
}

function reset() {
  form.current = ''
  form.next = ''
  form.repeat = ''
  show.current = false
  show.next = false
  show.repeat = false
  error.value = ''
  changed.value = false
}

async function submit() {
  if (busy.value) return
  error.value = ''
  if (form.next.length < MIN_LENGTH) {
    error.value = `Новый пароль должен быть не короче ${MIN_LENGTH} символов`
    return
  }
  if (form.next !== form.repeat) {
    error.value = 'Новый пароль и его повтор не совпадают'
    return
  }
  busy.value = true
  try {
    await changePassword(form.current, form.next)
    changed.value = true
    pushNotification('Пароль изменён')
  } catch (err) {
    error.value = errorText(err)
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
/* Ширина как у остальных страниц (Settings, RelaySettings, AdminUsers): без неё форма
   растягивается на весь монитор и поля превращаются в узкую полосу текста. */
.profile { max-width: 600px; margin: 0 auto; padding: 1rem; }
.header-row { display: flex; align-items: center; gap: 0.75rem; position: sticky; top: 0; z-index: 50; margin: -1rem -1rem 1.5rem; padding: 1rem; background: var(--bg); }
.header-row h2 { color: var(--text-strong); flex: 1; }
.section { margin-bottom: 2rem; }
.section h3 { color: var(--text-muted); font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 0.75rem; }
.account { display: flex; flex-direction: column; gap: 0.6rem; }
.account-row { display: flex; align-items: center; justify-content: space-between; gap: 0.7rem; background: var(--bg-card); padding: 0.7rem 1rem; border-radius: 8px; }
dt { color: var(--text-muted); font-size: 0.85rem; }
dd { color: var(--text); font-size: 0.85rem; overflow-wrap: anywhere; text-align: right; }
label { display: block; text-align: left; margin-bottom: 0.9rem; }
label span { color: var(--text-muted); font-size: 0.85rem; display: block; margin-bottom: 0.3rem; }
input { width: 100%; padding: 0.6rem; border: 1px solid var(--border); border-radius: 8px; background: var(--bg); color: var(--text); font-size: 0.95rem; }
.password-field { position: relative; }
.password-field input { padding-right: 2.6rem; }
.toggle-password { position: absolute; top: 50%; right: 0.35rem; transform: translateY(-50%); width: auto; margin: 0; padding: 0.35rem; border: none; border-radius: 6px; background: transparent; color: var(--text-dim); cursor: pointer; line-height: 0; }
.toggle-password:hover { color: var(--text); }
.hint { color: var(--text-dim); font-size: 0.72rem; line-height: 1.4; margin: 0.6rem 0 0; }
.note { color: var(--muted-fg); font-size: 0.8rem; line-height: 1.5; background: var(--bg-card); padding: 0.9rem 1rem; border-radius: 8px; }
.note .btn { margin-left: 0.4rem; }
.error { color: var(--danger); font-size: 0.85rem; margin-bottom: 0.9rem; }
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.small { font-size: 0.75rem; padding: 0.3rem 0.6rem; }
.btn.secondary { background: var(--bg-accent); }
button[type='submit'] { width: 100%; padding: 0.7rem; border: none; border-radius: 8px; background: var(--success); color: #fff; font-size: 1rem; cursor: pointer; }
button[type='submit']:disabled { opacity: 0.6; }
</style>
