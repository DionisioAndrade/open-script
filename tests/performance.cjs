/* Reproducible rendering-work comparison, using only Chromium's fake camera
 * and microphone. Freeze before edits: node tests/performance.cjs --freeze-baseline
 * Then, with Django active: --baseline before edits and --current afterwards.
 * Baseline sources are frozen in ignored tests/screenshots/.baseline-* files.
 * These measurements describe this desktop browser, not physical phone CPU.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { chromium } = require('playwright');
const { baseURL, newAuthenticatedContext } = require('./helpers/auth.cjs');

const baseline = process.argv.includes('--baseline');
const freezeBaseline = process.argv.includes('--freeze-baseline');
const browserPath = process.env.TEST_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const outputDir = path.join(__dirname, 'screenshots');
const sampleMs = Number(process.env.PERFORMANCE_SAMPLE_MS || 3000);
const sourceFiles = ['studio.js', 'exporter.js', 'styles.css', 'frame-pump.js'];
const paragraph = 'Uma boa conversa permite apresentar uma ideia com calma. O roteiro ajuda a organizar o pensamento, explicar exemplos e compartilhar uma experiencia. Leia no seu ritmo, respire e conclua quando desejar.\n\n';
const script = paragraph.repeat(Math.ceil(100 * 1024 / paragraph.length)).slice(0, 100 * 1024);

function instrumentation() {
  const data = window.__renderWork = {
    raf: {}, videoCallbacks: {}, rafMs: 0, videoCallbackMs: 0, cameraDraws: 0, portraitFills: 0,
    sourceFrames: 0, sourcePresentedFrames: 0, text: {}, textChanged: {}, progress: {},
    rangeStyles: 0, transforms: 0, recorders: [], cameraSettings: [],
  };
  const increment = (map, key) => { map[key] = (map[key] || 0) + 1; };
  const nativeRAF = window.requestAnimationFrame.bind(window);
  window.requestAnimationFrame = (callback) => nativeRAF((now) => {
    increment(data.raf, callback.name || '(anonymous)');
    const start = performance.now();
    try { return callback(now); } finally { data.rafMs += performance.now() - start; }
  });
  const nativeRVFC = HTMLVideoElement.prototype.requestVideoFrameCallback;
  if (nativeRVFC) HTMLVideoElement.prototype.requestVideoFrameCallback = function(callback) {
    return nativeRVFC.call(this, (now, metadata) => {
      increment(data.videoCallbacks, callback.name || '(anonymous)');
      const start = performance.now();
      try { return callback(now, metadata); } finally { data.videoCallbackMs += performance.now() - start; }
    });
  };
  const nativeText = Object.getOwnPropertyDescriptor(Node.prototype, 'textContent');
  Object.defineProperty(Node.prototype, 'textContent', {
    ...nativeText,
    set(value) {
      if (this.id) {
        increment(data.text, this.id);
        if (nativeText.get.call(this) !== String(value)) increment(data.textChanged, this.id);
      }
      return nativeText.set.call(this, value);
    },
  });
  const nativeValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value');
  Object.defineProperty(HTMLInputElement.prototype, 'value', {
    ...nativeValue,
    set(value) {
      if (this.id === 'prompt-progress' || this.id === 'immersive-prompt-progress') increment(data.progress, this.id);
      return nativeValue.set.call(this, value);
    },
  });
  const nativeProperty = CSSStyleDeclaration.prototype.setProperty;
  CSSStyleDeclaration.prototype.setProperty = function(name, ...args) {
    if (name === '--range-progress') data.rangeStyles += 1;
    return nativeProperty.call(this, name, ...args);
  };
  const nativeTransform = Object.getOwnPropertyDescriptor(CSSStyleDeclaration.prototype, 'transform');
  if (nativeTransform?.set) Object.defineProperty(CSSStyleDeclaration.prototype, 'transform', {
    ...nativeTransform,
    set(value) { data.transforms += 1; return nativeTransform.set.call(this, value); },
  });
  const nativeDraw = CanvasRenderingContext2D.prototype.drawImage;
  CanvasRenderingContext2D.prototype.drawImage = function(source, ...args) {
    if (source?.id === 'camera-video') data.cameraDraws += 1;
    return nativeDraw.call(this, source, ...args);
  };
  const nativeFill = CanvasRenderingContext2D.prototype.fillRect;
  CanvasRenderingContext2D.prototype.fillRect = function(...args) {
    if (this.canvas.width === 1080 && this.canvas.height === 1920) data.portraitFills += 1;
    return nativeFill.call(this, ...args);
  };
  const nativeMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
  navigator.mediaDevices.getUserMedia = async (constraints) => {
    const next = constraints.video ? { ...constraints, video: { ...constraints.video,
      width: { exact: 1920 }, height: { exact: 1080 }, frameRate: { exact: 30 },
    } } : constraints;
    const stream = await nativeMedia(next);
    if (next.video) data.cameraSettings.push(stream.getVideoTracks()[0].getSettings());
    return stream;
  };
  const NativeRecorder = window.MediaRecorder;
  window.MediaRecorder = class extends NativeRecorder {
    constructor(...args) {
      super(...args);
      const entry = { recorder: this, events: [], chunks: [], video: args[0].getVideoTracks()[0]?.getSettings() };
      data.recorders.push(entry);
      for (const name of ['start', 'pause', 'resume', 'stop', 'error']) {
        this.addEventListener(name, () => entry.events.push(name));
      }
      this.addEventListener('dataavailable', (event) => { if (event.data?.size) entry.chunks.push(event.data); });
    }
  };
  window.__observeSourceFrames = () => {
    const video = document.querySelector('#camera-video');
    if (!nativeRVFC) return;
    const observe = (now, metadata) => {
      data.sourceFrames += 1;
      data.sourcePresentedFrames = metadata.presentedFrames;
      nativeRVFC.call(video, observe);
    };
    nativeRVFC.call(video, observe);
  };
  window.__renderWorkSnapshot = () => ({
    raf: { ...data.raf }, videoCallbacks: { ...data.videoCallbacks }, rafMs: data.rafMs,
    videoCallbackMs: data.videoCallbackMs, cameraDraws: data.cameraDraws, portraitFills: data.portraitFills,
    sourceFrames: data.sourceFrames, sourcePresentedFrames: data.sourcePresentedFrames,
    text: { ...data.text }, textChanged: { ...data.textChanged }, progress: { ...data.progress },
    rangeStyles: data.rangeStyles, transforms: data.transforms,
  });
}

function difference(before, after) {
  const result = {};
  for (const [key, value] of Object.entries(after)) {
    result[key] = typeof value === 'number' ? value - (before[key] || 0)
      : Object.fromEntries(Object.keys(value).map((name) => [name, value[name] - (before[key]?.[name] || 0)]).filter(([, count]) => count));
  }
  return result;
}

async function measure(page, cdp, name) {
  const before = await page.evaluate(() => ({ now: performance.now(), work: window.__renderWorkSnapshot() }));
  const metricBefore = Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(({ name, value }) => [name, value]));
  await page.waitForTimeout(sampleMs);
  const metricAfter = Object.fromEntries((await cdp.send('Performance.getMetrics')).metrics.map(({ name, value }) => [name, value]));
  const after = await page.evaluate(() => ({ now: performance.now(), work: window.__renderWorkSnapshot(),
    state: document.querySelector('#preview-stage').dataset.state,
    recorderState: window.__renderWork.recorders.at(-1)?.recorder.state || null,
    progress: document.querySelector('#immersive-prompt-progress').value,
    clock: document.querySelector('#immersive-recording-time').textContent,
  }));
  const metrics = Object.fromEntries(['TaskDuration', 'ScriptDuration', 'LayoutDuration', 'RecalcStyleDuration',
    'LayoutCount', 'RecalcStyleCount', 'JSHeapUsedSize'].map((key) => [key, metricAfter[key] - metricBefore[key]]));
  const report = { name, elapsedMs: after.now - before.now, state: after.state, recorderState: after.recorderState,
    progress: after.progress, clock: after.clock, work: difference(before.work, after.work), metrics };
  console.log(JSON.stringify(report));
  return report;
}

async function decodeRecording(page) {
  return page.evaluate(async () => {
    const entry = window.__renderWork.recorders.at(-1);
    const blob = new Blob(entry.chunks, { type: entry.recorder.mimeType });
    const url = URL.createObjectURL(blob);
    const video = document.createElement('video');
    video.muted = true; video.playsInline = true; video.preload = 'auto'; video.src = url;
    video.style.cssText = 'position:fixed;width:108px;height:192px;left:0;top:0;z-index:9999;pointer-events:none';
    document.body.append(video);
    const once = (name, timeout = 15000) => new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`Recorded playback timed out: ${name}`)), timeout);
      video.addEventListener(name, () => { clearTimeout(timer); resolve(); }, { once: true });
      video.addEventListener('error', () => { clearTimeout(timer); reject(new Error('Recorded playback failed')); }, { once: true });
    });
    try {
      await once('loadedmetadata');
      if (!Number.isFinite(video.duration)) {
        const seek = once('timeupdate'); video.currentTime = 1e10; await seek;
      }
      const rewind = once('seeked'); video.currentTime = 0; await rewind;
      const framesBefore = video.getVideoPlaybackQuality().totalVideoFrames;
      const droppedBefore = video.getVideoPlaybackQuality().droppedVideoFrames;
      const ended = once('ended', 25000);
      await video.play(); await ended;
      const quality = video.getVideoPlaybackQuality();
      const decodedFrames = quality.totalVideoFrames - framesBefore;
      return { bytes: blob.size, mimeType: blob.type, duration: video.duration,
        width: video.videoWidth, height: video.videoHeight, decodedFrames,
        droppedFrames: quality.droppedVideoFrames - droppedBefore, decodedFramesPerSecond: decodedFrames / video.duration };
    } finally { video.pause(); video.removeAttribute('src'); video.load(); video.remove(); URL.revokeObjectURL(url); }
  });
}

async function main() {
  await fs.mkdir(outputDir, { recursive: true });
  if (freezeBaseline) {
    const existing = await Promise.all(sourceFiles.map(async (file) => {
      try { await fs.access(path.join(outputDir, `.baseline-${file}`)); return file; }
      catch (error) { if (error.code !== 'ENOENT') throw error; return null; }
    }));
    if (existing.some(Boolean)) {
      throw new Error('A frozen baseline already exists. Existing snapshots are preserved; archive them before explicitly freezing another baseline.');
    }
    const snapshots = await Promise.all(sourceFiles.map(async (file) => [file,
      await fs.readFile(path.join(__dirname, '..', 'studio', 'static', 'studio', file), 'utf8')]));
    for (const [file, source] of snapshots) {
      await fs.writeFile(path.join(outputDir, `.baseline-${file}`), source, { flag: 'wx' });
    }
    console.log('Current studio, exporter, styles and frame pump frozen. Next run --baseline before editing the product.');
    return;
  }
  const sources = {};
  for (const file of sourceFiles) {
    const filePath = baseline ? path.join(outputDir, `.baseline-${file}`)
      : path.join(__dirname, '..', 'studio', 'static', 'studio', file);
    try { sources[file] = await fs.readFile(filePath, 'utf8'); }
    catch (error) {
      if (!baseline || error.code !== 'ENOENT') throw error;
      // The first measurements predate the shared frame-pump module. Those
      // frozen studio/exporter files do not use it, so its absence is valid.
      if (file === 'frame-pump.js') continue;
      throw new Error('No frozen baseline found. Run --freeze-baseline before changing the product, then --baseline.');
    }
  }
  const browser = await chromium.launch({ executablePath: browserPath, headless: true,
    args: ['--use-fake-device-for-media-stream=fps=30', '--use-fake-ui-for-media-stream'] });
  const errors = [];
  try {
    const context = await newAuthenticatedContext(browser, { viewport: { width: 390, height: 844 } });
    await context.addInitScript(instrumentation);
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    for (const [file, body] of Object.entries(sources)) {
      await page.route(new RegExp(`/static/studio/${file.replace('.', '\\.')}(?:\\?.*)?$`), (route) => route.fulfill({
        status: 200, contentType: file.endsWith('.css') ? 'text/css' : 'text/javascript', body,
      }));
    }
    const cdp = await context.newCDPSession(page);
    await cdp.send('Performance.enable');
    await page.goto(baseURL);
    try {
      await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'idle'
        && document.querySelector('#camera-video').srcObject && document.querySelector('#camera-video').readyState >= 2);
    } catch (error) {
      console.error(await page.evaluate(() => ({ state: document.querySelector('#preview-stage')?.dataset.state,
        message: document.querySelector('#camera-placeholder-message')?.textContent, camera: window.__renderWork.cameraSettings })));
      throw error;
    }
    await page.evaluate((value) => {
      const input = document.querySelector('#script-input');
      input.value = value; input.dispatchEvent(new Event('input', { bubbles: true }));
      window.__observeSourceFrames();
    }, script);
    await page.waitForTimeout(1200);
    const results = [];
    results.push(await measure(page, cdp, 'idle-camera'));
    await page.locator('#immersive-prompt-play-btn').click();
    await page.waitForFunction(() => document.querySelector('#preview-stage').classList.contains('is-rehearsing'));
    await page.waitForTimeout(250);
    results.push(await measure(page, cdp, 'rehearsal-scroll'));
    await page.locator('#immersive-prompt-play-btn').click();
    await page.waitForFunction(() => !document.querySelector('#preview-stage').classList.contains('is-rehearsing'));
    await page.locator('#immersive-record-btn').click();
    const countdownStart = Date.now();
    await page.waitForFunction(() => window.__renderWork.recorders.at(-1)?.recorder.state === 'recording'
      && document.querySelector('#preview-stage').dataset.state === 'recording', null, { timeout: 15000 });
    const countdownMs = Date.now() - countdownStart;
    assert.ok(countdownMs >= 4700, 'the real five-second countdown is preserved');
    await page.waitForTimeout(400);
    results.push(await measure(page, cdp, 'recording-scroll'));
    await page.locator('#immersive-prompt-progress').evaluate((input) => {
      input.value = '100'; input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    await page.waitForTimeout(350);
    results.push(await measure(page, cdp, 'recording-script-finished'));
    assert.equal(results.at(-1).recorderState, 'recording', 'EOF does not stop the recorder');
    await page.locator('#immersive-pause-btn').click();
    await page.waitForFunction(() => document.querySelector('#preview-stage').dataset.state === 'paused'
      && window.__renderWork.recorders.at(-1)?.recorder.state === 'paused');
    await page.waitForTimeout(350);
    results.push(await measure(page, cdp, 'recording-paused'));
    await page.locator('#immersive-stop-btn').click();
    await page.waitForFunction(() => window.__renderWork.recorders.at(-1)?.recorder.state === 'inactive');
    await page.waitForTimeout(200);
    const decodedRecording = await decodeRecording(page);
    console.log(`Decoded recording ${JSON.stringify(decodedRecording)}`);
    if (!baseline) {
      for (const scenario of results.filter((item) => ['recording-scroll', 'recording-script-finished'].includes(item.name))) {
        assert.ok(scenario.work.cameraDraws >= scenario.work.sourceFrames * 0.9,
          `${scenario.name}: at least 90% of the 30fps source frames reach the recording canvas`);
      }
      assert.ok(decodedRecording.decodedFramesPerSecond >= 27,
        `actual recorded playback preserves at least 27fps: ${decodedRecording.decodedFramesPerSecond}`);
    }
    assert.deepEqual(errors, [], 'no unhandled browser errors');
    const settings = await page.evaluate(() => ({ camera: window.__renderWork.cameraSettings,
      recording: window.__renderWork.recorders.map(({ video, events }) => ({ video, events })) }));
    const report = { mode: baseline ? 'baseline' : 'current', recordedAt: new Date().toISOString(),
      browserVersion: browser.version(), viewport: { width: 390, height: 844 }, scriptBytes: Buffer.byteLength(script),
      sampleTargetMs: sampleMs, countdownMs, decodedRecording, sourceHashes: Object.fromEntries(Object.entries(sources).map(([file, body]) =>
        [file, createHash('sha256').update(body).digest('hex')])), settings, scenarios: results, errors,
      limitations: 'Desktop headless Edge; synthetic 1920x1080 30fps camera and microphone; timing includes instrumentation. Counts compare deterministic rendering work; CPU deltas are illustrative, not phone battery or speed estimates.' };
    const output = path.join(outputDir, baseline ? 'performance-before.json' : 'performance-after.json');
    await fs.writeFile(output, `${JSON.stringify(report, null, 2)}\n`);
    console.log(`Saved ${output}`);
    if (!baseline) {
      let before;
      try { before = JSON.parse(await fs.readFile(path.join(outputDir, 'performance-before.json'), 'utf8')); }
      catch (error) {
        if (error.code !== 'ENOENT') throw error;
        console.log('Current measurements saved; no performance-before.json is available for comparison.');
        return;
      }
      const sum = (map) => Object.values(map).reduce((total, value) => total + value, 0);
      const comparison = report.scenarios.map((after) => {
        const previous = before.scenarios.find((item) => item.name === after.name);
        const totals = (item) => ({
          raf: sum(item.work.raf), videoCallbacks: sum(item.work.videoCallbacks), cameraDraws: item.work.cameraDraws,
          portraitFills: item.work.portraitFills, sourceFrames: item.work.sourceFrames,
          textWrites: sum(item.work.text), textChanged: sum(item.work.textChanged), progressWrites: sum(item.work.progress),
          rangeStyles: item.work.rangeStyles, layouts: item.metrics.LayoutCount, styles: item.metrics.RecalcStyleCount,
          taskMs: item.metrics.TaskDuration * 1000, scriptMs: item.metrics.ScriptDuration * 1000,
        });
        const first = totals(previous);
        const last = totals(after);
        return { name: after.name, perSecond: Object.fromEntries(Object.keys(last).map((name) => {
          const beforeRate = first[name] / (previous.elapsedMs / 1000);
          const afterRate = last[name] / (after.elapsedMs / 1000);
          return [name, { before: beforeRate, after: afterRate,
            reductionPercent: beforeRate ? (1 - afterRate / beforeRate) * 100 : null }];
        })) };
      });
      const comparisonPath = path.join(outputDir, 'performance-comparison.json');
      await fs.writeFile(comparisonPath, `${JSON.stringify(comparison, null, 2)}\n`);
      console.log(`Comparison saved ${comparisonPath}`);
    }
  } finally { await browser.close(); }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
