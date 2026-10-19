/* Anonymous studio and optional account navigation regressions.
 * Requires the isolated localhost:8001 server and tests/prepare-auth.py.
 * Synthetic camera/microphone feed real MediaRecorder and browser export.
 * This suite signs in only to the test account and never creates an account.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { chromium } = require('playwright');
const { baseURL, testEmail, testPassword, signOut } = require('./helpers/auth.cjs');

const origin = new URL(baseURL);
assert.ok(['127.0.0.1', 'localhost'].includes(origin.hostname) && origin.port === '8001',
  'Guest/account regressions require the isolated localhost:8001 test server');
const directory = path.join(__dirname, 'screenshots');
const guestScript = `Roteiro do visitante ${randomUUID()}\n\nFim do roteiro.`;
const accountScript = `Roteiro exclusivo da conta ${randomUUID()}`;

async function waitForStudio(page, authenticated = false) {
  await page.waitForFunction((signedIn) => !!document.body.dataset.userKey === signedIn
    && document.querySelector('#preview-stage')?.dataset.state === 'idle'
    && document.querySelector('#camera-video')?.readyState >= 2, authenticated);
}

async function range(page, selector, value) {
  await page.locator(selector).evaluate((input, next) => {
    input.value = String(next);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }, value);
}

async function settings(page) {
  if (await page.locator('body').getAttribute('data-mobile-panel') !== 'settings') {
    await page.locator('#settings-btn').click();
  }
}

async function assertAccountLinksBlocked(page, state) {
  const before = page.url();
  for (const selector of ['#account-login-link', '#account-signup-link']) {
    assert.equal(await page.locator(selector).getAttribute('aria-disabled'), 'true', `${state}: account link is disabled`);
    await page.locator(selector).evaluate((link) => link.click());
    assert.equal(page.url(), before, `${state}: account navigation cannot interrupt capture/export`);
  }
}

async function startTake(page) {
  await page.locator('#immersive-record-btn').click();
  await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'recording');
  await page.waitForTimeout(1000);
}

async function stopTake(page) {
  await page.locator('#immersive-stop-btn').click();
  await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'idle'
    && document.querySelector('#recording-count').textContent === '1');
  await page.locator('#close-recordings-btn').click();
}

async function cancelNavigation(page, selector) {
  await settings(page);
  await page.locator(selector).click();
  await page.locator('#logout-dialog').waitFor({ state: 'visible' });
  await page.locator('#logout-cancel-btn').click();
  assert.equal(new URL(page.url()).pathname, '/', 'Cancel preserves the studio');
  assert.equal(await page.locator('#recording-count').textContent(), '1', 'Cancel preserves the unsaved original');
}

async function assertGuestContents(page) {
  assert.equal(await page.locator('body').getAttribute('data-user-key') || '', '', 'Visitor has no account storage identity');
  assert.equal(await page.locator('#script-input').inputValue(), guestScript, 'Visitor restores its own script');
  assert.equal(await page.locator('#script-title').inputValue(), 'Roteiro visitante');
  assert.equal(await page.locator('#speed-input').inputValue(), '67');
  assert.equal(await page.locator('#font-select').inputValue(), 'serif');
  assert.equal(await page.locator('#language-select').inputValue(), 'es', 'Visitor restores its own language');
}

async function run() {
  await fs.mkdir(directory, { recursive: true });
  const browser = await chromium.launch({
    executablePath: process.env.TEST_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true, args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
  });
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  await context.addInitScript(() => {
    const nativeMedia = navigator.mediaDevices?.getUserMedia?.bind(navigator.mediaDevices);
    let release;
    const pending = new Promise((resolve) => { release = resolve; });
    window.__releaseGuestCamera = release;
    window.__guestMediaRequests = 0;
    if (nativeMedia) navigator.mediaDevices.getUserMedia = async (...args) => {
      window.__guestMediaRequests++;
      if (location.search.includes('hold-camera')) await pending;
      return nativeMedia(...args);
    };
    window.__guestRecorders = [];
    const NativeRecorder = window.MediaRecorder;
    window.MediaRecorder = class extends NativeRecorder {
      constructor(...args) {
        super(...args);
        const report = { recorder: this, input: args[0], events: [] };
        window.__guestRecorders.push(report);
        for (const event of ['start', 'pause', 'resume', 'stop']) {
          this.addEventListener(event, () => report.events.push(event));
        }
      }
    };
  });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  try {
    const response = await page.goto(`${baseURL}/?hold-camera`);
    assert.equal(response.status(), 200, 'Public studio is accessible without account or consent gating');
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'preparing');
    await settings(page);
    await assertAccountLinksBlocked(page, 'Camera preparation');
    await page.evaluate(() => window.__releaseGuestCamera());
    await waitForStudio(page);
    await page.locator('#close-settings-btn').click();
    assert.equal((await (await context.request.get(`${baseURL}/accounts/session/`)).json()).authenticated, false);

    for (const language of ['pt-BR', 'en', 'es']) {
      await page.evaluate((next) => window.StudioI18n.setLanguage(next), language);
      for (const viewport of [{ width: 320, height: 568 }, { width: 390, height: 844 }, { width: 844, height: 390 }]) {
        await page.setViewportSize(viewport);
        await settings(page);
        for (const selector of ['#account-login-link', '#account-signup-link']) {
          const layout = await page.locator(selector).evaluate((element) => {
            const box = element.getBoundingClientRect();
            const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
            return { x: box.x, y: box.y, right: box.right, bottom: box.bottom, width: box.width, height: box.height,
              viewportWidth: innerWidth, viewportHeight: innerHeight, reachable: hit === element || element.contains(hit),
              accountTop: element.closest('.account-card')?.getBoundingClientRect().top };
          });
          assert.ok(layout.width >= 44 && layout.height >= 44 && layout.x >= -1 && layout.y >= -1
            && layout.right <= layout.viewportWidth + 1 && layout.bottom <= layout.viewportHeight + 1 && layout.reachable,
          `${language} ${viewport.width}×${viewport.height}: account action visible immediately on opening Settings: ${JSON.stringify(layout)}`);
          assert.equal(await page.locator(selector).getAttribute('aria-disabled'), 'false');
        }
        assert.match(await page.locator('#account-login-link').textContent(),
          { 'pt-BR': /entrar/i, en: /sign in/i, es: /iniciar sesi[oó]n/i }[language]);
        if (language === 'pt-BR') await page.screenshot({ path: path.join(directory, `guest-settings-${viewport.width}x${viewport.height}.png`), animations: 'disabled' });
        await page.locator('#close-settings-btn').click();
      }
    }
    console.log('PASS anonymous camera preparation, public studio and immediately visible optional login in mobile PT/EN/ES');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('#editor-btn').click();
    await page.locator('#script-input').fill(guestScript);
    await page.locator('#script-title').fill('Roteiro visitante');
    await page.locator('#close-script-btn').click();
    await settings(page);
    await range(page, '#speed-input', 67);
    await page.locator('#font-select').selectOption('serif');
    await page.locator('#close-settings-btn').click();
    await page.waitForFunction((script) => JSON.parse(localStorage.getItem('open-script.studio.v1'))?.script === script, guestScript);
    await page.goto(baseURL);
    await waitForStudio(page);
    await assertGuestContents(page);

    await startTake(page);
    await settings(page);
    await assertAccountLinksBlocked(page, 'Recording');
    await page.locator('#close-settings-btn').click();
    await page.locator('#immersive-pause-btn').click();
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'paused');
    await settings(page);
    await assertAccountLinksBlocked(page, 'Paused capture');
    await page.locator('#close-settings-btn').click();
    await page.locator('#immersive-pause-btn').click();
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'recording');
    await range(page, '#immersive-prompt-progress', 100);
    await page.waitForTimeout(600);
    assert.equal(await page.locator('#preview-stage').getAttribute('data-state'), 'recording', 'Script end never stops anonymous capture');
    await stopTake(page);
    assert.deepEqual(await page.evaluate(() => window.__guestRecorders[0].events), ['start', 'pause', 'resume', 'stop']);
    await cancelNavigation(page, '#account-login-link');
    await cancelNavigation(page, '#account-signup-link');

    await page.locator('#immersive-recordings-btn').click();
    await page.locator('.recording-card__export').click();
    await page.locator('#export-format').selectOption('webm');
    await page.locator('#export-resolution').selectOption('360');
    await page.locator('#export-submit-btn').click();
    await page.waitForFunction(() => document.querySelector('#export-dialog').getAttribute('aria-busy') === 'true');
    await assertAccountLinksBlocked(page, 'Export');
    await page.locator('#export-cancel-btn').click();
    await page.waitForFunction(() => document.querySelector('#export-dialog').getAttribute('aria-busy') === 'false');
    await page.locator('#export-format').selectOption('original');
    const [download] = await Promise.all([page.waitForEvent('download'), page.locator('#export-submit-btn').click()]);
    assert.ok((await fs.stat(await download.path())).size > 1000, 'Guest exports real video without an account');
    await page.locator('#close-export-btn').click();
    await page.locator('#close-recordings-btn').click();
    await settings(page);
    await page.locator('#account-signup-link').click();
    await page.waitForURL(/\/accounts\/signup\//);
    assert.equal(await page.evaluate(() => window.__guestMediaRequests), 0, 'Optional signup does not request media');
    console.log('PASS guest persistence, actual pause/resume/manual stop, navigation guards, discard cancellation and account-free export');

    await page.goto(baseURL);
    await waitForStudio(page);
    await startTake(page);
    await stopTake(page);
    await settings(page);
    await page.locator('#account-login-link').click();
    await page.locator('#logout-dialog').waitFor({ state: 'visible' });
    await page.locator('#logout-confirm-btn').click();
    await page.waitForURL(/\/accounts\/login\//);
    assert.equal(await page.evaluate(() => window.__guestMediaRequests), 0, 'Confirmed optional sign-in closes guest capture safely');
    await page.locator('[name="username"]').fill(testEmail);
    await page.locator('[name="password"]').fill(testPassword);
    await page.locator('.auth-form .auth-submit').click();
    await waitForStudio(page, true);
    const accountKey = await page.locator('body').getAttribute('data-user-key');
    assert.notEqual(await page.locator('#script-input').inputValue(), guestScript, 'Account never imports visitor script automatically');
    assert.notEqual(await page.locator('#speed-input').inputValue(), '67', 'Account never imports visitor appearance automatically');
    await page.locator('#editor-btn').click();
    await page.locator('#script-input').fill(accountScript);
    await page.locator('#close-script-btn').click();
    await settings(page);
    await range(page, '#speed-input', 42);
    await page.locator('#font-select').selectOption('mono');
    await page.locator('#language-select').selectOption('en');
    await page.waitForFunction(({ key, script }) => JSON.parse(localStorage.getItem(`open-script.studio.v1:user-${key}`))?.script === script,
      { key: accountKey, script: accountScript });
    await page.locator('#logout-btn').click();
    await page.waitForURL((url) => url.pathname === '/');
    await waitForStudio(page);
    await assertGuestContents(page);
    await settings(page);
    await page.locator('#account-login-link').click();
    await page.waitForURL(/\/accounts\/login\//);
    await page.locator('[name="username"]').fill(testEmail);
    await page.locator('[name="password"]').fill(testPassword);
    await page.locator('.auth-form .auth-submit').click();
    await waitForStudio(page, true);
    assert.equal(await page.locator('#script-input').inputValue(), accountScript, 'Returning account restores account script');
    assert.equal(await page.locator('#speed-input').inputValue(), '42');
    assert.equal(await page.locator('#font-select').inputValue(), 'mono');
    assert.equal(await page.locator('#language-select').inputValue(), 'en', 'Account language preference wins over visitor language');

    await startTake(page);
    await stopTake(page);
    await signOut(context);
    await page.evaluate(() => {
      const nativeNow = Date.now.bind(Date);
      Date.now = () => nativeNow() + 11000;
      window.dispatchEvent(new Event('focus'));
    });
    await settings(page);
    await page.locator('#account-session-warning').waitFor({ state: 'visible' });
    await cancelNavigation(page, '#account-guest-link');
    assert.equal(await page.locator('body').getAttribute('data-user-key'), accountKey, 'Canceled session recovery retains the account storage owner');
    await settings(page);
    await page.locator('#account-guest-link').click();
    await page.locator('#logout-confirm-btn').click();
    await page.waitForFunction(() => !document.body.dataset.userKey);
    await waitForStudio(page);
    await assertGuestContents(page);
    assert.equal(await page.evaluate((key) => JSON.parse(localStorage.getItem(`open-script.studio.v1:user-${key}`))?.script, accountKey),
      accountScript, 'Recovering as guest never overwrites account contents');
    assert.deepEqual(errors, [], 'No unhandled guest/account browser errors');
    console.log('PASS guest/account isolation across optional sign-in, sign-out and guarded expired-session recovery');
  } finally {
    await context.close();
    await browser.close();
  }
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
