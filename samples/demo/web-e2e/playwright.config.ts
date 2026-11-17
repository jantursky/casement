import { defineConfig, devices } from '@playwright/test';

const swiftshader = ['--use-gl=angle', '--use-angle=swiftshader'];
const brave = process.env.BRAVE_PATH ?? '/Applications/Brave Browser.app/Contents/MacOS/Brave Browser';

// The demo is a Compose canvas, so the tests click it by position and read the apps inside the sandbox frames.
// Build the web distribution first: ./gradlew :samples:demo:shared:wasmJsBrowserDevelopmentExecutableDistribution
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  workers: 1,
  retries: 0,
  use: { baseURL: 'http://localhost:8080', viewport: { width: 700, height: 860 } },
  // The browsers a user may run: Playwright's Chromium and Firefox, plus the installed Chrome, Edge and Brave.
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 700, height: 860 }, launchOptions: { args: swiftshader } } },
    { name: 'chrome', use: { channel: 'chrome', viewport: { width: 700, height: 860 }, launchOptions: { args: swiftshader } } },
    { name: 'edge', use: { channel: 'msedge', viewport: { width: 700, height: 860 }, launchOptions: { args: swiftshader } } },
    { name: 'brave', use: { viewport: { width: 700, height: 860 }, launchOptions: { executablePath: brave, args: swiftshader } } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'], viewport: { width: 700, height: 860 } } },
  ],
  webServer: {
    command: 'node ../../../scripts/serve-web-demo.mjs',
    url: 'http://localhost:8080/',
    reuseExistingServer: true,
  },
});
