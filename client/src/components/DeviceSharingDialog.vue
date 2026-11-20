<template>
  <dialog ref="panel" class="sharing-dialog" :aria-label="title" @cancel.prevent="$emit('close')">
    <header class="dialog-header">
      <DeviceSharingIcon :kind="kind" class="dialog-icon" />
      <h2>{{ title }}</h2>
      <button type="button" class="modal-close" aria-label="Закрыть" title="Закрыть" autofocus @click="$emit('close')">✕</button>
    </header>
    <div class="dialog-content">
      <slot />
    </div>
  </dialog>
</template>

<script setup>
import { ref, watchEffect } from 'vue'
import DeviceSharingIcon from './DeviceSharingIcon.vue'

const props = defineProps({
  title: { type: String, required: true },
  kind: { type: String, required: true },
  open: Boolean
})
defineEmits(['close'])

const panel = ref(null)
watchEffect(() => {
  const dialog = panel.value
  if (!dialog) return
  if (props.open && !dialog.open) dialog.showModal()
  if (!props.open && dialog.open) dialog.close()
}, { flush: 'post' })
</script>

<style scoped>
.sharing-dialog {
  width: min(560px, calc(100% - 2rem));
  max-height: calc(100% - 2rem);
  margin: auto;
  color: var(--text);
  background: var(--bg-card);
  border: 1px solid var(--border-soft);
  border-radius: 12px;
}
.sharing-dialog::backdrop { background: rgba(0, 0, 0, 0.6); }
.dialog-header {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 1rem;
  background: var(--bg-card);
}
.dialog-header h2 { color: var(--text-strong); }
.dialog-header .modal-close { margin-left: auto; flex: none; }
.dialog-icon { width: 1.5rem; height: 1.5rem; flex: none; color: var(--text-strong); }
.dialog-content { padding: 0 1rem 1rem; }
</style>
