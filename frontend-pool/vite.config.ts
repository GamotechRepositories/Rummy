import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: true, // Exposes Network link in terminal (e.g. http://192.168.x.x:5173/)
    port: 5173,
  },
})
