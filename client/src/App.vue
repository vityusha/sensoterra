<template>
  <ToastHost />
  <router-view v-if="!auth.loading" />
  <div v-else class="app-loading">Загрузка...</div>
</template>

<script setup>
import { onMounted } from 'vue'
import { auth, loadUser } from './store/auth'
import ToastHost from './components/ToastHost.vue'
import { applyTheme } from './store/settings'

onMounted(async () => {
  applyTheme()
  await loadUser()
})
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
html {
  overflow-x: hidden;
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: var(--bg);
  color: var(--text);
  min-height: 100vh;
  overflow-x: hidden;
  -webkit-text-size-adjust: 100%;
  transition: background 0.2s, color 0.2s;
}

:root {
  --bg: #1a1a2e;
  --bg-soft: #242444;
  --bg-card: #2c2c3e;
  --bg-primary: #3a3a50;
  --bg-accent: #5c1a1a;
  --bg-active: #1a3b5c;
  --text: #eee;
  --text-strong: #e0e0e0;
  --text-sub: #ccc;
  --text-muted: #aaa;
  --text-dim: #888;
  --text-faint: #666;
  --on-accent: #ffdddd;
  --border: #555;
  --border-soft: #444;
  --link: #4dabf7;
  --danger: #e74c3c;
  --success: #2ecc71;
  --accent: #3498db;
  --warn: #f39c12;
  --tag-bg: #0d2b3b;
  --tag-text: #8ab4d0;
  --tag-bg-ok: #0d3b1e;
  --tag-text-ok: #7fd0a0;
  --banner-bg: #12314f;
  --banner-text: #a8d8ff;
  --banner-border: #1f4d7a;
  --divider: #3a3a52;
  --muted-fg: #7a7a92;
  --chart-grid: #2a2a3e;
  --card-shadow: none;
  --btn-bg: #3a3a50;
  --btn-bg-hover: #4a4a66;
  --btn-border: #5b5b85;
  --btn-text: #f2f2f8;
  --btn-disabled-bg: #33334a;
  --btn-disabled-border: #43435c;
  --btn-disabled-text: #8a8a9e;
}

[data-theme="light"] {
  --bg: #f7f7f7;
  --bg-soft: #f0f0f5;
  --bg-card: #ffffff;
  --bg-primary: #e6e6ec;
  --bg-accent: #f6dada;
  --bg-active: #d3e6f6;
  --text: #1a1a1a;
  --text-strong: #222222;
  --text-sub: #333333;
  --text-muted: #4d4d4d;
  --text-dim: #5c5c5c;
  --text-faint: #767676;
  --on-accent: #7a1010;
  --border: #d0d0d0;
  --border-soft: #c6c6c6;
  --link: #1971c2;
  --danger: #c92a2a;
  --success: #2f9e44;
  --accent: #1c7ed6;
  --warn: #e67700;
  --tag-bg: #d6ebf7;
  --tag-text: #1864ab;
  --tag-bg-ok: #d3f3dd;
  --tag-text-ok: #2b8a3e;
  --banner-bg: #d3e6f6;
  --banner-text: #1864ab;
  --banner-border: #90c2e6;
  --divider: #c0c0c8;
  --muted-fg: #5c5c6a;
  --chart-grid: #e0e0e6;
  --card-shadow: 0 1px 3px rgba(0,0,0,0.08), 0 0 0 1px rgba(0,0,0,0.05);
  --btn-bg: #e6e6ec;
  --btn-bg-hover: #d8d8e2;
  --btn-border: #c0c0c8;
  --btn-text: #222222;
  --btn-disabled-bg: #eeeef2;
  --btn-disabled-border: #dcdce2;
  --btn-disabled-text: #999;
}

canvas, img, svg { max-width: 100%; }
.app-loading { color: var(--text-sub); text-align: center; padding: 3rem; }

/* Телефон: кнопки и поля крупнее, шрифт в полях 1rem — иначе iOS зумит при фокусе. */
@media (hover: none) {
  button { min-height: 2.4rem; min-width: 2.4rem; }
  input:not([type='checkbox']):not([type='radio']), select, textarea {
    min-height: 2.4rem;
    font-size: 1rem;
  }
}

/* Узкий экран: длинные имена и chipId переносятся, а не растягивают карточку. */
@media (max-width: 480px) {
  .device-name, .device-chip, .relay-name, .user-name, .field-value, .t-item {
    overflow-wrap: anywhere;
  }
}
</style>
