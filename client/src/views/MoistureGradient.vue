<template>
  <div class="moisture-gradient">
    <div class="header-row">
      <button @click="$router.back()" class="btn small back-btn"><svg class="back-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg></button>
      <h2>Градиент влажности почвы</h2>
    </div>

    <section class="section">
      <p class="hint">
        Задайте две характерные точки: «минимум» (сухая, 0%) и «максимум» (увлажнённая).
        Между ними цвет меняется по радуге через зелёный — как на экране блока:
        норма зелёная, небольшие отклонения жёлтые, края красный/синий.
        Для чёрного, белого или серого цвета переход будет обычным линейным.
      </p>

      <div class="form">
        <div class="row">
          <label class="field">
            <span class="label">Минимум, %</span>
            <input type="number" min="0" max="100" step="1" v-model.number="minPct" />
          </label>
          <label class="field">
            <span class="label">Цвет минимума</span>
            <input type="color" v-model="minColor" />
          </label>
        </div>
        <div class="row">
          <label class="field">
            <span class="label">Максимум, %</span>
            <input type="number" min="0" max="100" step="1" v-model.number="maxPct" />
          </label>
          <label class="field">
            <span class="label">Цвет максимума</span>
            <input type="color" v-model="maxColor" />
          </label>
        </div>

        <div class="preview" v-if="canShow">
          <div class="swatch" :style="{ background: gradientStyle }"></div>
          <div class="labels">
            <span>{{ minPct }}%</span>
            <span>{{ maxPct }}%</span>
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
import { getMoistureScale, setMoistureScale, clearMoistureScale } from '../api/auth'
import { moistureGradientCss } from '../utils/gradient'
import { pushNotification } from '../store/notifications'

const route = useRoute()
const router = useRouter()
const chipId = Number(route.params.chipId)

const DEFAULT_MIN_COLOR = '#ff0000'
const DEFAULT_MAX_COLOR = '#0000ff'

const minPct = ref(null)
const maxPct = ref(null)
const minColor = ref(DEFAULT_MIN_COLOR)
const maxColor = ref(DEFAULT_MAX_COLOR)
const enabled = ref(false)
const message = ref('')

const canShow = computed(() => minPct.value != null && maxPct.value != null && minPct.value !== maxPct.value)

const gradientStyle = computed(() => {
  if (!canShow.value) return 'transparent'
  return moistureGradientCss(minColor.value, maxColor.value)
})

onMounted(async () => await load())

async function load() {
  try {
    const sc = await getMoistureScale(chipId)
    if (sc) {
      minPct.value = sc.minPct ?? 0
      maxPct.value = sc.maxPct ?? 100
      minColor.value = sc.minColor ?? DEFAULT_MIN_COLOR
      maxColor.value = sc.maxColor ?? DEFAULT_MAX_COLOR
      enabled.value = !!sc.enabled
    } else {
      minPct.value = 0
      maxPct.value = 100
      minColor.value = DEFAULT_MIN_COLOR
      maxColor.value = DEFAULT_MAX_COLOR
      enabled.value = false
    }
  } catch {
    minPct.value = 0
    maxPct.value = 100
    minColor.value = DEFAULT_MIN_COLOR
    maxColor.value = DEFAULT_MAX_COLOR
    enabled.value = false
  }
}

async function save() {
  message.value = ''
  if (minPct.value == null || maxPct.value == null) {
    message.value = 'Укажите обе точки'
    return
  }
  if (minPct.value < 0 || minPct.value > 100 || maxPct.value < 0 || maxPct.value > 100) {
    message.value = 'Значения должны быть в диапазоне 0-100'
    return
  }
  if (minPct.value === maxPct.value) {
    message.value = 'Точки не должны совпадать'
    return
  }
  try {
    await setMoistureScale(chipId, {
      minPct: Number(minPct.value),
      maxPct: Number(maxPct.value),
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
    await clearMoistureScale(chipId)
    minPct.value = null
    maxPct.value = null
    minColor.value = DEFAULT_MIN_COLOR
    maxColor.value = DEFAULT_MAX_COLOR
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
.moisture-gradient { max-width: 600px; margin: 0 auto; padding: 1rem; }
.header-row { display: flex; align-items: center; gap: 0.75rem; margin-bottom: 1rem; }
.header-row h2 { color: var(--text-strong); flex: 1; }
.section { background: var(--bg); border-radius: 10px; padding: 1rem; }
.hint { color: var(--text-muted); font-size: 0.9rem; margin: 0 0 1rem; }
.form { display: flex; flex-direction: column; gap: 1rem; }
.row { display: flex; gap: 1rem; flex-wrap: wrap; }
.field { display: flex; flex-direction: column; gap: 0.4rem; flex: 1; min-width: 140px; }
.label { color: var(--text-muted); font-size: 0.8rem; }
.field input[type="number"] { background: #0f0f1a; border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); padding: 0.4rem 0.6rem; font-size: 0.9rem; }
.preview { display: flex; flex-direction: column; gap: 0.4rem; }
.swatch { width: 100%; height: 24px; border-radius: 6px; border: 1px solid var(--border-soft); }
.labels { display: flex; justify-content: space-between; color: var(--text-muted); font-size: 0.8rem; }
.actions { display: flex; gap: 0.6rem; flex-wrap: wrap; }
.message { color: var(--warn); font-size: 0.85rem; }
.btn { padding: 0.4rem 0.8rem; border: none; border-radius: 6px; cursor: pointer; font-size: 0.8rem; background: var(--bg-primary); color: var(--text); }
.btn.secondary { background: var(--bg-accent); }
.btn:disabled { opacity: 0.5; cursor: default; }
</style>
