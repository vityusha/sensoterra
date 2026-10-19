import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  preview: {
    port: 3000
  },
  server: {
    port: 3000,
    proxy: {
      '/api': 'http://localhost:8080',
      // WS для live-статуса (connection.online): браузер открывает same-origin /ws/status
      '/ws': { target: 'http://localhost:8080', ws: true }
    }
  }
})