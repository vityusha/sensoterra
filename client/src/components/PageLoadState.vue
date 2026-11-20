<template>
  <div class="load-state" role="status">
    <template v-if="error">
      <p>{{ error }}</p>
      <button type="button" class="btn small" @click="$emit('retry')">Повторить</button>
    </template>
    <p v-else>Загрузка…</p>
  </div>
</template>

<script setup>
defineProps({ error: String })
defineEmits(['retry'])
</script>

<style scoped>
.load-state {
  min-height: 12rem;
  padding: 1rem;
  color: var(--text-muted);
}
.load-state p { margin: 0 0 0.75rem; }
</style>
