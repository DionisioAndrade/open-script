/* Read-only UI checks use the isolated localhost:8001 server.
 * No recovery emails or external Google login requests are sent.
 * TEST_RESET_CONFIRM_URL optionally points to a valid disposable reset token
 * for that same isolated database; submitting a new password is not needed.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('playwright');
const { baseURL, signIn } = require('./helpers/auth.cjs');

const origin = new URL(baseURL);
assert.ok(['127.0.0.1', 'localhost'].includes(origin.hostname) && origin.port === '8001',
  'Recovery UI checks require the isolated localhost:8001 test server');

async function assertLayout(page, label) {
  const geometry = await page.evaluate(() => ({
    width: innerWidth, documentWidth: document.documentElement.scrollWidth,
    controls: [...document.querySelectorAll('button, .auth-submit, .auth-recovery-link')]
      .filter((element) => element.getClientRects().length).map((element) => {
        const rect = element.getBoundingClientRect();
        return { label: element.textContent.trim() || element.getAttribute('aria-label'),
          left: rect.left, right: rect.right, width: rect.width, height: rect.height };
      }),
  }));
  assert.ok(geometry.documentWidth <= geometry.width + 1, `${label}: no horizontal overflow`);
  for (const control of geometry.controls) {
    assert.ok(control.left >= -1 && control.right <= geometry.width + 1,
      `${label}: controls fit the screen ${JSON.stringify(control)}`);
    assert.ok(control.width >= 44 && control.height >= 44,
      `${label}: controls support touch ${JSON.stringify(control)}`);
  }
}

async function assertLanguage(page, language) {
  await page.selectOption('#language-select', language);
  assert.equal(await page.locator('html').getAttribute('lang'),
    { 'pt-BR': 'pt-BR', en: 'en-US', es: 'es-ES' }[language]);
  const content = await page.evaluate(() => ({
    missing: [...document.querySelectorAll('[data-i18n]')]
      .filter((element) => !window.StudioI18n.has(element.dataset.i18n, window.StudioI18n.language))
      .map((element) => element.dataset.i18n),
    languages: [...document.querySelectorAll('input[type="hidden"][name="lang"]')].map((input) => input.value),
  }));
  assert.deepEqual(content.missing, [], `${language}: displayed copy is translated`);
  assert.ok(content.languages.every((value) => value === language), `${language}: server receives the chosen language`);
}

async function run() {
  const directory = path.join(__dirname, 'screenshots');
  await fs.mkdir(directory, { recursive: true });
  const browser = await chromium.launch({
    executablePath: process.env.TEST_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true,
  });
  const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
  await context.addInitScript(() => { window.__mediaRequests = 0;
    const original = navigator.mediaDevices?.getUserMedia?.bind(navigator.mediaDevices);
    if (original) navigator.mediaDevices.getUserMedia = (...args) => { window.__mediaRequests++; return original(...args); };
  });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const pages = ['/accounts/login/', '/accounts/signup/', '/accounts/password-reset/',
    '/accounts/password-reset/done/', '/accounts/reset/invalid/invalid/', '/accounts/reset/complete/'];
  try {
    for (const pathname of pages) {
      const response = await page.goto(new URL(pathname, baseURL).href);
      assert.equal(response.status(), 200, `${pathname}: public page is available`);
      for (const language of ['pt-BR', 'en', 'es']) {
        await assertLanguage(page, language);
        for (const viewport of [{ width: 320, height: 680 }, { width: 390, height: 844 }, { width: 844, height: 390 }]) {
          await page.setViewportSize(viewport);
          await assertLayout(page, `${pathname}/${language}/${viewport.width}`);
        }
      }
      assert.equal(await page.evaluate(() => window.__mediaRequests), 0,
        'Account and recovery pages do not request camera or microphone access');
      assert.equal(await page.locator('script[src$="studio.js"], script[src$="exporter.js"]').count(), 0);
    }
    console.log('PASS public recovery pages in three languages and mobile portrait/landscape layouts');

    await page.setViewportSize({ width: 390, height: 844 });
    await page.goto(new URL('/accounts/login/?lang=en', baseURL).href);
    await page.locator('#id_username').fill('person@example.test');
    await page.locator('#id_password').fill('Typed-only-password!2026');
    await assertLanguage(page, 'es');
    assert.equal(await page.locator('#id_username').inputValue(), 'person@example.test');
    assert.equal(await page.locator('#id_password').inputValue(), 'Typed-only-password!2026');
    assert.match(await page.locator('.auth-recovery-link').getAttribute('href'), /lang=es/);
    await page.locator('.auth-recovery-link').click();
    assert.match(page.url(), /password-reset\/\?lang=es/);
    assert.equal(await page.locator('input[name="lang"]').inputValue(), 'es');
    await page.locator('#id_email').fill('person@example.test');
    await assertLanguage(page, 'en');
    assert.equal(await page.locator('#id_email').inputValue(), 'person@example.test');
    await page.screenshot({ path: path.join(directory, 'recovery-mobile.png'), fullPage: true });
    console.log('PASS recovery links and server language update without changing typed values');

    for (const pathname of ['/accounts/login/', '/accounts/signup/']) {
      await page.goto(new URL(pathname, baseURL).href);
      const google = page.locator('#google-signin-button');
      const unavailable = await google.getAttribute('data-unavailable') === 'true';
      assert.equal(await page.locator('form[action$="/google/login/"] input[name="csrfmiddlewaretoken"]').count(), 1,
        'Google entry uses a separate CSRF-protected POST form');
      assert.equal(await page.locator('form[action$="/google/login/"] input[type="password"]').count(), 0,
        'Google entry does not depend on password validation');
      if (unavailable) {
        assert.equal(await google.isDisabled(), true);
        for (const language of ['en', 'es', 'pt-BR']) await assertLanguage(page, language);
        await page.evaluate(() => window.dispatchEvent(new Event('pageshow')));
        assert.equal(await google.isDisabled(), true, 'Language changes and restored navigation keep Google unavailable');
        await page.locator('#google-signin-note').waitFor();
      }
      if (pathname.includes('signup')) {
        assert.equal(await page.locator('#signup-submit').isDisabled(), true);
        assert.equal(await page.locator('#id_accept_terms').isChecked(), false);
        assert.equal(await page.locator('#id_accept_privacy').isChecked(), false);
      }
    }
    await page.goto(new URL('/accounts/login/?lang=pt-BR', baseURL).href);
    await page.screenshot({ path: path.join(directory, 'login-recovery-google-mobile.png'), fullPage: true });
    console.log('PASS separate Google entry and preserved signup acceptance gating');

    if (process.env.TEST_RESET_CONFIRM_URL) {
      const confirmURL = new URL(process.env.TEST_RESET_CONFIRM_URL);
      assert.equal(confirmURL.origin, origin.origin, 'Reset UI fixture stays on the isolated server');
      await page.goto(confirmURL.href);
      const password = page.locator('#id_new_password1');
      await password.waitFor();
      await password.fill('Typed-only-password!2026');
      await page.locator('#id_new_password2').fill('Typed-only-password!2026');
      await page.locator('[data-password-target="id_new_password1"]').click();
      assert.equal(await password.getAttribute('type'), 'text');
      for (const language of ['pt-BR', 'en', 'es']) {
        await assertLanguage(page, language);
        assert.equal(await password.inputValue(), 'Typed-only-password!2026');
        await assertLayout(page, `new-password/${language}`);
      }
      await page.screenshot({ path: path.join(directory, 'new-password-mobile.png'), fullPage: true });
      console.log('PASS new-password controls, translation and preserved typed values');
    }
    // Sign-in updates last_login, invalidating previously issued reset tokens.
    // Check the optional token fixture before logging into the test account.
    const signedIn = await browser.newContext();
    try {
      await signIn(signedIn);
      const studio = await signedIn.request.get(new URL('/', baseURL).href);
      const markup = await studio.text();
      assert.equal(studio.status(), 200, 'Signed-in settings are available');
      const googleButton = markup.match(/<button[^>]+id="google-connect-btn"[^>]*>/)?.[0];
      assert.ok(googleButton, 'Google can be connected through account settings');
      if (googleButton.includes('data-available="false"')) {
        assert.match(googleButton, /\bdisabled\b/, 'Google connection stays unavailable until configured');
      }
      assert.match(markup, /id="google-connect-form"[^>]+method="post"/, 'Google connection starts with a POST');
    } finally { await signedIn.close(); }
    console.log('PASS Google connection availability in signed-in account settings');
    assert.deepEqual(errors, [], 'No browser JavaScript errors');
  } finally {
    await context.close();
    await browser.close();
  }
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
