<template>
  <div class="field-gradient">
    <div class="header-row">
      <button @click="$router.back()" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
      <h2>{{ title }}</h2>
      <button type="button" class="btn small back-btn save-icon-btn" title="Сохранить" aria-label="Сохранить" @click="save">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/></svg>
      </button>
    </div>

    <section class="section">
      <p class="hint">
        Задайте две характерные точки: «минимум» и «максимум».
        Между ними цвет меняется по радуге через зелёный — как на экране блока.
        Для чёрного, белого или серого цвета переход будет обычным линейным.
      </p>

      <div class="form">
        <div class="row">
          <label class="field">
            <span class="label">Минимум</span>
            <span class="value-with-unit"><input type="number" step="any" v-model.number="minValue" /><span v-if="getFieldUnit(sensorType, fieldKey)" class="field-unit">{{ getFieldUnit(sensorType, fieldKey) }}</span></span>
          </label>
          <label class="field">
            <span class="label">Цвет минимума</span>
            <input type="color" v-model="minColor" />
          </label>
        </div>
        <div class="row">
          <label class="field">
            <span class="label">Максимум</span>
            <span class="value-with-unit"><input type="number" step="any" v-model.number="maxValue" /><span v-if="getFieldUnit(sensorType, fieldKey)" class="field-unit">{{ getFieldUnit(sensorType, fieldKey) }}</span></span>
          </label>
          <label class="field">
            <span class="label">Цвет максимума</span>
            <input type="color" v-model="maxColor" />
          </label>
        </div>

        <div class="preview" v-if="canShow">
          <div v-if="typeAlarm !== 0 && limit1Enabled" class="alarm-preview">
            <div class="alarm-track" :style="alarmTrackStyle"></div>
          </div>
          <div class="swatch threshold-bar" :style="{ background: gradientStyle }">
            <input v-if="limit1Enabled" class="bar-slider" type="range" :min="minValue" :max="maxValue" step="1" v-model.number="limit1" aria-label="Порог 1" />
            <input v-if="limit2Enabled" class="bar-slider second" type="range" :min="minValue" :max="maxValue" step="1" v-model.number="limit2" aria-label="Порог 2" @input="clampLimit2" />
          </div>
          <div class="labels">
            <span>{{ minValue }}<template v-if="getFieldUnit(sensorType, fieldKey)"> {{ getFieldUnit(sensorType, fieldKey) }}</template></span>
            <span>{{ maxValue }}<template v-if="getFieldUnit(sensorType, fieldKey)"> {{ getFieldUnit(sensorType, fieldKey) }}</template></span>
          </div>
        </div>
      </div>

      <section v-if="thresholdSupported && canShow" class="thresholds">
        <label class="threshold-enable">
          <input type="checkbox" v-model="limit1Enabled" />
          <span>Порог 1</span>
          <input class="threshold-number" type="number" step="1" :min="minValue" :max="maxValue" v-model.number="limit1" :disabled="!limit1Enabled" aria-label="Значение порога 1" />
          <span v-if="getFieldUnit(sensorType, fieldKey)" class="threshold-unit">{{ getFieldUnit(sensorType, fieldKey) }}</span>
        </label>
        <label class="threshold-enable">
          <input type="checkbox" v-model="limit2Enabled" :disabled="!limit1Enabled" />
          <span>Порог 2</span>
          <input class="threshold-number" type="number" step="1" :min="limit1" :max="maxValue" v-model.number="limit2" :disabled="!limit2Enabled" aria-label="Значение порога 2" />
          <span v-if="getFieldUnit(sensorType, fieldKey)" class="threshold-unit">{{ getFieldUnit(sensorType, fieldKey) }}</span>
        </label>
        <div class="alarm-types">
          <div class="label">Типы уведомлений</div>
          <label v-for="option in alarmOptions" :key="option.value" class="alarm-option" :class="{ disabled: !option.enabled }">
            <input type="radio" name="threshold-type" :value="option.value" v-model.number="typeAlarm" :disabled="!option.enabled" />
            <span>{{ option.label }}</span>
          </label>
        </div>
      </section>

      <div class="actions">
        <button class="btn secondary" @click="clear">Очистить</button>
      </div>
      <div v-if="message" class="message">{{ message }}</div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSensorScale, setSensorScale, clearSensorScale, getSensorCatalog, getSensorThreshold, saveSensorThreshold } from '../api/auth'
import { moistureGradientCss } from '../utils/gradient'
import { getFieldName, getFieldUnit } from '../store/settings'
import { pushNotification } from '../store/notifications'

const route = useRoute()
const router = useRouter()
const chipId = Number(route.params.chipId)
const deviceId = route.query.deviceId ? Number(route.query.deviceId) : null
const fieldKey = route.params.fieldKey
const nameFromQuery = route.query.name || null

const FIELD_LABELS = { v1: 'Температура', v2: 'Влажность воздуха', v3: 'Освещённость' }

const sensorType = ref(0)
const title = computed(() => {
  const local = getFieldName(sensorType.value, fieldKey)
  const fallback = FIELD_LABELS[fieldKey] || fieldKey
  return `Градиент: ${nameFromQuery || (local !== fieldKey ? local : fallback)}`
})

const minValue = ref(null)
const maxValue = ref(null)
const minColor = ref('#000000')
const maxColor = ref('#ffffff')
const message = ref('')
const limit1Enabled = ref(false)
const limit2Enabled = ref(false)
const limit1 = ref(null)
const limit2 = ref(null)
const typeAlarm = ref(0)
const thresholdSupported = computed(() => ['v1', 'v2', 'v3'].includes(fieldKey))
const alarmOptions = computed(() => [
  { value: 0, label: 'Нет уведомлений', enabled: true },
  { value: 1, label: 'Ниже порога 1', enabled: limit1Enabled.value && !limit2Enabled.value },
  { value: 2, label: 'Выше порога 1', enabled: limit1Enabled.value && !limit2Enabled.value },
  { value: 3, label: 'Вне интервала', enabled: limit2Enabled.value },
  { value: 4, label: 'Внутри интервала', enabled: limit2Enabled.value },
])
const alarmTrackStyle = computed(() => {
  const span = Number(maxValue.value) - Number(minValue.value)
  if (!Number.isFinite(span) || span <= 0) return {}
  const pct = n => Math.max(0, Math.min(100, (Number(n) - Number(minValue.value)) * 100 / span))
  const p1 = pct(limit1.value)
  if (typeAlarm.value === 1) return { background: `linear-gradient(to right, #e5484d 0 ${p1}%, transparent ${p1}% 100%)` }
  if (typeAlarm.value === 2) return { background: `linear-gradient(to right, transparent 0 ${p1}%, #e5484d ${p1}% 100%)` }
  const p2 = pct(limit2.value)
  if (typeAlarm.value === 3) return { background: `linear-gradient(to right, #e5484d 0 ${p1}%, transparent ${p1}% ${p2}%, #e5484d ${p2}% 100%)` }
  return { background: `linear-gradient(to right, transparent 0 ${p1}%, #e5484d ${p1}% ${p2}%, transparent ${p2}% 100%)` }
})
let loading = false

watch([minValue, maxValue], ([min, max], [oldMin, oldMax]) => {
  if (loading || (min === oldMin && max === oldMax)) return
  resetThresholds()
}, { flush: 'sync' })
watch(limit1Enabled, enabled => {
  if (enabled) {
    if (limit1.value == null || limit1.value === '') limit1.value = Math.round((Number(maxValue.value) - Number(minValue.value)) / 4)
  } else {
    limit2Enabled.value = false
    limit2.value = null
    typeAlarm.value = 0
  }
})
watch(limit2Enabled, enabled => {
  if (enabled) {
    if (limit2.value == null || limit2.value === '') limit2.value = Math.round(Number(limit1.value) + (Number(maxValue.value) - Number(limit1.value)) / 2)
    if (!loading) typeAlarm.value = 0
  }
}, { flush: 'sync' })
watch([limit1, limit2], ([l1, l2]) => {
  if (limit1Enabled.value && limit2Enabled.value && l1 != null && l2 != null && Number(l1) > Number(l2)) {
    limit2.value = l1
  }
})

function clampLimit2() {
  if (limit1.value != null && limit2.value != null && Number(limit2.value) < Number(limit1.value)) {
    limit2.value = Number(limit1.value)
  }
}

const canShow = computed(() => minValue.value != null && maxValue.value != null && minValue.value !== maxValue.value)

const gradientStyle = computed(() => {
  if (!canShow.value) return 'transparent'
  return moistureGradientCss(minColor.value, maxColor.value)
})

onMounted(async () => {
  getSensorCatalog().then(cat => {
    const entry = cat.find(c => Number(c.chipId) === chipId)
    sensorType.value = entry?.sensorType ?? 0
  }).catch(() => {})
  await load()
})

async function load() {
  loading = true
  try {
    const [sc, threshold] = await Promise.all([
      getSensorScale(chipId, fieldKey),
      thresholdSupported.value ? getSensorThreshold(chipId, fieldKey, deviceId).catch(() => null) : Promise.resolve(null),
    ])
    if (sc) {
      minValue.value = sc.minValue ?? 0
      maxValue.value = sc.maxValue ?? 100
      minColor.value = sc.minColor ?? '#000000'
      maxColor.value = sc.maxColor ?? '#ffffff'
    } else {
      minValue.value = 0
      maxValue.value = 100
    }
    if (threshold) {
      limit1.value = threshold.limit1
      limit2.value = threshold.limit2
      typeAlarm.value = threshold.typeAlarm ?? 0
      limit1Enabled.value = threshold.limit1 != null
      limit2Enabled.value = threshold.limit2 != null
    }
  } catch {
    minValue.value = 0
    maxValue.value = 100
  }
  loading = false
}

function resetThresholds() {
  limit1Enabled.value = false
  limit2Enabled.value = false
  limit1.value = null
  limit2.value = null
  typeAlarm.value = 0
}

async function save() {
  message.value = ''
  if (minValue.value == null || maxValue.value == null) {
    message.value = 'Укажите обе точки'
    return
  }
  if (minValue.value === maxValue.value) {
    message.value = 'Точки не должны совпадать'
    return
  }
  if (Number(minValue.value) > Number(maxValue.value)) {
    message.value = 'Минимум должен быть меньше максимума'
    return
  }
  if (thresholdSupported.value && limit1Enabled.value && (limit1.value == null || limit1.value === '')) {
    message.value = 'Укажите значение порога 1'
    return
  }
  if (thresholdSupported.value && limit2Enabled.value && (limit2.value == null || limit2.value === '')) {
    message.value = 'Укажите значение порога 2'
    return
  }
  try {
    await setSensorScale(chipId, {
      fieldKey,
      minValue: Number(minValue.value),
      maxValue: Number(maxValue.value),
      minColor: minColor.value,
      maxColor: maxColor.value,
      enabled: true,
    })
    if (thresholdSupported.value) {
      await saveSensorThreshold(chipId, fieldKey, {
        limit1: limit1Enabled.value ? Number(limit1.value) : null,
        limit2: limit2Enabled.value ? Number(limit2.value) : null,
        typeAlarm: typeAlarm.value,
      }, deviceId)
    }
    pushNotification('Градиент сохранён', 'success')
    router.back()
  } catch (e) {
    message.value = formatApiError(e, 'Не удалось сохранить настройки')
    pushNotification(message.value, 'error')
  }
}

async function clear() {
  try {
    await clearSensorScale(chipId, fieldKey)
    if (thresholdSupported.value) await saveSensorThreshold(chipId, fieldKey, { limit1: null, limit2: null, typeAlarm: 0 }, deviceId)
    minValue.value = null
    maxValue.value = null
    resetThresholds()
    pushNotification('Градиент очищен', 'success')
  } catch (e) {
    pushNotification(formatApiError(e, 'Не удалось очистить градиент'), 'error')
  }
}

/** Показывает статус и текст ответа сервера — иначе причину не отличить от обрыва сети. */
function formatApiError(e, fallback) {
  if (e?.response) {
    const msg = e.response.data?.message || e.response.data?.error || ''
    return `${fallback}: HTTP ${e.response.status}${msg ? ` — ${msg}` : ''}`
  }
  return `${fallback} — нет ответа от сервера`
}
</script>

<style scoped>
.field-gradient { max-width: 600px; margin: 0 auto; padding: 1rem; }
.header-row { display: flex; align-items: center; gap: 0.75rem; position: sticky; top: 0; z-index: 50; margin: -1rem -1rem 1.5rem; padding: 1rem; background: var(--bg); }
.header-row h2 { color: var(--text-strong); flex: 1; }
.section { background: var(--bg); border-radius: 10px; padding: 1rem; }
.hint { color: var(--text-muted); font-size: 0.9rem; margin: 0 0 1rem; }
.form { display: flex; flex-direction: column; gap: 1rem; }
.row { display: flex; gap: 1rem; flex-wrap: wrap; }
.field { display: flex; flex-direction: column; gap: 0.4rem; flex: 1; min-width: 140px; }
.label { color: var(--text-muted); font-size: 0.8rem; }
.value-with-unit { display: flex; align-items: center; gap: 0.4rem; }
.value-with-unit input { min-width: 0; width: 100%; }
.field-unit { flex: none; color: var(--text-muted); font-size: 0.82rem; }
.field input[type="number"] { background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); padding: 0.4rem 0.6rem; font-size: 0.9rem; }
.preview { display: flex; flex-direction: column; gap: 0.4rem; }
.swatch { width: 100%; height: 20px; border-radius: 6px; border: 1px solid var(--border-soft); }
.labels { display: flex; justify-content: space-between; color: var(--text-muted); font-size: 0.8rem; }
.save-icon-btn { display: inline-flex; align-items: center; justify-content: center; flex: none; }
.save-icon-btn svg { width: 1.5rem; height: 1.5rem; fill: var(--success); }
.save-icon-btn:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.actions { display: flex; justify-content: center; }
.thresholds { display: flex; flex-direction: column; gap: 0.65rem; padding: 0.2rem 0 0.4rem; }
.threshold-enable { display: flex; align-items: center; gap: 0.55rem; color: var(--text); font-size: 0.9rem; }
.threshold-enable input[type="checkbox"], .alarm-option input { accent-color: var(--accent); }
.threshold-number { width: 9rem; margin-left: auto; background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); padding: 0.4rem 0.6rem; }
.threshold-unit { color: var(--text-muted); font-size: 0.82rem; }
.threshold-bar { position: relative; overflow: visible; }
.bar-slider { position: absolute; inset: 0; width: 100%; height: 100%; margin: 0; padding: 0; appearance: none; -webkit-appearance: none; background: transparent; pointer-events: none; }
.bar-slider.second { z-index: 2; }
.bar-slider::-webkit-slider-runnable-track { height: 18px; background: transparent; }
.bar-slider::-webkit-slider-thumb { width: 14px; height: 30px; margin-top: -5px; border: 2px solid white; border-radius: 4px; background: #808080; box-shadow: 0 0 2px #111; appearance: none; -webkit-appearance: none; pointer-events: auto; cursor: ew-resize; }
.bar-slider::-moz-range-track { height: 18px; background: transparent; }
.bar-slider::-moz-range-thumb { width: 10px; height: 24px; border: 2px solid white; border-radius: 4px; background: #808080; box-shadow: 0 0 2px #111; pointer-events: auto; cursor: ew-resize; }
.alarm-types { display: flex; flex-direction: column; gap: 0.4rem; }
.alarm-types .label { margin-bottom: 0.15rem; }
.alarm-option { display: flex; align-items: center; gap: 0.55rem; padding: 0.45rem 0.6rem; border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.86rem; }
.alarm-option.disabled { opacity: 0.45; }
.alarm-preview { display: flex; flex-direction: column; gap: 0.3rem; margin-bottom: 0.25rem; }
.alarm-track { height: 3px; border-radius: 3px; background-color: var(--divider); }
.message { color: var(--warn); font-size: 0.85rem; }
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.secondary { background: var(--bg-accent); }
.btn:disabled { opacity: 0.5; cursor: default; }
</style>
