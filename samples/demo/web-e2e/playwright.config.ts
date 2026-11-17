import { defineConfig } from '@playwright/test';

// The demo is a Compose canvas, so the tests click it by position and read the apps inside the sandbox frames.
// Build the web distribution first: ./gradlew :samples:demo:shared:wasmJsBrowserDevelopmentExecutableDistribution
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  workers: 1,
  retries: 0,
  use: { baseURL: 'http://localhost:8080', viewport: { width: 700, height: 860 }, launchOptions: { args: ['--use-gl=angle', '--use-angle=swiftshader'] } },
  webServer: {
    command: 'node ../../../scripts/serve-web-demo.mjs',
    url: 'http://localhost:8080/',
    reuseExistingServer: true,
  },
});
