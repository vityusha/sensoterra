<template>
  <div v-if="pages > 1" class="paginator" role="navigation" :aria-label="label">
    <button class="page-btn" :disabled="page <= 1" aria-label="Предыдущая страница" @click="go(page - 1)">
      Назад
    </button>
    <span class="page-info" aria-live="polite">{{ page }} / {{ pages }}</span>
    <button class="page-btn" :disabled="page >= pages" aria-label="Следующая страница" @click="go(page + 1)">
      Вперёд
    </button>
  </div>
</template>

<script setup>
const props = defineProps({
  page: { type: Number, required: true },
  pages: { type: Number, required: true },
  label: { type: String, default: 'Постраничная навигация' },
})

const emit = defineEmits(['update:page'])

function go(next) {
  if (next < 1 || next > props.pages || next === props.page) return
  emit('update:page', next)
}
</script>

<style scoped>
.paginator {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 0.6rem;
  margin-top: 0.7rem;
}
.page-btn {
  padding: 0.3rem 0.65rem;
  border: 1px solid var(--border-soft);
  border-radius: 6px;
  background: var(--bg-primary);
  color: var(--text);
  font-size: 0.75rem;
  cursor: pointer;
}
.page-btn:hover:not(:disabled) {
  background: var(--bg-accent);
}
.page-btn:disabled {
  opacity: 0.45;
  cursor: default;
}
.page-info {
  min-width: 3.2rem;
  color: var(--text-dim);
  font-size: 0.75rem;
  text-align: center;
}
</style>
