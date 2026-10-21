<template>
  <div class="field-gradient">
    <div class="header-row">
      <button @click="$router.back()" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
      <h2>{{ title }}</h2>
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
            <input type="number" step="any" v-model.number="minValue" />
          </label>
          <label class="field">
            <span class="label">Цвет минимума</span>
            <input type="color" v-model="minColor" />
          </label>
        </div>
        <div class="row">
          <label class="field">
            <span class="label">Максимум</span>
            <input type="number" step="any" v-model.number="maxValue" />
          </label>
          <label class="field">
            <span class="label">Цвет максимума</span>
            <input type="color" v-model="maxColor" />
          </label>
        </div>

        <div class="preview" v-if="canShow">
          <div class="swatch" :style="{ background: gradientStyle }"></div>
          <div class="labels">
            <span>{{ minValue }}</span>
            <span>{{ maxValue }}</span>
          </div>
        </div>

        <div class="actions">
          <button class="btn save" @click="save">Сохранить</button>
          <button class="btn secondary" @click="clear">Очистить</button>
          <button class="btn secondary" @click="$router.back()">Отмена</button>
        </div>
        <div v-if="message" class="message">{{ message }}</div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getSensorScale, setSensorScale, clearSensorScale, getSensorCatalog } from '../api/auth'
import { moistureGradientCss } from '../utils/gradient'
import { getFieldName } from '../store/settings'
import { pushNotification } from '../store/notifications'

const route = useRoute()
const router = useRouter()
const chipId = Number(route.params.chipId)
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
  try {
    const sc = await getSensorScale(chipId, fieldKey)
    if (sc) {
      minValue.value = sc.minValue ?? 0
      maxValue.value = sc.maxValue ?? 100
      minColor.value = sc.minColor ?? '#000000'
      maxColor.value = sc.maxColor ?? '#ffffff'
    } else {
      minValue.value = 0
      maxValue.value = 100
    }
  } catch {
    minValue.value = 0
    maxValue.value = 100
  }
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
  try {
    await setSensorScale(chipId, {
      fieldKey,
      minValue: Number(minValue.value),
      maxValue: Number(maxValue.value),
      minColor: minColor.value,
      maxColor: maxColor.value,
      enabled: true,
    })
    pushNotification('Градиент сохранён', 'success')
    router.back()
  } catch (e) {
    pushNotification(formatApiError(e, 'Не удалось сохранить градиент'), 'error')
  }
}

async function clear() {
  try {
    await clearSensorScale(chipId, fieldKey)
    minValue.value = null
    maxValue.value = null
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
.header-row { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1rem; }
.header-row h2 { color: var(--text-strong); flex: 1; }
.section { background: var(--bg); border-radius: 10px; padding: 1rem; }
.hint { color: var(--text-muted); font-size: 0.9rem; margin: 0 0 1rem; }
.form { display: flex; flex-direction: column; gap: 1rem; }
.row { display: flex; gap: 1rem; flex-wrap: wrap; }
.field { display: flex; flex-direction: column; gap: 0.4rem; flex: 1; min-width: 140px; }
.label { color: var(--text-muted); font-size: 0.8rem; }
.field input[type="number"] { background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); padding: 0.4rem 0.6rem; font-size: 0.9rem; }
.preview { display: flex; flex-direction: column; gap: 0.4rem; }
.swatch { height: 24px; border-radius: 6px; border: 1px solid var(--border-soft); }
.labels { display: flex; justify-content: space-between; color: var(--text-muted); font-size: 0.8rem; }
.actions { display: flex; gap: 0.6rem; flex-wrap: wrap; }
.message { color: var(--warn); font-size: 0.85rem; }
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.secondary { background: var(--bg-accent); }
.btn:disabled { opacity: 0.5; cursor: default; }
</style>
