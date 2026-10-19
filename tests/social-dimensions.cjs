/* Encoder regression: validate supported social dimensions and export native
 * videos with synthetic colors/audio. No camera or microphone is accessed.
 * Set NODE_PATH to the bundled node_modules and run node tests/social-dimensions.cjs.
 */
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { chromium } = require('playwright');

const root = path.join(__dirname, '..');
const resolutionError = 'Escolha uma resolução válida para o formato selecionado.';

async function validateDimensions() {
  const video = {
    addEventListener() {}, removeEventListener() {}, pause() {}, load() {}, remove() {},
    setAttribute() {}, removeAttribute() {}, style: {},
  };
  const environment = {
    Blob, DOMException, URL, performance, setTimeout, clearTimeout, clearInterval,
    MediaRecorder: Object.assign(function Recorder() {}, { isTypeSupported: () => true }),
    HTMLCanvasElement: { prototype: { captureStream() {} } },
    document: {
      visibilityState: 'visible', addEventListener() {}, removeEventListener() {},
      createElement: (tag) => tag === 'video' ? video : { getContext: () => null },
    },
  };
  environment.window = environment;
  vm.runInNewContext(fs.readFileSync(path.join(root, 'studio/static/studio/exporter.js'), 'utf8'), environment);
  const options = {
    blob: new Blob(['synthetic clip']), mimeType: 'video/webm', hasAudio: false,
    preview: { append() {}, isConnected: true },
  };
  for (const [width, height] of [[360, 640], [720, 1280], [1080, 1920], [2160, 3840],
    [360, 450], [720, 900], [1080, 1350], [360, 360], [1080, 1080],
    [256, 144], [640, 360], [1280, 720], [1920, 1080], [360, 540], [720, 1080], [1000, 1500]]) {
    await assert.rejects(environment.StudioExporter.exportVideo({ ...options, width, height }),
      (error) => error.i18nSource === 'O navegador não conseguiu preparar a imagem da exportação.',
      `${width}×${height} passes resolution validation before the stubbed canvas refuses allocation`);
  }
  for (const [width, height] of [[144, 143], [143, 144], [0, 0], [720.5, 1280], [720, 1280.5],
    [NaN, 1280], [720, Infinity], [720, 7200], [2304, 4096], [2400, 1350],
    [720, 960], [1024, 768], [640, 480], [359, 640]]) {
    await assert.rejects(environment.StudioExporter.exportVideo({ ...options, width, height }),
      (error) => error.i18nSource === resolutionError,
      `${width}×${height} must reject unsupported ratios, nonintegers and unsafe bounds`);
  }
  console.log('PASS supported social dimensions and invalid aspect/size bounds');
}

async function runNativeEncoders() {
  const browser = await chromium.launch({
    executablePath: process.env.TEST_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true, args: ['--autoplay-policy=no-user-gesture-required'],
  });
  const errors = [];
  try {
    const page = await browser.newPage();
    page.on('pageerror', (error) => errors.push(error.message));
    await page.setContent('<main><div id="preview"></div></main>');
    await page.addScriptTag({ path: path.join(root, 'studio/static/studio/frame-pump.js') });
    await page.addScriptTag({ path: path.join(root, 'studio/static/studio/exporter.js') });
    const results = await page.evaluate(async () => {
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
      const check = (condition, message) => { if (!condition) throw new Error(message); };
      const originalRecorder = MediaRecorder;
      const reports = [];
      window.MediaRecorder = class extends originalRecorder {
        constructor(stream, options) {
          super(stream, options);
          reports.push({ recorder: this, stream, settings: stream.getVideoTracks()[0]?.getSettings() });
        }
      };
      const formats = StudioExporter.getFormats(true);
      const sourceFormat = formats.find((format) => format.value === 'webm') || formats[0];
      check(!!sourceFormat, 'Native video encoder required');
      const canvas = document.createElement('canvas');
      canvas.width = 360; canvas.height = 640;
      const context = canvas.getContext('2d');
      const paint = () => {
        context.fillStyle = '#fe2010'; context.fillRect(0, 0, 360, 640);
        context.fillStyle = '#2044f0'; context.fillRect(120, 0, 120, 640);
        context.fillStyle = '#10ef20'; context.fillRect(240, 0, 120, 640);
        context.fillStyle = '#ffffff'; context.fillRect(0, 0, 360, 24);
        context.fillStyle = '#ffff00'; context.fillRect(0, 616, 360, 24);
      };
      paint();
      const sourceStream = canvas.captureStream(30);
      const audio = new AudioContext();
      const oscillator = audio.createOscillator();
      const gain = audio.createGain();
      const audioDestination = audio.createMediaStreamDestination();
      gain.gain.value = 0.15;
      oscillator.frequency.value = 440;
      oscillator.connect(gain).connect(audioDestination);
      audioDestination.stream.getAudioTracks().forEach((track) => sourceStream.addTrack(track));
      await audio.resume();
      oscillator.start();
      const chunks = [];
      const sourceRecorder = new MediaRecorder(sourceStream, { mimeType: sourceFormat.mimeType });
      sourceRecorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data); };
      const stopped = new Promise((resolve) => { sourceRecorder.onstop = resolve; });
      const timer = setInterval(paint, 1000 / 30);
      sourceRecorder.start(200);
      await sleep(2000);
      sourceRecorder.stop();
      await stopped;
      clearInterval(timer);
      oscillator.stop();
      sourceStream.getTracks().forEach((track) => track.stop());
      await audio.close();
      const source = new Blob(chunks, { type: sourceRecorder.mimeType });
      const sourceBytes = new Uint8Array(await source.arrayBuffer());
      const originalSize = source.size;

      async function decode(result) {
        const url = URL.createObjectURL(result.blob);
        const video = document.createElement('video');
        video.muted = true; video.playsInline = true; video.src = url;
        try {
          await new Promise((resolve, reject) => {
            video.onloadedmetadata = resolve;
            video.onerror = () => reject(new Error('Encoded file failed to decode'));
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
          await sleep(180);
          video.pause();
          const output = document.createElement('canvas');
          output.width = video.videoWidth; output.height = video.videoHeight;
          const ctx = output.getContext('2d');
          ctx.drawImage(video, 0, 0);
          const scale = Math.min(output.width / 360, output.height / 640);
          const contentWidth = 360 * scale;
          const contentHeight = 640 * scale;
          const x = (output.width - contentWidth) / 2;
          const y = (output.height - contentHeight) / 2;
          const pixel = (px, py) => [...ctx.getImageData(Math.floor(px), Math.floor(py), 1, 1).data];
          const colors = [[254, 32, 16], [32, 68, 240], [16, 239, 32]];
          const samples = [0.05, 0.5, 0.95].map((fraction) => pixel(x + contentWidth * fraction, y + contentHeight / 2));
          samples.forEach((sample, index) => check(colors[index].every((channel, at) => Math.abs(sample[at] - channel) < 25),
            'Every camera edge and center must retain its color; found ' + JSON.stringify(samples)));
          const top = pixel(x + contentWidth / 2, y + 12 * scale);
          const bottom = pixel(x + contentWidth / 2, y + 628 * scale);
          check(top.slice(0, 3).every((value) => value > 220), 'Top camera boundary remains visible');
          check(bottom[0] > 220 && bottom[1] > 220 && bottom[2] < 25, 'Bottom camera boundary remains visible');
          if (x > 2) {
            for (const px of [x / 2, output.width - x / 2]) {
              const padding = pixel(px, output.height / 2);
              check([15, 23, 42].every((channel, at) => Math.abs(padding[at] - channel) < 20), 'Navy side padding required');
            }
          }
          const bytes = await result.blob.arrayBuffer();
          const metadata = new TextDecoder('latin1').decode(bytes);
          check(metadata.includes('A_OPUS') || metadata.includes('Opus') || metadata.includes('mp4a'), 'Recorded audio codec remains in exported file');
          const decoder = new AudioContext();
          let audioRms;
          try {
            const decodedAudio = await decoder.decodeAudioData(bytes.slice(0));
            const channel = decodedAudio.getChannelData(0);
            audioRms = Math.sqrt(channel.reduce((total, value) => total + value * value, 0) / channel.length);
            check(audioRms > 0.025, 'Export preserves audible source tone');
          } finally { await decoder.close(); }
          return { width: output.width, height: output.height, duration, audioRms, bytes: result.blob.size,
            mimeType: result.mimeType, samples };
        } finally {
          video.pause(); video.removeAttribute('src'); video.load(); URL.revokeObjectURL(url);
        }
      }

      const output = [];
      const preview = document.querySelector('#preview');
      for (const [ratio, width, height] of [['9:16', 360, 640], ['1:1', 360, 360], ['16:9', 640, 360],
        ['4:5', 360, 450], ['2:3', 360, 540]]) {
        const progress = [];
        const result = await StudioExporter.exportVideo({
          blob: source, mimeType: sourceFormat.mimeType, duration: 2, hasAudio: true,
          width, height, frameRate: 24, videoBitsPerSecond: 1500000, preview,
          onProgress: (fraction) => progress.push(fraction),
        });
        check(result.width === width && result.height === height && result.hasAudio, 'Export return dimensions match request');
        const decoded = await decode(result);
        check(decoded.width === width && decoded.height === height, 'Actual output file dimensions match request');
        check(decoded.duration > 1.5 && decoded.duration < 3.5, 'Source duration is retained: ' + decoded.duration);
        check(progress[0] === 0 && progress.at(-1) === 1 && progress.every((value, i) => !i || value >= progress[i - 1]),
          'Export progress starts at zero, advances monotonically and reaches one');
        check(!preview.children.length, 'Export removes its temporary preview player');
        const report = reports.at(-1);
        check(report.recorder.state === 'inactive' && report.stream.getTracks().every((track) => track.readyState === 'ended'),
          'Export stops all owned recording tracks');
        check(report.settings.frameRate === 24, 'Frame rate reaches native encoder');
        output.push({ ratio, ...decoded });
      }

      const controller = new AbortController();
      const cancellation = StudioExporter.exportVideo({
        blob: source, mimeType: sourceFormat.mimeType, duration: 2, hasAudio: true,
        width: 360, height: 360, preview, signal: controller.signal,
      });
      await sleep(350);
      controller.abort();
      let cancelled = false;
      try { await cancellation; } catch (error) { cancelled = error.name === 'AbortError'; }
      check(cancelled, 'Cancellation must reject with AbortError');
      check(!preview.children.length, 'Cancellation removes temporary preview');
      check(reports.every((report) => report.recorder.state === 'inactive'
        && report.stream.getTracks().every((track) => track.readyState === 'ended')), 'Cancellation stops every owned track');
      const retained = new Uint8Array(await source.arrayBuffer());
      check(source.size === originalSize && retained.every((byte, i) => byte === sourceBytes[i]), 'Original clip remains intact after conversion/cancellation');
      return output;
    });
    for (const result of results) console.log('PASS native social export', JSON.stringify(result));
    assert.deepEqual(errors, [], 'No unhandled browser errors');
    console.log('PASS cancellation and source preservation for social export');
  } finally { await browser.close(); }
}

validateDimensions().then(runNativeEncoders).catch((error) => { console.error(error); process.exitCode = 1; });
