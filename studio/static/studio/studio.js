(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const ui = Object.fromEntries([
    "script-input", "script-title", "word-count", "reading-time", "save-status",
    "import-script-btn", "script-file-input", "clear-script-btn", "camera-video",
    "camera-placeholder", "camera-placeholder-title", "camera-placeholder-message", "camera-btn", "camera-placeholder-btn", "camera-status",
    "microphone-status", "recording-status", "recording-dot", "camera-select",
    "microphone-select", "preview-stage", "prompt-overlay", "prompt-text",
    "prompt-progress", "progress-label", "countdown-overlay", "countdown-number",
    "countdown-label", "recording-time", "record-btn", "pause-record-btn", "stop-btn",
    "prompt-play-btn", "reset-script-btn", "speed-input", "speed-value", "font-select",
    "size-input", "size-value", "opacity-input", "opacity-value", "text-color",
    "show-prompt-input", "mirror-input", "fullscreen-btn", "status-message",
    "recordings-btn", "recordings-dialog", "close-recordings-btn", "recordings-list",
    "recordings-empty", "recording-count", "help-btn", "help-dialog", "close-help-btn",
    "export-dialog", "close-export-btn", "export-preview", "export-recording-title", "export-filename",
    "export-profile", "export-profile-note", "export-aspect", "export-preview-wrap", "export-preview-caption",
    "export-format", "export-resolution", "export-quality", "export-framerate", "export-estimate", "export-note",
    "export-progress", "export-progress-bar", "export-status", "export-submit-btn", "export-cancel-btn", "export-save-link",
    "immersive-record-btn", "immersive-pause-btn", "immersive-stop-btn",
    "immersive-prompt-play-btn", "immersive-reset-btn", "immersive-camera-btn",
    "immersive-recording-status", "immersive-recording-time", "immersive-recording-dot",
    "immersive-recordings-btn", "immersive-fullscreen-btn", "immersive-help-btn", "editor-btn", "settings-btn",
    "immersive-speed-input", "immersive-speed-value", "immersive-prompt-progress", "immersive-progress-label", "immersive-reading-time",
    "studio-btn", "close-script-btn", "close-settings-btn", "script-panel", "settings-panel", "sheet-backdrop",
    "language-select",
    "logout-form", "logout-btn", "account-action-note", "account-session-warning",
    "logout-dialog", "logout-heading", "logout-note", "logout-confirm-btn", "logout-cancel-btn",
  ].map((id) => [id.replaceAll("-", "_"), $(id)]));

  if (!ui.script_input || !ui.camera_video || !ui.preview_stage) return;

  const accountKey = document.body.dataset.userKey || "";
  const STORAGE_KEY = accountKey ? `open-script.studio.v1:user-${accountKey}` : "open-script.studio.v1";
  let accountSessionValid = true;
  let accountNavigationAllowed = false;
  let pendingAccountAction = null;
  const accountNavigationLinks = Array.from(document.querySelectorAll("[data-account-navigation]"));
  const accountForms = Array.from(document.querySelectorAll("[data-account-form]"));
  let sessionCheck = null;
  let lastSessionCheck = 0;
  const CANCELLED = Symbol("capture-cancelled");
  const fonts = {
    sans: '"Segoe UI", system-ui, sans-serif',
    serif: 'Georgia, "Times New Roman", serif',
    mono: '"JetBrains Mono", Consolas, monospace',
  };
  const activeStates = new Set(["preparing", "countdown", "recording", "paused", "stopping"]);
  const pendingStreams = new Set();
  let state = "idle";
  let prepareIntent = "preview";
  let stream = null;
  let cameraIssue = null;
  let requestGeneration = 0;
  let session = null;
  let recordTransition = null;
  let countdownTimer = null;
  let countdownDeadline = 0;
  let countdownSeconds = 5;
  let rehearsalPlaying = false;
  let promptRequested = false;
  let promptOffset = 0;
  let maximumScroll = 0;
  let scriptWordCount = 0;
  let scriptHasText = false;
  let readingSpeed = 40;
  let animationFrame = null;
  let previousFrameTime = null;
  let lastPromptControlsTime = -Infinity;
  let recordingClockTimer = null;
  let measurementFrame = null;
  let measurementRatio = null;
  let saveTimer = null;
  let autoSize = true;
  let storageFailed = false;
  let notificationTimer = null;
  let importGeneration = 0;
  let recordings = [];
  let exportRecording = null;
  let exportJob = null;
  let preparedExport = null;
  let deviceListGeneration = 0;
  let cameraExplicitlySelected = false;
  let wakeLock = null;
  let wakeLockRequest = null;
  let wakeLockGeneration = 0;
  let mobilePanel = null;
  let panelReturnFocus = null;
  let pausedInBackground = false;
  const mobileViewport = window.matchMedia("(max-width: 760px), (max-width: 1024px) and (max-height: 520px)");
  const portraitSize = { width: 1080, height: 1920 };
  const translatedElements = new Map();
  const t = (source, params = {}) => window.StudioI18n?.t(source, params)
    || String(source).replace(/\{(\w+)\}/g, (match, key) => Object.hasOwn(params, key) ? String(params[key]) : match);
  const locale = () => window.StudioI18n?.locale || "pt-BR";

  const clamp = (value, minimum, maximum) => Math.min(maximum, Math.max(minimum, value));
  const numberValue = (element, fallback) => Number(element?.value) || fallback;
  const hasVideo = () => stream?.getVideoTracks().some((track) => track.readyState === "live") || false;
  const hasAudio = () => stream?.getAudioTracks().some((track) => track.readyState === "live") || false;
  const hasText = () => scriptHasText;
  const secondsLabel = (milliseconds) => {
    const totalSeconds = Math.floor(Math.max(0, milliseconds) / 1000);
    const seconds = String(totalSeconds % 60).padStart(2, "0");
    const minutes = String(Math.floor(totalSeconds / 60) % 60).padStart(2, "0");
    const hours = Math.floor(totalSeconds / 3600);
    return hours ? `${String(hours).padStart(2, "0")}:${minutes}:${seconds}` : `${minutes}:${seconds}`;
  };
  const emptyParams = Object.freeze({});
  const text = (element, value, params = emptyParams) => {
    if (!element) return;
    const rendered = params === null ? String(value) : t(value, params);
    if (params === null) translatedElements.delete(element);
    else {
      const previous = translatedElements.get(element);
      if (!previous || previous.source !== value || previous.params !== params) translatedElements.set(element, { source: value, params });
    }
    if (element.textContent !== rendered) element.textContent = rendered;
  };
  const renderRecordingTime = (value) => [ui.recording_time, ui.immersive_recording_time].forEach((element) => text(element, value));
  const statusText = (element, value) => {
    if (!element) return;
    let label = element.querySelector("[data-status-label]");
    if (!label) {
      Array.from(element.childNodes).filter((node) => node.nodeType === Node.TEXT_NODE).forEach((node) => node.remove());
      label = document.createElement("span");
      label.dataset.statusLabel = "";
      element.append(label);
    }
    text(label, value);
  };
  const disable = (element, value) => { if (element) element.disabled = value; };
  const labelButton = (element, source, params = {}) => {
    if (!element) return;
    const label = t(source, params);
    element.setAttribute("aria-label", label);
    element.title = label;
    const labelElement = element.querySelector("[data-button-label]")
      || Array.from(element.children).filter((child) => child.tagName === "SPAN" && !child.classList.contains("record-button-dot")).at(-1);
    if (labelElement) text(labelElement, source, params);
    else if (!element.querySelector("svg")) text(element, source, params);
  };
  const renderRange = (element) => {
    if (!element) return;
    const minimum = Number(element.min || 0);
    const maximum = Number(element.max || 100);
    const percentage = maximum > minimum ? clamp((Number(element.value) - minimum) / (maximum - minimum) * 100, 0, 100) : 0;
    const progress = `${percentage}%`;
    if (element.style.getPropertyValue("--range-progress") !== progress) element.style.setProperty("--range-progress", progress);
  };

  function notify(message, tone = "info") {
    if (!ui.status_message) return;
    clearTimeout(notificationTimer);
    ui.status_message.hidden = false;
    ui.status_message.dataset.tone = tone;
    text(ui.status_message, message);
    notificationTimer = setTimeout(() => { ui.status_message.hidden = true; }, ["warning", "error"].includes(tone) ? 12000 : 3500);
  }

  function clearNotification() {
    if (["warning", "error"].includes(ui.status_message?.dataset.tone)) return;
    clearTimeout(notificationTimer);
    if (ui.status_message) ui.status_message.hidden = true;
  }

  function environmentError(recording = false) {
    if (!navigator.mediaDevices?.getUserMedia) {
      if (window.location.protocol === "http:") {
        return "O navegador não disponibilizou a câmera e o microfone neste endereço HTTP. O aplicativo permite HTTP; para testar, use http://localhost:8000 neste computador ou libere esta origem nas opções do seu navegador de testes.";
      }
      return "Este navegador não oferece acesso à câmera. Abra o projeto em uma versão atual do Chrome ou Edge.";
    }
    if (recording && typeof window.MediaRecorder !== "function") {
      return "Este navegador não oferece gravação de vídeo. Você ainda pode usar o ensaio do roteiro.";
    }
    if (recording && typeof HTMLCanvasElement.prototype.captureStream !== "function") {
      return "Este navegador não oferece gravação vertical 9:16. Atualize o navegador ou use Chrome, Edge ou Safari atualizado.";
    }
    return null;
  }

  function renderState() {
    const cameraActive = hasVideo();
    const reading = (state === "idle" && rehearsalPlaying) || (state === "recording" && promptRequested);
    const busy = state !== "idle";
    const preparing = state === "preparing";
    const liveRecording = state === "recording" || state === "paused";
    ui.preview_stage.dataset.state = state;
    ui.preview_stage.classList.toggle("has-camera", cameraActive);
    ui.preview_stage.classList.toggle("is-recording", state === "recording");
    ui.preview_stage.classList.toggle("is-paused", state === "paused");
    ui.preview_stage.classList.toggle("is-rehearsing", state === "idle" && rehearsalPlaying);
    if (ui.camera_placeholder) ui.camera_placeholder.hidden = cameraActive || (state === "idle" && rehearsalPlaying);
    ui.camera_placeholder?.setAttribute("aria-busy", String(preparing));
    ui.camera_placeholder?.classList.toggle("has-camera-issue", !!cameraIssue);
    text(ui.camera_placeholder_title, preparing ? "Autorize a câmera e o microfone" : cameraIssue ? "Precisamos do acesso aos dispositivos" : "Seu roteiro, na sua câmera.");
    text(ui.camera_placeholder_message, preparing
      ? "Confirme as permissões no navegador. A gravação só começa quando você escolher gravar."
      : cameraIssue || "Permita o acesso à câmera e ao microfone para começar.");
    labelButton(ui.camera_placeholder_btn, preparing ? "Aguardando autorização…" : cameraIssue ? "Tentar novamente" : "Ativar câmera e microfone");
    if (ui.countdown_overlay) ui.countdown_overlay.hidden = state !== "countdown";
    if (ui.prompt_overlay) ui.prompt_overlay.hidden = !ui.show_prompt_input?.checked;

    labelButton(ui.camera_btn, preparing ? "Preparando câmera…" : cameraActive ? "Desligar câmera" : "Ativar câmera");
    ui.camera_btn?.setAttribute("aria-pressed", String(cameraActive));
    disable(ui.camera_btn, busy || !!environmentError());
    disable(ui.camera_placeholder_btn, busy || !!environmentError());
    disable(ui.camera_select, busy || ui.camera_select?.options.length < 2);
    disable(ui.microphone_select, busy || ui.microphone_select?.options.length < 2);
    disable(ui.record_btn, busy || !accountSessionValid || !!environmentError(true));
    labelButton(ui.record_btn,
      state === "countdown" ? "Começando em {seconds}s…"
        : state === "stopping" ? "Salvando vídeo…"
          : preparing && prepareIntent === "record" ? "Preparando gravação…"
            : "Iniciar gravação", { seconds: countdownSeconds });
    disable(ui.pause_record_btn, !liveRecording || !!recordTransition);
    labelButton(ui.pause_record_btn,
      recordTransition === "pause" ? "Pausando…"
        : recordTransition === "resume" ? "Retomando…"
          : state === "paused" ? "Retomar" : "Pausar");
    ui.pause_record_btn?.querySelector("use")?.setAttribute("href", state === "paused" ? "#i-play" : "#i-pause");
    disable(ui.stop_btn, state === "idle" || state === "stopping");
    labelButton(ui.stop_btn, preparing || state === "countdown" ? "Cancelar" : "Encerrar");
    disable(ui.prompt_play_btn, ["preparing", "countdown", "paused", "stopping"].includes(state) || !hasText());
    labelButton(ui.prompt_play_btn, reading ? "Pausar texto" : state === "recording" ? "Retomar texto" : "Ensaiar");
    ui.prompt_play_btn?.querySelector("use")?.setAttribute("href", reading ? "#i-pause" : "#i-play");
    ui.prompt_play_btn?.setAttribute("aria-pressed", String(reading));

    // Both layouts use the same recorder and state, including pending transitions.
    for (const [source, duplicate] of [
      [ui.camera_btn, ui.immersive_camera_btn],
      [ui.record_btn, ui.immersive_record_btn],
      [ui.pause_record_btn, ui.immersive_pause_btn],
      [ui.stop_btn, ui.immersive_stop_btn],
      [ui.prompt_play_btn, ui.immersive_prompt_play_btn],
    ]) {
      if (!source || !duplicate) continue;
      duplicate.disabled = source.disabled;
      const label = source.querySelector("[data-button-label]")?.textContent || source.getAttribute("aria-label") || source.textContent.trim();
      labelButton(duplicate, label);
      duplicate.setAttribute("aria-label", label);
      duplicate.title = label;
      const pressed = source.getAttribute("aria-pressed");
      if (pressed !== null) duplicate.setAttribute("aria-pressed", pressed);
      const icon = source.querySelector("use")?.getAttribute("href");
      if (icon) duplicate.querySelector("use")?.setAttribute("href", icon);
    }

    statusText(ui.camera_status, preparing ? "Aguardando acesso à câmera" : cameraActive ? "Câmera ativa" : "Câmera desligada");
    statusText(ui.microphone_status,
      preparing ? "Aguardando microfone"
        : cameraActive ? hasAudio() ? "Microfone ativo" : "Sem microfone · vídeo sem áudio"
          : "Microfone desligado");
    ui.camera_status?.querySelector(".status-dot")?.classList.toggle("muted", !cameraActive);
    if (ui.camera_status) ui.camera_status.title = stream?.getVideoTracks()[0]?.label || "";
    if (ui.microphone_status) ui.microphone_status.title = stream?.getAudioTracks()[0]?.label || "";
    const statuses = {
      idle: rehearsalPlaying ? "Ensaiando roteiro" : "Pronto para gravar",
      preparing: "Preparando câmera",
      countdown: "Prepare-se",
      recording: "Gravando",
      paused: "Gravação pausada",
      stopping: "Salvando vídeo",
    };
    text(ui.recording_status, statuses[state]);
    text(ui.immersive_recording_status, statuses[state]);
    [ui.recording_dot, ui.immersive_recording_dot].forEach((element) => { if (element) element.dataset.state = state; });
    syncPromptAnimation();
    syncRecordingClock();
    renderAccountState();
  }

  function renderAccountState() {
    const busy = state !== "idle" || !!exportJob;
    const guestAction = !accountKey || !accountSessionValid || accountForms.length > 0;
    const note = state === "preparing" || state === "countdown"
      ? guestAction ? "Cancele a preparação antes de continuar." : "Cancele a preparação antes de sair."
      : state !== "idle"
        ? guestAction ? "Encerre a gravação antes de continuar." : "Encerre a gravação antes de sair."
        : exportJob ? guestAction ? "Cancele a exportação antes de continuar." : "Cancele a exportação antes de sair." : "";
    disable(ui.logout_btn, !accountSessionValid || busy);
    accountNavigationLinks.forEach((link) => link.setAttribute("aria-disabled", String(busy)));
    accountForms.forEach((form) => {
      const button = form.querySelector('[type="submit"]');
      disable(button, busy || !accountSessionValid || button?.dataset.available !== "true");
    });
    text(ui.account_action_note, note);
    if (ui.account_action_note) ui.account_action_note.hidden = !note;
    if (ui.account_session_warning) ui.account_session_warning.hidden = accountSessionValid;
  }

  async function checkAccountSession(force = false) {
    const url = document.body.dataset.sessionUrl;
    if (!accountKey || !url || sessionCheck || (!force && Date.now() - lastSessionCheck < 10000)) return;
    lastSessionCheck = Date.now();
    const controller = new AbortController();
    sessionCheck = controller;
    const timeout = setTimeout(() => controller.abort(), 5000);
    try {
      const response = await fetch(url, { credentials: "same-origin", cache: "no-store", signal: controller.signal });
      if (!response.ok) return;
      const current = await response.json();
      const valid = current.authenticated === true && current.user_key === accountKey;
      if (current.authenticated === true && typeof current.csrf_token === "string") {
        for (const form of [ui.logout_form, ...accountForms]) {
          const input = form?.querySelector('[name="csrfmiddlewaretoken"]');
          if (input) input.value = current.csrf_token;
        }
      }
      if (valid === accountSessionValid) return;
      accountSessionValid = valid;
      // Keep existing takes and capture alive. Only the user stops a recording.
      renderState();
      if (!valid) notify("Sua sessão terminou. Salve os vídeos antes de entrar novamente.", "warning");
    } catch {
      // A lost connection is not evidence that the user's session has ended.
    } finally {
      clearTimeout(timeout);
      if (sessionCheck === controller) sessionCheck = null;
    }
  }

  function finishAccountAction() {
    const action = pendingAccountAction;
    if (!action || state !== "idle" || exportJob) return;
    const logoutAction = action.kind === "logout" || action.kind === "guest";
    if (logoutAction && !ui.logout_form) return;
    if (action.kind === "logout" && !accountSessionValid) return;
    if (action.kind === "form" && !accountSessionValid) return;
    saveSettings();
    accountNavigationAllowed = true;
    if (logoutAction) HTMLFormElement.prototype.submit.call(ui.logout_form);
    else if (action.kind === "form") {
      const language = action.form.querySelector('[name="lang"]');
      if (language) language.value = window.StudioI18n?.language || "pt-BR";
      HTMLFormElement.prototype.submit.call(action.form);
    }
    else window.location.assign(action.url);
  }

  function requestAccountAction(action) {
    if (state !== "idle" || exportJob
      || (["logout", "form"].includes(action.kind) && !accountSessionValid)) { renderAccountState(); return; }
    pendingAccountAction = action;
    if (recordings.some((recording) => !recording.exported)) {
      const signingOut = action.kind === "logout";
      for (const [element, source] of [
        [ui.logout_heading, signingOut ? "Sair da conta" : "Salvar vídeos antes de continuar"],
        [ui.logout_note, signingOut
          ? "Você tem vídeos ainda não salvos. Exporte antes de sair ou confirme para descartá-los."
          : "Você tem vídeos ainda não salvos. Exporte antes de continuar ou confirme para descartá-los."],
        [ui.logout_confirm_btn, signingOut ? "Sair e descartar vídeos" : "Continuar e descartar vídeos"],
      ]) {
        if (element) element.dataset.i18n = source;
        text(element, source);
      }
      setMobilePanel(null, false);
      openDialog(ui.logout_dialog);
    } else finishAccountAction();
  }

  function requestAccountLogout(event) {
    event.preventDefault();
    requestAccountAction({ kind: "logout" });
  }

  function requestAccountNavigation(event) {
    if (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    requestAccountAction({
      kind: event.currentTarget.id === "account-guest-link" ? "guest" : "navigation",
      url: event.currentTarget.href,
    });
  }

  function setState(nextState) {
    state = nextState;
    renderState();
    if (["countdown", "recording", "paused"].includes(state)) acquireWakeLock();
    else releaseWakeLock();
  }

  async function acquireWakeLock() {
    if (!navigator.wakeLock?.request || document.visibilityState === "hidden" || wakeLock || wakeLockRequest) return;
    const generation = wakeLockGeneration;
    const pending = navigator.wakeLock.request("screen");
    wakeLockRequest = pending;
    try {
      const lock = await pending;
      if (generation !== wakeLockGeneration || !["countdown", "recording", "paused"].includes(state)) {
        await lock.release();
        return;
      }
      wakeLock = lock;
      lock.addEventListener("release", () => { if (wakeLock === lock) wakeLock = null; }, { once: true });
    } catch {
      // A battery-saving policy can decline this optional convenience.
    } finally {
      if (wakeLockRequest === pending) wakeLockRequest = null;
    }
  }

  function releaseWakeLock() {
    wakeLockGeneration += 1;
    const lock = wakeLock;
    wakeLock = null;
    lock?.release().catch(() => {});
  }

  function settingsSnapshot() {
    return {
      version: 2,
      script: ui.script_input.value,
      title: ui.script_title?.value || "",
      speed: numberValue(ui.speed_input, 40),
      font: ui.font_select?.value || "sans",
      size: numberValue(ui.size_input, 36),
      autoSize,
      opacity: Number(ui.opacity_input?.value ?? 0),
      color: ui.text_color?.value || "#ffffff",
      showPrompt: ui.show_prompt_input?.checked ?? true,
      mirror: ui.mirror_input?.checked ?? true,
    };
  }

  function saveSettings() {
    clearTimeout(saveTimer);
    saveTimer = null;
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(settingsSnapshot()));
      statusText(ui.save_status, "Salvo neste navegador");
      storageFailed = false;
    } catch {
      statusText(ui.save_status, "Salvamento local indisponível");
      if (!storageFailed) notify("O navegador bloqueou o salvamento do roteiro. Você pode continuar, mas copie seu texto antes de sair.", "warning");
      storageFailed = true;
    }
  }

  function scheduleSave() {
    statusText(ui.save_status, "Salvando…");
    clearTimeout(saveTimer);
    saveTimer = setTimeout(saveSettings, 350);
  }

  function restoreSettings() {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return;
      const saved = JSON.parse(raw);
      if (!saved || ![1, 2].includes(saved.version)) return;
      autoSize = saved.version === 1 ? saved.size === 32 : saved.autoSize === true;
      if (typeof saved.script === "string") ui.script_input.value = saved.script;
      if (typeof saved.title === "string" && ui.script_title) ui.script_title.value = saved.title.slice(0, 100);
      for (const [key, element, minimum, maximum] of [
        ["speed", ui.speed_input, 10, 100],
        ["size", ui.size_input, 20, 120],
        ["opacity", ui.opacity_input, 0, 70],
      ]) {
        if (element && typeof saved[key] === "number" && Number.isFinite(saved[key])) {
          const value = key === "opacity" && saved.version === 1 && saved.opacity === 18 ? 0 : saved[key];
          element.value = String(clamp(value, minimum, maximum));
        }
      }
      if (ui.font_select && Object.hasOwn(fonts, saved.font)) ui.font_select.value = saved.font;
      if (ui.text_color && /^#[0-9a-f]{6}$/i.test(saved.color)) ui.text_color.value = saved.color;
      if (ui.show_prompt_input && typeof saved.showPrompt === "boolean") ui.show_prompt_input.checked = saved.showPrompt;
      if (ui.mirror_input && typeof saved.mirror === "boolean") ui.mirror_input.checked = saved.mirror;
    } catch {
      // Invalid or blocked local storage does not prevent using the studio.
    }
  }

  function applyResponsiveFontSize() {
    if (!autoSize || !ui.size_input) return false;
    const size = window.innerWidth < 600 ? 36 : clamp(Math.round(window.innerWidth * 0.07), 42, 100);
    if (Number(ui.size_input.value) === size) return false;
    ui.size_input.value = String(size);
    return true;
  }

  function renderPromptSettings(preserveProgress = true) {
    if (preserveProgress && maximumScroll > 0) measurementRatio = promptOffset / maximumScroll;
    const speed = numberValue(ui.speed_input, 40);
    readingSpeed = speed;
    const size = numberValue(ui.size_input, 36);
    const opacity = Number(ui.opacity_input?.value ?? 0);
    const color = ui.text_color?.value || "#ffffff";
    text(ui.speed_value, `${speed} px/s`);
    text(ui.immersive_speed_value, String(speed));
    if (ui.immersive_speed_input) ui.immersive_speed_input.value = String(speed);
    text(ui.size_value, `${size} px`);
    text(ui.opacity_value, `${opacity}%`);
    [ui.speed_input, ui.immersive_speed_input, ui.size_input, ui.opacity_input].forEach(renderRange);
    if (ui.prompt_text) {
      ui.prompt_text.style.fontFamily = fonts[ui.font_select?.value] || fonts.sans;
      ui.prompt_text.style.fontSize = `${size}px`;
      ui.prompt_text.style.color = color;
    }
    if (ui.prompt_overlay) {
      ui.prompt_overlay.style.backgroundColor = `rgba(15, 23, 42, ${opacity / 100})`;
      ui.prompt_overlay.hidden = !ui.show_prompt_input?.checked;
    }
    ui.camera_video.classList.toggle("is-mirrored", ui.mirror_input?.checked ?? true);
    document.querySelectorAll("[data-color]").forEach((button) => {
      const selected = button.dataset.color.toLowerCase() === color.toLowerCase();
      button.classList.toggle("is-active", selected);
      button.setAttribute("aria-pressed", String(selected));
    });
    renderPromptPosition();
    queueMeasurement();
  }

  function updateScript() {
    text(ui.prompt_text, ui.script_input.value, null);
    promptOffset = 0;
    maximumScroll = 0;
    measurementRatio = 0;
    const script = ui.script_input.value.trim();
    scriptHasText = script.length > 0;
    scriptWordCount = script ? script.split(/\s+/u).length : 0;
    text(ui.word_count, String(scriptWordCount));
    if (!hasText()) {
      rehearsalPlaying = false;
      if (state === "idle") renderState();
    }
    queueMeasurement();
  }

  function queueMeasurement() {
    if (measurementFrame !== null) return;
    measurementFrame = requestAnimationFrame(() => {
      measurementFrame = null;
      measurePrompt();
    });
  }

  function measurePrompt() {
    if (!ui.prompt_text || !ui.prompt_overlay) return;
    const styles = getComputedStyle(ui.prompt_overlay);
    const viewport = ui.prompt_overlay.clientHeight - parseFloat(styles.paddingTop || 0) - parseFloat(styles.paddingBottom || 0);
    if (viewport > 0 && ui.prompt_text.offsetHeight > 0) {
      maximumScroll = Math.max(0, ui.prompt_text.offsetHeight - viewport);
      promptOffset = measurementRatio === null
        ? clamp(promptOffset, 0, maximumScroll)
        : clamp(measurementRatio, 0, 1) * maximumScroll;
      measurementRatio = null;
    }
    const estimated = maximumScroll > 0
      ? Math.ceil(maximumScroll / numberValue(ui.speed_input, 40))
      : Math.ceil(scriptWordCount / 150 * 60);
    text(ui.reading_time, `≈ ${secondsLabel(estimated * 1000)}`);
    renderPromptPosition();
    syncPromptAnimation();
  }

  function renderPromptPosition(updateControls = true) {
    if (ui.prompt_text) {
      const transform = `translateY(${-promptOffset}px)`;
      if (ui.prompt_text.style.transform !== transform) ui.prompt_text.style.transform = transform;
    }
    if (!updateControls) return;
    const percentage = maximumScroll > 0 ? clamp(promptOffset / maximumScroll * 100, 0, 100) : 0;
    [ui.prompt_progress, ui.immersive_prompt_progress].forEach((element) => {
      if (element) element.value = String(percentage);
      renderRange(element);
    });
    text(ui.progress_label, `${Math.round(percentage)}%`);
    text(ui.immersive_progress_label, `${Math.round(percentage)}%`);
    const remaining = maximumScroll > 0
      ? Math.ceil(Math.max(0, maximumScroll - promptOffset) / numberValue(ui.speed_input, 40))
      : Math.ceil(scriptWordCount / 150 * 60);
    text(ui.immersive_reading_time, secondsLabel(remaining * 1000));
  }

  function resetPrompt() {
    promptOffset = 0;
    measurementRatio = 0;
    renderPromptPosition();
  }

  function toggleRehearsal() {
    if (["preparing", "countdown", "paused", "stopping"].includes(state) || !hasText()) return;
    if (maximumScroll > 0 && promptOffset >= maximumScroll) resetPrompt();
    if (state === "recording") {
      promptRequested = !promptRequested;
    } else {
      rehearsalPlaying = !rehearsalPlaying;
    }
    if (ui.show_prompt_input && !ui.show_prompt_input.checked) {
      ui.show_prompt_input.checked = true;
      renderPromptSettings(false);
      scheduleSave();
    }
    previousFrameTime = null;
    renderState();
    queueMeasurement();
  }

  function elapsedMilliseconds(currentSession, now = performance.now()) {
    return currentSession.elapsedMs + (currentSession.segmentStart === null ? 0 : now - currentSession.segmentStart);
  }

  function freezeClock(currentSession) {
    currentSession.elapsedMs = elapsedMilliseconds(currentSession);
    currentSession.segmentStart = null;
    renderRecordingTime(secondsLabel(currentSession.elapsedMs));
  }

  function syncRecordingClock() {
    clearTimeout(recordingClockTimer);
    recordingClockTimer = null;
    if (!session || state !== "recording") return;
    const elapsed = elapsedMilliseconds(session);
    renderRecordingTime(secondsLabel(elapsed));
    // The label changes once per second; capture and scrolling keep their own
    // schedules, including when the script is finished or has no text.
    recordingClockTimer = setTimeout(syncRecordingClock, Math.max(20, 1000 - elapsed % 1000 + 5));
  }

  function shouldAnimatePrompt() {
    return hasText() && maximumScroll > 0
      && ((state === "idle" && rehearsalPlaying) || (state === "recording" && promptRequested));
  }

  function syncPromptAnimation() {
    const moving = shouldAnimatePrompt();
    ui.preview_stage.classList.toggle("is-prompt-moving", moving);
    if (moving) ensureAnimation();
    else {
      if (animationFrame !== null) cancelAnimationFrame(animationFrame);
      animationFrame = null;
      previousFrameTime = null;
    }
  }

  function ensureAnimation() {
    if (animationFrame === null) animationFrame = requestAnimationFrame(animate);
  }

  function animate(now) {
    animationFrame = null;
    const delta = previousFrameTime === null ? 0 : clamp((now - previousFrameTime) / 1000, 0, 0.1);
    previousFrameTime = now;
    if (shouldAnimatePrompt()) {
      promptOffset = Math.min(maximumScroll, promptOffset + readingSpeed * delta);
      // Move the text at display refresh rate, while sliders and remaining time
      // update at most ten times a second. Explicit input still updates at once.
      const updateControls = now - lastPromptControlsTime >= 100 || promptOffset >= maximumScroll;
      renderPromptPosition(updateControls);
      if (updateControls) lastPromptControlsTime = now;
      if (promptOffset >= maximumScroll) {
        // Finishing the script only stops text scrolling. The capture and its
        // clock stay active so the user can speak until choosing to stop.
        rehearsalPlaying = false;
        promptRequested = false;
        renderState();
        notify(state === "recording" ? "Fim do roteiro. Continue falando e toque em Encerrar quando quiser." : "Fim do roteiro. Volte ao início para ensaiar novamente.");
      }
    }
    if (shouldAnimatePrompt()) ensureAnimation();
    else previousFrameTime = null;
  }

  function stopStream(mediaStream) {
    mediaStream?.getTracks().forEach((track) => track.stop());
  }

  function waitForCameraFrame(generation) {
    const video = ui.camera_video;
    let playbackError = null;
    video.play().catch((error) => { playbackError = error; });
    return new Promise((resolve, reject) => {
      const deadline = performance.now() + 10000;
      const check = () => {
        if (generation !== requestGeneration) { reject(CANCELLED); return; }
        if (!hasVideo()) {
          reject({ previewMessage: "A câmera foi desconectada. Ative-a novamente antes de gravar." });
          return;
        }
        if (video.readyState >= HTMLMediaElement.HAVE_CURRENT_DATA && video.videoWidth > 0 && video.videoHeight > 0 && !video.paused) {
          resolve();
          return;
        }
        if (playbackError || performance.now() >= deadline) {
          reject({ previewMessage: "Não foi possível exibir imagens da câmera. Toque na prévia, confira a câmera e tente gravar novamente." });
          return;
        }
        setTimeout(check, 40);
      };
      check();
    });
  }

  function createPortraitOutput() {
    const video = ui.camera_video;
    if (video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA || !video.videoWidth || !video.videoHeight) {
      throw new Error("A câmera ainda não está exibindo imagens. Ative-a e tente novamente.");
    }
    const canvas = document.createElement("canvas");
    canvas.width = portraitSize.width;
    canvas.height = portraitSize.height;
    if (typeof canvas.captureStream !== "function") {
      throw new Error("Este navegador não permite gravar em 9:16. Atualize o navegador ou use Chrome, Edge ou Safari atualizado.");
    }
    const context = canvas.getContext("2d", { alpha: false });
    if (!context) throw new Error("Não foi possível preparar o vídeo vertical. Recarregue a página e tente novamente.");
    let framePump = null;
    let requestCaptureFrame = null;
    let sourceWidth = 0;
    let sourceHeight = 0;
    let imageWidth = 0;
    let imageHeight = 0;
    let imageLeft = 0;
    let imageTop = 0;
    let released = false;
    let outputStream = null;
    const drawCamera = () => {
      if (!video.videoWidth || !video.videoHeight || video.readyState < HTMLMediaElement.HAVE_CURRENT_DATA) return;
      if (video.videoWidth !== sourceWidth || video.videoHeight !== sourceHeight) {
        sourceWidth = video.videoWidth;
        sourceHeight = video.videoHeight;
        const scale = Math.min(canvas.width / sourceWidth, canvas.height / sourceHeight);
        imageWidth = sourceWidth * scale;
        imageHeight = sourceHeight * scale;
        imageLeft = (canvas.width - imageWidth) / 2;
        imageTop = (canvas.height - imageHeight) / 2;
        // Static padding is painted only when the source framing changes.
        context.fillStyle = "#0F172A";
        context.fillRect(0, 0, canvas.width, canvas.height);
      }
      // Preserve the complete camera frame. Script and mirror are preview-only.
      context.drawImage(video, imageLeft, imageTop, imageWidth, imageHeight);
      if (requestCaptureFrame) {
        try { requestCaptureFrame(); }
        catch { requestCaptureFrame = null; } // Keep the native capture timer as a fallback.
      }
    };
    const output = {
      stream: null,
      release() {
        if (released) return;
        released = true;
        framePump?.stop();
        framePump = null;
        requestCaptureFrame = null;
        stopStream(outputStream);
        canvas.width = 1;
        canvas.height = 1;
      },
    };
    let canvasStream = null;
    const clonedAudio = [];
    try {
      drawCamera();
      canvasStream = canvas.captureStream(30);
      if (!canvasStream.getVideoTracks().length) throw new Error("O navegador não conseguiu preparar a gravação vertical 9:16.");
      const canvasTrack = canvasStream.getVideoTracks()[0];
      if (typeof canvasTrack.requestFrame === "function") requestCaptureFrame = () => canvasTrack.requestFrame();
      for (const track of stream.getAudioTracks().filter((audioTrack) => audioTrack.readyState === "live")) clonedAudio.push(track.clone());
      outputStream = new MediaStream([...canvasStream.getVideoTracks(), ...clonedAudio]);
      output.stream = outputStream;
      framePump = window.StudioFramePump.start({
        video, frameRate: 30, draw: drawCamera,
        shouldDraw: () => !released && state !== "paused",
        onError: () => {
          if (session?.output === output) stopRecording("A câmera não conseguiu continuar o vídeo vertical. O trecho disponível será salvo.");
        },
      });
      return output;
    } catch (error) {
      stopStream(canvasStream);
      clonedAudio.forEach((track) => track.stop());
      output.release();
      throw error;
    }
  }

  function releaseCamera() {
    const oldStream = stream;
    stream = null;
    stopStream(oldStream);
    ui.camera_video.pause();
    ui.camera_video.srcObject = null;
    renderState();
    queueMeasurement();
  }

  function mediaFailure(problem) {
    const error = problem?.cause || problem;
    const device = problem?.kind === "microphone" ? "microfone" : "câmera";
    switch (error?.name) {
      case "NotAllowedError":
      case "PermissionDeniedError":
      case "SecurityError":
        return device === "câmera" ? "O acesso à câmera foi bloqueado. Libere a permissão no navegador e tente novamente." : "O acesso ao microfone foi bloqueado. Libere a permissão no navegador e tente novamente.";
      case "NotFoundError":
      case "DevicesNotFoundError":
        return device === "câmera" ? "Nenhuma câmera foi encontrada. Conecte uma câmera e tente novamente." : "O microfone selecionado não está disponível. Escolha outro dispositivo.";
      case "NotReadableError":
      case "TrackStartError":
        return device === "câmera" ? "Não foi possível acessar a câmera. Feche outros aplicativos que estejam usando esse dispositivo e tente novamente." : "Não foi possível acessar o microfone. Feche outros aplicativos que estejam usando esse dispositivo e tente novamente.";
      case "OverconstrainedError":
      case "ConstraintNotSatisfiedError":
        return device === "câmera" ? "O dispositivo selecionado não está disponível. Escolha outra câmera e tente novamente." : "O dispositivo selecionado não está disponível. Escolha outro microfone e tente novamente.";
      default:
        return device === "câmera" ? "Não foi possível ativar a câmera. Verifique o dispositivo e tente novamente." : "Não foi possível ativar o microfone. Verifique o dispositivo e tente novamente.";
    }
  }

  async function resetCameraZoom(track) {
    try {
      const range = track?.getCapabilities?.().zoom;
      if (!range || !Number.isFinite(range.min) || !Number.isFinite(range.max) || range.max < range.min || !track.applyConstraints) return;
      let zoom = clamp(1, range.min, range.max);
      if (Number.isFinite(range.step) && range.step > 0) {
        zoom = clamp(range.min + Math.round((zoom - range.min) / range.step) * range.step, range.min, range.max);
      }
      if (track.getSettings?.().zoom === zoom) return;
      const constraints = track.getConstraints?.() || {};
      await track.applyConstraints({ ...constraints, advanced: [...(constraints.advanced || []), { zoom }] });
    } catch {
      // Zoom controls are optional; devices without them keep their native view.
    }
  }

  async function acquireStream(generation) {
    const acquired = [];
    const keepIfCurrent = (capturedStream) => {
      if (generation !== requestGeneration) {
        stopStream(capturedStream);
        throw CANCELLED;
      }
      acquired.push(capturedStream);
      pendingStreams.add(capturedStream);
      return capturedStream;
    };
    try {
      const selectedCamera = cameraExplicitlySelected ? ui.camera_select?.value : null;
      const selectedMicrophone = ui.microphone_select?.value;
      // Sensor constraints use landscape dimensions, even when a phone returns
      // a rotated portrait frame. The recording canvas handles output sizing.
      const videoConstraints = { width: { ideal: 1920 }, height: { ideal: 1080 }, frameRate: { ideal: 30 } };
      if (navigator.mediaDevices.getSupportedConstraints?.().resizeMode) videoConstraints.resizeMode = { exact: "none" };
      if (selectedCamera) videoConstraints.deviceId = { exact: selectedCamera };
      else videoConstraints.facingMode = { ideal: "user" };
      let cameraStream;
      try {
        try {
          cameraStream = keepIfCurrent(await navigator.mediaDevices.getUserMedia({ video: videoConstraints, audio: false }));
        } catch (cause) {
          if (!["OverconstrainedError", "ConstraintNotSatisfiedError"].includes(cause?.name) || cause.constraint !== "resizeMode") throw cause;
          if (generation !== requestGeneration) throw CANCELLED;
          delete videoConstraints.resizeMode;
          cameraStream = keepIfCurrent(await navigator.mediaDevices.getUserMedia({ video: videoConstraints, audio: false }));
        }
        await resetCameraZoom(cameraStream.getVideoTracks()[0]);
        if (generation !== requestGeneration) throw CANCELLED;
      } catch (cause) {
        if (cause === CANCELLED) throw cause;
        throw { cause, kind: "camera" };
      }
      let audioStream = null;
      try {
        const audioConstraints = { echoCancellation: true, noiseSuppression: true };
        if (selectedMicrophone) audioConstraints.deviceId = { exact: selectedMicrophone };
        audioStream = keepIfCurrent(await navigator.mediaDevices.getUserMedia({ audio: audioConstraints, video: false }));
      } catch (cause) {
        if (cause === CANCELLED) throw cause;
        // Audio-only NotFoundError identifies absent hardware. Denied or busy
        // microphones never silently produce a video without the intended audio.
        let microphoneAbsent = false;
        if (["NotFoundError", "DevicesNotFoundError"].includes(cause?.name)) {
          if (!selectedMicrophone) microphoneAbsent = true;
          else {
            try {
              const devices = await navigator.mediaDevices.enumerateDevices();
              microphoneAbsent = !devices.some((device) => device.kind === "audioinput");
            } catch { /* Keep the original error when absence cannot be verified. */ }
          }
        }
        if (!microphoneAbsent) throw { cause, kind: "microphone" };
      }
      if (generation !== requestGeneration) throw CANCELLED;
      return new MediaStream([...cameraStream.getVideoTracks(), ...(audioStream?.getAudioTracks() || [])]);
    } catch (error) {
      acquired.forEach(stopStream);
      throw error;
    } finally {
      acquired.forEach((capturedStream) => pendingStreams.delete(capturedStream));
    }
  }

  function attachStream(capturedStream) {
    stream = capturedStream;
    ui.camera_video.srcObject = capturedStream;
    ui.camera_video.play().catch(() => {
      if (stream === capturedStream) notify("A câmera está ativa. Clique na prévia para exibir o vídeo.", "warning");
    });
    for (const track of capturedStream.getTracks()) {
      track.addEventListener("ended", () => {
        if (stream !== capturedStream) return;
        const reason = track.kind === "audio"
          ? "O microfone foi desconectado. O trecho disponível será salvo."
          : "A câmera foi desconectada. O trecho disponível será salvo.";
        if (["recording", "paused", "stopping"].includes(state)) stopRecording(reason);
        else if (state === "countdown") cancelPreparation("A contagem foi cancelada porque um dispositivo foi desconectado.");
        else notify("Um dispositivo foi desconectado. Ative a câmera novamente para continuar.", "warning");
        releaseCamera();
      }, { once: true });
    }
    renderState();
    queueMeasurement();
    refreshDevices();
  }

  async function prepareCamera(forRecording = false) {
    if (state !== "idle") return;
    if (!accountSessionValid) { notify("Sua sessão terminou. Salve os vídeos antes de entrar novamente.", "warning"); return; }
    const error = environmentError(forRecording);
    if (error) { cameraIssue = error; renderState(); notify(error, "error"); return; }
    cameraIssue = null;
    prepareIntent = forRecording ? "record" : "preview";
    rehearsalPlaying = false;
    const generation = ++requestGeneration;
    setState("preparing");
    notify("Permita o acesso à câmera e ao microfone no navegador. Você pode cancelar a preparação.");
    try {
      const capturedStream = await acquireStream(generation);
      if (generation !== requestGeneration || state !== "preparing") {
        stopStream(capturedStream);
        return;
      }
      attachStream(capturedStream);
      await waitForCameraFrame(generation);
      if (generation !== requestGeneration || state !== "preparing") return;
      setState("idle");
      if (forRecording) startCountdown();
      else if (!hasAudio()) notify("Nenhum microfone foi encontrado. A câmera está pronta para gravar vídeo sem áudio.", "warning");
      else clearNotification();
    } catch (problem) {
      if (problem === CANCELLED || generation !== requestGeneration) return;
      cameraIssue = problem?.previewMessage || mediaFailure(problem);
      setState("idle");
      notify(cameraIssue, "error");
      refreshDevices();
    }
  }

  function toggleCamera() {
    if (state !== "idle") return;
    if (hasVideo()) {
      releaseCamera();
      notify("Câmera e microfone desligados. Você pode continuar ensaiando o roteiro.");
    } else prepareCamera();
  }

  function cancelPreparation(message = "Preparação cancelada. Nenhum vídeo foi gravado.") {
    requestGeneration += 1;
    pendingStreams.forEach(stopStream);
    pendingStreams.clear();
    clearTimeout(countdownTimer);
    countdownTimer = null;
    rehearsalPlaying = false;
    promptRequested = false;
    setState("idle");
    notify(message);
  }

  function beginRecording() {
    if (state !== "idle") return;
    if (!accountSessionValid) { notify("Sua sessão terminou. Salve os vídeos antes de entrar novamente.", "warning"); return; }
    const error = environmentError(true);
    if (error) { notify(error, "error"); return; }
    setMobilePanel(null, false);
    if (mobileViewport.matches) {
      // Request while the button still has user activation; camera permission can take time.
      document.documentElement.requestFullscreen?.().catch(() => {});
    }
    if (hasVideo()) startCountdown();
    else prepareCamera(true);
  }

  async function startCountdown() {
    if (state !== "idle" || !hasVideo()) return;
    const generation = ++requestGeneration;
    prepareIntent = "record";
    setState("preparing");
    try { await waitForCameraFrame(generation); }
    catch (problem) {
      if (problem === CANCELLED || generation !== requestGeneration) return;
      setState("idle");
      notify(problem.previewMessage || "A câmera ainda não está exibindo imagens. Ative a câmera e tente novamente.", "error");
      return;
    }
    if (generation !== requestGeneration || state !== "preparing") return;
    if (document.visibilityState === "hidden") {
      promptRequested = false;
      setState("idle");
      notify("Volte à página e inicie novamente a gravação para manter a câmera visível.", "warning");
      return;
    }
    rehearsalPlaying = false;
    promptRequested = true;
    resetPrompt();
    renderRecordingTime("00:00");
    countdownSeconds = 5;
    countdownDeadline = performance.now() + 5000;
    text(ui.countdown_label, hasAudio() ? "Olhe para a câmera. Começamos em…" : "Sem microfone. O vídeo será gravado sem áudio.");
    setState("countdown");
    queueMeasurement();
    if (!hasText()) notify("Seu roteiro está vazio. A gravação começa em 5 segundos, com a câmera e o áudio disponíveis.", "warning");
    else clearNotification();
    countdownTick();
  }

  function countdownTick() {
    if (state !== "countdown") return;
    const remaining = countdownDeadline - performance.now();
    if (remaining <= 0) {
      clearTimeout(countdownTimer);
      countdownTimer = null;
      createRecording();
      return;
    }
    const nextSeconds = Math.ceil(remaining / 1000);
    if (countdownSeconds !== nextSeconds) { countdownSeconds = nextSeconds; renderState(); }
    text(ui.countdown_number, String(countdownSeconds));
    ui.countdown_overlay?.style.setProperty("--countdown-progress", String(1 - remaining / 5000));
    countdownTimer = setTimeout(countdownTick, 80);
  }

  function createMediaRecorder(mediaStream) {
    const withAudio = mediaStream.getAudioTracks().length > 0;
    const types = withAudio
      ? ["video/webm;codecs=vp8,opus", "video/webm;codecs=vp9,opus", "video/webm", "video/mp4"]
      : ["video/webm;codecs=vp8", "video/webm;codecs=vp9", "video/webm", "video/mp4"];
    const supported = types.filter((type) => typeof MediaRecorder.isTypeSupported === "function" && MediaRecorder.isTypeSupported(type));
    let latestError;
    for (const mimeType of [...supported, ""]) {
      try {
        return new MediaRecorder(mediaStream, { ...(mimeType ? { mimeType } : {}), videoBitsPerSecond: 10000000, ...(withAudio ? { audioBitsPerSecond: 192000 } : {}) });
      } catch (error) { latestError = error; }
    }
    throw latestError || new Error("Gravação indisponível");
  }

  function createRecording() {
    if (state !== "countdown") return;
    if (!hasVideo()) { cancelPreparation("A câmera não está disponível. Ative-a antes de gravar."); return; }
    let recorder;
    let output;
    try {
      output = createPortraitOutput();
      recorder = createMediaRecorder(output.stream);
    }
    catch (error) {
      output?.release();
      promptRequested = false;
      setState("idle");
      notify(error.message || "O navegador não conseguiu preparar um formato de vídeo. Tente novamente ou abra no Chrome ou Edge atualizado.", "error");
      return;
    }
    const currentSession = {
      recorder,
      output,
      chunks: [],
      title: ui.script_title?.value.trim() || t("Meu vídeo"),
      date: new Date(),
      elapsedMs: 0,
      segmentStart: null,
      finalized: false,
      error: null,
      issue: null,
      finalizationTimer: null,
    };
    session = currentSession;
    pausedInBackground = false;
    recordTransition = "start";
    recorder.addEventListener("dataavailable", (event) => {
      if (!currentSession.finalized && event.data?.size > 0) currentSession.chunks.push(event.data);
    });
    recorder.addEventListener("start", () => {
      if (session !== currentSession || currentSession.finalized || state === "stopping") return;
      currentSession.segmentStart = performance.now();
      recordTransition = null;
      previousFrameTime = null;
      renderState();
      if (!hasAudio()) notify("Gravando em 9:16 sem áudio.", "warning");
      else clearNotification();
      handleVisibilityChange();
    });
    recorder.addEventListener("pause", () => {
      if (session !== currentSession || state === "stopping") return;
      freezeClock(currentSession);
      recordTransition = null;
      setState("paused");
      clearNotification();
    });
    recorder.addEventListener("resume", () => {
      if (session !== currentSession || state === "stopping") return;
      currentSession.segmentStart = performance.now();
      recordTransition = null;
      previousFrameTime = null;
      setState("recording");
      clearNotification();
      handleVisibilityChange();
    });
    recorder.addEventListener("error", (event) => {
      if (currentSession.finalized) return;
      currentSession.error = event.error || new Error("Falha na gravação");
      stopRecording("O navegador encontrou uma falha na gravação. Salvaremos o trecho disponível.");
    });
    recorder.addEventListener("stop", () => finalizeRecording(currentSession));
    try {
      recorder.start(1000);
      setState("recording");
    } catch {
      currentSession.finalized = true;
      currentSession.output.release();
      session = null;
      recordTransition = null;
      promptRequested = false;
      setState("idle");
      notify("Não foi possível iniciar a gravação. Verifique os dispositivos e tente novamente. Seus vídeos anteriores continuam em Gravações.", "error");
    }
  }

  function toggleRecordingPause() {
    if (!session || recordTransition) return;
    const recorder = session.recorder;
    try {
      if (state === "recording" && recorder.state === "recording") {
        recordTransition = "pause";
        recorder.pause();
      } else if (state === "paused" && recorder.state === "paused") {
        recordTransition = "resume";
        recorder.resume();
      } else return;
      renderState();
    } catch {
      recordTransition = null;
      notify("Não foi possível alterar a pausa. Você ainda pode encerrar e salvar o vídeo.", "error");
      renderState();
    }
  }

  function handleVisibilityChange() {
    if (document.visibilityState === "hidden") {
      if (exportJob) cancelExport("A exportação foi cancelada porque a página saiu de primeiro plano. Sua gravação continua disponível; mantenha a página aberta ao exportar novamente.");
      if (state === "countdown") cancelPreparation("A contagem foi cancelada porque a página saiu de primeiro plano. Inicie novamente quando estiver pronto.");
      else if (state === "recording" && session && !recordTransition) {
        toggleRecordingPause();
        pausedInBackground = recordTransition === "pause";
      }
      releaseWakeLock();
    } else {
      if (["countdown", "recording", "paused"].includes(state)) acquireWakeLock();
      if (pausedInBackground) {
        pausedInBackground = false;
        notify("A gravação foi pausada ao sair da página. Confira a câmera e toque em Retomar para continuar.", "warning");
      }
    }
  }

  function stopRecording(issue = null) {
    if (state === "preparing" || state === "countdown") { cancelPreparation(); return; }
    if (!session || state === "idle") return;
    if (issue) session.issue = issue;
    if (state === "stopping") return;
    const currentSession = session;
    freezeClock(currentSession);
    rehearsalPlaying = false;
    promptRequested = false;
    recordTransition = null;
    setState("stopping");
    notify(issue || "Finalizando o vídeo. Aguarde a gravação aparecer na sua lista.", issue ? "warning" : "info");
    try {
      if (currentSession.recorder.state !== "inactive") currentSession.recorder.stop();
    } catch {
      currentSession.error = new Error("Falha ao encerrar");
    }
    // Normally the stop event arrives immediately after the final dataavailable.
    // Keep already received chunks if an encoder fails to finish its events.
    currentSession.finalizationTimer = setTimeout(() => {
      if (!currentSession.finalized) {
        currentSession.issue = "O navegador demorou a finalizar. O trecho recebido foi recuperado; verifique a reprodução antes de sair.";
        finalizeRecording(currentSession);
      }
    }, 15000);
  }

  function filenameFor(recording, extension) {
    const title = recording.title.normalize("NFKC").replace(/[^\p{L}\p{N}\s_-]/gu, "").trim().replace(/\s+/gu, "-").slice(0, 72) || "meu-video";
    const date = recording.date;
    const parts = [date.getFullYear(), String(date.getMonth() + 1).padStart(2, "0"), String(date.getDate()).padStart(2, "0")];
    const time = [date.getHours(), date.getMinutes(), date.getSeconds()].map((part) => String(part).padStart(2, "0")).join("-");
    return `${title}-${parts.join("-")}-${time}.${extension}`;
  }

  function finalizeRecording(currentSession) {
    if (currentSession.finalized) return;
    currentSession.finalized = true;
    clearTimeout(currentSession.finalizationTimer);
    if (currentSession.recorder.state !== "inactive") {
      try { currentSession.recorder.stop(); } catch { /* Preserve chunks even if the encoder cannot finish. */ }
    }
    currentSession.output.release();
    freezeClock(currentSession);
    const type = currentSession.recorder.mimeType || currentSession.chunks.find((chunk) => chunk.type)?.type || "video/webm";
    let recording = null;
    try {
      const blob = new Blob(currentSession.chunks, { type });
      if (blob.size > 0) {
        recording = {
          title: currentSession.title,
          date: currentSession.date,
      duration: currentSession.elapsedMs,
          size: blob.size,
          url: URL.createObjectURL(blob),
          blob,
          mimeType: type,
          width: portraitSize.width,
          height: portraitSize.height,
          frameRate: 30,
          hasAudio: currentSession.output.stream.getAudioTracks().length > 0,
          exported: false,
          partial: !!currentSession.error || !!currentSession.issue,
        };
        recording.filename = filenameFor(recording, type.includes("mp4") ? "mp4" : "webm");
        recordings.unshift(recording);
      }
    } catch {
      currentSession.error = new Error("Falha ao preparar o arquivo");
    }
    currentSession.chunks = [];
    if (session === currentSession) {
      session = null;
      recordTransition = null;
      promptRequested = false;
      rehearsalPlaying = false;
      setState("idle");
    }
    renderRecordings();
    if (recording) {
      notify(currentSession.issue || (currentSession.error ? "Um trecho do vídeo foi recuperado. Verifique a reprodução antes de exportar." : "Vídeo pronto! Escolha a qualidade em Exportar vídeo."), recording.partial ? "warning" : "success");
      openDialog(ui.recordings_dialog);
    } else {
      notify("Não foi recebido um vídeo válido. Tente gravar novamente. Seus vídeos anteriores continuam em Gravações.", "error");
    }
  }

  function renderRecordings() {
    text(ui.recording_count, String(recordings.length));
    if (ui.recordings_empty) ui.recordings_empty.hidden = recordings.length > 0;
    if (!ui.recordings_list) return;
    ui.recordings_list.querySelectorAll("video").forEach((video) => {
      video.pause();
      video.removeAttribute("src");
      video.load();
    });
    ui.recordings_list.replaceChildren();
    for (const recording of recordings) {
      const card = document.createElement("article");
      card.className = "recording-card";
      card.studioRecording = recording;
      const video = document.createElement("video");
      video.className = "recording-card__video";
      video.src = recording.url;
      video.controls = true;
      video.playsInline = true;
      video.preload = "metadata";
      const details = document.createElement("div");
      details.className = "recording-card__details";
      const title = document.createElement("h3");
      title.textContent = recording.title;
      const description = document.createElement("p");
      details.append(title, description);
      const actions = document.createElement("div");
      actions.className = "recording-card__actions";
      const exportButton = document.createElement("button");
      exportButton.className = "recording-card__export";
      exportButton.type = "button";
      exportButton.addEventListener("click", () => openExport(recording));
      const remove = document.createElement("button");
      remove.className = "recording-card__delete";
      remove.type = "button";
      remove.addEventListener("click", () => {
        if (exportRecording === recording) closeExport(false);
        video.pause();
        video.removeAttribute("src");
        video.load();
        URL.revokeObjectURL(recording.url);
        recordings = recordings.filter((item) => item !== recording);
        renderRecordings();
        notify("Gravação removida desta sessão.");
      });
      actions.append(exportButton, remove);
      card.append(video, details, actions);
      localizeRecordingCard(card);
      ui.recordings_list.append(card);
    }
  }

  function localizeRecordingCard(card) {
    const recording = card.studioRecording;
    if (!recording) return;
    card.querySelector("video")?.setAttribute("aria-label", t("Prévia de {title}", { title: recording.title }));
    const description = card.querySelector(".recording-card__details p");
    if (description) description.textContent = `${recording.date.toLocaleString(locale(), { dateStyle: "short", timeStyle: "short" })} · ${secondsLabel(recording.duration)} · 9:16 · ${recording.width} × ${recording.height} · ${(recording.size / 1024 / 1024).toLocaleString(locale(), { maximumFractionDigits: 1 })} MB${recording.partial ? t(" · verifique o trecho recuperado") : ""}`;
    const exportButton = card.querySelector(".recording-card__export");
    if (exportButton) exportButton.textContent = t("Exportar vídeo");
    const remove = card.querySelector(".recording-card__delete");
    if (remove) { remove.textContent = t("Excluir"); remove.setAttribute("aria-label", t("Excluir {title}", { title: recording.title })); }
  }

  function clearPreparedExport() {
    if (preparedExport) URL.revokeObjectURL(preparedExport.url);
    preparedExport = null;
    if (ui.export_save_link) {
      ui.export_save_link.hidden = true;
      ui.export_save_link.removeAttribute("href");
      ui.export_save_link.removeAttribute("download");
    }
  }

  function exportPreview(url) {
    if (!ui.export_preview) return;
    clearExportPreview();
    const video = document.createElement("video");
    video.src = url;
    video.muted = true;
    video.playsInline = true;
    video.controls = true;
    video.preload = "metadata";
    video.setAttribute("aria-label", t("Prévia do vídeo para exportação"));
    ui.export_preview.replaceChildren(video);
  }

  function clearExportPreview() {
    ui.export_preview?.querySelectorAll("video").forEach((video) => { video.pause(); video.removeAttribute("src"); video.load(); });
    ui.export_preview?.replaceChildren();
  }

  function selectedExportOptions() {
    return window.StudioExportProfiles.options({
      aspect: ui.export_aspect?.value || "9:16",
      resolution: Number(ui.export_resolution?.value) || 720,
      quality: ui.export_quality?.value || "balanced",
      frameRate: Number(ui.export_framerate?.value) || 30,
    });
  }

  function selectedExportProfile() {
    return window.StudioExportProfiles.profiles.find((profile) => profile.id === ui.export_profile?.value)
      || window.StudioExportProfiles.profiles[0];
  }

  function renderExportProfiles() {
    if (!ui.export_profile) return;
    const selected = ui.export_profile.value || "custom";
    const options = window.StudioExportProfiles.profiles.map((profile) => {
      const option = document.createElement("option");
      option.value = profile.id;
      option.textContent = t(profile.label);
      return option;
    });
    ui.export_profile.replaceChildren(...options);
    ui.export_profile.value = selected;
  }

  function renderExportResolutions(preferred = Number(ui.export_resolution?.value) || 720) {
    if (!ui.export_resolution || !exportRecording) return;
    const shortSide = Math.min(exportRecording.width, exportRecording.height);
    const options = window.StudioExportProfiles.resolutions.map((resolution) => {
      const size = window.StudioExportProfiles.dimensions(ui.export_aspect?.value, resolution);
      const option = document.createElement("option");
      option.value = String(resolution);
      option.textContent = `${size.width} × ${size.height}`;
      option.disabled = resolution > shortSide;
      return option;
    });
    ui.export_resolution.replaceChildren(...options);
    const allowed = options.filter((option) => !option.disabled);
    ui.export_resolution.value = (allowed.filter((option) => Number(option.value) <= preferred).at(-1) || allowed[0] || options[0]).value;
  }

  function exportProfileUnavailable() {
    const profile = selectedExportProfile();
    return profile.id !== "custom" && !profile.formats.includes(ui.export_format?.value);
  }

  function changeExportOptions() {
    exportPreview(exportRecording.url);
    clearPreparedExport();
    if (ui.export_progress) ui.export_progress.hidden = true;
    text(ui.export_status, "Escolha as opções e exporte seu vídeo.");
    renderExportOptions();
  }

  function applyExportProfile() {
    if (!exportRecording || exportJob) return;
    const profile = selectedExportProfile();
    if (profile.id !== "custom") {
      ui.export_aspect.value = profile.aspect;
      renderExportResolutions(profile.resolution);
      ui.export_quality.value = profile.quality;
      ui.export_framerate.value = String(profile.frameRate);
      const formats = window.StudioExporter?.getFormats(exportRecording.hasAudio) || [];
      ui.export_format.value = formats.find((format) => profile.formats.includes(format.value))?.value || formats[0]?.value || "original";
    }
    changeExportOptions();
  }

  function renderExportOptions() {
    if (!exportRecording) return;
    renderAccountState();
    const original = ui.export_format?.value === "original";
    const options = selectedExportOptions();
    const profile = selectedExportProfile();
    const unavailable = exportProfileUnavailable();
    for (const element of [ui.export_filename, ui.export_format, ui.export_profile]) disable(element, !!exportJob);
    for (const element of [ui.export_aspect, ui.export_resolution, ui.export_quality, ui.export_framerate]) disable(element, !!exportJob || original);
    disable(ui.export_submit_btn, !!exportJob || unavailable);
    labelButton(ui.export_submit_btn, exportJob ? "Exportando…" : "Exportar vídeo");
    if (ui.export_cancel_btn) ui.export_cancel_btn.hidden = !exportJob;
    ui.export_dialog?.setAttribute("aria-busy", String(!!exportJob));
    const size = original ? exportRecording.size
      : (options.videoBitsPerSecond + (exportRecording.hasAudio ? options.audioBitsPerSecond : 0)) * exportRecording.duration / 8000;
    text(ui.export_estimate, `${t(original ? "Tamanho do arquivo" : "Tamanho estimado")}: ${(size / 1024 / 1024).toLocaleString(locale(), { maximumFractionDigits: 1 })} MB · ${original ? exportRecording.width : options.width} × ${original ? exportRecording.height : options.height} · ${original ? exportRecording.frameRate : options.frameRate} fps`, null);
    text(ui.export_recording_title, `${exportRecording.title} · ${secondsLabel(exportRecording.duration)} · ${t(exportRecording.hasAudio ? "Com áudio" : "Sem áudio")}`, null);
    const originalOption = ui.export_format?.querySelector('option[value="original"]');
    if (originalOption) originalOption.textContent = t("Original ({format})", { format: exportRecording.mimeType.includes("mp4") ? "MP4" : "WebM" });
    const aspect = original ? "9:16" : ui.export_aspect.value;
    ui.export_preview_wrap?.style.setProperty("--export-aspect-ratio", aspect.replace(":", " / "));
    text(ui.export_preview_caption, "Prévia · {aspect}", { aspect });
    const notes = [t(profile.note)];
    if (profile.id !== "custom") {
      if (unavailable) notes.push(t("Este navegador não consegue gerar MP4 para este destino. Use Personalizado ou um navegador com exportação MP4."));
      else if (ui.export_format.value === "webm") notes.push(t("Este perfil usa WebM porque MP4 não está disponível neste navegador."));
      if (profile.id === "tiktok" && ui.export_format.value === "webm") notes.push(t("Para enviar WebM ao TikTok, use o TikTok Studio na web."));
      if (Number(ui.export_resolution.value) < profile.resolution) notes.push(t("A resolução foi ajustada à gravação para evitar ampliar a imagem."));
      if (profile.maxSeconds && exportRecording.duration > profile.maxSeconds * 1000) notes.push(t("O vídeo ultrapassa {limit}. Confira o limite do destino; a exportação mantém o vídeo inteiro.", { limit: t(profile.limitLabel) }));
      if (profile.minSeconds && exportRecording.duration < profile.minSeconds * 1000) notes.push(t("O vídeo é mais curto que {limit}. Confira o mínimo aceito pelo destino.", { limit: t(profile.minimumLabel) }));
      if (profile.maxBytes && size > profile.maxBytes) notes.push(t("O tamanho estimado ultrapassa {size} MB. Confira o limite da sua conta.", { size: Math.round(profile.maxBytes / 1024 / 1024).toLocaleString(locale()) }));
      if (profile.advice && (!profile.adviceAboveSeconds || exportRecording.duration > profile.adviceAboveSeconds * 1000)) notes.push(t(profile.advice));
    }
    text(ui.export_profile_note, notes.join(" "), null);
    text(ui.export_note, original
      ? "Mantém o arquivo gravado, sem alterar a qualidade."
      : "Exportação em {aspect}. A imagem completa é preservada; espaços vazios são preenchidos em azul-escuro. Mantenha esta página aberta durante o preparo, que leva aproximadamente o tempo do vídeo.", { aspect });
  }

  function openExport(recording) {
    if (!ui.export_dialog || exportJob) return;
    if (state !== "idle") { notify("Encerre a gravação antes de exportar seus vídeos.", "warning"); return; }
    clearPreparedExport();
    exportRecording = recording;
    if (ui.export_filename) ui.export_filename.value = recording.filename.replace(/\.(mp4|webm)$/i, "");
    const formats = window.StudioExporter?.getFormats(recording.hasAudio) || [];
    const nativeOptions = formats.map((format) => {
      const option = document.createElement("option");
      option.value = format.value;
      option.textContent = format.label;
      return option;
    });
    const original = document.createElement("option");
    original.value = "original";
    original.textContent = t("Original ({format})", { format: recording.mimeType.includes("mp4") ? "MP4" : "WebM" });
    ui.export_format?.replaceChildren(...nativeOptions, original);
    if (ui.export_format) ui.export_format.value = formats.find((format) => format.value === "mp4")?.value || formats[0]?.value || "original";
    if (ui.export_profile) ui.export_profile.value = "custom";
    renderExportProfiles();
    if (ui.export_aspect) ui.export_aspect.value = "9:16";
    renderExportResolutions(Math.min(recording.width, recording.height) >= 720 ? 720 : 360);
    if (ui.export_quality) ui.export_quality.value = "balanced";
    if (ui.export_framerate) ui.export_framerate.value = "30";
    if (ui.export_progress) ui.export_progress.hidden = true;
    text(ui.export_status, "Escolha as opções e exporte seu vídeo.");
    exportPreview(recording.url);
    renderExportOptions();
    closeDialog(ui.recordings_dialog);
    openDialog(ui.export_dialog);
  }

  function cancelExport(message = "Exportação cancelada. Sua gravação continua disponível.") {
    if (!exportJob) return;
    exportJob.cancelMessage = message;
    exportJob.controller.abort();
    text(ui.export_status, "Cancelando a exportação…");
    disable(ui.export_cancel_btn, true);
  }

  function closeExport(returnToRecordings = true) {
    if (exportJob) exportJob.controller.abort();
    closeDialog(ui.export_dialog);
    clearPreparedExport();
    clearExportPreview();
    exportRecording = null;
    if (returnToRecordings) openDialog(ui.recordings_dialog);
  }

  function exportFilename(extension) {
    const name = (ui.export_filename?.value || "meu-video").normalize("NFKC").replace(/\.(mp4|webm)$/i, "")
      .replace(/[^\p{L}\p{N}\s_-]/gu, "").trim().replace(/\s+/gu, "-").slice(0, 100) || "meu-video";
    return `${name}.${extension}`;
  }

  async function startExport() {
    if (!exportRecording || exportJob) return;
    if (state !== "idle") { text(ui.export_status, "Encerre a gravação antes de exportar."); return; }
    if (exportProfileUnavailable()) { text(ui.export_status, "Este navegador não consegue gerar MP4 para este destino. Use Personalizado ou um navegador com exportação MP4."); return; }
    const recording = exportRecording;
    const original = ui.export_format?.value === "original";
    const format = window.StudioExporter?.getFormats(recording.hasAudio).find((item) => item.value === ui.export_format?.value);
    if (!original && !format) { text(ui.export_status, "Este formato não está disponível. Escolha o arquivo original."); return; }
    const options = selectedExportOptions();
    const filename = exportFilename((original ? recording.mimeType : format.mimeType).includes("mp4") ? "mp4" : "webm");
    const job = { controller: new AbortController(), recording };
    exportJob = job;
    clearPreparedExport();
    disable(ui.export_cancel_btn, false);
    if (ui.export_progress) ui.export_progress.hidden = false;
    if (ui.export_progress_bar) ui.export_progress_bar.value = 0;
    text(ui.export_status, original ? "Preparando o arquivo original…" : "Preparando a exportação…");
    renderExportOptions();
    if (!original) clearExportPreview();
    try {
      // Invoke the encoder directly from the click to retain mobile user activation.
      const result = original ? { blob: recording.blob, mimeType: recording.mimeType, width: recording.width, height: recording.height, frameRate: recording.frameRate }
        : await window.StudioExporter.exportVideo({
          blob: recording.blob,
          ...options,
          mimeType: format.mimeType,
          hasAudio: recording.hasAudio,
          duration: recording.duration / 1000,
          signal: job.controller.signal,
          preview: ui.export_preview,
          onProgress(progress) {
            if (exportJob !== job || job.controller.signal.aborted || exportRecording !== recording) return;
            const percent = Math.round(clamp(progress, 0, 1) * 100);
            if (ui.export_progress_bar) ui.export_progress_bar.value = percent;
            text(ui.export_status, "Exportando {percent}% · mantenha esta página aberta.", { percent });
          },
        });
      if (job.controller.signal.aborted || exportRecording !== recording) return;
      const url = URL.createObjectURL(result.blob);
      preparedExport = { ...result, url, filename, recording };
      if (ui.export_progress_bar) ui.export_progress_bar.value = 100;
      text(ui.export_status, "Exportação pronta · {size} MB. Use Salvar arquivo para guardar novamente se precisar.", { size: (result.blob.size / 1024 / 1024).toLocaleString(locale(), { maximumFractionDigits: 1 }) });
      exportPreview(url);
      if (exportJob === job) exportJob = null;
      renderExportOptions();
      if (ui.export_save_link) {
        ui.export_save_link.href = url;
        ui.export_save_link.download = filename;
        ui.export_save_link.hidden = false;
        ui.export_save_link.click();
      }
    } catch (error) {
      if (exportRecording !== recording) return;
      text(ui.export_status, error?.name === "AbortError" ? job.cancelMessage || "Exportação cancelada. Sua gravação continua disponível."
        : error.i18nSource || error.message || "Não foi possível exportar. Sua gravação continua disponível; tente outra qualidade ou o arquivo original.");
      if (ui.export_progress) ui.export_progress.hidden = true;
      exportPreview(recording.url);
    } finally {
      if (exportJob === job) exportJob = null;
      renderAccountState();
      if (exportRecording === recording) renderExportOptions();
    }
  }

  async function refreshDevices() {
    if (!navigator.mediaDevices?.enumerateDevices) return;
    const generation = ++deviceListGeneration;
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      if (generation !== deviceListGeneration) return;
      for (const [element, kind, name] of [
        [ui.camera_select, "videoinput", "Câmera"],
        [ui.microphone_select, "audioinput", "Microfone"],
      ]) {
        if (!element) continue;
        const selected = element.value;
        const available = devices.filter((device) => device.kind === kind);
        const options = available.map((device, index) => {
          const option = document.createElement("option");
          option.value = device.deviceId;
          option.textContent = device.label || `${t(name)} ${index + 1}`;
          return option;
        });
        if (!options.length) {
          const option = document.createElement("option");
          option.value = "";
          option.textContent = t(kind === "videoinput" ? "Câmera padrão" : "Microfone padrão");
          options.push(option);
        }
        element.replaceChildren(...options);
        if (available.some((device) => device.deviceId === selected)) element.value = selected;
        else {
          const trackId = stream?.getTracks().find((track) => track.kind === (kind === "videoinput" ? "video" : "audio"))?.getSettings().deviceId;
          if (available.some((device) => device.deviceId === trackId)) element.value = trackId;
        }
      }
      renderState();
    } catch {
      // Device labels are optional; permission controls remain usable without them.
    }
  }

  function changeDevice() {
    if (state !== "idle") return;
    if (hasVideo()) { releaseCamera(); prepareCamera(); }
  }

  // The camera and transparent script are the main view at every screen size.
  const overlayLayout = () => true;

  function updateMobilePanels() {
    const overlay = overlayLayout();
    document.body.classList.toggle("camera-layout", overlay);
    ui.preview_stage.inert = overlay && !!mobilePanel;
    if (overlay && mobilePanel) document.body.dataset.mobilePanel = mobilePanel;
    else document.body.removeAttribute("data-mobile-panel");
    for (const [name, panel, button] of [
      ["script", ui.script_panel, ui.editor_btn],
      ["settings", ui.settings_panel, ui.settings_btn],
    ]) {
      const open = overlay && mobilePanel === name;
      button?.setAttribute("aria-expanded", String(open));
      if (!panel) continue;
      panel.inert = overlay && !open;
      panel.setAttribute("aria-hidden", String(overlay && !open));
      if (overlay) {
        panel.setAttribute("role", "dialog");
        panel.setAttribute("aria-modal", "true");
      } else {
        panel.removeAttribute("role");
        panel.removeAttribute("aria-modal");
      }
    }
    queueMeasurement();
  }

  function setMobilePanel(nextPanel, focus = true) {
    if (!overlayLayout()) {
      mobilePanel = null;
      updateMobilePanels();
      if (nextPanel === "script") ui.script_input.focus();
      else if (nextPanel === "settings") ui.speed_input?.focus();
      return;
    }
    if (!nextPanel && !focus) {
      const previousPanel = mobilePanel === "script" ? ui.script_panel : ui.settings_panel;
      if (document.activeElement instanceof HTMLElement && previousPanel?.contains(document.activeElement)) document.activeElement.blur();
    }
    if (nextPanel && !mobilePanel) panelReturnFocus = document.activeElement;
    mobilePanel = nextPanel;
    updateMobilePanels();
    if (!focus) {
      if (!nextPanel) panelReturnFocus = null;
      return;
    }
    if (nextPanel === "script") ui.script_input.focus({ preventScroll: true });
    else if (nextPanel === "settings") ui.speed_input?.focus({ preventScroll: true });
    else if (panelReturnFocus instanceof HTMLElement && panelReturnFocus.isConnected) panelReturnFocus.focus({ preventScroll: true });
    if (!nextPanel) panelReturnFocus = null;
  }

  function renderFullscreen() {
    const expanded = document.body.classList.contains("is-immersive") || !!document.fullscreenElement;
    for (const button of [ui.fullscreen_btn, ui.immersive_fullscreen_btn]) {
      labelButton(button, expanded ? "Sair da tela cheia" : "Tela cheia");
      button?.setAttribute("aria-label", t(expanded ? "Sair da tela cheia" : "Tela cheia"));
      button?.setAttribute("aria-pressed", String(expanded));
      disable(button, false);
    }
    updateMobilePanels();
  }

  async function toggleFullscreen() {
    if (document.body.classList.contains("is-immersive") || document.fullscreenElement) {
      document.body.classList.remove("is-immersive");
      if (document.fullscreenElement && document.exitFullscreen) {
        try { await document.exitFullscreen(); } catch { /* CSS viewport mode still works. */ }
      }
    } else {
      document.body.classList.add("is-immersive");
      renderFullscreen();
      try { await document.documentElement.requestFullscreen?.(); } catch { /* iPhone uses CSS viewport mode. */ }
    }
    renderFullscreen();
  }

  function openDialog(dialog) {
    if (!dialog || dialog.open) return;
    try {
      if (typeof dialog.showModal === "function") dialog.showModal();
      else dialog.setAttribute("open", "");
    } catch { dialog.setAttribute("open", ""); }
  }

  function closeDialog(dialog) {
    if (!dialog) return;
    if (typeof dialog.close === "function" && dialog.open) dialog.close();
    else dialog.removeAttribute("open");
    dialog.querySelectorAll("video").forEach((video) => video.pause());
  }

  async function importScript() {
    const file = ui.script_file_input?.files?.[0];
    if (!file) return;
    const generation = ++importGeneration;
    ui.script_file_input.value = "";
    if (!/\.txt$/i.test(file.name)) { notify("Escolha um arquivo de texto com a extensão .txt.", "error"); return; }
    if (file.size > 2 * 1024 * 1024) { notify("O arquivo deve ter até 2 MB. Divida roteiros muito longos em partes.", "error"); return; }
    try {
      const importedText = await file.text();
      if (generation !== importGeneration) return;
      ui.script_input.value = importedText;
      if (ui.script_title) ui.script_title.value = file.name.replace(/\.txt$/i, "").slice(0, 100);
      updateScript();
      renderState();
      scheduleSave();
      notify("Roteiro importado. O arquivo permanece no seu navegador.", "success");
    } catch { notify("Não foi possível ler esse arquivo. Tente outro arquivo .txt.", "error"); }
  }

  function refreshLanguage() {
    window.StudioI18n?.applyDocument();
    for (const [element, message] of translatedElements) {
      if (element.isConnected) element.textContent = t(message.source, message.params);
      else translatedElements.delete(element);
    }
    renderState();
    renderFullscreen();
    ui.recordings_list?.querySelectorAll(".recording-card").forEach(localizeRecordingCard);
    if (exportRecording) {
      renderExportProfiles();
      renderExportOptions();
      ui.export_preview?.querySelectorAll("video").forEach((video) => {
        const source = video.classList.contains("export-render-video") ? "Prévia da exportação" : "Prévia do vídeo para exportação";
        video.setAttribute("aria-label", t(source));
      });
      if (preparedExport) text(ui.export_status, "Exportação pronta · {size} MB. Use Salvar arquivo para guardar novamente se precisar.", { size: (preparedExport.blob.size / 1024 / 1024).toLocaleString(locale(), { maximumFractionDigits: 1 }) });
    }
    refreshDevices();
  }

  ui.language_select?.addEventListener("change", () => window.StudioI18n?.setLanguage(ui.language_select.value));
  document.addEventListener("studio:languagechange", refreshLanguage);

  ui.script_input.addEventListener("input", () => { importGeneration += 1; updateScript(); renderState(); scheduleSave(); });
  ui.script_title?.addEventListener("input", scheduleSave);
  ui.import_script_btn?.addEventListener("click", () => ui.script_file_input?.click());
  ui.script_file_input?.addEventListener("change", importScript);
  ui.clear_script_btn?.addEventListener("click", () => {
    importGeneration += 1;
    ui.script_input.value = "";
    updateScript();
    renderState();
    scheduleSave();
    ui.script_input.focus();
    notify("Roteiro limpo. Escreva ou importe um novo texto.");
  });
  for (const element of [ui.speed_input, ui.size_input, ui.opacity_input, ui.text_color, ui.font_select, ui.show_prompt_input, ui.mirror_input]) {
    element?.addEventListener("input", () => {
      if (element === ui.size_input) autoSize = false;
      renderPromptSettings();
      scheduleSave();
    });
  }
  ui.immersive_speed_input?.addEventListener("input", () => {
    if (ui.speed_input) ui.speed_input.value = ui.immersive_speed_input.value;
    renderPromptSettings();
    scheduleSave();
  });
  document.querySelectorAll("[data-color]").forEach((button) => {
    button.addEventListener("click", () => {
      if (!ui.text_color || !/^#[0-9a-f]{6}$/i.test(button.dataset.color)) return;
      ui.text_color.value = button.dataset.color;
      renderPromptSettings();
      scheduleSave();
    });
  });
  [ui.prompt_progress, ui.immersive_prompt_progress].forEach((element) => element?.addEventListener("input", () => {
    promptOffset = clamp(Number(element.value), 0, 100) / 100 * maximumScroll;
    measurementRatio = null;
    renderPromptPosition();
  }));
  [ui.reset_script_btn, ui.immersive_reset_btn].forEach((button) => button?.addEventListener("click", resetPrompt));
  [ui.prompt_play_btn, ui.immersive_prompt_play_btn].forEach((button) => button?.addEventListener("click", toggleRehearsal));
  [ui.camera_btn, ui.immersive_camera_btn].forEach((button) => button?.addEventListener("click", toggleCamera));
  ui.camera_placeholder_btn?.addEventListener("click", toggleCamera);
  ui.camera_video.addEventListener("click", () => {
    if (hasVideo()) ui.camera_video.play().catch(() => notify("O navegador não conseguiu exibir a câmera.", "error"));
  });
  ui.camera_select?.addEventListener("change", () => { cameraExplicitlySelected = true; changeDevice(); });
  ui.microphone_select?.addEventListener("change", changeDevice);
  navigator.mediaDevices?.addEventListener?.("devicechange", refreshDevices);
  [ui.record_btn, ui.immersive_record_btn].forEach((button) => button?.addEventListener("click", beginRecording));
  [ui.pause_record_btn, ui.immersive_pause_btn].forEach((button) => button?.addEventListener("click", toggleRecordingPause));
  [ui.stop_btn, ui.immersive_stop_btn].forEach((button) => button?.addEventListener("click", () => stopRecording()));
  [ui.recordings_btn, ui.immersive_recordings_btn].forEach((button) => button?.addEventListener("click", () => { setMobilePanel(null, false); openDialog(ui.recordings_dialog); }));
  ui.editor_btn?.addEventListener("click", () => setMobilePanel(mobilePanel === "script" ? null : "script"));
  ui.settings_btn?.addEventListener("click", () => setMobilePanel(mobilePanel === "settings" ? null : "settings"));
  [ui.studio_btn, ui.close_script_btn, ui.close_settings_btn, ui.sheet_backdrop].forEach((button) => button?.addEventListener("click", () => setMobilePanel(null)));
  ui.close_recordings_btn?.addEventListener("click", () => closeDialog(ui.recordings_dialog));
  ui.logout_form?.addEventListener("submit", requestAccountLogout);
  accountNavigationLinks.forEach((link) => link.addEventListener("click", requestAccountNavigation));
  accountForms.forEach((form) => form.addEventListener("submit", (event) => {
    event.preventDefault();
    if (form.querySelector('[type="submit"]')?.dataset.available !== "true") return;
    requestAccountAction({ kind: "form", form });
  }));
  ui.logout_confirm_btn?.addEventListener("click", finishAccountAction);
  ui.logout_dialog?.addEventListener("close", () => { pendingAccountAction = null; });
  ui.logout_cancel_btn?.addEventListener("click", () => closeDialog(ui.logout_dialog));
  ui.logout_dialog?.addEventListener("cancel", (event) => { event.preventDefault(); closeDialog(ui.logout_dialog); });
  ui.close_export_btn?.addEventListener("click", () => closeExport());
  ui.export_submit_btn?.addEventListener("click", startExport);
  ui.export_cancel_btn?.addEventListener("click", () => cancelExport());
  ui.export_save_link?.addEventListener("click", () => {
    if (preparedExport) preparedExport.recording.exported = true;
  });
  ui.export_profile?.addEventListener("change", applyExportProfile);
  for (const element of [ui.export_filename, ui.export_format, ui.export_aspect, ui.export_resolution, ui.export_quality, ui.export_framerate]) {
    element?.addEventListener("input", () => {
      if (exportJob || !exportRecording) return;
      if (element === ui.export_filename) {
        if (preparedExport) {
          preparedExport.filename = exportFilename(preparedExport.mimeType.includes("mp4") ? "mp4" : "webm");
          if (ui.export_save_link) ui.export_save_link.download = preparedExport.filename;
        }
        return;
      }
      ui.export_profile.value = "custom";
      if (element === ui.export_aspect) renderExportResolutions();
      changeExportOptions();
    });
  }
  ui.export_dialog?.addEventListener("cancel", (event) => { event.preventDefault(); if (exportJob) cancelExport(); else closeExport(); });
  ui.export_dialog?.addEventListener("close", () => { if (exportRecording) closeExport(); });
  [ui.help_btn, ui.immersive_help_btn].forEach((button) => button?.addEventListener("click", () => { setMobilePanel(null, false); openDialog(ui.help_dialog); }));
  ui.close_help_btn?.addEventListener("click", () => closeDialog(ui.help_dialog));
  for (const dialog of [ui.recordings_dialog, ui.help_dialog]) {
    dialog?.addEventListener("click", (event) => {
      if (event.target !== dialog) return;
      const bounds = dialog.getBoundingClientRect();
      if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) closeDialog(dialog);
    });
    dialog?.addEventListener("close", () => dialog.querySelectorAll("video").forEach((video) => video.pause()));
  }
  [ui.fullscreen_btn, ui.immersive_fullscreen_btn].forEach((button) => button?.addEventListener("click", toggleFullscreen));
  document.addEventListener("fullscreenchange", () => {
    document.body.classList.toggle("is-immersive", !!document.fullscreenElement);
    renderFullscreen();
  });
  mobileViewport.addEventListener?.("change", () => { mobilePanel = null; updateMobilePanels(); });
  window.addEventListener("resize", () => {
    if (applyResponsiveFontSize()) {
      renderPromptSettings();
      scheduleSave();
    }
    updateMobilePanels();
  });
  document.addEventListener("visibilitychange", handleVisibilityChange);
  document.addEventListener("visibilitychange", () => { if (document.visibilityState === "visible") checkAccountSession(); });
  window.addEventListener("focus", () => checkAccountSession());
  if (typeof ResizeObserver === "function") {
    const observer = new ResizeObserver(queueMeasurement);
    observer.observe(ui.preview_stage);
    if (ui.prompt_text) observer.observe(ui.prompt_text);
    if (ui.prompt_overlay) observer.observe(ui.prompt_overlay);
  }
  document.fonts?.ready.then(queueMeasurement);
  document.addEventListener("keydown", (event) => {
    if (event.repeat || event.ctrlKey || event.altKey || event.metaKey) return;
    if (ui.help_dialog?.open || ui.recordings_dialog?.open || ui.export_dialog?.open || ui.logout_dialog?.open) return;
    if (mobilePanel && overlayLayout()) {
      if (event.key === "Escape") {
        event.preventDefault();
        setMobilePanel(null);
        return;
      }
      if (event.key === "Tab") {
        const panel = mobilePanel === "script" ? ui.script_panel : ui.settings_panel;
        const focusable = Array.from(panel?.querySelectorAll('button, input, textarea, select, a[href], [tabindex="0"]') || [])
          .filter((element) => !element.disabled && element.getClientRects().length > 0);
        const first = focusable[0];
        const last = focusable.at(-1);
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
      }
      return;
    }
    if (event.key === "Escape" && activeStates.has(state) && state !== "stopping") {
      event.preventDefault();
      stopRecording();
      return;
    }
    if (event.key === "Escape" && document.body.classList.contains("is-immersive") && !document.fullscreenElement) {
      event.preventDefault();
      toggleFullscreen();
      return;
    }
    const target = event.target instanceof Element ? event.target : null;
    if (target?.closest('input, textarea, select, button, a, [contenteditable="true"]')) return;
    if (event.code === "Space") {
      event.preventDefault();
      if (state === "recording" || state === "paused") toggleRecordingPause();
      else if (state === "idle") toggleRehearsal();
    } else if (event.key.toLowerCase() === "r" && state === "idle") {
      event.preventDefault();
      beginRecording();
    }
  });
  window.addEventListener("beforeunload", (event) => {
    if (saveTimer) saveSettings();
    if (accountNavigationAllowed) return;
    if (exportJob || ["recording", "paused", "stopping"].includes(state) || recordings.some((recording) => !recording.exported)) {
      event.preventDefault();
      event.returnValue = "";
    }
  });
  window.addEventListener("pagehide", (event) => {
    sessionCheck?.abort();
    exportJob?.controller.abort();
    clearPreparedExport();
    requestGeneration += 1;
    pendingStreams.forEach(stopStream);
    pendingStreams.clear();
    clearTimeout(countdownTimer);
    clearTimeout(notificationTimer);
    clearTimeout(recordingClockTimer);
    recordingClockTimer = null;
    if (animationFrame !== null) cancelAnimationFrame(animationFrame);
    if (measurementFrame !== null) cancelAnimationFrame(measurementFrame);
    if (session && ["recording", "paused"].includes(state)) stopRecording("A página foi fechada ou suspensa. Verifique o trecho recuperado antes de sair.");
    session?.output.release();
    releaseWakeLock();
    stopStream(stream);
    if (!event.persisted) recordings.forEach((recording) => URL.revokeObjectURL(recording.url));
  });
  window.addEventListener("pageshow", (event) => {
    if (!event.persisted) return;
    checkAccountSession(true);
    animationFrame = null;
    measurementFrame = null;
    previousFrameTime = null;
    if (!hasVideo()) releaseCamera();
    if (state === "preparing" || state === "countdown") cancelPreparation();
    queueMeasurement();
    renderState();
  });

  restoreSettings();
  applyResponsiveFontSize();
  updateScript();
  renderPromptSettings(false);
  renderRecordings();
  renderState();
  renderFullscreen();
  saveSettings();
  if (new URL(location.href).searchParams.get("settings") === "1") setMobilePanel("settings", false);
  refreshDevices();
  prepareCamera().then(() => {
    const unavailable = environmentError(true);
    if (unavailable && hasVideo()) notify(unavailable, "warning");
  });
})();
