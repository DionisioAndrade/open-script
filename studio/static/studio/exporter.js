(() => {
  "use strict";

  const t = (source, params = {}) => window.StudioI18n?.t(source, params) || source;
  const localizedError = (source) => {
    const error = new Error(t(source));
    error.i18nSource = source;
    return error;
  };
  const AudioContextClass = window.AudioContext || window.webkitAudioContext;
  const abortError = () => {
    const source = "Exportação cancelada.";
    const error = new DOMException(t(source), "AbortError");
    error.i18nSource = source;
    return error;
  };
  const supported = (mimeType) => {
    try { return typeof window.MediaRecorder?.isTypeSupported === "function" && MediaRecorder.isTypeSupported(mimeType); }
    catch { return false; }
  };

  function getFormats(hasAudio = true) {
    const candidates = [
      {
        value: "mp4", label: "MP4",
        types: hasAudio
          ? ["video/mp4;codecs=avc1.640028,mp4a.40.2", "video/mp4;codecs=avc1,mp4a.40.2", "video/mp4"]
          : ["video/mp4;codecs=avc1.640028", "video/mp4;codecs=avc1", "video/mp4"],
      },
      {
        value: "webm", label: "WebM",
        types: hasAudio
          ? ["video/webm;codecs=vp8,opus", "video/webm;codecs=vp9,opus", "video/webm"]
          : ["video/webm;codecs=vp8", "video/webm;codecs=vp9", "video/webm"],
      },
    ];
    return candidates.flatMap(({ value, label, types }) => {
      const mimeType = types.find(supported);
      return mimeType ? [{ value, label, mimeType }] : [];
    });
  }

  // A fresh media element owns its audio graph on each export. The live camera
  // and the clip-library players never become part of this stream.
  async function exportVideo(options) {
    const {
      blob, mimeType, signal, onProgress, preview,
      hasAudio = true, duration: durationHint,
      width = 720, height = 1280, frameRate = 30,
      videoBitsPerSecond = 5000000, audioBitsPerSecond = 128000,
    } = options || {};
    if (signal?.aborted) throw abortError();
    if (!(blob instanceof Blob) || !blob.size) throw localizedError("A gravação está vazia. Grave um novo vídeo para exportar.");
    if (!preview?.append || !preview.isConnected) throw localizedError("Abra a janela de exportação antes de preparar o vídeo.");
    if (typeof window.MediaRecorder !== "function" || typeof HTMLCanvasElement.prototype.captureStream !== "function") {
      throw localizedError("Este navegador não oferece exportação de vídeo. Abra o estúdio em um navegador atualizado.");
    }
    if (!supported(mimeType)) throw localizedError("O formato escolhido não está disponível neste navegador. Escolha outro formato.");
    const supportedRatios = [9 / 16, 4 / 5, 1, 16 / 9, 2 / 3];
    if (!Number.isInteger(width) || !Number.isInteger(height) || width < 144 || height < 144
      || width > 2160 || height > 3840
      || !supportedRatios.some((ratio) => Math.abs(width / height - ratio) < 0.001)) {
      throw localizedError("Escolha uma resolução válida para o formato selecionado.");
    }
    if (!Number.isFinite(frameRate) || frameRate < 1 || frameRate > 60
      || !Number.isFinite(videoBitsPerSecond) || videoBitsPerSecond < 100000 || videoBitsPerSecond > 50000000
      || (hasAudio && (!Number.isFinite(audioBitsPerSecond) || audioBitsPerSecond < 16000 || audioBitsPerSecond > 320000))) {
      throw localizedError("As opções de qualidade do vídeo são inválidas.");
    }
    if (hasAudio && !AudioContextClass) throw localizedError("Este navegador não permite preservar o áudio na exportação. Atualize o navegador ou exporte sem áudio.");
    if (document.visibilityState === "hidden") throw localizedError("Mantenha esta aba aberta para preparar o vídeo.");

    const video = document.createElement("video");
    const canvas = document.createElement("canvas");
    const chunks = [];
    const listeners = [];
    const timers = new Set();
    let sourceURL = null;
    let audioContext = null;
    let audioSource = null;
    let audioDestination = null;
    let canvasStream = null;
    let requestCaptureFrame = null;
    let outputStream = null;
    let recorder = null;
    let framePump = null;
    let progressTimer = null;
    let lastProgress = -1;
    let lastPlaybackPosition = 0;
    let lastPlaybackMovement = performance.now();
    let recorderStarted = false;
    let finishing = false;
    let cleaned = false;
    let interruption = null;
    let rejectInterruption;
    const interrupted = new Promise((_, reject) => { rejectInterruption = reject; });
    interrupted.catch(() => {});

    const interrupt = (error) => {
      if (interruption || cleaned) return;
      interruption = error;
      rejectInterruption(error);
    };
    const listen = (target, event, handler) => {
      target.addEventListener(event, handler);
      listeners.push(() => target.removeEventListener(event, handler));
    };
    const guard = async (promise, timeout, message) => {
      if (interruption) throw interruption;
      let timer;
      const deadline = new Promise((_, reject) => {
        timer = setTimeout(() => reject(localizedError(message)), timeout);
        timers.add(timer);
      });
      try { return await Promise.race([promise, interrupted, deadline]); }
      finally { clearTimeout(timer); timers.delete(timer); }
    };
    const waitFor = (target, event, test) => {
      if (test?.()) return Promise.resolve();
      const events = Array.isArray(event) ? event : [event];
      return new Promise((resolve) => {
        const ready = () => {
          if (test && !test()) return;
          for (const name of events) target.removeEventListener(name, ready);
          resolve();
        };
        for (const name of events) listen(target, name, ready);
      });
    };
    const reportProgress = (fraction) => {
      const value = Math.max(0, Math.min(1, fraction));
      if (value < 1 && value - lastProgress < 0.002) return;
      lastProgress = value;
      try { onProgress?.(value); } catch { /* The export does not depend on UI rendering. */ }
    };
    const cleanup = () => {
      cleaned = true;
      framePump?.stop();
      requestCaptureFrame = null;
      clearInterval(progressTimer);
      for (const timer of timers) clearTimeout(timer);
      for (const remove of listeners) remove();
      if (recorder && recorder.state !== "inactive") {
        try { recorder.stop(); } catch { /* The encoder may already have stopped after an error. */ }
      }
      video.pause();
      audioSource?.disconnect();
      audioDestination?.disconnect();
      const ownedTracks = new Set([
        ...(outputStream?.getTracks() || []),
        ...(canvasStream?.getTracks() || []),
        ...(audioDestination?.stream.getTracks() || []),
      ]);
      for (const track of ownedTracks) track.stop();
      if (audioContext && audioContext.state !== "closed") audioContext.close().catch(() => {});
      video.removeAttribute("src");
      video.load();
      video.remove();
      if (sourceURL) URL.revokeObjectURL(sourceURL);
      canvas.width = 1;
      canvas.height = 1;
    };

    try {
      if (signal) listen(signal, "abort", () => interrupt(abortError()));
      listen(document, "visibilitychange", () => {
        if (document.visibilityState === "hidden") interrupt(localizedError("A exportação foi interrompida porque a aba ficou em segundo plano. Mantenha o estúdio aberto e tente novamente."));
      });
      listen(video, "error", () => interrupt(localizedError("O navegador não conseguiu ler esta gravação. Tente outro formato ou grave novamente.")));
      video.className = "export-render-video";
      video.setAttribute("data-i18n-aria-label", "Prévia da exportação");
      video.setAttribute("aria-label", t("Prévia da exportação"));
      video.playsInline = true;
      video.setAttribute("playsinline", "");
      video.setAttribute("webkit-playsinline", "");
      video.disablePictureInPicture = true;
      video.controls = false;
      video.loop = false;
      video.preload = "auto";
      video.playbackRate = 1;
      video.style.cssText = "width:100%;height:100%;object-fit:contain;display:block";
      preview.append(video);
      canvas.width = width;
      canvas.height = height;
      const context = canvas.getContext("2d", { alpha: false });
      if (!context) throw localizedError("O navegador não conseguiu preparar a imagem da exportação.");

      let resumeAudio = Promise.resolve();
      if (hasAudio) {
        audioContext = new AudioContextClass();
        audioSource = audioContext.createMediaElementSource(video);
        audioDestination = audioContext.createMediaStreamDestination();
        // The unmuted element preserves the source audio, while only the
        // recording destination receives it instead of the speakers.
        audioSource.connect(audioDestination);
        video.muted = false;
        video.volume = 1;
        resumeAudio = audioContext.resume();
        resumeAudio.catch(() => {});
        listen(audioContext, "statechange", () => {
          if (recorderStarted && !finishing && audioContext.state !== "running") {
            interrupt(localizedError("O navegador suspendeu o áudio. Mantenha a aba aberta e tente exportar novamente."));
          }
        });
      } else {
        video.muted = true;
      }
      sourceURL = URL.createObjectURL(blob);
      video.src = sourceURL;
      video.load();
      reportProgress(0);

      // Both calls run inside the caller's click before the first await. This
      // unlocks playback and Web Audio on mobile without an extra gesture.
      const ready = waitFor(video, "loadeddata", () => video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA && video.videoWidth > 0);
      const primePlayback = video.play();
      primePlayback?.catch(() => {});
      await guard(ready, 20000, "A gravação demorou para abrir. Tente exportar novamente.");
      await guard(Promise.resolve(primePlayback), 12000, "O navegador não liberou a reprodução. Toque em Exportar novamente.");
      video.pause();
      await guard(resumeAudio, 12000, "O navegador não liberou o áudio. Toque em Exportar novamente.");
      if (hasAudio && audioContext.state !== "running") throw localizedError("O navegador não liberou o áudio. Toque em Exportar novamente.");

      const finiteDuration = () => Number.isFinite(video.duration) && video.duration > 0 ? video.duration : 0;
      let sourceDuration = finiteDuration() || (Number.isFinite(durationHint) && durationHint > 0 ? durationHint : 0);
      if (!sourceDuration && !Number.isFinite(video.duration)) {
        // Recorded WebM often has no duration header. Seeking past the end lets
        // the decoder discover it without playing the entire clip twice.
        const probed = waitFor(video, "seeked");
        video.currentTime = Number.MAX_SAFE_INTEGER;
        try { await guard(probed, 6000, "Não foi possível determinar a duração desta gravação."); }
        catch (error) { if (interruption) throw error; }
        sourceDuration = finiteDuration();
        if (!sourceDuration && video.seekable.length) sourceDuration = video.seekable.end(video.seekable.length - 1);
      }
      if (video.currentTime > 0.001 || video.seeking) {
        const rewind = waitFor(video, "seeked", () => !video.seeking && video.currentTime < 0.05);
        video.currentTime = 0;
        await guard(rewind, 12000, "Não foi possível voltar ao início do vídeo. Tente exportar novamente.");
      }
      // A rewind can temporarily discard the decoded frame after loadeddata
      // has already fired. canplay signals its availability again after seek.
      await guard(waitFor(video, ["loadeddata", "canplay"], () => video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA), 12000, "Não foi possível preparar o primeiro quadro do vídeo.");

      // The decoded clip has fixed dimensions. Keep its scaling geometry out of
      // the frame callback so every encoded frame only needs one canvas draw.
      const sourceWidth = video.videoWidth;
      const sourceHeight = video.videoHeight;
      const scale = Math.min(width / sourceWidth, height / sourceHeight);
      const drawWidth = sourceWidth * scale;
      const drawHeight = sourceHeight * scale;
      const destinationX = (width - drawWidth) / 2;
      const destinationY = (height - drawHeight) / 2;
      // Keep the entire camera frame visible in every social format. The
      // source dimensions stay fixed, so the padding needs one fill only.
      context.fillStyle = "#0F172A";
      context.fillRect(0, 0, width, height);
      const draw = () => {
        if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) return;
        context.drawImage(video, 0, 0, sourceWidth, sourceHeight,
          destinationX, destinationY, drawWidth, drawHeight);
        if (requestCaptureFrame) {
          try { requestCaptureFrame(); }
          catch { requestCaptureFrame = null; } // Native capture remains available.
        }
      };
      draw();
      canvasStream = canvas.captureStream(frameRate);
      if (!canvasStream.getVideoTracks().length) throw localizedError("O navegador não conseguiu gerar o vídeo na resolução escolhida.");
      const canvasTrack = canvasStream.getVideoTracks()[0];
      if (typeof canvasTrack.requestFrame === "function") requestCaptureFrame = () => canvasTrack.requestFrame();
      outputStream = new MediaStream([
        ...canvasStream.getVideoTracks(),
        ...(hasAudio ? audioDestination.stream.getAudioTracks() : []),
      ]);
      recorder = new MediaRecorder(outputStream, {
        mimeType, videoBitsPerSecond,
        ...(hasAudio ? { audioBitsPerSecond } : {}),
      });
      listen(recorder, "dataavailable", (event) => { if (!cleaned && event.data?.size) chunks.push(event.data); });
      listen(recorder, "error", () => interrupt(localizedError("O navegador não conseguiu exportar com esta qualidade. Tente uma resolução menor ou outro formato.")));
      const stopped = new Promise((resolve) => {
        listen(recorder, "stop", () => {
          if (!finishing && !cleaned) interrupt(localizedError("A exportação parou antes do final. Tente uma resolução menor."));
          resolve();
        });
      });
      const ended = waitFor(video, "ended", () => video.ended);
      const started = waitFor(recorder, "start", () => recorder.state === "recording");
      recorder.start(500);
      recorderStarted = true;
      // WebM encoders can defer their start event until a captured frame
      // arrives. The source is intentionally paused here, so prime the canvas
      // once instead of depending on a playback callback before video.play().
      draw();
      framePump = window.StudioFramePump.start({
        video, frameRate, draw,
        shouldDraw: () => !finishing && !cleaned && !interruption,
        onError: () => interrupt(localizedError("Não foi possível processar a imagem deste vídeo. Tente exportar novamente.")),
      });
      // Some native encoders publish start only after receiving video/audio.
      // Begin the already-unlocked source now so waiting for start cannot hold
      // the playback that supplies those frames. Recording is already active.
      const playback = video.play();
      playback?.catch(() => interrupt(localizedError("O navegador não liberou a reprodução para exportar. Toque em Exportar novamente.")));
      await guard(started, 12000, "O navegador não conseguiu iniciar a exportação.");
      lastPlaybackMovement = performance.now();
      progressTimer = setInterval(() => {
        const position = video.currentTime;
        if (position > lastPlaybackPosition + 0.005) {
          lastPlaybackPosition = position;
          lastPlaybackMovement = performance.now();
        } else if (performance.now() - lastPlaybackMovement > 15000) {
          interrupt(localizedError("A reprodução do vídeo travou. Tente exportar novamente ou escolha uma resolução menor."));
        }
        const measuredDuration = finiteDuration();
        if (measuredDuration) sourceDuration = measuredDuration;
        if (sourceDuration > 0) reportProgress(Math.min(0.98, position / sourceDuration * 0.98));
      }, 200);
      await guard(Promise.resolve(playback), 12000, "O navegador não liberou a reprodução para exportar. Toque em Exportar novamente.");
      await guard(ended, sourceDuration ? Math.max(30000, sourceDuration * 1400 + 20000) : 12 * 60 * 60 * 1000,
        "A exportação demorou além do esperado. Tente uma resolução menor.");
      if (lastPlaybackPosition < 0.005 && video.currentTime < 0.005) throw localizedError("Esta gravação não contém vídeo suficiente para exportar.");
      finishing = true;
      clearInterval(progressTimer);
      framePump?.stop();
      draw();
      if (recorder.state !== "inactive") recorder.stop();
      await guard(stopped, 15000, "O navegador demorou para finalizar o arquivo. Tente exportar novamente.");
      if (interruption) throw interruption;
      const actualMimeType = recorder.mimeType || chunks.find((chunk) => chunk.type)?.type;
      if (!actualMimeType || !/^video\/(mp4|webm)(;|$)/i.test(actualMimeType)) {
        throw localizedError("O navegador não informou um formato de vídeo válido.");
      }
      const result = new Blob(chunks, { type: actualMimeType });
      if (!result.size) throw localizedError("A exportação ficou vazia. Tente uma resolução menor ou outro formato.");
      reportProgress(1);
      return {
        blob: result, mimeType: actualMimeType, width, height, frameRate, hasAudio: !!hasAudio,
        videoBitsPerSecond: recorder.videoBitsPerSecond,
        audioBitsPerSecond: hasAudio ? recorder.audioBitsPerSecond : 0,
      };
    } finally {
      cleanup();
    }
  }

  window.StudioExporter = Object.freeze({ getFormats, exportVideo });
})();
