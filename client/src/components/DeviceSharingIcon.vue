<template>
  <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
    <path v-if="kind === 'access'" d="M16 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM8 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm0 2c-2.3 0-7 1.2-7 3.5V19h14v-2.5C15 14.2 10.3 13 8 13Zm8 0c-.3 0-.6 0-1 .1 1.3.8 2 2 2 3.4V19h6v-2.5c0-2.3-4.7-3.5-7-3.5Z"/>
    <path v-else d="M20 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2Zm0 4-8 5-8-5V6l8 5 8-5v2Z"/>
  </svg>
</template>

<script setup>
defineProps({ kind: { type: String, required: true } })
</script>
