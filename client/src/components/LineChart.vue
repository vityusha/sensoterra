<template>
  <div class="chart-wrap">
    <canvas ref="canvas"></canvas>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { Chart, LineController, LineElement, PointElement, LinearScale, TimeScale, CategoryScale, Filler, Tooltip, Legend } from 'chart.js'
import { fmtDateTime, DEFAULT_ZONE_OFFSET_MIN } from '../utils/datetime'
import { settings, chartTooltipsEnabled } from '../store/settings'

function cssVar(name, fallback) {
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim()
  return v || fallback
}

// Плагин: бледно-серый фон на интервалах простоя сервиса.
const downtimePlugin = {
  id: 'serverDowntime',
  beforeDatasetsDraw(chart) {
    const list = chart.config.options.plugins?.downtime?.downtimes || []
    if (!list.length) return
    const { ctx, chartArea } = chart
    const xScale = chart.scales.x
    if (!ctx || !chartArea || !xScale) return
    ctx.save()
    ctx.fillStyle = 'rgba(140,140,140,0.24)'
    for (const it of list) {
      const f = new Date(it.from).getTime()
      const t = new Date(it.to).getTime()
      if (isNaN(f) || isNaN(t)) continue
      const left = Math.max(chartArea.left, Math.min(xScale.getPixelForValue(f), xScale.getPixelForValue(t)))
      const right = Math.min(chartArea.right, Math.max(xScale.getPixelForValue(f), xScale.getPixelForValue(t)))
      if (right <= left) continue
      ctx.fillRect(left, chartArea.top, right - left, chartArea.bottom - chartArea.top)
    }
    ctx.restore()
  },
}

// Плагин: тёмно-красный фон в интервалах, где устройство было офлайн (отличие от серого простоя сервиса).
const offlinePlugin = {
  id: 'offlineBackground',
  beforeDatasetsDraw(chart) {
    const list = chart.config.options.plugins?.offline?.intervals || []
    if (!list.length) return
    const { ctx, chartArea } = chart
    const xScale = chart.scales.x
    if (!ctx || !chartArea || !xScale) return
    ctx.save()
    ctx.fillStyle = 'rgba(160,62,62,0.20)'
    for (const it of list) {
      const f = new Date(it.from).getTime()
      const t = new Date(it.to).getTime()
      if (isNaN(f) || isNaN(t)) continue
      const left = Math.max(chartArea.left, Math.min(xScale.getPixelForValue(f), xScale.getPixelForValue(t)))
      const right = Math.min(chartArea.right, Math.max(xScale.getPixelForValue(f), xScale.getPixelForValue(t)))
      if (right <= left) continue
      ctx.fillRect(left, chartArea.top, right - left, chartArea.bottom - chartArea.top)
    }
    ctx.restore()
  },
}

Chart.register(LineController, LineElement, PointElement, LinearScale, TimeScale, CategoryScale, Filler, Tooltip, Legend, downtimePlugin, offlinePlugin)

const props = defineProps({
  labels: { type: Array, default: () => [] },
  datasets: { type: Array, default: () => [] },
  unit: { type: String, default: '' },
  zoneOffset: { type: Number, default: DEFAULT_ZONE_OFFSET_MIN },
  downtimes: { type: Array, default: () => [] },
  offline: { type: Array, default: () => [] },
  downtimeMinMs: { type: Number, default: 10 * 60 * 1000 },
  maxGapMs: { type: Number, default: 15 * 60 * 1000 },
  yMin: { type: Number, default: null },
  yMax: { type: Number, default: null },
  yStep: { type: Number, default: null },
  yTickMap: { type: Object, default: () => null },
  xMin: { type: Number, default: null },
  xMax: { type: Number, default: null },
  // Непрерывный ряд (статус): линия держит последнее значение между записями,
  // но разрывается строго по границам серой зоны простоя (нет данных — нет уверенности).
  continuous: { type: Boolean, default: false },
})

const canvas = ref(null)
let chart = null

// Интервалы для фона и разрывов линии — единый список (должны быть согласованы).
function mergeIntervals(list) {
  const arr = list.filter(i => i.to > i.from).sort((a, b) => a.from - b.from)
  const out = []
  for (const i of arr) {
    const last = out[out.length - 1]
    if (last && i.from <= last.to) last.to = Math.max(last.to, i.to)
    else out.push({ from: i.from, to: i.to })
  }
  return out
}

const series = computed(() => {
  // Точки, попавшие внутрь простоя сервиса, отбрасываются (сервер не принимал данные).
  const datasets = props.datasets.map(d => {
    const pts = (d.data || []).map((y, i) => {
      const t = props.labels?.[i]
      const x = t ? new Date(t).getTime() : null
      return { x, y }
    }).filter(pt => {
      if (pt.x == null || isNaN(pt.x)) return false
      for (const dn of downMs.value) {
        if (pt.x > dn.from && pt.x < dn.to) return false
      }
      return true
    })
    return { ...d, pts }
  })

  // Единый набор интервалов разрыва — только простой сервиса (одинаков для всех графиков).
  const breakInts = mergeIntervals(downMs.value)
  bgBreaks.value = breakInts.map(b => ({ from: new Date(b.from).toISOString(), to: new Date(b.to).toISOString() }))
  bgBreaksMs.value = breakInts

  // Вставляем null на середину каждого интервала разрыва (ломаем линию).
  // В режиме continuous (статус) разрыв идёт строго по границам серой зоны:
  // линия держит последнее значение до её начала и возобновляется от её конца.
  return datasets.map(ds => {
    const p = ds.pts
    const minX = p.length ? p[0].x : Infinity
    const maxX = p.length ? p[p.length - 1].x : -Infinity
    const markers = []
    for (const b of breakInts) {
      const m = (b.from + b.to) / 2
      if (props.continuous) {
        if (b.from > minX && b.from < maxX) markers.push({ x: b.from, kind: 'start' })
        if (b.to > minX && b.to < maxX) markers.push({ x: b.to, kind: 'end' })
        if (m > minX && m < maxX) markers.push({ x: m, kind: 'gap' })
      } else if (m > minX && m < maxX) {
        markers.push({ x: m, kind: 'gap' })
      }
    }
    markers.sort((a, b) => a.x - b.x)
    const broken = []
    let mi = 0
    let lastY = null
    const push = pt => { broken.push(pt); if (pt.y != null) lastY = pt.y }
    for (let i = 0; i < p.length; i++) {
      while (mi < markers.length && markers[mi].x < p[i].x) {
        const mk = markers[mi++]
        if (mk.kind === 'gap') push({ x: mk.x, y: null })
        else if (mk.kind === 'start') push({ x: mk.x, y: lastY })
        else push({ x: mk.x, y: p[i].y }) // end: возобновляем значением следующей точки
      }
      push(p[i])
    }
    return { ...ds, data: broken }
  })
})

const bgBreaks = ref([])
const bgBreaksMs = ref([])

// Красный фон офлайна рисуем только там, где линия непрерывна: внутри разрыва линии
// фон уже серый (простой), чтобы у всех разрывов был один цвет.
const offlineClipped = computed(() => {
  const offs = (props.offline || []).map(o => ({
    from: new Date(o.from).getTime(),
    to: new Date(o.to).getTime(),
  })).filter(i => !isNaN(i.from) && !isNaN(i.to) && i.to > i.from)
  const br = bgBreaksMs.value
  if (!br.length) return offs.map(i => ({ from: new Date(i.from).toISOString(), to: new Date(i.to).toISOString() }))
  const out = []
  for (const o of offs) {
    let cur = { ...o }
    for (const b of br) {
      if (b.to <= cur.from || b.from >= cur.to) continue
      if (b.from <= cur.from && b.to >= cur.to) { cur = null; break }
      if (b.from > cur.from && b.to < cur.to) {
        out.push({ from: cur.from, to: b.from })
        cur = { from: b.to, to: cur.to }
      } else if (b.from <= cur.from) cur = { from: b.to, to: cur.to }
      else cur = { from: cur.from, to: b.from }
      if (cur.to - cur.from <= 0) { cur = null; break }
    }
    if (cur) out.push(cur)
  }
  return out.map(i => ({ from: new Date(i.from).toISOString(), to: new Date(i.to).toISOString() }))
})

// Интервалы простоя сервиса: только длинные (>= downtimeMinMs), чтобы не мелькали
// микропростои в считанные секунды.
const visibleDowns = computed(() => (props.downtimes || []).filter(d => {
  const f = new Date(d.from).getTime()
  const t = new Date(d.to).getTime()
  return !isNaN(f) && !isNaN(t) && (t - f) >= (props.downtimeMinMs || 0)
}))

const downMs = computed(() => visibleDowns.value.map(d => ({
  from: new Date(d.from).getTime(),
  to: new Date(d.to).getTime(),
})).filter(d => !isNaN(d.from) && !isNaN(d.to)))

function tickLabel(ms) {
  if (ms == null || isNaN(ms)) return ''
  return fmtDateTime(new Date(ms).toISOString(), props.zoneOffset)
}

function buildChart() {
  if (chart) chart.destroy()
  if (!canvas.value) return
  const tipOn = chartTooltipsEnabled()
  const legendColor = cssVar('--text-muted', '#aaa')
  const tickColor = cssVar('--text-dim', '#888')
  const tickColor2 = cssVar('--text-faint', '#666')
  const gridColor = cssVar('--chart-grid', '#2a2a3e')
  chart = new Chart(canvas.value, {
    type: 'line',
    data: {
      datasets: series.value.map(d => ({
        label: d.label,
        data: d.data,
        borderColor: d.color || '#3498db',
        backgroundColor: (d.color || '#3498db') + '33',
        borderWidth: 1.5,
        pointRadius: 0,
        tension: d.stepped ? 0 : 0.3,
        stepped: d.stepped || false,
        fill: d.fill || false,
      })),
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      interaction: { mode: 'index', intersect: false, enabled: tipOn },
      plugins: {
        downtime: { downtimes: bgBreaks.value },
        offline: { intervals: offlineClipped.value },
        legend: { display: series.value.length > 1, labels: { color: legendColor, font: { size: 10 } } },
        tooltip: {
          enabled: tipOn,
          callbacks: {
            title: (items) => tickLabel(items?.[0]?.parsed?.x),
          },
        },
      },
      scales: {
        x: {
          type: 'linear',
          min: props.xMin != null ? props.xMin : undefined,
          max: props.xMax != null ? props.xMax : undefined,
          display: true,
          ticks: {
            color: tickColor2,
            maxTicksLimit: 6,
            maxRotation: 0,
            font: { size: 9 },
            callback: value => tickLabel(value),
          },
          grid: { color: gridColor },
        },
        y: {
          display: true,
          min: props.yMin != null ? props.yMin : undefined,
          max: props.yMax != null ? props.yMax : undefined,
          afterFit: (scale) => { scale.width = 46 },
          ticks: {
            color: tickColor,
            font: { size: 9 },
            stepSize: props.yStep != null ? props.yStep : undefined,
            callback: (v) => {
              if (props.yTickMap) {
                return props.yTickMap[v] != null ? props.yTickMap[v] : ''
              }
              if (typeof v !== 'number' || !isFinite(v)) return v
              // Деления оси считаются из float-границ (напр. минимума 21.4) и дают
              // мусорные хвосты вида 21.400000000000002 — округляем до разумной точности.
              return String(Math.round(v * 100) / 100)
            },
          },
          grid: { color: gridColor },
          title: { display: !!props.unit, text: props.unit, color: tickColor, font: { size: 9 } },
        },
      },
    },
  })
}

onMounted(buildChart)
watch(() => [props.labels, props.datasets, props.downtimes, props.yMin, props.yMax, props.yStep,
    props.xMin, props.xMax, props.zoneOffset, props.unit, props.continuous], buildChart, { deep: true })
watch(() => settings.chartTooltips, buildChart)
onUnmounted(() => { if (chart) chart.destroy() })
</script>

<style scoped>
.chart-wrap { height: 180px; position: relative; background: var(--bg); border-radius: 8px; padding: 0.5rem; }
/* Чтобы на телефоне страница прокручивалась пальцем прямо по графику. */
.chart-wrap canvas { touch-action: pan-y; }
</style>