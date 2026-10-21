<template>
  <div class="callback">
    <div class="card">
      <img class="logo" src="/favicon.svg" alt="" width="56" height="56" />
      <template v-if="!error">
        <p class="muted">Входим через Яндекс ID...</p>
      </template>
      <template v-else>
        <div class="error">{{ error }}</div>
        <RouterLink class="back" :to="backTo">Вернуться</RouterLink>
      </template>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { acceptInvite, loginWithYandex, setToken } from '../api/auth'
import { enterSession } from '../store/auth'
import { pushNotification } from '../store/notifications'
import { takeYandexContext } from '../utils/yandexOAuth'

/**
 * Return point from oauth.yandex.ru. Checks state, sends the code to the server and
 * finishes what the starting page asked for: plain sign-in, sign-up with a device pair,
 * or accepting an invite.
 */
const route = useRoute()
const router = useRouter()
const error = ref('')
const backTo = ref('/login')

function serverMessage(e) {
  const data = e.response?.data
  return data?._embedded?.errors?.[0]?.message || data?.message || ''
}

onMounted(async () => {
  const { code, state, error: yandexError } = route.query
  const context = takeYandexContext(String(state || ''))
  backTo.value = context?.returnTo || '/login'
  // The code is one-time: drop it from the address bar and history right away.
  router.replace({ path: route.path })

  if (yandexError) {
    error.value = yandexError === 'access_denied' ? 'Вход через Яндекс отменён' : 'Яндекс не выполнил вход'
    return
  }
  if (!context) {
    error.value = 'Ссылка входа устарела — начните вход через Яндекс заново'
    return
  }
  if (!code) {
    error.value = 'Яндекс не вернул код входа'
    return
  }

  let data
  try {
    data = await loginWithYandex(String(code), context.verifier, context.chipId ?? null, context.key ?? null)
  } catch (e) {
    error.value = serverMessage(e)
      || (context.chipId ? 'ID или ключ не подошли — аккаунт не создан' : 'Не удалось войти через Яндекс')
    return
  }
  setToken(data.token)
  await enterSession({ id: data.id, username: data.username, email: data.email, role: data.role,
    authProvider: 'yandex' })

  if (context.inviteToken) {
    try {
      await acceptInvite(context.inviteToken)
      pushNotification('Приглашение принято')
    } catch (e) {
      // Already has access (409) is fine; anything else is shown on the invite page itself.
      if (e.response?.status !== 409) {
        router.replace(`/invite/${context.inviteToken}`)
        return
      }
    }
  } else if (context.intent === 'register') {
    pushNotification(data.deviceBound ? 'Аккаунт создан, блок привязан' : 'Аккаунт создан')
  }
  router.replace('/')
})
</script>

<style scoped>
.callback { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: var(--bg); padding: 1rem; }
.card { background: var(--bg-card); padding: 2rem 1.5rem; border-radius: 16px; width: min(360px, 100%); text-align: center; }
.logo { display: block; margin: 0 auto 1rem; border-radius: 14px; }
.muted { color: var(--text-dim); font-size: 0.9rem; }
.error { color: var(--danger); font-size: 0.9rem; line-height: 1.45; }
.back { display: inline-block; margin-top: 1rem; color: var(--link); text-decoration: none; font-size: 0.9rem; }
</style>
