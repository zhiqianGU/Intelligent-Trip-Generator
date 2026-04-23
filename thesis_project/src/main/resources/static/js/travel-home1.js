// travel-home.js
(() => {
  'use strict';

  // =========================
  // storage keys
  // =========================
  const STORAGE_KEY = 'nav_jwt';
  const PLAN_CACHE_KEY = 'latest_trip_plan_draft';
  const PLAN_PREVIEW_KEY = 'latest_trip_plan_preview';
  const PLAN_ID_KEY = 'latest_trip_plan_id';
  const PLAN_FAVORITE_KEY = 'latest_trip_plan_favorite';
  const PLAN_DRIVE_KEY = 'latest_trip_plan_self_drive';

  let renameActionContext = null;
  let deleteActionContext = null;
  let generateProgressTimer = null;
  let routeSuggestionRunId = 0;

  // =========================
  // api
  // =========================
  const API = {
    login: '/auth/login',
    register: '/auth/register',
    challenge: '/auth/challenge',
    refresh: '/auth/refresh',
    logout: '/auth/logout',
    raw: '/api/v1/plans/raw',
    draft: '/api/v1/plans/draft',
    routeSuggestions: '/api/v1/plans/route-suggestions',
    weather: '/api/v1/plans/weather',
    myPlans: '/api/v1/plans/me',
    myFavorites: '/api/v1/plans/me/favorites',
    favorite: (planId, value) => `/api/v1/plans/${planId}/favorite?value=${value}`,
    rename: (planId, title) => `/api/v1/plans/${planId}/title?title=${encodeURIComponent(title ?? '')}`,
    delete: (planId) => `/api/v1/plans/${planId}`,
    detail: (planId) => `/api/v1/plans/${planId}`
  };

  // =========================
  // selector helpers
  // =========================
  function pick(...selectors) {
    for (const s of selectors) {
      const el = document.querySelector(s);
      if (el) return el;
    }
    return null;
  }

  function picks(...selectors) {
    for (const s of selectors) {
      const list = document.querySelectorAll(s);
      if (list && list.length) return Array.from(list);
    }
    return [];
  }

  // =========================
  // elements
  // =========================
  const els = {
    toast: pick('#toast'),

    // top right
    btnMapView: pick('#btnMapView', '#btnGotoMap'),
    btnMapButtons: picks('#btnMapView', '#btnGotoMap', '#btnOpenMap', '[data-action="goto-map"]'),
    btnOpenAuth: pick('#btnOpenAuth', '#openAuthBtn'),

    // guest / logged-in account zones
    accountGuestWrap: pick('#accountGuest', '#authGuestWrap', '#guestPanel', '.account-guest'),
    accountUserWrap: pick('#accountUser', '#authUserWrap', '#userPanel', '.account-user'),

    // user info
    userName: pick('#userName', '#accountUserName', '[data-role="user-name"]'),
    userHint: pick('#userHint', '#accountUserHint', '[data-role="user-hint"]'),

    // login/register container
    authEntryTitle: pick('#authEntryTitle', '[data-role="auth-title"]'),
    authFormsWrap: pick('#authFormsWrap', '#authGuestBox', '.auth-forms-wrap'),
    accountActionsWrap: pick('#accountActionsWrap', '#afterLoginActions', '.account-actions-wrap'),

    // tabs
    authTabLogin: pick('#tabLogin', '[data-tab="login"]'),
    authTabRegister: pick('#tabRegister', '[data-tab="register"]'),
    loginPanel: pick('#loginPanel', '#loginFormWrap', '[data-panel="login"]'),
    registerPanel: pick('#registerPanel', '#registerFormWrap', '[data-panel="register"]'),

    // login form
    loginForm: pick('#loginForm'),
    loginId: pick('#loginId'),
    loginPassword: pick('#loginPassword'),
    loginSubmit: pick('#loginForm button[type="submit"]'),
    loginChallengeBox: pick('#loginChallengeBox'),
    loginChallengeQuestion: pick('#loginChallengeQuestion'),
    loginChallengeId: pick('#loginChallengeId'),
    loginChallengeAnswer: pick('#loginChallengeAnswer'),
    loginChallengeRefresh: pick('#loginChallengeRefresh'),
    toggleLoginPassword: pick('#toggleLoginPassword'),
    rememberMe: pick('#rememberMe'),

    // register form
    registerForm: pick('#registerForm'),
    regEmail: pick('#regEmail'),
    regPhone: pick('#regPhone'),
    regPassword: pick('#regPassword'),
    toggleRegPassword: pick('#toggleRegPassword'),
    regUsername: pick('#regUsername'),
    registerSubmit: pick('#registerForm button[type="submit"]'),
    registerChallengeBox: pick('#registerChallengeBox'),
    registerChallengeQuestion: pick('#registerChallengeQuestion'),
    registerChallengeId: pick('#registerChallengeId'),
    registerChallengeAnswer: pick('#registerChallengeAnswer'),
    registerChallengeRefresh: pick('#registerChallengeRefresh'),

    // account buttons after login
    btnHistoryPlans: pick('#btnHistoryPlans', '#viewHistoryPlans', '[data-action="history-plans"]'),
    btnFavoritePlans: pick('#btnFavoritePlans', '#viewFavoritePlans', '[data-action="favorite-plans"]'),
    btnLogout: pick('#btnLogout', '[data-action="logout"]'),
    accountListBox: pick('#accountListBox', '#historyListBox', '.account-list-box'),

    // generate form
    generateForm: pick('#generateForm'),
    city: pick('#city', '#planCity'),
    days: pick('#days', '#planDays'),
    budget: pick('#budget', '#planBudget'),
    adults: pick('#adults', '#partyAdults'),
    kids: pick('#kids', '#partyKids'),
    departureDate: pick('#departureDate', '#planDepartureDate'),
    style: pick('#style', '#planStyle'),
    styleChecks: picks('input[name="style"]'),
    pace: pick('#pace', '#planPace'),
    mainModel: pick('#mainModel', '#planMainModel'),
    selfDrive: pick('#selfDrive', '#isSelfDrive', 'input[name="selfDrive"]'),
    btnGenerate: pick('#btnGenerate', '[data-action="generate-plan"]'),
    generateProgress: pick('#generateProgress'),
    generateProgressFill: pick('#generateProgressFill'),
    generateProgressLabel: pick('#generateProgressLabel'),
    generateProgressPercent: pick('#generateProgressPercent'),
    generateProgressHint: pick('#generateProgressHint'),

    // result
    resultSection: pick('#resultSection', '#planResultSection', '.result-section'),
    resultEmpty: pick('#resultEmpty', '#planResultEmpty', '.result-empty'),
    resultContent: pick('#resultContent', '#planResultContent', '.result-content'),
    resultTitle: pick('#resultTitle', '#planResultTitle'),
    resultMeta: pick('#resultMeta', '#planResultMeta'),
    resultDays: pick('#resultDays', '#planResultDays'),
    previewBox: pick('#previewBox', '#planPreviewBox', '.preview-box'),

    // action buttons under result
    btnClearPlan: pick('#btnClearPlan', '[data-action="clear-plan"]'),
    btnCopyJson: pick('#btnCopyJson'),
    btnFavoritePlan: pick('#btnFavoritePlan', '[data-action="favorite-plan"]'),

    planListModal: pick('#planListModal'),
    planListModalMask: pick('#planListModalMask'),
    planListModalClose: pick('#planListModalClose'),
    planListModalTitle: pick('#planListModalTitle'),
    planListModalBody: pick('#planListModalBody'),

    renameModal: pick('#renameModal'),
    renameModalMask: pick('#renameModalMask'),
    renameModalClose: pick('#renameModalClose'),
    renameModalTitle: pick('#renameModalTitle'),
    renameInput: pick('#renameInput'),
    renameModalSkip: pick('#renameModalSkip'),
    renameModalConfirm: pick('#renameModalConfirm'),
    deletePlanModal: pick('#deletePlanModal'),
    deletePlanModalMask: pick('#deletePlanModalMask'),
    deletePlanModalClose: pick('#deletePlanModalClose'),
    deletePlanModalCancel: pick('#deletePlanModalCancel'),
    deletePlanModalConfirm: pick('#deletePlanModalConfirm'),

  };

  // =========================
  // state
  // =========================
  let currentPlanId = Number(localStorage.getItem(PLAN_ID_KEY) || 0) || null;
  let currentDraft = readJson(PLAN_CACHE_KEY);
  let currentPreview = sanitizePreviewForDisplay(localStorage.getItem(PLAN_PREVIEW_KEY) || '');
  let currentFavorite = localStorage.getItem(PLAN_FAVORITE_KEY) === 'true';
  let lastRequestedPace = null;
  let currentDayPage = 1;
  let latestRouteSuggestions = null;
  let latestWeatherForecast = null;

  if (els.departureDate && !els.departureDate.value) {
    els.departureDate.value = new Date().toISOString().slice(0, 10);
  }

  // =========================
  // utils
  // =========================
  function showToast(message, isError = false) {
    if (!els.toast) {
      alert(message);
      return;
    }
    els.toast.textContent = message;
    els.toast.style.background = isError ? 'rgba(185,28,28,.94)' : 'rgba(17,24,39,.94)';
    els.toast.classList.add('show');
    clearTimeout(showToast._t);
    showToast._t = setTimeout(() => els.toast.classList.remove('show'), 2600);
  }

  function safeText(v) {
    return v == null ? '' : String(v);
  }

  function readJson(key) {
    const s = localStorage.getItem(key);
    if (!s) return null;
    try { return JSON.parse(s); } catch { return null; }
  }

  function writeJson(key, val) {
    localStorage.setItem(key, JSON.stringify(val));
  }

  function escapeHtml(str) {
    return safeText(str)
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
  }

  function translateUiText(text) {
    const replacements = [
      ['璐︽埛鍏ュ彛', 'Account'],
      ['鍘嗗彶璁″垝', 'History Plans'],
      ['鏀惰棌璁″垝', 'Favorite Plans'],
      ['鏈懡鍚嶈鍒?', 'Untitled Plan'],
      ['鏌ョ湅璁″垝', 'View Plan'],
      ['閲嶅懡鍚?', 'Rename'],
      ['鏀惰棌璇ヨ鍒?', 'Favorite This Plan'],
      ['鍙栨秷鏀惰棌', 'Remove Favorite'],
      ['鍩庡競:', 'City:'],
      ['澶╂暟:', 'Days:'],
      ['鏄惁鏀惰棌:', 'Favorite:'],
      ['YesNo鏀惰棌:', 'Favorite:'],
      ['鍒涘缓鏃堕棿:', 'Created At:'],
      ['鏄?', 'Yes'],
      ['鍚?', 'No'],
      ['浣忓锛?', 'Hotel: '],
      ['浣忓:', 'Hotel: '],
      ['寤鸿鍋滅暀', 'Recommended stay '],
      ['鍒嗛挓', ' minutes'],
      ['鏆傛棤琛岀▼鐐?', 'No stops yet'],
      ['鍙互淇濆瓨鍜岀鐞嗘梾琛岃鍒?', 'You can save and manage travel plans']
    ];

    let out = safeText(text);
    replacements.forEach(([from, to]) => {
      out = out.split(from).join(to);
    });
    return out;
  }

  function normalizePreviewText(text) {
    return translateUiText(safeText(text))
        .replace(/Pace:\\s*rush/gi, 'Pace: Fast-paced')
        .replace(/Pace:\\s*normal/gi, 'Pace: Moderate')
        .replace(/Pace:\\s*relaxed/gi, 'Pace: Relaxed');
  }

  function looksLikeJsonPayload(text) {
    const raw = safeText(text).trim();
    if (!raw || (!raw.startsWith('{') && !raw.startsWith('['))) return false;
    try {
      JSON.parse(raw);
      return true;
    } catch {
      return /"daysPlan"\s*:|"stops"\s*:|"copyPolishStatus"\s*:/.test(raw);
    }
  }

  function sanitizePreviewForDisplay(preview) {
    const raw = safeText(preview).trim();
    if (looksLikeJsonPayload(raw)) return '';
    if (looksLikeLegacyRenderedPreview(raw)) return '';
    return raw;
  }

  function looksLikeLegacyRenderedPreview(text) {
    const raw = safeText(text);
    return /Itinerary Overview|Traveling City|Number of travel days|Tour stop|general budget|旅行城市|旅行天数|行程概览|行程节奏|琛岀▼|鏃呰/.test(raw);
  }

  function localizeDom(root = document.body) {
    if (!root) return;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(node => {
      const next = translateUiText(node.nodeValue);
      if (next !== node.nodeValue) node.nodeValue = next;
    });
  }

  function parseJwt(token) {
    try {
      const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
      const json = decodeURIComponent(
          atob(base64)
              .split('')
              .map(c => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
              .join('')
      );
      return JSON.parse(json);
    } catch {
      return null;
    }
  }

  function nowSec() {
    return Math.floor(Date.now() / 1000);
  }

  function getToken() {
    return localStorage.getItem(STORAGE_KEY) || sessionStorage.getItem(STORAGE_KEY) || null;
  }

  function setToken(token, remember) {
    localStorage.removeItem(STORAGE_KEY);
    sessionStorage.removeItem(STORAGE_KEY);
    sessionStorage.setItem(STORAGE_KEY, token);
  }

  function showGenerationDebug(message) {
    if (!els.previewBox) return;
    const text = String(message || '').trim();
    if (!text) return;
    els.previewBox.innerHTML = `
      <div>
        <div style="font-weight:700;margin-bottom:8px;color:#991b1b;">Generation Debug</div>
        <pre style="white-space:pre-wrap;margin:0;font:inherit;color:#7f1d1d;">${escapeHtml(text)}</pre>
      </div>
    `;
    els.previewBox.style.display = 'block';
  }

  function setGenerateProgress(percent, label, hint) {
    const bounded = Math.max(0, Math.min(100, Math.round(percent)));
    if (els.generateProgress) {
      els.generateProgress.style.display = 'block';
    }
    if (els.generateProgressFill) {
      els.generateProgressFill.style.width = `${bounded}%`;
    }
    if (els.generateProgressPercent) {
      els.generateProgressPercent.textContent = `${bounded}%`;
    }
    if (els.generateProgressLabel && label) {
      els.generateProgressLabel.textContent = label;
    }
    if (els.generateProgressHint && hint) {
      els.generateProgressHint.textContent = hint;
    }
  }

  function hideGenerateProgress() {
    clearInterval(generateProgressTimer);
    generateProgressTimer = null;
    if (els.generateProgress) {
      els.generateProgress.style.display = 'none';
    }
    if (els.generateProgressFill) {
      els.generateProgressFill.style.width = '0%';
    }
    if (els.generateProgressPercent) {
      els.generateProgressPercent.textContent = '0%';
    }
  }

  function beginGenerateProgress() {
    clearInterval(generateProgressTimer);
    const checkpoints = [
      { percent: 10, label: 'Preparing request...', hint: 'Collecting dates, budget, style, and traveler details.' },
      { percent: 28, label: 'Calling itinerary model...', hint: 'Generating hotels, attractions, meals, and time slots.' },
      { percent: 52, label: 'Verifying constraints...', hint: 'Checking lunch and dinner coverage, stop order, and timing gaps.' },
      { percent: 74, label: 'Checking venue quality...', hint: 'Validating food stops and normalizing place details.' },
      { percent: 88, label: 'Finalizing itinerary...', hint: 'Preparing the preview and saved plan payload for display.' }
    ];
    let index = 0;
    setGenerateProgress(checkpoints[0].percent, checkpoints[0].label, checkpoints[0].hint);
    generateProgressTimer = setInterval(() => {
      if (index >= checkpoints.length - 1) {
        clearInterval(generateProgressTimer);
        generateProgressTimer = null;
        return;
      }
      index += 1;
      const next = checkpoints[index];
      setGenerateProgress(next.percent, next.label, next.hint);
    }, 1400);
  }

  function finishGenerateProgress(success) {
    clearInterval(generateProgressTimer);
    generateProgressTimer = null;
    setGenerateProgress(
        100,
        success ? 'Plan ready.' : 'Generation stopped.',
        success
            ? 'The itinerary has been generated and rendered below.'
            : 'The draft did not pass validation. Review the details below and try again.'
    );
    setTimeout(() => hideGenerateProgress(), success ? 900 : 1800);
  }

  function clearToken() {
    localStorage.removeItem(STORAGE_KEY);
    sessionStorage.removeItem(STORAGE_KEY);
  }

  function isLoggedIn() {
    const token = getToken();
    if (!token) return false;
    const payload = parseJwt(token);
    if (payload?.exp && payload.exp < nowSec()) {
      clearToken();
      return false;
    }
    return true;
  }

  async function refreshAccessToken() {
    try {
      const resp = await fetch(API.refresh, {
        method: 'POST',
        credentials: 'include'
      });
      const data = await safeJson(resp);
      if (!resp.ok || !data?.token) {
        clearToken();
        return null;
      }
      setToken(data.token, false);
      return data;
    } catch {
      clearToken();
      return null;
    }
  }

  async function authFetch(url, options = {}) {
    const token = getToken();
    const headers = new Headers(options.headers || {});
    if (token) headers.set('Authorization', 'Bearer ' + token);
    if (!headers.has('Content-Type') && options.method && options.method !== 'GET') {
      headers.set('Content-Type', 'application/json');
    }

    let resp = await fetch(url, { ...options, headers, credentials: 'include' });

    if (resp.status === 401) {
      const refreshed = await refreshAccessToken();
      if (refreshed?.token) {
        const retryHeaders = new Headers(options.headers || {});
        retryHeaders.set('Authorization', `Bearer ${refreshed.token}`);
        if (!retryHeaders.has('Content-Type') && options.method && options.method !== 'GET') {
          retryHeaders.set('Content-Type', 'application/json');
        }
        resp = await fetch(url, { ...options, headers: retryHeaders, credentials: 'include' });
      }
    }

    if (resp.status === 401) {
      clearToken();
      renderLoggedOut();
      syncFavoriteButtonState();
      showToast('Login expired. Please sign in again.', true);
    }
    return resp;
  }

  async function safeJson(resp) {
    try { return await resp.json(); } catch { return null; }
  }

  function getUserFromToken() {
    const token = getToken();
    if (!token) return null;
    const payload = parseJwt(token) || {};
    return {
      username: payload.username || payload.name || payload.sub || 'User',
      email: payload.email || '',
      phone: payload.phone || ''
    };
  }

  function setVisible(el, visible) {
    if (!el) return;

    if (!visible) {
      el.style.display = 'none';
      return;
    }

    if (el.id === 'accountActionsWrap') {
      el.style.display = 'grid';
      return;
    }

    if (el.id === 'resultContent') {
      el.style.display = 'block';
      return;
    }

    if (el.id === 'previewBox') {
      el.style.display = 'block';
      return;
    }

    el.style.display = 'block';
  }

  function setText(el, text) {
    if (el) el.textContent = safeText(text);
  }

  function setButtonLoading(button, loading, loadingText) {
    if (!button) return;
    if (loading) {
      button.dataset.originalText = button.textContent || '';
      button.textContent = loadingText;
      button.disabled = true;
      return;
    }
    button.disabled = false;
    if (button.dataset.originalText) {
      button.textContent = button.dataset.originalText;
      delete button.dataset.originalText;
    }
  }

  async function loadChallenge(kind, visible = true) {
    const box = kind === 'login' ? els.loginChallengeBox : els.registerChallengeBox;
    const question = kind === 'login' ? els.loginChallengeQuestion : els.registerChallengeQuestion;
    const challengeId = kind === 'login' ? els.loginChallengeId : els.registerChallengeId;
    const answer = kind === 'login' ? els.loginChallengeAnswer : els.registerChallengeAnswer;
    try {
      const resp = await fetch(API.challenge, { method: 'GET' });
      const data = await safeJson(resp);
      if (!resp.ok || !data?.challengeId || !data?.question) {
        throw new Error(data?.message || 'Challenge failed to load');
      }
      if (box) box.style.display = visible ? '' : 'none';
      if (question) question.textContent = data.question;
      if (challengeId) challengeId.value = data.challengeId;
      if (answer) answer.value = '';
    } catch (e) {
      console.error(e);
      showToast(e.message || 'Challenge failed to load', true);
    }
  }

  function isChallengeVisible(kind) {
    const box = kind === 'login' ? els.loginChallengeBox : els.registerChallengeBox;
    return !!box && box.style.display !== 'none';
  }

  function resetInputsOnly() {
    if (els.city) els.city.value = '';
    if (els.days) els.days.value = '';
    if (els.budget) els.budget.value = '';
    if (els.adults) els.adults.value = '2';
    if (els.kids) els.kids.value = '0';
    if (els.style) els.style.value = '';
    if (els.styleChecks.length) {
      els.styleChecks.forEach(input => {
        input.checked = false;
      });
    }
    if (els.pace) els.pace.value = 'normal';
    if (els.mainModel) els.mainModel.value = 'qwen-max';
    if (els.selfDrive) els.selfDrive.checked = false;
    localStorage.removeItem(PLAN_DRIVE_KEY);
  }

  function clearRenderedPlanOnly() {
    routeSuggestionRunId++;
    currentPlanId = null;
    currentDraft = null;
    currentPreview = '';
    currentFavorite = false;

    localStorage.removeItem(PLAN_ID_KEY);
    localStorage.removeItem(PLAN_CACHE_KEY);
    localStorage.removeItem(PLAN_PREVIEW_KEY);
    localStorage.removeItem(PLAN_FAVORITE_KEY);

    if (els.resultTitle) els.resultTitle.innerHTML = '';
    if (els.resultMeta) els.resultMeta.innerHTML = '';
    if (els.resultDays) els.resultDays.innerHTML = '';
    if (els.previewBox) els.previewBox.innerHTML = '';

    setVisible(els.resultEmpty, true);
    setVisible(els.resultContent, false);
    syncFavoriteButtonState();
  }

  function clearPlanUI() {
    resetInputsOnly();
    clearRenderedPlanOnly();
    showToast('Inputs and generated results have been cleared');
  }

  async function copyCurrentPlanJson() {
    if (!currentDraft) {
      showToast('There is no generated plan to copy yet', true);
      return;
    }

    const copyDraft = {
      ...currentDraft,
      mainModel: currentDraft.mainModel || currentDraft._mainModel || null
    };
    delete copyDraft._mainModel;
    const text = JSON.stringify(copyDraft, null, 2);
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.setAttribute('readonly', 'readonly');
        textarea.style.position = 'fixed';
        textarea.style.left = '-9999px';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        textarea.remove();
      }
      showToast('Plan JSON copied to clipboard');
    } catch (e) {
      console.error(e);
      showToast('Failed to copy JSON', true);
    }
  }

  function syncFavoriteButtonState() {
    if (!els.btnFavoritePlan) return;

    if (!isLoggedIn()) {
      els.btnFavoritePlan.disabled = true;
      els.btnFavoritePlan.textContent = 'Favorite This Plan (Please Log In First)';
      return;
    }

    if (!currentPlanId) {
      els.btnFavoritePlan.disabled = true;
      els.btnFavoritePlan.textContent = 'Favorite This Plan';
      return;
    }

    els.btnFavoritePlan.disabled = false;
    els.btnFavoritePlan.textContent = currentFavorite ? 'Remove from Favorites' : 'Favorite This Plan';
  }

  function renderLoggedIn(user) {
    setVisible(els.accountGuestWrap, false);
    setVisible(els.authFormsWrap, false);

    setVisible(els.accountUserWrap, true);
    setVisible(els.accountActionsWrap, true);

    if (els.authEntryTitle) setText(els.authEntryTitle, 'Account');

    setText(els.userName, user?.username || 'Logged-in User');
    setText(els.userHint, 'You can save and manage travel plans');

    syncFavoriteButtonState();
  }

  function renderLoggedOut() {
    setVisible(els.accountGuestWrap, true);
    setVisible(els.authFormsWrap, true);

    setVisible(els.accountUserWrap, false);
    setVisible(els.accountActionsWrap, false);

    if (els.authEntryTitle) setText(els.authEntryTitle, 'Account');

    syncFavoriteButtonState();
    if (els.accountListBox) els.accountListBox.innerHTML = '';
  }

  async function bootAuthState() {
    if (isLoggedIn()) {
      const user = getUserFromToken();
      renderLoggedIn(user);
      return;
    }

    const refreshed = await refreshAccessToken();
    if (refreshed?.token) {
      const user = refreshed.user || getUserFromToken();
      renderLoggedIn(user);
      return;
    }

    renderLoggedOut();
  }

  function getStyleValues() {
    if (els.styleChecks.length) {
      return els.styleChecks
          .filter(input => input.checked)
          .map(input => input.value)
          .filter(Boolean);
    }

    if (!els.style) return [];

    if (els.style.tagName === 'SELECT' && els.style.multiple) {
      return Array.from(els.style.selectedOptions).map(o => o.value).filter(Boolean);
    }

    const raw = els.style.value || '';
    return raw
        .split(/[锛?銆乗s]+/)
        .map(s => s.trim())
        .filter(Boolean);
  }

  function buildGeneratePayload() {
    const city = (els.city?.value || '').trim();
    const days = Number(els.days?.value || 0);
    const budget = Number(els.budget?.value || 0);
    const adults = Number(els.adults?.value || 0);
    const kids = Number(els.kids?.value || 0);
    const departureDate = (els.departureDate?.value || '').trim();
    const style = getStyleValues();
    const rawPace = (els.pace?.value || 'normal').trim() || 'normal';
    const pace = rawPace === 'fast' ? 'rush' : rawPace;
    const mainModel = (els.mainModel?.value || 'qwen-max').trim() || 'qwen-max';

    if (!city) throw new Error('Please enter a city');
    if (!days || days < 1) throw new Error('Please enter a valid number of days');
    if (!budget || budget < 1) throw new Error('Please enter a valid budget');
    if (adults < 0 || kids < 0 || adults + kids < 1) throw new Error('At least 1 traveler is required');
    if (!/^\d{4}-\d{2}-\d{2}$/.test(departureDate)) throw new Error('Please select a valid departure date');

    return {
      city,
      days,
      budget,
      party: { adults, kids },
      style,
      pace,
      mainModel,
      departureDate
    };
  }

  function formatMainModelLabel(model) {
    const normalized = safeText(model).trim().toLowerCase();
    switch (normalized) {
      case 'qwen-max':
        return 'Qwen Max';
      case 'qwen-plus':
        return 'Qwen Plus';
      case 'gemini-2.5-flash':
        return 'Gemini 2.5 Flash';
      default:
        return safeText(model).trim();
    }
  }

  function stripFrontendDraftMetadata(draft) {
    if (!draft || typeof draft !== 'object') return draft;
    const {
      mainModel,
      _mainModel,
      _generationTimeMs,
      _requestedPace,
      ...backendDraft
    } = draft;
    return backendDraft;
  }

  function renderDraft(draft, preview, weatherPromise = null) {
    if (!draft) {
      clearRenderedPlanOnly();
      return;
    }
    const dayCount = Array.isArray(draft.daysPlan) ? draft.daysPlan.length : 0;
    if (!currentDayPage || currentDayPage > dayCount) {
      currentDayPage = 1;
    }
    latestRouteSuggestions = null;
    latestWeatherForecast = null;

    setVisible(els.resultEmpty, false);
    setVisible(els.resultContent, true);

    const title = (draft.title || `${draft.city || ''} ${draft.days || ''}-Day Plan`).trim();
    if (els.resultTitle) {
      els.resultTitle.textContent = title || 'Travel Plan';
    }

    if (els.resultMeta) {
      const adults = draft.party?.adults ?? 0;
      const kids = draft.party?.kids ?? 0;
      const budgetText = els.budget?.value ? `Budget ${draft.currency || ''} ${els.budget.value}` : '';
      const paceText = draft.pace ? `Pace ${draft.pace}` : '';
      const requestedPace = draft._requestedPace || lastRequestedPace;
      const fallbackText = requestedPace && requestedPace !== draft.pace
          ? 'Adjusted to relaxed pace'
          : '';
      const mainModel = draft.mainModel || draft._mainModel || '';
      const modelText = mainModel ? `Main model ${formatMainModelLabel(mainModel)}` : '';
      const generationTimeText = draft._generationTimeMs
          ? `Generated in ${formatDurationMs(draft._generationTimeMs)}`
          : '';

      const tags = [budgetText, `${adults} Adults`, `${kids} Children`, paceText, fallbackText, modelText, generationTimeText]
          .filter(Boolean)
          .map(t => `<span class="chip">${escapeHtml(t)}</span>`)
          .join('');

      els.resultMeta.innerHTML = tags;
    }

    const budgetOverviewEl = document.getElementById('budgetOverview');
    if (budgetOverviewEl) {
      const budgetNum = Number(els.budget?.value || 0);
      const daysNum = Number(draft.days || 0);
      const adults = Number(draft.party?.adults ?? 0);
      const kids = Number(draft.party?.kids ?? 0);
      const travelers = adults + kids;
      if (budgetNum > 0 && daysNum > 0) {
        const totalBudget = budgetNum * Math.max(adults, 1);
        const dailyBudget = Math.round(totalBudget / daysNum);
        const hotelBudget = Math.round(totalBudget * 0.35);
        const foodBudget = Math.round(totalBudget * 0.25);
        const transportBudget = Math.round(totalBudget * 0.15);
        const activitiesBudget = Math.max(0, totalBudget - hotelBudget - foodBudget - transportBudget);
        const currency = draft.currency || '';
        budgetOverviewEl.style.display = '';
        budgetOverviewEl.innerHTML = `
          <div class="budget-overview-head">
            <div>
              <h4>Budget Overview</h4>
              <div class="muted">A quick planning split based on your current budget input and trip length.</div>
            </div>
            <div class="budget-total">
              <span class="muted">Estimated total</span>
              <strong>${escapeHtml(currency)} ${escapeHtml(totalBudget)}</strong>
              <span class="muted">${escapeHtml(daysNum)} days${travelers > 0 ? ` · ${escapeHtml(travelers)} travelers` : ''}</span>
            </div>
          </div>
          <div class="budget-grid">
            <div class="budget-card">
              <div class="budget-card-label">Per Day</div>
              <div class="budget-card-value">${escapeHtml(currency)} ${escapeHtml(dailyBudget)}</div>
              <div class="budget-card-note">Useful for daily pacing</div>
            </div>
            <div class="budget-card">
              <div class="budget-card-label">Hotel</div>
              <div class="budget-card-value">${escapeHtml(currency)} ${escapeHtml(hotelBudget)}</div>
              <div class="budget-card-note">Estimated 35%% of total budget</div>
            </div>
            <div class="budget-card">
              <div class="budget-card-label">Food</div>
              <div class="budget-card-value">${escapeHtml(currency)} ${escapeHtml(foodBudget)}</div>
              <div class="budget-card-note">Estimated 25%% for meals and cafes</div>
            </div>
            <div class="budget-card">
              <div class="budget-card-label">Activities</div>
              <div class="budget-card-value">${escapeHtml(currency)} ${escapeHtml(activitiesBudget)}</div>
              <div class="budget-card-note">Museums, tickets, shopping buffer</div>
            </div>
          </div>
        `;
      } else {
        budgetOverviewEl.style.display = 'none';
        budgetOverviewEl.innerHTML = '';
      }
    }

    if (els.previewBox) {
      const displayPreview = sanitizePreviewForDisplay(preview);
      if (displayPreview) {
        els.previewBox.innerHTML = `<pre style="white-space:pre-wrap;margin:0;font:inherit;">${escapeHtml(normalizePreviewText(displayPreview))}</pre>`;
      } else if (draft.title || draft.overview) {
        els.previewBox.innerHTML = `
          <div>
            ${draft.title ? `<div style="font-weight:700;margin-bottom:8px;">${escapeHtml(draft.title)}</div>` : ''}
            ${draft.overview ? `<div class="muted">${escapeHtml(draft.overview)}</div>` : ''}
          </div>
        `;
      } else {
        els.previewBox.innerHTML = '';
      }
    }

    if (els.resultDays) {
      const daysPlan = Array.isArray(draft.daysPlan) ? draft.daysPlan : [];
      const selectedDay = daysPlan.find(day => Number(day.dayIndex || 0) === Number(currentDayPage)) || daysPlan[0];
      currentDayPage = Number(selectedDay?.dayIndex || 1);
      const dayNav = renderDayPager(daysPlan);
      els.resultDays.innerHTML = dayNav + (selectedDay ? [selectedDay].map(day => {
        const hotel = day.hotel
            ? `
            <div class="plan-hotel">
              <div><strong>Hotel:</strong> ${escapeHtml(day.hotel.name || '-')}</div>
              <div class="muted">${escapeHtml(day.hotel.addressLine || '')}</div>
              ${day.hotel.reason ? `<div class="muted">${escapeHtml(day.hotel.reason)}</div>` : ''}
              ${day.hotel.tip ? `<div class="muted">Tip: ${escapeHtml(day.hotel.tip)}</div>` : ''}
            </div>
            ${Array.isArray(day.stops) && day.stops.length ? `
              <div class="route-suggestion is-loading" data-route-day="${escapeHtml(day.dayIndex ?? '')}" data-route-index="-1">
                <div class="route-line"></div>
                <div class="route-card">
                  <span class="route-pill">Route</span>
                  <strong>Calculating hotel transfer...</strong>
                  <span class="muted">Checking the first transfer from your hotel.</span>
                </div>
              </div>
            ` : ''}
          `
            : '';

        const stops = Array.isArray(day.stops) ? day.stops : [];
        const stopsHtml = stops.map((stop, idx) => `
          <div class="plan-stop">
            <div class="plan-stop-head">
              <div>
                <strong>${idx + 1}. ${escapeHtml(stop.name || '-')}</strong>
                ${stop.timeSlot ? `<div class="plan-stop-time">${escapeHtml(stop.timeSlot)}</div>` : ''}
              </div>
              ${stop.category ? `<span class="stop-tag">${escapeHtml(stop.category)}</span>` : ''}
            </div>
            <div class="muted">${escapeHtml(stop.addressLine || '')}</div>
            <div class="muted">${escapeHtml(stop.startTime || '--:--')} - ${escapeHtml(stop.endTime || '--:--')} (${escapeHtml(stop.stayMinutes ?? '-')} min)</div>
            ${['restaurant', 'food', 'cafe', 'dining'].includes(String(stop.category || '').toLowerCase())
                ? `<div class="muted">Intent: ${escapeHtml([
                    stop.mealType ? `meal ${stop.mealType}` : '',
                    stop.preferredArea ? `area ${stop.preferredArea}` : '',
                    stop.cuisine ? `cuisine ${stop.cuisine}` : '',
                    stop.vibe ? `vibe ${stop.vibe}` : '',
                    stop.budgetLevel ? `budget ${stop.budgetLevel}` : ''
                  ].filter(Boolean).join(' · ') || 'n/a')}</div>`
                : ''}
            ${stop.reason ? `<div class="muted">${escapeHtml(stop.reason)}</div>` : ''}
            ${stop.tip ? `<div class="muted">Tip: ${escapeHtml(stop.tip)}</div>` : ''}
          </div>
          ${idx < stops.length - 1 ? `
            <div class="route-suggestion is-loading" data-route-day="${escapeHtml(day.dayIndex ?? '')}" data-route-index="${escapeHtml(idx)}">
              <div class="route-line"></div>
              <div class="route-card">
                <span class="route-pill">Route</span>
                <strong>Calculating best transfer...</strong>
                <span class="muted">Checking walk, transit, and car summaries in the background.</span>
              </div>
            </div>
          ` : ''}
        `).join('');

        return `
          <section class="day-card">
            ${renderSideDayArrow(daysPlan, -1)}
            ${renderSideDayArrow(daysPlan, 1)}
            <div class="day-card-head">
              <div>
                <h3>Day ${escapeHtml(day.dayIndex ?? '')}</h3>
                <div class="day-theme">${escapeHtml(day.theme || day.note || '')}</div>
              </div>
              <div class="weather-card weather-card-inline is-loading" data-weather-day="${escapeHtml(day.dayIndex ?? '')}">
                <div class="weather-icon">···</div>
                <div>
                  <strong>Weather</strong>
                  <span>Checking forecast...</span>
                </div>
              </div>
            </div>
            <div class="day-rhythm">
              ${day.morningNote ? `<div class="day-rhythm-item"><span class="day-rhythm-label">Morning</span><div class="muted">${escapeHtml(day.morningNote)}</div></div>` : ''}
              ${day.afternoonNote ? `<div class="day-rhythm-item"><span class="day-rhythm-label">Afternoon</span><div class="muted">${escapeHtml(day.afternoonNote)}</div></div>` : ''}
              ${day.eveningNote ? `<div class="day-rhythm-item"><span class="day-rhythm-label">Evening</span><div class="muted">${escapeHtml(day.eveningNote)}</div></div>` : ''}
              ${day.note && day.theme ? `<div class="day-rhythm-item"><span class="day-rhythm-label">Summary</span><div class="muted">${escapeHtml(day.note)}</div></div>` : ''}
            </div>
            ${hotel}
            <div class="plan-stops">${stopsHtml || '<div class="muted">No stops yet</div>'}</div>
          </section>
        `;
      }).join('') : '<div class="muted">No days yet</div>');
    }

    syncFavoriteButtonState();
    localizeDom(els.resultContent || document.body);
    showWeatherLoading();
    requestRouteSuggestions(draft);
    (weatherPromise || requestWeatherForecast(buildWeatherPayloadFromDraft(draft))).then(renderWeatherForecast);
    syncDaySideArrowPosition();
  }

  function renderDayPager(daysPlan) {
    if (!Array.isArray(daysPlan) || daysPlan.length <= 1) {
      return '';
    }
    const currentIndex = Math.max(0, daysPlan.findIndex(day => Number(day.dayIndex || 0) === Number(currentDayPage)));
    const prevDay = daysPlan[Math.max(0, currentIndex - 1)]?.dayIndex;
    const nextDay = daysPlan[Math.min(daysPlan.length - 1, currentIndex + 1)]?.dayIndex;
    const tabs = daysPlan.map(day => {
      const dayIndex = Number(day.dayIndex || 0);
      return `
        <button class="day-pager-tab${dayIndex === Number(currentDayPage) ? ' is-active' : ''}" type="button" data-action="select-day" data-day-index="${escapeHtml(dayIndex)}">
          Day ${escapeHtml(dayIndex)}
        </button>
      `;
    }).join('');
    return `
      <div class="day-pager">
        <button class="day-pager-arrow" type="button" data-action="select-day" data-day-index="${escapeHtml(prevDay ?? currentDayPage)}" ${currentIndex === 0 ? 'disabled' : ''}>Prev</button>
        <div class="day-pager-tabs">${tabs}</div>
        <button class="day-pager-arrow" type="button" data-action="select-day" data-day-index="${escapeHtml(nextDay ?? currentDayPage)}" ${currentIndex === daysPlan.length - 1 ? 'disabled' : ''}>Next</button>
      </div>
    `;
  }

  function selectDayPage(dayIndex) {
    const nextDay = Number(dayIndex || 0);
    if (!currentDraft || !Number.isFinite(nextDay) || nextDay < 1) return;
    currentDayPage = nextDay;
    renderDraft(currentDraft, currentPreview, Promise.resolve(latestWeatherForecast));
    if (latestRouteSuggestions) {
      renderRouteSuggestions(latestRouteSuggestions);
    }
    syncDaySideArrowPosition();
  }

  function syncDaySideArrowPosition() {
    if (!els.resultContent || getComputedStyle(els.resultContent).display === 'none') return;
    const rect = els.resultContent.getBoundingClientRect();
    const arrowWidth = window.innerWidth <= 720 ? 34 : 42;
    const arrowHeight = window.innerWidth <= 720 ? 56 : 72;
    const inset = 10;
    if (rect.bottom <= 0 || rect.top >= window.innerHeight || rect.width <= arrowWidth + inset * 2) {
      document.documentElement.style.setProperty('--day-arrow-display', 'none');
      return;
    }
    const minLeft = Math.max(8, rect.left + inset);
    const maxLeft = Math.min(window.innerWidth - arrowWidth - 8, rect.right - arrowWidth - inset);
    const left = Math.min(Math.max(minLeft, rect.left + inset), maxLeft);
    const right = Math.min(Math.max(minLeft, rect.right - arrowWidth - inset), maxLeft);
    const visibleTop = Math.max(rect.top, 0);
    const visibleBottom = Math.min(rect.bottom, window.innerHeight);
    const top = Math.min(
        Math.max((visibleTop + visibleBottom) / 2, arrowHeight / 2 + 8),
        window.innerHeight - arrowHeight / 2 - 8
    );
    document.documentElement.style.setProperty('--day-arrow-display', 'inline-flex');
    document.documentElement.style.setProperty('--day-arrow-top', `${Math.round(top)}px`);
    document.documentElement.style.setProperty('--day-arrow-left', `${Math.round(left)}px`);
    document.documentElement.style.setProperty('--day-arrow-right', `${Math.round(right)}px`);
  }

  function renderSideDayArrow(daysPlan, direction) {
    if (!Array.isArray(daysPlan) || daysPlan.length <= 1) {
      return '';
    }
    const currentIndex = Math.max(0, daysPlan.findIndex(day => Number(day.dayIndex || 0) === Number(currentDayPage)));
    const target = daysPlan[currentIndex + direction];
    const disabled = !target;
    return `
      <button
        class="day-side-arrow ${direction < 0 ? 'day-side-arrow-left' : 'day-side-arrow-right'}"
        type="button"
        data-action="select-day"
        data-day-index="${escapeHtml(target?.dayIndex ?? currentDayPage)}"
        ${disabled ? 'disabled' : ''}
        aria-label="${direction < 0 ? 'Previous day' : 'Next day'}">
        ${direction < 0 ? '&lt;' : '&gt;'}
      </button>
    `;
  }

  async function requestRouteSuggestions(draft) {
    if (!draft || !Array.isArray(draft.daysPlan) || !els.resultDays) return;
    const runId = ++routeSuggestionRunId;
    try {
      const resp = await fetch(API.routeSuggestions, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          draft: stripFrontendDraftMetadata(draft),
          budget: Number(els.budget?.value || draft.budget || 0) || null,
          departureDate: (els.departureDate?.value || draft.departureDate || '').trim() || null
        })
      });
      const data = await safeJson(resp);
      if (runId !== routeSuggestionRunId) return;
      if (!resp.ok) throw new Error(data?.message || `Route suggestions failed (${resp.status})`);
      latestRouteSuggestions = data;
      renderRouteSuggestions(data);
    } catch (e) {
      if (runId !== routeSuggestionRunId) return;
      console.warn(e);
      markRouteSuggestionsUnavailable();
    }
  }

  async function requestWeatherForecast(payload) {
    if (!payload?.city || !payload?.departureDate || !payload?.days) return null;
    try {
      const resp = await fetch(API.weather, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          city: payload.city,
          departureDate: payload.departureDate,
          days: payload.days
        })
      });
      const data = await safeJson(resp);
      if (!resp.ok) throw new Error(data?.message || `Weather forecast failed (${resp.status})`);
      return data;
    } catch (e) {
      console.warn(e);
      return null;
    }
  }

  function buildWeatherPayloadFromDraft(draft) {
    return {
      city: draft?.city || els.city?.value || '',
      departureDate: (draft?.departureDate || els.departureDate?.value || '').trim(),
      days: Number(draft?.days || els.days?.value || 0)
    };
  }

  function showWeatherLoading() {
    document.querySelectorAll('.weather-card[data-weather-day]').forEach(slot => {
      slot.className = 'weather-card weather-card-inline is-loading';
      slot.innerHTML = `
        <div class="weather-icon">···</div>
        <div>
          <strong>Weather</strong>
          <span>Checking forecast...</span>
        </div>
      `;
    });
  }

  function renderWeatherForecast(data) {
    latestWeatherForecast = data;
    const days = Array.isArray(data?.days) ? data.days : [];
    if (!data?.available || !days.length) {
      document.querySelectorAll('.weather-card[data-weather-day]').forEach(slot => {
        slot.className = 'weather-card weather-card-inline is-muted';
        slot.innerHTML = `
          <div class="weather-icon">--</div>
          <div>
            <strong>Weather unavailable</strong>
            <span>Normal route rules still apply.</span>
          </div>
        `;
      });
      return;
    }

    for (const day of days) {
      const slot = document.querySelector(`[data-weather-day="${cssEscape(String(day.dayIndex ?? ''))}"]`);
      if (!slot) continue;
      const rainy = !!day.rainy;
      const tempText = formatTemperatureRange(day);
      slot.className = `weather-card weather-card-inline${rainy ? ' is-rainy' : ''}`;
      slot.innerHTML = `
        <div class="weather-icon">${rainy ? 'Rain' : 'Sky'}</div>
        <div>
          <strong>${escapeHtml(day.condition || (rainy ? 'Rain possible' : 'Weather forecast'))}</strong>
          <span>${escapeHtml(day.date || '')}${tempText ? ` · ${escapeHtml(tempText)}` : ''} · Rain ${escapeHtml(day.chanceOfRain ?? 0)}%</span>
        </div>
      `;
    }
  }

  function formatTemperatureRange(day) {
    const max = Number(day?.maxTempC);
    const min = Number(day?.minTempC);
    const avg = Number(day?.avgTempC);
    if (Number.isFinite(min) && Number.isFinite(max)) return `${Math.round(min)}-${Math.round(max)}°C`;
    if (Number.isFinite(avg)) return `${Math.round(avg)}°C`;
    return '';
  }

  function renderRouteSuggestions(data) {
    const days = Array.isArray(data?.days) ? data.days : [];
    for (const day of days) {
      const segments = Array.isArray(day.segments) ? day.segments : [];
      for (const segment of segments) {
        const slot = document.querySelector(`[data-route-day="${cssEscape(String(day.dayIndex ?? ''))}"][data-route-index="${cssEscape(String(segment.segmentIndex ?? ''))}"]`);
        if (!slot) continue;
        if (segment.hidden) {
          slot.remove();
          continue;
        }
        slot.classList.remove('is-loading');
        slot.innerHTML = routeSuggestionHtml(segment);
      }
    }
    localizeDom(els.resultContent || document.body);
  }

  function routeSuggestionHtml(segment) {
    if (!segment?.recommendedMode) {
      return `
        <div class="route-line"></div>
        <div class="route-card route-card-muted">
          <span class="route-pill">Route</span>
          <strong>Route suggestion unavailable</strong>
          <span class="muted">${escapeHtml(segment?.hint || 'Try the map view for manual route checking.')}</span>
        </div>
      `;
    }
    const mode = String(segment.recommendedMode || '').toLowerCase();
    const alternatives = ['walk', 'transit', 'car']
        .map(key => segment[key] ? `<span>${escapeHtml(labelMode(key))} ${escapeHtml(formatMinutes(segment[key].durationMinutes))}</span>` : '')
        .filter(Boolean)
        .join('');
    return `
      <div class="route-line"></div>
      <div class="route-card route-mode-${escapeHtml(mode)}">
        <span class="route-pill">${escapeHtml(labelMode(mode))}</span>
        <strong>${escapeHtml(formatMinutes(segment.durationMinutes))}${segment.distanceMeters ? ` · ${escapeHtml(formatDistance(segment.distanceMeters))}` : ''}</strong>
        <span class="muted">${escapeHtml(segment.from || 'Previous stop')} → ${escapeHtml(segment.to || 'Next stop')}</span>
        ${alternatives ? `<div class="route-options">${alternatives}</div>` : ''}
        ${segment.hint ? `<span class="route-hint">${escapeHtml(segment.hint)}</span>` : ''}
      </div>
    `;
  }

  function markRouteSuggestionsUnavailable() {
    document.querySelectorAll('.route-suggestion.is-loading').forEach(slot => {
      slot.classList.remove('is-loading');
      slot.innerHTML = `
        <div class="route-line"></div>
        <div class="route-card route-card-muted">
          <span class="route-pill">Route</span>
          <strong>Route suggestions unavailable</strong>
          <span class="muted">${escapeHtml(slot.dataset.routeHint || 'The plan is ready; open the map view if you want to inspect routes manually.')}</span>
        </div>
      `;
    });
  }

  function labelMode(mode) {
    if (mode === 'walk') return 'Walk';
    if (mode === 'transit') return 'Transit';
    if (mode === 'car') return 'Car / Taxi';
    return mode || 'Route';
  }

  function formatMinutes(value) {
    const minutes = Number(value || 0);
    if (!Number.isFinite(minutes) || minutes <= 0) return '-- min';
    return `${Math.round(minutes)} min`;
  }

  function formatDistance(value) {
    const meters = Number(value || 0);
    if (!Number.isFinite(meters) || meters <= 0) return '';
    if (meters >= 1000) return `${(meters / 1000).toFixed(meters >= 10000 ? 0 : 1)} km`;
    return `${Math.round(meters)} m`;
  }

  function cssEscape(value) {
    return window.CSS?.escape ? CSS.escape(value) : value.replace(/["\\]/g, '\\$&');
  }

  function cacheLatestPlan(rawResp, selfDriveChecked) {
    currentPlanId = rawResp?.planId ?? null;
    currentDraft = rawResp?.draft ?? null;
    currentPreview = sanitizePreviewForDisplay(rawResp?.preview ?? '');
    currentFavorite = false;

    if (currentPlanId) localStorage.setItem(PLAN_ID_KEY, String(currentPlanId));
    else localStorage.removeItem(PLAN_ID_KEY);

    if (currentDraft) writeJson(PLAN_CACHE_KEY, currentDraft);
    else localStorage.removeItem(PLAN_CACHE_KEY);

    localStorage.setItem(PLAN_PREVIEW_KEY, currentPreview || '');
    localStorage.setItem(PLAN_FAVORITE_KEY, 'false');
    localStorage.setItem(PLAN_DRIVE_KEY, selfDriveChecked ? 'true' : 'false');
  }

  function restoreLatestPlan() {
    if (currentDraft) {
      renderDraft(currentDraft, currentPreview);
    } else {
      clearRenderedPlanOnly();
    }
  }

  function formatDurationMs(value) {
    const ms = Number(value || 0);
    if (!Number.isFinite(ms) || ms <= 0) return '';
    if (ms < 1000) return `${Math.round(ms)} ms`;
    const seconds = ms / 1000;
    if (seconds < 60) return `${seconds.toFixed(seconds < 10 ? 1 : 0)} s`;
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.round(seconds % 60);
    return `${minutes} min ${remainingSeconds} s`;
  }

  async function submitGeneratePlan() {
    let payload;
    try {
      payload = buildGeneratePayload();
    } catch (e) {
      showToast(e.message || 'Please complete the required fields', true);
      return;
    }

    clearRenderedPlanOnly();

    const selfDriveChecked = !!els.selfDrive?.checked;
    const loggedIn = isLoggedIn();
    const url = loggedIn ? API.raw : API.draft;

    if (els.btnGenerate) {
      els.btnGenerate.disabled = true;
      els.btnGenerate.textContent = 'Generating...';
    }
    beginGenerateProgress();
    const generationStartedAt = performance.now();
    const weatherPromise = requestWeatherForecast(payload);

    try {
      lastRequestedPace = payload.pace;
      const reqOptions = {
        method: 'POST',
        body: JSON.stringify(payload)
      };

      const resp = loggedIn
          ? await authFetch(url, reqOptions)
          : await fetch(url, {
            ...reqOptions,
            headers: { 'Content-Type': 'application/json' }
          });

      const data = await safeJson(resp);

      if (!resp.ok) {
        throw new Error(data?.message || `Generation failed (${resp.status})`);
      }

      // 宸茬櫥褰曪細/raw 杩斿洖 { planId, aiRawId, draft, preview }
      // 鏈櫥褰曪細/draft 鐩存帴杩斿洖 draft
      let normalized;
      if (loggedIn) {
        if (!data?.draft) {
          throw new Error('Generation succeeded, but no draft was returned');
        }
        normalized = {
          planId: data.planId ?? null,
          draft: data.draft,
          preview: data.preview || ''
        };
      } else {
        normalized = {
          planId: null,
          draft: data,
          preview: ''
        };
      }

      if (normalized.draft) {
        normalized.draft._generationTimeMs = Math.round(performance.now() - generationStartedAt);
        normalized.draft._requestedPace = payload.pace;
        normalized.draft.mainModel = payload.mainModel;
        normalized.draft._mainModel = payload.mainModel;
      }

      cacheLatestPlan(normalized, selfDriveChecked);
      renderDraft(currentDraft, currentPreview, weatherPromise);
      syncFavoriteButtonState();
      finishGenerateProgress(true);

      showToast(loggedIn ? 'Travel plan generated successfully and saved to the database' : 'Travel plan generated successfully (not logged in, will not be saved)');
    } catch (e) {
      console.error(e);
      finishGenerateProgress(false);
      showGenerationDebug(e.message || 'Failed to generate travel plan');
      showToast(e.message || 'Failed to generate travel plan', true);
    } finally {
      if (els.btnGenerate) {
        els.btnGenerate.disabled = false;
        els.btnGenerate.textContent = 'Generate Travel Plan';
      }
    }
  }

  async function toggleFavoriteCurrentPlan() {
    if (!isLoggedIn()) {
      showToast('Please log in before favoriting a plan', true);
      return;
    }
    if (!currentPlanId) {
      showToast('There is no plan available to favorite', true);
      return;
    }

    if (!currentFavorite) {
        openRenameModal('Name this plan before favoriting', '', {
        type: 'favorite-current-plan',
        planId: currentPlanId
      });
      return;
    }

    await doFavoritePlan(currentPlanId, false, true);
  }

  async function doFavoritePlan(planId, targetValue, refreshList = false) {
    try {
      const resp = await authFetch(API.favorite(planId, targetValue), {
        method: 'PATCH'
      });
      const data = await safeJson(resp);

      if (!resp.ok) {
        throw new Error(data?.message || `Operation failed (${resp.status})`);
      }

      if (planId === currentPlanId) {
        currentFavorite = !!data?.favorite;
        localStorage.setItem(PLAN_FAVORITE_KEY, String(currentFavorite));
        syncFavoriteButtonState();
      }

      showToast(targetValue ? 'Plan has been added to favorites' : 'Plan has been removed from favorites');

      if (refreshList && renameActionContext?.listType) {
        await loadPlanList(renameActionContext.listType);
      }
    } catch (e) {
      console.error(e);
      showToast(e.message || 'Operation failed', true);
    }
  }

  async function loadPlanList(type) {
    if (!isLoggedIn()) {
      showToast('Please log in first', true);
      return;
    }

    const url = type === 'favorites' ? API.myFavorites : API.myPlans;
    openPlanListModal(type === 'favorites' ? 'Favorite Plans' : 'History Plans');

    if (els.planListModalBody) {
      els.planListModalBody.innerHTML = '<div class="muted">Loading...</div>';
    }

    try {
      const resp = await authFetch(url);
      const data = await safeJson(resp);

      if (!resp.ok) {
        throw new Error(data?.message || `Loading failed (${resp.status})`);
      }

      const items = Array.isArray(data?.items) ? data.items : [];
      renderPlanListModal(items, type);
    } catch (e) {
      console.error(e);
      if (els.planListModalBody) {
        els.planListModalBody.innerHTML = '<div class="muted">Loading failed</div>';
      }
      showToast(e.message || 'Failed to load plan list', true);
    }
  }

  async function doLogin(evt) {
    evt.preventDefault();

    const login = (els.loginId?.value || '').trim();
    const password = (els.loginPassword?.value || '').trim();
    const remember = !!els.rememberMe?.checked;
    const challengeVisible = isChallengeVisible('login');
    const challengeId = challengeVisible ? (els.loginChallengeId?.value || '').trim() : '';
    const challengeAnswer = challengeVisible ? (els.loginChallengeAnswer?.value || '').trim() : '';

    if (!login || !password) {
      showToast('Please enter your username and password', true);
      return;
    }
    if (challengeVisible && (!challengeId || !challengeAnswer)) {
      showToast('Please complete the verification challenge', true);
      return;
    }

    setButtonLoading(els.loginSubmit, true, 'Logging in...');
    try {
      const resp = await fetch(API.login, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ login, password, rememberMe: remember, challengeId, challengeAnswer })
      });

      const data = await safeJson(resp);
      if (!resp.ok) {
        throw new Error(data?.message || `Login failed (${resp.status})`);
      }
      if (!data?.token) {
        throw new Error('Login succeeded, but no token was returned');
      }

      setToken(data.token, remember);
      const user = data.user || getUserFromToken();
      renderLoggedIn(user);

      showToast('Login successful');
      syncFavoriteButtonState();
    } catch (e) {
      console.error(e);
      showToast(e.message || 'Login failed', true);
      await loadChallenge('login', true);
    } finally {
      setButtonLoading(els.loginSubmit, false);
    }
  }

  async function doRegister(evt) {
    evt.preventDefault();

    const email = (els.regEmail?.value || '').trim();
    const phone = (els.regPhone?.value || '').trim();
    const password = (els.regPassword?.value || '').trim();
    const name = (els.regUsername?.value || '').trim();
    const login = email || phone;
    const challengeId = (els.registerChallengeId?.value || '').trim();
    const challengeAnswer = (els.registerChallengeAnswer?.value || '').trim();

    if (!login) {
      showToast('Please provide at least one of email or phone number', true);
      return;
    }
    if (!password) {
      showToast('Please enter a password', true);
      return;
    }
    if (!challengeId || !challengeAnswer) {
      showToast('Please complete the verification challenge', true);
      return;
    }

    setButtonLoading(els.registerSubmit, true, 'Creating account...');
    try {
      const resp = await fetch(API.register, {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          login,
          password,
          name: name || null,
          challengeId,
          challengeAnswer
        })
      });

      const data = await safeJson(resp);
      if (!resp.ok) {
        throw new Error(data?.message || `Registration failed (${resp.status})`);
      }
      if (!data?.token) {
        throw new Error('Registration succeeded, but no token was returned');
      }

      setToken(data.token, false);
      const user = data.user || getUserFromToken();
      renderLoggedIn(user);
      showToast('Registration successful. You are now logged in.');
      syncFavoriteButtonState();
    } catch (e) {
      console.error(e);
      showToast(e.message || 'Registration failed', true);
      await loadChallenge('register', true);
    } finally {
      setButtonLoading(els.registerSubmit, false);
    }
  }

  async function logout() {
    try {
      await fetch(API.logout, {
        method: 'POST',
        credentials: 'include'
      });
    } catch (e) {
      console.error(e);
    } finally {
      clearToken();
      renderLoggedOut();
      showToast('Signed out');
    }
  }

  async function deletePlan(planId) {
    const resp = await authFetch(API.delete(planId), {
      method: 'DELETE'
    });
    const data = await safeJson(resp);

    if (!resp.ok) {
      throw new Error(data?.message || `Delete failed (${resp.status})`);
    }
    return data;
  }

  function gotoMapView() {
    const selfDriveChecked = !!els.selfDrive?.checked;
    localStorage.setItem(PLAN_DRIVE_KEY, selfDriveChecked ? 'true' : 'false');
    window.location.href = '/map.html';
  }

  function bindTabs() {
    if (els.authTabLogin) {
      els.authTabLogin.addEventListener('click', () => {
        setVisible(els.loginPanel, true);
        setVisible(els.registerPanel, false);
        els.authTabLogin.classList.add('active');
        els.authTabRegister?.classList.remove('active');
      });
    }

    if (els.authTabRegister) {
      els.authTabRegister.addEventListener('click', () => {
        setVisible(els.loginPanel, false);
        setVisible(els.registerPanel, true);
        els.authTabRegister.classList.add('active');
        els.authTabLogin?.classList.remove('active');
      });
    }
  }

  function bindEvents() {
    [
      els.city,
      els.days,
      els.budget,
      els.adults,
      els.kids,
      els.loginId,
      els.loginPassword,
      els.regEmail,
      els.regPhone,
      els.regPassword,
      els.regUsername
    ].forEach(el => {
      if (!el) return;
      el.addEventListener('invalid', () => {
        if (el.validity.valueMissing) {
          el.setCustomValidity('Please fill out this field.');
        } else {
          el.setCustomValidity('');
        }
      });
      el.addEventListener('input', () => el.setCustomValidity(''));
    });

    els.loginForm?.addEventListener('submit', doLogin);
    els.registerForm?.addEventListener('submit', doRegister);
    els.loginChallengeRefresh?.addEventListener('click', () => loadChallenge('login', true));
    els.registerChallengeRefresh?.addEventListener('click', () => loadChallenge('register', true));
    els.toggleLoginPassword?.addEventListener('change', () => {
      if (els.loginPassword) {
        els.loginPassword.type = els.toggleLoginPassword.checked ? 'text' : 'password';
      }
    });
    els.toggleRegPassword?.addEventListener('change', () => {
      if (els.regPassword) {
        els.regPassword.type = els.toggleRegPassword.checked ? 'text' : 'password';
      }
    });
    els.generateForm?.addEventListener('submit', e => {
      e.preventDefault();
      submitGeneratePlan();
    })
    ;

    els.btnGenerate?.addEventListener('click', e => {
      if (!els.generateForm) {
        e.preventDefault();
        submitGeneratePlan();
      }
    });

    els.btnLogout?.addEventListener('click', logout);
    els.btnClearPlan?.addEventListener('click', clearPlanUI);
    els.btnCopyJson?.addEventListener('click', copyCurrentPlanJson);
    els.btnFavoritePlan?.addEventListener('click', toggleFavoriteCurrentPlan);
    els.btnHistoryPlans?.addEventListener('click', () => loadPlanList('history'));
    els.btnFavoritePlans?.addEventListener('click', () => loadPlanList('favorites'));
    els.resultDays?.addEventListener('click', e => {
      const btn = e.target.closest('[data-action="select-day"]');
      if (!btn) return;
      selectDayPage(btn.dataset.dayIndex);
    });
    els.btnMapButtons.forEach(btn => {
      btn.addEventListener('click', e => {
        e.preventDefault();
        gotoMapView();
      });
    });
    window.addEventListener('resize', syncDaySideArrowPosition);
    window.addEventListener('scroll', syncDaySideArrowPosition, { passive: true });

    if (els.selfDrive) {
      els.selfDrive.addEventListener('change', () => {
        localStorage.setItem(PLAN_DRIVE_KEY, els.selfDrive.checked ? 'true' : 'false');
      });
    }
    els.planListModalClose?.addEventListener('click', closePlanListModal);
    els.planListModalMask?.addEventListener('click', closePlanListModal);
    els.planListModalBody?.addEventListener('click', async (e) => {
      const btn = e.target.closest('button[data-action]');
      if (!btn) return;

      const action = btn.dataset.action;
      const planId = Number(btn.dataset.planId || 0);
      const listType = btn.dataset.listType || 'history';

      if (!planId) return;

      if (action === 'view-plan') {
        await viewPlanDetail(planId);
        return;
      }

      if (action === 'rename-plan') {
        const currentTitle = btn.dataset.title || '';
        openRenameModal('Rename Plan', currentTitle, {
          type: 'rename-plan',
          planId,
          listType
        });
        return;
      }

      if (action === 'delete-plan') {
        openDeletePlanModal({
          planId,
          listType
        });
        return;
      }

      if (action === 'toggle-favorite') {
        const currentFav = btn.dataset.favorite === 'true';
        await doFavoritePlan(planId, !currentFav, false);
        await loadPlanList(listType);
      }
    });

    els.renameModalClose?.addEventListener('click', closeRenameModal);
    els.renameModalMask?.addEventListener('click', closeRenameModal);
    els.deletePlanModalClose?.addEventListener('click', closeDeletePlanModal);
    els.deletePlanModalMask?.addEventListener('click', closeDeletePlanModal);
    els.deletePlanModalCancel?.addEventListener('click', closeDeletePlanModal);

    els.renameModalSkip?.addEventListener('click', async () => {
      if (!renameActionContext) {
        closeRenameModal();
        return;
      }

      const ctx = renameActionContext;

      try {
        if (ctx.type === 'favorite-current-plan') {
          await doFavoritePlan(ctx.planId, true, false);
        }
        closeRenameModal();
      } catch (e) {
        console.error(e);
        showToast(e.message || 'Operation failed', true);
      }
    });

    els.renameModalConfirm?.addEventListener('click', async () => {
      if (!renameActionContext) {
        closeRenameModal();
        return;
      }

      const ctx = renameActionContext;
      const title = (els.renameInput?.value || '').trim();

      try {
        if (ctx.type === 'favorite-current-plan') {
          if (title) {
            await renamePlan(ctx.planId, title);
          }
          await doFavoritePlan(ctx.planId, true, false);
          closeRenameModal();
          return;
        }

        if (ctx.type === 'rename-plan') {
          await renamePlan(ctx.planId, title);
          closeRenameModal();
          await loadPlanList(ctx.listType || 'history');
          showToast('Plan title updated');
        }
      } catch (e) {
        console.error(e);
        showToast(e.message || 'Operation failed', true);
      }
    });

    els.deletePlanModalConfirm?.addEventListener('click', async () => {
      if (!deleteActionContext) {
        closeDeletePlanModal();
        return;
      }

      const ctx = deleteActionContext;
      try {
        await deletePlan(ctx.planId);
        if (ctx.planId === currentPlanId) {
          clearRenderedPlanOnly();
        }
        closeDeletePlanModal();
        await loadPlanList(ctx.listType || 'history');
        showToast('Plan deleted');
      } catch (e) {
        console.error(e);
        showToast(e.message || 'Delete failed', true);
      }
    });
  }
  function openPlanListModal(title) {
    if (els.planListModalTitle) els.planListModalTitle.textContent = title;
    if (els.planListModal) els.planListModal.style.display = 'block';
  }

  function closePlanListModal() {
    if (els.planListModal) els.planListModal.style.display = 'none';
  }

  function renderPlanListModal(items, type) {
    if (!els.planListModalBody) return;

    if (!items.length) {
      els.planListModalBody.innerHTML =
          `<div class="muted">${type === 'favorites' ? 'No favorite plans yet' : 'No history plans yet'}</div>`;
      return;
    }

    els.planListModalBody.innerHTML = items.map(item => {
      const title = item.title || 'Untitled Plan';
      const city = item.city || '-';
      const days = item.days ?? '-';
      const favorite = item.favorite ? 'Yes' : 'No';
      const createdAt = item.createdAt || '-';

      let actionButtons = `
      <button
        class="plan-mini-btn"
        data-action="view-plan"
        data-plan-id="${item.id}"
        data-list-type="${type}">
        View Plan
      </button>
      <button
        class="plan-mini-btn"
        data-action="rename-plan"
        data-plan-id="${item.id}"
        data-title="${escapeHtml(item.title || '')}"
        data-list-type="${type}">
        Rename
      </button>
    `;

      if (type === 'favorites') {
        actionButtons += `
        <button
          class="plan-mini-btn"
          data-action="toggle-favorite"
          data-plan-id="${item.id}"
          data-favorite="true"
          data-list-type="${type}">
          Remove Favorite
        </button>
      `;
      } else if (!item.favorite) {
        actionButtons += `
        <button
          class="plan-mini-btn primary"
          data-action="toggle-favorite"
          data-plan-id="${item.id}"
          data-favorite="false"
          data-list-type="${type}">
          Favorite This Plan
        </button>
      `;
      }

      return `
      <div class="plan-modal-item">
        <button
          class="plan-item-delete"
          type="button"
          data-action="delete-plan"
          data-plan-id="${item.id}"
          data-list-type="${type}"
          aria-label="Delete plan">
          ×
        </button>
        <div class="title">${escapeHtml(title)}</div>
        <div class="plan-modal-grid">
          <div><strong>City:</strong> ${escapeHtml(city)}</div>
          <div><strong>Days:</strong> ${escapeHtml(days)}</div>
          <div><strong>Favorite:</strong> ${escapeHtml(favorite)}</div>
          <div><strong>Created At:</strong> ${escapeHtml(createdAt)}</div>
        </div>
        <div class="plan-item-actions">
          ${actionButtons}
        </div>
      </div>
    `;
    }).join('');
    localizeDom(els.planListModalBody);
  }

  function restoreDriveChoice() {
    const v = localStorage.getItem(PLAN_DRIVE_KEY);
    if (els.selfDrive) els.selfDrive.checked = v === 'true';
  }

  function openRenameModal(title, defaultValue = '', context = null) {
    renameActionContext = context;
    if (els.renameModalTitle) els.renameModalTitle.textContent = title;
    if (els.renameInput) els.renameInput.value = defaultValue || '';
    if (els.renameModal) els.renameModal.style.display = 'block';
    setTimeout(() => els.renameInput?.focus(), 0);
  }

  function closeRenameModal() {
    renameActionContext = null;
    if (els.renameModal) els.renameModal.style.display = 'none';
  }

  function openDeletePlanModal(context) {
    deleteActionContext = context;
    if (els.deletePlanModal) els.deletePlanModal.style.display = 'block';
  }

  function closeDeletePlanModal() {
    deleteActionContext = null;
    if (els.deletePlanModal) els.deletePlanModal.style.display = 'none';
  }

  async function renamePlan(planId, title) {
    const resp = await authFetch(API.rename(planId, title), {
      method: 'PATCH'
    });
    const data = await safeJson(resp);

    if (!resp.ok) {
      throw new Error(data?.message || `Rename failed (${resp.status})`);
    }
    return data;
  }

  function fillFormFromPlanDetail(detail) {
    const plan = detail?.plan || {};

    if (els.city) els.city.value = plan.city || '';
    if (els.days) els.days.value = plan.days || '';
    if (els.budget) els.budget.value = plan.budgetCents ? plan.budgetCents / 100 : '';
    if (els.adults) els.adults.value = plan.partyAdults ?? '';
    if (els.kids) els.kids.value = plan.partyKids ?? '';
    if (els.departureDate) els.departureDate.value = plan.departureDate || '';
    if (els.pace) els.pace.value = plan.pace || 'normal';
  }

  async function viewPlanDetail(planId) {
    if (!isLoggedIn()) {
      showToast('Please log in first', true);
      return;
    }

    try {
      const resp = await authFetch(API.detail(planId));
      const data = await safeJson(resp);

      if (!resp.ok) {
        throw new Error(data?.message || `Failed to load plan details (${resp.status})`);
      }

      const draft = buildDraftFromPlanDetail(data);

      // 娓呮帀褰撳墠缁撴灉鍖?      clearRenderedPlanOnly();

      currentPlanId = data?.plan?.id || planId;
      currentDraft = draft;
      currentPreview = '';
      currentFavorite = !!data?.plan?.favorite;

      localStorage.setItem(PLAN_ID_KEY, String(currentPlanId));
      writeJson(PLAN_CACHE_KEY, draft);
      localStorage.setItem(PLAN_PREVIEW_KEY, '');
      localStorage.setItem(PLAN_FAVORITE_KEY, String(currentFavorite));

      fillFormFromPlanDetail(data);
      renderDraft(draft, '');
      closePlanListModal();
      syncFavoriteButtonState();

      showToast('Plan loaded below for review');
    } catch (e) {
      console.error(e);
      showToast(e.message || 'Failed to load plan details', true);
    }
  }



  function buildDraftFromPlanDetail(detail) {
    const plan = detail?.plan || {};
    const daysPlan = Array.isArray(detail?.daysPlan) ? detail.daysPlan : [];

    return {
      city: plan.city || '',
      country: daysPlan[0]?.hotel?.country || '',
      days: plan.days || daysPlan.length || 0,
      currency: 'AUD',
      party: {
        adults: plan.partyAdults ?? 0,
        kids: plan.partyKids ?? 0
      },
      pace: plan.pace || 'normal',
      budget: plan.budgetCents ? plan.budgetCents / 100 : null,
      departureDate: plan.departureDate || '',
      daysPlan: daysPlan.map(day => ({
        dayIndex: day.dayIndex || 0,
        hotel: day.hotel
            ? {
              id: day.hotel.id || null,
              name: day.hotel.name || '',
              addressLine: day.hotel.addressLine || '',
              suburb: day.hotel.district || '',
              city: day.hotel.city || '',
              state: '',
              postcode: '',
              country: day.hotel.country || '',
              latitude: day.hotel.latitude ?? null,
              longitude: day.hotel.longitude ?? null,
              category: 'hotel',
              stayMinutes: null,
              timeSlot: day.hotel.timeSlot || 'night',
              startTime: day.hotel.startTime || '',
              endTime: day.hotel.endTime || '',
              reason: day.hotelReason || '',
              tip: day.hotelTip || '',
              url: day.hotel.external_ref || ''
            }
            : null,
        stops: Array.isArray(day.stops)
            ? day.stops
                .slice()
                .sort((a, b) => (a.seq || 0) - (b.seq || 0))
                .map(stop => ({
                  id: stop.place?.id || null,
                  name: stop.place?.name || '',
                  addressLine: stop.place?.addressLine || '',
                  suburb: stop.place?.district || '',
                  city: stop.place?.city || '',
                  state: '',
                  postcode: '',
                  country: stop.place?.country || '',
                  latitude: stop.place?.latitude ?? null,
                  longitude: stop.place?.longitude ?? null,
                  category: stop.category || '',
                  stayMinutes: stop.dwellMinutes ?? null,
                  timeSlot: stop.timeSlot || '',
                  startTime: stop.startTime || '',
                  endTime: stop.endTime || '',
                  mealType: stop.mealType || '',
                  preferredArea: stop.preferredArea || '',
                  cuisine: stop.cuisine || '',
                  vibe: stop.vibe || '',
                  budgetLevel: stop.budgetLevel || '',
                  reason: stop.reason || '',
                  tip: stop.tip || '',
                  url: stop.place?.external_ref || ''
                }))
            : [],
        note: day.note || ''
      }))
    };
  }

  async function init() {
    await bootAuthState();
    bindTabs();
    bindEvents();
    loadChallenge('register', true);
    restoreDriveChoice();
    restoreLatestPlan();
    syncFavoriteButtonState();
    localizeDom(document.body);
  }

  document.addEventListener('DOMContentLoaded', init);
})();
