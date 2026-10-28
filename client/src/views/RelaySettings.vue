<template>
  <div class="relay-settings">
    <div class="header-row">
      <button @click="$router.back()" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
      <h2>{{ displayName }} · {{ relayTitle }}</h2>
      <span v-if="device?.deviceType === 'BLOCK'" class="status-tag" :class="isOnline ? 'online' : 'offline'">
        {{ isOnline ? 'online' : 'offline' }}
      </span>
      <button
        v-if="relay"
        type="button"
        class="btn small back-btn save-icon-btn"
        :disabled="!isOnline || !formValid || sending"
        :title="sending ? 'Отправка...' : 'Сохранить'"
        :aria-label="sending ? 'Отправка...' : 'Сохранить'"
        @click="save"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>
      </button>
    </div>

    <div v-if="loading" class="loading">Загрузка...</div>
    <div v-else-if="!relay" class="empty small">Канал не найден</div>
    <template v-else>
      <div v-if="!isOnline" class="offline-banner">
        Блок оффлайн — настройка канала невозможна
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
          <div class="field" :class="{ disabled: !isOnline }">
            <span class="field-label">Название (до 32 символов)</span>
            <input type="text" v-model="form.name" maxlength="32" :disabled="!isOnline" />
          </div>

          <div class="field" :class="{ disabled: !isOnline }">
            <span class="field-label">Активация: {{ form.activate ? 'активирован' : 'неактивирован' }}</span>
            <span class="toggle" :class="{ on: form.activate }" @click="toggleActivate">
              <span class="toggle-dot"></span>
            </span>
          </div>

          <div class="field" :class="{ disabled: !isOnline }">
            <span class="field-label">Тип</span>
            <select v-model.number="form.type" :disabled="!isOnline">
              <option v-for="t in types" :key="t.value" :value="t.value">{{ t.label }}{{ t.comment ? ' — ' + t.comment : '' }}</option>
            </select>
          </div>

          <div class="field" :class="{ disabled: !isOnline }">
            <span class="field-label">Длительность основного включения (1–60 мин)</span>
            <span class="field-row">
              <input type="number" min="1" max="60" step="1" v-model.number="form.worktimeFirst" :disabled="!isOnline" />
              <span class="field-unit">мин</span>
            </span>
          </div>

          <template v-if="isTypeTime">
            <div class="field" :class="{ disabled: !isOnline }">
              <span class="field-label">Длительность дополнительного включения (0–60 мин)</span>
              <span class="field-row">
                <input type="number" min="0" max="60" step="1" v-model.number="form.worktimeSecond" :disabled="!isOnline" />
                <span class="field-unit">мин</span>
                <button type="button" class="btn-clear" title="Сбросить (не задано)" :disabled="!isOnline" @click="clearField('worktimeSecond')">✕</button>
              </span>
            </div>

            <div class="field" :class="{ disabled: !isOnline }">
              <span class="field-label">Интервал между включениями (1–168 ч)</span>
              <span class="field-row">
                <input type="number" min="1" max="168" step="1" v-model.number="form.betweentime" :disabled="!isOnline" />
                <span class="field-unit">ч</span>
              </span>
            </div>

            <div class="field" :class="{ disabled: !isOnline }">
              <span class="field-label">Основное время включения</span>
              <span class="field-row">
                <input type="time" step="60" v-model="form.turnOnTimeFirst" :disabled="!isOnline" />
              </span>
            </div>

            <div class="field" :class="{ disabled: !isOnline }">
              <span class="field-label">Дополнительное время включения</span>
              <span class="field-row">
                <input type="time" step="60" v-model="form.turnOnTimeSecond" :disabled="!isOnline" />
                <button type="button" class="btn-clear" title="Сбросить (не задано)" :disabled="!isOnline" @click="clearField('turnOnTimeSecond')">✕</button>
              </span>
            </div>
          </template>

          <div v-if="isTypeOne" class="field" :class="{ disabled: !isOnline }">
            <span class="field-label">Датчик</span>
            <select v-model.number="form.idSensor" :disabled="!isOnline">
              <option :value="0">{{ registered.length ? 'не выбран' : 'нет зарегистрированных датчиков' }}</option>
              <option v-for="sen in registered" :key="Number(sen.sensorChipId)" :value="Number(sen.sensorChipId)">{{ sensorLabel(sen) }}</option>
            </select>
          </div>

          <div v-if="isTypeOne" class="field" :class="{ disabled: !isOnline }">
            <span class="field-label">Порог включения канала<template v-if="oneThresholdUnit">, {{ oneThresholdUnit }}</template><template v-if="isOnePct"> ({{ thresholdMinPct }}–{{ thresholdMaxPct }})</template><template v-else-if="form.idSensor"> (0–65535)</template></span>
            <input v-if="isOnePct" type="number" :min="thresholdMinPct" :max="thresholdMaxPct" step="0.1" v-model.number="limitPct" :disabled="!isOnline" />
            <input v-else type="number" min="0" max="65535" step="1" v-model.number="form.limitSingle" :disabled="!isOnline" />
            <div v-if="isOnePct && sensorScale" class="scale-slider">
              <input type="range" :min="thresholdMinPct" :max="thresholdMaxPct" step="0.1"
                     v-model.number="limitPct" :disabled="!isOnline" :style="sliderTrackStyle" />
              <div class="scale-labels">
                <span>{{ thresholdMinPct }}%</span>
                <span>{{ thresholdMaxPct }}%</span>
              </div>
            </div>
          </div>

          <div v-if="isTypeOne" class="field" :class="{ disabled: !isOnline }">
            <span class="field-label">Начало запретного интервала</span>
            <span class="field-row">
              <input type="time" step="60" v-model="form.startTime" :disabled="!isOnline" />
              <button type="button" class="btn-clear" title="Сбросить (не задано)" :disabled="!isOnline" @click="clearField('startTime')">✕</button>
            </span>
          </div>

          <div v-if="isTypeOne" class="field" :class="{ disabled: !isOnline }">
            <span class="field-label">Конец запретного интервала</span>
            <span class="field-row">
              <input type="time" step="60" v-model="form.finishTime" :disabled="!isOnline" />
              <button type="button" class="btn-clear" title="Сбросить (не задано)" :disabled="!isOnline" @click="clearField('finishTime')">✕</button>
            </span>
          </div>

          <template v-if="isTypeThreshold">
            <div class="field" :class="{ disabled: !isOnline }">
              <span class="field-label">Использовать таймер выключения?</span>
              <label class="checkbox">
                <input type="checkbox" v-model="form.useTimer" :disabled="!isOnline" />
                <span>{{ form.useTimer ? 'да' : 'нет' }}</span>
              </label>
            </div>

            <div class="field" :class="{ disabled: !isOnline }">
              <span class="field-label">Датчик</span>
              <select v-model.number="form.idSensor" :disabled="!isOnline" @change="syncDataType">
                <option :value="0">{{ registered.length ? 'не выбран' : 'нет зарегистрированных датчиков' }}</option>
                <option v-for="sen in registered" :key="Number(sen.sensorChipId)" :value="Number(sen.sensorChipId)">{{ sensorLabel(sen) }}</option>
              </select>
            </div>

            <div v-if="form.idSensor && dataTypeOptions.length" class="field" :class="{ disabled: !isOnline }">
              <span class="field-label">Измеряемая величина датчика</span>
              <select v-model.number="form.dataType" :disabled="!isOnline">
                <option v-for="opt in dataTypeOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
              </select>
            </div>

            <div class="field" :class="{ disabled: !isOnline }">
              <span class="field-label">Пороги<template v-if="thresholdUnit">, {{ thresholdUnit }}</template></span>
              <div class="threshold-inputs">
                <label class="threshold-input">
                  <span>{{ isThresholdPct ? 'Верхний' : 'Нижний' }}</span>
                  <input v-if="isThresholdPct" type="number" :min="thresholdSliderMin" :max="thresholdSliderMax" step="0.1"
                         v-model.number="lowPct" :disabled="!isOnline" :class="{ invalid: thresholdInvalid }" />
                  <input v-else type="number" :min="intMin" :max="intMax" step="1"
                         v-model.number="form.limitLow" :disabled="!isOnline" :class="{ invalid: thresholdInvalid }" @input="clampLow" />
                </label>
                <label class="threshold-input">
                  <span>{{ isThresholdPct ? 'Нижний' : 'Верхний' }}</span>
                  <input v-if="isThresholdPct" type="number" :min="thresholdSliderMin" :max="thresholdSliderMax" step="0.1"
                         v-model.number="highPct" :disabled="!isOnline" :class="{ invalid: thresholdInvalid }" />
                  <input v-else type="number" :min="intMin" :max="intMax" step="1"
                         v-model.number="form.limitHigh" :disabled="!isOnline" :class="{ invalid: thresholdInvalid }" @input="clampHigh" />
                </label>
              </div>
              <template v-if="fieldScale">
                <div class="dual-slider" :style="dualSliderStyle">
                  <template v-if="isThresholdPct">
                    <input type="range" :min="thresholdSliderMin" :max="thresholdSliderMax" step="0.1"
                           v-model.number="lowPct" :disabled="!isOnline" @input="clampPctLow" />
                    <input type="range" :min="thresholdSliderMin" :max="thresholdSliderMax" step="0.1"
                           v-model.number="highPct" :disabled="!isOnline" @input="clampPctHigh" />
                  </template>
                  <template v-else>
                    <input type="range" :min="thresholdSliderMin" :max="thresholdSliderMax" step="1"
                           v-model.number="form.limitLow" :disabled="!isOnline" @input="clampLow" />
                    <input type="range" :min="thresholdSliderMin" :max="thresholdSliderMax" step="1"
                           v-model.number="form.limitHigh" :disabled="!isOnline" @input="clampHigh" />
                  </template>
                </div>
                <div class="scale-labels">
                  <span>{{ thresholdSliderMin }}<template v-if="thresholdUnit"> {{ thresholdUnit }}</template></span>
                  <span>{{ thresholdSliderMax }}<template v-if="thresholdUnit"> {{ thresholdUnit }}</template></span>
                </div>
              </template>
              <span v-if="thresholdInvalid" class="field-error">
                {{ isThresholdPct ? 'Значение верхнего порога не должно превышать нижний' : 'Нижний порог не должен превышать верхний' }}
              </span>
            </div>
          </template>

        </form>
      </section>
    </template>

    <div v-if="timerDialog" class="modal-overlay" @click.self="timerDialog = false">
      <div class="modal">
        <h3>Таймер выключения не задан</h3>
        <p class="modal-sub">Датчики влажности почвы достаточно инерционны, поэтому рекомендуется использовать
          таймер выключения канала, чтобы недопустить слишком длинного интервала включения канала.
          Вы хотите продолжить без таймера?</p>
        <div class="modal-actions">
          <button class="btn secondary" @click="timerDialog = false; doSend()">Да</button>
          <button class="btn" @click="timerDialog = false">Нет</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { getDevice, getDeviceRelays, getDeviceSensors, getDeviceRegisteredSensors, getDeviceStatuses, sendRelayParams, getMoistureScale, getSensorScale } from '../api/auth'
import { getBlockName, getFieldName, getFieldUnit } from '../store/settings'
import { pushNotification } from '../store/notifications'
import { canControlDevice, isAdmin } from '../store/auth'
import { loadCalibration, calibrationFor } from '../store/calibration'
import { adcToPercent, percentToAdc } from '../utils/moisture'
import { moistureGradientCss } from '../utils/gradient'

const route = useRoute()

const chipId = Number(route.params.chipId)
const relayNumber = Number(route.params.relayNumber)

const types = [
  { value: 0, code: 'TIME', label: 'по времени' },
  { value: 1, code: 'S_ONE', label: 'от датчика', comment: 'по превышению одного порога' },
  { value: 2, code: 'S_LOW', label: 'от датчика', comment: 'включение по нижнему, выключение по верхнему' },
  { value: 3, code: 'S_HIGH', label: 'от датчика', comment: 'включение по верхнему, выключение по нижнему' },
]
const typeCodes = { TIME: 0, S_ONE: 1, S_LOW: 2, S_HIGH: 3 }
const intMin = -32768
const intMax = 32767

const loading = ref(true)
const sending = ref(false)
const timerDialog = ref(false)
const device = ref(null)
const relay = ref(null)
const statusMap = ref({})
const sensors = ref([])
const registered = ref([])

const form = reactive({
  name: '',
  activate: false,
  type: 0,
  worktimeFirst: 5,
  worktimeSecond: '',
  betweentime: 1,
  turnOnTimeFirst: '00:00',
  turnOnTimeSecond: '00:00',
  idSensor: 0,
  limitSingle: 0,
  startTime: '',
  finishTime: '',
  useTimer: false,
  dataType: 0,
  limitLow: 0,
  limitHigh: 0,
})

const UNSET_TIME_MINUTES = 1441

function toHm(v) {
  if (v == null) return ''
  const s = String(v).trim()
  if (s === '1440' || s === '1441' || s.startsWith('24:')) return ''
  return s.includes(':') ? s.slice(0, 5) : s
}

function codeToValue(code) {
  return typeCodes[String(code || '').toUpperCase()] ?? 0
}

function applyFromRelay(r) {
  form.name = r?.name || ''
  form.activate = r?.activity === true
  form.type = codeToValue(r?.typeEnum)
  form.worktimeFirst = r?.durationMain ?? 5
  form.worktimeSecond = (r?.durationExtra == null || Number(r.durationExtra) === 0 || Number(r.durationExtra) === 1440) ? '' : (r?.durationExtra ?? '')
  form.betweentime = r?.intervalH ?? 1
  form.turnOnTimeFirst = toHm(r?.mainTime) || '00:00'
  form.turnOnTimeSecond = toHm(r?.extraTime)
  form.idSensor = r?.sensorId ? Number(r.sensorId) : 0
  form.limitSingle = r?.oneLimit ?? 0
  form.startTime = toHm(r?.startTime)
  form.finishTime = toHm(r?.finishTime)
  form.useTimer = r?.timerEnabled === true
  form.dataType = r?.dataType ?? 0
  form.limitLow = r?.lowLimit ?? 0
  form.limitHigh = r?.highLimit ?? 0
  syncLimitPct()
}

const displayName = computed(() => getBlockName(chipId) || String(chipId))
const relayTitle = computed(() => `Канал R${relayNumber}`)
const isOnline = computed(() => statusMap.value[chipId]?.status === 'online')
const isTypeTime = computed(() => form.type === 0)
const isTypeOne = computed(() => form.type === 1)
const isTypeThreshold = computed(() => form.type === 2 || form.type === 3)

function typeLabel(t) {
  const x = types.find(v => v.value === t)
  return x ? (x.comment ? `${x.label} (${x.comment})` : x.label) : '—'
}

function sensorTypeFor(id) {
  const s = sensors.value.find(x => String(x.sensorChipId) === String(id))
  return s?.sensorType ?? 0
}

function sensorLabel(sen) {
  const s = sensors.value.find(x => String(x.sensorChipId) === String(sen.sensorChipId))
  return s?.name || `S${sen.slot} · ${sen.sensorChipId}`
}

function sensorLabelById(id) {
  if (!id) return 'не выбран'
  const sen = registered.value.find(x => String(x.sensorChipId) === String(id))
  return sen ? sensorLabel(sen) : String(id)
}

/**
 * Порог канала S_ONE в % влажности: калибровка выбранного датчика типа 0
 * (value2=сухая почва → 0%, value3=вода → 100%). Показываем %, в блок
 * уходит пересчитанный АЦП; без калибровки — прежний числовой ввод АЦП.
 */
const limitPct = ref(0)
const sensorCal = computed(() => (form.idSensor ? calibrationFor(Number(form.idSensor)) : null))
const isOnePct = computed(() => isTypeOne.value && sensorTypeFor(form.idSensor) === 0 && sensorCal.value != null)
const pctAdcPreview = computed(() => (isOnePct.value ? percentToAdc(limitPct.value, sensorCal.value) : null))

/** Шкала-градиент выбранного датчика — полоска слайдера порога. */
const sensorScale = ref(null)

const thresholdMinPct = computed(() =>
  isOnePct.value && sensorScale.value?.minPct != null ? Number(sensorScale.value.minPct) : 0)
const thresholdMaxPct = computed(() =>
  isOnePct.value && sensorScale.value?.maxPct != null ? Number(sensorScale.value.maxPct) : 100)

const sliderTrackStyle = computed(() => {
  if (!isOnePct.value || !sensorScale.value?.enabled) return null
  return { background: moistureGradientCss(sensorScale.value.minColor, sensorScale.value.maxColor) }
})

function maybeLoadSensorScale(id) {
  const chip = Number(id)
  if (!chip || sensorTypeFor(chip) !== 0) {
    sensorScale.value = null
    return
  }
  getMoistureScale(chip)
    .then(s => { sensorScale.value = s?.enabled && s?.minPct != null && s?.maxPct != null ? s : null })
    .catch(() => { sensorScale.value = null })
}

function syncLimitPct() {
  if (!isOnePct.value) return
  limitPct.value = adcToPercent(form.limitSingle, sensorCal.value) ?? 0
}

function maybeLoadSensorCalibration() {
  if (form.idSensor && sensorTypeFor(form.idSensor) === 0) {
    loadCalibration(Number(form.idSensor)).then(syncLimitPct)
    maybeLoadSensorScale(form.idSensor)
  } else {
    sensorScale.value = null
  }
}

// --- Пороги Low/High (тип 2/3): шкала-градиент выбранного поля датчика ---

/** Шкала-градиент выбранного поля (v0 — влажность, v1/v2/v3 — sensor_scale). */
const fieldScale = ref(null)

const isThresholdPct = computed(() =>
  isTypeThreshold.value && form.dataType === 0 && sensorTypeFor(form.idSensor) === 0 && sensorCal.value != null)

const lowPct = ref(0)
const highPct = ref(0)

/** Границы слайдера: из сохранённой шкалы (LimL/LimH как в прошивке), иначе int16. */
const thresholdSliderMin = computed(() => {
  if (fieldScale.value?.min != null) return fieldScale.value.min
  return isThresholdPct.value ? 0 : intMin
})
const thresholdSliderMax = computed(() => {
  if (fieldScale.value?.max != null) return fieldScale.value.max
  return isThresholdPct.value ? 100 : intMax
})

/** Трек слайдера — сохранённый градиент поля; без него — обычный серый. */
const dualSliderStyle = computed(() => {
  if (!fieldScale.value?.enabled) return null
  return { background: moistureGradientCss(fieldScale.value.minColor, fieldScale.value.maxColor) }
})

/**
 * У влажности пересчёт обратный (0% = сухо = большой АЦП), поэтому в %-режиме
 * «Нижний» % уходит в limitHigh, «Верхний» % — в limitLow, чтобы блок получил
 * limitLow ≤ limitHigh.
 */
const thresholdAdcLow = computed(() => form.limitLow)
const thresholdAdcHigh = computed(() => form.limitHigh)

/**
 * Смена измеряемой величины/типа канала: если у датчика задан градиент по
 * новой величине — ставим пороги на min/max шкалы; без градиента не трогаем.
 */
let prevDataType = null
let resetThresholdsPending = false

function applyScaleDefaults() {
  // Градиента по этой величине нет — прежние пороги оставляем как есть.
  if (!fieldScale.value) {
    syncThresholdPct()
    return
  }
  if (isThresholdPct.value) {
    lowPct.value = fieldScale.value.min
    highPct.value = fieldScale.value.max
    syncThresholdFromPct()
  } else {
    form.limitLow = fieldScale.value.min
    form.limitHigh = fieldScale.value.max
  }
}

function maybeLoadFieldScale() {
  if (!isTypeThreshold.value || !form.idSensor) {
    fieldScale.value = null
    resetThresholdsPending = false
    return
  }
  const chip = Number(form.idSensor)
  if (form.dataType === 0 && sensorTypeFor(chip) === 0) {
    getMoistureScale(chip)
      .then(s => {
        fieldScale.value = s?.enabled && s.minPct != null && s.maxPct != null
          ? { enabled: true, min: Number(s.minPct), max: Number(s.maxPct), minColor: s.minColor, maxColor: s.maxColor }
          : null
        if (resetThresholdsPending) {
          resetThresholdsPending = false
          applyScaleDefaults()
        } else {
          // Шкала догрузилась — переклампить пороги в её пределы.
          syncThresholdFromPct()
        }
      })
      .catch(() => { fieldScale.value = null })
  } else {
    getSensorScale(chip, `v${form.dataType}`)
      .then(s => {
        fieldScale.value = s?.enabled && s.minValue != null && s.maxValue != null
          ? { enabled: true, min: Number(s.minValue), max: Number(s.maxValue), minColor: s.minColor, maxColor: s.maxColor }
          : null
        if (resetThresholdsPending) {
          resetThresholdsPending = false
          applyScaleDefaults()
        }
      })
      .catch(() => { fieldScale.value = null })
  }
}

/** % влажности → значения формы (в блок уходит АЦП), с клампом Low ≤ High. */
function syncThresholdFromPct() {
  if (!isThresholdPct.value || !sensorCal.value) return
  // Автокламп в пределы сохранённой шкалы: порог не может выйти за min/max градиента.
  if (lowPct.value < thresholdSliderMin.value) lowPct.value = thresholdSliderMin.value
  if (lowPct.value > thresholdSliderMax.value) lowPct.value = thresholdSliderMax.value
  if (highPct.value < thresholdSliderMin.value) highPct.value = thresholdSliderMin.value
  if (highPct.value > thresholdSliderMax.value) highPct.value = thresholdSliderMax.value
  if (lowPct.value > highPct.value) highPct.value = lowPct.value
  // Обратный пересчёт влажности: Нижний % → limitHigh, Верхний % → limitLow.
  form.limitLow = percentToAdc(highPct.value, sensorCal.value) ?? form.limitLow
  form.limitHigh = percentToAdc(lowPct.value, sensorCal.value) ?? form.limitHigh
}

/** Значения формы → % влажности (после загрузки/смены датчика/величины). */
function syncThresholdPct() {
  if (!isThresholdPct.value) return
  // Обратный пересчёт влажности: limitHigh → Нижний %, limitLow → Верхний %.
  lowPct.value = adcToPercent(form.limitHigh, sensorCal.value) ?? 0
  highPct.value = adcToPercent(form.limitLow, sensorCal.value) ?? 0
  // Легаси-значения блока могли быть сохранены старым (прямым) маппингом:
  // limitLow > limitHigh. После конвертации это lowPct > highPct — меняем
  // местами и пересчитываем форму, чтобы валидация не блокировала сохранение.
  if (form.limitLow > form.limitHigh) {
    const t = lowPct.value
    lowPct.value = highPct.value
    highPct.value = t
  }
  // Кламп в пределы шкалы градиента (пороги из блока могли выйти за min/max).
  syncThresholdFromPct()
}

function clampLow() {
  if (form.limitLow < thresholdSliderMin.value) form.limitLow = thresholdSliderMin.value
  if (form.limitLow > thresholdSliderMax.value) form.limitLow = thresholdSliderMax.value
  if (form.limitLow > form.limitHigh) form.limitHigh = form.limitLow
}

function clampHigh() {
  if (form.limitHigh > thresholdSliderMax.value) form.limitHigh = thresholdSliderMax.value
  if (form.limitHigh < thresholdSliderMin.value) form.limitHigh = thresholdSliderMin.value
  if (form.limitHigh < form.limitLow) form.limitLow = form.limitHigh
}

function clampPctLow() {
  if (lowPct.value > highPct.value) highPct.value = lowPct.value
  syncThresholdFromPct()
}

function clampPctHigh() {
  if (highPct.value < lowPct.value) lowPct.value = highPct.value
  syncThresholdFromPct()
}

watch(lowPct, () => { if (isThresholdPct.value) syncThresholdFromPct() })
watch(highPct, () => { if (isThresholdPct.value) syncThresholdFromPct() })
watch(sensorCal, () => { syncLimitPct(); syncThresholdPct() })

function fm(v) {
  return v != null && String(v).trim() !== ''
}

const dataTypeOptions = computed(() => {
  const sensorType = sensorTypeFor(form.idSensor)
  return [0, 1, 2, 3].map(i => ({ value: i, label: getFieldName(sensorType, `v${i}`) }))
})

function dataTypeLabel() {
  return getFieldName(sensorTypeFor(form.idSensor), `v${form.dataType}`)
}

/** Единица измерения одиночного порога (S_ONE): % для влажности, иначе из настроек. */
const oneThresholdUnit = computed(() => {
  if (isOnePct.value) return '%'
  if (!form.idSensor) return ''
  return getFieldUnit(sensorTypeFor(form.idSensor), 'v0') || ''
})

/** Единица измерения выбранной величины (в %-режиме влажности — %). */
const thresholdUnit = computed(() => {
  if (isThresholdPct.value) return '%'
  if (!form.idSensor) return ''
  return getFieldUnit(sensorTypeFor(form.idSensor), `v${form.dataType}`) || ''
})

/** Порог в % для сводки; админу в скобках показываем реальное значение (АЦП). */
function thresholdSummary(pct, raw) {
  return isAdmin() ? `${pct}% (${raw})` : `${pct}%`
}

const summary = computed(() => {
  const items = [
    { l: 'Название', v: form.name || '—' },
    { l: 'Активация', v: form.activate ? 'активирован' : 'неактивирован' },
    { l: 'Тип', v: typeLabel(form.type) },
    { l: 'Длит. осн. включения', v: `${form.worktimeFirst} мин` },
  ]
  if (isTypeTime.value) {
    items.push({ l: 'Длит. доп. включения', v: fm(form.worktimeSecond) ? `${form.worktimeSecond} мин` : 'не задано' })
    items.push({ l: 'Интервал между включениями', v: `${form.betweentime} ч` })
    items.push({ l: 'Осн. время включения', v: form.turnOnTimeFirst })
    items.push({ l: 'Доп. время включения', v: fm(form.turnOnTimeSecond) ? form.turnOnTimeSecond : 'не задано' })
  } else if (isTypeOne.value) {
    items.push({ l: 'Датчик', v: sensorLabelById(form.idSensor) })
    items.push({ l: oneThresholdUnit.value ? `Порог включения, ${oneThresholdUnit.value}` : 'Порог включения',
      v: isOnePct.value
        ? thresholdSummary(limitPct.value, pctAdcPreview.value ?? form.limitSingle)
        : `${form.limitSingle}${oneThresholdUnit.value ? ' ' + oneThresholdUnit.value : ''}` })
    items.push({ l: 'Начало запретного интервала', v: fm(form.startTime) ? form.startTime : 'не задано' })
    items.push({ l: 'Конец запретного интервала', v: fm(form.finishTime) ? form.finishTime : 'не задано' })
  } else if (isTypeThreshold.value) {
    items.push({ l: 'Таймер выключения', v: form.useTimer ? 'вкл' : 'выкл' })
    items.push({ l: 'Датчик', v: sensorLabelById(form.idSensor) })
    if (form.idSensor) items.push({ l: 'Измеряемая величина', v: dataTypeLabel() })
    const u = thresholdUnit.value ? `, ${thresholdUnit.value}` : ''
    const uv = thresholdUnit.value ? ` ${thresholdUnit.value}` : ''
    if (isThresholdPct.value) {
      items.push({ l: `Верхний порог${u}`, v: thresholdSummary(lowPct.value, form.limitHigh) })
      items.push({ l: `Нижний порог${u}`, v: thresholdSummary(highPct.value, form.limitLow) })
    } else {
      items.push({ l: `Нижний порог${u}`, v: `${form.limitLow}${uv}` })
      items.push({ l: `Верхний порог${u}`, v: `${form.limitHigh}${uv}` })
    }
  }
  return items
})

const formValid = computed(() => {
  if (!(form.worktimeFirst >= 1 && form.worktimeFirst <= 60)) return false
  if (isTypeTime.value) {
    if (fm(form.worktimeSecond) && !(form.worktimeSecond >= 0 && form.worktimeSecond <= 60)) return false
    if (!(form.betweentime >= 1 && form.betweentime <= 168)) return false
  }
  if (isTypeOne.value) {
    if (isOnePct.value) {
      if (!(limitPct.value >= thresholdMinPct.value && limitPct.value <= thresholdMaxPct.value)) return false
    } else if (!(form.limitSingle >= 0 && form.limitSingle <= 65535)) return false
  }
  if (isTypeThreshold.value) {
    if (form.idSensor) {
      if (!(form.dataType >= 0 && form.dataType <= 3)) return false
    }
    if (!(form.limitLow >= intMin && form.limitLow <= intMax)) return false
    if (!(form.limitHigh >= intMin && form.limitHigh <= intMax)) return false
    if (form.limitLow > form.limitHigh) return false
    if (isThresholdPct.value) {
      if (!(lowPct.value >= thresholdSliderMin.value && lowPct.value <= thresholdSliderMax.value)) return false
      if (!(highPct.value >= thresholdSliderMin.value && highPct.value <= thresholdSliderMax.value)) return false
    }
  }
  return true
})

function syncDataType() {
  if (form.dataType > dataTypeOptions.value.length) form.dataType = 0
}

// Выбор датчика/величины/типа в канале меняет калибровку и шкалу-градиент.
watch(() => form.idSensor, maybeLoadSensorCalibration)
let prevType = null
watch(() => [form.idSensor, form.dataType, form.type], () => {
  // Смена измеряемой величины или переключение типа канала на пороговый —
  // прежние пороги могут не подходить новой шкале, сбрасываем на min/max.
  if (isTypeThreshold.value && (
    (prevDataType !== null && prevDataType !== form.dataType) ||
    (prevType !== null && prevType !== form.type)
  )) {
    resetThresholdsPending = true
  }
  prevDataType = form.dataType
  prevType = form.type
  maybeLoadFieldScale()
  if (!resetThresholdsPending) syncThresholdPct()
})

const thresholdInvalid = computed(() =>
  isTypeThreshold.value && typeof form.limitLow === 'number'
    && typeof form.limitHigh === 'number' && form.limitLow > form.limitHigh
)

function toggleActivate() {
  if (!isOnline.value) return
  form.activate = !form.activate
}

function clearField(key) {
  form[key] = ''
}

function timeToMinutes(v) {
  if (!fm(v)) return UNSET_TIME_MINUTES
  const [h, m] = String(v).split(':').map(Number)
  if (!Number.isFinite(h) || !Number.isFinite(m)) return UNSET_TIME_MINUTES
  return h * 60 + m
}

function buildRequest() {
  const isT = form.type === 0
  const isOne = form.type === 1
  const isTh = form.type === 2 || form.type === 3
  return {
    name: form.name,
    activate: form.activate ? 1 : 0,
    type: form.type,
    useTimer: isTh ? (form.useTimer ? 1 : 0) : null,
    dataType: (isTh && form.idSensor) ? form.dataType : null,
    turnOnTimeFirst: isT ? timeToMinutes(form.turnOnTimeFirst) : null,
    worktimeFirst: form.worktimeFirst,
    turnOnTimeSecond: isT ? timeToMinutes(form.turnOnTimeSecond) : null,
    worktimeSecond: isT ? (fm(form.worktimeSecond) ? form.worktimeSecond : 0) : null,
    betweentime: isT ? form.betweentime : null,
    idSensor: (isOne || isTh) ? (form.idSensor || 0) : null,
    startTime: isOne ? timeToMinutes(form.startTime) : null,
    finishTime: isOne ? timeToMinutes(form.finishTime) : null,
    limitSingle: isOne ? (isOnePct.value && pctAdcPreview.value != null ? pctAdcPreview.value : form.limitSingle) : null,
    limitLow: isTh ? (isThresholdPct.value && thresholdAdcLow.value != null && thresholdAdcLow.value !== '—'
      ? thresholdAdcLow.value : form.limitLow) : null,
    limitHigh: isTh ? (isThresholdPct.value && thresholdAdcHigh.value != null && thresholdAdcHigh.value !== '—'
      ? thresholdAdcHigh.value : form.limitHigh) : null,
  }
}

async function save() {
  // Роль «только чтение» на этом блоке; страница уже недоступна через роутер,
  // проверка здесь — страховка от прямого вызова.
  if (!canControlDevice(chipId)) return
  if ((isTypeOne.value || isTypeThreshold.value) && !form.idSensor) {
    pushNotification('Необходимо выбрать датчик', 'error')
    return
  }
  if (!formValid.value) {
    pushNotification(isTypeThreshold.value && form.limitLow > form.limitHigh
      ? 'Нижний порог не должен превышать верхний'
      : 'Проверьте введённые значения: часть полей вне допустимых пределов', 'error')
    return
  }
  // Влажность + два порога + без таймера — предупреждение об инерционности.
  if (isTypeThreshold.value && form.dataType === 0 && !form.useTimer) {
    timerDialog.value = true
    return
  }
  await doSend()
}

async function doSend() {
  const name = form.name || relayTitle.value
  if (!window.confirm(`Отправить настройки канала ${name} (R${relayNumber}) на блок?\n\nНастройки будут переданы по MQTT и применятся к каналу.`)) {
    return
  }
  if (relay.value?.id == null) {
    pushNotification('Канал не найден — нельзя отправить настройки', 'error')
    return
  }
  sending.value = true
  try {
    const previousSignature = relay.value ? relayParamSignature(relay.value) : null
    await sendRelayParams(relay.value.id, buildRequest())
    pushNotification(`Настройки канала R${relayNumber} отправлены на блок`, 'success')
    // Блок подтверждает настройки асинхронно (STATE_R приходит после применения).
    // Не перечитываем канал сразу: иначе форма и «Текущие настройки» откатятся
    // к старым значениям из БД до прихода подтверждения.
    const confirmed = await waitRelayUpdate(previousSignature)
    if (confirmed) {
      relay.value = confirmed
      applyFromRelay(confirmed)
    }
  } catch (e) {
    pushNotification(errorMessage(e, 'Не удалось отправить настройки'), 'error')
  } finally {
    sending.value = false
  }
}

/**
 * Отпечаток настроек канала без служебных полей. EVENT_R (сработка канала)
 * тоже обновляет updatedAt, но не трогает параметры — по одному updatedAt
 * нельзя отличить его от подтверждения STATE_R, иначе форма откатывалась
 * к старым настройкам (порог «возвращался» к прежнему значению).
 */
function relayParamSignature(r) {
  if (!r) return ''
  return JSON.stringify([
    r.name, r.activity, r.typeEnum, r.timerEnabled, r.dataType,
    r.mainTime, r.durationMain, r.extraTime, r.durationExtra,
    r.intervalH, r.sensorId, r.startTime, r.finishTime,
    r.oneLimit, r.lowLimit, r.highLimit,
  ])
}

/**
 * Ждём, пока блок пришлёт STATE_R с новыми настройками (изменится отпечаток).
 * Если подтверждение не пришло (тихий канал, блок пришлёт его позже), оставляем
 * в форме то, что отправили.
 */
async function waitRelayUpdate(previousSignature, attempts = 6, delay = 400) {
  if (previousSignature == null) return null
  for (let i = 0; i < attempts; i++) {
    await new Promise(resolve => setTimeout(resolve, delay))
    try {
      const relays = await getDeviceRelays(chipId)
      const next = relays.find(r => Number(r.relayNumber) === relayNumber)
      if (next && relayParamSignature(next) !== previousSignature) return next
    } catch (e) {
      // следующий опрос может пройти успешно
    }
  }
  return null
}

function errorMessage(e, fallback) {
  const status = e?.response?.status
  if (status === 401) return 'Сессия истекла — войдите заново'
  if (status === 403) return 'Недостаточно прав'
  if (status === 503) return 'MQTT-брокер недоступен — настройки не отправлены'
  return e?.response?.data?.message || fallback
}

onMounted(async () => {
  try {
    const [dev, relays, statuses, devSensors, reg] = await Promise.all([
      getDevice(chipId),
      getDeviceRelays(chipId),
      getDeviceStatuses(),
      getDeviceSensors(chipId),
      getDeviceRegisteredSensors(chipId),
    ])
    device.value = dev
    relay.value = relays.find(r => Number(r.relayNumber) === relayNumber) || null
    sensors.value = devSensors
    registered.value = reg
    const map = {}
    for (const s of statuses) map[s.chipId] = s
    statusMap.value = map
    if (relay.value) applyFromRelay(relay.value)
    maybeLoadSensorCalibration()
    maybeLoadFieldScale()
    syncThresholdPct()
  } catch {
    pushNotification('Не удалось загрузить настройки канала', 'error')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.relay-settings { max-width: 600px; margin: 0 auto; padding: 1rem; }
.header-row { display: flex; justify-content: space-between; align-items: center; gap: 0.5rem; position: sticky; top: 0; z-index: 50; margin: -1rem -1rem 1.5rem; padding: 1rem; background: var(--bg); flex-wrap: wrap; }
h2 { color: var(--text-strong); flex: 1; min-width: 0; }
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.small { padding: 0.25rem 0.5rem; font-size: 0.7rem; }
.btn.secondary { background: var(--bg-accent); }
.btn:disabled { opacity: 0.45; cursor: not-allowed; }
.status-tag { font-size: 0.72rem; padding: 0.15rem 0.6rem; border-radius: 999px; white-space: nowrap; }
.status-tag.online { background: var(--tag-bg-ok); color: var(--success); }
.status-tag.offline { background: var(--bg-accent); color: var(--danger); }
.save-icon-btn { display: inline-flex; align-items: center; justify-content: center; flex: none; }
.save-icon-btn svg { width: 1.5rem; height: 1.5rem; fill: currentColor; }
.save-icon-btn:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.loading, .empty { color: var(--text-dim); text-align: center; padding: 2rem; }
.empty.small { padding: 1rem; font-size: 0.85rem; }
.offline-banner { background: var(--bg-accent); color: var(--on-accent); border: 1px solid #8a2a2a; border-radius: 10px; padding: 0.7rem 1rem; font-size: 0.85rem; margin-bottom: 1.5rem; }
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
.field input[type="text"], .field input[type="number"], .field input[type="time"], .field select { background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.95rem; padding: 0.45rem 0.6rem; }
.field input.invalid { border-color: var(--danger); }
.field-error { color: var(--danger); font-size: 0.72rem; }
.field select { cursor: pointer; }
.field-row { display: flex; align-items: center; gap: 0.4rem; }
.field-unit { color: var(--text-faint); font-size: 0.8rem; }
.btn-clear { background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text-dim); cursor: pointer; width: 2rem; height: 2rem; font-size: 0.85rem; flex-shrink: 0; }
.btn-clear:hover { color: var(--danger); border-color: var(--danger); }
.scale-slider { display: flex; flex-direction: column; gap: 0.25rem; }
.scale-slider input[type="range"] { width: 100%; height: 20px; margin: 0; padding: 0; border: 1px solid var(--border-soft); border-radius: 6px; background: #333; cursor: pointer; -webkit-appearance: none; appearance: none; }
.scale-slider input[type="range"]::-webkit-slider-runnable-track { height: 18px; background: transparent; border-radius: 5px; }
.scale-slider input[type="range"]::-webkit-slider-thumb { -webkit-appearance: none; appearance: none; width: 16px; height: 16px; margin-top: 1px; border-radius: 50%; background: var(--text); border: 2px solid #222; cursor: pointer; }
.scale-slider input[type="range"]::-moz-range-track { height: 18px; background: transparent; border: none; border-radius: 5px; }
.scale-slider input[type="range"]::-moz-range-thumb { width: 16px; height: 16px; border-radius: 50%; background: var(--text); border: 2px solid #222; cursor: pointer; }
.scale-labels { display: flex; justify-content: space-between; color: var(--text-faint); font-size: 0.72rem; }
.threshold-inputs { display: flex; gap: 0.8rem; }
.threshold-input { display: flex; flex-direction: column; gap: 0.2rem; flex: 1; }
.threshold-input span { color: var(--text-faint); font-size: 0.72rem; }
.threshold-input input { width: 100%; box-sizing: border-box; }
.dual-slider { position: relative; height: 20px; margin-top: 0.4rem; border: 1px solid var(--border-soft); border-radius: 6px; background: #333; }
.dual-slider input[type="range"] {
  position: absolute; left: 0; top: 0; width: 100%; height: 100%; margin: 0; padding: 0;
  appearance: none; -webkit-appearance: none; background: transparent; pointer-events: none; z-index: 2;
}
.dual-slider input[type="range"]:nth-of-type(2) { z-index: 3; }
.dual-slider input[type="range"]::-webkit-slider-thumb {
  -webkit-appearance: none; appearance: none; pointer-events: auto;
  width: 1rem; height: 1rem; border-radius: 50%; background: var(--text);
  border: 2px solid #222; cursor: pointer;
}
.dual-slider input[type="range"]::-moz-range-thumb {
  width: 0.85rem; height: 0.85rem; border-radius: 50%; background: var(--text);
  border: 2px solid #222; cursor: pointer; pointer-events: auto;
}
.btn-clear:disabled { opacity: 0.4; cursor: not-allowed; }
.field input[type="time"] { min-width: 8rem; }
.toggle { width: 3rem; height: 1.5rem; border-radius: 999px; background: var(--bg); border: 1px solid var(--border-soft); cursor: pointer; position: relative; transition: background 0.2s; }
.toggle.on { background: var(--tag-bg-ok); border-color: var(--success); }
.toggle-dot { position: absolute; top: 2px; left: 2px; width: calc(1.5rem - 6px); height: calc(1.5rem - 6px); border-radius: 50%; background: var(--text-dim); transition: left 0.2s, background 0.2s; }
.toggle.on .toggle-dot { left: calc(3rem - 1.5rem + 2px); background: var(--success); }
.checkbox { display: flex; align-items: center; gap: 0.5rem; cursor: pointer; color: var(--text); font-size: 0.9rem; }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; z-index: 100; padding: 1rem; }
.modal { background: var(--bg-card); border-radius: 12px; padding: 1.25rem; max-width: 420px; width: 100%; box-shadow: 0 8px 30px rgba(0,0,0,0.5); }
.modal h3 { color: var(--text-strong); margin: 0 0 0.5rem; }
.modal-sub { color: var(--text-dim); font-size: 0.85rem; margin: 0 0 0.5rem; line-height: 1.4; }
.modal-actions { display: flex; justify-content: flex-end; gap: 0.5rem; margin-top: 1rem; }

/* Телефон: поля в ряд не влезают — переносим; кнопка сброса крупнее. */
@media (hover: none) {
  .field-row { flex-wrap: wrap; row-gap: 0.4rem; }
  .btn-clear { width: 2.4rem; height: 2.4rem; }
  .setting-row { flex-wrap: wrap; row-gap: 0.2rem; }
}
</style>
