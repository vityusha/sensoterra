<template>
  <div class="google-auth">
    <div v-if="unavailable" class="note">{{ unavailable }}</div>
    <div v-show="!unavailable" ref="button" class="button-slot"></div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { authStatus } from '../api/auth'

/**
 * Кнопка входа через Google Identity Services.
 *
 * Скрипт Google — единственный внешний источник в приложении: он же рисует
 * официальную кнопку и отдаёт нам id_token, который сервер проверяет сам.
 * Никакого redirect'а и client_secret — только токен.
 */
const props = defineProps({
  /** Текст кнопки Google: signin_with — вход, signup_with — регистрация. */
  text: { type: String, default: 'signin_with' },
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['credential', 'error'])

const button = ref(null)
const unavailable = ref('')

let gsiPromise = null
let rendered = null

function loadGsi() {
  if (gsiPromise) return gsiPromise
  gsiPromise = new Promise((resolve, reject) => {
    if (window.google?.accounts?.id) {
      resolve(window.google.accounts.id)
      return
    }
    const script = document.createElement('script')
    script.src = 'https://accounts.google.com/gsi/client'
    script.async = true
    script.defer = true
    script.onload = () => (window.google?.accounts?.id
      ? resolve(window.google.accounts.id)
      : reject(new Error('Google script loaded without API')))
    script.onerror = () => reject(new Error('Google script failed to load'))
    document.head.appendChild(script)
  })
  return gsiPromise
}

async function render() {
  if (unavailable.value || !button.value) return
  if (!window.isSecureContext) {
    // Google отдаёт токен только защищённому origins: с http на LAN-адресе кнопка
    // не заработает, поэтому честно говорим об этом, вместо тихой ошибки.
    unavailable.value = 'Вход через Google работает только по HTTPS'
    return
  }
  let status
  try {
    status = await authStatus()
  } catch {
    unavailable.value = ''
    return
  }
  if (!status.googleEnabled || !status.googleClientId) {
    unavailable.value = ''
    return
  }
  try {
    const id = await loadGsi()
    rendered?.cancel?.()
    rendered = id
    id.initialize({
      client_id: status.googleClientId,
      callback: response => emit('credential', response.credential),
      ux_mode: 'popup',
      cancel_on_tap_outside: false
    })
    drawButton()
  } catch {
    unavailable.value = 'Не удалось загрузить вход через Google'
  }
}

// Google draws a fixed pixel width, so match the slot (the form's width) to line up with
// the other buttons; GIS accepts 200..400 px.
function drawButton() {
  if (!rendered || !button.value) return
  const width = Math.round(button.value.clientWidth)
  if (!width || width === drawnWidth) return
  drawnWidth = width
  rendered.renderButton(button.value, {
    theme: props.text === 'signup_with' ? 'outline' : 'filled_black',
    size: 'large',
    shape: 'pill',
    text: props.text,
    width: Math.min(400, Math.max(200, width))
  })
}

let drawnWidth = 0
let resizeObserver = null
let resizeTimer = null

onMounted(() => {
  render()
  resizeObserver = new ResizeObserver(() => {
    clearTimeout(resizeTimer)
    resizeTimer = setTimeout(drawButton, 150)
  })
  if (button.value) resizeObserver.observe(button.value)
})
watch(() => props.disabled, value => {
  if (button.value) button.value.style.pointerEvents = value ? 'none' : ''
})
onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  clearTimeout(resizeTimer)
  rendered?.cancel?.()
})
</script>

<style scoped>
.google-auth { margin-top: 0.75rem; }
/* Google renders the button in an iframe whose page is light; with the app's dark
   color-scheme inherited, the browser paints an opaque white backdrop behind it. */
.button-slot { display: flex; justify-content: center; width: 100%; max-width: 320px; min-height: 44px;
  margin: 0 auto; color-scheme: light; }
.note { color: var(--muted-fg); font-size: 0.75rem; text-align: center; }
</style>
