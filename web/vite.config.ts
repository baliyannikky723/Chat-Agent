/// <reference types="vitest/config" />
import { fileURLToPath } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

// The npm launcher (scripts/dev.mjs) passes the Spring Boot address it started.
// 127.0.0.1 rather than localhost: Spring listens on IPv4, and Node may resolve localhost to ::1.
const backend = process.env.ORRERY_BACKEND_URL ?? 'http://127.0.0.1:8080';

export default defineConfig({
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // Server-Sent Events pass through untouched: the proxy pipes the stream, nothing buffers it.
    proxy: { '/api': { target: backend, changeOrigin: false } },
  },
  preview: { port: 4173, proxy: { '/api': { target: backend } } },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    // three.js is imported only by the lazily loaded hologram, so it lands in its own chunk
    // and never blocks the first paint.
    chunkSizeWarningLimit: 900,
  },
  test: { environment: 'node', include: ['src/**/*.test.ts'] },
});
