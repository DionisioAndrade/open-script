/* Browser regression authentication uses the public CSRF-protected forms.
 * Start Django with DJANGO_DATABASE_PATH=tests/screenshots/login-test.sqlite3
 * on port 8001 and seed the account below only in that isolated database.
 * TEST_EMAIL/TEST_PASSWORD can override those test-only credentials.
 */
const assert = require('node:assert/strict');

const baseURL = process.env.TEST_BASE_URL || 'http://127.0.0.1:8001';
const testEmail = process.env.TEST_EMAIL || 'regression@open-script.test';
const testPassword = process.env.TEST_PASSWORD || 'Test-only-Camera!2026-Regression';
const authenticatedBrowsers = new WeakMap();

async function signIn(context, email = testEmail, password = testPassword, remember = false) {
  const login = await context.request.get(new URL('/accounts/login/', baseURL).href);
  assert.equal(login.status(), 200, 'Login page is accessible');
  const markup = await login.text();
  const token = markup.match(/name=["']csrfmiddlewaretoken["'][^>]*value=["']([^"']+)/)?.[1];
  assert.ok(token, 'Login uses a Django CSRF token');
  const form = { csrfmiddlewaretoken: token, username: email, password };
  if (remember) form.remember_me = 'on';
  const response = await context.request.post(new URL('/accounts/login/', baseURL).href, {
    form, headers: { Referer: new URL('/accounts/login/', baseURL).href },
  });
  assert.equal(response.status(), 200, 'Normal login request succeeds');
  const session = await context.request.get(new URL('/accounts/session/', baseURL).href);
  const data = await session.json();
  assert.equal(data.authenticated, true, 'Regression user signs in through the public login form');
  assert.match(data.user_key, /^[0-9a-f-]{36}$/i, 'Session exposes the account storage UUID');
  return { userKey: data.user_key, storageState: await context.storageState() };
}

async function signOut(context) {
  const session = await context.request.get(new URL('/accounts/session/', baseURL).href);
  const data = await session.json();
  assert.equal(data.authenticated, true, 'Logout starts with a signed-in test account');
  assert.ok(data.csrf_token, 'Session supplies the normal Django CSRF token for logout');
  await context.request.post(new URL('/accounts/logout/', baseURL).href, {
    form: { csrfmiddlewaretoken: data.csrf_token },
    headers: { Referer: new URL('/', baseURL).href },
  });
  const next = await context.request.get(new URL('/accounts/session/', baseURL).href);
  assert.equal((await next.json()).authenticated, false, 'Normal POST logout invalidates the session');
}

async function authentication(browser) {
  if (!authenticatedBrowsers.has(browser)) {
    const pending = (async () => {
      const context = await browser.newContext();
      try { return await signIn(context); }
      finally { await context.close(); }
    })();
    authenticatedBrowsers.set(browser, pending);
  }
  return authenticatedBrowsers.get(browser);
}

async function newAuthenticatedContext(browser, options = {}) {
  const auth = await authentication(browser);
  return browser.newContext({ ...options, storageState: auth.storageState });
}

async function userKey(browser) {
  return (await authentication(browser)).userKey;
}

async function storageKey(browser, key) {
  return `${key}:user-${await userKey(browser)}`;
}

module.exports = { baseURL, testEmail, testPassword, signIn, signOut, newAuthenticatedContext, userKey, storageKey };
