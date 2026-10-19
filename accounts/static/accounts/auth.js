(() => {
  "use strict";

  const i18n = window.StudioI18n;
  document.getElementById("language-select")?.addEventListener("change", (event) => {
    i18n?.setLanguage(event.target.value);
  });

  const passwordButtons = [...document.querySelectorAll("[data-password-target]")];
  function describePassword(button) {
    const visible = button.getAttribute("aria-pressed") === "true";
    const source = visible ? "Ocultar senha" : "Mostrar senha";
    button.dataset.i18nAriaLabel = source;
    button.setAttribute("aria-label", i18n?.t(source) || source);
  }
  for (const button of passwordButtons) {
    button.addEventListener("click", () => {
      const field = document.getElementById(button.dataset.passwordTarget);
      if (!field) return;
      const visible = field.type === "password";
      const position = [field.selectionStart, field.selectionEnd];
      field.type = visible ? "text" : "password";
      button.setAttribute("aria-pressed", String(visible));
      describePassword(button);
      field.focus({ preventScroll: true });
      if (position[0] !== null && position[1] !== null) field.setSelectionRange(...position);
    });
  }
  document.addEventListener("studio:languagechange", () => passwordButtons.forEach(describePassword));

  const signup = document.getElementById("signup-form");
  const forms = [...document.querySelectorAll(".auth-form, [data-auth-form]")];
  const submit = signup?.querySelector("[type='submit']");
  const consentInputs = signup ? [document.getElementById("id_accept_terms"), document.getElementById("id_accept_privacy")] : [];
  const consentReady = () => !signup || consentInputs.every((input) => input && input.dataset.legalRead === "true" && input.checked && !input.disabled);
  function updateConsent() {
    if (submit) submit.disabled = !consentReady() || signup?.getAttribute("aria-busy") === "true";
  }
  consentInputs.forEach((input) => input?.addEventListener("change", updateConsent));
  function restoreSubmit() {
    for (const form of forms) {
      const button = form.querySelector("[type='submit']");
      if (!button) continue;
      form.removeAttribute("aria-busy");
      button.disabled = button.dataset.unavailable === "true";
      const span = button.querySelector("span");
      if (span && button.dataset.submitSource) {
        span.dataset.i18n = button.dataset.submitSource;
        span.textContent = i18n?.t(button.dataset.submitSource) || button.dataset.submitSource;
      }
    }
    updateConsent();
  }
  for (const form of forms) form.addEventListener("submit", (event) => {
    const button = form.querySelector("[type='submit']");
    if (!button) return;
    if (button.dataset.unavailable === "true" || (form === signup && !consentReady())) {
      event.preventDefault();
      updateConsent();
      if (form === signup) document.getElementById("consent-help")?.scrollIntoView({ block: "center" });
      return;
    }
    syncLanguage();
    button.disabled = true;
    form.setAttribute("aria-busy", "true");
    const span = button.querySelector("span");
    if (span) {
      span.dataset.i18n = "Aguarde…";
      span.textContent = i18n?.t("Aguarde…") || "Aguarde…";
    }
  });
  window.addEventListener("pageshow", restoreSubmit);
  restoreSubmit();
  document.getElementById("auth-errors")?.focus({ preventScroll: true });

  const publicDocument = document.querySelector("[data-public-legal-page]");
  function syncLanguage() {
    const language = i18n?.language || "pt-BR";
    for (const input of document.querySelectorAll('input[type="hidden"][name="lang"]')) input.value = language;
    for (const link of document.querySelectorAll("[data-auth-language-link]")) {
      const url = new URL(link.href, location.href);
      url.searchParams.set("lang", language);
      link.href = url.href;
    }
  }
  function refreshPublicLinks() {
    for (const link of document.querySelectorAll("[data-public-legal-link], [data-legal-kind]")) {
      const url = new URL(link.href, location.href);
      url.searchParams.set("lang", i18n?.language || "pt-BR");
      link.href = url.href;
    }
  }
  function syncPublicDocument() {
    if (!publicDocument) return;
    const language = i18n?.language || "pt-BR";
    if (publicDocument.dataset.documentLanguage === language) return;
    const url = new URL(location.href);
    url.searchParams.set("lang", language);
    location.replace(url.href);
  }
  // A language explicitly selected in a document URL also works when shared.
  if (publicDocument && new URL(location.href).searchParams.has("lang")) {
    i18n?.setLanguage(publicDocument.dataset.documentLanguage);
  } else {
    const requestedLanguage = new URL(location.href).searchParams.get("lang");
    if (["pt-BR", "en", "es"].includes(requestedLanguage)) i18n?.setLanguage(requestedLanguage);
  }
  syncLanguage();
  refreshPublicLinks();
  syncPublicDocument();

  const dialog = document.getElementById("legal-dialog");
  const reader = document.getElementById("legal-scroll");
  const content = document.getElementById("legal-dialog-content");
  const readButton = document.getElementById("legal-read-button");
  const status = document.getElementById("legal-read-status");
  const retry = document.getElementById("legal-load-retry");
  let activeDocument = null;
  let loadingController = null;
  let acknowledging = false;
  let reachedEnd = false;
  const documentNames = { terms: "Termos de Uso", privacy: "Política de Privacidade" };
  const translated = (source) => i18n?.t(source) || source;
  function setText(element, source) {
    if (!element) return;
    element.dataset.i18n = source;
    element.textContent = translated(source);
  }
  function showStatus(source, error = false) {
    setText(status, source);
    if (status) status.dataset.error = String(error);
  }
  function checkEnd() {
    if (!activeDocument?.loaded || !reader || !readButton || acknowledging) return;
    if (reader.scrollHeight - reader.clientHeight - reader.scrollTop <= 5) reachedEnd = true;
    readButton.disabled = !reachedEnd;
    showStatus(reachedEnd ? "Agora você pode confirmar a leitura." : "Leia o documento até o final.");
  }
  async function loadDocument() {
    if (!activeDocument || !content || !dialog?.open) return;
    loadingController?.abort();
    const controller = new AbortController();
    loadingController = controller;
    const current = activeDocument;
    current.loaded = false;
    reachedEnd = false;
    readButton.disabled = true;
    retry.hidden = true;
    content.replaceChildren();
    reader.scrollTop = 0;
    reader.setAttribute("aria-busy", "true");
    setText(document.getElementById("legal-dialog-title"), documentNames[current.kind]);
    showStatus("Abrindo documento…");
    try {
      const url = new URL(current.link.href, location.href);
      url.searchParams.set("fragment", "1");
      url.searchParams.set("lang", i18n?.language || "pt-BR");
      const response = await fetch(url, { signal: controller.signal, credentials: "same-origin", headers: { Accept: "text/html" } });
      if (!response.ok) throw new Error("Document unavailable");
      const markup = new DOMParser().parseFromString(await response.text(), "text/html");
      const fragment = markup.querySelector("[data-legal-document]");
      if (!fragment || fragment.dataset.legalDocument !== current.kind || !fragment.dataset.legalVersion) throw new Error("Invalid document");
      if (controller.signal.aborted || activeDocument !== current) return;
      // Import only our server's escaped document fragment, never a full page.
      content.replaceChildren(document.importNode(fragment, true));
      current.version = fragment.dataset.legalVersion;
      current.loaded = true;
      const versionField = signup.elements.namedItem(`${current.kind}_version`);
      const checkbox = document.getElementById(`id_accept_${current.kind}`);
      if (versionField.value !== current.version) {
        versionField.value = current.version;
        checkbox.dataset.legalRead = "false";
        checkbox.checked = false;
        checkbox.disabled = true;
        setText(document.getElementById(`${current.kind}-read-status`), "Leia para continuar");
        updateConsent();
      }
      i18n?.applyDocument();
      reader.removeAttribute("aria-busy");
      requestAnimationFrame(checkEnd);
    } catch (error) {
      if (error.name === "AbortError" || controller.signal.aborted) return;
      reader.removeAttribute("aria-busy");
      showStatus("Não foi possível abrir o documento. Tente novamente.", true);
      retry.hidden = false;
    }
  }
  if (signup && dialog && typeof dialog.showModal === "function") {
    for (const link of signup.querySelectorAll("[data-legal-kind]")) {
      link.addEventListener("click", (event) => {
        event.preventDefault();
        activeDocument = { kind: link.dataset.legalKind, link, loaded: false };
        dialog.showModal();
        loadDocument();
      });
    }
    document.getElementById("legal-close-button")?.addEventListener("click", () => dialog.close());
    dialog.addEventListener("close", () => {
      loadingController?.abort();
      activeDocument = null;
      reader.removeAttribute("aria-busy");
    });
    reader.addEventListener("scroll", checkEnd, { passive: true });
    if (typeof ResizeObserver === "function") new ResizeObserver(checkEnd).observe(reader);
    retry?.addEventListener("click", loadDocument);
    readButton.addEventListener("click", async () => {
      const current = activeDocument;
      if (!current?.loaded || !reachedEnd || acknowledging) return;
      acknowledging = true;
      readButton.disabled = true;
      showStatus("Registrando leitura…");
      try {
        const body = new URLSearchParams({
          kind: current.kind,
          version: current.version,
          csrfmiddlewaretoken: signup.elements.namedItem("csrfmiddlewaretoken").value,
        });
        const response = await fetch(signup.dataset.legalReadUrl, {
          method: "POST", credentials: "same-origin", body, headers: { Accept: "application/json" },
        });
        const result = await response.json();
        if (!response.ok || result.kind !== current.kind || result.version !== current.version) throw new Error(result.error || "Read confirmation failed");
        const checkbox = document.getElementById(`id_accept_${current.kind}`);
        checkbox.dataset.legalRead = "true";
        checkbox.disabled = false;
        // Reading enables the checkbox; acceptance always requires a user action.
        setText(document.getElementById(`${current.kind}-read-status`), "Leitura concluída");
        updateConsent();
        if (dialog.open && activeDocument === current) {
          dialog.close();
          checkbox.focus({ preventScroll: true });
        }
      } catch (error) {
        if (dialog.open && activeDocument === current) {
          showStatus(error.message === "Os documentos foram atualizados. Leia e aceite a versão atual." ? error.message : "Não foi possível registrar a leitura. Tente novamente.", true);
          readButton.disabled = false;
        }
      } finally {
        acknowledging = false;
        if (dialog.open && activeDocument !== current) checkEnd();
      }
    });
  }
  document.addEventListener("studio:languagechange", () => {
    syncLanguage();
    refreshPublicLinks();
    syncPublicDocument();
    if (dialog?.open && !acknowledging) loadDocument();
  });
})();
