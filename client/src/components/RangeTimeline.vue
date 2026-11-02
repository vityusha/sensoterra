<template>
  <div class="range-timeline" @click.stop>
    <button
      type="button"
      class="shift-btn"
      :disabled="!canBack"
      aria-label="Предыдущий период"
      title="Предыдущий период"
      @click="$emit('shift', -1)"
    >
      <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M15 18l-6-6 6-6" />
      </svg>
    </button>
    <div class="track-wrap">
      <div ref="track" class="track" role="group" :aria-label="ariaLabel" @pointerdown="onPointerDown" @pointermove="onPointerMove" @pointerup="onPointerUp" @pointercancel="onPointerCancel">
        <div class="rail"></div>
        <div v-if="viewSelection" class="selection" :style="selectionStyle" :title="selectionTitle">
          <span class="selection-line" aria-hidden="true"></span>
          <span class="handle start" aria-hidden="true"></span>
          <span class="handle end" aria-hidden="true"></span>
        </div>
      </div>
      <div class="labels">
        <span>{{ fromLabel }}</span>
        <span>{{ toLabel }}</span>
      </div>
    </div>
    <button
      type="button"
      class="shift-btn"
      :disabled="!canForward"
      aria-label="Следующий период"
      title="Следующий период"
      @click="$emit('shift', 1)"
    >
      <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M9 18l6-6-6-6" />
      </svg>
    </button>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { fmtDateTime, DEFAULT_ZONE_OFFSET_MIN } from '../utils/datetime'

const props = defineProps({
  from: { type: Number, required: true },
  to: { type: Number, required: true },
  selection: { type: Object, default: null },
  zoneOffset: { type: Number, default: DEFAULT_ZONE_OFFSET_MIN },
  canBack: { type: Boolean, default: true },
  canForward: { type: Boolean, default: true },
  ariaLabel: { type: String, default: 'Период статистики' },
})

const emit = defineEmits(['shift', 'update:selection'])

const track = ref(null)
const draft = ref(null)
let drag = null

const span = computed(() => Math.max(1, props.to - props.from))
const viewSelection = computed(() => {
  const s = draft.value || props.selection
  if (!s) return null
  const from = Math.max(props.from, Math.min(props.to, s.from))
  const to = Math.max(props.from, Math.min(props.to, s.to))
  return to > from ? { from, to } : null
})
const selectionStyle = computed(() => {
  const s = viewSelection.value
  if (!s) return null
  return {
    left: `${((s.from - props.from) / span.value) * 100}%`,
    width: `${((s.to - s.from) / span.value) * 100}%`,
  }
})
const fromLabel = computed(() => fmtDateTime(new Date(props.from).toISOString(), props.zoneOffset))
const toLabel = computed(() => fmtDateTime(new Date(props.to).toISOString(), props.zoneOffset))
const selectionTitle = computed(() => {
  const s = viewSelection.value
  if (!s) return ''
  return `${fmtDateTime(new Date(s.from).toISOString(), props.zoneOffset)} — ${fmtDateTime(new Date(s.to).toISOString(), props.zoneOffset)}`
})

function trackPoint(e) {
  const rect = track.value?.getBoundingClientRect()
  if (!rect || !rect.width) return null
  const x = Math.max(0, Math.min(rect.width, e.clientX - rect.left))
  return { x, width: rect.width }
}

function onPointerDown(e) {
  const s = viewSelection.value
  if (!s || e.button !== 0 || e.isPrimary === false || !track.value) return
  const p = trackPoint(e)
  if (!p) return
  const left = ((s.from - props.from) / span.value) * p.width
  const right = ((s.to - props.from) / span.value) * p.width
  const edge = Math.max(9, p.width * 0.025)
  const mode = Math.abs(p.x - left) <= edge ? 'start'
    : Math.abs(p.x - right) <= edge ? 'end'
      : p.x > left && p.x < right ? 'move' : null
  if (!mode) return
  drag = { pointerId: e.pointerId, mode, startX: p.x, width: p.width, from: s.from, to: s.to, moved: false }
  try { track.value.setPointerCapture(e.pointerId) } catch {}
  e.preventDefault()
}

function onPointerMove(e) {
  if (!drag || e.pointerId !== drag.pointerId) return
  const p = trackPoint(e)
  if (!p) return
  const delta = ((p.x - drag.startX) / drag.width) * span.value
  const min = Math.max(60000, span.value * 0.001)
  let from = drag.from
  let to = drag.to
  if (drag.mode === 'move') {
    const shift = Math.max(props.from - drag.from, Math.min(props.to - drag.to, delta))
    from += shift
    to += shift
  } else if (drag.mode === 'start') {
    from = Math.max(props.from, Math.min(drag.to - min, drag.from + delta))
  } else {
    to = Math.min(props.to, Math.max(drag.from + min, drag.to + delta))
  }
  draft.value = { from, to }
  if (Math.abs(delta) > 0.5) drag.moved = true
  e.preventDefault()
}

function release() {
  try {
    if (drag && track.value?.hasPointerCapture?.(drag.pointerId)) {
      track.value.releasePointerCapture(drag.pointerId)
    }
  } catch {}
}

function onPointerUp(e) {
  if (!drag || e.pointerId !== drag.pointerId) return
  const moved = drag.moved
  const next = viewSelection.value
  release()
  drag = null
  draft.value = null
  if (!moved || !next) return
  const round = ms => Math.round(ms / 60000) * 60000
  emit('update:selection', { from: round(next.from), to: round(next.to) })
}

function onPointerCancel(e) {
  if (!drag || e.pointerId !== drag.pointerId) return
  release()
  drag = null
  draft.value = null
}
</script>

<style scoped>
.range-timeline { display: flex; align-items: center; gap: 0.35rem; margin: 0.55rem 0 0.15rem; padding-bottom: 0.95rem; }
.shift-btn { flex: none; width: 1.9rem; height: 2.2rem; padding: 0; border: 1px solid var(--border-soft); border-radius: 6px; display: flex; align-items: center; justify-content: center; background: var(--btn-bg); color: var(--btn-text); cursor: pointer; }
.shift-btn:hover:not(:disabled) { background: var(--btn-bg-hover); }
.shift-btn:disabled { cursor: default; opacity: 0.4; }
.track-wrap { flex: 1; min-width: 0; position: relative; }
.track { position: relative; height: 26px; touch-action: none; cursor: default; }
.rail { position: absolute; left: 0; right: 0; top: 2px; bottom: 2px; background: color-mix(in srgb, #2ecc71 12%, var(--btn-bg)); border: 1px solid var(--border-soft); border-radius: 4px; box-sizing: border-box; }
.track::before, .track::after { content: ''; position: absolute; top: 4px; bottom: 4px; width: 2px; background: var(--border-soft); border-radius: 1px; }
.track::before { left: 0; }
.track::after { right: 0; }
.selection { position: absolute; top: 3px; bottom: 3px; background: color-mix(in srgb, var(--accent) 20%, transparent); border-left: 2px solid var(--accent); border-right: 2px solid var(--accent); border-radius: 3px; cursor: grab; touch-action: none; }
.selection:active { cursor: grabbing; }
.selection-line { position: absolute; left: 0; right: 0; top: 50%; height: 3px; transform: translateY(-50%); background: var(--accent); border-radius: 2px; pointer-events: none; }
.handle { position: absolute; top: -2px; bottom: -2px; width: 8px; background: var(--accent); border-radius: 3px; cursor: ew-resize; touch-action: none; }
.handle.start { left: -4px; }
.handle.end { right: -4px; }
.labels { position: absolute; left: 0; right: 0; top: 100%; display: flex; justify-content: space-between; gap: 0.5rem; margin-top: 0.1rem; color: var(--text-faint); font-size: 0.65rem; line-height: 1.2; }
</style>
