import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Vite turns our React source files into a few optimized static files in dist/.
// Maven then packs dist/ into the jar, so the Java server can serve the UI itself.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false, // don't ship source maps to production
    modulePreload: { polyfill: false },
  },
  server: {
    // `npm run dev` gives hot-reload while editing the UI. API calls are
    // forwarded to the Java server, which must be running on port 8080.
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
