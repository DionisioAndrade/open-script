(() => {
  "use strict";

  // Copy new video frames at the requested output rate, independently of the
  // screen's refresh rate. The owner controls recording, playback and cleanup.
  function start({ video, frameRate = 30, draw, onError, shouldDraw } = {}) {
    if (!video || typeof draw !== "function") {
      throw new TypeError("A video element and a draw callback are required.");
    }
    if (!Number.isFinite(frameRate) || frameRate <= 0) {
      throw new RangeError("The frame rate must be a positive number.");
    }

    const interval = 1000 / frameRate;
    const tolerance = Math.min(2, interval * 0.08);
    const clock = () => performance.now();
    let stopped = false;
    let timer = null;
    let videoCallback = null;
    let generation = 0;
    let nextDraw = null;
    let nextTimer = null;
    let lastSignal = null;
    let rateClock = null;
    let lastMediaTime = null;
    let lastCaptureTime = null;
    let useVideoFrames = typeof video.requestVideoFrameCallback === "function"
      && typeof video.cancelVideoFrameCallback === "function";
    let sourceFrameRate = null;
    try {
      const rate = video.srcObject?.getVideoTracks?.()[0]?.getSettings?.().frameRate;
      if (Number.isFinite(rate) && rate > 0) sourceFrameRate = rate;
    } catch { /* A file or an older live-stream implementation has no settings. */ }

    function stop() {
      if (stopped) return;
      stopped = true;
      generation += 1;
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
      if (videoCallback !== null) {
        try { video.cancelVideoFrameCallback(videoCallback); } catch { /* Already consumed. */ }
        videoCallback = null;
      }
    }

    function fail(error) {
      if (stopped) return;
      stop();
      try { onError?.(error); } catch { /* Cleanup must survive an error handler. */ }
    }

    function frameSignal(metadata) {
      if (Number.isFinite(metadata?.presentedFrames) && metadata.presentedFrames > 0) {
        return ["presented", metadata.presentedFrames];
      }
      // Some live-stream implementations expose this API but report zero
      // forever. Zero is not a usable frame counter; use the media clock.
      try {
        const frames = video.getVideoPlaybackQuality?.().totalVideoFrames;
        if (Number.isFinite(frames) && frames > 0) return ["decoded", frames];
      } catch { /* Playback-quality information is optional. */ }
      const mediaTime = video.currentTime;
      if (Number.isFinite(mediaTime) && (mediaTime !== 0 || !video.srcObject)) {
        return ["time", mediaTime];
      }
      // A live camera with neither a working counter nor a media clock can
      // still be copied safely at the requested rate.
      return null;
    }

    function copyFrame(timestamp, metadata) {
      if (video.readyState < 2 || !video.videoWidth || !video.videoHeight) return;
      if (shouldDraw?.() === false) return;

      // Native video callbacks already identify a new composited frame. Use
      // their counter when supplied, without trusting a zero live mediaTime.
      const signal = useVideoFrames && !Number.isFinite(metadata?.presentedFrames)
        ? null : frameSignal(metadata);
      if (signal && lastSignal && signal[0] === lastSignal[0] && signal[1] === lastSignal[1]) return;

      let rateTimestamp = timestamp;
      let nextClock = "callback";
      if (useVideoFrames) {
        const mediaTime = metadata?.mediaTime;
        const captureTime = metadata?.captureTime;
        if (Number.isFinite(mediaTime) && mediaTime >= 0
          && (mediaTime > 0 || !video.srcObject)
          && (lastMediaTime === null || mediaTime > lastMediaTime)) {
          rateTimestamp = mediaTime * 1000;
          nextClock = "media";
        } else if (Number.isFinite(captureTime) && captureTime >= 0
          && (lastCaptureTime === null || captureTime > lastCaptureTime)) {
          rateTimestamp = captureTime;
          nextClock = "capture";
        }
        if (Number.isFinite(mediaTime)) lastMediaTime = mediaTime;
        if (Number.isFinite(captureTime)) lastCaptureTime = captureTime;
      }
      if (nextClock !== rateClock) {
        rateClock = nextClock;
        nextDraw = null;
      }

      // A 30 fps camera composited by a 75 Hz screen has uneven callback
      // timestamps. Never discard its new frames merely because the display
      // delivered one early; the source is already at or below the target.
      const sourceWithinTarget = useVideoFrames && sourceFrameRate !== null
        && sourceFrameRate <= frameRate + 0.01;
      if (!sourceWithinTarget && nextDraw !== null && rateTimestamp + tolerance < nextDraw) return;

      draw(timestamp);
      lastSignal = signal;
      if (nextDraw === null || rateTimestamp < nextDraw - interval * 2) {
        nextDraw = rateTimestamp + interval;
      } else {
        // Keep the fractional remainder instead of rounding every callback.
        // This preserves 30 fps with 59.94/29.97 fps sources and timer jitter.
        const steps = Math.max(1, Math.floor((rateTimestamp + tolerance - nextDraw) / interval) + 1);
        nextDraw += steps * interval;
      }
    }

    function tick(timestamp, metadata) {
      if (stopped) return;
      try { copyFrame(timestamp, metadata); }
      catch (error) { fail(error); return; }
      if (!stopped) schedule();
    }

    function scheduleTimer() {
      if (stopped) return;
      const timestamp = clock();
      if (nextTimer === null) nextTimer = timestamp + interval;
      else {
        nextTimer += interval;
        if (nextTimer <= timestamp) nextTimer = timestamp + interval;
      }
      timer = setTimeout(() => {
        timer = null;
        tick(clock());
      }, Math.max(1, nextTimer - timestamp));
    }

    function schedule() {
      if (stopped) return;
      if (useVideoFrames) {
        const requestGeneration = generation;
        try {
          const handle = video.requestVideoFrameCallback((timestamp, metadata) => {
            if (stopped || !useVideoFrames || generation !== requestGeneration) return;
            videoCallback = null;
            tick(Number.isFinite(timestamp) ? timestamp : clock(), metadata);
          });
          if (!Number.isFinite(handle)) throw new TypeError("Invalid video callback handle.");
          videoCallback = handle;
          return;
        } catch {
          // Older engines can expose a nonfunctional video callback method.
          // Its outstanding callbacks cannot reactivate the timer pump.
          generation += 1;
          useVideoFrames = false;
        }
      }
      scheduleTimer();
    }

    // Returning the handle first lets callers stop safely inside draw/onError,
    // including when the first registration has to fall back to a timer.
    timer = setTimeout(() => {
      timer = null;
      schedule();
    }, 0);
    return { stop };
  }

  window.StudioFramePump = Object.freeze({ start });
})();
