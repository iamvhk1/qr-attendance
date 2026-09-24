import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      // Allows clean imports: import { Button } from '@/components/ui'
      '@': `${import.meta.dirname}/src`,
    },
  },
  server: {
    port: 5173,
    // Proxy API calls to the Spring Boot backend during development.
    // This avoids CORS issues when running both locally.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
