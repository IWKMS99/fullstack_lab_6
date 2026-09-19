import {defineConfig, devices} from '@playwright/test';

export default defineConfig({
  testDir: './tests/real',
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 60000,
  expect: {timeout: 15000},
  reporter: [['list'], ['html', {outputFolder: 'playwright-report-real', open: 'never'}]],
  outputDir: 'test-results-real',
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:18085',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    reducedMotion: 'reduce',
    launchOptions: {executablePath: process.env.E2E_CHROMIUM_EXECUTABLE},
    locale: 'ru-RU',
  },
  projects: [{name: 'chromium-real', use: {...devices['Desktop Chrome']}}],
});
