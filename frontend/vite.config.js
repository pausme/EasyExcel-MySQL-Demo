import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期把 /api 转发到后端，生产建议 nginx 反代或打进 Spring Boot static
export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          'element-plus': ['element-plus'],
          'vue-vendor': ['vue', 'vue-router', 'pinia'],
          'axios': ['axios']
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:18088',
        changeOrigin: true
      }
    }
  }
})
