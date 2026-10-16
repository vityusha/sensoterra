<template>
  <div class="toast-host">
    <div v-for="n in notifications" :key="n.id" class="toast" :class="n.type" @click="dismissNotification(n.id)">
      {{ n.text }}
    </div>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { notifications, dismissNotification, connectWs } from '../store/notifications'

onMounted(() => {
  connectWs()
})
</script>

<style scoped>
.toast-host {
  position: fixed;
  top: 1rem;
  right: 1rem;
  z-index: 9999;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  max-width: 360px;
}
.toast {
  padding: 0.7rem 1rem;
  border-radius: 8px;
  font-size: 0.82rem;
  color: #fff;
  cursor: pointer;
  background: var(--bg-card);
  border: 1px solid var(--border);
}
.toast.error { background: var(--bg-accent); border-color: var(--danger); }
.toast.warn  { background: #5c4a1a; border-color: var(--warn); }
.toast.info  { background: var(--bg-active); border-color: var(--accent); }
/* Телефон: тосты во всю ширину, иначе длинный текст обрезается краем экрана. */
@media (max-width: 480px) {
  .toast-host {
    top: 0.6rem;
    left: 0.6rem;
    right: 0.6rem;
    max-width: none;
    gap: 0.4rem;
  }
  .toast { font-size: 0.85rem; padding: 0.6rem 0.75rem; }
}
</style>
