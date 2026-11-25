import { expect, Frame, Page, test } from '@playwright/test';

// Positions on the 700 x 860 canvas.
const CHIP_Y = 828;
const chips = { time: 70, form: 200, chart: 318, hostile: 430 };
const THEME = { light: { x: 580, y: 32 }, dark: { x: 657, y: 32 } };

/** Opens the demo; `run` is a scenario id that the demo starts on its own (`?run=`). */
async function open(page: Page, run = '') {
  await page.goto(run ? `/?run=${run}` : '/');
  await expect(page.locator('canvas').first()).toBeVisible();
  await page.waitForTimeout(4000); // Wasm start-up.
}

/** The frame that holds the app: the one whose document has [selector]. */
async function appFrame(page: Page, selector: string): Promise<Frame> {
  let found: Frame | undefined;
  await expect
    .poll(async () => {
      for (const frame of page.frames()) {
        if (frame === page.mainFrame()) continue;
        if (await frame.$(selector).catch(() => null)) {
          found = frame;
          return true;
        }
      }
      return false;
    }, { timeout: 30_000 })
    .toBe(true);
  return found!;
}

test('the sandbox proxy is on another origin than the host page', async ({ page }) => {
  await open(page);
  await page.mouse.click(chips.form, CHIP_Y);
  const frame = await appFrame(page, '#submit');
  expect(new URL(page.url()).origin).toBe('http://localhost:8080');
  // The app's document sits below the proxy, which is served from :8081.
  let ancestor: Frame | null = frame;
  const origins: string[] = [];
  while (ancestor) {
    const url = ancestor.url();
    if (url.startsWith('http')) origins.push(new URL(url).origin);
    ancestor = ancestor.parentFrame();
  }
  expect(origins).toContain('http://localhost:8081');
  // The app cannot read the host page.
  const reach = await frame.evaluate(() => {
    try { return String(window.top!.document.title); } catch { return 'blocked'; }
  });
  expect(reach).toBe('blocked');
});

test('a tool call from the contact form is answered', async ({ page }) => {
  await open(page);
  await page.mouse.click(chips.form, CHIP_Y);
  const frame = await appFrame(page, '#submit');
  await frame.click('#submit');
  await expect(frame.locator('#out')).toHaveText('Saved as T-1001', { timeout: 20_000 });
});

test('the theme switch reaches the app', async ({ page }) => {
  await open(page);
  await page.mouse.click(chips.form, CHIP_Y);
  const frame = await appFrame(page, '#submit');
  await page.mouse.click(THEME.dark.x, THEME.dark.y);
  await expect.poll(() => frame.evaluate(() => document.documentElement.getAttribute('data-theme'))).toBe('dark');
  await page.mouse.click(THEME.light.x, THEME.light.y);
  await expect.poll(() => frame.evaluate(() => document.documentElement.getAttribute('data-theme'))).toBe('light');
});

test('the hostile app is contained', async ({ page }) => {
  const attackerRequests: string[] = [];
  page.on('request', (r) => { if (r.url().includes('evil.example')) attackerRequests.push(r.url()); });
  await open(page);
  await page.mouse.click(chips.hostile, CHIP_Y);
  const frame = await appFrame(page, '#status');
  await expect.poll(() => frame.evaluate(() => (window as any).__probe?.done), { timeout: 40_000 }).toBe(true);
  const probe = await frame.evaluate(() => (window as any).__probe);
  expect(probe.unknownTool).toBe(-32000);
  expect(probe.hiddenTool).toBe(-32000);
  expect(['true', 'error']).toContain(probe.badLink);
  expect(probe.fetch).toBe('blocked');
  expect(probe.media).toBe('denied');
  expect(['blocked', 'handle']).toContain(probe.open);
  // The app then tries to navigate away. The proxy tears the frame down; nothing may reach the attacker.
  await expect.poll(() => frame.isDetached(), { timeout: 20_000 }).toBe(true);
  expect(page.frames().map((f) => f.url()).filter((u) => u.includes('evil.example'))).toEqual([]);
  expect(attackerRequests).toEqual([]);
  expect(page.frames().filter((f) => f.url().startsWith('http://localhost:8081')).length).toBe(1);
});

test('the link policy opens an https link and refuses an http one', async ({ page }) => {
  await open(page, 'policy');
  const frame = await appFrame(page, '#https');
  await frame.click('#https');
  await expect(frame.locator('#out')).toContainText('The host opened https://example.com/', { timeout: 20_000 });
  await frame.click('#http');
  await expect(frame.locator('#out')).toContainText('Refused by the host', { timeout: 20_000 });
});

test('a fullscreen request is answered with the mode the host is in', async ({ page }) => {
  await open(page, 'display');
  const frame = await appFrame(page, '#ask');
  await frame.click('#ask');
  await expect(frame.locator('#out')).toContainText('The host is now in:', { timeout: 20_000 });
});

test('the content policy keeps the https domain and drops the plain http one', async ({ page }) => {
  await open(page, 'csp');
  const frame = await appFrame(page, '#sandbox');
  await expect
    .poll(() => frame.locator('#sandbox').textContent(), { timeout: 20_000 })
    .toContain('https://api.example.com');
  const sandbox = await frame.locator('#sandbox').textContent();
  expect(sandbox).not.toContain('evil.example');
});

test('the permissions scenario grants the camera and withholds the microphone', async ({ page }) => {
  await open(page, 'capabilities');
  const frame = await appFrame(page, '#sandbox');
  await expect
    .poll(() => frame.locator('#sandbox').textContent(), { timeout: 20_000 })
    .toContain('"camera"');
  const sandbox = await frame.locator('#sandbox').textContent();
  expect(sandbox).not.toContain('microphone');
});

test('the host theme scenario follows a theme switch', async ({ page }) => {
  await open(page, 'theme');
  const frame = await appFrame(page, '#theme');
  await page.mouse.click(THEME.dark.x, THEME.dark.y);
  await expect(frame.locator('#theme')).toHaveText('theme: dark', { timeout: 20_000 });
  await page.mouse.click(THEME.light.x, THEME.light.y);
  await expect(frame.locator('#theme')).toHaveText('theme: light', { timeout: 20_000 });
});
