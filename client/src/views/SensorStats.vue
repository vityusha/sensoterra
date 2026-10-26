<template>
  <div class="sensor">
    <div class="header-row">
      <button @click="$router.back()" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
      <h2>{{ displayName }} ({{ chipId }})</h2>
      <span class="type-tag">{{ isIndividual ? 'автономный' : `${blockLabel} &nbsp;S${blockRole?.slot ?? ''}` }}</span>
      <div v-if="catalogEntry && canEditPrefs" class="menu-wrap" ref="menuRef">
        <button
          class="menu-toggle"
          :aria-expanded="menuOpen"
          aria-haspopup="menu"
          aria-label="Меню"
          @click="menuOpen = !menuOpen"
        >
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
            <circle cx="12" cy="5" r="2" />
            <circle cx="12" cy="12" r="2" />
            <circle cx="12" cy="19" r="2" />
          </svg>
        </button>
        <div v-if="menuOpen" class="menu-dropdown" role="menu">
          <button v-if="isIndividual" role="menuitem" @click="openSettingsFromMenu">
            <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z"/>
            </svg>
            Настройка
          </button>
          <button role="menuitem" @click="openCalibrateFromMenu">
            <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M12 2C6.49 2 2 6.49 2 12s4.49 10 10 10 10-4.49 10-10S17.51 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm0-13c-2.76 0-5 2.24-5 5s2.24 5 5 5 5-2.24 5-5-2.24-5-5-5zm0 8c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3z"/>
            </svg>
            Калибровка
          </button>
          <button v-if="calibSectionVisible" role="menuitem" @click="openCalibSettings">
            <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M12 2c-5.33 5.33-8 8.67-8 12 0 4.42 3.58 8 8 8s8-3.58 8-8c0-3.33-2.67-6.67-8-12zm0 18c-3.31 0-6-2.69-6-6 0-2.22 1.86-5.03 6-9.36 4.14 4.33 6 7.14 6 9.36 0 3.31-2.69 6-6 6z"/>
            </svg>
            Калибровка влажности
          </button>
          <button role="menuitem" @click="openDisplaySettings">
            <svg class="menu-icon" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
              <path d="M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zm0 12.5c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z"/>
            </svg>
            Показ измерений
          </button>
        </div>
      </div>
    </div>

    <section class="section" v-if="latest">
      <h3>Последние данные</h3>
      <div class="last-received" v-if="latest.time">
        Получено: <b>{{ fmtEventTimeWithZone(latest.time, tzOffset) }}</b>
        <span v-if="latest.numMessage != null" class="msg-num">[{{ latest.numMessage }}]</span>
        <span class="ago">({{ agoText }})</span>
      </div>
      <div class="immersion-warning" v-if="immersionWarningShown">
        Проверьте уровень погружения датчика в почву
      </div>
      <div class="latest-grid">
        <div class="latest-cell" v-for="che in cellCharts" :key="'l' + che.key">
          <span class="l-label">{{ label(che.key) }}<button v-if="isGradientField(che.key)" class="gradient-btn" @click.stop="openGradient(che.key)" :title="gradientTitle(che.key)"></button></span>
          <span class="l-value">{{ cellValue(che, latest) }}<span class="l-unit" v-if="cellUnit(che)">&nbsp;{{ cellUnit(che) }}</span><span v-if="che.key === 'v0' && pctColor" class="pct-color" :style="{ background: pctColor }"></span><span v-if="che.key !== 'v0' && fieldColor(che, latest)" class="pct-color" :style="{ background: fieldColor(che, latest) }"></span></span>
        </div>
      </div>
    </section>

    <div class="battery-runtime">
      <span class="battery-label">Время работы батареи:</span>
      <b v-if="batteryRuntimeText">{{ batteryRuntimeText }}</b>
      <span v-else-if="!batteryStartLoaded" class="muted">…</span>
      <span v-else class="muted">информация недоступна</span>
    </div>

    <!-- Ключ для комплектного листа: его копирует админ и отдаёт покупателю вместе с датчиком. -->
    <section v-if="isAdmin && isIndividual" class="section">
      <div class="section-head">
        <h3>Ключ для комплектного листа</h3>
        <button class="btn small" @click="toggleKey" :disabled="keyBusy">{{ keyBusy ? '...' : (deviceKey ? 'Скрыть' : 'Показать') }}</button>
      </div>
      <div v-if="deviceKey" class="invite-box">
        <div class="invite-link">ID {{ chipId }} · ключ {{ deviceKey }}</div>
        <div class="invite-note">Распечатайте на листе, который едет в комплекте с датчиком.
          Покупатель введёт эту пару при регистрации.</div>
        <div class="invite-actions">
          <button class="btn small" @click="copyKey">Скопировать</button>
          <button class="btn small danger" @click="regenKey">Перевыпустить</button>
        </div>
      </div>
    </section>

    <section class="section">
      <h3>Статистика измерений</h3>
      <div class="range-select">
        <button v-for="r in ranges" :key="r.label" class="btn small" :class="{ active: range === r.value }" @click="setRange(r.value)">{{ r.label }}</button>
        <button class="btn small" :class="{ active: range === 'custom' }" @click="showCustom = !showCustom">{{ showCustom ? 'Скрыть' : 'Свой период' }}</button>
      </div>
      <div v-if="showCustom" class="custom-range">
        <label>С <input v-model="customFrom" type="datetime-local" /></label>
        <label>По <input v-model="customTo" type="datetime-local" /></label>
        <button class="btn small" @click="applyCustom">Применить</button>
      </div>
      <div v-if="loading" class="empty small">Загрузка...</div>
      <template v-else-if="rows.length">
        <div class="chart-section" v-for="che in chartSections" :key="che.key">
          <div class="chart-head">
            <h4>{{ label(che.key) }} <span class="unit" v-if="chartUnit(che)">, {{ chartUnit(che) }}</span></h4>
            <div class="y-edit" v-if="isYEditable(che.key) && canEditPrefs">
              <label>min <input type="number" step="any" v-model="yEdit(che.key).min" @keyup.enter="applyY(che.key)" /></label>
              <label>max <input type="number" step="any" v-model="yEdit(che.key).max" @keyup.enter="applyY(che.key)" /></label>
              <button class="btn small" @click="applyY(che.key)">ОК</button>
              <button class="btn small ghost" @click="resetY(che.key)">Авто</button>
            </div>
          </div>
          <LineChart :labels="timeLabels" :datasets="chartDatasets(che)" :unit="chartUnit(che)" :zone-offset="tzOffset ?? undefined" :x-min="statsXMin" :x-max="statsXMax" :y-min="yBounds(che.key).min" :y-max="yBounds(che.key).max" />
        </div>
        <div class="empty small" v-if="!chartSections.length">За выбранный период данных нет</div>
      </template>
      <div v-else class="empty small">Нет данных за выбранный период</div>
    </section>

    <div v-if="displaySettingsOpen" class="modal-overlay" @click.self="displaySettingsOpen = false">
      <div class="modal" role="dialog" aria-modal="true" aria-labelledby="display-settings-title">
        <div class="modal-head">
          <h3 id="display-settings-title">Показ измерений</h3>
          <button class="modal-close" aria-label="Закрыть" @click="displaySettingsOpen = false">✕</button>
        </div>
        <div class="field-toggles">
          <label class="field-check" v-for="key in fieldKeys" :key="'modal-tg-' + key">
            <input type="checkbox" :checked="!isFieldHidden(chipId, key)" @change="toggleField(chipId, key)" />
            {{ key.toUpperCase() }} — {{ label(key) }}
          </label>
        </div>
        <p class="hint">
          Каналы этого датчика: скрытые не показываются ни в последних данных, ни на графиках.
          Выбор хранится в вашем аккаунте и переносится на другие устройства.
        </p>
      </div>
    </div>

    <div v-if="calibDialogOpen" class="modal-overlay" @click.self="calibDialogOpen = false">
      <div class="modal" role="dialog" aria-modal="true" aria-labelledby="calib-dialog-title">
        <div class="modal-head">
          <h3 id="calib-dialog-title">Калибровка влажности</h3>
          <button class="modal-close" aria-label="Закрыть" @click="calibDialogOpen = false">✕</button>
        </div>
        <div class="calib-row">
          <label>Воздух <input type="number" v-model.number="calibForm.value1" /></label>
          <label>Сухая почва (0%) <input type="number" v-model.number="calibForm.value2" /></label>
          <label>Вода (100%) <input type="number" v-model.number="calibForm.value3" /></label>
        </div>
        <p class="hint">
          Опорные точки для этого датчика: текущие значения из БД, при их отсутствии —
          дефолтные. Правка калибровки — только админом.
        </p>
        <div class="modal-actions">
          <button class="btn small secondary" @click="calibDialogOpen = false">Закрыть</button>
          <button class="btn small save" :disabled="calibForm.busy" @click="saveCalib">
            {{ calibForm.busy ? '...' : 'Сохранить' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSensorCatalog, getSensorTelemetry, getSensorFirstMessage, getDevice, listDeviceAccess, saveSensorCalibration, getMoistureScale, getSensorScales, getDeviceKey, regenerateDeviceKey } from '../api/auth'
import { auth, deviceAccess, rememberDeviceAccess } from '../store/auth'
import { getIndividualName, getBlockName, getFieldName, getFieldUnit, isFieldHidden, toggleField, getFieldY, setFieldY, fieldKeys } from '../store/settings'
import { pushNotification } from '../store/notifications'
import { calibrationFor, loadCalibration, setCalibration } from '../store/calibration'
import { adcToPercent, immersionWarning } from '../utils/moisture'
import { moistureScaleColor, sensorScaleColor } from '../utils/gradient'
import { fmtEventTimeWithZone, fmtAgo } from '../utils/datetime'
import LineChart from '../components/LineChart.vue'

const route = useRoute()
const router = useRouter()
const chipId = Number(route.params.chipId)
const queryDeviceId = route.query.deviceId ? Number(route.query.deviceId) : null
const rows = ref([])
const range = ref('24h')
const showCustom = ref(false)
const customFrom = ref('')
const customTo = ref('')
const loading = ref(true)
const catalogEntry = ref(null)
const tzOffset = ref(null)

const ranges = [
  { label: '3ч', value: '3h' },
  { label: '24ч', value: '24h' },
  { label: '3д', value: '3d' },
  { label: '7д', value: '7d' },
]

const isIndividual = computed(() => catalogEntry.value?.individual === true)
const blockRole = computed(() => catalogEntry.value?.blockRoles?.[0] || null)

/** Подпись блока-владельца: «Имя (id)» либо просто id, если имени нет. */
const blockLabel = computed(() => {
  if (isIndividual.value || !blockRole.value) return null
  const id = blockRole.value.blockId
  const name = getBlockName(id)
  return name ? `${name} (${id})` : String(id)
})

const displayName = computed(() => {
  const local = getIndividualName(chipId)
  if (local) return local
  return catalogEntry.value?.name || 'Датчик'
})

const sensorType = computed(() => {
  const last = rows.value.slice().reverse().find(r => r.sensorType != null)
  return last?.sensorType ?? 0
})

const timeLabels = computed(() => rows.value.map(r => r.time))

const statsXMin = ref(null)
const statsXMax = ref(null)

/**
 * Права текущего пользователя на устройство датчика. Границы оси и скрытие
 * каналов (v0–v3) — «настройка», а не отображение: viewer их не меняет
 * (сервер тоже отклоняет запись).
 */
const accessRole = ref(null)
const resolvedDeviceId = ref(null)

const canEditPrefs = computed(() => {
  if (auth.user?.role === 'ADMIN') return true
  if (accessRole.value) return accessRole.value !== 'VIEWER'
  // Роль ещё не загружена — сверяемся с общим кэшем, иначе не блокируем.
  return deviceAccess[resolvedDeviceId.value ?? chipId] !== 'VIEWER'
})

async function loadAccess(deviceId) {
  if (auth.user?.role === 'ADMIN') {
    accessRole.value = null
    return
  }
  if (accessRole.value) return
  const cached = deviceAccess[deviceId]
  if (cached) {
    accessRole.value = cached
    return
  }
  try {
    const list = await listDeviceAccess(deviceId)
    const mine = list.find(a => a.userId === auth.user?.id)
    if (mine?.accessRole) {
      accessRole.value = mine.accessRole
      rememberDeviceAccess(deviceId, mine.accessRole)
    }
  } catch {
    accessRole.value = null
  }
}

// --- Калибровка влажности этого датчика (только админ; PUT /api/sensors/{id}/calibration) ---

const isAdmin = computed(() => auth.user?.role === 'ADMIN')
const menuOpen = ref(false)
const menuRef = ref(null)
const displaySettingsOpen = ref(false)
const calibDialogOpen = ref(false)

function closeMenuOnOutsideClick(e) {
  if (menuOpen.value && !menuRef.value?.contains(e.target)) menuOpen.value = false
}

function closeMenuOnEscape(e) {
  if (e.key === 'Escape') {
    menuOpen.value = false
    displaySettingsOpen.value = false
    calibDialogOpen.value = false
  }
}

function openSettingsFromMenu() {
  menuOpen.value = false
  openSettings()
}

function openCalibrateFromMenu() {
  menuOpen.value = false
  openCalibrate()
}

function openDisplaySettings() {
  menuOpen.value = false
  displaySettingsOpen.value = true
}

function openCalibSettings() {
  menuOpen.value = false
  initCalibForm()
  calibDialogOpen.value = true
}

const deviceKey = ref(null)
const keyBusy = ref(false)

async function toggleKey() {
  if (deviceKey.value) {
    deviceKey.value = null
    return
  }
  keyBusy.value = true
  try {
    deviceKey.value = (await getDeviceKey(chipId)).key
  } catch (e) {
    pushNotification(e?.response?.data?.message || 'Не удалось получить ключ', 'error')
  } finally {
    keyBusy.value = false
  }
}

async function copyKey() {
  try {
    await navigator.clipboard.writeText(`ID ${chipId} · ключ ${deviceKey.value}`)
    pushNotification('Ключ скопирован')
  } catch {
    pushNotification('Скопируйте вручную', 'error')
  }
}

async function regenKey() {
  if (!confirm('Перевыпустить ключ? Прежний комплектный лист перестанет подходить.')) return
  keyBusy.value = true
  try {
    deviceKey.value = (await regenerateDeviceKey(chipId)).key
    pushNotification('Ключ перевыпущен')
  } catch (e) {
    pushNotification(e?.response?.data?.message || 'Не удалось перевыпустить ключ', 'error')
  } finally {
    keyBusy.value = false
  }
}

/** Секция только для админов и датчиков типа 0 (влажность). */
const calibSectionVisible = computed(() =>
  isAdmin.value && (!catalogEntry.value || Number(catalogEntry.value.sensorType ?? 0) === 0))

const calibForm = reactive({ value1: null, value2: null, value3: null, busy: false })
let calibFormReady = false

/** Заполняет форму один раз при первой загрузке страницы (кэш store-калибровки). */
async function initCalibForm() {
  if (calibFormReady || !calibSectionVisible.value) return
  const cal = await loadCalibration(chipId)
  if (cal) Object.assign(calibForm, { value1: cal.value1, value2: cal.value2, value3: cal.value3 })
  calibFormReady = true
}

async function saveCalib() {
  if (calibForm.busy) return
  if (calibForm.value1 == null || calibForm.value2 == null || calibForm.value3 == null) {
    pushNotification('Заполните все три опорных значения', 'error')
    return
  }
  calibForm.busy = true
  try {
    const v = await saveSensorCalibration(chipId, {
      value1: calibForm.value1, value2: calibForm.value2, value3: calibForm.value3,
    })
    if (v) {
      Object.assign(calibForm, { value1: v.value1, value2: v.value2, value3: v.value3 })
      // Обновляем кеш — график v0 в процентах сразу пересчитается.
      setCalibration(chipId, { value1: v.value1, value2: v.value2, value3: v.value3 })
    }
    pushNotification('Калибровка сохранена', 'success')
  } catch (e) {
    pushNotification(e?.response?.data?.message || 'Не удалось сохранить калибровку', 'error')
  } finally {
    calibForm.busy = false
  }
}

/** Страница калибровки 0% (value2): показана всем, кроме viewer. */
function openCalibrate() {
  router.push(`/sensor/${chipId}/calibrate${queryDeviceId ? `?deviceId=${queryDeviceId}` : ''}`)
}

/** Настройки автономного датчика: только не-viewer, только индивидуальный. */
function openSettings() {
  router.push(`/sensor/${chipId}/settings`)
}

/** Поля с редактируемым градиентом: v0 (влажность) и v1/v2/v3 (sensor_scale). */
function isGradientField(key) {
  return ['v0', 'v1', 'v2', 'v3'].includes(key)
}

function gradientTitle(key) {
  if (key === 'v0') return 'Градиент влажности'
  return `Градиент: ${label(key)}`
}

/** Цветовой градиент поля — отдельная страница настройки. */
function openGradient(key) {
  if (key === 'v0') {
    router.push(`/sensor/${chipId}/moisture-gradient`)
  } else {
    router.push({ path: `/sensor/${chipId}/field-gradient/${key}`, query: { name: label(key) } })
  }
}

/**
 * Последняя запись датчика в целом — грузится отдельно с limit=1, чтобы раздел
 * «Последние данные» показывал её, даже когда она старше выбранного периода.
 */
const lastAny = ref(null)

const latest = computed(() => lastAny.value || (rows.value.length ? rows.value[rows.value.length - 1] : null))

/** Текущее время для подписи «сколько назад» — обновляется вместе с таймером освежения. */
const nowTick = ref(Date.now())
const agoText = computed(() => fmtAgo(latest.value?.time, nowTick.value))

const batteryStart = ref(null)
const batteryStartLoaded = ref(false)
const BATTERY_START_TTL_MS = 5 * 60 * 1000
let batteryStartFetchedAt = 0

async function loadBatteryStart(deviceId) {
  if (Date.now() - batteryStartFetchedAt < BATTERY_START_TTL_MS) return
  try {
    const row = await getSensorFirstMessage(deviceId, chipId)
    batteryStart.value = row?.time ?? null
    batteryStartFetchedAt = Date.now()
  } catch {
    batteryStartFetchedAt = 0
  } finally {
    batteryStartLoaded.value = true
  }
}

function fmtBatteryRuntime(ms) {
  const totalMin = Math.max(0, Math.floor(ms / 60000))
  const days = Math.floor(totalMin / 1440)
  const hours = Math.floor((totalMin % 1440) / 60)
  const minutes = totalMin % 60
  if (days) return `${days} дн. ${hours} ч. ${minutes} мин.`
  if (hours) return `${hours} ч. ${minutes} мин.`
  return `${minutes} мин.`
}

const batteryRuntimeText = computed(() => {
  if (!batteryStart.value) return null
  const startMs = new Date(batteryStart.value).getTime()
  if (!Number.isFinite(startMs)) return null
  const ms = nowTick.value - startMs
  return ms >= 0 ? fmtBatteryRuntime(ms) : null
})

const moistureCal = computed(() => {
  if (sensorType.value !== 0) return null
  return calibrationFor(chipId)
})

/** Сохранённая шкала-градиент датчика (для цветной индикации влажности). */
const moistureScale = ref(null)

/** Шкалы-градиенты полей v1/v2/v3 (sensor_scale) — для цветной индикации показаний. */
const fieldScales = ref({})

const latestPct = computed(() => {
  const cal = moistureCal.value
  if (!cal || latest.value?.moistureAdc == null) return null
  return adcToPercent(latest.value.moistureAdc, cal)
})

const pctColor = computed(() => moistureScaleColor(moistureScale.value, latestPct.value))

function fieldColor(che, row) {
  if (che.key === 'v0') return null // влажность — отдельная логика (moistureScaleColor)
  const v = che.value(row)
  const scale = fieldScales.value[che.key]
  return sensorScaleColor(scale, v)
}

const immersionWarningShown = computed(() => {
  if (!latest.value || latest.value.moistureAdc == null) return false
  const cal = moistureCal.value
  return cal ? immersionWarning(latest.value.moistureAdc, cal) : false
})

const charts = computed(() => [
  { key: 'v0', value: r => r.moistureAdc, color: '#3498db' },
  { key: 'v1', value: r => r.temperature, color: '#e74c3c' },
  { key: 'v2', value: r => r.humidity, color: '#2ecc71' },
  { key: 'v3', value: r => r.light, color: '#f39c12' },
  { key: 'battery', value: r => r.battery, color: '#9b59b6' },
  // BatLoad — батарея под нагрузкой (автономные датчики): своя ячейка в
  // последних данных + вторая линия на графике батареи, не отдельный график.
  { key: 'batload', value: r => r.batload, color: '#e67e22', onBattery: true },
  { key: 'rssi', value: r => r.rssi, color: '#1abc9c' },
])

const availableCharts = computed(() => charts.value.filter(c => {
  if (isFieldHidden(chipId, c.key)) return false
  return rows.value.some(r => c.value(r) != null && !isNaN(c.value(r)))
}))

/**
 * Ячейки «Последних данных» — по последней записи датчика, даже если она старше
 * выбранного периода (иначе при пустом периоде показания не отображаются вовсе).
 */
const cellCharts = computed(() => charts.value.filter(c => {
  if (isFieldHidden(chipId, c.key)) return false
  const r = latest.value
  return r != null && c.value(r) != null && !isNaN(c.value(r))
}))

/** Графики-секции: BatLoad не рисуется отдельно, а входит в график батареи. */
const chartSections = computed(() => availableCharts.value.filter(c => !c.onBattery))

/** Подпись канала — из настроек (страница «Настройки»); дефолты для батареи/RSSI. */
function label(key) {
  const name = getFieldName(sensorType.value, key)
  if (name !== key) return name
  if (key === 'battery') return 'Батарея'
  if (key === 'batload') return 'Батарея под нагрузкой'
  if (key === 'rssi') return 'RSSI'
  return name
}

/** Единица величины — из настроек; дефолты для батареи/RSSI. */
function fieldUnit(key) {
  const u = getFieldUnit(sensorType.value, key)
  if (u) return u
  if (key === 'battery') return 'В'
  if (key === 'batload') return 'В'
  if (key === 'rssi') return 'dBm'
  return ''
}

/** Влажность почвы в процентах (как на странице блока): "74.2% (512)". */
function moistureDisplay(adc) {
  const cal = moistureCal.value
  const pct = cal ? adcToPercent(adc, cal) : null
  return pct != null ? `${pct}% (${adc})` : fmtValue(adc)
}

function cellValue(che, row) {
  const v = che.value(row)
  if (che.key === 'v0') return moistureDisplay(v)
  return fmtValue(v)
}

function cellUnit(che) {
  // Процент уже внутри значения «74.2% (1554)» — единица не дублируется.
  if (che.key === 'v0') return moistureCal.value ? '' : fieldUnit('v0')
  return fieldUnit(che.key)
}

function chartData(che) {
  if (che.key === 'v0' && moistureCal.value) {
    return rows.value.map(r => r.moistureAdc != null ? adcToPercent(r.moistureAdc, moistureCal.value) : null)
  }
  return rows.value.map(r => che.value(r))
}

/** На графике батареи второй линией — BatLoad (нагрузка), если по датчику есть. */
function chartDatasets(che) {
  const ds = [{ label: label(che.key), data: chartData(che), color: che.color }]
  if (che.key === 'battery') {
    const bl = availableCharts.value.find(c => c.key === 'batload')
    if (bl) ds.push({ label: label('batload'), data: chartData(bl), color: bl.color })
  }
  return ds
}

function chartUnit(che) {
  if (che.key === 'v0') {
    if (moistureCal.value) return getFieldUnit(sensorType.value, 'v0') || '%'
    return fieldUnit('v0')
  }
  return fieldUnit(che.key)
}

// --- Выбор границ вертикальной шкалы (v0–v3), запоминается в настройках пользователя ---

const Y_EDITABLE = ['v0', 'v1', 'v2', 'v3']

function isYEditable(key) {
  return Y_EDITABLE.includes(key)
}

const yEdits = reactive({})

function yEdit(key) {
  if (!yEdits[key]) {
    const r = getFieldY(chipId, key)
    yEdits[key] = { min: r.min ?? '', max: r.max ?? '' }
  }
  return yEdits[key]
}

/** График батареи всегда в фиксированном диапазоне 1,5–3,5 В (для всех датчиков). */
const BATTERY_Y = { min: 1.5, max: 3.5 }

function yBounds(key) {
  if (key === 'battery') return BATTERY_Y
  if (!isYEditable(key)) return { min: null, max: null }
  return getFieldY(chipId, key)
}

function parseBound(v) {
  if (v === '' || v == null) return null
  const n = Number(String(v).replace(',', '.'))
  return Number.isFinite(n) ? n : NaN
}

function applyY(key) {
  if (!canEditPrefs.value) return
  const e = yEdit(key)
  const min = parseBound(e.min)
  const max = parseBound(e.max)
  if ((min != null && !Number.isFinite(min)) || (max != null && !Number.isFinite(max))) {
    pushNotification('Граница шкалы — число (допустима запятая)', 'error')
    return
  }
  if (min != null && max != null && max <= min) {
    pushNotification('max должен быть больше min', 'error')
    return
  }
  setFieldY(chipId, key, min, max)
  pushNotification('Шкала сохранена', 'success')
}

function resetY(key) {
  if (!canEditPrefs.value) return
  const e = yEdit(key)
  e.min = ''
  e.max = ''
  setFieldY(chipId, key, null, null)
}

function fmtValue(v) {
  if (v == null || isNaN(v)) return '—'
  return typeof v === 'number' ? v.toFixed(1).replace(/\.0$/, '') : String(v)
}

function parseRange(r) {
  const num = parseInt(r)
  const unit = r.slice(-1)
  const ms = unit === 'h' ? num * 3600000 : num * 86400000
  return new Date(Date.now() - ms).toISOString()
}

async function setRange(r) {
  range.value = r
  await loadData()
}

/** Начало периода: для «своего» — поле «С», для пресетов — откат от сейчас. */
function currentSince() {
  if (range.value === 'custom') {
    return customFrom.value ? new Date(customFrom.value).toISOString() : ''
  }
  return parseRange(range.value)
}

/** Конец периода: только у «своего» периода, у пресетов — до текущего момента. */
function currentTo() {
  if (range.value === 'custom') {
    return customTo.value ? new Date(customTo.value).toISOString() : ''
  }
  return ''
}

function applyCustom() {
  range.value = 'custom'
  loadData()
}

async function loadData(silent = false) {
  if (!silent) loading.value = true
  try {
    const catalog = await getSensorCatalog()
    catalogEntry.value = catalog.find(c => Number(c.chipId) === chipId) || null
    // Форма калибровки (админ): один раз, не блокирует загрузку данных.
    initCalibForm()
    let deviceId = queryDeviceId
    if (!deviceId) {
      const entry = catalogEntry.value
      if (entry && !entry.individual && entry.blockRoles?.length) {
        deviceId = entry.blockRoles[0].blockId
      } else {
        deviceId = chipId
      }
    }
    resolvedDeviceId.value = deviceId
    await loadAccess(deviceId)
    if (tzOffset.value == null) {
      try {
        const device = await getDevice(deviceId)
        tzOffset.value = device?.tzOffsetMinutes ?? null
      } catch { /* зона остаётся по умолчанию */ }
    }
    const since = currentSince()
    const to = currentTo()
    const batteryPromise = loadBatteryStart(deviceId)
    const [periodRows, fresh] = await Promise.all([
      getSensorTelemetry(deviceId, chipId, since || undefined, to || undefined),
      // Последняя запись без привязки к периоду; ошибка не обнуляет прежнее значение.
      getSensorTelemetry(deviceId, chipId, undefined, undefined, 1).catch(() => null),
    ])
    await batteryPromise
    rows.value = periodRows
    rows.value.reverse()
    if (Array.isArray(fresh)) lastAny.value = fresh.length ? fresh[0] : null
    statsXMin.value = since ? Date.parse(since) : (rows.value.length ? Date.parse(rows.value[0].time) : Date.now() - 3600000)
    statsXMax.value = to
      ? Date.parse(to)
      : (rows.value.length ? Date.parse(rows.value[rows.value.length - 1].time) : statsXMin.value + 1)
    if (!(statsXMax.value > statsXMin.value)) statsXMax.value = statsXMin.value + 1
    if (sensorType.value === 0) await loadCalibration(chipId)
  } finally {
    loading.value = false
  }
}

let refreshTimer = null

onMounted(() => {
  loadData()
  getMoistureScale(chipId).then(s => { moistureScale.value = s }).catch(() => {})
  getSensorScales(chipId).then(list => {
    const map = {}
    for (const sc of list || []) {
      if (sc.enabled && sc.minValue != null && sc.maxValue != null) map[sc.fieldKey] = sc
    }
    fieldScales.value = map
  }).catch(() => { fieldScales.value = {} })
  refreshTimer = setInterval(() => { nowTick.value = Date.now(); loadData(true) }, 30000)
  document.addEventListener('click', closeMenuOnOutsideClick)
  document.addEventListener('keydown', closeMenuOnEscape)
})
onUnmounted(() => {
  clearInterval(refreshTimer)
  document.removeEventListener('click', closeMenuOnOutsideClick)
  document.removeEventListener('keydown', closeMenuOnEscape)
})
</script>

<style scoped>
.sensor { max-width: 600px; margin: 0 auto; padding: 1rem; }
.header-row { display: flex; align-items: center; gap: 0.75rem; position: sticky; top: 0; z-index: 50; margin: -1rem -1rem 1.5rem; padding: 1rem; background: var(--bg); }
.header-row h2 { color: var(--text-strong); flex: 1; }
.type-tag { font-size: 0.7rem; padding: 0.2rem 0.5rem; border-radius: 4px; background: var(--tag-bg); color: var(--tag-text); }
.menu-wrap { position: relative; flex: none; }
.menu-toggle { width: 2.2rem; height: 2.2rem; padding: 0; border: none; border-radius: 50%; display: flex; align-items: center; justify-content: center; background: transparent; color: var(--text-strong); cursor: pointer; }
.menu-toggle:hover, .menu-toggle[aria-expanded='true'] { background: var(--bg-primary); color: var(--text-strong); }
.menu-dropdown { position: absolute; top: calc(100% + 0.4rem); right: 0; z-index: 60; min-width: 200px; padding: 0.35rem; display: flex; flex-direction: column; background: var(--bg-card); border: 1px solid var(--border-soft); border-radius: 10px; box-shadow: 0 8px 30px rgba(0, 0, 0, 0.45); }
.menu-dropdown button { width: 100%; padding: 0.55rem 0.7rem; border: none; border-radius: 6px; display: flex; align-items: center; gap: 0.55rem; background: transparent; color: var(--text); font-size: 0.85rem; text-align: left; white-space: nowrap; cursor: pointer; }
.menu-dropdown button:hover { background: var(--bg-primary); }
.menu-icon { width: 1rem; height: 1rem; flex: none; opacity: 0.9; }
.ago { color: var(--text-dim); font-size: 0.85em; margin-left: 0.35rem; }
.msg-num { color: var(--text-dim); font-size: 0.85em; margin-left: 0.25rem; }
.battery-runtime { display: flex; align-items: baseline; flex-wrap: wrap; gap: 0.4rem; background: var(--bg); border-left: 3px solid var(--accent); border-radius: 10px; padding: 0.7rem 1rem; font-size: 0.85rem; color: var(--text-muted); margin-bottom: 1.5rem; }
.battery-runtime b { color: var(--text-strong); }
.battery-label { color: var(--text-dim); }
.battery-runtime .muted { color: var(--text-dim); }
.section-head { display: flex; align-items: center; justify-content: space-between; gap: 0.5rem; }
.section-head h3 { margin: 0; }
.invite-box { background: var(--bg); border: 1px solid var(--border); border-radius: 8px; padding: 0.6rem; margin-top: 0.6rem; }
.invite-link { color: var(--accent); font-family: monospace; font-size: 0.75rem; word-break: break-all; margin-bottom: 0.4rem; }
.invite-note { color: var(--text-dim); font-size: 0.75rem; line-height: 1.4; margin-bottom: 0.5rem; }
.invite-actions { display: flex; flex-wrap: wrap; gap: 0.6rem; margin-top: 0.2rem; }
.btn.danger { background: var(--bg-accent); border-color: var(--bg-accent); color: var(--on-accent); }
.btn.danger:hover:not(:disabled) { background: #7a2222; border-color: #7a2222; color: #fff; }
.gradient-btn {
  width: 16px; height: 16px; flex: none; padding: 0; cursor: pointer;
  border: 2px solid #fff; border-radius: 50%;
  background: conic-gradient(#ff0000 0 120deg, #00ff00 120deg 240deg, #0000ff 240deg 360deg);
}
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.small { font-size: 0.75rem; padding: 0.3rem 0.6rem; }
.btn.secondary { background: var(--bg-accent); }
.btn.active { background: var(--bg-active); border: 1px solid var(--accent); }
.section { margin-bottom: 2rem; }
.section h3 { color: var(--text-muted); font-size: 0.9rem; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 0.75rem; }
.range-select { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.custom-range { display: flex; gap: 0.6rem; align-items: center; flex-wrap: wrap; margin-bottom: 1rem; padding: 0.6rem 0.8rem; background: var(--bg-card); border-radius: 10px; }
.custom-range label { display: flex; align-items: center; gap: 0.4rem; color: var(--text-muted); font-size: 0.78rem; }
.custom-range input { background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.8rem; padding: 0.3rem 0.5rem; }
.last-received { color: var(--text-muted); font-size: 0.85rem; margin-bottom: 0.6rem; }
.last-received b { color: var(--text-strong); }
.immersion-warning { color: var(--danger); background: #3a1518; font-weight: 600; font-size: 0.85rem; padding: 0.5rem 0.7rem; border-radius: 8px; margin-bottom: 0.7rem; }
.latest-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 0.6rem; }
.latest-cell { background: var(--bg-card); border-radius: 10px; padding: 0.7rem 0.9rem; }
.l-label { display: flex; align-items: center; justify-content: space-between; gap: 0.4rem; color: var(--text-sub); font-size: 0.8rem; margin-bottom: 0.25rem; }
.l-value { color: var(--text); font-size: 1rem; font-weight: 500; display: inline-flex; align-items: center; }
.pct-color { display: inline-block; width: 2.55em; height: 0.85em; margin: 0.2em 0 0 0.4rem; flex: none; border: 1px solid #fff; border-radius: 2px; }
.l-unit { color: var(--text); font-size: 1rem; font-weight: 500; }
.chart-section { margin-bottom: 1.5rem; }
.chart-head { display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; flex-wrap: wrap; margin-bottom: 0.3rem; }
.chart-section h4 { color: var(--text-dim); font-size: 0.8rem; margin-bottom: 0; }
.y-edit { display: flex; align-items: center; gap: 0.4rem; flex-wrap: wrap; }
.y-edit label { display: flex; align-items: center; gap: 0.3rem; color: var(--text-dim); font-size: 0.7rem; }
.y-edit input { width: 5.5rem; background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.75rem; padding: 0.2rem 0.4rem; }
.btn.ghost { background: transparent; border: 1px solid var(--border-soft); color: var(--text-muted); }
.field-toggles { display: flex; flex-direction: column; gap: 0.5rem; }
.field-check { display: flex; align-items: center; gap: 0.35rem; color: var(--text-sub); font-size: 0.8rem; cursor: pointer; }
.hint { color: var(--text-dim); font-size: 0.75rem; margin-top: 0.5rem; }
.calib-row { display: flex; flex-wrap: wrap; gap: 0.6rem; align-items: center; }
.calib-row label { display: flex; align-items: center; gap: 0.4rem; color: var(--text-muted); font-size: 0.78rem; background: var(--bg-card); border-radius: 8px; padding: 0.4rem 0.6rem; }
.calib-row input { width: 4.5rem; background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.85rem; padding: 0.3rem 0.5rem; }
.unit { color: var(--text-faint); font-weight: 400; }
.modal-overlay { position: fixed; inset: 0; z-index: 100; display: flex; align-items: center; justify-content: center; padding: 1rem; background: rgba(0, 0, 0, 0.6); }
.modal { width: 100%; max-width: 420px; max-height: 85vh; overflow-y: auto; padding: 1.25rem; background: var(--bg-card); border-radius: 12px; box-shadow: 0 8px 30px rgba(0, 0, 0, 0.5); }
.modal-head { display: flex; align-items: center; justify-content: space-between; gap: 0.75rem; margin-bottom: 0.9rem; }
.modal h3 { color: var(--text-strong); font-size: 1rem; text-transform: none; letter-spacing: 0; margin: 0; }
.modal-close { width: 2rem; height: 2rem; padding: 0; border: none; border-radius: 50%; background: transparent; color: var(--text-dim); font-size: 1rem; cursor: pointer; }
.modal-close:hover { background: var(--bg-primary); color: var(--text); }
.modal-actions { display: flex; justify-content: center; gap: 0.6rem; margin-top: 1rem; }
.modal-actions .btn { display: inline-flex; align-items: center; justify-content: center; width: 8rem; height: 2rem; padding: 0 0.6rem; }
.empty.small { color: var(--text-faint); text-align: center; padding: 1rem; font-size: 0.85rem; }
</style>
