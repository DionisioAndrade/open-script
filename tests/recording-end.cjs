/* Run against a running Django server: node tests/recording-end.cjs
 * Requires Playwright and Edge (override TEST_BROWSER_PATH / TEST_BASE_URL).
 * Capture and encoding use the native MediaRecorder with synthetic camera and
 * microphone inputs. No real devices, shortened countdowns or simulated clocks.
 */
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const { baseURL, newAuthenticatedContext } = require('./helpers/auth.cjs');

const browserPath = process.env.TEST_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const longScript = Array.from({ length: 65 }, (_, index) => `Linha ${index + 1}: continue a conversa com a câmera.`).join('\n\n');
const seconds = (label) => label.split(':').reduce((total, part) => total * 60 + Number(part), 0);

async function setRange(page, selector, value) {
  await page.locator(selector).evaluate((element, next) => {
    element.value = String(next);
    element.dispatchEvent(new Event('input', { bubbles: true }));
  }, value);
}

async function editScript(page, value) {
  await page.locator('#editor-btn').click();
  await page.locator('#script-input').fill(value);
  await page.locator('#close-script-btn').click();
  await page.waitForFunction((text) => document.querySelector('#prompt-text').textContent === text, value);
}

async function snapshot(page, index) {
  return page.evaluate((next) => {
    const report = window.__endRecorders[next];
    return {
      now: performance.now(), recorders: window.__endRecorders.length,
      state: report.recorder.state, appState: document.querySelector('#preview-stage').dataset.state,
      clock: document.querySelector('#immersive-recording-time').textContent,
      readingTime: document.querySelector('#immersive-reading-time').textContent,
      progress: Number(document.querySelector('#immersive-prompt-progress').value),
      cards: document.querySelectorAll('#recordings-list video').length,
      count: Number(document.querySelector('#recording-count').textContent),
      events: report.events, stopCalls: report.stopCalls,
      chunks: report.chunks.map(({ time, blob }) => ({ time, bytes: blob.size })),
      tracks: report.input.getTracks().map((track) => ({ kind: track.kind, state: track.readyState })),
      promptPlaying: document.querySelector('#immersive-prompt-play-btn').getAttribute('aria-pressed'),
    };
  }, index);
}

function assertStillRecording(report, index, completed, description) {
  assert.equal(report.recorders, index + 1, `${description}: the same native recorder is retained`);
  assert.equal(report.state, 'recording', `${description}: native capture continues`);
  assert.equal(report.appState, 'recording', `${description}: the interface remains recording`);
  assert.equal(report.stopCalls, 0, `${description}: no automatic call to stop()`);
  assert.ok(!report.events.some((event) => event.type === 'stop' || event.type === 'error'),
    `${description}: no premature stop or encoding error`);
  assert.equal(report.count, completed, `${description}: no finished recording is created`);
  assert.equal(report.cards, completed, `${description}: no card is created before the user stops`);
  assert.deepEqual(report.tracks.map((track) => track.kind).sort(), ['audio', 'video'],
    `${description}: microphone and video remain in the recording stream`);
  assert.ok(report.tracks.every((track) => track.state === 'live'), `${description}: both capture tracks remain live`);
}

async function begin(page, index) {
  await page.locator('#immersive-record-btn').click();
  await page.locator('#countdown-overlay').waitFor({ state: 'visible' });
  assert.equal((await page.locator('#countdown-number').textContent()).trim(), '5');
  const countdownStarted = await page.evaluate(() => performance.now());
  await page.waitForFunction((next) => window.__endRecorders[next]?.events.some((event) => event.type === 'start'), index);
  const started = await snapshot(page, index);
  assert.ok(started.events.find((event) => event.type === 'start').time - countdownStarted >= 4700,
    'a take waits for the real five-second countdown');
  assertStillRecording(started, index, index, 'take started');
}

async function assertContinuesAfter(page, index, ended, description) {
  const clockAtEnd = seconds(ended.clock);
  await page.waitForFunction(({ take, after, clock }) => {
    const report = window.__endRecorders[take];
    const elapsed = document.querySelector('#immersive-recording-time').textContent.split(':')
      .reduce((total, part) => total * 60 + Number(part), 0);
    return elapsed >= clock + 2 && report.chunks.filter((chunk) => chunk.time > after && chunk.blob.size > 0).length >= 2;
  }, { take: index, after: ended.now, clock: clockAtEnd });
  const continued = await snapshot(page, index);
  assertStillRecording(continued, index, index, description);
  assert.ok(seconds(continued.clock) >= clockAtEnd + 2, `${description}: REC time keeps advancing`);
  assert.ok(continued.chunks.filter((chunk) => chunk.time > ended.now && chunk.bytes > 0).length >= 2,
    `${description}: native encoder keeps producing data with live audio and video after the script ends`);
  return continued;
}

async function stopAndCheckBlob(page, index, minimumDuration) {
  const before = await snapshot(page, index);
  assertStillRecording(before, index, index, 'before explicit stop');
  assert.equal(await page.locator('#immersive-stop-btn').getAttribute('aria-label'), 'Encerrar');
  await page.locator('#immersive-stop-btn').click();
  await page.waitForFunction((count) => Number(document.querySelector('#recording-count').textContent) === count, index + 1);
  await page.locator('#recordings-dialog').waitFor({ state: 'visible' });
  assert.equal(await page.locator('#recordings-list video').count(), index + 1);
  const finished = await snapshot(page, index);
  assert.equal(finished.state, 'inactive');
  assert.equal(finished.stopCalls, 1, 'capture stops once, following the explicit user action');
  assert.equal(finished.events.filter((event) => event.type === 'stop').length, 1);
  let activeStart = null;
  let encodedMilliseconds = 0;
  for (const event of finished.events) {
    if (event.type === 'start' || event.type === 'resume') activeStart = event.time;
    else if ((event.type === 'pause' || event.type === 'stop') && activeStart !== null) {
      encodedMilliseconds += event.time - activeStart;
      activeStart = null;
    }
  }
  const url = await page.locator('#recordings-list video').first().getAttribute('src');
  assert.match(url, /^blob:/, 'manual completion exposes the recorded source Blob');
  const decoded = await page.evaluate(async (source) => {
    const blob = await fetch(source).then((response) => response.blob());
    const metadata = new TextDecoder('latin1').decode(await blob.arrayBuffer());
    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.src = source;
    try {
      await new Promise((resolve, reject) => {
        video.addEventListener('loadedmetadata', resolve, { once: true });
        video.addEventListener('error', () => reject(new Error('Recorded Blob cannot be decoded')), { once: true });
      });
      if (!Number.isFinite(video.duration)) {
        await new Promise((resolve) => {
          video.addEventListener('timeupdate', resolve, { once: true });
          video.currentTime = 1e10;
          setTimeout(resolve, 1500);
        });
      }
      const duration = video.duration;
      video.currentTime = Math.max(0, duration - 1);
      await video.play();
      await new Promise((resolve) => setTimeout(resolve, 250));
      const decodedStream = video.captureStream();
      const result = {
        bytes: blob.size, width: video.videoWidth, height: video.videoHeight, duration,
        containsAudio: metadata.includes('A_OPUS') || metadata.includes('mp4a') || metadata.includes('Opus'),
        decodedKinds: decodedStream.getTracks().map((track) => track.kind).sort(),
      };
      decodedStream.getTracks().forEach((track) => track.stop());
      return result;
    } finally {
      video.pause();
      video.removeAttribute('src');
      video.load();
    }
  }, url);
  assert.ok(decoded.bytes > 1000, 'completed source contains native encoded data');
  assert.equal(decoded.width, 1080);
  assert.equal(decoded.height, 1920);
  assert.ok(decoded.duration >= minimumDuration, `recorded duration includes speech after the script: ${JSON.stringify(decoded)}`);
  assert.ok(Math.abs(decoded.duration - encodedMilliseconds / 1000) < 0.6,
    'the playable duration includes the whole active recording interval and excludes the user pause');
  assert.equal(decoded.containsAudio, true, 'completed source includes an encoded microphone track');
  assert.deepEqual(decoded.decodedKinds, ['audio', 'video'], 'the end of the recorded source plays with audio and video');
  await page.locator('#close-recordings-btn').click();
  return decoded;
}

async function main() {
  const browser = await chromium.launch({ executablePath: browserPath, headless: true,
    args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] });
  const errors = [];
  try {
    const context = await newAuthenticatedContext(browser, { viewport: { width: 390, height: 844 } });
    await context.addInitScript(() => {
      window.__endRecorders = [];
      const NativeRecorder = window.MediaRecorder;
      window.MediaRecorder = class extends NativeRecorder {
        constructor(...args) {
          super(...args);
          const report = { recorder: this, input: args[0], chunks: [], events: [], stopCalls: 0 };
          this.__endReport = report;
          window.__endRecorders.push(report);
          for (const type of ['start', 'pause', 'resume', 'stop', 'error']) {
            this.addEventListener(type, () => report.events.push({ type, time: performance.now() }));
          }
          this.addEventListener('dataavailable', (event) => report.chunks.push({ blob: event.data, time: performance.now() }));
        }
        stop() { this.__endReport.stopCalls += 1; return super.stop(); }
      };
    });
    const page = await context.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto(baseURL);
    await page.waitForFunction(() => document.querySelector('#camera-video').readyState >= 2
      && document.querySelector('#preview-stage').dataset.state === 'idle');
    await setRange(page, '#immersive-speed-input', 100);
    await editScript(page, longScript);
    await begin(page, 0);
    await setRange(page, '#immersive-prompt-progress', 98.5);
    assert.ok((await snapshot(page, 0)).progress < 100, 'the test starts before the script finishes');
    await page.waitForFunction(() => Number(document.querySelector('#immersive-prompt-progress').value) === 100
      && document.querySelector('#immersive-prompt-play-btn').getAttribute('aria-pressed') === 'false');
    const ended = await snapshot(page, 0);
    assert.equal(ended.readingTime, '00:00', 'estimated text time reaches zero at natural end of scrolling');
    assertStillRecording(ended, 0, 0, 'natural end of the script');
    assert.match(await page.locator('#status-message').textContent(), /Continue falando.*Encerrar/i,
      'the end message explicitly invites the user to continue and finish when ready');
    await assertContinuesAfter(page, 0, ended, 'natural script end');

    // Seeking directly to the end must also leave the capture session running.
    await setRange(page, '#immersive-prompt-progress', 100);
    assertStillRecording(await snapshot(page, 0), 0, 0, 'seek to 100%');
    await page.locator('#immersive-pause-btn').click();
    await page.waitForFunction(() => window.__endRecorders[0].recorder.state === 'paused');
    const paused = await snapshot(page, 0);
    await page.waitForTimeout(2200);
    assert.equal((await snapshot(page, 0)).clock, paused.clock, 'manual pause freezes REC time after the script ends');
    assert.equal((await snapshot(page, 0)).progress, 100);
    await page.locator('#immersive-pause-btn').click();
    await page.waitForFunction(() => window.__endRecorders[0].recorder.state === 'recording');
    await assertContinuesAfter(page, 0, await snapshot(page, 0), 'resume after script end');

    await page.locator('#immersive-reset-btn').click();
    assert.equal(Number(await page.locator('#immersive-prompt-progress').inputValue()), 0);
    assertStillRecording(await snapshot(page, 0), 0, 0, 'return to the start while recording');
    await page.locator('#immersive-prompt-play-btn').click();
    await page.waitForFunction(() => Number(document.querySelector('#immersive-prompt-progress').value) > 0);
    assertStillRecording(await snapshot(page, 0), 0, 0, 'replay the script in the same take');
    await editScript(page, `${longScript}\n\nMinha conclusão extra, ainda neste mesmo vídeo.`);
    assertStillRecording(await snapshot(page, 0), 0, 0, 'edit the script in the same take');
    await setRange(page, '#immersive-prompt-progress', 100);
    await page.waitForFunction(() => document.querySelector('#immersive-prompt-play-btn').getAttribute('aria-pressed') === 'false');
    assertStillRecording(await snapshot(page, 0), 0, 0, 'seek to the amended script end');
    const first = await stopAndCheckBlob(page, 0, seconds(ended.clock) + 2);
    assert.deepEqual((await snapshot(page, 0)).events.map((event) => event.type), ['start', 'pause', 'resume', 'stop']);
    console.log(`PASS natural EOF, TEXTO 00:00, seek, pause/resume and script reset/replay/edit preserve one native recorder; manual stop saved ${first.duration.toFixed(2)}s with audio and video.`);

    await editScript(page, 'Considerações finais.');
    const estimated = seconds(await page.locator('#immersive-reading-time').textContent());
    assert.ok(estimated > 0 && estimated <= 2, 'the short non-scrolling script has a small reading estimate');
    await begin(page, 1);
    await page.waitForFunction((limit) => {
      const elapsed = document.querySelector('#immersive-recording-time').textContent.split(':')
        .reduce((total, part) => total * 60 + Number(part), 0);
      return elapsed > limit;
    }, estimated);
    const short = await snapshot(page, 1);
    assert.equal(short.progress, 0, 'short script fits in the teleprompter without scrolling');
    await assertContinuesAfter(page, 1, short, 'elapsed time exceeds the non-scrolling reading estimate');
    const second = await stopAndCheckBlob(page, 1, estimated + 2);
    console.log(`PASS short script continues beyond its ${estimated}s estimate; manual stop saved ${second.duration.toFixed(2)}s with audio and video.`);

    await editScript(page, '');
    assert.equal(await page.locator('#immersive-reading-time').textContent(), '00:00');
    await begin(page, 2);
    await assertContinuesAfter(page, 2, await snapshot(page, 2), 'empty script with TEXTO 00:00');
    const third = await stopAndCheckBlob(page, 2, 1.5);
    console.log(`PASS empty script with TEXTO 00:00 allows freely speaking until manual stop (${third.duration.toFixed(2)}s).`);
    assert.deepEqual(errors, [], 'no unhandled browser errors');
    console.log('All manual recording completion checks passed. Synthetic devices only.');
    await context.close();
  } finally { await browser.close(); }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
