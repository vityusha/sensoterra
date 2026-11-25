<template>
  <div class="sensor-settings save-page">
    <div class="header-row">
      <button @click="$router.back()" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
      <h2>{{ headerTitle }}</h2>
      <div class="header-controls">
        <span v-if="device?.deviceType === 'BLOCK'" class="status-tag" :class="isOnline ? 'online' : 'offline'">
          {{ isOnline ? 'онлайн' : 'офлайн' }}
        </span>
        <HeaderActions />
      </div>
    </div>
    <div class="device-id">ID: {{ displayId }}</div>

    <div v-if="loading" class="loading" role="status"><span class="loading-label">Загрузка…</span></div>
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
          <table class="settings-table">
            <tbody>
              <tr v-for="item in summary" :key="item.l">
                <th scope="row">{{ item.l }}</th>
                <td>{{ item.v }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>



      <section class="section">
        <h3>Изменение</h3>
        <form ref="settingsForm" class="settings-form" @submit.prevent="save">
          <DeviceNameField
            v-if="isBlock"
            class="field settings-name"
            :class="{ disabled: !settable }"
            :name="form.name"
            :disabled="!settable"
            @save="form.name = $event"
          />

          <label class="field-check">
            <input type="checkbox" v-model="form.measureSoilMoisture" :disabled="!settable" @change="onMeasureSoilMoistureChange" />
            Измерять влажность почвы
          </label>

          <div v-for="{ key, label } in intervalFields" :key="key" class="field" :class="{ disabled: isIntervalDisabled(key) }">
            <span class="field-label">{{ label }} (чч:мм:сс)</span>
            <div class="duration-picker" role="group" :aria-label="label">
              <NumberPicker v-model="form[key].hours" :options="hourOptions" label="Часы" padded :disabled="isIntervalDisabled(key)" @change="onIntervalHoursChange(form[key])" />
              <span aria-hidden="true">:</span>
              <NumberPicker v-model="form[key].minutes" :options="minuteOptions" label="Минуты" padded :disabled="isIntervalDisabled(key) || form[key].hours === 24" />
              <span aria-hidden="true">:</span>
              <NumberPicker v-model="form[key].seconds" :options="secondOptions" label="Секунды" padded :disabled="isIntervalDisabled(key) || form[key].hours === 24" />
            </div>
            <template v-if="key === 'sleep'">
              <span class="field-hint">от 30 сек до 24 часов, шаг 30 сек</span>
              <span v-if="!sleepValid" class="field-error">Выберите интервал от 30 сек до 24 ч и секунды 00 или 30</span>
            </template>
            <template v-else>
              <span class="field-hint">от {{ minSendText }} до 24 часов<span v-if="form.measureSoilMoisture">, не меньше интервала измерения</span></span>
              <span v-if="!sendValid" class="field-error">Выберите интервал от {{ minSendText }} до 24 ч и секунды 00 или 30</span>
            </template>
          </div>

          <div v-if="!oldStandalone" class="field" :class="{ disabled: !settable }">
            <span class="field-label">Предел отличия от соседнего измерения</span>
            <NumberPicker v-model="form.percentN" :options="percentOptions" label="Предел отличия от соседнего измерения" suffix=" %" :disabled="!settable" />
          </div>

          <div v-if="!oldStandalone" class="field" :class="{ disabled: !settable }">
            <span class="field-label">Предел отличия от последнего переданного измерения</span>
            <NumberPicker v-model="form.percentS" :options="percentOptions" label="Предел отличия от последнего переданного измерения" suffix=" %" :disabled="!settable" />
          </div>

          <div class="field" :class="{ disabled: !settable }">
            <span class="field-label">Тип батарейки</span>
            <select v-model="form.batteryType" :disabled="!settable" @change="onBatteryTypeChange">
              <option v-for="type in BATTERY_TYPES" :key="type.value" :value="type.value">{{ type.label }}</option>
            </select>
          </div>

          <div v-if="!oldStandalone" class="field" :class="{ disabled: !settable }">
            <span class="field-label">Минимальное напряжение (1,8–2,9 В)</span>
            <span class="field-row">
              <input type="number" min="1.8" max="2.9" step="0.1" v-model.number="form.minVoltageV" :disabled="!settable" />
              <span class="field-unit">В</span>
            </span>
          </div>

          <label class="field" :class="{ disabled: !settable }">
            <span class="field-label">Порог уведомления</span>
            <span class="field-row">
              <input type="number" :min="minWarningVoltageMv / 1000" step="0.1" v-model.number="form.warningVoltageV" :disabled="!settable" />
              <span class="field-unit">В</span>
            </span>
            <span class="field-hint">Минимум на 0,1 В выше минимального напряжения</span>
            <span v-if="!warningVoltageValid" class="field-error">Укажите порог не ниже {{ minWarningVoltageMv / 1000 }} В</span>
          </label>

          <!-- Настройки автономного датчика уходят на брокер retained: спящий датчик иначе
               их пропустит. Если датчик уже применил настройки, сообщение снимается само,
               но после правки «руками» старые значения могут вернуться — тогда снять
               нужно вручную. -->
          <div v-if="!isBlock" class="form-actions">
            <button type="button" class="btn secondary" :disabled="clearing" @click="dropRetained">
              {{ clearing ? 'Снятие...' : 'Снять сохранённые настройки' }}
            </button>
          </div>
        </form>
      </section>
    </template>
    <footer v-if="!loading && sensor" class="save-bar">
      <button type="button" class="btn save" :disabled="!settable || !formValid || sending" @click="save">
        {{ sending ? 'Отправка…' : 'Сохранить' }}
      </button>
    </footer>
  </div>
</template>

<script setup>
import HeaderActions from '../components/HeaderActions.vue'
import NumberPicker from '../components/NumberPicker.vue'
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DeviceNameField from '../components/DeviceNameField.vue'
import { getDevice, getDeviceSensors, getDeviceStatuses, sendSensorParams, clearRetainedSettings } from '../api/auth'
import { getIndividualName } from '../store/settings'
import { BATTERY_TYPES, batteryLabel, batteryMinVoltageV, batteryWarningV } from '../utils/battery'
import { fmtEventTimeWithZone, fmtMeasurementInterval } from '../utils/datetime'
import { pushNotification } from '../store/notifications'
import { canControlDevice, isAdmin, rememberDeviceOwner } from '../store/auth'

const route = useRoute()
const router = useRouter()

const chipId = Number(route.params.chipId)
const slot = Number(route.params.slot)
const isBlock = route.params.slot !== undefined

const loading = ref(true)
const settingsForm = ref(null)
const sending = ref(false)
const device = ref(null)
const sensor = ref(null)
const statusMap = ref({})

const percentOptions = Array.from({ length: 16 }, (_, index) => 5 + index)
const hourOptions = Array.from({ length: 25 }, (_, index) => index)
const minuteOptions = Array.from({ length: 60 }, (_, index) => index)
const secondOptions = [0, 30]
const intervalFields = [
  { key: 'sleep', label: 'Интервал измерения' },
  { key: 'send', label: 'Интервал отправки' },
]

const form = reactive({
  name: '',
  measureSoilMoisture: true,
  sleep: splitInterval(300),
  send: splitInterval(3600),
  percentN: 10,
  percentS: 15,
  minVoltageV: 2.5,
  warningVoltageV: batteryWarningV('ALKALINE'),
  batteryType: 'ALKALINE',
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
  const sleep = s?.sleep ?? 300
  const send = s?.send ?? 3600
  form.measureSoilMoisture = sleep !== send
  form.sleep = splitInterval(sleep)
  form.send = splitInterval(send)
  form.percentN = s?.percentN ?? 10
  form.percentS = s?.percentS ?? 15
  form.batteryType = s?.batteryType || 'ALKALINE'
  form.minVoltageV = s?.minVoltage != null ? Math.round(s.minVoltage) / 1000 : batteryMinVoltageV(form.batteryType)
  form.warningVoltageV = s?.warningVoltage != null ? s.warningVoltage / 1000 : batteryWarningV(form.batteryType)
  applying = false
  dirty.value = false
}

function onBatteryTypeChange() {
  form.warningVoltageV = batteryWarningV(form.batteryType)
  if (!oldStandalone.value) {
    form.minVoltageV = batteryMinVoltageV(form.batteryType)
  }
}

function splitInterval(totalSeconds) {
  return {
    hours: Math.floor(totalSeconds / 3600),
    minutes: Math.floor(totalSeconds / 60) % 60,
    seconds: totalSeconds % 60,
  }
}

const displayName = computed(() =>
  getIndividualName(chipId) || device.value?.name || sensor.value?.name || 'Датчик')
const sensorTitle = computed(() => {
  if (!isBlock) return `Датчик ${chipId}`
  const s = sensor.value
  const base = s?.name || `S${slot}`
  return s?.sensorChipId ? `${base} (${s.sensorChipId})` : base
})
const headerTitle = computed(() => {
  if (!isBlock) return displayName.value
  return sensor.value?.name || `S${slot}`
})
const displayId = computed(() => isBlock ? (sensor.value?.sensorChipId ?? chipId) : chipId)
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
const lastMsgText = computed(() => {
  if (!lastMsgMs.value) return null
  const t = fmtEventTimeWithZone(sensor.value?.lastTime || sensor.value?.updatedAt, device.value?.tzOffsetMinutes)
  const n = sensor.value?.numMessage
  return n != null ? `${t} [${n}]` : t
})
const lastMsgVerdict = computed(() => {
  if (!lastMsgMs.value) return ''
  const ageSec = Math.round((nowTick.value - lastMsgMs.value) / 1000)
  const rel = ageSec >= 86400 ? `${Math.round(ageSec / 86400 * 10) / 10} дн назад`
    : ageSec >= 3600 ? `${Math.round(ageSec / 3600)} ч назад`
    : `${Math.max(1, Math.round(ageSec / 60))} мин назад`
  return lastMsgState.value === 'alive' ? `живой · ${rel}` : `молчит · ${rel}`
})

function intervalSeconds({ hours, minutes, seconds }) {
  return hours * 3600 + minutes * 60 + seconds
}

const sleepSeconds = computed(() => intervalSeconds(form.sleep))
const sendSeconds = computed(() => intervalSeconds(form.send))

function onMeasureSoilMoistureChange() {
  if (!form.measureSoilMoisture || sleepSeconds.value < sendSeconds.value) return
  // A 30-second send interval leaves no room for a shorter measurement interval.
  if (sendSeconds.value <= 30) form.send = splitInterval(60)
  const suggested = Math.max(30, Math.floor((sendSeconds.value - 60) / 30) * 30)
  form.sleep = splitInterval(suggested)
}

function isIntervalDisabled(key) {
  return !settable.value || (key === 'sleep' && !form.measureSoilMoisture)
}

function onIntervalHoursChange(interval) {
  if (interval.hours !== 24) return
  interval.minutes = 0
  interval.seconds = 0
}

function validInterval(seconds, min, savedSeconds) {
  return Number.isInteger(seconds) && seconds >= min && seconds <= 86400 &&
    (seconds % 30 === 0 || seconds === savedSeconds)
}

const sleepValid = computed(() =>
  !form.measureSoilMoisture || validInterval(sleepSeconds.value, 30, sensor.value?.sleep)
)
const minSendSeconds = computed(() => form.measureSoilMoisture ? Math.max(30, sleepSeconds.value) : 30)
const sendValid = computed(() =>
  validInterval(sendSeconds.value, minSendSeconds.value, sensor.value?.send)
)
const minSendText = computed(() => fmtMeasurementInterval(minSendSeconds.value))
const minWarningVoltageMv = computed(() => Math.round(
  (oldStandalone.value ? batteryMinVoltageV(form.batteryType) : form.minVoltageV) * 1000
) + 100)
const warningVoltageMv = computed(() => Math.round(form.warningVoltageV * 1000))
const warningVoltageValid = computed(() =>
  Number.isFinite(form.warningVoltageV) &&
  warningVoltageMv.value >= minWarningVoltageMv.value
)
watch(() => form.minVoltageV, value => {
  if (applying || !Number.isFinite(value) || value < 1.8 || value > 2.9) return
  if (!warningVoltageValid.value) form.warningVoltageV = minWarningVoltageMv.value / 1000
}, { flush: 'sync' })
const formValid = computed(() => {
  if (!sleepValid.value) return false
  if (!sendValid.value) return false
  if (!BATTERY_TYPES.some(t => t.value === form.batteryType)) return false
  if (!warningVoltageValid.value) return false
  if (oldStandalone.value) return true
  if (!percentOptions.includes(form.percentN) || !percentOptions.includes(form.percentS)) return false
  if (!(form.minVoltageV >= 1.8 && form.minVoltageV <= 2.9)) return false
  return true
})

const summary = computed(() => {
  const s = sensor.value || {}
  const sleepS = s.sleep
  const sendS = s.send
  const rows = [
    ...(isBlock ? [{ l: 'Название', v: s.name || '—' }] : []),
    { l: 'Интервал измерения', v: sleepS != null ? fmtMeasurementInterval(sleepS) : '—' },
    { l: 'Интервал отправки', v: sendS != null ? fmtMeasurementInterval(sendS) : '—' },
  ]
  if (!oldStandalone.value) {
    rows.push({ l: 'Предел отличия от соседнего измерения', v: s.percentN != null ? `${s.percentN} %` : '—' })
    rows.push({ l: 'Предел отличия от последнего переданного измерения', v: s.percentS != null ? `${s.percentS} %` : '—' })
  }
  rows.push({ l: 'Тип батарейки', v: batteryLabel(s.batteryType) })
  if (!oldStandalone.value) {
    rows.push({ l: 'Мин. напряжение', v: s.minVoltage != null ? `${Math.round(s.minVoltage) / 1000} В` : '—' })
  }
  rows.push({ l: 'Порог уведомления', v: `${s.warningVoltage != null ? Math.round(s.warningVoltage) / 1000 : batteryWarningV(s.batteryType)} В` })
  return rows
})

function buildRequest() {
  const req = {
    sleep: form.measureSoilMoisture ? sleepSeconds.value : sendSeconds.value,
    send: sendSeconds.value,
    batteryType: form.batteryType,
    warningVoltage: warningVoltageMv.value,
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
  if (form.measureSoilMoisture && sleepSeconds.value === sendSeconds.value &&
      !window.confirm('При равных интервалах измерения и отправки влажность почвы не будет измеряться. Вы хотите продолжить?')) {
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
  const submittedFrom = router.currentRoute.value
  sending.value = true
  try {
    await sendSensorParams(sensor.value.id, buildRequest())
    pushNotification(`Настройки датчика ${isBlock ? `S${slot}` : chipId} отправлены`, 'success')
    if (router.currentRoute.value === submittedFrom) router.back()
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

function isPickerOpen() {
  return Boolean(settingsForm.value?.querySelector('.number-picker [aria-expanded="true"]'))
}

async function load(silent = false) {
  if (silent && isPickerOpen()) return
  try {
    const [dev, sensors, statuses] = await Promise.all([
      getDevice(chipId),
      getDeviceSensors(chipId),
      getDeviceStatuses(),
    ])
    if (silent && isPickerOpen()) return
    device.value = dev
    rememberDeviceOwner(dev)
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
  tickTimer = setInterval(() => {
    if (!isPickerOpen()) nowTick.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  if (tickTimer) clearInterval(tickTimer)
})
</script>

<style scoped>
.sensor-settings { max-width: 600px; margin: 0 auto; padding: 1rem; }
.header-row { display: flex; align-items: flex-start; gap: 0.5rem; position: sticky; top: 0; z-index: 50; margin: -1rem -1rem 1.5rem; padding: 1rem; background: var(--bg); }
h2 { color: var(--text-strong); flex: 1; min-width: 0; overflow-wrap: anywhere; }
.header-controls { display: flex; align-items: center; gap: 0.5rem; flex: none; }
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
.settings-table { width: 100%; border-collapse: collapse; font-size: 0.85rem; }
.settings-table th, .settings-table td { padding: 0.35rem 0; vertical-align: top; }
.settings-table th { color: var(--text-dim); font-weight: normal; text-align: left; padding-right: 1rem; }
.settings-table td { color: var(--text); text-align: right; white-space: nowrap; }
.settings-form { background: var(--bg-card); border-radius: 12px; padding: 1rem; }
.field { display: flex; flex-direction: column; gap: 0.4rem; margin-bottom: 1rem; }
.field.disabled { opacity: 0.5; }
.field-label { color: var(--text-muted); font-size: 0.8rem; }
.field-hint { color: var(--text-faint); font-size: 0.72rem; }
.field input[type="number"], .field select { background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.95rem; padding: 0.45rem 0.6rem; }
.field input[type="number"] { width: 5rem; flex-shrink: 0; }
.settings-name { width: max-content; max-width: 100%; }
.field input.invalid { border-color: var(--danger); }
.field-error { color: var(--danger); font-size: 0.72rem; }
.field select { cursor: pointer; align-self: flex-start; max-width: 100%; }
.duration-picker { display: inline-flex; align-items: center; align-self: flex-start; gap: 0.2rem; }
.field-check { display: flex; align-items: center; gap: 0.3rem; color: var(--text-muted); font-size: 0.8rem; cursor: pointer; }
.field-check input { cursor: pointer; }
.field-check { margin-bottom: 0.5rem; }
.field-row { display: flex; align-items: center; gap: 0.4rem; }
.field-unit { color: var(--text-faint); font-size: 0.8rem; }
.form-actions { display: flex; justify-content: center; gap: 0.6rem; margin-top: 0.5rem; }

/* Телефон: поля в ряд не влезают — переносим. */
@media (hover: none) {
  .field-row { flex-wrap: wrap; row-gap: 0.4rem; }
}
</style>
