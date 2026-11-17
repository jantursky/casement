import { expect, Frame, Page, test } from '@playwright/test';

// Positions on the 700 x 860 canvas.
const CHIP_Y = 828;
const chips = { time: 70, form: 200, chart: 318, hostile: 430 };
const THEME = { light: { x: 580, y: 32 }, dark: { x: 657, y: 32 } };

async function open(page: Page) {
  await page.goto('/');
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
