<template>
  <button
    class="alarm-bell"
    :class="{ active: $route.name === 'alarms' }"
    :aria-label="alarmState.unseen > 0 ? 'Уведомления: есть новые' : 'Уведомления'"
    @click="$router.push('/alarms')"
  >
    <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor" aria-hidden="true">
      <path d="M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"/>
    </svg>
    <span v-if="alarmState.unseen > 0" class="dot" aria-hidden="true"></span>
  </button>
</template>

<script setup>
import { onMounted } from 'vue'
import { alarmState, refreshAlarmSummary } from '../store/alarms'
import { connectWs } from '../store/notifications'

onMounted(() => {
  connectWs()
  refreshAlarmSummary()
})
</script>

<style scoped>
.alarm-bell {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: none;
  width: 2.25rem;
  height: 2.25rem;
  padding: 0;
  border: none;
  border-radius: 50%;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
}
.alarm-bell:hover, .alarm-bell.active { color: var(--text-strong); background: var(--btn-bg); }
.alarm-bell:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.dot {
  position: absolute;
  top: 0.3rem;
  right: 0.35rem;
  width: 0.6rem;
  height: 0.6rem;
  border-radius: 50%;
  background: var(--danger);
  border: 2px solid var(--bg);
}
</style>
