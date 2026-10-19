/* Native MediaRecorder coverage for the frame scheduler's timer fallback.
 * Run with Playwright available: node tests/frame-pump-browser.cjs
 * Uses synthetic canvas video, with no server or real camera permissions.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const helper = fs.readFileSync(path.join(__dirname, '../studio/static/studio/frame-pump.js'), 'utf8');
const browserPath = process.env.TEST_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';

(async () => {
  const browser = await chromium.launch({ executablePath: browserPath, headless: true });
  try {
    const page = await browser.newPage();
    await page.setContent('<!doctype html><video muted playsinline style="width:320px;height:180px"></video>');
    await page.addScriptTag({ content: helper });
    for (const forceZeroClock of [false, true]) {
      const report = await page.evaluate(async (zeroClock) => {
        const delay = (time) => new Promise((resolve) => setTimeout(resolve, time));
        const source = document.createElement('canvas');
        source.width = 320;
        source.height = 180;
        const sourceContext = source.getContext('2d', { alpha: false });
        let sourceFrames = 0;
        const paint = () => {
          sourceContext.fillStyle = sourceFrames++ % 2 ? '#F34D4D' : '#0F172A';
          sourceContext.fillRect(0, 0, source.width, source.height);
        };
        paint();
        const sourceTimer = setInterval(paint, 1000 / 30);
        const input = source.captureStream(30);
        const video = document.querySelector('video');
        Object.defineProperty(video, 'requestVideoFrameCallback', { configurable: true, value: undefined });
        Object.defineProperty(video, 'cancelVideoFrameCallback', { configurable: true, value: undefined });
        Object.defineProperty(video, 'getVideoPlaybackQuality', { configurable: true, value: () => ({ totalVideoFrames: 0 }) });
        if (zeroClock) Object.defineProperty(video, 'currentTime', { configurable: true, get: () => 0 });
        video.srcObject = input;
        await video.play();
        if (video.readyState < 2) await new Promise((resolve) => video.addEventListener('loadeddata', resolve, { once: true }));

        const output = document.createElement('canvas');
        output.width = 180;
        output.height = 320;
        const context = output.getContext('2d', { alpha: false });
        context.fillStyle = '#0F172A';
        context.fillRect(0, 0, output.width, output.height);
        context.drawImage(video, 0, 109.375, 180, 101.25);
        const stream = output.captureStream(30);
        const mimeType = ['video/webm;codecs=vp8', 'video/webm'].find((type) => MediaRecorder.isTypeSupported(type));
        const recorder = new MediaRecorder(stream, { mimeType, videoBitsPerSecond: 800000 });
        const chunks = [];
        recorder.addEventListener('dataavailable', (event) => { if (event.data.size) chunks.push(event.data); });
        const stopped = new Promise((resolve) => recorder.addEventListener('stop', resolve, { once: true }));
        let draws = 0;
        let drawEnabled = true;
        let errors = 0;
        const pump = window.StudioFramePump.start({
          video, frameRate: 30,
          draw: () => { draws += 1; context.drawImage(video, 0, 109.375, 180, 101.25); },
          shouldDraw: () => drawEnabled,
          onError: () => { errors += 1; },
        });
        recorder.start(200);
        await delay(1100);
        drawEnabled = false;
        recorder.pause();
        const beforePause = draws;
        await delay(300);
        const afterPause = draws;
        recorder.resume();
        drawEnabled = true;
        await delay(1100);
        pump.stop();
        const beforeStop = draws;
        await delay(100);
        const afterStop = draws;
        const stateBeforeManualStop = recorder.state;
        recorder.stop();
        await stopped;
        const blob = new Blob(chunks, { type: recorder.mimeType });
        const decoded = document.createElement('video');
        decoded.muted = true;
        decoded.playsInline = true;
        const url = URL.createObjectURL(blob);
        decoded.src = url;
        await decoded.play();
        const dimensions = [decoded.videoWidth, decoded.videoHeight];
        decoded.pause();
        decoded.removeAttribute('src');
        decoded.load();
        URL.revokeObjectURL(url);
        clearInterval(sourceTimer);
        for (const track of [...input.getTracks(), ...stream.getTracks()]) track.stop();
        video.pause();
        video.srcObject = null;
        if (zeroClock) delete video.currentTime;
        return {
          forceZeroClock: zeroClock, draws, sourceFrames, errors,
          beforePause, afterPause, beforeStop, afterStop,
          stateBeforeManualStop, bytes: blob.size, dimensions,
        };
      }, forceZeroClock);
      assert.equal(report.errors, 0);
      assert.equal(report.beforePause, report.afterPause, 'Pause does not draw canvas frames.');
      assert.equal(report.beforeStop, report.afterStop, 'Stopped helper cannot draw again.');
      assert.equal(report.stateBeforeManualStop, 'recording', 'The owner controls the native recorder.');
      assert.ok(report.draws >= 50 && report.draws <= 70, `Fallback frame count: ${report.draws}.`);
      assert.ok(report.bytes > 1000, 'Native MediaRecorder produced a nonempty clip.');
      assert.deepEqual(report.dimensions, [180, 320], 'Encoded video decodes in 9:16.');
      console.log(`PASS native timer fallback ${JSON.stringify(report)}`);
    }
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exitCode = 1; });
