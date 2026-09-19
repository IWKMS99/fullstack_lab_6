import { defineConfig } from 'vitest/config'
import { loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig(({mode}) => {
  const env = loadEnv(mode, process.cwd(), '');
  const proxyTarget = env.VITE_PROXY_TARGET || 'http://localhost:8081';

  return {
    plugins: [react()],
    test: {
      environment: 'jsdom',
      setupFiles: './src/test/setup.ts',
      globals: true,
      css: true,
      include: ['src/**/*.test.ts', 'src/**/*.test.tsx'],
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html', 'json-summary', 'lcov'],
        include: ['src/features/booking/lib/*.ts', 'src/lib/httpError.ts', 'src/context/AuthContext.tsx', 'src/layers/OverlayLayer/components/RoomFormModal.tsx', 'src/services/queryKeys.ts'],
        thresholds: { statements: 80, lines: 80, functions: 80, branches: 70 },
      },
    },
    build: {
      chunkSizeWarningLimit: 1000,
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (id.includes('node_modules')) {
              if (id.includes('framer-motion')) return 'motion';
              if (id.includes('@tanstack/react-table')) return 'table-admin';
              if (id.includes('react-dom') || id.includes('react-router') || id.includes('react')) return 'react-vendor';
            }
            return undefined;
          }
        },
      },
    },
    server: {
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
  };
});
