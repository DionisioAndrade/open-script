const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../studio/static/studio/frame-pump.js'), 'utf8');

function harness({ native = true, frames = 0, stream = false } = {}) {
  let now = 0;
  let serial = 0;
  const timers = new Map();
  const callbacks = new Map();
  const video = {
    readyState: 2, videoWidth: 1920, videoHeight: 1080, currentTime: 0,
    srcObject: stream ? {} : null,
    getVideoPlaybackQuality: () => ({ totalVideoFrames: frames }),
  };
  if (native) {
    video.requestVideoFrameCallback = (callback) => {
      const id = ++serial;
      callbacks.set(id, callback);
      return id;
    };
    video.cancelVideoFrameCallback = (id) => callbacks.delete(id);
  }
  const context = {
    window: {}, performance: { now: () => now },
    setTimeout: (callback, delay) => {
      const id = ++serial;
      timers.set(id, { callback, time: now + delay });
      return id;
    },
    clearTimeout: (id) => timers.delete(id),
  };
  vm.runInNewContext(source, context);
  function advance(target) {
    let iterations = 0;
    while (timers.size) {
      const [id, timer] = [...timers.entries()].sort((a, b) => a[1].time - b[1].time)[0];
      if (timer.time > target + 1e-7) break;
      if (++iterations > 10000) throw new Error('Timer busy loop.');
      now = timer.time;
      timers.delete(id);
      timer.callback();
    }
    now = target;
  }
  function emit(timestamp, presentedFrames, metadata = {}) {
    now = timestamp;
    const entries = [...callbacks.values()];
    callbacks.clear();
    for (const callback of entries) callback(timestamp, { presentedFrames, ...metadata });
  }
  return {
    video, timers, callbacks, advance, emit,
    setFrames: (value) => { frames = value; },
    start: (options) => context.window.StudioFramePump.start({ video, ...options }),
  };
}

const tests = [];
function test(name, run) { tests.push([name, run]); }

test('native pump copies 30 fps from 59.94 fps without drift', () => {
  const h = harness();
  let draws = 0;
  h.start({ frameRate: 30, draw: () => { draws += 1; } });
  assert.equal(h.callbacks.size, 0, 'Start is asynchronous.');
  h.advance(0);
  for (let frame = 0; frame < 600; frame += 1) h.emit(frame * 1000 / 59.94, frame + 1);
  assert.equal(draws, 300);
  assert.equal(h.callbacks.size, 1);
  assert.equal(h.timers.size, 0, 'Native path does not poll.');
});

test('native pump preserves every 29.97 fps source frame', () => {
  const h = harness();
  let draws = 0;
  h.start({ draw: () => { draws += 1; } });
  h.advance(0);
  for (let frame = 0; frame < 300; frame += 1) h.emit(frame * 1000 / 29.97, frame + 1);
  assert.equal(draws, 300);
});

test('120 fps source is bounded to requested 24 fps', () => {
  const h = harness();
  let draws = 0;
  h.start({ frameRate: 24, draw: () => { draws += 1; } });
  h.advance(0);
  for (let frame = 0; frame < 1200; frame += 1) h.emit(frame * 1000 / 120, frame + 1);
  assert.equal(draws, 240);
});

test('media clock preserves 30 fps when callbacks are quantized to a 75 Hz display', () => {
  const h = harness();
  let draws = 0;
  h.start({ frameRate: 30, draw: () => { draws += 1; } });
  h.advance(0);
  for (let frame = 0; frame < 300; frame += 1) {
    const mediaTime = frame / 30;
    const displayTimestamp = 10000 + Math.ceil(mediaTime * 75) * 1000 / 75;
    h.emit(displayTimestamp, frame + 1, { mediaTime });
  }
  assert.equal(draws, 300);
});

test('known 30 fps live source preserves frames even with zero media timestamps', () => {
  const h = harness({ stream: true });
  h.video.srcObject.getVideoTracks = () => [{ getSettings: () => ({ frameRate: 30 }) }];
  let draws = 0;
  h.start({ frameRate: 30, draw: () => { draws += 1; } });
  h.advance(0);
  for (let frame = 0; frame < 300; frame += 1) {
    const timestamp = 10000 + Math.ceil(frame * 75 / 30) * 1000 / 75;
    h.emit(timestamp, frame + 1, { mediaTime: 0 });
  }
  assert.equal(draws, 300);
});

test('file media clock limits 30 fps source to 24 fps under display jitter', () => {
  const h = harness();
  let draws = 0;
  h.start({ frameRate: 24, draw: () => { draws += 1; } });
  h.advance(0);
  for (let frame = 0; frame < 300; frame += 1) {
    const mediaTime = frame / 30;
    h.emit(10000 + Math.ceil(mediaTime * 75) * 1000 / 75, frame + 1, { mediaTime });
  }
  assert.equal(draws, 240);
});

test('live capture clock preserves 30 fps when media timestamps stay zero', () => {
  const h = harness({ stream: true });
  let draws = 0;
  h.start({ frameRate: 30, draw: () => { draws += 1; } });
  h.advance(0);
  for (let frame = 0; frame < 300; frame += 1) {
    const captureTime = frame * 1000 / 30;
    h.emit(10000 + Math.ceil(frame * 75 / 30) * 1000 / 75, frame + 1, { mediaTime: 0, captureTime });
  }
  assert.equal(draws, 300);
});

test('pause suppresses drawing and resumes without creating another loop', () => {
  const h = harness();
  let paused = false;
  let draws = 0;
  const pump = h.start({ draw: () => { draws += 1; }, shouldDraw: () => !paused });
  h.advance(0);
  h.emit(0, 1);
  paused = true;
  for (let frame = 2; frame < 100; frame += 1) h.emit(frame * 1000 / 30, frame);
  assert.equal(draws, 1);
  paused = false;
  h.emit(4000, 100);
  assert.equal(draws, 2);
  pump.stop();
  pump.stop();
  assert.equal(h.callbacks.size, 0);
  assert.equal(h.timers.size, 0);
});

test('readiness and dimensions are required before drawing', () => {
  const h = harness();
  let draws = 0;
  h.video.readyState = 1;
  h.start({ draw: () => { draws += 1; } });
  h.advance(0);
  h.emit(0, 1);
  h.video.readyState = 2;
  h.video.videoWidth = 0;
  h.emit(34, 2);
  h.video.videoWidth = 640;
  h.emit(68, 3);
  assert.equal(draws, 1);
});

test('fallback uses video frame count to skip duplicates', () => {
  const h = harness({ native: false, frames: 5 });
  let draws = 0;
  const pump = h.start({ draw: () => { draws += 1; } });
  h.advance(200);
  assert.equal(draws, 1);
  h.setFrames(6);
  h.advance(250);
  assert.equal(draws, 2);
  pump.stop();
  assert.equal(h.timers.size, 0);
});

test('zero live frame counter falls back to advancing media time', () => {
  const h = harness({ native: false, stream: true });
  let draws = 0;
  h.start({ draw: () => { draws += 1; } });
  h.video.currentTime = 1;
  h.advance(200);
  assert.equal(draws, 1);
  h.video.currentTime = 1.1;
  h.advance(250);
  assert.equal(draws, 2);
});

test('live stream with unavailable frame metadata still draws at output rate', () => {
  const h = harness({ native: false, stream: true });
  let draws = 0;
  h.start({ draw: () => { draws += 1; } });
  h.advance(1000);
  assert.equal(draws, 30);
  assert.equal(h.timers.size, 1);
});

test('unusable native callback falls back to timer and ignores stale callbacks', () => {
  const h = harness({ stream: true });
  let stale;
  let draws = 0;
  h.video.requestVideoFrameCallback = (callback) => { stale = callback; return undefined; };
  h.start({ draw: () => { draws += 1; } });
  h.advance(100);
  assert.equal(draws, 3);
  stale(101, { presentedFrames: 1 });
  assert.equal(draws, 3);
  assert.equal(h.timers.size, 1);
});

test('native registration throwing falls back without reporting a draw error', () => {
  const h = harness({ stream: true });
  let draws = 0;
  let errors = 0;
  h.video.requestVideoFrameCallback = () => { throw new Error('Unsupported.'); };
  h.start({ draw: () => { draws += 1; }, onError: () => { errors += 1; } });
  h.advance(100);
  assert.equal(draws, 3);
  assert.equal(errors, 0);
});

test('draw failure reports once and permanently stops, even if handler throws', () => {
  const h = harness();
  let errors = 0;
  h.start({
    draw: () => { throw new Error('Draw failed.'); },
    onError: () => { errors += 1; throw new Error('Handler failed.'); },
  });
  h.advance(0);
  h.emit(0, 1);
  h.emit(34, 2);
  assert.equal(errors, 1);
  assert.equal(h.callbacks.size, 0);
  assert.equal(h.timers.size, 0);
});

test('stop before first callback cancels all work', () => {
  const h = harness();
  let draws = 0;
  h.start({ draw: () => { draws += 1; } }).stop();
  h.advance(100);
  h.emit(100, 1);
  assert.equal(draws, 0);
  assert.equal(h.callbacks.size, 0);
  assert.equal(h.timers.size, 0);
});

for (const [name, run] of tests) {
  run();
  console.log(`PASS ${name}`);
}
console.log(`${tests.length} frame scheduler checks passed.`);
