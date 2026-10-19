/* Signup consent regressions use only the isolated localhost:8001 server.
 * Start it with DJANGO_DATABASE_PATH=tests/screenshots/login-test.sqlite3.
 * Restart that server between this suite and login.cjs to reset the ordinary
 * in-memory signup throttle. One disposable account is created here.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { randomUUID } = require('node:crypto');
const { chromium } = require('playwright');
const { baseURL, testPassword } = require('./helpers/auth.cjs');

const origin = new URL(baseURL);
assert.ok(['127.0.0.1', 'localhost'].includes(origin.hostname) && origin.port === '8001',
  'Consent regressions require the isolated localhost:8001 test server');
const signupURL = new URL('/accounts/signup/', baseURL).href;
const screenshotDirectory = path.join(__dirname, 'screenshots');

async function readDocument(page, kind, { accept = true } = {}) {
  assert.ok(['terms', 'privacy'].includes(kind));
  await page.locator(`#read-${kind}-link`).click();
  await page.locator('#legal-dialog[open]').waitFor({ state: 'visible' });
  await page.locator('#legal-dialog-content [data-legal-version]').waitFor();
  const scroll = page.locator('#legal-scroll');
  await scroll.evaluate((element) => {
    element.scrollTop = element.scrollHeight;
    element.dispatchEvent(new Event('scroll', { bubbles: true }));
  });
  await page.waitForFunction(() => !document.querySelector('#legal-read-button')?.disabled);
  const receipt = page.waitForResponse((response) => new URL(response.url()).pathname === '/accounts/legal-read/'
    && response.request().method() === 'POST');
  await page.locator('#legal-read-button').click();
  const response = await receipt;
  assert.equal(response.status(), 200, `${kind}: reading receipt is saved through the CSRF-protected endpoint`);
  assert.equal((await response.json()).read, true);
  await page.locator('#legal-dialog').waitFor({ state: 'hidden' });
  const checkbox = page.locator(`[name="accept_${kind}"]`);
  await page.waitForFunction((name) => document.querySelector(`[name="${name}"]`)?.dataset.legalRead === 'true', `accept_${kind}`);
  if (accept) await checkbox.setChecked(true);
}

async function acceptDocuments(page) {
  await readDocument(page, 'terms');
  await readDocument(page, 'privacy');
  assert.equal(await page.locator('#signup-submit').isDisabled(), false,
    'Signup becomes available only after reading and actively accepting both documents');
}

async function assertDialogLayout(page, label) {
  const geometry = await page.evaluate(() => {
    const dialog = document.getElementById('legal-dialog');
    const scroll = document.getElementById('legal-scroll');
    const rect = dialog.getBoundingClientRect();
    const buttons = [...dialog.querySelectorAll('button')].filter((button) => button.getClientRects().length);
    return { width: innerWidth, height: innerHeight, documentWidth: document.documentElement.scrollWidth,
      dialog: { x: rect.x, y: rect.y, right: rect.right, bottom: rect.bottom },
      scroll: { clientHeight: scroll.clientHeight, scrollHeight: scroll.scrollHeight },
      buttons: buttons.map((button) => {
        const box = button.getBoundingClientRect();
        const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
        return { id: button.id, x: box.x, y: box.y, right: box.right, bottom: box.bottom,
          width: box.width, height: box.height, hit: hit === button || button.contains(hit) };
      }) };
  });
  assert.ok(geometry.documentWidth <= geometry.width + 1, `${label}: no horizontal page overflow`);
  assert.ok(geometry.dialog.x >= -1 && geometry.dialog.right <= geometry.width + 1
    && geometry.dialog.y >= -1 && geometry.dialog.bottom <= geometry.height + 1,
  `${label}: legal dialog stays in the viewport ${JSON.stringify(geometry)}`);
  assert.ok(geometry.scroll.clientHeight > 70 && geometry.scroll.scrollHeight > geometry.scroll.clientHeight,
    `${label}: the document can scroll inside the dialog`);
  for (const button of geometry.buttons) {
    assert.ok(button.x >= -1 && button.right <= geometry.width + 1 && button.y >= -1
      && button.bottom <= geometry.height + 1 && button.hit, `${label}: controls remain reachable ${JSON.stringify(button)}`);
    assert.ok(button.width >= 44 && button.height >= 44, `${label}: controls have 44px touch targets`);
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
    window.__consentMediaRequests = 0;
    const native = navigator.mediaDevices?.getUserMedia?.bind(navigator.mediaDevices);
    if (native) navigator.mediaDevices.getUserMedia = (...args) => { window.__consentMediaRequests++; return native(...args); };
  });
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  try {
    for (const language of ['pt-BR', 'en', 'es']) {
      for (const kind of ['terms', 'privacy']) {
        const url = new URL(`/accounts/${kind}/`, baseURL);
        url.searchParams.set('lang', language);
        const response = await page.goto(url.href);
        assert.equal(response.status(), 200, `${kind}/${language}: document is public`);
        assert.equal(await page.locator('html').getAttribute('lang'),
          { 'pt-BR': 'pt-BR', en: 'en-US', es: 'es-ES' }[language]);
        assert.equal(await page.locator('script[src$="studio.js"], script[src$="exporter.js"], #camera-video').count(), 0,
          'Public legal pages load no camera or encoding code');
        assert.equal(await page.evaluate(() => window.__consentMediaRequests), 0, 'Legal pages never request media permission');
        const document = page.locator('[data-legal-version]').first();
        assert.ok((await document.innerText()).length > 1600, `${kind}/${language}: full readable document is present`);
        assert.ok(await document.locator('h2').count() >= 5, `${kind}/${language}: legal text has structured sections`);
        assert.match(await document.getAttribute('data-legal-version'), /\d{4}-\d{2}-\d{2}/);
        assert.match(await page.locator('h1').first().textContent(),
          { 'pt-BR': /Termos de uso|Política de privacidade/i,
            en: /Terms of use|Privacy policy/i, es: /Términos de uso|Política de privacidad/i }[language]);
        url.searchParams.set('fragment', '1');
        const fragment = await context.request.get(url.href);
        const markup = await fragment.text();
        assert.equal(fragment.status(), 200);
        assert.ok(!/<!doctype|<html|<script/i.test(markup), 'Dialog fragments contain document content only');
      }
    }
    console.log('PASS public Portuguese, English and Spanish legal documents with no media requests');

    await page.goto(signupURL);
    await page.locator('#language-select').selectOption('pt-BR');
    assert.equal(await page.locator('#signup-submit').isDisabled(), true);
    for (const kind of ['terms', 'privacy']) {
      const checkbox = page.locator(`[name="accept_${kind}"]`);
      assert.equal(await checkbox.isChecked(), false, 'Acceptance is never selected automatically');
      assert.equal(await checkbox.isDisabled(), true, 'Unread documents cannot be accepted');
    }
    const details = { name: 'Leitura preservada', email: `consent-form-${randomUUID()}@open-script.test`, password: testPassword };
    await page.locator('[name="display_name"]').fill(details.name);
    await page.locator('[name="email"]').fill(details.email);
    await page.locator('[name="password1"]').fill(details.password);
    await page.locator('[name="password2"]').fill(details.password);
    await page.locator('#read-terms-link').click();
    await page.locator('#legal-dialog-content [data-legal-version]').waitFor();
    assert.equal(await page.locator('#legal-read-button').isDisabled(), true, 'Opening a document does not register it as read');
    assert.equal(await page.locator('[name="accept_terms"]').isChecked(), false);
    await page.locator('#legal-close-button').click();
    assert.equal(await page.locator('[name="display_name"]').inputValue(), details.name);
    assert.equal(await page.locator('[name="email"]').inputValue(), details.email);
    assert.equal(await page.locator('[name="password1"]').inputValue(), details.password);
    assert.equal(await page.locator('[name="password2"]').inputValue(), details.password);
    assert.equal(await page.locator('#signup-submit').isDisabled(), true);
    assert.equal(await page.locator('[name="accept_terms"]').isDisabled(), true);

    for (const language of ['pt-BR', 'en', 'es']) {
      await page.locator('#language-select').selectOption(language);
      for (const viewport of [{ width: 320, height: 568 }, { width: 390, height: 844 }, { width: 844, height: 390 }]) {
        await page.setViewportSize(viewport);
        await page.locator('#read-privacy-link').click();
        await page.locator('#legal-dialog-content [data-legal-version]').waitFor();
        await assertDialogLayout(page, `privacy ${language} ${viewport.width}×${viewport.height}`);
        assert.equal(await page.locator('#legal-read-button').isDisabled(), true);
        if (language === 'pt-BR') await page.screenshot({
          path: path.join(screenshotDirectory, `consent-${viewport.width}x${viewport.height}.png`) });
        await page.locator('#legal-scroll').evaluate((element) => {
          element.scrollTop = element.scrollHeight;
          element.dispatchEvent(new Event('scroll', { bubbles: true }));
        });
        await page.waitForFunction(() => !document.querySelector('#legal-read-button')?.disabled);
        await assertDialogLayout(page, `privacy end ${language} ${viewport.width}×${viewport.height}`);
        await page.locator('#legal-close-button').click();
      }
    }
    await page.locator('#language-select').selectOption('pt-BR');
    await page.setViewportSize({ width: 390, height: 844 });
    await readDocument(page, 'terms');
    assert.equal(await page.locator('#signup-submit').isDisabled(), true, 'One read/accepted document cannot authorize signup');
    await readDocument(page, 'privacy', { accept: false });
    assert.equal(await page.locator('[name="accept_privacy"]').isChecked(), false,
      'Confirming reading still requires separate, active acceptance');
    assert.equal(await page.locator('#signup-submit').isDisabled(), true);
    await page.locator('[name="accept_privacy"]').setChecked(true);
    assert.equal(await page.locator('#signup-submit').isDisabled(), false);
    await page.locator('[name="accept_terms"]').setChecked(false);
    assert.equal(await page.locator('#signup-submit').isDisabled(), true, 'Revoking either acceptance blocks signup');
    await page.reload();
    assert.equal(await page.locator('[name="accept_terms"]').isChecked(), false, 'Reload does not silently select acceptance');
    assert.equal(await page.locator('[name="accept_privacy"]').isChecked(), false);
    console.log('PASS opening versus reading, two explicit acceptances, retained form fields and small-screen dialogs');

    const failureContext = await browser.newContext({ viewport: { width: 390, height: 844 } });
    try {
      const failurePage = await failureContext.newPage();
      await failurePage.goto(signupURL);
      await failurePage.route('**/accounts/terms/**', (route) => route.abort('failed'));
      await failurePage.locator('#read-terms-link').click();
      await failurePage.locator('#legal-load-retry').waitFor({ state: 'visible' });
      assert.equal(await failurePage.locator('#legal-read-button').isDisabled(), true,
        'A failed document download never counts as reading');
      assert.equal(await failurePage.locator('[name="accept_terms"]').isDisabled(), true);
      assert.ok((await failurePage.locator('#legal-read-status').textContent()).trim().length > 10,
        'Network failures display a readable explanation');
      await failurePage.unroute('**/accounts/terms/**');
      await failurePage.locator('#legal-load-retry').click();
      await failurePage.locator('#legal-dialog-content [data-legal-version]').waitFor();
      assert.equal(await failurePage.locator('#legal-read-button').isDisabled(), true);
      await failurePage.route('**/accounts/legal-read/', (route) => route.abort('failed'));
      await failurePage.locator('#legal-scroll').evaluate((element) => {
        element.scrollTop = element.scrollHeight;
        element.dispatchEvent(new Event('scroll', { bubbles: true }));
      });
      await failurePage.waitForFunction(() => !document.querySelector('#legal-read-button')?.disabled);
      await failurePage.locator('#legal-read-button').click();
      await failurePage.waitForFunction(() => /não|tente|conexão|unable|try|connection/i.test(
        document.querySelector('#legal-read-status')?.textContent || ''));
      assert.equal(await failurePage.locator('[name="accept_terms"]').isDisabled(), true,
        'A failed server receipt cannot enable acceptance');
      assert.equal(await failurePage.locator('#signup-submit').isDisabled(), true);
      await failurePage.unroute('**/accounts/legal-read/');
      const saved = failurePage.waitForResponse((response) => new URL(response.url()).pathname === '/accounts/legal-read/'
        && response.request().method() === 'POST');
      await failurePage.locator('#legal-read-button').click();
      assert.equal((await saved).status(), 200);
      await failurePage.locator('#legal-dialog').waitFor({ state: 'hidden' });
      assert.equal(await failurePage.locator('[name="accept_terms"]').isDisabled(), false,
        'A retried reading receipt enables the acceptance control');
      assert.equal(await failurePage.locator('[name="accept_terms"]').isChecked(), false,
        'Recovery never checks acceptance automatically');
    } finally { await failureContext.close(); }
    console.log('PASS failed document download or reading receipt remains blocked, with visible retry feedback');

    const bypassContext = await browser.newContext();
    try {
      const bypass = await bypassContext.newPage();
      await bypass.goto(signupURL);
      const payload = await bypass.evaluate(() => ({
        csrfmiddlewaretoken: document.querySelector('[name="csrfmiddlewaretoken"]').value,
        terms_version: document.querySelector('[name="terms_version"]').value,
        privacy_version: document.querySelector('[name="privacy_version"]').value,
      }));
      await bypassContext.request.post(signupURL, { form: { ...payload,
        display_name: 'Consentimento sem leitura', email: `consent-bypass-${randomUUID()}@open-script.test`,
        password1: testPassword, password2: testPassword, accept_terms: 'on', accept_privacy: 'on' },
      headers: { Referer: signupURL } });
      const session = await bypassContext.request.get(new URL('/accounts/session/', baseURL).href);
      assert.equal((await session.json()).authenticated, false,
        'Posting checkbox flags and current versions without session reading receipts cannot create an account');
      // A native POST exercises the visible error rendering when browser-side
      // disablement is bypassed. Missing flags are intentionally left absent.
      await bypass.locator('[name="display_name"]').fill('Sem aceite');
      await bypass.locator('[name="email"]').fill(`consent-missing-${randomUUID()}@open-script.test`);
      await bypass.locator('[name="password1"]').fill(testPassword);
      await bypass.locator('[name="password2"]').fill(testPassword);
      await bypass.locator('#signup-form').evaluate((form) => form.submit());
      await bypass.locator('#auth-errors').waitFor();
      assert.ok((await bypass.locator('.field-errors, #auth-errors').allTextContents()).join(' ').match(/leia|aceit|termos|privacidade/i),
        'Server-side consent errors remain visible after JavaScript gating is bypassed');
      assert.match(bypass.url(), /\/accounts\/signup\//);
      assert.equal(await bypass.locator('[name="password1"]').inputValue(), '', 'Rejected signup never echoes passwords');
    } finally { await bypassContext.close(); }
    console.log('PASS server rejects missing acceptance and forged client-side reading state');

    const noScriptContext = await browser.newContext({ javaScriptEnabled: false, viewport: { width: 390, height: 844 } });
    try {
      const noScript = await noScriptContext.newPage();
      await noScript.goto(signupURL);
      for (const kind of ['terms', 'privacy']) {
        await noScript.locator(`#read-${kind}-link`).click();
        assert.match(noScript.url(), new RegExp(`/accounts/${kind}/`));
        const confirmation = noScript.locator('form[action$="/accounts/legal-read/"]');
        await confirmation.locator('button[type="submit"]').click();
        await noScript.waitForURL(/\/accounts\/signup\//);
        assert.equal(await noScript.locator(`[name="accept_${kind}"]`).isDisabled(), false,
          'No-JavaScript reading confirmation enables active acceptance after returning to signup');
      }
      await noScript.locator('[name="display_name"]').fill('Leitura sem JavaScript');
      await noScript.locator('[name="email"]').fill(`consent-noscript-${randomUUID()}@open-script.test`);
      await noScript.locator('[name="password1"]').fill(testPassword);
      await noScript.locator('[name="password2"]').fill(testPassword);
      await noScript.locator('[name="accept_terms"]').setChecked(true);
      await noScript.locator('[name="accept_privacy"]').setChecked(true);
      await noScript.locator('#signup-submit').click();
      await noScript.waitForURL((url) => url.pathname === '/');
      const session = await noScriptContext.request.get(new URL('/accounts/session/', baseURL).href);
      assert.equal((await session.json()).authenticated, true, 'No-JavaScript fallback can create an account after both acceptances');
    } finally { await noScriptContext.close(); }
    assert.deepEqual(errors, [], 'Legal and signup pages have no unhandled browser errors');
    console.log('PASS public-document confirmation fallback and explicit signup consent without JavaScript');
  } catch (error) {
    await page.screenshot({ path: path.join(screenshotDirectory, 'consent-failure.png'), fullPage: true }).catch(() => {});
    throw error;
  } finally {
    await context.close();
    await browser.close();
  }
}

module.exports = { readDocument, acceptDocuments };
if (require.main === module) run().catch((error) => { console.error(error); process.exitCode = 1; });
