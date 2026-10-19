/* Account and privacy regressions use real Django forms and an isolated DB.
 * Run Django on localhost:8001 with DJANGO_DATABASE_PATH pointing to
 * tests/screenshots/login-test.sqlite3, then node tests/login.cjs.
 * Two temporary accounts are created only in that disposable test database.
 * The browser's camera and microphone are synthetic.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { chromium } = require('playwright');
const { baseURL, testPassword, signIn, signOut } = require('./helpers/auth.cjs');
const { acceptDocuments } = require('./consent.cjs');

const origin = new URL(baseURL);
assert.ok(['127.0.0.1', 'localhost'].includes(origin.hostname) && origin.port === '8001',
  'Account creation regressions require the isolated localhost:8001 test server');
const screenshotDirectory = path.join(__dirname, 'screenshots');
const emailA = `qa-a-${randomUUID()}@open-script.test`;
const emailB = `qa-b-${randomUUID()}@open-script.test`;

async function setRange(page, selector, value) {
  await page.locator(selector).evaluate((input, next) => {
    input.value = String(next);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }, value);
}

async function waitForStudio(page) {
  try {
    await page.waitForFunction(() => document.body.dataset.userKey
      && document.querySelector('#preview-stage')?.dataset.state === 'idle'
      && document.querySelector('#camera-video')?.readyState >= 2);
  } catch (cause) {
    const error = await page.locator('#auth-errors').textContent({ timeout: 1000 }).catch(() => '');
    throw new Error(`Studio did not open. ${error.trim() || 'Check camera readiness and the authentication session.'}`, { cause });
  }
}

async function submitSignup(page, email, name, password = testPassword) {
  await page.goto(new URL('/accounts/signup/', baseURL).href);
  await page.locator('[name="display_name"]').fill(name);
  await page.locator('[name="email"]').fill(email);
  await page.locator('[name="password1"]').fill(password);
  await page.locator('[name="password2"]').fill(password);
  await acceptDocuments(page);
  await page.locator('.auth-form .auth-submit').click();
}

async function submitLogin(page, email, password = testPassword, remember = false) {
  await page.goto(new URL('/accounts/login/', baseURL).href);
  await page.locator('[name="username"]').fill(email);
  await page.locator('[name="password"]').fill(password);
  await page.locator('[name="remember_me"]').setChecked(remember);
  await page.locator('.auth-form .auth-submit').click();
}

async function logout(page) {
  await page.locator('#settings-btn').click();
  const form = page.locator('form[action$="/accounts/logout/"]');
  await form.locator('button[type="submit"]').scrollIntoViewIfNeeded();
  await form.locator('button[type="submit"]').click();
  await page.waitForURL((url) => url.pathname === '/');
  await page.waitForFunction(() => !document.body.dataset.userKey
    && document.querySelector('#preview-stage')?.dataset.state === 'idle'
    && document.querySelector('#camera-video')?.readyState >= 2);
  assert.equal(await page.locator('#camera-video').count(), 1, 'Logout returns to the public studio');
}

async function checkChangedSession(page) {
  // Focus checks intentionally coalesce within ten seconds. Advance only
  // Date.now, leaving native camera/encoder timers running normally.
  await page.evaluate(() => {
    const previousNow = Date.now.bind(Date);
    Date.now = () => previousNow() + 11000;
    window.dispatchEvent(new Event('focus'));
  });
}

async function assertAuthLayout(page, label) {
  const dimensions = await page.evaluate(() => ({ width: innerWidth,
    contentWidth: document.documentElement.scrollWidth, bodyWidth: document.body.scrollWidth }));
  assert.ok(dimensions.contentWidth <= dimensions.width + 1 && dimensions.bodyWidth <= dimensions.width + 1,
    `${label}: no horizontal page overflow ${JSON.stringify(dimensions)}`);
  const controls = page.locator('.auth-form input:not([type="hidden"]), .auth-form button, #language-select');
  for (let index = 0; index < await controls.count(); index++) {
    const control = controls.nth(index);
    await control.evaluate((element) => element.scrollIntoView({ block: 'center', behavior: 'instant' }));
    const box = await control.evaluate((element) => {
      const bounds = element.getBoundingClientRect();
      const hit = document.elementFromPoint(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
      return { left: bounds.left, top: bounds.top, right: bounds.right, bottom: bounds.bottom,
        width: bounds.width, height: bounds.height, viewportHeight: innerHeight, viewportWidth: innerWidth,
        type: element.type, hit: hit === element || element.contains(hit) };
    });
    assert.ok(box.left >= -1 && box.right <= box.viewportWidth + 1 && box.top >= -1
      && box.bottom <= box.viewportHeight + 1 && box.hit, `${label}: form control is reachable ${JSON.stringify(box)}`);
    if (box.type !== 'checkbox') assert.ok(box.height >= 44, `${label}: form controls provide touch targets`);
  }
}

async function run() {
  await fs.mkdir(screenshotDirectory, { recursive: true });
  const browser = await chromium.launch({
    executablePath: process.env.TEST_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true, args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
  });
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  await context.addInitScript(() => {
    window.__loginMediaRequests = 0;
    const native = navigator.mediaDevices?.getUserMedia?.bind(navigator.mediaDevices);
    if (native) navigator.mediaDevices.getUserMedia = (...args) => { window.__loginMediaRequests++; return native(...args); };
  });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  page.setDefaultNavigationTimeout(30000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  try {
    await page.goto(baseURL);
    assert.equal(new URL(page.url()).pathname, '/', 'Anonymous studio access does not require login');
    assert.equal(await page.locator('#camera-video').count(), 1);
    assert.equal(await page.locator('body').getAttribute('data-user-key') || '', '', 'Public studio uses visitor storage');
    const response = await context.request.get(new URL('/accounts/session/', baseURL).href);
    assert.equal((await response.json()).authenticated, false);
    await page.goto(new URL('/accounts/login/', baseURL).href);
    assert.equal(await page.evaluate(() => window.__loginMediaRequests), 0, 'The optional login page never requests camera or microphone access');

    for (const language of ['pt-BR', 'en', 'es']) {
      await page.locator('#language-select').selectOption(language);
      const locale = { 'pt-BR': 'pt-BR', en: 'en-US', es: 'es-ES' }[language];
      assert.equal(await page.locator('html').getAttribute('lang'), locale);
      assert.match(await page.locator('.auth-form .auth-submit').textContent(),
        { 'pt-BR': /entrar/i, en: /sign in/i, es: /iniciar sesi[oó]n/i }[language]);
      for (const viewport of [{ width: 320, height: 568 }, { width: 390, height: 844 }, { width: 844, height: 390 }, { width: 1440, height: 900 }]) {
        await page.setViewportSize(viewport);
        await assertAuthLayout(page, `login ${language} ${viewport.width}×${viewport.height}`);
        if (language === 'pt-BR') await page.screenshot({
          path: path.join(screenshotDirectory, `login-${viewport.width}x${viewport.height}.png`), fullPage: true });
      }
    }
    await page.locator('#language-select').selectOption('pt-BR');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('[name="password"]').fill(testPassword);
    const toggle = page.locator('button[data-password-target="id_password"]');
    await toggle.click();
    assert.equal(await page.locator('[name="password"]').getAttribute('type'), 'text');
    assert.equal(await toggle.getAttribute('aria-pressed'), 'true');
    await toggle.click();
    assert.equal(await page.locator('[name="password"]').getAttribute('type'), 'password');
    assert.equal(await toggle.getAttribute('aria-pressed'), 'false');

    await submitLogin(page, emailA, 'incorrect-password');
    await page.locator('#auth-errors').waitFor();
    assert.ok(await page.locator('#auth-errors').evaluate((element) => document.activeElement === element),
      'A failed login brings focus to its accessible error summary');
    assert.equal(await page.locator('[name="password"]').inputValue(), '', 'Failed login does not echo the password');
    assert.equal(await page.evaluate(() => window.__loginMediaRequests), 0);
    console.log('PASS public studio, optional translated login layouts, password visibility and focused credential errors');

    await page.goto(new URL('/accounts/signup/', baseURL).href);
    for (const language of ['pt-BR', 'en', 'es']) {
      await page.locator('#language-select').selectOption(language);
      assert.match(await page.locator('.auth-form .auth-submit').textContent(),
        { 'pt-BR': /criar conta/i, en: /create account/i, es: /crear cuenta/i }[language]);
      for (const viewport of [{ width: 320, height: 568 }, { width: 390, height: 844 }, { width: 844, height: 390 }, { width: 1440, height: 900 }]) {
        await page.setViewportSize(viewport);
        await assertAuthLayout(page, `signup ${language} ${viewport.width}×${viewport.height}`);
      }
    }
    await page.locator('#language-select').selectOption('pt-BR');
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({ path: path.join(screenshotDirectory, 'signup-mobile.png'), fullPage: true });
    await submitSignup(page, emailA, 'Pessoa A', '12345678');
    await page.locator('#auth-errors').waitFor();
    assert.equal(await page.locator('[name="password1"]').inputValue(), '');
    assert.equal(await page.locator('[name="password2"]').inputValue(), '');
    assert.equal(await page.evaluate(() => window.__loginMediaRequests), 0, 'Rejected signup never opens the camera');
    await submitSignup(page, emailA, 'Pessoa A');
    await waitForStudio(page);
    const keyA = await page.locator('body').getAttribute('data-user-key');
    assert.ok(await page.evaluate(() => window.__loginMediaRequests > 0), 'Successful signup opens the authorized studio');
    await page.locator('#editor-btn').click();
    const scriptA = `Roteiro privado da conta A: ${randomUUID()}`;
    await page.locator('#script-input').fill(scriptA);
    await page.locator('#script-title').fill('Título privado da conta A');
    await page.locator('#close-script-btn').click();
    await page.locator('#settings-btn').click();
    await setRange(page, '#speed-input', 73);
    await page.locator('#font-select').selectOption('serif');
    await page.locator('#language-select').selectOption('es');
    await page.locator('#close-settings-btn').click();
    await page.waitForFunction(({ key, script }) => JSON.parse(localStorage.getItem(`open-script.studio.v1:user-${key}`))?.script === script,
      { key: keyA, script: scriptA });
    assert.equal(await page.evaluate((key) => localStorage.getItem(`open-script.language.v1:user-${key}`), keyA), 'es');
    await logout(page);

    await submitSignup(page, emailA.toUpperCase(), 'Outra pessoa');
    await page.locator('#auth-errors').waitFor();
    assert.match(page.url(), /\/accounts\/signup\//, 'Duplicate emails cannot create another account');
    await submitSignup(page, emailB, 'Pessoa B');
    await waitForStudio(page);
    const keyB = await page.locator('body').getAttribute('data-user-key');
    assert.notEqual(keyB, keyA, 'Accounts receive separate local-storage identities');
    assert.notEqual(await page.locator('#script-input').inputValue(), scriptA, 'A new account cannot see another account script');
    assert.notEqual(await page.locator('#script-title').inputValue(), 'Título privado da conta A');
    assert.notEqual(await page.locator('#speed-input').inputValue(), '73');
    assert.notEqual(await page.locator('#font-select').inputValue(), 'serif');
    assert.equal(await page.locator('#language-select').inputValue(), 'pt-BR', 'New accounts do not inherit another account language');
    await page.locator('#editor-btn').click();
    const scriptB = `Roteiro privado da conta B: ${randomUUID()}`;
    await page.locator('#script-input').fill(scriptB);
    await page.locator('#close-script-btn').click();
    await page.locator('#settings-btn').click();
    await page.locator('#language-select').selectOption('en');
    await page.locator('#close-settings-btn').click();
    await page.waitForFunction(({ key, script }) => JSON.parse(localStorage.getItem(`open-script.studio.v1:user-${key}`))?.script === script,
      { key: keyB, script: scriptB });
    await logout(page);

    await submitLogin(page, emailA.toUpperCase(), testPassword, true);
    await waitForStudio(page);
    assert.equal(await page.locator('#script-input').inputValue(), scriptA, 'Returning account recovers its own script');
    assert.equal(await page.locator('#script-title').inputValue(), 'Título privado da conta A');
    assert.equal(await page.locator('#speed-input').inputValue(), '73');
    assert.equal(await page.locator('#font-select').inputValue(), 'serif');
    assert.equal(await page.locator('#language-select').inputValue(), 'es');
    const remembered = (await context.cookies()).find((cookie) => cookie.name === 'sessionid');
    assert.ok(remembered.expires > Date.now() / 1000, 'Remember me gives the authenticated session a persistent expiry');
    await page.screenshot({ path: path.join(screenshotDirectory, 'account-settings-mobile.png'), fullPage: true });
    console.log('PASS signup validation, duplicate email, logout and two-account script/preferences/language isolation');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('#immersive-record-btn').click();
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'recording');
    await page.waitForTimeout(1100);
    await page.locator('#settings-btn').click();
    assert.equal(await page.locator('#logout-btn').isDisabled(), true, 'Logout cannot interrupt an active take');
    await page.locator('#close-settings-btn').click();
    await page.locator('#immersive-stop-btn').click();
    await page.waitForFunction(() => document.querySelector('#recording-count').textContent === '1');
    await page.locator('#close-recordings-btn').click();
    await page.locator('#settings-btn').click();
    await page.locator('#logout-btn').click();
    await page.locator('#logout-dialog').waitFor({ state: 'visible' });
    await page.locator('#logout-cancel-btn').click();
    assert.equal(new URL(page.url()).pathname, '/', 'Canceling logout preserves the studio');
    assert.equal(await page.locator('#recording-count').textContent(), '1', 'Canceling logout preserves the unsaved take');
    if (await page.locator('body').getAttribute('data-mobile-panel') === 'settings') await page.locator('#close-settings-btn').click();
    console.log('PASS logout waits for manual capture stop and confirms before discarding an unsaved take');

    // Logging in elsewhere changes this same browser's cookies. The old tab
    // must keep its take/contents while blocking new captures under wrong
    // credentials. Automatically reloading could lose an unexported take.
    await signOut(context);
    await signIn(context, emailB);
    await checkChangedSession(page);
    await page.locator('#settings-btn').click();
    await page.locator('#account-session-warning').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#immersive-record-btn').isDisabled(), true, 'Account mismatch blocks new recordings');
    assert.equal(await page.locator('body').getAttribute('data-user-key'), keyA, 'An old tab never rewrites its storage owner');
    assert.equal(await page.locator('#script-input').inputValue(), scriptA, 'Existing unsaved contents survive an account change');
    assert.equal(await page.evaluate((key) => JSON.parse(localStorage.getItem(`open-script.studio.v1:user-${key}`))?.script, keyB),
      scriptB, 'The old tab cannot overwrite the other account script');
    await signOut(context);
    await checkChangedSession(page);
    await page.locator('#account-session-warning').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#immersive-record-btn').isDisabled(), true, 'An expired session also blocks new captures without losing data');
    await page.locator('#close-settings-btn').click();
    await page.locator('#immersive-recordings-btn').click();
    assert.equal(await page.locator('#recordings-list video').count(), 1, 'Session loss preserves the original take');
    await page.locator('.recording-card__export').first().click();
    await page.locator('#export-format').selectOption('original');
    const [download] = await Promise.all([page.waitForEvent('download'), page.locator('#export-submit-btn').click()]);
    assert.ok((await fs.stat(await download.path())).size > 1000, 'An expired session still allows exporting the preserved original');
    // A different tab can sign in to account B after A's session expires.
    // "Continue without login" must clear B's current authenticated session,
    // rather than reopen account B when the old tab requests the public URL.
    await signIn(context, emailB);
    await checkChangedSession(page);
    await page.locator('#close-export-btn').click();
    await page.locator('#close-recordings-btn').click();
    await page.locator('#settings-btn').click();
    await page.locator('#account-session-warning').waitFor({ state: 'visible' });
    await page.locator('#account-guest-link').click();
    await page.waitForFunction(() => !document.body.dataset.userKey
      && document.querySelector('#preview-stage')?.dataset.state === 'idle'
      && document.querySelector('#camera-video')?.readyState >= 2);
    assert.equal(new URL(page.url()).pathname, '/', 'Continuing without login returns to the public studio');
    const guestSession = await context.request.get(new URL('/accounts/session/', baseURL).href);
    assert.equal((await guestSession.json()).authenticated, false,
      'Continue without login clears another tab\'s authenticated session using the current CSRF token');
    assert.notEqual(await page.locator('#script-input').inputValue(), scriptA, 'Guest recovery never exposes account A script');
    assert.notEqual(await page.locator('#script-input').inputValue(), scriptB, 'Guest recovery never exposes account B script');
    assert.deepEqual(errors, [], 'No unhandled account or studio browser errors');
    console.log('PASS account changes and expired sessions preserve contents, and guest recovery clears a changed account session');
  } finally {
    await context.close();
    await browser.close();
  }
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
