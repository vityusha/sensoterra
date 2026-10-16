<template>
  <div class="sensor-settings">
    <div class="header-row">
      <button @click="$router.back()" class="btn small secondary">&larr;</button>
      <h2>{{ displayName }} · {{ sensorTitle }}</h2>
      <span v-if="device?.deviceType === 'BLOCK'" class="status-tag" :class="isOnline ? 'online' : 'offline'">
        {{ isOnline ? 'online' : 'offline' }}
      </span>
    </div>

    <div v-if="loading" class="loading">Загрузка...</div>
    <div v-else-if="!sensor" class="empty small">Датчик не найден</div>
    <template v-else>
      <div v-if="isBlock && !isOnline" class="offline-banner">
        Блок оффлайн — настройка датчика невозможна
      </div>
      <div v-else-if="!isBlock" class="info-banner">
        Автономный датчик: настройки передаются по MQTT (Sleep_Send) и применятся при следующем включении датчика.
      </div>

      <div class="last-msg" :class="lastMsgState" v-if="lastMsgText">
        <span class="lm-label">Последнее сообщение:</span>
        <span class="lm-time">{{ lastMsgText }}</span>
        <span class="lm-verdict">{{ lastMsgVerdict }}</span>
      </div>
      <div class="last-msg stale" v-else-if="sensor">
        <span class="lm-label">Последнее сообщение:</span>
        <span class="lm-verdict">нет данных</span>
      </div>

      <section class="section">
        <h3>Текущие настройки</h3>
        <div class="settings-list">
          <div class="setting-row" v-for="item in summary" :key="item.l">
            <span class="setting-label">{{ item.l }}</span>
            <span class="setting-value">{{ item.v }}</span>
          </div>
        </div>
      </section>



      <section class="section">
        <h3>Изменение</h3>
        <form class="settings-form" @submit.prevent="save">
          <div v-if="isBlock" class="field" :class="{ disabled: !settable }">
            <span class="field-label">Название</span>
            <input type="text" v-model="form.name" maxlength="32" :disabled="!settable" />
            <span class="field-hint">до 32 символов</span>
          </div>

          <div class="field" :class="{ disabled: !settable }">
            <span class="field-label">Интервал сна (мин)</span>
            <span class="field-row">
              <input class="half" type="number" min="0.5" step="0.5" v-model.number="form.sleepMin" :disabled="!settable" />
              <span class="field-unit">мин</span>
            </span>
            <span class="field-hint">от 0,5 мин (30 с), кратно 0,5</span>
            <span v-if="!sleepValid" class="field-error">Минимум 30 секунд, шаг 0,5 мин</span>
          </div>

          <div class="field" :class="{ disabled: !settable }">
            <span class="field-label">Интервал отправки</span>
            <span class="field-row">
              <input class="half" type="number" min="0" max="24" step="1" v-model.number="form.sendHours" :disabled="!settable" />
              <span class="field-unit">ч</span>
              <input class="half" type="number" min="0" max="59" step="1" v-model.number="form.sendMinutes" :disabled="!settable" />
              <span class="field-unit">мин</span>
            </span>
            <span class="field-hint">от {{ minSendText }} до 24 часов, не меньше интервала сна</span>
            <span v-if="!sendValid" class="field-error">Интервал отправки должен быть от {{ minSendText }} до 24 ч и не меньше сна</span>
          </div>

          <div v-if="!oldStandalone" class="field" :class="{ disabled: !settable }">
            <span class="field-label">Предел отличия от соседнего измерения (%)</span>
            <input type="number" min="5" max="20" step="1" v-model.number="form.percentN" :disabled="!settable" />
            <span class="field-hint">от 5 до 20 %</span>
          </div>

          <div v-if="!oldStandalone" class="field" :class="{ disabled: !settable }">
            <span class="field-label">Предел отличия от последнего переданного измерения (%)</span>
            <input type="number" min="5" max="20" step="1" v-model.number="form.percentS" :disabled="!settable" />
            <span class="field-hint">от 5 до 20 %</span>
          </div>

          <div v-if="!oldStandalone" class="field" :class="{ disabled: !settable }">
            <span class="field-label">Минимальное напряжение (2,0–2,9 В)</span>
            <span class="field-row">
              <input class="half" type="number" min="2.0" max="2.9" step="0.1" v-model.number="form.minVoltageV" :disabled="!settable" />
              <span class="field-unit">В</span>
            </span>
          </div>

          <div class="form-actions">
            <button type="submit" class="btn" :disabled="!settable || !formValid || sending">
              {{ sending ? 'Отправка...' : 'Сохранить' }}
            </button>
            <!-- Настройки автономного датчика уходят на брокер retained: спящий датчик иначе
                 их пропустит. Если датчик уже применил настройки, сообщение снимается само,
                 но после правки «руками» старые значения могут вернуться — тогда снять
                 нужно вручную. -->
            <button v-if="!isBlock" type="button" class="btn secondary" :disabled="clearing" @click="dropRetained">
              {{ clearing ? 'Снятие...' : 'Снять сохранённые настройки' }}
            </button>
          </div>
        </form>
      </section>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getDevice, getDeviceSensors, getDeviceStatuses, sendSensorParams, clearRetainedSettings } from '../api/auth'
import { getBlockName, getIndividualName } from '../store/settings'
import { fmtEventTimeWithZone } from '../utils/datetime'
import { pushNotification } from '../store/notifications'
import { canControlDevice, isAdmin } from '../store/auth'

const route = useRoute()

const chipId = Number(route.params.chipId)
const slot = Number(route.params.slot)
const isBlock = route.params.slot !== undefined

const loading = ref(true)
const sending = ref(false)
const device = ref(null)
const sensor = ref(null)
const statusMap = ref({})

const form = reactive({
  name: '',
  sleepMin: 5,
  sendHours: 1,
  sendMinutes: 0,
  percentN: 10,
  percentS: 15,
  minVoltageV: 2.5,
})

// Правки пользователя не должны затираться фоновым обновлением с сервера.
// applying=true на время программного заполнения, иначе watcher ловит и его правки.
const dirty = ref(false)
let applying = false
watch(form, () => { if (!applying) dirty.value = true }, { deep: true, flush: 'sync' })

// Тикер для «X мин назад», чтобы относительное время шло live, а не застывало.
const nowTick = ref(Date.now())

function applyFromSensor(s) {
  applying = true
  form.name = isBlock ? (s?.name || '') : ''
  form.sleepMin = (s?.sleep != null) ? roundHalf(s.sleep / 60) : 5
  const sendS = s?.send != null ? Number(s.send) : 3600
  form.sendHours = Math.floor(sendS / 3600)
  form.sendMinutes = Math.round((sendS % 3600) / 60)
  form.percentN = s?.percentN ?? 10
  form.percentS = s?.percentS ?? 15
  form.minVoltageV = s?.minVoltage != null ? Math.round(s.minVoltage) / 1000 : 2.5
  applying = false
  dirty.value = false
}

function roundHalf(v) {
  return Math.round(v * 2) / 2
}

const displayName = computed(() => getBlockName(chipId) || getIndividualName(chipId) || String(chipId))
const sensorTitle = computed(() => isBlock ? `Датчик S${slot}` : `Датчик ${chipId}`)
const isOnline = computed(() => statusMap.value[chipId]?.status === 'online')
const settable = computed(() => !isBlock || isOnline.value)

// Старые автономные датчики не передают percent_n/percent_s/min_voltage — показываем и шлём только sleep/send.
const oldStandalone = computed(() => !isBlock
  && sensor.value?.percentN == null
  && sensor.value?.percentS == null
  && sensor.value?.minVoltage == null)

const lastMsgMs = computed(() => {
  const s = sensor.value
  const t = s?.lastTime || s?.updatedAt
  return t ? new Date(t).getTime() : null
})
const sendSec = computed(() => (sensor.value?.send && sensor.value.send > 0) ? sensor.value.send : null)
const lastMsgState = computed(() => {
  if (!lastMsgMs.value) return 'none'
  const ageSec = (nowTick.value - lastMsgMs.value) / 1000
  const limit = sendSec.value ? sendSec.value * 2 : 3600
  return ageSec <= limit + 60 ? 'alive' : 'stale'
})
const lastMsgText = computed(() => lastMsgMs.value
  ? fmtEventTimeWithZone(sensor.value?.lastTime || sensor.value?.updatedAt, device.value?.tzOffsetMinutes)
  : null)
const lastMsgVerdict = computed(() => {
  if (!lastMsgMs.value) return ''
  const ageSec = Math.round((nowTick.value - lastMsgMs.value) / 1000)
  const rel = ageSec >= 86400 ? `${Math.round(ageSec / 86400 * 10) / 10} дн назад`
    : ageSec >= 3600 ? `${Math.round(ageSec / 3600)} ч назад`
    : `${Math.max(1, Math.round(ageSec / 60))} мин назад`
  return lastMsgState.value === 'alive' ? `живой · ${rel}` : `молчит · ${rel}`
})

const sleepSeconds = computed(() => Math.round((form.sleepMin || 0) * 60))
const sendSeconds = computed(() => ((form.sendHours || 0) * 3600) + ((form.sendMinutes || 0) * 60))
const sleepValid = computed(() =>
  form.sleepMin != null && form.sleepMin >= 0.5 && (form.sleepMin * 2) % 1 === 0
)
const sendValid = computed(() =>
  sendSeconds.value > 0 && sendSeconds.value <= 86400 && sendSeconds.value >= sleepSeconds.value
)
const minSendText = computed(() => {
  const s = sleepSeconds.value
  if (s % 3600 === 0) return `${s / 3600} ч`
  if (s % 60 === 0 && s >= 60) return `${s / 60} мин`
  return `${s} с`
})
const formValid = computed(() => {
  if (!sleepValid.value) return false
  if (!sendValid.value) return false
  if (oldStandalone.value) return true
  if (!(form.percentN >= 5 && form.percentN <= 20)) return false
  if (!(form.percentS >= 5 && form.percentS <= 20)) return false
  if (!(form.minVoltageV >= 2.0 && form.minVoltageV <= 2.9)) return false
  return true
})

const summary = computed(() => {
  const s = sensor.value || {}
  const sleepS = s.sleep
  const sendS = s.send
  const rows = [
    { l: 'Название', v: s.name || '—' },
    { l: 'Интервал сна', v: sleepS != null ? `${sleepS} с (${roundHalf(sleepS / 60)} мин)` : '—' },
    { l: 'Интервал отправки', v: sendS != null ? fmtSend(sendS) : '—' },
  ]
  if (!oldStandalone.value) {
    rows.push({ l: 'Предел отличия от соседнего измерения', v: s.percentN != null ? `${s.percentN} %` : '—' })
    rows.push({ l: 'Предел отличия от последнего переданного измерения', v: s.percentS != null ? `${s.percentS} %` : '—' })
    rows.push({ l: 'Мин. напряжение', v: s.minVoltage != null ? `${Math.round(s.minVoltage) / 1000} В` : '—' })
  }
  return rows
})

function fmtSend(seconds) {
  const h = Math.floor(seconds / 3600)
  const m = Math.round((seconds % 3600) / 60)
  return `${h} ч ${m} мин`
}

function buildRequest() {
  const req = {
    sleep: sleepSeconds.value,
    send: sendSeconds.value,
  }
  if (isBlock) req.name = form.name
  if (!oldStandalone.value) {
    req.percentN = form.percentN
    req.percentS = form.percentS
    req.minVoltage = Math.round(form.minVoltageV * 1000)
  }
  return req
}

function errorMessage(e, fallback) {
  const status = e?.response?.status
  if (status === 401) return 'Сессия истекла — войдите заново'
  if (status === 403) return 'Недостаточно прав'
  if (status === 503) return 'MQTT-брокер недоступен — настройки не отправлены'
  return e?.response?.data?.message || fallback
}

async function save() {
  // Роль «только чтение» на этом блоке; страница уже недоступна через роутер,
  // проверка здесь — страховка от прямого вызова.
  if (!canControlDevice(chipId)) return
  if (!formValid.value) {
    pushNotification('Проверьте введённые значения: часть полей вне допустимых пределов', 'error')
    return
  }
  const name = isBlock ? (form.name || sensorTitle.value) : sensorTitle.value
  if (!window.confirm(`Отправить настройки датчика ${name}${isBlock ? ` (S${slot})` : ''}?\n\nНастройки будут переданы по MQTT и применятся к датчику.`)) {
    return
  }
  if (sensor.value?.id == null) {
    pushNotification('Датчик не найден — нельзя отправить настройки', 'error')
    return
  }
  sending.value = true
  try {
    await sendSensorParams(sensor.value.id, buildRequest())
    pushNotification(`Настройки датчика ${isBlock ? `S${slot}` : chipId} отправлены`, 'success')
    const sensors = await getDeviceSensors(chipId)
    const next = sensors.find(x => isBlock ? Number(x.sensorNumber) === slot : x.sensorNumber == null)
    if (next) {
      sensor.value = next
      applyFromSensor(next)
    }
    dirty.value = false
  } catch (e) {
    pushNotification(errorMessage(e, 'Не удалось отправить настройки'), 'error')
  } finally {
    sending.value = false
  }
}

const clearing = ref(false)

async function dropRetained() {
  if (!canControlDevice(chipId)) return
  if (!window.confirm('Снять с брокера сохранённые настройки датчика?\n\n'
    + 'Нужно, если настройки заданы вручную, а датчик возвращает прежние: retained-сообщение '
    + 'пережило применение. Новые настройки с этого экрана надо отправить заново.')) {
    return
  }
  clearing.value = true
  try {
    const res = await clearRetainedSettings(chipId)
    pushNotification(res.cleared > 0
      ? 'Сохранённые настройки сняты с брокера'
      : 'На брокере не было сохранённых настроек', 'success')
  } catch (e) {
    pushNotification(errorMessage(e, 'Не удалось снять настройки с брокера'), 'error')
  } finally {
    clearing.value = false
  }
}

let refreshTimer = null
let tickTimer = null

async function load(silent = false) {
  try {
    const [dev, sensors, statuses] = await Promise.all([
      getDevice(chipId),
      getDeviceSensors(chipId),
      getDeviceStatuses(),
    ])
    device.value = dev
    sensor.value = sensors.find(x => isBlock ? Number(x.sensorNumber) === slot : x.sensorNumber == null) || sensors[0] || null


    const map = {}
    for (const s of statuses) map[s.chipId] = s
    statusMap.value = map
    // Фоновое обновление подтягивает «последнее сообщение» и статус, но не затирает правки формы.
    if (sensor.value && !dirty.value && !sending.value) {
      applyFromSensor(sensor.value)
    }
  } catch {
    if (!silent) pushNotification('Не удалось загрузить настройки датчика', 'error')
  } finally {
    if (!silent) loading.value = false
  }
}

onMounted(async () => {
  await load()
  // Спящие автономные датчики просыпаются редко, поэтому обновляем окно сами.
  refreshTimer = setInterval(() => load(true), 10000)
  tickTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  if (tickTimer) clearInterval(tickTimer)
})
</script>

<style scoped>
.sensor-settings { max-width: 600px; margin: 0 auto; padding: 1rem; }
.header-row { display: flex; justify-content: space-between; align-items: center; gap: 0.5rem; margin-bottom: 1.5rem; flex-wrap: wrap; }
h2 { color: var(--text-strong); flex: 1; min-width: 0; }
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.small { padding: 0.25rem 0.5rem; font-size: 0.7rem; }
.btn.secondary { background: var(--bg-accent); }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.status-tag { font-size: 0.72rem; padding: 0.15rem 0.6rem; border-radius: 999px; white-space: nowrap; }
.status-tag.online { background: var(--tag-bg-ok); color: var(--success); }
.status-tag.offline { background: var(--bg-accent); color: var(--danger); }
.loading, .empty { color: var(--text-dim); text-align: center; padding: 2rem; }
.empty.small { padding: 1rem; font-size: 0.85rem; }
.offline-banner { background: var(--bg-accent); color: var(--on-accent); border: 1px solid #8a2a2a; border-radius: 10px; padding: 0.7rem 1rem; font-size: 0.85rem; margin-bottom: 1rem; }
.info-banner { background: var(--banner-bg); color: var(--banner-text); border: 1px solid var(--banner-border); border-radius: 10px; padding: 0.7rem 1rem; font-size: 0.85rem; margin-bottom: 1rem; }
.last-msg { display: flex; align-items: baseline; flex-wrap: wrap; gap: 0.5rem; background: var(--bg); border-radius: 10px; border-left: 3px solid var(--text-faint); padding: 0.7rem 1rem; font-size: 0.85rem; margin-bottom: 1.5rem; }
.last-msg.alive { border-left-color: var(--success); }
.last-msg.stale { border-left-color: var(--danger); }
.lm-label { color: var(--text-dim); }
.lm-time { color: var(--text); font-family: monospace; }
.lm-verdict { margin-left: auto; color: var(--text-muted); }
.last-msg.alive .lm-verdict { color: var(--success); }
.last-msg.stale .lm-verdict { color: var(--danger); }
.section { margin-bottom: 2rem; }
.section h3 { color: var(--text-muted); font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 0.75rem; }
.settings-list { background: var(--bg-card); border-radius: 12px; padding: 0.9rem 1rem; }
.setting-row { display: flex; justify-content: space-between; gap: 1rem; padding: 0.35rem 0; font-size: 0.85rem; }
.setting-label { color: var(--text-dim); }
.setting-value { color: var(--text); text-align: right; max-width: 60%; }
.settings-form { background: var(--bg-card); border-radius: 12px; padding: 1rem; }
.field { display: flex; flex-direction: column; gap: 0.4rem; margin-bottom: 1rem; }
.field.disabled { opacity: 0.5; }
.field-label { color: var(--text-muted); font-size: 0.8rem; }
.field-hint { color: var(--text-faint); font-size: 0.72rem; }
.field input[type="text"], .field input[type="number"], .field select { background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.95rem; padding: 0.45rem 0.6rem; }
.field input.invalid { border-color: var(--danger); }
.field-error { color: var(--danger); font-size: 0.72rem; }
.field select { cursor: pointer; }
.field-row { display: flex; align-items: center; gap: 0.4rem; }
.field-unit { color: var(--text-faint); font-size: 0.8rem; }
.field-row input.half { width: 5rem; }
.form-actions { display: flex; justify-content: flex-end; gap: 0.6rem; margin-top: 0.5rem; }

/* Телефон: поля в ряд не влезают — переносим. */
@media (hover: none) {
  .field-row { flex-wrap: wrap; row-gap: 0.4rem; }
  .setting-row { flex-wrap: wrap; row-gap: 0.2rem; }
}
</style>