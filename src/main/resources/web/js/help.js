(() => {
  "use strict";

  const runtimeConfig = window.WEBSHOPX_CONFIG || {};
  const I18N = window.WebShopXI18n || null;
  if (I18N) {
    I18N.preparePage("help");
  }
  const HELP_LOCALE_BUNDLE = I18N && typeof I18N.loadBundleSync === "function"
    ? I18N.loadBundleSync("help")
    : {};

  const FALLBACK_HELP_RUNTIME = Object.freeze({
    themeToggleLight: "Light mode",
    themeToggleDark: "Dark mode",
    modeToggleSingle: "Full Page",
    modeToggleFull: "Single Page",
    fallbackManualTitle: "WebShopX Manual",
    fallbackHelpZhTitle: "WebShopX Help (zh-CN)",
    fallbackHelpEnTitle: "WebShopX Help (en-US)",
    rootDocTitle: "Document",
    unnamedSection: "Untitled section",
    noNavigableHeadings: "No navigable headings found.",
    searchSection: "Section",
    searchHeading: "Heading",
    noSearchMatch: "No matching content",
    docFetchFailed: "Cannot load {file}",
    docNotFound: "No available documents found.",
    docLoading: "Loading document...",
    docLoadFailed: "Document load failed: {message}"
  });

  function getHelpRuntimeText(key, fallback) {
    const value = HELP_LOCALE_BUNDLE?.uiText?.runtime?.[key];
    if (typeof value === "string" && value.length > 0) {
      return value;
    }
    if (Object.prototype.hasOwnProperty.call(FALLBACK_HELP_RUNTIME, key)) {
      return FALLBACK_HELP_RUNTIME[key];
    }
    return fallback;
  }

  function formatHelpRuntimeText(key, params = {}, fallback = "") {
    const template = String(getHelpRuntimeText(key, fallback) || "");
    return template.replace(/\{(\w+)\}/g, (_, token) => {
      const value = params[token];
      return value == null ? "" : String(value);
    });
  }
  const query = new URLSearchParams(window.location.search);

  const requestedCategory = String(query.get("category") || "").trim().toLowerCase();
  const requestedAlgorithm = String(query.get("algorithm") || "").trim();

  const queryMode = String(query.get("mode") || "").trim().toLowerCase();
  let requestedMode = queryMode === "single" ? "single" : "full";
  if (!queryMode) {
    try {
      const savedMode = String(window.localStorage.getItem("webshopx_help_mode") || "").trim().toLowerCase();
      if (savedMode === "single") {
        requestedMode = "single";
      }
    } catch (error) {
      requestedMode = "full";
    }
  }

  const requestedDocToken = String(query.get("doc") || "").trim();
  const requestedLang = String(
    query.get("lang") || query.get("locale") || runtimeConfig.defaultLocale || "zh-CN"
  ).trim() || "zh-CN";

  const elements = {
    shell: document.querySelector(".help-shell"),
    menuBtn: document.getElementById("helpMenuBtn"),
    headerActions: document.getElementById("helpHeaderActions"),
    homeBtn: document.getElementById("helpHomeBtn"),
    themeBtn: document.getElementById("helpThemeToggleBtn"),
    modeBtn: document.getElementById("helpModeToggleBtn"),
    reloadBtn: document.getElementById("helpReloadBtn"),
    docSelect: document.getElementById("helpDocSelect"),
    searchInput: document.getElementById("helpSearchInput"),
    searchResults: document.getElementById("helpSearchResults"),
    toc: document.getElementById("helpToc"),
    content: document.getElementById("helpContent"),
    meta: document.getElementById("helpMeta"),
  };

  const state = {
    docs: [],
    doc: null,
    mode: requestedMode,
    lang: requestedLang,
    headings: [],
    sections: [],
    sectionMap: new Map(),
    headingToSection: new Map(),
    validIds: new Set(),
    idMapUpper: new Map(),
    activeId: null,
    currentSectionId: null,
    searchIndex: [],
    scrollRaf: 0,
    searchTimer: 0,
  };

  const mobileMenuMedia = window.matchMedia("(max-width: 760px)");

  function isMobileMenuMode() {
    return Boolean(mobileMenuMedia && mobileMenuMedia.matches);
  }

  function closeMobileMenu() {
    if (!elements.shell) {
      return;
    }
    elements.shell.classList.remove("menu-open");
    if (elements.menuBtn) {
      elements.menuBtn.setAttribute("aria-expanded", "false");
    }
  }

  function toggleMobileMenu() {
    if (!elements.shell || !isMobileMenuMode()) {
      return;
    }
    const opening = !elements.shell.classList.contains("menu-open");
    elements.shell.classList.toggle("menu-open", opening);
    if (elements.menuBtn) {
      elements.menuBtn.setAttribute("aria-expanded", opening ? "true" : "false");
    }
  }

  function syncMobileMenuState() {
    if (!isMobileMenuMode()) {
      closeMobileMenu();
      return;
    }
    if (elements.menuBtn && !elements.shell.classList.contains("menu-open")) {
      elements.menuBtn.setAttribute("aria-expanded", "false");
    }
  }

  function setThemeButtonText() {
    const isDark = document.documentElement.classList.contains("dark");
    elements.themeBtn.textContent = isDark
      ? getHelpRuntimeText("themeToggleLight", "Light mode")
      : getHelpRuntimeText("themeToggleDark", "Dark mode");
  }

  function setModeButtonText() {
    elements.modeBtn.textContent = state.mode === "single"
      ? getHelpRuntimeText("modeToggleSingle", "Full Page")
      : getHelpRuntimeText("modeToggleFull", "Single Page");
  }

  function toggleTheme() {
    const isDark = document.documentElement.classList.contains("dark");
    const next = isDark ? "light" : "dark";
    document.documentElement.classList.remove("light", "dark");
    document.documentElement.classList.add(next);
    try {
      window.localStorage.setItem("webshopx_theme", next);
    } catch (error) {
      // ignore
    }
    setThemeButtonText();
  }

  function buildHelpUrl(docId, lang, mode, hashId) {
    const url = new URL(window.location.href);
    url.searchParams.set("doc", docId);
    if (lang) {
      url.searchParams.set("lang", lang);
    } else {
      url.searchParams.delete("lang");
    }

    if (mode === "single") {
      url.searchParams.set("mode", "single");
    } else {
      url.searchParams.delete("mode");
    }

    if (requestedCategory) {
      url.searchParams.set("category", requestedCategory);
    } else {
      url.searchParams.delete("category");
    }

    if (requestedAlgorithm) {
      url.searchParams.set("algorithm", requestedAlgorithm);
    } else {
      url.searchParams.delete("algorithm");
    }

    url.searchParams.delete("locale");

    if (hashId) {
      url.hash = encodeURIComponent(hashId);
    } else {
      url.hash = "";
    }
    return url.toString();
  }

  function toggleMode() {
    const next = state.mode === "single" ? "full" : "single";
    try {
      window.localStorage.setItem("webshopx_help_mode", next);
    } catch (error) {
      // ignore
    }
    const hashId = getCurrentHashId();
    window.location.assign(buildHelpUrl(state.doc.id, state.lang, next, hashId));
  }

  function parseDocFromFile(fileName) {
    const normalized = String(fileName || "").trim();
    const noExt = normalized.replace(/\.md$/i, "");
    const match = noExt.match(/^(.*)\.([A-Za-z]{2}(?:-[A-Za-z]{2})?)$/);
    if (!match) {
      return {
        id: noExt,
        key: noExt,
        locale: "",
        file: normalized,
      };
    }

    return {
      id: noExt,
      key: match[1],
      locale: match[2],
      file: normalized,
    };
  }

  function normalizeDocEntry(raw) {
    const parsed = parseDocFromFile(raw.file || raw.path || raw.id || "");
    return {
      id: String(raw.id || parsed.id || "").trim(),
      key: String(raw.key || parsed.key || "").trim(),
      locale: String(raw.locale || parsed.locale || "").trim(),
      title: String(raw.title || "").trim() || String(raw.id || parsed.id || "").trim(),
      file: String(raw.file || parsed.file || "").trim(),
      path: `docs/${String(raw.file || parsed.file || "").trim()}`,
    };
  }

  async function loadDocsManifest() {
    const fallback = [
      { file: "manual.zh-CN.md", title: getHelpRuntimeText("fallbackManualTitle", "WebShopX Manual") },
      { file: "help.zh-CN.md", title: getHelpRuntimeText("fallbackHelpZhTitle", "WebShopX Help (zh-CN)") },
      { file: "help.en-US.md", title: getHelpRuntimeText("fallbackHelpEnTitle", "WebShopX Help (en-US)") },
    ].map(normalizeDocEntry);

    const manifestPath = String(runtimeConfig.docsManifest || "docs/index.json").trim() || "docs/index.json";
    try {
      const response = await fetch(manifestPath, { cache: "no-cache" });
      if (!response.ok) {
        return fallback;
      }
      const payload = await response.json();
      if (!payload || !Array.isArray(payload.documents)) {
        return fallback;
      }

      const docs = payload.documents
        .map(normalizeDocEntry)
        .filter((doc) => doc.id && doc.file);

      if (docs.length === 0) {
        return fallback;
      }

      const dedup = new Map();
      for (const doc of docs) {
        if (!dedup.has(doc.id)) {
          dedup.set(doc.id, doc);
        }
      }

      return Array.from(dedup.values()).sort((left, right) => {
        const keyCompare = left.key.localeCompare(right.key, "en", { sensitivity: "base" });
        if (keyCompare !== 0) {
          return keyCompare;
        }
        return left.locale.localeCompare(right.locale, "en", { sensitivity: "base" });
      });
    } catch (error) {
      return fallback;
    }
  }

  function pickDocByToken(token) {
    if (!token) {
      return null;
    }
    const normalized = token.trim().toLowerCase();
    const candidates = state.docs.filter((doc) =>
      doc.id.toLowerCase() === normalized
      || doc.file.toLowerCase() === normalized
      || doc.key.toLowerCase() === normalized
    );
    if (candidates.length === 0) {
      return null;
    }

    const byLang = candidates.find((doc) => doc.locale.toLowerCase() === state.lang.toLowerCase());
    return byLang || candidates[0];
  }

  function pickDocByKey(key, lang) {
    const candidates = state.docs.filter((doc) => doc.key.toLowerCase() === String(key).toLowerCase());
    if (candidates.length === 0) {
      return null;
    }
    const byLang = candidates.find((doc) => doc.locale.toLowerCase() === String(lang).toLowerCase());
    if (byLang) {
      return byLang;
    }

    const defaultLocale = String(runtimeConfig.defaultLocale || "").toLowerCase();
    const byDefaultLocale = candidates.find((doc) => doc.locale.toLowerCase() === defaultLocale);
    if (byDefaultLocale) {
      return byDefaultLocale;
    }

    const localeLess = candidates.find((doc) => !doc.locale);
    return localeLess || candidates[0];
  }

  function resolveInitialDoc() {
    const tokenDoc = pickDocByToken(requestedDocToken);
    if (tokenDoc) {
      return tokenDoc;
    }

    const defaultKey = String(runtimeConfig.helpDefaultDoc || "manual").trim() || "manual";
    if (state.mode === "single") {
      const singleDoc = pickDocByKey(defaultKey, state.lang);
      if (singleDoc) {
        return singleDoc;
      }
    }

    const helpDoc = pickDocByKey("help", state.lang);
    if (helpDoc) {
      return helpDoc;
    }

    return state.docs[0] || null;
  }

  function refreshDocControls() {
    elements.docSelect.innerHTML = "";
    for (const doc of state.docs) {
      const option = document.createElement("option");
      option.value = doc.id;
      const localeLabel = doc.locale ? ` [${doc.locale}]` : "";
      option.textContent = `${doc.title}${localeLabel}`;
      if (state.doc && state.doc.id === doc.id) {
        option.selected = true;
      }
      elements.docSelect.appendChild(option);
    }
  }

  function resetDocumentModel() {
    state.headings = [];
    state.sections = [];
    state.sectionMap.clear();
    state.headingToSection.clear();
    state.validIds.clear();
    state.idMapUpper.clear();
    state.searchIndex = [];
    state.activeId = null;
    state.currentSectionId = null;
  }

  function sanitizeHashId(raw) {
    let decoded = "";
    try {
      decoded = decodeURIComponent(String(raw || "").replace(/^#/, "")).trim();
    } catch (error) {
      return "";
    }
    if (!decoded || decoded.length > 120) {
      return "";
    }
    if (!/^[A-Za-z0-9._\-\u4e00-\u9fa5]+$/.test(decoded)) {
      return "";
    }
    return decoded;
  }

  function getCurrentHashId() {
    return sanitizeHashId(window.location.hash || "");
  }

  function createHeadingId(text, used, explicitId) {
    let base = String(explicitId || text || "").trim();
    if (!base) {
      base = "section";
    }

    base = base
      .replace(/\s+/g, "-")
      .replace(/[!"#$%&'()*+,./:;<=>?@[\\\]^`{|}~，。！？；：“”‘’、]/g, "")
      .replace(/-+/g, "-")
      .replace(/^-|-$/g, "");

    if (!base) {
      base = "section";
    }

    if (!used.has(base)) {
      used.add(base);
      return base;
    }

    let suffix = 2;
    while (used.has(`${base}-${suffix}`)) {
      suffix += 1;
    }
    const id = `${base}-${suffix}`;
    used.add(id);
    return id;
  }

  function applyMathRendering() {
    if (typeof window.renderMathInElement !== "function") {
      return;
    }
    window.renderMathInElement(elements.content, {
      delimiters: [
        { left: "$$", right: "$$", display: true },
        { left: "$", right: "$", display: false },
        { left: "\\(", right: "\\)", display: false },
      ],
      ignoredTags: ["script", "noscript", "style", "textarea", "pre", "code"],
      processEscapes: true,
      throwOnError: false,
      strict: "ignore",
    });
  }

  // Marked 会把 "\(" 里的反斜杠当作转义吞掉；先转成 $...$ 再交给 KaTeX。
  function normalizeMarkdownMath(markdown) {
    const lines = String(markdown || "").replace(/\r\n/g, "\n").split("\n");
    let inFence = false;

    for (let index = 0; index < lines.length; index += 1) {
      const line = lines[index];
      const trimmed = line.trim();

      if (/^```/.test(trimmed)) {
        inFence = !inFence;
        continue;
      }

      if (inFence) {
        continue;
      }

      lines[index] = line
        .replace(/\\\((.+?)\\\)/g, (_, expression) => `$${expression}$`)
        .replace(/\\\[(.+?)\\\]/g, (_, expression) => `$$${expression}$$`);
    }

    return lines.join("\n");
  }

  function renderMarkdown(markdown) {
    if (typeof window.marked === "undefined") {
      const fallback = document.createElement("pre");
      fallback.textContent = markdown;
      elements.content.innerHTML = "";
      elements.content.appendChild(fallback);
      return;
    }

    const normalizedMarkdown = normalizeMarkdownMath(markdown);

    const html = window.marked.parse(normalizedMarkdown, {
      gfm: true,
      breaks: false,
    });

    const safeHtml = typeof window.DOMPurify !== "undefined"
      ? window.DOMPurify.sanitize(html)
      : html;

    elements.content.innerHTML = safeHtml;
    applyMathRendering();
  }

  function registerValidId(id) {
    state.validIds.add(id);
    state.idMapUpper.set(id.toUpperCase(), id);
  }

  function buildHeadingsModel() {
    const usedIds = new Set();
    const headingElements = elements.content.querySelectorAll("h1, h2, h3, h4, h5, h6");
    const headings = [];

    for (const heading of headingElements) {
      const level = Number(heading.tagName.slice(1));
      let text = String(heading.textContent || "").trim();
      let explicitId = "";

      const explicitMatch = text.match(/^(.*)\s+\{#([^}]+)\}\s*$/);
      if (explicitMatch) {
        text = explicitMatch[1].trim();
        explicitId = explicitMatch[2].trim();
        heading.textContent = text;
      }

      const id = createHeadingId(text, usedIds, explicitId);
      heading.id = id;
      headings.push({
        id,
        level,
        text,
        element: heading,
      });
      registerValidId(id);
    }

    state.headings = headings;
  }

  function splitSectionsByHeading2() {
    const nodes = Array.from(elements.content.childNodes);
    const h1 = elements.content.querySelector("h1");
    const rootTitle = h1 ? String(h1.textContent || "").trim() : getHelpRuntimeText("rootDocTitle", "Document");
    const rootId = h1 && h1.id ? h1.id : "top";

    const sections = [];
    let current = {
      id: rootId,
      title: rootTitle,
      nodes: [],
    };

    for (const node of nodes) {
      if (node.nodeType === Node.ELEMENT_NODE && node.tagName === "H2") {
        if (current.nodes.length > 0) {
          sections.push(current);
        }
        const title = String(node.textContent || "").trim() || getHelpRuntimeText("unnamedSection", "Untitled section");
        const id = node.id || createHeadingId(title, new Set(), "");
        current = {
          id,
          title,
          nodes: [node],
        };
        continue;
      }
      current.nodes.push(node);
    }

    if (current.nodes.length > 0) {
      sections.push(current);
    }

    elements.content.innerHTML = "";
    state.sections = [];

    for (const section of sections) {
      const sectionEl = document.createElement("section");
      sectionEl.className = "doc-section";
      sectionEl.dataset.sectionId = section.id;
      for (const node of section.nodes) {
        sectionEl.appendChild(node);
      }
      elements.content.appendChild(sectionEl);
      state.sections.push({
        id: section.id,
        title: section.title,
        element: sectionEl,
      });
      state.sectionMap.set(section.id, {
        id: section.id,
        title: section.title,
        element: sectionEl,
      });
      registerValidId(section.id);
    }

    for (const section of state.sections) {
      const scopedHeadings = section.element.querySelectorAll("h1, h2, h3, h4, h5, h6");
      for (const heading of scopedHeadings) {
        if (heading.id) {
          state.headingToSection.set(heading.id, section.id);
        }
      }
    }
  }

  function buildToc() {
    elements.toc.innerHTML = "";
    const headings = state.headings.filter((heading) => heading.level >= 2 && heading.level <= 4);
    if (headings.length === 0) {
      const empty = document.createElement("p");
      empty.className = "help-empty";
      empty.textContent = getHelpRuntimeText("noNavigableHeadings", "No navigable headings found.");
      elements.toc.appendChild(empty);
      return;
    }

    for (const heading of headings) {
      const link = document.createElement("a");
      link.className = `toc-link level-${heading.level}`;
      link.dataset.id = heading.id;
      link.href = `#${encodeURIComponent(heading.id)}`;
      link.textContent = heading.text;
      link.addEventListener("click", (event) => {
        event.preventDefault();
        if (window.location.hash === `#${encodeURIComponent(heading.id)}` || window.location.hash === `#${heading.id}`) {
          navigateTo(heading.id, { smooth: true, scroll: true, updateHash: false });
          return;
        }
        window.location.hash = encodeURIComponent(heading.id);
      });
      elements.toc.appendChild(link);
    }
  }

  function setActiveToc(id) {
    if (!id || state.activeId === id) {
      return;
    }
    state.activeId = id;

    let activeLink = null;
    const links = elements.toc.querySelectorAll(".toc-link");
    links.forEach((link) => {
      const selected = link.dataset.id === id;
      link.classList.toggle("active", selected);
      if (selected) {
        activeLink = link;
      }
    });

    if (activeLink) {
      activeLink.scrollIntoView({ block: "nearest", inline: "nearest" });
    }
  }

  function resolveSectionId(targetId) {
    if (state.sectionMap.has(targetId)) {
      return targetId;
    }
    return state.headingToSection.get(targetId) || null;
  }

  function applyModeVisibility() {
    if (state.mode !== "single") {
      for (const section of state.sections) {
        section.element.hidden = false;
      }
      return;
    }

    const fallbackId = state.sectionMap.has(state.currentSectionId)
      ? state.currentSectionId
      : (state.sections[0] ? state.sections[0].id : null);

    if (!fallbackId) {
      return;
    }

    state.currentSectionId = fallbackId;
    for (const section of state.sections) {
      section.element.hidden = section.id !== fallbackId;
    }
  }

  function findLooseId(candidate) {
    const strict = sanitizeHashId(candidate);
    if (strict && state.validIds.has(strict)) {
      return strict;
    }

    const upper = String(candidate || "").trim().toUpperCase();
    if (!upper) {
      return null;
    }

    if (state.idMapUpper.has(upper)) {
      return state.idMapUpper.get(upper);
    }

    for (const heading of state.headings) {
      if (String(heading.text || "").toUpperCase().includes(upper)) {
        return heading.id;
      }
    }

    return null;
  }

  function resolveDefaultTargetId() {
    const hashId = getCurrentHashId();
    if (hashId && state.validIds.has(hashId)) {
      return hashId;
    }

    if (requestedAlgorithm) {
      const algorithmId = findLooseId(requestedAlgorithm);
      if (algorithmId) {
        return algorithmId;
      }
    }

    if (requestedCategory === "dynamic") {
      const dynamicId = findLooseId("dynamic-algorithms") || findLooseId(getHelpRuntimeText("dynamicAlgTitle", "动态算法总览"));
      if (dynamicId) {
        return dynamicId;
      }
    }

    if (requestedCategory === "auction") {
      const auctionId = findLooseId("market-auction") || findLooseId(getHelpRuntimeText("auctionTitle", "拍卖模式说明"));
      if (auctionId) {
        return auctionId;
      }
    }

    const firstHeading = state.headings.find((heading) => heading.level >= 2);
    if (firstHeading) {
      return firstHeading.id;
    }

    return state.sections[0] ? state.sections[0].id : null;
  }

  function navigateTo(targetId, options) {
    const resolved = findLooseId(targetId);
    if (!resolved || !state.validIds.has(resolved)) {
      return false;
    }

    const sectionId = resolveSectionId(resolved);
    if (state.mode === "single" && sectionId) {
      state.currentSectionId = sectionId;
      applyModeVisibility();
    }

    const targetEl = document.getElementById(resolved)
      || (sectionId ? state.sectionMap.get(sectionId)?.element : null);
    if (!targetEl) {
      return false;
    }

    if (options.updateHash) {
      const url = new URL(window.location.href);
      url.hash = encodeURIComponent(resolved);
      window.history.replaceState(null, "", url.toString());
    }

    if (options.scroll !== false) {
      targetEl.scrollIntoView({
        behavior: options.smooth ? "smooth" : "auto",
        block: "start",
      });
    }

    setActiveToc(resolved);
    return true;
  }

  function applyHashRoute(isInitial) {
    const hashId = getCurrentHashId();
    if (hashId && state.validIds.has(hashId)) {
      navigateTo(hashId, {
        smooth: !isInitial,
        scroll: true,
        updateHash: false,
      });
      return;
    }

    const fallback = resolveDefaultTargetId();
    if (!fallback) {
      return;
    }

    navigateTo(fallback, {
      smooth: false,
      scroll: true,
      updateHash: false,
    });

    const url = new URL(window.location.href);
    url.hash = encodeURIComponent(fallback);
    window.history.replaceState(null, "", url.toString());
  }

  function updateActiveByScrollNow() {
    const visible = state.headings.filter((heading) => heading.element.offsetParent !== null);
    if (visible.length === 0) {
      return;
    }

    let current = visible[0].id;
    for (const heading of visible) {
      if (heading.element.getBoundingClientRect().top <= 125) {
        current = heading.id;
      } else {
        break;
      }
    }

    setActiveToc(current);
  }

  function queueScrollSync() {
    if (state.scrollRaf) {
      return;
    }
    state.scrollRaf = window.requestAnimationFrame(() => {
      state.scrollRaf = 0;
      updateActiveByScrollNow();
    });
  }

  function buildSearchIndex() {
    const items = [];

    for (const section of state.sections) {
      const text = String(section.element.textContent || "").toLowerCase();
      items.push({
        id: section.id,
        type: "section",
        title: section.title,
        subtitle: getHelpRuntimeText("searchSection", "Section"),
        sectionId: section.id,
        haystack: `${section.title} ${text}`.toLowerCase(),
      });
    }

    for (const heading of state.headings) {
      const sectionId = state.headingToSection.get(heading.id) || "";
      const sectionTitle = state.sectionMap.get(sectionId)?.title || "";
      const nextText = heading.element.nextElementSibling
        ? String(heading.element.nextElementSibling.textContent || "")
        : "";
      items.push({
        id: heading.id,
        type: "heading",
        title: heading.text,
        subtitle: sectionTitle,
        sectionId,
        haystack: `${heading.text} ${sectionTitle} ${nextText}`.toLowerCase(),
      });
    }

    state.searchIndex = items;
  }

  function renderSearchResults(keyword) {
    const input = String(keyword || "").trim().toLowerCase();
    elements.searchResults.innerHTML = "";
    if (!input) {
      return;
    }

    const tokens = input.split(/\s+/).filter(Boolean);
    const ranked = [];
    const seen = new Set();

    for (const item of state.searchIndex) {
      if (seen.has(item.id)) {
        continue;
      }
      let score = 0;
      if (item.title.toLowerCase().includes(input)) {
        score += item.type === "section" ? 140 : 120;
      }
      for (const token of tokens) {
        if (item.haystack.includes(token)) {
          score += 25;
        }
      }
      if (!item.haystack.includes(input)) {
        score -= 10;
      }
      if (score <= 0) {
        continue;
      }
      ranked.push({ item, score });
      seen.add(item.id);
    }

    ranked.sort((left, right) => right.score - left.score);
    const limited = ranked.slice(0, 12);

    if (limited.length === 0) {
      const empty = document.createElement("div");
      empty.className = "help-search-empty";
      empty.textContent = getHelpRuntimeText("noSearchMatch", "No matching content");
      elements.searchResults.appendChild(empty);
      return;
    }

    for (const result of limited) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "search-result-item";

      const main = document.createElement("span");
      main.className = "search-result-main";
      main.textContent = result.item.title;
      button.appendChild(main);

      const sub = document.createElement("span");
      sub.className = "search-result-sub";
      sub.textContent = result.item.subtitle || (
        result.item.type === "section"
          ? getHelpRuntimeText("searchSection", "Section")
          : getHelpRuntimeText("searchHeading", "Heading")
      );
      button.appendChild(sub);

      button.addEventListener("click", () => {
        const encoded = encodeURIComponent(result.item.id);
        if (window.location.hash === `#${encoded}` || window.location.hash === `#${result.item.id}`) {
          navigateTo(result.item.id, {
            smooth: true,
            scroll: true,
            updateHash: false,
          });
        } else {
          window.location.hash = encoded;
        }
        elements.searchInput.value = "";
        elements.searchResults.innerHTML = "";
      });

      elements.searchResults.appendChild(button);
    }
  }

  function rewriteMarkdownLinks() {
    const anchors = elements.content.querySelectorAll("a[href]");
    for (const anchor of anchors) {
      const href = String(anchor.getAttribute("href") || "").trim();
      if (!href) {
        continue;
      }

      if (href.startsWith("#")) {
        const id = sanitizeHashId(href);
        if (!id) {
          continue;
        }
        anchor.href = `#${encodeURIComponent(id)}`;
        anchor.addEventListener("click", (event) => {
          event.preventDefault();
          if (window.location.hash === `#${encodeURIComponent(id)}` || window.location.hash === `#${id}`) {
            navigateTo(id, {
              smooth: true,
              scroll: true,
              updateHash: false,
            });
            return;
          }
          window.location.hash = encodeURIComponent(id);
        });
        continue;
      }

      if (/^(https?:|mailto:|javascript:)/i.test(href)) {
        continue;
      }

      const mdMatch = href.match(/^(.+?\.md)(?:#(.+))?$/i);
      if (!mdMatch) {
        continue;
      }

      const fileName = mdMatch[1].split("/").pop();
      const hashPart = sanitizeHashId(mdMatch[2] || "");
      if (!fileName) {
        continue;
      }

      const targetDoc = state.docs.find((doc) => doc.file.toLowerCase() === fileName.toLowerCase());
      if (!targetDoc) {
        continue;
      }

      anchor.href = buildHelpUrl(targetDoc.id, targetDoc.locale || state.lang, state.mode, hashPart || "");
    }
  }

  function findDocById(id) {
    return state.docs.find((doc) => doc.id === id) || null;
  }

  function setMetaInfo() {
    elements.meta.innerHTML = "";
    if (!state.doc) {
      return;
    }

    const fields = [
      `doc: ${state.doc.id}`,
      // `file: ${state.doc.file}`,
      // `lang: ${state.lang}`,
      `mode: ${state.mode}`,
    ];

    if (requestedCategory) {
      fields.push(`category: ${requestedCategory}`);
    }
    if (requestedAlgorithm) {
      fields.push(`algorithm: ${requestedAlgorithm}`);
    }

    for (const field of fields) {
      const item = document.createElement("span");
      item.textContent = field;
      elements.meta.appendChild(item);
    }
  }

  async function fetchDocumentText(doc) {
    const response = await fetch(doc.path, { cache: "no-cache" });
    if (!response.ok) {
      throw new Error(formatHelpRuntimeText("docFetchFailed", { file: doc.file }, `Cannot load ${doc.file}`));
    }
    return response.text();
  }

  async function loadCurrentDocument() {
    if (!state.doc) {
      elements.content.innerHTML = `<p class='help-empty'>${getHelpRuntimeText("docNotFound", "No available documents found.")}</p>`;
      return;
    }

    elements.content.innerHTML = `<p class='help-empty'>${getHelpRuntimeText("docLoading", "Loading document...")}</p>`;
    elements.toc.innerHTML = "";
    elements.searchResults.innerHTML = "";
    resetDocumentModel();

    try {
      const markdown = await fetchDocumentText(state.doc);
      renderMarkdown(markdown);
      buildHeadingsModel();
      splitSectionsByHeading2();
      buildToc();
      buildSearchIndex();
      rewriteMarkdownLinks();
      applyModeVisibility();
      setMetaInfo();
      applyHashRoute(true);
      queueScrollSync();
    } catch (error) {
      elements.content.innerHTML = "";
      const fail = document.createElement("p");
      fail.className = "help-empty";
      fail.textContent = formatHelpRuntimeText("docLoadFailed", { message: error.message }, `Document load failed: ${error.message}`);
      elements.content.appendChild(fail);
    }
  }

  function switchToDoc(doc, lang, preserveHash) {
    state.doc = doc;
    state.lang = lang || doc.locale || state.lang;
    refreshDocControls();

    const hashId = preserveHash ? getCurrentHashId() : "";
    window.history.replaceState(
      null,
      "",
      buildHelpUrl(state.doc.id, state.lang, state.mode, hashId)
    );

    loadCurrentDocument();
  }

  function bindEvents() {
    if (elements.menuBtn) {
      elements.menuBtn.addEventListener("click", (event) => {
        event.preventDefault();
        event.stopPropagation();
        toggleMobileMenu();
      });
    }

    document.addEventListener("click", (event) => {
      if (!isMobileMenuMode() || !elements.shell || !elements.shell.classList.contains("menu-open")) {
        return;
      }
      const target = event.target;
      if (!(target instanceof Node)) {
        return;
      }
      if ((elements.menuBtn && elements.menuBtn.contains(target))
        || (elements.headerActions && elements.headerActions.contains(target))) {
        return;
      }
      closeMobileMenu();
    });

    elements.homeBtn.addEventListener("click", () => {
      closeMobileMenu();
      window.location.href = "index.html";
    });

    elements.themeBtn.addEventListener("click", () => {
      toggleTheme();
      closeMobileMenu();
    });
    elements.modeBtn.addEventListener("click", () => {
      closeMobileMenu();
      toggleMode();
    });

    elements.reloadBtn.addEventListener("click", () => {
      closeMobileMenu();
      loadCurrentDocument();
    });

    elements.docSelect.addEventListener("change", () => {
      const target = findDocById(elements.docSelect.value);
      if (!target) {
        return;
      }
      const targetLang = target.locale || state.lang;
      closeMobileMenu();
      switchToDoc(target, targetLang, true);
    });

    elements.searchInput.addEventListener("input", () => {
      if (state.searchTimer) {
        window.clearTimeout(state.searchTimer);
      }
      state.searchTimer = window.setTimeout(() => {
        renderSearchResults(elements.searchInput.value);
      }, 120);
    });

    elements.searchInput.addEventListener("keydown", (event) => {
      if (event.key === "Escape") {
        closeMobileMenu();
        elements.searchInput.value = "";
        elements.searchResults.innerHTML = "";
      }
      if (event.key === "Enter") {
        const first = elements.searchResults.querySelector(".search-result-item");
        if (first) {
          first.click();
          event.preventDefault();
        }
      }
    });

    window.addEventListener("hashchange", () => {
      applyHashRoute(false);
    });

    window.addEventListener("resize", syncMobileMenuState);

    window.addEventListener("keydown", (event) => {
      if (event.key === "Escape") {
        closeMobileMenu();
      }
    });

    window.addEventListener("scroll", queueScrollSync, { passive: true });
  }

  async function init() {
    setThemeButtonText();
    setModeButtonText();

    state.docs = await loadDocsManifest();
    const initialDoc = resolveInitialDoc();
    if (!initialDoc) {
      elements.content.innerHTML = `<p class='help-empty'>${getHelpRuntimeText("docNotFound", "No available documents found.")}</p>`;
      return;
    }

    state.doc = initialDoc;
    state.lang = initialDoc.locale || state.lang;

    bindEvents();
    syncMobileMenuState();
    refreshDocControls();

    window.history.replaceState(
      null,
      "",
      buildHelpUrl(state.doc.id, state.lang, state.mode, getCurrentHashId())
    );

    loadCurrentDocument();
  }

  init();
})();
