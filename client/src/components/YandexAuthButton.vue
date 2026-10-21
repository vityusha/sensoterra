<template>
  <div v-if="clientId" class="yandex-auth">
    <button type="button" class="yandex-button" :disabled="disabled || leaving" @click="start">
      <span class="logo" aria-hidden="true">Я</span>
      <span>{{ leaving ? 'Переходим в Яндекс...' : label }}</span>
    </button>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { authStatus } from '../api/auth'
import { startYandexLogin } from '../utils/yandexOAuth'

/**
 * Yandex ID button. Leaves the page for oauth.yandex.ru; the result comes back to the
 * /oauth/yandex view, so everything the page needs afterwards goes through prepare().
 */
const props = defineProps({
  label: { type: String, default: 'Войти с Яндекс ID' },
  disabled: { type: Boolean, default: false },
  /** Returns the context to carry through the redirect, or null to stay (e.g. form invalid). */
  prepare: { type: Function, default: () => ({}) }
})

const clientId = ref('')
const leaving = ref(false)

onMounted(async () => {
  try {
    const status = await authStatus()
    clientId.value = status.yandexEnabled ? (status.yandexClientId || '') : ''
  } catch {
    clientId.value = ''
  }
})

async function start() {
  const context = props.prepare()
  if (context === null) return
  leaving.value = true
  try {
    await startYandexLogin(clientId.value, context)
  } catch {
    leaving.value = false
  }
}
</script>

<style scoped>
.yandex-auth { margin-top: 0.75rem; display: flex; justify-content: center; }
.yandex-button { display: inline-flex; align-items: center; justify-content: center; gap: 0.55rem;
  width: 100%; max-width: 320px; min-height: 44px; margin: 0; padding: 0 1rem; border: none; border-radius: 999px;
  background: #000; color: #fff; font-size: 0.95rem; font-weight: 500; cursor: pointer; }
.yandex-button:disabled { opacity: 0.6; cursor: default; }
.logo { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px;
  border-radius: 50%; background: #fc3f1d; color: #fff; font-size: 0.85rem; font-weight: 700; line-height: 1; }
</style>
