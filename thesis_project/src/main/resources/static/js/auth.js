
// auth.js — 登录注册逻辑（防止 $ 冲突）
// 简单 DOM 选择器封装
// ===== 小工具 =====
const $$  = sel => document.querySelector(sel);
const $$$ = sel => document.querySelectorAll(sel);

const API = {
    login:    '/auth/login',
    register: '/auth/register',

};

const STORAGE_KEY = 'nav_jwt';

// 解析 JWT payload（无校验，只做 UI 用）
function parseJwt(token) {
    try {
        const base64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
        const json = decodeURIComponent(atob(base64).split('').map(c =>
            '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2)
        ).join(''));
        return JSON.parse(json);
    } catch { return null; }
}

function nowSec() { return Math.floor(Date.now()/1000); }

function getToken() {
    // 优先 localStorage（记住我），其次 sessionStorage
    return localStorage.getItem(STORAGE_KEY) || sessionStorage.getItem(STORAGE_KEY) || null;
}
function setToken(token, remember) {
    // 只存一个地方，避免混乱
    localStorage.removeItem(STORAGE_KEY);
    sessionStorage.removeItem(STORAGE_KEY);
    if (remember) localStorage.setItem(STORAGE_KEY, token);
    else          sessionStorage.setItem(STORAGE_KEY, token);
}
function clearToken() {
    localStorage.removeItem(STORAGE_KEY);
    sessionStorage.removeItem(STORAGE_KEY);
}

// 包装 fetch：自动带 Authorization；401 自动登出
async function authFetch(url, options={}) {
    const token = getToken();
    const headers = new Headers(options.headers || {});
    if (token) headers.set('Authorization', 'Bearer ' + token);
    headers.set('Content-Type', 'application/json');

    const resp = await fetch(url, { ...options, headers });
    if (resp.status === 401) {
        // token 过期/无效
        clearToken();
        // 弹个轻提示（简单起见用 alert）
        alert('登录已过期，请重新登录');
        renderUserLoggedOut();
    }
    return resp;
}

// ====== UI：登录态渲染 ======
function renderUserLoggedIn({ username, email }) {
    const btnOpenAuth = $$('#btnOpenAuth');
    const userMenu    = $$('#userMenu');
    const userInitial = $$('#userInitial');
    const userNameEl  = $$('#userName');
    const userEmailEl = $$('#userEmail');

    btnOpenAuth.style.display = 'none';
    userMenu.style.display = 'block';

    userInitial.textContent = (username || email || 'U').trim().charAt(0).toUpperCase();
    userNameEl.textContent  = username || email || '未命名';
    userEmailEl.textContent = email || '';
}

function renderUserLoggedOut() {
    $$('#btnOpenAuth').style.display = 'block';
    $$('#userMenu').style.display    = 'none';
    const dd = $$('#userDropdown');
    if (dd) dd.style.display = 'none';
}

// 从 token 或 /me 获取用户信息
async function loadUserFromToken() {
    const token = getToken();
    if (!token) { renderUserLoggedOut(); return; }
    // 过期检查
    const payload = parseJwt(token);
    if (payload?.exp && payload.exp < nowSec()) {
        clearToken();
        renderUserLoggedOut();
        return;
    }

    // 优先调 /me，失败则用 payload 兜底
    try {
        const r = await authFetch(API.me);
        if (r.ok) {
            const u = await r.json(); // 期待 {id, username, email, phone,...}
            renderUserLoggedIn({ username: u.username, email: u.email });
            return;
        }
    } catch (_) {}
    // 兜底：直接用 JWT payload
    renderUserLoggedIn({ username: payload?.username, email: payload?.email });
}

// ====== 绑定事件 ======
document.addEventListener('DOMContentLoaded', () => {
    // 元素引用
    const btnOpenAuth   = $$('#btnOpenAuth');
    const authModal     = $$('#authModal');
    const authBackdrop  = $$('#authBackdrop');
    const authClose     = $$('#authClose');
    const tabs          = $$('.tabs');
    const loginForm     = $$('#loginForm');
    const registerForm  = $$('#registerForm');
    const userChip      = $$('#userChip');
    const userDropdown  = $$('#userDropdown');
    const btnLogout     = $$('#btnLogout');

    // ===== 初始：渲染登录态 =====
    loadUserFromToken();

    // 打开弹窗
    btnOpenAuth.addEventListener('click', () => { authModal.style.display = 'block'; });

    // 关闭弹窗（遮罩、X、卡片外）
    const closeAuth = () => { authModal.style.display = 'none'; };
    authBackdrop.addEventListener('click', closeAuth);
    authClose.addEventListener('click', closeAuth);
    const authCard = document.querySelector('.auth-card');
    if (authCard) authCard.addEventListener('click', e => e.stopPropagation());
    authModal.addEventListener('click', (e) => { if (e.target === authModal) closeAuth(); });

    // Tab 切换
    tabs.addEventListener('click', e => {
        if (!e.target.classList.contains('tab')) return;
        tabs.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
        e.target.classList.add('active');
        const targetPanel = e.target.dataset.tab;
        $$$('.tab-panel').forEach(p => { p.style.display = (p.dataset.panel === targetPanel) ? '' : 'none'; });
    });

    // 登录提交
    loginForm.addEventListener('submit', async e => {
        e.preventDefault();
        const loginId = $$('#loginId').value.trim();
        const pwd     = $$('#loginPassword').value.trim();
        const remember= $$('#rememberMe').checked;

        if (!loginId || !pwd) { alert('请输入账号和密码'); return; }

        try {
            // 根据你后端的定义：这里用 {login, password}
            const resp = await fetch(API.login, {
                method: 'POST',
                headers: { 'Content-Type':'application/json' },
                body: JSON.stringify({ login: loginId, password: pwd })
            });

            if (!resp.ok) {
                const err = await safeJson(resp);
                throw new Error(err?.message || `登录失败（${resp.status}）`);
            }
            const data = await resp.json(); // 期待 { token: '...' } 或 { token, user:{...} }
            if (!data.token) throw new Error('登录成功但未返回 token');

            setToken(data.token, remember);

            // 优先使用后端返回的 user，否则从 JWT 解析
            const payload = parseJwt(data.token) || {};
            const user = data.user || { username: payload.username, email: payload.email };
            renderUserLoggedIn(user);

            closeAuth();
        } catch (err) {
            console.error(err);
            alert(err.message || '登录异常');
        }
    });

    // 注册提交
    registerForm.addEventListener('submit', async e => {
        e.preventDefault();
        const email = $$('#regEmail').value.trim();
        const phone = $$('#regPhone').value.trim();
        const pwd   = $$('#regPassword').value;
        const name  = $$('#regUsername').value.trim();

        // 至少一个账号
        const login = email || phone;
        if (!login) { alert('邮箱与手机号至少填写一个'); return; }
        if (!pwd) { alert('请输入密码'); return; }

        try {
            const resp = await fetch('/auth/register', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    login: login,          // ← 必须叫 login
                    password: pwd,         // ← 密码
                    name: name || null     // ← 可选用户名
                })
            });

            if (!resp.ok) {
                const msg = await resp.text();
                throw new Error(msg || `注册失败 (${resp.status})`);
            }

            // 注册成功：切换到登录 Tab，并把账号预填
            tabs.querySelector('[data-tab="login"]').click();
            $$('#loginId').value = login;
            $$('#loginPassword').focus();
            alert('注册成功，请登录');
        } catch (err) {
            console.error(err);
            alert(err.message || '注册异常');
        }
    });

    // 用户头像点击 — 切换菜单显示
    userChip.addEventListener('click', () => {
        const isShown = userDropdown.style.display === 'block';
        userDropdown.style.display = isShown ? 'none' : 'block';
    });
    // 点击页面其他区域时收起菜单
    document.addEventListener('click', (e) => {
        if (!userDropdown.contains(e.target) && !userChip.contains(e.target)) {
            userDropdown.style.display = 'none';
        }
    });

    // 退出登录
    btnLogout.addEventListener('click', () => {
        clearToken();
        renderUserLoggedOut();
    });
});

// 安全 JSON 解析
async function safeJson(resp) {
    try { return await resp.json(); }
    catch { return null; }
}
