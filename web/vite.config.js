import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  optimizeDeps: {
    // 预览抽屉的动态 import 在 dev 下必须预构建，否则运行时加载失败
    include: ['mammoth/mammoth.browser', 'xlsx', 'pdfjs-dist', 'highlight.js'],
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/mcp': 'http://localhost:8080',
    },
  },
  build: {
    outDir: '../server/src/main/resources/static',
    emptyOutDir: true,
  },
})
