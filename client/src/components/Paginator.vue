<template>
  <nav v-if="pages > 1" class="paginator" :aria-label="label">
    <ul class="pagination">
      <li class="page-item" :class="{ disabled: page <= 1 }">
        <button type="button" class="page-link" :disabled="page <= 1" aria-label="Предыдущая страница" @click="go(page - 1)">
          <span aria-hidden="true">&laquo;</span>
        </button>
      </li>
      <li v-for="item in items" :key="item.key" class="page-item"
        :class="{ active: item.page === page, disabled: item.page === null }">
        <span v-if="item.page === null" class="page-link" aria-hidden="true">&hellip;</span>
        <button v-else type="button" class="page-link" :aria-current="item.page === page ? 'page' : null"
          :aria-label="`Страница ${item.page}`" @click="go(item.page)">
          {{ item.page }}
        </button>
      </li>
      <li class="page-item" :class="{ disabled: page >= pages }">
        <button type="button" class="page-link" :disabled="page >= pages" aria-label="Следующая страница" @click="go(page + 1)">
          <span aria-hidden="true">&raquo;</span>
        </button>
      </li>
    </ul>
  </nav>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  page: { type: Number, required: true },
  pages: { type: Number, required: true },
  label: { type: String, default: 'Постраничная навигация' },
})

const emit = defineEmits(['update:page'])

// First, last and the current page's neighbours; a gap of one page shows that page, not an ellipsis.
const items = computed(() => {
  const shown = [...new Set([1, props.page - 1, props.page, props.page + 1, props.pages])]
    .filter((p) => p >= 1 && p <= props.pages)
    .sort((a, b) => a - b)
  const result = []
  shown.forEach((p, i) => {
    const gap = i > 0 ? p - shown[i - 1] : 1
    if (gap === 2) result.push({ key: p - 1, page: p - 1 })
    else if (gap > 2) result.push({ key: `gap-${p}`, page: null })
    result.push({ key: p, page: p })
  })
  return result
})

function go(next) {
  if (next < 1 || next > props.pages || next === props.page) return
  emit('update:page', next)
}
</script>

<style scoped>
.paginator {
  display: flex;
  justify-content: center;
  margin-top: 0.7rem;
}
.pagination {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  margin: 0;
  padding: 0;
  list-style: none;
}
.page-item:not(:first-child) .page-link {
  margin-left: -1px;
}
.page-item:first-child .page-link {
  border-radius: 6px 0 0 6px;
}
.page-item:last-child .page-link {
  border-radius: 0 6px 6px 0;
}
.page-link {
  position: relative;
  display: block;
  box-sizing: border-box;
  min-width: 2rem;
  padding: 0.35rem 0.6rem;
  border: 1px solid var(--border-soft);
  background: var(--bg-card);
  color: var(--link);
  font-size: 0.8rem;
  line-height: 1.25;
  text-align: center;
  cursor: pointer;
  transition: background-color 0.15s, color 0.15s, box-shadow 0.15s;
}
.page-link:hover:not(:disabled) {
  z-index: 2;
  background: var(--bg-primary);
}
.page-link:focus-visible {
  z-index: 3;
  outline: 0;
  box-shadow: 0 0 0 0.2rem color-mix(in srgb, var(--accent) 35%, transparent);
}
.page-item.active .page-link {
  z-index: 1;
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
  cursor: default;
}
.page-item.disabled .page-link {
  color: var(--text-faint);
  cursor: default;
}
</style>
