<template>
  <div class="device-name-field">
    <span class="field-label">Название</span>
    <div ref="editor" class="name-row" @keydown.esc.prevent="cancel">
      <input
        ref="input"
        v-model="draft"
        type="text"
        :maxlength="nameLimit"
        :size="nameLimit"
        aria-label="Название"
        placeholder="Введите название"
        :disabled="!editing"
        @keydown.enter.prevent
      />
      <button
        type="button"
        class="btn small name-action"
        :class="{ 'name-save': editing }"
        :aria-label="actionLabel"
        :title="actionLabel"
        :disabled="disabled"
        @click="toggle"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path v-if="editing" d="M9 16.17 4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z" />
          <path v-else d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75z" />
        </svg>
      </button>
    </div>
    <span class="field-hint">до {{ nameLimit }} символов</span>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

const props = defineProps({
  name: { type: String, required: true },
  disabled: Boolean,
})
const emit = defineEmits(['save'])
const nameLimit = 32
const editor = ref(null)
const input = ref(null)
const editing = ref(false)
const draft = ref(props.name)
const actionLabel = computed(() => editing.value ? 'Сохранить название' : 'Редактировать название')

watch(() => props.name, (name) => {
  if (!editing.value) draft.value = name
})
watch(() => props.disabled, (disabled) => {
  if (disabled) cancel()
})

async function toggle() {
  if (editing.value) {
    const name = draft.value.trim()
    cancel()
    if (name !== props.name) emit('save', name)
    return
  }
  draft.value = props.name
  editing.value = true
  await nextTick()
  input.value?.focus()
}

function cancel() {
  editing.value = false
  draft.value = props.name
}

function onOutsideClick(event) {
  if (editing.value && !event.composedPath().includes(editor.value)) cancel()
}

onMounted(() => document.addEventListener('click', onOutsideClick, true))
onUnmounted(() => document.removeEventListener('click', onOutsideClick, true))
</script>

<style scoped>
.device-name-field { display: flex; flex-direction: column; gap: 0.4rem; max-width: 360px; margin-bottom: 1rem; }
.field-label { color: var(--text-muted); font-size: 0.8rem; }
.field-hint { color: var(--text-faint); font-size: 0.72rem; }
.name-row { display: flex; gap: 0.4rem; }
input { flex: 1; min-width: 0; background: var(--bg); border: 1px solid var(--border-soft); border-radius: 6px; color: var(--text); font-size: 0.95rem; padding: 0.45rem 0.6rem; }
input:focus { outline: none; border-color: var(--accent); }
input:disabled { color: var(--text-muted); }
.name-action { display: grid; place-items: center; flex: 0 0 2.25rem; padding: 0; }
.name-action svg { width: 1.15rem; height: 1.15rem; fill: currentColor; }
.name-action.name-save { color: var(--success); }
</style>
