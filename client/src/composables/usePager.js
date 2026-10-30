import { ref, computed, watch } from 'vue'

// Client-side search and paging over a reactive list. Enough while the API returns the
// full list; move it server-side when a list outgrows a single response.
// matches(item, q) gets the query trimmed and lower-cased; an empty query skips it.
export function usePager(source, query, matches, perPage = 5) {
  const page = ref(1)
  const filtered = computed(() => {
    const q = query.value.trim().toLowerCase()
    if (!q) return source.value
    return source.value.filter(item => matches(item, q))
  })
  const pages = computed(() => Math.max(1, Math.ceil(filtered.value.length / perPage)))
  const paged = computed(() => {
    const current = Math.min(page.value, pages.value)
    const start = (current - 1) * perPage
    return filtered.value.slice(start, start + perPage)
  })
  watch(query, () => { page.value = 1 })
  watch(pages, total => { if (page.value > total) page.value = total })
  return { page, filtered, pages, paged }
}
