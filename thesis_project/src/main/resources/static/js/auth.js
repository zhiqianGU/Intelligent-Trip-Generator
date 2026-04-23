const $$ = sel => document.querySelector(sel);
const $$$ = sel => document.querySelectorAll(sel);

const API = {
    login: '/auth/login',
    register: '/auth/register',
    challenge: '/auth/challenge',
    refresh: '/auth/refresh',
    logout: '/auth/logout',
    me: '/auth/me'
};

const STORAGE_KEY = 'nav_jwt';
let currentAuthProfile = null;

function parseJwt(token) {
    try {
        const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
        const json = decodeURIComponent(atob(base64).split('').map(c =>
            '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2)
        ).join(''));
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

function setToken(token) {
    localStorage.removeItem(STORAGE_KEY);
    sessionStorage.removeItem(STORAGE_KEY);
    sessionStorage.setItem(STORAGE_KEY, token);
}

function clearToken() {
    localStorage.removeItem(STORAGE_KEY);
    sessionStorage.removeItem(STORAGE_KEY);
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
    const box = $$('#' + kind + 'ChallengeBox');
    const question = $$('#' + kind + 'ChallengeQuestion');
    const challengeId = $$('#' + kind + 'ChallengeId');
    const answer = $$('#' + kind + 'ChallengeAnswer');
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
    } catch (err) {
        console.error(err);
        alert(err.message || 'Challenge failed to load');
    }
}

function isChallengeVisible(kind) {
    const box = $$('#' + kind + 'ChallengeBox');
    return !!box && box.style.display !== 'none';
}

async function safeJson(resp) {
    try {
        return await resp.json();
    } catch {
        return null;
    }
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
        setToken(data.token);
        return data;
    } catch {
        clearToken();
        return null;
    }
}

async function authFetch(url, options = {}) {
    const headers = new Headers(options.headers || {});
    const token = getToken();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');

    let resp = await fetch(url, { ...options, headers, credentials: 'include' });

    if (resp.status === 401) {
        const refreshed = await refreshAccessToken();
        if (refreshed?.token) {
            const retryHeaders = new Headers(options.headers || {});
            retryHeaders.set('Authorization', `Bearer ${refreshed.token}`);
            if (!retryHeaders.has('Content-Type')) retryHeaders.set('Content-Type', 'application/json');
            resp = await fetch(url, { ...options, headers: retryHeaders, credentials: 'include' });
        }
    }

    if (resp.status === 401) {
        clearToken();
        alert('Login expired. Please log in again.');
        renderUserLoggedOut();
    }

    return resp;
}

function setCurrentAuthProfile(profile) {
    currentAuthProfile = profile || null;
    window.currentAuthProfile = currentAuthProfile;
}

function getCurrentAuthProfile() {
    return currentAuthProfile || window.currentAuthProfile || null;
}

async function loadCurrentUserProfile() {
    const resp = await authFetch(API.me, { method: 'GET', headers: {} });
    const user = await safeJson(resp);
    if (!resp.ok || !user) {
        throw new Error('Failed to load current user');
    }
    setCurrentAuthProfile(user);
    return user;
}

function renderUserLoggedIn({ username, email }) {
    const btnOpenAuth = $$('#btnOpenAuth');
    const userMenu = $$('#userMenu');
    const userInitial = $$('#userInitial');
    const userNameEl = $$('#userName');
    const userEmailEl = $$('#userEmail');

    if (btnOpenAuth) btnOpenAuth.style.display = 'none';
    if (userMenu) userMenu.style.display = 'block';

    if (userInitial) userInitial.textContent = (username || email || 'U').trim().charAt(0).toUpperCase();
    if (userNameEl) userNameEl.textContent = username || email || 'Unnamed User';
    if (userEmailEl) userEmailEl.textContent = email || '';
    if (typeof renderMapAccountState === 'function') renderMapAccountState();
    if (typeof renderMapPlanLibrary === 'function') renderMapPlanLibrary();
}

function renderUserLoggedOut() {
    setCurrentAuthProfile(null);
    const btnOpenAuth = $$('#btnOpenAuth');
    const userMenu = $$('#userMenu');
    if (btnOpenAuth) btnOpenAuth.style.display = 'block';
    if (userMenu) userMenu.style.display = 'none';
    const dd = $$('#userDropdown');
    if (dd) dd.style.display = 'none';
    if (typeof renderMapAccountState === 'function') renderMapAccountState();
    if (typeof renderMapPlanLibrary === 'function') renderMapPlanLibrary();
}

async function loadUserFromToken() {
    if (!getToken()) {
        const refreshed = await refreshAccessToken();
        if (!refreshed?.token) {
            renderUserLoggedOut();
            return;
        }
    }

    let token = getToken();
    let payload = parseJwt(token);
    if (payload?.exp && payload.exp < nowSec()) {
        const refreshed = await refreshAccessToken();
        if (!refreshed?.token) {
            clearToken();
            renderUserLoggedOut();
            return;
        }
        token = getToken();
        payload = parseJwt(token);
    }

    try {
        const user = await loadCurrentUserProfile();
        renderUserLoggedIn({
            username: user?.username || payload?.username,
            email: user?.email || payload?.email
        });
        return;
    } catch (_) {
    }

    setCurrentAuthProfile({
        username: payload?.username || '',
        email: payload?.email || '',
        phone: payload?.phone || '',
        loggedIn: true
    });
    renderUserLoggedIn({ username: payload?.username, email: payload?.email });
}

document.addEventListener('DOMContentLoaded', () => {
    const btnOpenAuth = $$('#btnOpenAuth');
    const authModal = $$('#authModal');
    const authBackdrop = $$('#authBackdrop');
    const authClose = $$('#authClose');
    const tabs = $$('.tabs');
    const loginForm = $$('#loginForm');
    const registerForm = $$('#registerForm');
    const userChip = $$('#userChip');
    const userDropdown = $$('#userDropdown');
    const btnLogout = $$('#btnLogout');

    loadUserFromToken();

    btnOpenAuth?.addEventListener('click', () => {
        if (authModal) authModal.style.display = 'block';
    });

    const closeAuth = () => {
        if (authModal) authModal.style.display = 'none';
    };
    authBackdrop?.addEventListener('click', closeAuth);
    authClose?.addEventListener('click', closeAuth);
    document.querySelector('.auth-card')?.addEventListener('click', e => e.stopPropagation());
    authModal?.addEventListener('click', e => {
        if (e.target === authModal) closeAuth();
    });

    tabs?.addEventListener('click', e => {
        if (!e.target.classList.contains('tab')) return;
        tabs.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        e.target.classList.add('active');
        const targetPanel = e.target.dataset.tab;
        $$$('.tab-panel').forEach(p => {
            p.style.display = p.dataset.panel === targetPanel ? '' : 'none';
        });
    });

    loginForm?.addEventListener('submit', async e => {
        e.preventDefault();
        const loginId = $$('#loginId')?.value.trim() || '';
        const pwd = $$('#loginPassword')?.value.trim() || '';
        const remember = !!$$('#rememberMe')?.checked;
        const submitButton = loginForm.querySelector('button[type="submit"]');
        const challengeVisible = isChallengeVisible('login');
        const challengeId = challengeVisible ? ($$('#loginChallengeId')?.value.trim() || '') : '';
        const challengeAnswer = challengeVisible ? ($$('#loginChallengeAnswer')?.value.trim() || '') : '';

        if (!loginId || !pwd) {
            alert('Please enter your account and password.');
            return;
        }
        if (challengeVisible && (!challengeId || !challengeAnswer)) {
            alert('Please complete the verification challenge.');
            return;
        }

        setButtonLoading(submitButton, true, 'Logging in...');
        try {
            const resp = await fetch(API.login, {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ login: loginId, password: pwd, rememberMe: remember, challengeId, challengeAnswer })
            });
            const data = await safeJson(resp);
            if (!resp.ok) {
                throw new Error(data?.message || `Login failed (${resp.status})`);
            }
            if (!data?.token) {
                throw new Error('Login succeeded, but no token was returned.');
            }

            setToken(data.token);
            const payload = parseJwt(data.token) || {};
            const user = await loadCurrentUserProfile().catch(() => (
                data.user || { username: payload.username, email: payload.email }
            ));
            renderUserLoggedIn(user);
            closeAuth();
        } catch (err) {
            console.error(err);
            alert(err.message || 'Login error');
            await loadChallenge('login', true);
        } finally {
            setButtonLoading(submitButton, false);
        }
    });

    $$('#loginChallengeRefresh')?.addEventListener('click', () => loadChallenge('login', true));
    $$('#registerChallengeRefresh')?.addEventListener('click', () => loadChallenge('register', true));

    registerForm?.addEventListener('submit', async e => {
        e.preventDefault();
        const email = $$('#regEmail')?.value.trim() || '';
        const phone = $$('#regPhone')?.value.trim() || '';
        const pwd = $$('#regPassword')?.value || '';
        const name = $$('#regUsername')?.value.trim() || '';
        const login = email || phone;
        const submitButton = registerForm.querySelector('button[type="submit"]');
        const challengeId = $$('#registerChallengeId')?.value.trim() || '';
        const challengeAnswer = $$('#registerChallengeAnswer')?.value.trim() || '';

        if (!login) {
            alert('Please enter at least an email or a phone number.');
            return;
        }
        if (!pwd) {
            alert('Please enter a password.');
            return;
        }
        if (!challengeId || !challengeAnswer) {
            alert('Please complete the verification challenge.');
            return;
        }

        setButtonLoading(submitButton, true, 'Creating account...');
        try {
            const resp = await fetch(API.register, {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ login, password: pwd, name: name || null, challengeId, challengeAnswer })
            });
            const data = await safeJson(resp);
            if (!resp.ok) {
                throw new Error(data?.message || `Registration failed (${resp.status})`);
            }
            if (!data?.token) {
                throw new Error('Registration succeeded, but no token was returned.');
            }

            setToken(data.token);
            const payload = parseJwt(data.token) || {};
            const user = await loadCurrentUserProfile().catch(() => (
                data.user || { username: payload.username, email: payload.email }
            ));
            renderUserLoggedIn(user);
            closeAuth();
            alert('Registration successful. You are now logged in.');
        } catch (err) {
            console.error(err);
            alert(err.message || 'Registration error');
            await loadChallenge('register', true);
        } finally {
            setButtonLoading(submitButton, false);
        }
    });

    userChip?.addEventListener('click', () => {
        if (!userDropdown) return;
        userDropdown.style.display = userDropdown.style.display === 'block' ? 'none' : 'block';
    });

    document.addEventListener('click', e => {
        if (!userDropdown || !userChip) return;
        if (!userDropdown.contains(e.target) && !userChip.contains(e.target)) {
            userDropdown.style.display = 'none';
        }
    });

    btnLogout?.addEventListener('click', async () => {
        try {
            await fetch(API.logout, {
                method: 'POST',
                credentials: 'include'
            });
        } finally {
            clearToken();
            renderUserLoggedOut();
        }
    });

    loadChallenge('register', true);
});
