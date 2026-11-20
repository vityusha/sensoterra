<template>
  <div ref="actions" class="header-actions">
    <AlarmBell />
    <button ref="toggle" type="button" class="menu-toggle" :aria-expanded="open" aria-haspopup="menu" aria-label="Меню" @click="open = !open">
      <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
        <circle cx="12" cy="5" r="2" />
        <circle cx="12" cy="12" r="2" />
        <circle cx="12" cy="19" r="2" />
      </svg>
    </button>
    <div v-if="open" class="menu-dropdown" role="menu" @click="onItemClick">
      <slot v-if="!accountFirst" />
      <div v-if="!accountFirst && $slots.default" class="menu-sep" role="separator"></div>
      <button type="button" role="menuitem" @click="go('/settings')">
        <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
          <path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.04.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/>
        </svg>
        Общая настройка
      </button>
      <button type="button" role="menuitem" @click="go('/profile')">
        <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/></svg>
        Профиль
      </button>
      <slot v-if="accountFirst" />
      <div v-if="accountFirst" class="menu-sep" role="separator"></div>
      <button type="button" role="menuitem" class="menu-danger" @click="signOut">
        <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z"/></svg>
        Выйти
      </button>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AlarmBell from './AlarmBell.vue'
import { logout } from '../store/auth'

defineProps({ accountFirst: Boolean })

const route = useRoute()
const router = useRouter()
const actions = ref(null)
const toggle = ref(null)
const open = ref(false)

function go(path) {
  if (route.path !== path) router.push({ path, query: { returnTo: route.fullPath } })
}

function signOut() {
  logout()
  router.push('/login')
}

function onItemClick(event) {
  const item = event.target.closest('button:not(:disabled)')
  if (!item) return
  if (item.getAttribute('aria-haspopup') === 'dialog') toggle.value?.focus()
  open.value = false
}

function onOutsideClick(event) {
  if (open.value && !actions.value?.contains(event.target)) open.value = false
}

function onEscape(event) {
  if (event.key !== 'Escape' || !open.value) return
  open.value = false
  toggle.value?.focus()
}

watch(() => route.fullPath, () => { open.value = false })

onMounted(() => {
  document.addEventListener('click', onOutsideClick)
  document.addEventListener('keydown', onEscape)
})

onUnmounted(() => {
  document.removeEventListener('click', onOutsideClick)
  document.removeEventListener('keydown', onEscape)
})
</script>

<style scoped>
.header-actions { display: flex; align-items: center; position: relative; flex: none; margin-left: auto; }
.menu-toggle { display: grid; place-items: center; width: 2.25rem; height: 2.25rem; padding: 0; border: none; border-radius: 50%; background: transparent; color: var(--text-strong); cursor: pointer; }
.menu-toggle:hover, .menu-toggle[aria-expanded='true'] { background: var(--bg-primary); }
.menu-toggle:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.menu-dropdown { position: absolute; top: calc(100% + 0.4rem); right: 0; z-index: 60; width: max-content; min-width: 13rem; max-width: calc(100vw - 2rem); padding: 0.35rem; display: flex; flex-direction: column; background: var(--bg-card); border: 1px solid var(--border-soft); border-radius: 10px; box-shadow: 0 8px 30px rgba(0, 0, 0, 0.45); }
.menu-dropdown :deep(button) { width: 100%; padding: 0.55rem 0.7rem; border: none; border-radius: 6px; display: flex; align-items: center; gap: 0.55rem; background: transparent; color: var(--text); font-size: 0.85rem; text-align: left; cursor: pointer; }
.menu-dropdown :deep(button:hover:not(:disabled)) { background: var(--bg-primary); }
.menu-dropdown :deep(button:disabled) { color: var(--btn-disabled-text); cursor: not-allowed; }
.menu-dropdown :deep(.menu-danger:not(:disabled)) { color: var(--danger); }
.menu-dropdown :deep(.menu-warn) { color: var(--warn); }
.menu-dropdown :deep(.menu-icon) { width: 1rem; height: 1rem; flex: none; opacity: 0.9; }
.menu-sep { height: 1px; margin: 0.3rem 0; background: var(--border-soft); }
.menu-sep:first-child { display: none; }
</style>
