/* Social destination UI regression with real native encoders and synthetic
 * camera/audio. Django must be running. Run node tests/social-profiles.cjs.
 * Set NODE_PATH to the bundled node_modules when using its runtime.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { chromium } = require('playwright');
const { baseURL, newAuthenticatedContext } = require('./helpers/auth.cjs');

const screenshotDirectory = path.join(__dirname, 'screenshots');
const ids = ['custom', 'instagram-reels', 'instagram-stories', 'instagram-feed', 'tiktok',
  'youtube-shorts', 'youtube-video', 'facebook-reels', 'facebook-feed', 'linkedin', 'x',
  'whatsapp-status', 'pinterest', 'kwai'];
const aspects = ['9:16', '4:5', '1:1', '16:9', '2:3'];

async function settings(page) {
  return page.evaluate(() => Object.fromEntries(['profile', 'format', 'aspect', 'resolution',
    'quality', 'framerate', 'filename'].map((name) => [name, document.querySelector('#export-' + name).value])));
}

async function selectProfile(page, id) {
  await page.locator('#export-profile').selectOption(id);
  assert.equal(await page.locator('#export-profile').inputValue(), id);
}

async function renameWithoutRebuilding(page, name, expectedDownloadName) {
  const preview = await page.locator('#export-preview video').elementHandle();
  const before = await page.evaluate(() => ({
    profile: document.querySelector('#export-profile').value,
    encoders: window.__socialExportRecorders.length,
    href: document.querySelector('#export-save-link').getAttribute('href'),
    status: document.querySelector('#export-status').textContent,
  }));
  await page.locator('#export-filename').fill(name);
  assert.ok(await page.locator('#export-preview video').evaluate((video, original) => video === original, preview),
    'Renaming retains the existing preview player and its playback state');
  await preview.dispose();
  assert.equal(await page.locator('#export-profile').inputValue(), before.profile, 'Renaming keeps the destination preset');
  assert.equal(await page.evaluate(() => window.__socialExportRecorders.length), before.encoders, 'Renaming never starts another encoder');
  assert.equal(await page.locator('#export-status').textContent(), before.status, 'Renaming retains export status');
  assert.equal(await page.locator('#export-save-link').getAttribute('href'), before.href, 'Renaming preserves the prepared file URL');
  if (expectedDownloadName) {
    assert.ok(await page.locator('#export-save-link').isVisible(), 'Prepared video remains available to save');
    assert.equal(await page.locator('#export-save-link').getAttribute('download'), expectedDownloadName);
  }
}

async function assertMobileDialog(page, aspect, language) {
  const dialog = page.locator('#export-dialog');
  const state = await dialog.evaluate((element) => {
    const preview = document.querySelector('#export-preview').getBoundingClientRect();
    const bounds = element.getBoundingClientRect();
    return { viewport: innerWidth, pageWidth: document.documentElement.scrollWidth,
      left: bounds.left, right: bounds.right, ownWidth: element.clientWidth, scrollWidth: element.scrollWidth,
      previewRatio: preview.width / preview.height, videoFit: getComputedStyle(element.querySelector('video')).objectFit };
  });
  assert.ok(state.pageWidth <= state.viewport + 1 && state.scrollWidth <= state.ownWidth + 1,
    `${language} ${aspect}: export dialog has no horizontal overflow: ${JSON.stringify(state)}`);
  assert.ok(state.left >= -1 && state.right <= state.viewport + 1, 'Dialog fits the narrow viewport');
  const [width, height] = aspect.split(':').map(Number);
  assert.ok(Math.abs(state.previewRatio - width / height) < 0.02, 'Visible preview uses selected aspect');
  assert.equal(state.videoFit, 'contain', 'Preview preserves the full source');
  assert.match(await page.locator('#export-preview-caption').textContent(), new RegExp(aspect));
  for (const selector of ['#export-profile', '#export-filename', '#export-format', '#export-aspect',
    '#export-resolution', '#export-quality', '#export-framerate', '#export-submit-btn', '#close-export-btn',
    '#export-preview-caption']) {
    const control = page.locator(selector);
    // A sticky action footer can cover the bottom of the scrollport. Scroll
    // to the middle, as a user would, instead of accepting minimal alignment.
    await control.evaluate((element) => element.scrollIntoView({ block: 'center', behavior: 'instant' }));
    const bounds = await control.evaluate((element) => {
      const box = element.getBoundingClientRect();
      const hit = document.elementFromPoint(box.x + box.width / 2, box.y + box.height / 2);
      return { width: box.width, height: box.height, top: box.top, bottom: box.bottom,
        viewportHeight: innerHeight, hit: !!hit && (hit === element || element.contains(hit)) };
    });
    assert.ok(bounds.top >= -1 && bounds.bottom <= bounds.viewportHeight + 1 && bounds.hit,
      `${language} ${aspect}: ${selector} is reachable through scrolling: ${JSON.stringify(bounds)}`);
    if (selector !== '#export-preview-caption') assert.ok(bounds.height >= 44 && bounds.width >= 44,
      `${selector}: touch control is at least 44px`);
  }
  await dialog.evaluate((element) => { element.scrollTop = 0; });
}

async function decodeDownload(page, download, expectedWidth, expectedHeight) {
  const bytes = await fs.readFile(await download.path());
  const report = await page.evaluate(() => {
    const recording = window.__socialExportRecorders.at(-1);
    return { mimeType: recording.recorder.mimeType, settings: recording.settings };
  });
  const decoded = await page.evaluate(async ({ encoded, mimeType }) => {
    const bytes = Uint8Array.from(atob(encoded), (char) => char.charCodeAt(0));
    const blob = new Blob([bytes], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const video = document.createElement('video');
    video.muted = true; video.playsInline = true; video.src = url;
    try {
      await new Promise((resolve, reject) => {
        video.onloadedmetadata = resolve;
        video.onerror = () => reject(new Error('Exported video does not decode'));
      });
      if (!Number.isFinite(video.duration)) {
        await new Promise((resolve) => {
          video.addEventListener('timeupdate', resolve, { once: true });
          video.currentTime = 1e10; setTimeout(resolve, 1500);
        });
      }
      const duration = video.duration;
      video.currentTime = 0;
      await video.play();
      await new Promise((resolve) => setTimeout(resolve, 180));
      video.pause();
      const canvas = document.createElement('canvas');
      canvas.width = video.videoWidth; canvas.height = video.videoHeight;
      const context = canvas.getContext('2d');
      context.drawImage(video, 0, 0);
      const scale = Math.min(canvas.width / 1080, canvas.height / 1920);
      const contentWidth = 1080 * scale;
      const offset = (canvas.width - contentWidth) / 2;
      const samples = [0.05, 0.5, 0.95].map((x) => [...context.getImageData(
        Math.floor(offset + x * contentWidth), Math.floor(canvas.height / 2), 1, 1).data]);
      const sidePadding = offset > 1 ? [...context.getImageData(Math.floor(offset / 2), Math.floor(canvas.height / 2), 1, 1).data] : null;
      const audio = new AudioContext();
      let audioRms;
      try {
        const decoded = await audio.decodeAudioData(bytes.buffer.slice(0));
        const channel = decoded.getChannelData(0);
        audioRms = Math.sqrt(channel.reduce((sum, value) => sum + value * value, 0) / channel.length);
      } finally { await audio.close(); }
      return { width: canvas.width, height: canvas.height, duration, audioRms, samples, sidePadding, bytes: blob.size };
    } finally { video.pause(); video.removeAttribute('src'); video.load(); URL.revokeObjectURL(url); }
  }, { encoded: bytes.toString('base64'), mimeType: report.mimeType });
  assert.equal(decoded.width, expectedWidth);
  assert.equal(decoded.height, expectedHeight);
  assert.equal(report.settings.width, expectedWidth);
  assert.equal(report.settings.height, expectedHeight);
  assert.equal(report.settings.frameRate, 30);
  assert.ok(decoded.duration > 1.5 && decoded.duration < 4, 'Full two-second take duration survives conversion');
  assert.ok(decoded.audioRms > 0.025 && decoded.bytes > 1000, 'Export preserves audible synthetic microphone');
  const expected = [[254, 32, 16], [32, 68, 240], [16, 239, 32]];
  decoded.samples.forEach((pixel, index) => assert.ok(expected[index].every((channel, at) => Math.abs(pixel[at] - channel) < 25),
    `Left, middle and right camera bands survive without crop: ${JSON.stringify(decoded.samples)}`));
  if (decoded.sidePadding) assert.ok([15, 23, 42].every((channel, at) => Math.abs(decoded.sidePadding[at] - channel) < 20), 'Padding uses navy');
  return { ...decoded, mimeType: report.mimeType, filename: download.suggestedFilename() };
}

async function run() {
  await fs.mkdir(screenshotDirectory, { recursive: true });
  const browser = await chromium.launch({
    executablePath: process.env.TEST_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true,
    args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
  });
  const context = await newAuthenticatedContext(browser, { viewport: { width: 1440, height: 900 }, acceptDownloads: true });
  const errors = [];
  await context.addInitScript(() => {
    window.__socialExportRecorders = [];
    const NativeRecorder = MediaRecorder;
    window.MediaRecorder = class extends NativeRecorder {
      constructor(stream, options) {
        super(stream, options);
        if (document.querySelector('#export-dialog')?.open) window.__socialExportRecorders.push({
          recorder: this, stream, settings: stream.getVideoTracks()[0]?.getSettings(),
        });
      }
    };
    navigator.mediaDevices.getUserMedia = async (constraints) => {
      const canvas = document.createElement('canvas');
      canvas.width = 1280; canvas.height = 720;
      const ctx = canvas.getContext('2d');
      const paint = () => {
        ctx.fillStyle = '#fe2010'; ctx.fillRect(0, 0, 1280, 720);
        ctx.fillStyle = '#2044f0'; ctx.fillRect(437.5, 0, 405, 720);
        ctx.fillStyle = '#10ef20'; ctx.fillRect(842.5, 0, 437.5, 720);
      };
      paint(); setInterval(paint, 1000 / 30);
      const stream = canvas.captureStream(30);
      if (constraints.audio) {
        const audio = new AudioContext();
        const oscillator = audio.createOscillator();
        const gain = audio.createGain();
        gain.gain.value = 0.15;
        const output = audio.createMediaStreamDestination();
        oscillator.connect(gain).connect(output);
        await audio.resume(); oscillator.start();
        output.stream.getAudioTracks().forEach((track) => stream.addTrack(track));
      }
      return stream;
    };
  });
  const page = await context.newPage();
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('dialog', (dialog) => dialog.dismiss());
  try {
    await page.goto(baseURL);
    await page.waitForFunction(() => window.StudioExportProfiles && document.querySelector('#camera-video').readyState >= 2);
    await page.locator('#immersive-record-btn').click();
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'recording');
    await page.waitForTimeout(2100);
    await page.locator('#immersive-stop-btn').click();
    await page.waitForFunction(() => document.querySelector('#recording-count').textContent === '1');
    const original = await page.locator('.recording-card').first().evaluate(async (card) => {
      const recording = card.studioRecording;
      const bytes = new Uint8Array(await recording.blob.arrayBuffer());
      let binary = ''; for (const byte of bytes) binary += String.fromCharCode(byte);
      return { url: recording.url, data: btoa(binary), duration: recording.duration, mimeType: recording.mimeType };
    });
    const originalHash = createHash('sha256').update(Buffer.from(original.data, 'base64')).digest('hex');
    await page.locator('button.recording-card__export').first().click();
    assert.deepEqual(await page.locator('#export-profile option').evaluateAll((options) => options.map((option) => option.value)), ids,
      'All social destinations are visible without duplicate or missing choices');
    await page.locator('#export-filename').fill('Minha edição final');
    for (const id of ids) {
      await selectProfile(page, id);
      const choice = await settings(page);
      const aspect = ['instagram-feed', 'facebook-feed'].includes(id) ? '4:5'
        : ['youtube-video', 'x'].includes(id) ? '16:9' : id === 'linkedin' ? '1:1' : '9:16';
      assert.equal(choice.aspect, aspect, `${id}: expected destination framing`);
      assert.equal(choice.resolution, id === 'custom' || id === 'whatsapp-status' ? '720' : '1080', `${id}: useful destination resolution`);
      assert.equal(choice.format, 'mp4', `${id}: compatible MP4 when available`);
      assert.equal(choice.quality, 'balanced');
      assert.equal(choice.framerate, '30');
      assert.equal(choice.filename, 'Minha edição final', 'Destination selection preserves the custom filename');
      assert.ok(!(await page.locator('#export-submit-btn').isDisabled()), `${id}: available native format can export`);
      assert.match(await page.locator('#export-preview-caption').textContent(), new RegExp(aspect));
    }
    console.log('PASS all 14 destinations apply appropriate dimensions, MP4/quality/fps and preserve filename');

    for (const [selector, value] of [['#export-quality', 'high'], ['#export-framerate', '24'],
      ['#export-resolution', '360'], ['#export-aspect', '1:1'], ['#export-format', 'webm']]) {
      await selectProfile(page, 'instagram-reels');
      await page.locator(selector).selectOption(value);
      assert.equal(await page.locator('#export-profile').inputValue(), 'custom', 'Manual adjustment switches to Personalizado');
      assert.equal(await page.locator(selector).inputValue(), value, 'Manual adjustment is retained');
    }
    await selectProfile(page, 'linkedin');
    await renameWithoutRebuilding(page, 'Minha edição revisada');
    const savedChoices = await settings(page);
    for (const language of ['en', 'es', 'pt-BR']) {
      await page.evaluate((language) => StudioI18n.setLanguage(language), language);
      assert.deepEqual(await settings(page), savedChoices, 'Language switch retains destination, filename and all settings');
      const label = await page.locator('label[for="export-profile"]').textContent();
      assert.equal(label, { en: 'Video destination', es: 'Destino del vídeo', 'pt-BR': 'Destino do vídeo' }[language]);
    }
    console.log('PASS manual adjustments and live language changes preserve user choices');

    for (const [aspect, resolution, dimensions] of [['9:16', '1080', '1080 × 1920'], ['4:5', '1080', '1080 × 1350'],
      ['1:1', '1080', '1080 × 1080'], ['16:9', '1080', '1920 × 1080'], ['2:3', '1080', '1080 × 1620']]) {
      await page.locator('#export-aspect').selectOption(aspect);
      await page.locator('#export-resolution').selectOption(resolution);
      assert.equal(await page.locator('#export-resolution option:checked').textContent(), dimensions);
      assert.match(await page.locator('#export-estimate').textContent(), new RegExp(dimensions));
    }
    await page.setViewportSize({ width: 320, height: 568 });
    for (const aspect of aspects) {
      await page.locator('#export-aspect').selectOption(aspect);
      await assertMobileDialog(page, aspect, 'pt-BR');
    }
    for (const language of ['en', 'es']) {
      await page.evaluate((language) => StudioI18n.setLanguage(language), language);
      await assertMobileDialog(page, '2:3', language);
      await page.screenshot({ path: path.join(screenshotDirectory, `social-export-320x568-${language}.png`) });
    }
    await page.evaluate(() => StudioI18n.setLanguage('pt-BR'));
    await page.setViewportSize({ width: 1440, height: 900 });
    console.log('PASS all aspect labels, contain previews and 320×568 layout with reachable controls in three languages');

    await page.locator('.recording-card').first().evaluate((card) => { card.studioRecording.duration = 200000; });
    await selectProfile(page, 'youtube-shorts');
    assert.match(await page.locator('#export-profile-note').textContent(), /ultrapassa.*3 minutos.*vídeo inteiro/i);
    assert.ok(!(await page.locator('#export-submit-btn').isDisabled()), 'Duration advice never prevents the full video export');
    await page.locator('.recording-card').first().evaluate((card, duration) => { card.studioRecording.duration = duration; }, original.duration);
    await selectProfile(page, 'pinterest');
    assert.match(await page.locator('#export-profile-note').textContent(), /mais curto.*4 segundos/i);
    console.log('PASS duration limits are guidance and never truncate or block a recording');

    for (const [id, width, height, filename] of [['linkedin', 1080, 1080, 'linkedin-final'], ['youtube-video', 1920, 1080, 'youtube-final']]) {
      await selectProfile(page, id);
      await page.locator('#export-filename').fill(filename);
      const [download] = await Promise.all([
        page.waitForEvent('download', { timeout: 30000 }), page.locator('#export-submit-btn').click(),
      ]);
      assert.equal(download.suggestedFilename(), `${filename}.mp4`);
      console.log(`PASS native ${id} export`, JSON.stringify(await decodeDownload(page, download, width, height)));
      assert.ok(await page.evaluate(() => window.__socialExportRecorders.every((report) => report.recorder.state === 'inactive'
        && report.stream.getTracks().every((track) => track.readyState === 'ended'))), 'Completed exports release all owned tracks');
      if (id === 'linkedin') {
        await renameWithoutRebuilding(page, 'linkedin revisado', 'linkedin-revisado.mp4');
        const [renamedDownload] = await Promise.all([
          page.waitForEvent('download'), page.locator('#export-save-link').click(),
        ]);
        assert.equal(renamedDownload.suggestedFilename(), 'linkedin-revisado.mp4');
        const prepared = await fs.readFile(await download.path());
        const renamed = await fs.readFile(await renamedDownload.path());
        assert.equal(createHash('sha256').update(renamed).digest('hex'), createHash('sha256').update(prepared).digest('hex'),
          'Saving under a new name retains every prepared video byte');
        console.log('PASS renaming before/after conversion retains preset, player, prepared URL and exact file without re-encoding');
      }
    }

    await page.locator('#close-export-btn').click();
    await page.evaluate(() => {
      window.__nativeSocialExporter = StudioExporter;
      window.StudioExporter = { ...StudioExporter, getFormats: (hasAudio) => window.__nativeSocialExporter.getFormats(hasAudio).filter((format) => format.value !== 'mp4') };
    });
    await page.locator('button.recording-card__export').first().click();
    await selectProfile(page, 'instagram-reels');
    assert.ok(await page.locator('#export-submit-btn').isDisabled(), 'MP4 destination cannot accidentally export an incompatible WebM');
    assert.match(await page.locator('#export-profile-note').textContent(), /não consegue gerar MP4.*Personalizado/i);
    for (const id of ['tiktok', 'youtube-shorts', 'youtube-video']) {
      await selectProfile(page, id);
      assert.equal(await page.locator('#export-format').inputValue(), 'webm');
      assert.ok(!(await page.locator('#export-submit-btn').isDisabled()), `${id}: documented native WebM remains available`);
      assert.match(await page.locator('#export-profile-note').textContent(), /WebM porque MP4 não está disponível/i);
    }
    await page.locator('#export-format').selectOption('original');
    assert.equal(await page.locator('#export-profile').inputValue(), 'custom');
    for (const selector of ['#export-aspect', '#export-resolution', '#export-quality', '#export-framerate']) {
      assert.ok(await page.locator(selector).isDisabled(), 'Original clearly disables conversion settings');
    }
    await page.locator('#export-filename').fill('original-preservado');
    const [originalDownload] = await Promise.all([page.waitForEvent('download'), page.locator('#export-submit-btn').click()]);
    const originalBytes = await fs.readFile(await originalDownload.path());
    assert.equal(createHash('sha256').update(originalBytes).digest('hex'), originalHash, 'Original preserves every captured byte after social exports');
    await page.locator('#close-export-btn').click();
    assert.equal(await page.locator('.recording-card video').first().getAttribute('src'), original.url, 'Source player keeps its original URL');
    assert.equal(await page.locator('#recording-count').textContent(), '1', 'All destination exports retain the original take');
    assert.deepEqual(errors, [], 'No unhandled UI or encoder errors');
    console.log('PASS MP4 unavailable advice, permitted WebM fallback and Original byte-for-byte preservation');
  } catch (error) {
    console.error('SOCIAL EXPORT FAILURE', await page.evaluate(() => ({
      status: document.querySelector('#export-status')?.textContent,
      profileNote: document.querySelector('#export-profile-note')?.textContent,
      settings: Object.fromEntries(['profile', 'format', 'aspect', 'resolution', 'quality', 'framerate']
        .map((name) => [name, document.querySelector('#export-' + name)?.value])),
      recorders: (window.__socialExportRecorders || []).map((report) => ({ mimeType: report.recorder.mimeType,
        state: report.recorder.state, settings: report.settings })),
    })));
    await page.screenshot({ path: path.join(screenshotDirectory, 'social-export-failure.png') });
    throw error;
  } finally { await browser.close(); }
}

run().catch((error) => { console.error(error); process.exitCode = 1; });
