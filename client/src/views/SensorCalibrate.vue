<template>
  <div class="sensor-calibrate">
    <div class="header-row">
      <button @click="cancel" class="btn small secondary">&larr;</button>
      <h2>Калибровка 0% влажности</h2>
    </div>

    <section class="section">
      <p class="cal-text">
        Чтобы откалибровать 0% влажности почвы, поместите датчик в полностью высушенную
        почву и вручную сделайте измерение.
      </p>

      <div class="cal-status" :class="statusClass">
        <template v-if="candidate == null">
          Ждём новое измерение датчика… Текущее: {{ baselineText }}
        </template>
        <template v-else-if="!candidateValid">
          Некорректное измерение ({{ candidate }})
        </template>
        <template v-else>
          Новое измерение: {{ candidate }} — можно сохранять
        </template>
      </div>

      <div class="cal-actions">
        <button class="btn" :disabled="candidate == null || !candidateValid || busy" @click="save">
          {{ busy ? 'Сохраняю...' : 'Сохранить' }}
        </button>
        <button class="btn secondary" :disabled="busy" @click="cancel">Отмена</button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSensorCatalog, getSensorTelemetry, getDevice, saveSensorCalibrationValue2 } from '../api/auth'
import { loadCalibration, calibrationFor, setCalibration } from '../store/calibration'
import { pushNotification, onWsMessage } from '../store/notifications'
import { fmtEventTimeWithZone, DEFAULT_ZONE_OFFSET_MIN } from '../utils/datetime'

const route = useRoute()
const router = useRouter()
const chipId = Number(route.params.chipId)
const queryDeviceId = route.query.deviceId ? Number(route.query.deviceId) : null

const baselineTime = ref(null) // время последнего измерения на момент открытия
const baselineText = ref('нет данных')
const candidate = ref(null) // moistureAdc нового измерения
const candidateTime = ref(null)
const busy = ref(false)
const zoneOffset = ref(DEFAULT_ZONE_OFFSET_MIN)

/**
 * Новое измерение бракуется, если value_2 не лежит строго между водой (value_3)
 * и воздухом (value_1) текущей калибровки — та же проверка, что на сервере.
 */
const candidateValid = computed(() => {
  const cal = calibrationFor(chipId)
  if (candidate.value == null || !cal) return false
  return candidate.value > cal.value3 && candidate.value < cal.value1
})

const statusClass = computed(() => {
  if (candidate.value != null && candidateValid.value) return 'ok'
  if (candidate.value != null) return 'error'
  return 'wait'
})

let deviceId = null

async function resolveDeviceId() {
  if (queryDeviceId) {
    deviceId = queryDeviceId
    return
  }
  try {
    const catalog = await getSensorCatalog()
    const entry = catalog.find(c => Number(c.chipId) === chipId)
    if (entry && !entry.individual && entry.blockRoles?.length) {
      deviceId = entry.blockRoles[0].blockId
    } else {
      deviceId = chipId
    }
  } catch {
    deviceId = chipId
  }
}

async function fetchLatest() {
  const list = await getSensorTelemetry(deviceId, chipId)
  return list?.[0] || null
}

/** Новое измерение: сначала принимаем ЛЮБОЕ более свежее (по времени)
 * относительно baseline, даже если невалидное — чтобы показать ошибку
 * и дать возможность повторить замер. Как только приняли кандидат — разрешаем
 * его заменить только на ещё более свежее (новое измерение). */
function consider(row) {
  if (!row || row.moistureAdc == null) return
  const t = row.time ? new Date(row.time).getTime() : null
  const bt = baselineTime.value ? new Date(baselineTime.value).getTime() : null
  if (bt != null && t != null && t <= bt) return
  if (candidate.value != null) {
    const ct = candidateTime.value ? new Date(candidateTime.value).getTime() : null
    if (ct != null && t != null && t <= ct) return
  }
  candidate.value = row.moistureAdc
  candidateTime.value = row.time || null
}

async function save() {
  if (candidate.value == null || !candidateValid.value) return
  busy.value = true
  try {
    const data = await saveSensorCalibrationValue2(chipId, candidate.value)
    setCalibration(chipId, { value1: data.value1, value2: data.value2, value3: data.value3 })
    pushNotification('Калибровка сохранена', 'success')
    router.back()
  } catch (e) {
    pushNotification(e?.response?.data?.message || 'Не удалось сохранить калибровку', 'error')
  } finally {
    busy.value = false
  }
}

function cancel() {
  router.back()
}

let refreshTimer = null
let wsUnsub = null
let wsRefreshTimer = null

onMounted(async () => {
  await resolveDeviceId()
  try {
    const d = await getDevice(deviceId)
    if (d?.tzOffsetMinutes != null) zoneOffset.value = d.tzOffsetMinutes
  } catch { /* ignore */ }
  await loadCalibration(chipId).catch(() => null)
  try {
    const latest = await fetchLatest()
    baselineTime.value = latest?.time || null
    baselineText.value = latest?.time
      ? fmtEventTimeWithZone(latest.time, zoneOffset.value)
      : 'нет данных'
  } catch { /* ignore */ }
  refreshTimer = setInterval(async () => {
    try { consider(await fetchLatest()) } catch { /* ignore */ }
  }, 15000)
  wsUnsub = onWsMessage((msg) => {
    if (msg.type !== 'sensor' || msg.chipId === undefined || msg.chipId === null) return
    if (String(msg.chipId) !== String(chipId)) return
    if (wsRefreshTimer) return
    wsRefreshTimer = setTimeout(async () => {
      wsRefreshTimer = null
      try { consider(await fetchLatest()) } catch { /* ignore */ }
    }, 1000)
  })
})
onUnmounted(() => {
  clearInterval(refreshTimer)
  if (wsRefreshTimer) { clearTimeout(wsRefreshTimer); wsRefreshTimer = null }
  if (wsUnsub) wsUnsub()
})
</script>

<style scoped>
.sensor-calibrate { max-width: 600px; margin: 0 auto; padding: 1rem; }
.header-row { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1.5rem; }
.header-row h2 { color: var(--text-strong); flex: 1; }
.section { margin-bottom: 2rem; }
.cal-text { color: var(--text-sub); font-size: 0.9rem; line-height: 1.5; margin-bottom: 1rem; }
.cal-status { border-radius: 8px; padding: 0.7rem 0.9rem; font-size: 0.85rem; margin-bottom: 1rem; background: var(--bg); color: var(--text-muted); border-left: 3px solid var(--text-faint); }
.cal-status.ok { color: var(--success); border-left-color: var(--success); }
.cal-status.error { color: var(--danger); border-left-color: #7a2626; background: #3a1518; font-weight: 500; }
.cal-actions { display: flex; gap: 0.6rem; }
.btn { padding: 0.45rem 0.9rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.small { font-size: 0.75rem; padding: 0.3rem 0.6rem; }
.btn.secondary { background: var(--bg-accent); }
.btn:disabled { opacity: 0.5; cursor: default; }
</style>
