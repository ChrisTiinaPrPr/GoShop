import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 本地开发时把接口交给 Spring Boot，避免浏览器跨域限制。
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      // STOMP 使用原生 WebSocket，代理必须显式开启 ws。
      '/ws': { target: 'ws://localhost:8080', ws: true, changeOrigin: true }
    }
  }
})
