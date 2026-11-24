<template>
  <div ref="root" class="number-picker" @focusout="onFocusOut" @keydown.esc.stop="close(true)">
    <button ref="trigger" type="button" class="picker-trigger" :disabled="disabled" :aria-label="`${label}: ${format(modelValue)}`"
      aria-haspopup="listbox" :aria-expanded="open" @click="open ? close(true) : show()"
      @keydown.down.prevent="show()" @keydown.up.prevent="show()">
      {{ format(modelValue) }} <span aria-hidden="true">▾</span>
    </button>
    <div v-if="open" ref="list" class="picker-options" :class="{ above }" :style="listStyle"
      role="listbox" :aria-label="label" @keydown="moveFocus">
      <button v-for="option in options" :key="option" type="button" tabindex="-1" role="option"
        :aria-selected="option === modelValue" @click="select(option)">{{ format(option) }}</button>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Number, required: true },
  options: { type: Array, required: true },
  label: { type: String, required: true },
  disabled: Boolean,
  padded: Boolean,
  suffix: { type: String, default: '' },
  pickerWidth: { type: String, default: '96px' },
  pickerMaxHeight: { type: String, default: '50svh' }
})
const emit = defineEmits(['update:modelValue', 'change'])
const root = ref(null)
const trigger = ref(null)
const list = ref(null)
const open = ref(false)
const above = ref(false)
const availableHeight = ref(0)
const availableWidth = ref(0)
const listStyle = computed(() => ({
  width: `min(${props.pickerWidth}, ${availableWidth.value}px)`,
  maxHeight: `min(${props.pickerMaxHeight}, ${availableHeight.value}px)`
}))

function format(value) {
  return (props.padded ? String(value).padStart(2, '0') : String(value)) + props.suffix
}

async function show() {
  if (open.value || props.disabled) return
  const rect = trigger.value.getBoundingClientRect()
  const below = window.innerHeight - rect.bottom
  availableWidth.value = Math.max(0, document.documentElement.clientWidth - rect.left - 8)
  above.value = below < rect.top && below < props.options.length * 40 + 12
  availableHeight.value = Math.max(0, (above.value ? rect.top : below) - 12)
  open.value = true
  await nextTick()
  const selected = list.value?.querySelector('[aria-selected="true"]') ?? list.value?.firstElementChild
  if (selected) {
    list.value.scrollTop = selected.offsetTop - (list.value.clientHeight - selected.offsetHeight) / 2
    selected.focus({ preventScroll: true })
  }
}

function close(restoreFocus = false) {
  if (!open.value) return
  open.value = false
  if (restoreFocus) trigger.value.focus({ preventScroll: true })
}

function select(option) {
  emit('update:modelValue', option)
  emit('change', option)
  close(true)
}

function onFocusOut(event) {
  if (!root.value?.contains(event.relatedTarget)) close()
}

function moveFocus(event) {
  const item = event.target
  const target = {
    ArrowDown: item.nextElementSibling ?? list.value.firstElementChild,
    ArrowUp: item.previousElementSibling ?? list.value.lastElementChild,
    Home: list.value.firstElementChild,
    End: list.value.lastElementChild
  }[event.key]
  if (target) {
    event.preventDefault()
    target.focus()
  }
}

watch(() => props.disabled, disabled => { if (disabled) close() })
watch(open, (visible, _, onCleanup) => {
  if (!visible) return
  const outside = event => { if (!root.value?.contains(event.target)) close() }
  document.addEventListener('pointerdown', outside)
  onCleanup(() => document.removeEventListener('pointerdown', outside))
})
</script>

<style scoped>
.number-picker { position: relative; align-self: flex-start; color: var(--text); }
.picker-trigger {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.45rem 0.6rem;
  background: var(--bg);
  border: 1px solid var(--border-soft);
  border-radius: 6px;
  color: inherit;
  font: inherit;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}
.picker-trigger:disabled { opacity: 0.6; cursor: default; }
.picker-options {
  position: absolute;
  z-index: 20;
  top: calc(100% + 4px);
  left: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 4px;
  border: 2px solid var(--accent);
  border-radius: 8px;
  background: var(--bg-card);
  box-shadow: 0 4px 16px #0004;
}
.picker-options.above { top: auto; bottom: calc(100% + 4px); }
.picker-options button {
  display: block;
  width: 100%;
  min-height: 40px;
  padding: 0.25rem;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: inherit;
  font: inherit;
  white-space: nowrap;
  cursor: pointer;
}
.picker-options button[aria-selected='true'] { background: var(--bg-active); }
.picker-options button:focus-visible, .picker-trigger:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
</style>
