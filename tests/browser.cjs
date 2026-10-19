/* Browser regression checks. Requires Playwright and an installed Edge/Chromium.
 * Start Django first, then run: node tests/browser.cjs
 * Set NODE_PATH to the Codex bundled node_modules when using its runtime.
 * Camera and microphone inputs are synthetic; no real device is accessed.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { chromium } = require('playwright');
const { baseURL, newAuthenticatedContext, storageKey } = require('./helpers/auth.cjs');

const browserPath = process.env.TEST_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outputDir = path.join(__dirname, 'screenshots');
const layoutOnly = process.argv.includes('--layout-only');
const mediaAccessOnly = process.argv.includes('--media-access-only');
const languageOnly = process.argv.includes('--language-only');

async function range(page, selector, value) {
  await page.locator(selector).evaluate((input, next) => {
    input.value = String(next);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }, value);
}

async function dragRange(page, selector, targetRatio) {
  const input = page.locator(selector);
  const box = await input.boundingBox();
  const ratio = await input.evaluate((element) => (Number(element.value) - Number(element.min)) / (Number(element.max) - Number(element.min)));
  const thumbInset = 10;
  const position = (fraction) => box.x + thumbInset + (box.width - 2 * thumbInset) * fraction;
  await page.mouse.move(position(ratio), box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(position(targetRatio), box.y + box.height / 2, { steps: 10 });
  await page.mouse.up();
}

async function visibility(page, hidden) {
  await page.evaluate((hide) => {
    if (hide) Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    else delete document.visibilityState;
    document.dispatchEvent(new Event('visibilitychange'));
  }, hidden);
}

async function assertImmersiveLayout(page, name) {
  const layout = await page.evaluate(() => {
    const rect = (selector) => {
      const element = document.querySelector(selector);
      const box = element.getBoundingClientRect();
      const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
      return { x: box.x, y: box.y, width: box.width, height: box.height, right: box.right, bottom: box.bottom,
        receivesPointer: !!hit && (hit === element || element.contains(hit)),
        visible: getComputedStyle(element).display !== 'none' && box.width > 0 && box.height > 0 };
    };
    return {
      width: innerWidth, height: innerHeight, scrollWidth: document.documentElement.scrollWidth,
      stage: rect('#preview-stage'), camera: rect('#camera-video'), prompt: rect('#prompt-overlay'),
      cameraFit: getComputedStyle(document.querySelector('#camera-video')).objectFit,
      speed: rect('#immersive-speed-input'), progress: rect('#immersive-prompt-progress'),
      controls: ['#immersive-record-btn', '#immersive-pause-btn', '#immersive-stop-btn',
        '#immersive-prompt-play-btn', '#immersive-reset-btn', '#immersive-camera-btn',
        '#editor-btn', '#settings-btn', '#immersive-recordings-btn', '#immersive-fullscreen-btn'].map(rect),
    };
  });
  assert.ok(layout.scrollWidth <= layout.width + 1, `${name}: no horizontal overflow`);
  assert.ok(Math.abs(layout.stage.x) <= 1 && Math.abs(layout.stage.y) <= 1, `${name}: camera stage starts at viewport origin`);
  assert.ok(Math.abs(layout.stage.width - layout.width) <= 1 && Math.abs(layout.stage.height - layout.height) <= 1,
    `${name}: camera stage fills viewport, ${JSON.stringify(layout.stage)}`);
  assert.ok(layout.prompt.visible, `${name}: the script remains visible over the camera`);
  assert.ok(Math.abs(layout.camera.x) <= 1 && Math.abs(layout.camera.y) <= 1
    && Math.abs(layout.camera.width - layout.width) <= 1 && Math.abs(layout.camera.height - layout.height) <= 1,
    `${name}: the live camera fills the entire viewport`);
  assert.equal(layout.cameraFit, 'contain', `${name}: the live preview shows the entire camera field without zoom or stretching`);
  assert.ok(layout.prompt.x >= -1 && layout.prompt.y >= -1 && layout.prompt.right <= layout.width + 1
    && layout.prompt.bottom <= layout.height + 1, `${name}: script lies within camera viewport`);
  assert.ok(layout.prompt.width >= layout.width * 0.6 && layout.prompt.height >= layout.height * 0.45,
    `${name}: the transparent script occupies most of the camera`);
  assert.ok(layout.speed.visible && layout.progress.visible, `${name}: quick speed and position controls remain visible`);
  assert.ok(layout.speed.receivesPointer && layout.progress.receivesPointer,
    `${name}: quick speed and position controls accept pointer input`);
  for (const control of layout.controls) {
    assert.ok(control.visible, `${name}: recording and editing controls remain visible`);
    assert.ok(control.width >= 44 && control.height >= 44, `${name}: touch control is at least 44px, ${JSON.stringify(control)}`);
    assert.ok(control.x >= -1 && control.y >= -1 && control.right <= layout.width + 1
      && control.bottom <= layout.height + 1, `${name}: touch control stays within viewport`);
    assert.ok(control.receivesPointer, `${name}: control is not covered by another element`);
  }
}

async function openPanel(page, name) {
  await page.locator(name === 'script' ? '#editor-btn' : '#settings-btn').click();
  await page.waitForFunction((panel) => document.body.dataset.mobilePanel === panel, name);
}

async function closePanel(page, name) {
  await page.locator(name === 'script' ? '#close-script-btn' : '#close-settings-btn').click();
  await page.waitForFunction(() => !document.body.dataset.mobilePanel);
}

async function decodeVideo(page, source) {
  return page.evaluate(async ({ url, data, mimeType }) => {
    const blob = url ? await fetch(url).then((response) => response.blob())
      : new Blob([Uint8Array.from(atob(data), (character) => character.charCodeAt(0))], { type: mimeType });
    const objectURL = URL.createObjectURL(blob);
    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.preload = 'auto';
    video.src = objectURL;
    try {
      await new Promise((resolve, reject) => {
        video.addEventListener('loadedmetadata', resolve, { once: true });
        video.addEventListener('error', () => reject(new Error('Exported video metadata failed')), { once: true });
      });
      if (!Number.isFinite(video.duration)) {
        await new Promise((resolve) => {
          video.addEventListener('timeupdate', resolve, { once: true });
          video.currentTime = 1e10;
          setTimeout(resolve, 1500);
        });
      }
      const duration = video.duration;
      video.currentTime = 0;
      await video.play();
      await new Promise((resolve) => setTimeout(resolve, 200));
      video.pause();
      const canvas = document.createElement('canvas');
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      const context = canvas.getContext('2d');
      context.drawImage(video, 0, 0);
      const samples = [0.05, 0.5, 0.95].map((x) => Array.from(context.getImageData(
        Math.floor(x * canvas.width), Math.floor(canvas.height / 2), 1, 1).data));
      const bars = [0.05, 0.95].map((y) => Array.from(context.getImageData(
        Math.floor(canvas.width / 2), Math.floor(y * canvas.height), 1, 1).data));
      const metadata = new TextDecoder('latin1').decode(await blob.arrayBuffer());
      return { bytes: blob.size, mimeType: blob.type, duration, width: video.videoWidth, height: video.videoHeight,
        samples, bars, containsAudio: metadata.includes('A_OPUS') || metadata.includes('mp4a') || metadata.includes('Opus') };
    } finally {
      video.pause();
      video.removeAttribute('src');
      video.load();
      URL.revokeObjectURL(objectURL);
    }
  }, source);
}

function assertFullCameraFrame(video, description) {
  const expected = [[254, 32, 16], [32, 68, 240], [16, 239, 32]];
  for (const [index, pixel] of video.samples.entries()) {
    assert.ok(expected[index].every((channel, position) => Math.abs(pixel[position] - channel) < 25),
      `${description}: left, center and right camera bands are preserved without the script: ${JSON.stringify(video.samples)}`);
  }
  for (const pixel of video.bars) {
    assert.ok([15, 23, 42].every((channel, position) => Math.abs(pixel[position] - channel) < 20),
      `${description}: proportional fitting uses navy space instead of cropping or stretching: ${JSON.stringify(pixel)}`);
  }
}

async function saveDownload(download) {
  const directory = path.join(__dirname, 'downloads');
  await fs.mkdir(directory, { recursive: true });
  const filename = download.suggestedFilename();
  const file = path.join(directory, path.basename(filename));
  await download.saveAs(file);
  const bytes = await fs.readFile(file);
  assert.ok(bytes.length > 1000, 'the exported file contains video data');
  return { filename, bytes };
}

async function assertExportModal(page, name) {
  const layout = await page.locator('#export-dialog').evaluate((dialog) => {
    const box = dialog.getBoundingClientRect();
    return { open: dialog.open, x: box.x, y: box.y, right: box.right, bottom: box.bottom,
      width: innerWidth, height: innerHeight, scrollWidth: dialog.scrollWidth, clientWidth: dialog.clientWidth };
  });
  assert.ok(layout.open && layout.x >= -1 && layout.y >= -1 && layout.right <= layout.width + 1
    && layout.bottom <= layout.height + 1, `${name}: export dialog fits the viewport`);
  assert.ok(layout.scrollWidth <= layout.clientWidth + 1, `${name}: export dialog has no horizontal overflow`);
  for (const selector of ['#export-filename', '#export-format', '#export-resolution', '#export-quality',
    '#export-framerate', '#export-submit-btn', '#close-export-btn']) {
    const control = page.locator(selector);
    await control.scrollIntoViewIfNeeded();
    const usable = await control.evaluate((element) => {
      const box = element.getBoundingClientRect();
      const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
      return { width: box.width, height: box.height, pointer: !!hit && (hit === element || element.contains(hit)),
        onscreen: box.x >= -1 && box.y >= -1 && box.right <= innerWidth + 1 && box.bottom <= innerHeight + 1 };
    });
    assert.ok(usable.width >= 44 && usable.height >= 44 && usable.pointer && usable.onscreen,
      `${name}: ${selector} is reachable and usable by touch: ${JSON.stringify(usable)}`);
  }
  await page.locator('#export-filename').focus();
  await page.locator('#immersive-record-btn').focus();
  assert.equal(await page.locator('#export-dialog').evaluate((dialog) => dialog.contains(document.activeElement)), true,
    `${name}: the background cannot steal focus from the modal`);
  for (let index = 0; index < 10; index += 1) {
    await page.keyboard.press('Tab');
    // Edge briefly reports BODY while Tab visits browser chrome. Native modal
    // semantics must keep focus out of the studio controls behind the dialog.
    assert.equal(await page.locator('#export-dialog').evaluate((dialog) => document.activeElement === document.body
      || dialog.contains(document.activeElement)), true, `${name}: keyboard focus cannot enter the background`);
  }
}

async function assertHTTPMediaAccess(browser, errors) {
  const availableContext = await newAuthenticatedContext(browser, { viewport: { width: 390, height: 844 } });
  await availableContext.addInitScript(() => {
    // Some test browser configurations expose capture APIs on HTTP. The app
    // must test those capabilities instead of imposing its own HTTPS gate.
    Object.defineProperty(window, 'isSecureContext', { configurable: true, value: false });
    const native = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    window.__httpMediaRequests = [];
    navigator.mediaDevices.getUserMedia = async (constraints) => {
      window.__httpMediaRequests.push(constraints);
      return native(constraints);
    };
  });
  try {
    const available = await availableContext.newPage();
    available.on('pageerror', (error) => errors.push(error.message));
    await available.goto(baseURL);
    await available.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'idle'
      && document.querySelector('#camera-video').readyState >= 2);
    const capture = await available.evaluate(() => ({
      secure: window.isSecureContext,
      requests: window.__httpMediaRequests,
      tracks: document.querySelector('#camera-video').srcObject.getTracks().map((track) => ({ kind: track.kind, state: track.readyState })),
    }));
    assert.equal(capture.secure, false);
    assert.ok(capture.requests.some((request) => request.video), 'HTTP capture requests camera when its API is available');
    assert.ok(capture.requests.some((request) => request.audio), 'HTTP capture requests microphone when its API is available');
    assert.deepEqual(capture.tracks.map((track) => track.kind).sort(), ['audio', 'video']);
    assert.ok(capture.tracks.every((track) => track.state === 'live'));
    assert.equal(await available.locator('#immersive-record-btn').isEnabled(), true,
      'an insecure-context flag alone does not disable available recording controls');
    await available.locator('#immersive-record-btn').click();
    await available.locator('#countdown-overlay').waitFor({ state: 'visible' });
    await available.locator('#immersive-stop-btn').click();
    await available.locator('#countdown-overlay').waitFor({ state: 'hidden' });
    assert.equal(await available.locator('#recording-count').textContent(), '0');
  } finally {
    await availableContext.close();
  }
  console.log('PASS HTTP context with available media APIs requests camera and microphone and allows recording controls');

  const unavailableContext = await newAuthenticatedContext(browser, { viewport: { width: 390, height: 844 } });
  await unavailableContext.addInitScript(() => {
    Object.defineProperty(window, 'isSecureContext', { configurable: true, value: false });
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: undefined });
  });
  try {
    const unavailable = await unavailableContext.newPage();
    unavailable.on('pageerror', (error) => errors.push(error.message));
    await unavailable.goto(baseURL);
    await unavailable.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'idle'
      && document.querySelector('#camera-placeholder').classList.contains('has-camera-issue'));
    const explanation = await unavailable.locator('#camera-placeholder-message').textContent();
    assert.match(explanation, /navegador/i, 'the unavailable API is explained as a browser limitation');
    assert.match(explanation, /câmera|microfone/i);
    assert.match(explanation, /localhost|Chrome|Edge|teste|configura/i, 'the message offers an actionable test option');
    assert.equal(await unavailable.locator('#immersive-record-btn').isEnabled(), false,
      'recording is unavailable when the browser provides no camera API');
    await unavailable.locator('#immersive-prompt-play-btn').click();
    await unavailable.waitForFunction(() => Number(document.querySelector('#prompt-progress').value) > 0);
    await unavailable.locator('#immersive-prompt-play-btn').click();
    await unavailable.locator('#immersive-reset-btn').click();
    assert.equal(await unavailable.locator('#prompt-progress').inputValue(), '0');
  } finally {
    await unavailableContext.close();
  }
  console.log('PASS HTTP browser without media APIs displays an actionable explanation and still supports rehearsal');
}

async function assertOptionalZoom(browser, errors) {
  for (const rejectZoom of [false, true]) {
    const context = await newAuthenticatedContext(browser, { viewport: { width: 390, height: 844 } });
    await context.addInitScript(({ reject }) => {
      const native = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      window.__optionalZoom = [];
      navigator.mediaDevices.getUserMedia = async (constraints) => {
        const stream = await native(constraints);
        for (const track of stream.getVideoTracks()) {
          // A camera may expose only a range above 1×, or reject a supported
          // optional constraint. Neither case may disable capture or audio.
          const settings = track.getSettings.bind(track);
          let zoom = 3;
          track.getCapabilities = () => ({ zoom: { min: 2, max: 4, step: 0.1 } });
          track.getSettings = () => ({ ...settings(), zoom });
          track.applyConstraints = async (next) => {
            const requested = next.zoom ?? next.advanced?.find((item) => item.zoom !== undefined)?.zoom;
            window.__optionalZoom.push(requested);
            if (reject) throw new DOMException('Optional zoom unavailable', 'OverconstrainedError');
            zoom = typeof requested === 'object' ? requested.exact ?? requested.ideal : requested;
          };
        }
        return stream;
      };
    }, { reject: rejectZoom });
    try {
      const page = await context.newPage();
      page.on('pageerror', (error) => errors.push(error.message));
      await page.goto(baseURL);
      await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'idle'
        && document.querySelector('#camera-video').readyState >= 2);
      const camera = await page.locator('#camera-video').evaluate((video) => ({
        tracks: video.srcObject.getTracks().map((track) => ({ kind: track.kind, state: track.readyState })),
        zoom: video.srcObject.getVideoTracks()[0].getSettings().zoom,
        attempts: window.__optionalZoom,
      }));
      assert.deepEqual(camera.attempts, [2], 'natural zoom is clamped to the closest supported camera setting');
      assert.equal(camera.zoom, rejectZoom ? 3 : 2);
      assert.deepEqual(camera.tracks.map((track) => track.kind).sort(), ['audio', 'video']);
      assert.ok(camera.tracks.every((track) => track.state === 'live'), 'optional zoom failures retain live camera and microphone');
      assert.equal(await page.locator('#immersive-record-btn').isEnabled(), true);
    } finally {
      await context.close();
    }
  }
  console.log('PASS physical zoom honors camera limits and optional zoom rejection retains camera and microphone');
}

async function assertNativeFrameFallback(browser, errors) {
  const context = await newAuthenticatedContext(browser, { viewport: { width: 390, height: 844 } });
  await context.addInitScript(() => {
    const native = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    const supported = navigator.mediaDevices.getSupportedConstraints.bind(navigator.mediaDevices);
    navigator.mediaDevices.getSupportedConstraints = () => ({ ...supported(), resizeMode: true });
    window.__frameRequests = [];
    navigator.mediaDevices.getUserMedia = async (constraints) => {
      window.__frameRequests.push(structuredClone(constraints));
      if (constraints.video?.resizeMode) {
        const error = new DOMException('Native resize mode unsupported by this device', 'OverconstrainedError');
        Object.defineProperty(error, 'constraint', { value: 'resizeMode' });
        throw error;
      }
      return native(constraints);
    };
  });
  try {
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(baseURL);
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'idle'
      && document.querySelector('#camera-video').readyState >= 2);
    const requests = await page.evaluate(() => window.__frameRequests.filter((request) => request.video));
    assert.equal(requests.length, 2, 'a device that cannot provide native resizing is retried once');
    assert.deepEqual(requests[0].video.resizeMode, { exact: 'none' });
    assert.equal(requests[1].video.resizeMode, undefined);
    const { resizeMode, ...otherConstraints } = requests[0].video;
    assert.deepEqual(requests[1].video, otherConstraints, 'fallback retains the requested camera and sensor resolution');
    const tracks = await page.locator('#camera-video').evaluate((video) => video.srcObject.getTracks()
      .map((track) => ({ kind: track.kind, state: track.readyState })));
    assert.deepEqual(tracks.map((track) => track.kind).sort(), ['audio', 'video']);
    assert.ok(tracks.every((track) => track.state === 'live'));
    assert.equal(await page.locator('#immersive-record-btn').isEnabled(), true);
  } finally {
    await context.close();
  }
  console.log('PASS unsupported native resize mode retries without losing camera, sensor resolution or audio');
}

async function assertLanguageCatalog(page) {
  const sources = new Set(JSON.parse(await fs.readFile(path.join(__dirname, 'language-dynamic-keys.json'), 'utf8')));
  for (const filename of ['studio.js', 'exporter.js']) {
    const content = await fs.readFile(path.join(__dirname, '..', 'studio', 'static', 'studio', filename), 'utf8');
    // Read only string-literal calls, including template placeholders in their
    // source form. Coverage checks target user-facing text rather than copying
    // hundreds of translated strings into another catalog inside this test.
    const calls = content.matchAll(/\b(?:t|localizedError)\(\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')/g);
    for (const match of calls) {
      sources.add(match[1][0] === '"' ? JSON.parse(match[1]) : match[1].slice(1, -1).replace(/\\'/g, "'"));
    }
  }
  const missing = await page.evaluate((dynamic) => {
    const sources = new Set(dynamic);
    for (const profile of window.StudioExportProfiles?.profiles || []) {
      for (const key of ['label', 'note', 'advice', 'limitLabel', 'minimumLabel']) {
        if (profile[key]) sources.add(profile[key]);
      }
    }
    for (const element of document.querySelectorAll('*')) {
      for (const attribute of element.attributes) {
        if (attribute.name === 'data-i18n' || attribute.name.startsWith('data-i18n-')) sources.add(attribute.value);
      }
    }
    return ['en', 'es'].flatMap((language) => [...sources]
      .filter((source) => !window.StudioI18n.has(source, language)).map((source) => ({ language, source })));
  }, [...sources]);
  assert.deepEqual(missing, [], 'all marked interface text and literal dynamic templates have English and Spanish translations');
}

async function showLanguageSelect(page) {
  // Account controls now lead Settings; the remaining controls are available
  // by scrolling the sheet. Wait for its opening animation before measuring.
  await page.locator('#settings-panel').evaluate(async (panel) => {
    await Promise.all(panel.getAnimations().map((animation) => animation.finished.catch(() => {})));
  });
  await page.locator('#language-select').evaluate((select) => {
    select.scrollIntoView({ block: 'center', behavior: 'instant' });
  });
}

async function selectLanguage(page, language) {
  if (!(await page.locator('body').getAttribute('data-mobile-panel'))) await openPanel(page, 'settings');
  await showLanguageSelect(page);
  await page.locator('#language-select').selectOption(language);
  const locale = { 'pt-BR': 'pt-BR', en: 'en-US', es: 'es-ES' }[language];
  await page.waitForFunction((value) => document.documentElement.lang === value, locale);
}

async function assertAppLanguages(browser, errors) {
  const script = 'Meu roteiro pessoal, unchanged — texto de teste.\n\n' + 'Hola, hello e olá. '.repeat(80);
  const savedPreferences = { version: 2, script, title: 'Meu título / My title / Mi título', speed: 73,
    font: 'serif', size: 44, autoSize: false, opacity: 0, color: '#b8efd5', showPrompt: true, mirror: false };
  const context = await newAuthenticatedContext(browser, { viewport: { width: 390, height: 844 }, acceptDownloads: true });
  const studioKey = await storageKey(browser, 'open-script.studio.v1');
  const languageKey = await storageKey(browser, 'open-script.language.v1');
  await context.addInitScript(({ saved, studioKey }) => {
    if (!localStorage.getItem(studioKey)) localStorage.setItem(studioKey, JSON.stringify(saved));
    window.__languageRecorders = [];
    const NativeRecorder = window.MediaRecorder;
    window.MediaRecorder = class extends NativeRecorder {
      constructor(...args) {
        super(...args);
        const report = { recorder: this, events: [] };
        window.__languageRecorders.push(report);
        for (const name of ['start', 'pause', 'resume', 'stop']) this.addEventListener(name, () => report.events.push(name));
      }
    };
  }, { saved: savedPreferences, studioKey });
  try {
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(baseURL);
    await page.waitForFunction(() => window.StudioI18n && document.querySelector('#camera-video').readyState >= 2
      && document.querySelector('#preview-stage').dataset.state === 'idle');
    await assertLanguageCatalog(page);
    assert.equal(await page.locator('html').getAttribute('lang'), 'pt-BR');
    await selectLanguage(page, 'en');
    assert.equal((await page.locator('#settings-heading').textContent()).trim(), 'Settings');
    assert.match(await page.locator('#editor-btn').textContent(), /edit script/i);
    assert.match(await page.title(), /recording studio/i);
    assert.match(await page.locator('label[for="language-select"]').textContent(), /language/i);
    assert.match(await page.locator('#settings-btn').getAttribute('aria-label'), /settings/i);
    assert.equal(await page.locator('#script-input').inputValue(), script);
    assert.equal(await page.locator('#script-title').inputValue(), savedPreferences.title);
    await selectLanguage(page, 'es');
    assert.equal((await page.locator('#settings-heading').textContent()).trim(), 'Configuración');
    assert.match(await page.locator('#editor-btn').textContent(), /editar guion/i);
    assert.match(await page.title(), /estudio de grabación/i);
    await closePanel(page, 'settings');
    assert.deepEqual(await page.evaluate((key) => JSON.parse(localStorage.getItem(key)), studioKey), savedPreferences,
      'changing language preserves the saved user script, title and camera/reading preferences');
    await page.reload();
    await page.waitForFunction(() => document.querySelector('#camera-video').readyState >= 2
      && document.querySelector('#preview-stage').dataset.state === 'idle');
    assert.equal(await page.locator('html').getAttribute('lang'), 'es-ES', 'language survives a reload');
    assert.equal(await page.locator('#language-select').inputValue(), 'es');
    assert.equal(await page.locator('#script-input').inputValue(), script, 'reloading a translated interface preserves the script');
    assert.equal(await page.locator('#speed-input').inputValue(), '73');
    assert.equal(await page.locator('#size-input').inputValue(), '44');
    await selectLanguage(page, 'pt-BR');
    assert.equal((await page.locator('#settings-heading').textContent()).trim(), 'Configurações');
    await selectLanguage(page, 'en');
    await closePanel(page, 'settings');
    await page.locator('#immersive-recordings-btn').click();
    assert.match(await page.locator('#recordings-heading').textContent(), /recordings/i);
    assert.match(await page.locator('#recordings-empty').textContent(), /first take/i);
    await page.locator('#close-recordings-btn').click();
    console.log('PASS language changes translate the interface immediately and persist without modifying user content or preferences');

    for (const size of [{ width: 320, height: 844 }, { width: 390, height: 844 }, { width: 844, height: 390 }]) {
      await page.setViewportSize(size);
      await assertImmersiveLayout(page, `${size.width}×${size.height} English interface`);
      await openPanel(page, 'settings');
      await showLanguageSelect(page);
      const accessible = await page.locator('#language-select').evaluate((select) => {
        const box = select.getBoundingClientRect();
        const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
        return { width: box.width, height: box.height,
          fits: box.x >= 0 && box.y >= 0 && box.right <= innerWidth && box.bottom <= innerHeight,
          pointer: hit === select, overflow: document.documentElement.scrollWidth > innerWidth + 1 };
      });
      assert.ok(accessible.width >= 44 && accessible.height >= 44 && accessible.fits && accessible.pointer && !accessible.overflow,
        `${size.width}×${size.height}: language selection is visible and usable on phones: ${JSON.stringify(accessible)}`);
      await page.screenshot({ path: path.join(outputDir, `language-settings-${size.width}x${size.height}.png`) });
      await closePanel(page, 'settings');
    }
    console.log('PASS translated controls and language settings fit narrow phones and landscape');

    // A language switch must update live labels without resetting or replacing
    // an active MediaRecorder. Capture one short take; no re-encoding is needed.
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator('#immersive-record-btn').click();
    await page.waitForFunction(() => window.__languageRecorders[0]?.recorder.state === 'recording');
    await page.waitForTimeout(1100);
    await selectLanguage(page, 'es');
    await closePanel(page, 'settings');
    assert.match(await page.locator('#immersive-pause-btn').getAttribute('aria-label'), /pausar/i);
    assert.equal(await page.evaluate(() => window.__languageRecorders.length), 1, 'changing language retains the same recorder');
    assert.equal(await page.evaluate(() => window.__languageRecorders[0].recorder.state), 'recording');
    await page.locator('#immersive-pause-btn').click();
    await page.waitForFunction(() => window.__languageRecorders[0].recorder.state === 'paused');
    await selectLanguage(page, 'en');
    await closePanel(page, 'settings');
    assert.match(await page.locator('#immersive-pause-btn').getAttribute('aria-label'), /resume/i);
    assert.equal(await page.evaluate(() => window.__languageRecorders.length), 1);
    assert.equal(await page.evaluate(() => window.__languageRecorders[0].recorder.state), 'paused');
    await page.locator('#immersive-pause-btn').click();
    await page.waitForFunction(() => window.__languageRecorders[0].recorder.state === 'recording');
    await page.locator('#immersive-stop-btn').click();
    await page.waitForFunction(() => document.querySelector('#recording-count').textContent === '1');
    if (!(await page.locator('#recordings-dialog').evaluate((dialog) => dialog.open))) await page.locator('#immersive-recordings-btn').click();
    const originalURL = await page.locator('#recordings-list video').first().getAttribute('src');
    const originalBytes = await page.evaluate(async (url) => (await fetch(url).then((response) => response.blob())).size, originalURL);
    assert.ok(originalBytes > 1000);
    assert.deepEqual(await page.evaluate(() => window.__languageRecorders[0].events), ['start', 'pause', 'resume', 'stop']);
    await page.locator('button.recording-card__export').first().click();
    await page.locator('#export-filename').fill('Meu arquivo personalizado');
    await page.locator('#export-resolution').selectOption('360');
    await page.evaluate(() => window.StudioI18n.setLanguage('es'));
    assert.match(await page.locator('#export-heading').textContent(), /exportar v[ií]deo/i);
    assert.match(await page.locator('label[for="export-filename"]').textContent(), /nombre del archivo/i);
    assert.match(await page.locator('#export-quality option[value="balanced"]').textContent(), /equilibrada/i);
    assert.equal(await page.locator('#export-filename').inputValue(), 'Meu arquivo personalizado', 'live translation preserves a custom export filename');
    assert.equal(await page.locator('#export-resolution').inputValue(), '360', 'live translation preserves export options');
    assert.ok((await page.locator('#export-recording-title').textContent()).includes(savedPreferences.title));
    assert.equal(await page.locator('#recordings-list video').first().getAttribute('src'), originalURL, 'translation preserves the source URL');
    assert.equal(await page.evaluate(async (url) => (await fetch(url).then((response) => response.blob())).size, originalURL), originalBytes);
    await page.evaluate(() => {
      const native = window.StudioExporter;
      window.StudioExporter = { ...native, exportVideo: async () => { throw new Error('O navegador não conseguiu iniciar a exportação.'); } };
    });
    await page.locator('#export-submit-btn').click();
    await page.waitForFunction(() => !document.querySelector('#export-submit-btn').disabled);
    assert.match(await page.locator('#export-status').textContent(), /navegador.*exportaci[oó]n/i, 'export failures are localized');
    assert.doesNotMatch(await page.locator('#export-status').textContent(), /não conseguiu/i);
    await page.locator('#close-export-btn').click();
    await page.locator('#close-recordings-btn').click();
    console.log('PASS live language changes preserve recording/pause state and original clips, export options and filenames; errors are localized');
  } finally {
    await context.close();
  }

  const deniedContext = await newAuthenticatedContext(browser, { viewport: { width: 390, height: 844 } });
  await deniedContext.addInitScript((languageKey) => {
    localStorage.setItem(languageKey, 'en');
    navigator.mediaDevices.getUserMedia = async () => { throw new DOMException('Permission denied in test', 'NotAllowedError'); };
  }, languageKey);
  try {
    const denied = await deniedContext.newPage();
    denied.on('pageerror', (error) => errors.push(error.message));
    await denied.goto(baseURL);
    await denied.waitForFunction(() => document.querySelector('#camera-placeholder').classList.contains('has-camera-issue'));
    assert.match(await denied.locator('#camera-placeholder-message').textContent(), /camera.*block|block.*camera/i);
    assert.match(await denied.locator('#camera-placeholder-btn').textContent(), /try again/i);
    await selectLanguage(denied, 'es');
    await closePanel(denied, 'settings');
    assert.match(await denied.locator('#camera-placeholder-message').textContent(), /c[aá]mara.*bloque|bloque.*c[aá]mara/i);
    assert.match(await denied.locator('#camera-placeholder-btn').textContent(), /intentar de nuevo/i);
  } finally {
    await deniedContext.close();
  }
  console.log('PASS camera permission failures and retry instructions use the selected language');

  for (const storageUnavailable of [false, true]) {
    const fallbackContext = await newAuthenticatedContext(browser);
    await fallbackContext.addInitScript(({ unavailable, languageKey }) => {
      if (unavailable) {
        for (const method of ['getItem', 'setItem']) Storage.prototype[method] = () => { throw new DOMException('Blocked in test', 'SecurityError'); };
      } else localStorage.setItem(languageKey, 'unsupported-value');
    }, { unavailable: storageUnavailable, languageKey });
    try {
      const fallback = await fallbackContext.newPage();
      fallback.on('pageerror', (error) => errors.push(error.message));
      await fallback.goto(baseURL);
      await fallback.waitForFunction(() => window.StudioI18n && document.querySelector('#preview-stage').dataset.state === 'idle');
      assert.equal(await fallback.locator('html').getAttribute('lang'), 'pt-BR', 'unknown or unavailable saved language defaults to Portuguese');
      await selectLanguage(fallback, 'en');
      assert.equal((await fallback.locator('#settings-heading').textContent()).trim(), 'Settings', 'language switching works even without storage');
    } finally {
      await fallbackContext.close();
    }
  }
  console.log('PASS unsupported saved languages and blocked local storage do not prevent language selection');
}

async function main() {
  await fs.mkdir(outputDir, { recursive: true });
  const browser = await chromium.launch({
    executablePath: browserPath,
    headless: true,
    args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'],
  });
  const errors = [];
  try {
    if (languageOnly || (!layoutOnly && !mediaAccessOnly)) await assertAppLanguages(browser, errors);
    if (languageOnly) {
      assert.deepEqual(errors, [], 'no unhandled browser errors');
      console.log('All app language checks passed.');
      return;
    }
    await assertHTTPMediaAccess(browser, errors);
    await assertOptionalZoom(browser, errors);
    await assertNativeFrameFallback(browser, errors);
    if (mediaAccessOnly) {
      assert.deepEqual(errors, [], 'no unhandled browser errors');
      console.log('All camera and microphone access checks passed.');
      return;
    }
    const context = await newAuthenticatedContext(browser, { viewport: { width: 1440, height: 1040 }, acceptDownloads: true });
    await context.addInitScript(() => {
      window.__captureRecorders = [];
      window.__exportRecorders = [];
      window.__cropDraws = [];
      window.__mediaRequests = [];
      window.__cameraZoomRequests = [];
      const nativeDraw = CanvasRenderingContext2D.prototype.drawImage;
      CanvasRenderingContext2D.prototype.drawImage = function(source, ...args) {
        if (source.id === 'camera-video') window.__cropDraws.push({ args, width: this.canvas.width, height: this.canvas.height });
        return nativeDraw.call(this, source, ...args);
      };
      // Deliberately supply a landscape source even when the application asks
      // for portrait. All three bands must survive natural framing; missing
      // sides reveal cropping and distortion reveals disproportionate scaling.
      const nativeMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      navigator.mediaDevices.getUserMedia = async (constraints) => {
        window.__mediaRequests.push(constraints);
        const native = await nativeMedia(constraints);
        if (!constraints.video) return native;
        const canvas = document.createElement('canvas');
        canvas.width = 1280;
        canvas.height = 720;
        const paint = () => {
          const ctx = canvas.getContext('2d');
          ctx.fillStyle = '#fe2010'; ctx.fillRect(0, 0, 1280, 720);
          ctx.fillStyle = '#10ef20'; ctx.fillRect(842.5, 0, 437.5, 720);
          ctx.fillStyle = '#2044f0'; ctx.fillRect(437.5, 0, 405, 720);
        };
        paint();
        const timer = setInterval(paint, 1000 / 30);
        const synthetic = canvas.captureStream(30);
        native.getVideoTracks().forEach((track) => track.stop());
        native.getAudioTracks().forEach((track) => synthetic.addTrack(track));
        const track = synthetic.getVideoTracks()[0];
        const nativeSettings = track.getSettings.bind(track);
        let zoom = 2;
        track.getCapabilities = () => ({ zoom: { min: 1, max: 4, step: 0.1 } });
        track.getSettings = () => ({ ...nativeSettings(), zoom });
        track.applyConstraints = async (next) => {
          window.__cameraZoomRequests.push(next);
          const requested = next.zoom ?? next.advanced?.find((item) => item.zoom !== undefined)?.zoom;
          if (requested !== undefined) zoom = typeof requested === 'object' ? requested.exact ?? requested.ideal : requested;
        };
        track.addEventListener('ended', () => clearInterval(timer), { once: true });
        return synthetic;
      };
      const NativeRecorder = window.MediaRecorder;
      if (NativeRecorder) {
        window.MediaRecorder = class extends NativeRecorder {
          constructor(...args) {
            super(...args);
            const report = { events: [], chunks: [], recorder: this, input: args[0], options: args[1],
              video: args[0].getVideoTracks()[0]?.getSettings() };
            const reports = document.querySelector('#export-dialog')?.open ? window.__exportRecorders : window.__captureRecorders;
            reports.push(report);
            for (const type of ['start', 'pause', 'resume', 'stop', 'error']) {
              this.addEventListener(type, () => report.events.push({ type, time: performance.now() }));
            }
            this.addEventListener('dataavailable', (event) => report.chunks.push(event.data.size));
          }
        };
      }
    });
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(baseURL);
    await page.waitForFunction(() => document.querySelector('#word-count').textContent !== '0');
    await page.waitForFunction(() => {
      const video = document.querySelector('#camera-video');
      return video.srcObject && video.readyState >= 2 && document.querySelector('#preview-stage').dataset.state === 'idle';
    });
    assert.equal(await page.title(), 'OpenScript — seu estúdio de gravação');
    const initialRequests = await page.evaluate(() => window.__mediaRequests);
    assert.ok(initialRequests.some((request) => request.video), 'opening the studio requests camera authorization without a click');
    assert.ok(initialRequests.some((request) => request.audio), 'opening the studio requests microphone authorization without a click');
    assert.ok(initialRequests.some((request) => request.video?.resizeMode?.exact === 'none'),
      'camera acquisition requests the native field of view instead of a browser crop');
    assert.equal(await page.locator('#camera-video').evaluate((video) => video.srcObject.getVideoTracks()[0].getSettings().zoom), 1,
      'a supported physical zoom starts at the natural 1× setting');
    assert.ok((await page.evaluate(() => window.__cameraZoomRequests)).length > 0,
      'the natural zoom constraint reaches the camera track');
    const initialTracks = await page.locator('#camera-video').evaluate((video) => video.srcObject.getTracks().map((track) => ({ kind: track.kind, state: track.readyState })));
    assert.ok(initialTracks.some((track) => track.kind === 'video' && track.state === 'live'));
    assert.ok(initialTracks.some((track) => track.kind === 'audio' && track.state === 'live'));
    assert.equal(await page.evaluate(() => window.__captureRecorders.length), 0, 'authorizing devices does not start recording');
    assert.equal(await page.locator('#countdown-overlay').isVisible(), false, 'authorizing devices does not start the countdown');
    console.log('PASS opening the studio requests camera and microphone and shows a live preview without recording');
    await page.waitForTimeout(1000);
    assert.equal(await page.locator('.studio-layout').evaluate((layout) => getComputedStyle(layout).opacity), '1');
    await assertImmersiveLayout(page, '1440×1040 default camera layout');
    assert.equal(await page.locator('#opacity-input').inputValue(), '0', 'a fresh script window is transparent');
    await page.screenshot({ path: path.join(outputDir, 'desktop.png'), fullPage: true });

    const script = 'Meu roteiro de teste.\n\n' + 'Uma frase para conferir a leitura sobre a câmera. '.repeat(35);
    if (!layoutOnly) {
    await openPanel(page, 'script');
    await page.locator('#script-input').fill(script);
    await page.locator('#script-title').fill('Meu take de teste');
    await closePanel(page, 'script');
    await openPanel(page, 'settings');
    await range(page, '#speed-input', 73);
    await range(page, '#size-input', 44);
    await range(page, '#opacity-input', 0);
    await page.locator('#font-select').selectOption('serif');
    await page.locator('#text-color').evaluate((input) => {
      input.value = '#b8efd5';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await closePanel(page, 'settings');
    await page.waitForTimeout(700);
    await page.reload();
    await page.waitForFunction(() => document.querySelector('#word-count').textContent !== '0');
    await page.waitForFunction(() => document.querySelector('#camera-video').readyState >= 2
      && document.querySelector('#preview-stage').dataset.state === 'idle');
    assert.equal(await page.locator('#script-input').inputValue(), script);
    assert.equal(await page.locator('#script-title').inputValue(), 'Meu take de teste');
    assert.equal(await page.locator('#speed-input').inputValue(), '73');
    assert.equal(await page.locator('#size-input').inputValue(), '44');
    assert.equal(await page.locator('#opacity-input').inputValue(), '0');
    assert.equal(await page.locator('#font-select').inputValue(), 'serif');
    assert.equal(await page.locator('#text-color').inputValue(), '#b8efd5');
    assert.ok((await page.locator('#prompt-text').textContent()).includes('Meu roteiro de teste.'));
    assert.equal(await page.locator('#prompt-text').evaluate((text) => getComputedStyle(text).fontSize), '44px');
    assert.equal(await page.locator('#prompt-overlay').evaluate((overlay) => getComputedStyle(overlay).backgroundColor), 'rgba(15, 23, 42, 0)');
    assert.equal(await page.locator('#immersive-speed-input').inputValue(), '73');
    assert.match(await page.locator('#immersive-speed-value').textContent(), /73/);
    await dragRange(page, '#immersive-speed-input', 0.4);
    const draggedSpeed = await page.locator('#immersive-speed-input').inputValue();
    assert.notEqual(draggedSpeed, '73', 'dragging the visible speed thumb changes speed');
    assert.equal(await page.locator('#speed-input').inputValue(), draggedSpeed);
    await range(page, '#immersive-speed-input', 61);
    assert.equal(await page.locator('#speed-input').inputValue(), '61', 'quick speed updates the appearance panel');
    assert.match(await page.locator('#speed-value').textContent(), /61/);
    await range(page, '#immersive-speed-input', 73);
    await dragRange(page, '#immersive-prompt-progress', 0.5);
    assert.ok(Number(await page.locator('#immersive-prompt-progress').inputValue()) > 30,
      'dragging the visible position thumb advances the script');
    await range(page, '#immersive-prompt-progress', 35);
    assert.ok(Math.abs(Number(await page.locator('#prompt-progress').inputValue()) - 35) < 0.2,
      'quick position slider updates the shared reading position');
    assert.match(await page.locator('#immersive-progress-label').textContent(), /35/);
    const readingRemaining = await page.locator('#immersive-reading-time').textContent();
    assert.match(readingRemaining, /\d.*\d/, 'remaining reading time is shown above the live camera');
    await page.locator('#immersive-reset-btn').click();
    console.log('PASS script and appearance persist across reload');

    await page.locator('#immersive-prompt-play-btn').click();
    await page.waitForFunction(() => Number(document.querySelector('#prompt-progress').value) > 0);
    assert.ok(Number(await page.locator('#immersive-prompt-progress').inputValue()) > 0,
      'rehearsal moves the visible position control');
    assert.equal(await page.locator('#camera-placeholder').evaluate((placeholder) => placeholder.hidden), true);
    await page.locator('#immersive-prompt-play-btn').click();
    await page.locator('#immersive-reset-btn').click();
    assert.equal(await page.locator('#prompt-progress').inputValue(), '0');
    assert.equal(await page.locator('#immersive-prompt-progress').inputValue(), '0');
    console.log('PASS rehearsal scrolls and resets independently of recording');

    await page.waitForFunction(() => {
      const video = document.querySelector('#camera-video');
      return video.srcObject && video.readyState >= 2;
    });
    const tracks = await page.locator('#camera-video').evaluate((video) => video.srcObject.getTracks().map((t) => ({ kind: t.kind, state: t.readyState })));
    assert.ok(tracks.some((t) => t.kind === 'video' && t.state === 'live'));
    assert.ok(tracks.some((t) => t.kind === 'audio' && t.state === 'live'));
    await page.waitForTimeout(1000);
    assert.equal(await page.locator('.studio-layout').evaluate((layout) => getComputedStyle(layout).opacity), '1');
    await page.screenshot({ path: path.join(outputDir, 'desktop-camera.png'), fullPage: true });

    await page.locator('#immersive-record-btn').click();
    await page.locator('#countdown-overlay').waitFor({ state: 'visible' });
    await page.waitForTimeout(1100);
    await page.locator('#immersive-stop-btn').click();
    await page.locator('#countdown-overlay').waitFor({ state: 'hidden' });
    await page.waitForTimeout(4400);
    assert.equal(await page.locator('#recording-count').textContent(), '0');
    assert.equal(await page.evaluate(() => window.__captureRecorders.filter((r) => r.events.some((e) => e.type === 'start')).length), 0);
    console.log('PASS cancelled countdown never starts a recorder');

    const startedAt = Date.now();
    await page.locator('#immersive-record-btn').click();
    await page.locator('#countdown-overlay').waitFor({ state: 'visible' });
    assert.equal((await page.locator('#countdown-number').textContent()).trim(), '5');
    await page.waitForTimeout(4300);
    assert.equal(await page.evaluate(() => window.__captureRecorders.filter((r) => r.events.some((e) => e.type === 'start')).length), 0);
    await page.waitForFunction(() => window.__captureRecorders.some((r) => r.events.some((e) => e.type === 'start')));
    assert.ok(Date.now() - startedAt >= 4700, 'recording must wait for the five-second countdown');
    const recordingInput = await page.evaluate(() => {
      const recorder = window.__captureRecorders.at(-1);
      const source = document.querySelector('#camera-video').srcObject;
      return { source: source.getVideoTracks()[0].getSettings(), video: recorder.input.getVideoTracks()[0].getSettings(),
        sourceAudio: source.getAudioTracks().map((track) => ({ state: track.readyState, settings: track.getSettings() })),
        recordingAudio: recorder.input.getAudioTracks().map((track) => ({ state: track.readyState, settings: track.getSettings() })) };
    });
    assert.equal(recordingInput.source.width, 1280);
    assert.equal(recordingInput.source.height, 720);
    assert.equal(recordingInput.video.width, 1080, 'the original captures at 1080px wide despite landscape source');
    assert.equal(recordingInput.video.height, 1920, 'the original captures at 1920px high despite landscape source');
    assert.deepEqual(recordingInput.recordingAudio, recordingInput.sourceAudio, 'portrait processing preserves live microphone audio settings');
    assert.equal(recordingInput.recordingAudio.length, 1);
    await page.waitForTimeout(2200);
    await page.locator('#immersive-pause-btn').click();
    await page.waitForFunction(() => window.__captureRecorders.at(-1).recorder.state === 'paused' && document.querySelector('#preview-stage').dataset.state === 'paused');
    const pausePosition = await page.locator('#prompt-progress').inputValue();
    const pauseTime = await page.locator('#recording-time').textContent();
    assert.equal(await page.locator('#immersive-recording-time').textContent(), pauseTime,
      'the visible recording timer stays in sync');
    await page.waitForTimeout(2200);
    assert.equal(await page.locator('#prompt-progress').inputValue(), pausePosition, 'teleprompter must remain still while paused');
    assert.equal(await page.locator('#recording-time').textContent(), pauseTime, 'recording timer must exclude pause');
    assert.equal(await page.locator('#immersive-recording-time').textContent(), pauseTime,
      'the visible recording timer excludes paused time');
    assert.equal(await page.locator('#camera-video').evaluate((video) => video.srcObject.getVideoTracks()[0].readyState), 'live');
    await page.locator('#immersive-pause-btn').click();
    await page.waitForFunction(() => window.__captureRecorders.at(-1).recorder.state === 'recording' && document.querySelector('#preview-stage').dataset.state === 'recording');
    await page.waitForTimeout(2200);
    const displayedTime = await page.locator('#recording-time').textContent();
    const displayedSeconds = displayedTime.split(':').reduce((seconds, part) => seconds * 60 + Number(part), 0);
    assert.ok(displayedSeconds >= 3 && displayedSeconds <= 5, `timer excludes paused interval: ${displayedTime}`);
    await page.locator('#immersive-stop-btn').click();
    await page.waitForFunction(() => document.querySelector('#recording-count').textContent === '1');
    const recording = await page.evaluate(() => {
      const r = window.__captureRecorders.at(-1);
      return { events: r.events, chunks: r.chunks, mimeType: r.recorder.mimeType };
    });
    assert.deepEqual(recording.events.map((e) => e.type), ['start', 'pause', 'resume', 'stop']);
    assert.ok(recording.chunks.reduce((sum, n) => sum + n, 0) > 1000);
    assert.equal(await page.locator('#camera-video').evaluate((video) => video.srcObject.getAudioTracks()[0].readyState), 'live',
      'saving a portrait take preserves the microphone for subsequent takes');
    console.log('PASS real synthetic recording, pause/resume, timer and text exclusion');

    if (!(await page.locator('#recordings-dialog').evaluate((dialog) => dialog.open))) {
      await page.locator('#immersive-recordings-btn').click();
    }
    await page.locator('#recordings-dialog').waitFor({ state: 'visible' });
    const recordedVideo = page.locator('#recordings-list video').first();
    await recordedVideo.waitFor({ state: 'visible' });
    const originalURL = await recordedVideo.getAttribute('src');
    const clip = await decodeVideo(page, { url: originalURL });
    assert.ok(clip.bytes > 1000);
    assert.equal(clip.width, 1080, 'the preserved original decodes at 1080px wide');
    assert.equal(clip.height, 1920, 'the preserved original decodes at 1920px high');
    assert.ok(clip.containsAudio, 'the original includes an audio codec track');
    assertFullCameraFrame(clip, '1080×1920 original');
    const crop = await page.evaluate(() => window.__cropDraws.at(-1));
    assert.equal(crop.width, 1080); assert.equal(crop.height, 1920);
    const draw = crop.args.length === 8 ? crop.args : [0, 0, 1280, 720, ...crop.args];
    assert.deepEqual(draw.slice(0, 4), [0, 0, 1280, 720], 'the full source frame reaches the recorder');
    assert.deepEqual(draw.slice(4), [0, 656.25, 1080, 607.5], 'source fits the vertical canvas without zoom or distortion');
    // Native WebM files may legitimately omit duration metadata. Where the
    // decoder resolves it, verify the paused segment is excluded from the clip.
    if (Number.isFinite(clip.duration)) {
      assert.ok(clip.duration > 3.5 && clip.duration < 5.8, `video duration excludes 2.2 second pause: ${clip.duration}`);
    }
    assert.equal(await page.locator('#recordings-list a[download]').count(), 0,
      'recordings offer export settings instead of a direct download');
    const originalHash = await page.evaluate(async (url) => {
      const blob = await fetch(url).then((response) => response.blob());
      const hash = await crypto.subtle.digest('SHA-256', await blob.arrayBuffer());
      return Array.from(new Uint8Array(hash), (byte) => byte.toString(16).padStart(2, '0')).join('');
    }, originalURL);
    await page.locator('button.recording-card__export').first().click();
    await page.locator('#export-dialog').waitFor({ state: 'visible' });
    const format = await page.locator('#export-format').inputValue();
    assert.match(format, /^(mp4|webm)$/, 'a supported, convertible format is selected by default');
    assert.equal(await page.locator('#export-resolution').inputValue(), '720');
    assert.equal(await page.locator('#export-quality').inputValue(), 'balanced');
    assert.equal(await page.locator('#export-framerate').inputValue(), '30');
    assert.equal(await page.locator('#export-preview video').count(), 1, 'export shows the selected take');
    for (const size of [{ width: 390, height: 844 }, { width: 320, height: 844 }]) {
      await page.setViewportSize(size);
      await assertExportModal(page, `${size.width}×${size.height}`);
      await page.screenshot({ path: path.join(outputDir, `export-${size.width}x${size.height}.png`) });
    }
    await page.setViewportSize({ width: 1440, height: 1040 });
    await page.locator('#export-filename').fill('take-publicar');
    const [download] = await Promise.all([
      page.waitForEvent('download', { timeout: 30000 }), page.locator('#export-submit-btn').click(),
    ]);
    const exported = await saveDownload(download);
    assert.equal(exported.filename, `take-publicar.${format}`, 'the selected filename and real format are used');
    const exportRecorder = await page.evaluate(() => {
      const report = window.__exportRecorders.at(-1);
      return { mimeType: report.recorder.mimeType, video: report.video,
        bitrate: report.recorder.videoBitsPerSecond, events: report.events.map((event) => event.type) };
    });
    assert.equal(exportRecorder.video.width, 720);
    assert.equal(exportRecorder.video.height, 1280);
    assert.equal(exportRecorder.video.frameRate, 30, 'the requested frame rate reaches the encoder');
    assert.deepEqual(exportRecorder.events, ['start', 'stop']);
    assert.match(exportRecorder.mimeType, new RegExp(`^video/${format}`), 'the encoder uses the selected format');
    const output = await decodeVideo(page, { data: exported.bytes.toString('base64'), mimeType: exportRecorder.mimeType });
    assert.equal(output.width, 720, 'the exported file decodes at the selected width');
    assert.equal(output.height, 1280, 'the exported file decodes at the selected height');
    assert.ok(output.containsAudio, 'conversion preserves the recorded microphone track');
    assertFullCameraFrame(output, '720×1280 export');
    assert.ok(output.duration > 3.5 && output.duration < 6,
      `the exported duration preserves the take and excludes the capture pause: ${output.duration}`);
    await page.screenshot({ path: path.join(outputDir, 'export-complete.png') });
    await page.locator('#close-export-btn').click();
    console.log('PASS real export with custom filename, actual 720×1280 audio and format; metadata', JSON.stringify(output));

    // Cancel an actual conversion, then export the same original again. This
    // exposes revoked source URLs, deleted takes and incomplete-file downloads.
    await page.locator('button.recording-card__export').first().click();
    await page.locator('#export-resolution').selectOption('360');
    await page.locator('#export-quality').selectOption('compact');
    await page.locator('#export-framerate').selectOption('24');
    const cancelledDownloads = [];
    const onCancelledDownload = (item) => cancelledDownloads.push(item);
    page.on('download', onCancelledDownload);
    await page.locator('#export-submit-btn').click();
    await page.locator('#export-cancel-btn').waitFor({ state: 'visible' });
    await page.waitForFunction(() => window.__exportRecorders.at(-1).recorder.state === 'recording');
    await page.waitForTimeout(200);
    await page.locator('#export-cancel-btn').click();
    await page.waitForFunction(() => !document.querySelector('#export-submit-btn').disabled);
    await page.waitForTimeout(500);
    page.removeListener('download', onCancelledDownload);
    assert.equal(cancelledDownloads.length, 0, 'cancelling a conversion never downloads an incomplete file');
    assert.equal(await page.locator('#recording-count').textContent(), '1', 'cancellation retains the take');
    assert.equal(await recordedVideo.getAttribute('src'), originalURL, 'cancellation retains the original video URL');
    assert.equal(await page.evaluate(async (url) => (await fetch(url).then((response) => response.blob())).size, originalURL), clip.bytes,
      'the original remains readable after cancellation');
    if (!(await page.locator('#export-dialog').evaluate((dialog) => dialog.open))) {
      await page.locator('button.recording-card__export').first().click();
    }
    await page.locator('#export-format').selectOption('webm');
    await page.locator('#export-resolution').selectOption('360');
    await page.locator('#export-quality').selectOption('compact');
    await page.locator('#export-framerate').selectOption('24');
    await page.locator('#export-filename').fill('take-leve');
    const [compactDownload] = await Promise.all([
      page.waitForEvent('download', { timeout: 30000 }), page.locator('#export-submit-btn').click(),
    ]);
    const compact = await saveDownload(compactDownload);
    assert.equal(compact.filename, 'take-leve.webm');
    const compactRecorder = await page.evaluate(() => {
      const report = window.__exportRecorders.at(-1);
      return { mimeType: report.recorder.mimeType, video: report.video,
        bitrate: report.recorder.videoBitsPerSecond };
    });
    assert.equal(compactRecorder.video.frameRate, 24, 'the alternate frame rate reaches the encoder');
    assert.ok(compactRecorder.bitrate < exportRecorder.bitrate, 'compact export uses a lower encoding bitrate');
    const compactOutput = await decodeVideo(page, { data: compact.bytes.toString('base64'), mimeType: compactRecorder.mimeType });
    assert.equal(compactOutput.width, 360, 'the alternative resolution changes the actual exported file');
    assert.equal(compactOutput.height, 640);
    assert.ok(compactOutput.containsAudio, 'the compact export retains microphone audio');
    assertFullCameraFrame(compactOutput, '360×640 export');
    await page.locator('#close-export-btn').click();
    console.log('PASS cancelled export preserves the original and can be retried as 360×640 24fps compact WebM');

    await page.locator('button.recording-card__export').first().click();
    await page.locator('#export-format').selectOption('original');
    for (const selector of ['#export-resolution', '#export-quality', '#export-framerate']) {
      assert.equal(await page.locator(selector).isDisabled(), true, 'original format clearly disables conversion settings');
    }
    await page.locator('#export-filename').fill('take-original');
    const [originalDownload] = await Promise.all([
      page.waitForEvent('download'), page.locator('#export-submit-btn').click(),
    ]);
    const savedOriginal = await saveDownload(originalDownload);
    assert.equal(createHash('sha256').update(savedOriginal.bytes).digest('hex'), originalHash,
      'Original exports the intact source bytes without another lossy conversion');
    assert.match(savedOriginal.filename, /^take-original\.(mp4|webm)$/);
    await page.locator('#close-export-btn').click();
    await page.screenshot({ path: path.join(outputDir, 'recordings.png'), fullPage: true });
    await page.locator('#close-recordings-btn').click();
    console.log('PASS Original preserves the captured file byte for byte');

    await page.locator('#immersive-record-btn').click();
    await page.waitForFunction(() => window.__captureRecorders.filter((r) => r.events.some((e) => e.type === 'start')).length === 2);
    await page.waitForTimeout(1200);
    await visibility(page, true);
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'paused');
    await visibility(page, false);
    assert.equal(await page.locator('#preview-stage').getAttribute('data-state'), 'paused', 'returning to the page requires an explicit resume');
    await page.locator('#immersive-stop-btn').click();
    await page.waitForFunction(() => document.querySelector('#recording-count').textContent === '2');
    assert.equal(await page.locator('#recordings-list video').count(), 2, 'a later take preserves the previous recording');
    const secondEvents = await page.evaluate(() => window.__captureRecorders.at(-1).events.map((e) => e.type));
    assert.deepEqual(secondEvents, ['start', 'pause', 'stop']);
    await page.locator('#close-recordings-btn').click();
    console.log('PASS background pause requires manual resume; stopping while paused preserves earlier takes');

    // Reopening the camera must not destroy saved takes.
    await page.locator('#immersive-camera-btn').click();
    await page.waitForFunction(() => document.querySelector('#camera-video').srcObject === null);
    await page.locator('#immersive-camera-btn').click();
    await page.waitForFunction(() => document.querySelector('#camera-video').srcObject?.getVideoTracks()[0].readyState === 'live');
    assert.equal(await page.locator('#recording-count').textContent(), '2');
    } else {
      await openPanel(page, 'script');
      await page.locator('#script-input').fill(script);
      await closePanel(page, 'script');
      await openPanel(page, 'settings');
      await range(page, '#size-input', 44);
      await closePanel(page, 'settings');
      await page.waitForFunction(() => document.querySelector('#camera-video').srcObject?.getVideoTracks()[0].readyState === 'live');
    }
    const expectedRecordings = layoutOnly ? 0 : 2;

    for (const size of [{ width: 390, height: 844 }, { width: 320, height: 844 },
      { width: 844, height: 390 }, { width: 1024, height: 768 }, { width: 1440, height: 900 }]) {
      await page.setViewportSize(size);
      await page.waitForTimeout(120);
      await assertImmersiveLayout(page, `${size.width}×${size.height}`);
      assert.equal(await page.locator('#size-input').inputValue(), '44', 'resizing preserves a chosen text size');
      await page.screenshot({ path: path.join(outputDir, `mobile-${size.width}x${size.height}.png`), fullPage: true });
      await page.locator('#editor-btn').click();
      assert.equal(await page.locator('body').getAttribute('data-mobile-panel'), 'script');
      await page.locator('#script-input').fill(script);
      await page.locator('#close-script-btn').click();
      assert.ok(!(await page.locator('body').getAttribute('data-mobile-panel')));
      await page.locator('#settings-btn').click();
      assert.equal(await page.locator('body').getAttribute('data-mobile-panel'), 'settings');
      await range(page, '#speed-input', 55);
      assert.equal(await page.locator('#immersive-speed-input').inputValue(), '55', 'settings speed updates the visible quick slider');
      await page.locator('#close-settings-btn').click();
      assert.ok(!(await page.locator('body').getAttribute('data-mobile-panel')));
      await page.locator('#immersive-prompt-play-btn').click();
      await page.waitForFunction(() => Number(document.querySelector('#prompt-progress').value) > 0);
      await page.locator('#immersive-prompt-play-btn').click();
      await page.locator('#immersive-reset-btn').click();
      assert.equal(await page.locator('#prompt-progress').inputValue(), '0');
      await page.locator('#settings-btn').click();
      await page.keyboard.press('Escape');
      assert.ok(!(await page.locator('body').getAttribute('data-mobile-panel')), 'Escape closes settings');
    }
    // Exercise the visible mobile transport instead of only its desktop twin.
    await page.setViewportSize({ width: 390, height: 844 });
    await page.evaluate(() => { document.documentElement.requestFullscreen = undefined; });
    await page.locator('#immersive-record-btn').click();
    await page.locator('#countdown-overlay').waitFor({ state: 'visible' });
    assert.ok(!(await page.locator('body').getAttribute('data-mobile-panel')), 'sheets close before countdown');
    await page.locator('#immersive-stop-btn').click();
    await page.locator('#countdown-overlay').waitFor({ state: 'hidden' });
    assert.equal(await page.locator('#recording-count').textContent(), String(expectedRecordings));
    await page.locator('#immersive-record-btn').click();
    await page.locator('#countdown-overlay').waitFor({ state: 'visible' });
    await visibility(page, true);
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'idle');
    await visibility(page, false);
    assert.equal(await page.evaluate(() => window.__captureRecorders.filter((r) => r.events.some((e) => e.type === 'start')).length), expectedRecordings,
      'backgrounded countdown cannot start an unseen recording');
    console.log('PASS mobile camera viewport, touch controls, sheets, rehearsal and cancelled countdown');

    // Native full screen must expand the entire document so transport buttons
    // and dialogs are retained alongside the camera.
    await page.setViewportSize({ width: 1440, height: 1040 });
    await page.locator('#immersive-fullscreen-btn').click();
    await page.waitForFunction(() => document.body.classList.contains('is-immersive'));
    assert.equal(await page.evaluate(() => document.fullscreenElement), null);
    await assertImmersiveLayout(page, 'unsupported-API full screen fallback');
    await page.locator('#immersive-fullscreen-btn').click();
    await page.waitForFunction(() => !document.body.classList.contains('is-immersive'));
    await page.evaluate(() => { delete document.documentElement.requestFullscreen; });
    await page.locator('#immersive-fullscreen-btn').click();
    await page.waitForFunction(() => document.fullscreenElement === document.documentElement);
    await assertImmersiveLayout(page, 'native full screen');
    await page.locator('#immersive-fullscreen-btn').click();
    await page.waitForFunction(() => !document.fullscreenElement && !document.body.classList.contains('is-immersive'));
    console.log('PASS native full screen and unsupported-API full screen fallback');
    await context.close();

    const deniedContext = await newAuthenticatedContext(browser, { viewport: { width: 1440, height: 1040 } });
    await deniedContext.addInitScript(() => {
      const native = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      window.__permissionRequests = [];
      Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
        value: async (constraints) => {
          window.__permissionRequests.push(constraints);
          if (window.__permissionRequests.length === 1) throw new DOMException('Permission denied for browser regression check', 'NotAllowedError');
          return native(constraints);
        },
      });
    });
    const denied = await deniedContext.newPage();
    denied.on('pageerror', (error) => errors.push(error.message));
    await denied.goto(baseURL);
    await denied.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'idle'
      && /bloque/i.test(document.querySelector('#status-message').textContent));
    const errorText = await denied.locator('#status-message').textContent();
    assert.match(errorText, /permiss|permit|acesso|bloque/i);
    assert.equal(await denied.locator('#camera-btn').isEnabled(), true, 'permission failure must allow retry');
    assert.equal(await denied.locator('#camera-placeholder').isVisible(), true, 'the permission error has a visible camera retry prompt');
    assert.match(await denied.locator('#camera-placeholder-message').textContent(), /câmera.*bloque|bloque.*câmera/i);
    assert.match(await denied.locator('#camera-placeholder-btn').textContent(), /tentar novamente/i);
    await denied.screenshot({ path: path.join(outputDir, 'permission-error.png'), fullPage: true });
    // The persistent reason and retry remain usable after the temporary toast
    // has disappeared, including short phone screens in landscape.
    await denied.evaluate(() => { document.querySelector('#status-message').hidden = true; });
    for (const size of [{ width: 390, height: 844 }, { width: 740, height: 390 }, { width: 568, height: 320 }]) {
      await denied.setViewportSize(size);
      const failurePrompt = await denied.evaluate(() => {
        const message = document.querySelector('#camera-placeholder-message');
        const button = document.querySelector('#camera-placeholder-btn');
        const rect = button.getBoundingClientRect();
        const hit = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
        return { messageVisible: getComputedStyle(message).display !== 'none',
          buttonVisible: rect.x >= 0 && rect.y >= 0 && rect.right <= innerWidth && rect.bottom <= innerHeight,
          buttonReceivesPointer: !!hit && (hit === button || button.contains(hit)) };
      });
      assert.ok(failurePrompt.messageVisible, `${size.width}×${size.height}: permission failure reason stays visible`);
      assert.ok(failurePrompt.buttonVisible && failurePrompt.buttonReceivesPointer,
        `${size.width}×${size.height}: permission retry remains visible and clickable`);
      await denied.screenshot({ path: path.join(outputDir, `permission-error-${size.width}x${size.height}.png`) });
    }
    await denied.locator('#camera-placeholder-btn').click();
    await denied.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'idle'
      && document.querySelector('#camera-video').srcObject?.getTracks().every((track) => track.readyState === 'live'));
    const retried = await denied.evaluate(() => ({ requests: window.__permissionRequests,
      tracks: document.querySelector('#camera-video').srcObject.getTracks().map((track) => track.kind) }));
    assert.equal(retried.requests.filter((request) => request.video).length, 2, 'retry requests camera access again');
    assert.equal(retried.requests.filter((request) => request.audio).length, 1, 'a successful camera retry also requests microphone access');
    assert.deepEqual(retried.tracks.sort(), ['audio', 'video']);
    await deniedContext.close();
    console.log('PASS initial camera permission denial is actionable and retry obtains both devices');

    const microphoneDeniedContext = await newAuthenticatedContext(browser);
    await microphoneDeniedContext.addInitScript(() => {
      const native = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      window.__allowAudio = false;
      window.__initialTracks = [];
      Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
        value: async (constraints) => {
          if (constraints.audio && !window.__allowAudio) throw new DOMException('Microphone permission denied', 'NotAllowedError');
          const stream = await native(constraints);
          if (!window.__allowAudio) window.__initialTracks.push(...stream.getTracks());
          return stream;
        },
      });
    });
    const microphoneDenied = await microphoneDeniedContext.newPage();
    microphoneDenied.on('pageerror', (error) => errors.push(error.message));
    await microphoneDenied.goto(baseURL);
    await microphoneDenied.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'idle'
      && /microfone.*bloque/i.test(document.querySelector('#status-message').textContent));
    assert.equal(await microphoneDenied.locator('#camera-video').evaluate((video) => video.srcObject), null,
      'denying the microphone does not silently enable recording without audio');
    assert.equal(await microphoneDenied.evaluate(() => window.__initialTracks.every((track) => track.readyState === 'ended')), true,
      'denying the microphone releases the previously authorized camera');
    assert.match(await microphoneDenied.locator('#camera-placeholder-message').textContent(), /microfone.*bloque/i);
    await microphoneDenied.evaluate(() => { window.__allowAudio = true; });
    await microphoneDenied.locator('#camera-placeholder-btn').click();
    await microphoneDenied.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'idle'
      && document.querySelector('#camera-video').srcObject?.getAudioTracks().length === 1);
    await microphoneDeniedContext.close();
    console.log('PASS microphone permission denial releases the camera and retry restores audio');

    const lateContext = await newAuthenticatedContext(browser);
    await lateContext.addInitScript(() => {
      const native = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      window.__lateTracks = null;
      Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
        value: async (constraints) => {
          await new Promise((resolve) => setTimeout(resolve, 900));
          const stream = await native(constraints);
          window.__lateTracks = stream.getTracks();
          return stream;
        },
      });
    });
    const late = await lateContext.newPage();
    late.on('pageerror', (error) => errors.push(error.message));
    await late.goto(baseURL);
    await late.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'preparing');
    await late.locator('#immersive-stop-btn').click();
    await late.waitForFunction(() => window.__lateTracks && window.__lateTracks.every((track) => track.readyState === 'ended'));
    assert.equal(await late.locator('#camera-video').evaluate((video) => video.srcObject), null);
    assert.equal(await late.locator('#preview-stage').getAttribute('data-state'), 'idle');
    assert.equal(await late.locator('#recording-count').textContent(), '0');
    await lateContext.close();
    console.log('PASS cancelled pending media request releases its late stream');

    const silentContext = await newAuthenticatedContext(browser);
    await silentContext.addInitScript(() => {
      const native = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
      const enumerate = navigator.mediaDevices.enumerateDevices.bind(navigator.mediaDevices);
      Object.defineProperty(navigator.mediaDevices, 'getUserMedia', {
        value: (constraints) => constraints.audio
          ? Promise.reject(new DOMException('No microphone in test', 'NotFoundError'))
          : native(constraints),
      });
      Object.defineProperty(navigator.mediaDevices, 'enumerateDevices', {
        value: async () => (await enumerate()).filter((device) => device.kind !== 'audioinput'),
      });
    });
    const silent = await silentContext.newPage();
    silent.on('pageerror', (error) => errors.push(error.message));
    await silent.goto(baseURL);
    await silent.waitForFunction(() => document.querySelector('#camera-video').srcObject !== null
      && document.querySelector('#preview-stage').dataset.state === 'idle');
    assert.equal(await silent.locator('#camera-video').evaluate((video) => video.srcObject.getAudioTracks().length), 0);
    assert.match(await silent.locator('#microphone-status').textContent(), /sem microfone/i);
    assert.match(await silent.locator('#status-message').textContent(), /sem áudio/i);
    await silentContext.close();
    console.log('PASS absent microphone produces explicit silent-camera fallback');

    const cleanContext = await newAuthenticatedContext(browser, { viewport: { width: 390, height: 844 } });
    const clean = await cleanContext.newPage();
    clean.on('pageerror', (error) => errors.push(error.message));
    await clean.goto(baseURL);
    await clean.waitForFunction(() => document.querySelector('#camera-video').readyState >= 2
      && document.querySelector('#preview-stage').dataset.state === 'idle');
    await clean.waitForTimeout(1000);
    const defaultSize = await clean.locator('#size-input').inputValue();
    assert.equal(await clean.locator('#prompt-text').evaluate((text) => getComputedStyle(text).fontSize), `${defaultSize}px`,
      'default text size follows the appearance settings');
    assert.equal(await clean.locator('#text-color').inputValue(), '#ffffff');
    assert.equal(await clean.locator('#opacity-input').inputValue(), '0');
    assert.equal(await clean.locator('#prompt-overlay').evaluate((overlay) => getComputedStyle(overlay).backgroundColor), 'rgba(15, 23, 42, 0)');
    const freshSizes = [];
    for (const size of [{ width: 390, height: 844 }, { width: 320, height: 844 },
      { width: 844, height: 390 }, { width: 1024, height: 768 }, { width: 1440, height: 900 }]) {
      await clean.setViewportSize(size);
      await clean.waitForTimeout(200);
      await assertImmersiveLayout(clean, `${size.width}×${size.height} fresh appearance`);
      const appearanceSize = await clean.locator('#size-input').inputValue();
      freshSizes.push({ width: size.width, textSize: Number(appearanceSize) });
      assert.equal(await clean.locator('#prompt-text').evaluate((text) => getComputedStyle(text).fontSize), `${appearanceSize}px`,
        'automatic text size remains accurately reflected in settings');
      await clean.screenshot({ path: path.join(outputDir, `clean-${size.width}x${size.height}.png`), fullPage: true });
    }
    assert.ok(freshSizes.at(-1).textSize > freshSizes[0].textSize,
      'a fresh wide-screen teleprompter uses larger text than a phone');
    await cleanContext.close();

    assert.deepEqual(errors, [], 'no unhandled browser errors');
    console.log(layoutOnly ? 'All layout and device checks passed. Screenshots:' : 'All browser checks passed. Screenshots:', outputDir);
  } finally {
    await browser.close();
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
