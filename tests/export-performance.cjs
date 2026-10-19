/* Actual browser encoders; synthetic camera/microphone only. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require('playwright');
const { baseURL, newAuthenticatedContext } = require('./helpers/auth.cjs');

async function snapshot(page) {
  return page.evaluate(() => ({
    status: document.querySelector('#export-status')?.textContent,
    notification: document.querySelector('#status-message')?.textContent,
    progress: document.querySelector('#export-progress-bar')?.value,
    disabled: document.querySelector('#export-submit-btn')?.disabled,
    videos: [...document.querySelectorAll('#export-preview video')].map((video) => ({
      type: video.className, readyState: video.readyState, time: video.currentTime,
      duration: String(video.duration), paused: video.paused, ended: video.ended,
      error: video.error?.message, width: video.videoWidth, height: video.videoHeight,
    })),
    reports: window.__diagnosticRecorders.map((report) => ({
      mimeType: report.recorder.mimeType, state: report.recorder.state,
      settings: report.settings, chunks: report.chunks, events: report.events,
      tracks: report.input.getTracks().map((track) => ({ kind: track.kind, state: track.readyState })),
    })),
    pumps: window.__diagnosticPumps,
    videoEvents: window.__diagnosticVideoEvents,
  }));
}

async function verifyDownload(page, download, expectedWidth, expectedHeight, expectedFrameRate) {
  const bytes = await fs.readFile(await download.path());
  const recorder = await page.evaluate(() => {
    const report = window.__diagnosticRecorders.at(-1);
    return { mimeType: report.recorder.mimeType, settings: report.settings };
  });
  assert.equal(recorder.settings.frameRate, expectedFrameRate);
  const metadata = await page.evaluate(async ({ data, mimeType }) => {
    const blob = new Blob([Uint8Array.from(atob(data), (character) => character.charCodeAt(0))], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const video = document.createElement('video');
    video.muted = true; video.playsInline = true; video.preload = 'auto'; video.src = url;
    try {
      await new Promise((resolve, reject) => {
        video.addEventListener('loadedmetadata', resolve, { once: true });
        video.addEventListener('error', () => reject(new Error('Export metadata unavailable')), { once: true });
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
      canvas.width = video.videoWidth; canvas.height = video.videoHeight;
      const context = canvas.getContext('2d');
      context.drawImage(video, 0, 0);
      const pixel = (x, y) => [...context.getImageData(Math.floor(x * canvas.width), Math.floor(y * canvas.height), 1, 1).data];
      const source = new TextDecoder('latin1').decode(await blob.arrayBuffer());
      return { bytes: blob.size, width: canvas.width, height: canvas.height, duration,
        samples: [0.05, 0.5, 0.95].map((x) => pixel(x, 0.5)), bars: [0.05, 0.95].map((y) => pixel(0.5, y)),
        hasAudio: source.includes('A_OPUS') || source.includes('mp4a') || source.includes('Opus') };
    } finally {
      video.pause(); video.removeAttribute('src'); video.load(); URL.revokeObjectURL(url);
    }
  }, { data: bytes.toString('base64'), mimeType: recorder.mimeType });
  assert.equal(metadata.width, expectedWidth);
  assert.equal(metadata.height, expectedHeight);
  assert.ok(metadata.bytes > 1000 && metadata.hasAudio);
  assert.ok(metadata.duration > 3.5 && metadata.duration < 6, `Capture pause excluded: ${metadata.duration}`);
  const expected = [[254, 32, 16], [32, 68, 240], [16, 239, 32]];
  for (const [index, pixel] of metadata.samples.entries()) {
    assert.ok(expected[index].every((channel, position) => Math.abs(pixel[position] - channel) < 25), 'All camera bands preserved');
  }
  for (const pixel of metadata.bars) assert.ok([15, 23, 42].every((channel, position) => Math.abs(pixel[position] - channel) < 20), 'Proportional navy padding preserved');
  assert.ok(await page.evaluate(() => window.__diagnosticPumps.every((pump) => pump.stopped && !pump.error)), 'Completed export and capture leave no pump running');
  return { ...metadata, frameRate: recorder.settings.frameRate, mimeType: recorder.mimeType };
}

async function run() {
  const browser = await chromium.launch({
    executablePath: process.env.TEST_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true,
    args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', '--autoplay-policy=no-user-gesture-required'],
  });
  const context = await newAuthenticatedContext(browser, { viewport: { width: 1440, height: 1040 }, acceptDownloads: true });
  await context.addInitScript(() => {
    window.__diagnosticRecorders = [];
    window.__diagnosticVideoEvents = [];
    const nativeMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
    navigator.mediaDevices.getUserMedia = async (constraints) => {
      const native = await nativeMedia(constraints);
      if (!constraints.video) return native;
      const canvas = document.createElement('canvas');
      canvas.width = 1280; canvas.height = 720;
      const ctx = canvas.getContext('2d');
      const paint = () => {
        ctx.fillStyle = '#fe2010'; ctx.fillRect(0, 0, 1280, 720);
        ctx.fillStyle = '#10ef20'; ctx.fillRect(842.5, 0, 437.5, 720);
        ctx.fillStyle = '#2044f0'; ctx.fillRect(437.5, 0, 405, 720);
      };
      paint();
      const timer = setInterval(paint, 1000 / 30);
      const synthetic = canvas.captureStream(30);
      native.getVideoTracks().forEach((track) => track.stop());
      native.getAudioTracks().forEach((track) => synthetic.addTrack(track));
      synthetic.getVideoTracks()[0].addEventListener('ended', () => clearInterval(timer), { once: true });
      return synthetic;
    };
    const NativeRecorder = window.MediaRecorder;
    window.MediaRecorder = class extends NativeRecorder {
      constructor(...args) {
        super(...args);
        const report = { recorder: this, input: args[0], settings: args[0].getVideoTracks()[0]?.getSettings(), events: [], chunks: [] };
        window.__diagnosticRecorders.push(report);
        for (const type of ['start', 'pause', 'resume', 'stop', 'error']) {
          this.addEventListener(type, (event) => report.events.push({ type, time: performance.now(), error: event.error?.message }));
        }
        this.addEventListener('dataavailable', (event) => report.chunks.push(event.data.size));
      }
    };
    const create = document.createElement.bind(document);
    let videoId = 0;
    document.createElement = function (tag, ...args) {
      const element = create(tag, ...args);
      if (String(tag).toLowerCase() === 'video') {
        const id = ++videoId;
        for (const type of ['loadedmetadata', 'loadeddata', 'canplay', 'playing', 'pause', 'seeked', 'ended', 'error', 'stalled', 'waiting']) {
          element.addEventListener(type, () => window.__diagnosticVideoEvents.push({ id, type, time: performance.now(), position: element.currentTime, readyState: element.readyState, duration: String(element.duration), error: element.error?.message }));
        }
      }
      return element;
    };
  });
  const page = await context.newPage();
  page.on('pageerror', (error) => console.log('PAGE ERROR', error.message));
  page.on('console', (message) => { if (message.type() === 'error') console.log('CONSOLE ERROR', message.text()); });
  try {
    await page.goto(baseURL);
    await page.waitForFunction(() => document.querySelector('#camera-video').readyState >= 2 && document.querySelector('#preview-stage').dataset.state === 'idle');
    await page.evaluate(() => {
      const originalStart = window.StudioFramePump.start;
      window.__diagnosticPumps = [];
      window.StudioFramePump = { start(options) {
        const report = { kind: options.video.id || options.video.className, frameRate: options.frameRate, draws: 0, stopped: false, error: null };
        window.__diagnosticPumps.push(report);
        const pump = originalStart({ ...options,
          draw(...args) { report.draws++; report.time = options.video.currentTime; return options.draw(...args); },
          onError(error) { report.error = error.message; return options.onError?.(error); },
        });
        return { stop() { report.stopped = true; pump.stop(); } };
      } };
    });
    await page.locator('#immersive-record-btn').click();
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'recording');
    await page.waitForTimeout(2200);
    await page.locator('#immersive-pause-btn').click();
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'paused');
    await page.waitForTimeout(2200);
    await page.locator('#immersive-pause-btn').click();
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'recording');
    await page.waitForTimeout(2200);
    await page.locator('#immersive-stop-btn').click();
    await page.waitForFunction(() => document.querySelector('#recording-count').textContent === '1');
    await page.locator('button.recording-card__export').first().click();
    const [largeDownload] = await Promise.all([
      page.waitForEvent('download', { timeout: 30000 }), page.locator('#export-submit-btn').click(),
    ]);
    assert.ok(largeDownload.suggestedFilename().endsWith('.mp4') || largeDownload.suggestedFilename().endsWith('.webm'));
    await page.locator('#close-export-btn').click();
    console.log('PASS 720p actual export', JSON.stringify(await verifyDownload(page, largeDownload, 720, 1280, 30)));
    await page.locator('button.recording-card__export').first().click();
    await page.locator('#export-resolution').selectOption('360');
    await page.locator('#export-quality').selectOption('compact');
    await page.locator('#export-framerate').selectOption('24');
    await page.locator('#export-submit-btn').click();
    await page.waitForFunction(() => window.__diagnosticRecorders.at(-1).recorder.state === 'recording');
    await page.waitForTimeout(200);
    await page.locator('#export-cancel-btn').click();
    await page.waitForFunction(() => !document.querySelector('#export-submit-btn').disabled);
    await page.waitForTimeout(500);
    assert.ok(await page.evaluate(() => window.__diagnosticPumps.every((pump) => pump.stopped && !pump.error)), 'Cancellation stops every owned frame pump');
    console.log('PASS cancel conversion retains original and stops frame pump');
    await page.locator('#export-format').selectOption('webm');
    await page.locator('#export-resolution').selectOption('360');
    await page.locator('#export-quality').selectOption('compact');
    await page.locator('#export-framerate').selectOption('24');
    const [compactDownload] = await Promise.all([
      page.waitForEvent('download', { timeout: 30000 }), page.locator('#export-submit-btn').click(),
    ]);
    assert.ok(compactDownload.suggestedFilename().endsWith('.webm'));
    console.log('PASS cancelled export retry 360p24 WebM', JSON.stringify(await verifyDownload(page, compactDownload, 360, 640, 24)));
  } catch (error) {
    const report = await snapshot(page);
    console.log('EXPORT FAILURE', JSON.stringify(report));
    await fs.mkdir(path.join(__dirname, 'screenshots'), { recursive: true });
    await fs.writeFile(path.join(__dirname, 'screenshots', 'export-debug.json'), JSON.stringify(report, null, 2));
    await page.screenshot({ path: path.join(__dirname, 'screenshots', 'export-debug.png') });
    throw error;
  } finally {
    await browser.close();
  }
}
run().catch((error) => { console.error(error); process.exitCode = 1; });
