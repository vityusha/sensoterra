<template>
  <div v-if="canScrollUp || canScrollDown" class="page-scroll" role="group" aria-label="Прокрутка страницы">
    <button v-if="canScrollUp" type="button" class="btn page-scroll-up" :style="{ '--header-bottom': headerBottom + 'px' }" aria-label="На страницу вверх" title="На страницу вверх" @click="scrollPage(-1)">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16l-8 11z"/></svg>
    </button>
    <button v-if="canScrollDown" type="button" class="btn page-scroll-down" :style="{ '--viewport-bottom-inset': viewportBottomInset + 'px' }" aria-label="На страницу вниз" title="На страницу вниз" @click="scrollPage(1)">
      <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16l-8 11z"/></svg>
    </button>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const canScrollUp = ref(false)
const canScrollDown = ref(false)
const headerBottom = ref(0)
const viewportBottomInset = ref(0)
let resizeObserver
let header

function visibleViewport() {
  const viewport = window.visualViewport
  // Pinch-zoom pans within the page and should not change page-scroll controls.
  return viewport?.scale === 1 && viewport.height > 0 ? viewport : null
}

function updateDirections() {
  const page = document.scrollingElement
  const viewport = visibleViewport()
  const height = Math.min(viewport?.height ?? page.clientHeight, page.clientHeight)
  const top = Math.max(0, page.scrollTop, viewport?.pageTop ?? 0)
  const scrollable = page.scrollHeight - page.clientHeight > 1
  canScrollUp.value = scrollable && top > 1
  canScrollDown.value = scrollable && page.scrollHeight - top - height > 1
  viewportBottomInset.value = viewport ? Math.max(0, page.clientHeight - height - viewport.offsetTop) : 0
  if (canScrollUp.value) headerBottom.value = Math.max(0, header?.getBoundingClientRect().bottom ?? 0)
}

function updateLayout() {
  const pageHeader = document.querySelector('#app :is(.page-header, .header-row):not(dialog *)')
  if (pageHeader !== header) {
    if (header) resizeObserver.unobserve(header)
    header = pageHeader
    if (header) resizeObserver.observe(header)
  }
  updateDirections()
}

function scrollPage(direction) {
  window.scrollBy({
    top: direction * (visibleViewport()?.height ?? document.scrollingElement.clientHeight),
    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'
  })
}

watch(() => route.fullPath, updateLayout, { flush: 'post' })

onMounted(() => {
  resizeObserver = new ResizeObserver(updateLayout)
  resizeObserver.observe(document.body)
  updateLayout()
  window.addEventListener('scroll', updateDirections, { passive: true })
  window.addEventListener('resize', updateDirections)
  window.addEventListener('pageshow', updateLayout)
  window.visualViewport?.addEventListener('resize', updateDirections)
  window.visualViewport?.addEventListener('scroll', updateDirections, { passive: true })
})

onUnmounted(() => {
  window.removeEventListener('scroll', updateDirections)
  window.removeEventListener('resize', updateDirections)
  window.removeEventListener('pageshow', updateLayout)
  window.visualViewport?.removeEventListener('resize', updateDirections)
  window.visualViewport?.removeEventListener('scroll', updateDirections)
  resizeObserver?.disconnect()
})
</script>

<style scoped>
.page-scroll .btn {
  position: fixed;
  z-index: 40;
  right: calc(0.75rem + env(safe-area-inset-right, 0px));
  display: grid;
  place-items: center;
  width: 2.75rem;
  height: 2.75rem;
  padding: 0;
  border: 1px solid currentColor;
  border-radius: 50%;
  color: var(--text);
  cursor: pointer;
}
.page-scroll-up { top: calc(0.75rem + max(var(--header-bottom), env(safe-area-inset-top, 0px))); }
.page-scroll-down { bottom: calc(0.75rem + var(--save-bar-height, 0px) + max(var(--viewport-bottom-inset), env(safe-area-inset-bottom, 0px))); }
.page-scroll svg {
  width: 1.25rem;
  height: 1.25rem;
  fill: currentColor;
}
.page-scroll-up svg { transform: rotate(180deg); }
</style>
