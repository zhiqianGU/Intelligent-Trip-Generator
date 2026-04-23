// ==================== 坐标转换：GCJ-02 -> WGS-84 ====================
// ==================== 坐标转换：GCJ-02 -> WGS-84 ====================
function gcj02towgs84(lng, lat) {
    const PI = Math.PI, AXIS = 6378245.0, EE = 0.00669342162296594323;
    function tLat(x,y){let r=-100+2*x+3*y+0.2*y*y+0.1*x*y+0.2*Math.sqrt(Math.abs(x));
        r+=(20*Math.sin(6*x*PI)+20*Math.sin(2*x*PI))*2/3;
        r+=(20*Math.sin(y*PI)+40*Math.sin(y/3*PI))*2/3;
        r+=(160*Math.sin(y/12*PI)+320*Math.sin(y*PI/30))*2/3; return r;}
    function tLng(x,y){let r=300+x+2*y+0.1*x*x+0.1*x*y+0.1*Math.sqrt(Math.abs(x));
        r+=(20*Math.sin(6*x*PI)+20*Math.sin(2*x*PI))*2/3;
        r+=(20*Math.sin(x*PI)+40*Math.sin(x/3*PI))*2/3;
        r+=(150*Math.sin(x/12*PI)+300*Math.sin(x/30*PI))*2/3; return r;}
    let dLat=tLat(lng-105,lat-35), dLng=tLng(lng-105,lat-35);
    const radLat=lat/180*PI; let magic=Math.sin(radLat);
    magic=1-EE*magic*magic; const sqrtMagic=Math.sqrt(magic);
    dLat=(dLat*180)/((AXIS*(1-EE))/(magic*sqrtMagic)*PI);
    dLng=(dLng*180)/(AXIS/sqrtMagic*Math.cos(radLat)*PI);
    return [lng-dLng, lat-dLat];
}

// ==================== 小工具 ====================
const $ = sel => document.querySelector(sel);
function fmtMin(sec){ return Math.round(+sec/60) + '分钟'; }
function km(m){ return (+m/1000).toFixed(1) + ' 公里'; }

function locStrToWgs(loc) {
    if (!loc) return null;
    const [lng, lat] = loc.split(',').map(Number);
    if (isNaN(lng) || isNaN(lat)) return null;
    const [x, y] = gcj02towgs84(lng, lat);
    return [y, x];
}
function polylineToWgsCoords(raw) {
    if (!raw) return [];
    return raw.split(';').map(s => {
        const [lng, lat] = s.split(',').map(Number);
        const [x, y] = gcj02towgs84(lng, lat);
        return [y, x]; // Leaflet [lat,lng]
    });
}

// ==================== Leaflet 初始化 ====================
const map = L.map('map').setView([-27.4698,153.0251], 12);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{
    attribution:'&copy; OpenStreetMap contributors'
}).addTo(map);

// 步骤小圆点放到更高的 pane
map.createPane('stepsPane');
map.getPane('stepsPane').style.zIndex = 650;

let routeLayers = [];   // 可能多段线
let stepDots = [];
let routeMarkers = [];

const PLAN_CACHE_KEY = 'latest_trip_plan_draft';
const PLAN_DRIVE_KEY = 'latest_trip_plan_self_drive';
const geocodeCache = new Map();
const cityCenterCache = new Map();
const planRouteCache = new Map();
let activePlanDraft = null;
let activePlanDayIndex = 0;
let activePlanSegments = [];
let activeSegmentIndex = -1;
let activePlanListType = 'history';
let activeSelectedPlanId = Number(localStorage.getItem('latest_trip_plan_id') || 0) || null;
let activePlanListOpen = false;
let planDebugVisible = false;
let activePlanBaseMode = 'transit';
let walkConfirmResolver = null;

function clearRoute(){
    routeLayers.forEach(l => map.removeLayer(l));
    routeLayers = [];
    stepDots.forEach(d => map.removeLayer(d));
    stepDots = [];
    routeMarkers.forEach(m => map.removeLayer(m));
    routeMarkers = [];
}

function toOptionalNumber(value) {
    if (value === null || value === undefined || value === '') return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
}

// ==================== 出行方式切换 ====================
let currentMode = 'transit'; // drive | transit | walk | bike(同步用walk)
let lastQuerySig = null;
const originEl = document.getElementById('origin');
const destEl   = document.getElementById('destination');
const cityEl   = document.getElementById('city');

// 当前输入签名（起点|终点|城市）
const currentSig = () =>
    `${(originEl.value || '').trim()}|${(destEl.value || '').trim()}|${(cityEl.value || '').trim()}`;

// 输入变动 => 失效签名 + 清除坐标缓存
function invalidateInputs() {
    lastQuerySig = null;
    [originEl, destEl].forEach(el => {
        delete el.dataset.lng;
        delete el.dataset.lat;
        delete el.dataset.boundValue;
    });
    // 隐藏结果区域 + 清掉地图上的线
    document.getElementById('summary').hidden = true;
    document.getElementById('steps').hidden = true;
    clearRoute && clearRoute();
}
['input','change','paste'].forEach(evt => {
    originEl.addEventListener(evt, () => setTimeout(invalidateInputs));
    destEl.addEventListener(evt,   () => setTimeout(invalidateInputs));
    cityEl.addEventListener(evt,   () => setTimeout(() => lastQuerySig = null));
});

document.querySelectorAll('.mode').forEach(btn=>{
    btn.addEventListener('click', ()=>{
        document.querySelectorAll('.mode').forEach(b=>b.classList.remove('active'));
        btn.classList.add('active');
        const m = btn.dataset.mode;
        currentMode = (m === 'bike') ? 'walk' : (m === 'car' ? 'drive' : m);

        if (lastQuerySig && lastQuerySig === currentSig()) {
            runRoute(); // 只有城市具备时才自动触发
        } else if (activePlanDraft) {
            loadPlanRoutesForDay(activePlanDayIndex, true);
        }
    });
});


// ==================== 候选下拉（origin / destination） ====================
async function openSuggest(targetId){
    const input = document.getElementById(targetId);
    const list  = document.getElementById(targetId==='origin' ? 'origin-suggest' : 'dest-suggest');
    const q = (input.value || '').trim();
    if(!q){ list.style.display='none'; return; }

    const city = (cityEl?.value || '').trim();
    const url = new URL('/api/v1/map/suggestions', location.origin);
    url.searchParams.set('address', q);
    if (city) url.searchParams.set('city', city);

    try{
        const res = await fetch(url);
        if(!res.ok) throw new Error('suggestions failed: ' + res.status);
        const arr = await res.json();
        list.innerHTML = '';
        arr.forEach(p=>{
            const item = document.createElement('div');
            item.className = 'item';
            item.textContent = p.displayName;
            item.addEventListener('click', ()=>{
                input.value = p.displayName;
                input.dataset.lng = p.longitude;
                input.dataset.lat = p.latitude;
                input.dataset.boundValue = p.displayName; // ✅
                lastQuerySig = null;                      // ✅ 选中候选也算输入变化
                list.style.display = 'none';
            });
            list.appendChild(item);
        });
        list.style.display = arr.length ? 'block' : 'none';
    }catch(e){
        console.error(e);
        list.style.display = 'none';
    }
}
document.querySelectorAll('.suggest-btn').forEach(b=>{
    b.addEventListener('click', ()=> openSuggest(b.dataset.target));
});
['origin','destination'].forEach(id=>{
    const el = document.getElementById(id);
    el.addEventListener('keydown', e=>{ if(e.key === 'Enter') openSuggest(id); });
});

// ==================== 立即出发 ====================
async function runRoute() {
    // if (routingInFlight) return;
    // routingInFlight = true;
    // let routingInFlight;
    // try {
        clearRoute(); //
        const city = ($('#city').value || '').trim();

        function clearPoint(el) {
            delete el.dataset.lng;
            delete el.dataset.lat;
            delete el.dataset.boundValue;
        }

        function readCached(el) {
            // 只有当“当前值 == 上次命中的展示名(boundValue)”时才使用缓存
            const same = (el.value || '').trim() === (el.dataset.boundValue || '').trim();
            if (same && el.dataset.lng && el.dataset.lat) {
                return {lng: +el.dataset.lng, lat: +el.dataset.lat, name: el.dataset.boundValue || el.value};
            }
            // 值变了，缓存失效
            clearPoint(el);
            return null;
        }

        function setPointOnInput(el, p) {
            const lng = Number(p.longitude);
            const lat = Number(p.latitude);
            if (Number.isNaN(lng) || Number.isNaN(lat)) throw new Error('invalid coords');
            const boundValue = p.displayName || el.value; // 选中后展示的名称
            el.dataset.lng = String(lng);
            el.dataset.lat = String(lat);
            el.dataset.boundValue = boundValue; // 绑定当前展示名，用于下次校验
            el.value = boundValue;
            return {lng, lat, name: boundValue};
        }

        const qss = (o) => {
            const p = new URLSearchParams();
            Object.entries(o).forEach(([k, v]) => {
                if (v !== undefined && v !== null && `${v}`.trim() !== '') p.set(k, v);
            });
            return `?${p.toString()}`;
        };

        async function fetchSuggestions(address, cityHint) {
            const tryOnce = async (addr, cityOpt) => {
                const r = await fetch(`/api/v1/map/suggestions${qss({address: addr, city: cityOpt})}`);
                if (!r.ok) throw new Error('suggestions failed');
                return r.json();
            };
            let arr = await tryOnce(address, cityHint);
            if (!Array.isArray(arr) || !arr.length) {
                if (cityHint) arr = await tryOnce(address, undefined); // 城市命中为空则全国兜底
            }
            return arr ?? [];
        }

        async function ensurePoint(inputId) {
            const el = document.getElementById(inputId);
            const cached = readCached(el);
            if (cached) return cached;

            const addr = (el.value || '').trim();
            if (!addr) throw new Error('empty input');

            const arr = await fetchSuggestions(addr, city || undefined);
            if (!arr.length) throw new Error('no suggestion');
            const p = arr[0]; // 仍取第一条；需要手选的话这里改成弹出候选
            return setPointOnInput(el, p);
        }

        let o, d;
        try {
            o = await ensurePoint('origin');
            d = await ensurePoint('destination');
        } catch (e) {
            alert('请先选择有效的起点/终点');
            return;
        }

        const qs = new URLSearchParams({
            type: currentMode,
            origin: `${o.lat},${o.lng}`,
            destination: `${d.lat},${d.lng}`
        });
        if (city) qs.set('city', city);

        // 请求路线
        let data;
        try {
            const res = await fetch(`/api/v1/map/route?${qs.toString()}`);
            if (!res.ok) throw new Error('route failed: ' + res.status);
            data = await res.json();
        } catch (e) {
            console.error(e);
            alert('获取路线失败');
            return;
        }

        // 摘要卡（可选）
        ['car', 'transit', 'walk'].forEach(p => $(`#${p}Card`) && ($(`#${p}Card`).hidden = true));

        function putCard(idPrefix, sum) {
            if (!sum || !$(`#${idPrefix}Card`)) return;
            $(`#${idPrefix}Card`).hidden = false;
            $(`#${idPrefix}Time`).textContent = fmtMin(sum.duration);
            $(`#${idPrefix}Dist`).textContent = km(sum.distance);
        }

        putCard('car', data.car_summary);
        putCard('transit', data.transit_summary);
        putCard('walk', data.walk_summary);

        // 解析 + 渲染
        renderRoute(data);
        document.getElementById('summary').hidden = false;
        document.getElementById('steps').hidden = false;
        lastQuerySig = currentSig();
    // } finally {
    //     routingInFlight = false;
    // }
}

$('#btnGo').addEventListener('click', () => runRoute());

// ==================== 解析 & 渲染 ====================
function renderRoute(data){
    const route = data.main?.route;
    if (!route) { alert('没有可用的路线'); return; }

    // ---------- 驾车/步行/骑行 ----------
    function parseDrivingLike(route) {
        const path = route?.paths?.[0];
        if (!path) return null;

        let raw = path.polyline;
        if (!raw) raw = (path.steps || []).map(s => s.polyline).filter(Boolean).join(';');

        const coords = polylineToWgsCoords(raw);
        const stepTexts = (path.steps || []).map((s, i) => ({
            idx: i + 1,
            text: s.instruction || s.road || '',
            dist: +s.distance || 0,
            dur:  +s.duration || 0
        }));

        const stepFirstPoints = (path.steps || [])
            .map(s => s.polyline?.split(';')[0])
            .filter(Boolean)
            .map(p => {
                const [lng, lat] = p.split(',').map(Number);
                const [x, y] = gcj02towgs84(lng, lat);
                return [y, x];
            });

        return {
            segments: [{ type: 'drive', coords }],
            stepTexts,
            stepFirstPoints
        };
    }

    // ---------- 公交（含步行/地铁等） ----------
    function parseTransit(route) {
        const t = route?.transits?.[0];
        if (!t) return null;

        const segments = [];
        const stepTexts = [];
        const stepFirstPoints = [];
        let lastEndCoord = null;

        (t.segments || []).forEach((seg, segIdx) => {
            // 步行：你的返回里 walking 是数组
            const walkArr = Array.isArray(seg.walking) ? seg.walking : (seg.walking ? [seg.walking] : []);
            walkArr.forEach(w => {
                let wRaw = '';
                if (Array.isArray(w.steps) && w.steps.length) {
                    wRaw = w.steps.map(s => s.polyline).filter(Boolean).join(';');
                }
                if (!wRaw && w.polyline) wRaw = w.polyline;

                let wCoords = polylineToWgsCoords(wRaw);

                // 没坐标 → 兜底直线：优先 origin/destination，否则 lastEnd -> nextStart
                if (!wCoords.length) {
                    const o = locStrToWgs(w.origin);
                    const d = locStrToWgs(w.destination);
                    if (o && d && (o[0] !== d[0] || o[1] !== d[1])) {
                        wCoords = [o, d];
                    } else {
                        let nextStart = null;
                        const nextSeg = (t.segments || [])[segIdx + 1];
                        if (nextSeg?.bus?.buslines?.length) {
                            const firstLine = nextSeg.bus.buslines[0];
                            const pl = polylineToWgsCoords(firstLine.polyline);
                            if (pl.length) nextStart = pl[0];
                        } else {
                            const rArr = Array.isArray(nextSeg?.railway) ? nextSeg.railway : [];
                            if (rArr[0]?.polyline) {
                                const pl = polylineToWgsCoords(rArr[0].polyline);
                                if (pl.length) nextStart = pl[0];
                            } else if (Array.isArray(nextSeg?.entrance) && nextSeg.entrance[0]?.location) {
                                nextStart = locStrToWgs(nextSeg.entrance[0].location);
                            }
                        }
                        if (lastEndCoord && nextStart) wCoords = [lastEndCoord, nextStart];
                    }
                }

                if (wCoords.length) {
                    segments.push({ type: 'walk', coords: wCoords });
                    stepFirstPoints.push(wCoords[0]);
                    lastEndCoord = wCoords[wCoords.length - 1];
                }

                stepTexts.push({
                    idx: stepTexts.length + 1,
                    text: '步行',
                    dist: +(w.distance || 0),
                    dur:  +(w.duration || 0)
                });
            });

            // 公交/地铁（buslines）
            const b = seg.bus;
            if (b?.buslines?.length) {
                b.buslines.forEach(line => {
                    const bCoords = polylineToWgsCoords(line.polyline);
                    if (bCoords.length) {
                        segments.push({ type: 'bus', coords: bCoords, name: line.name || '公交' });
                        stepTexts.push({
                            idx: stepTexts.length + 1,
                            text: `${line.name || '公交'}（${line.departure_stop?.name || ''} → ${line.arrival_stop?.name || ''}）`,
                            dist: +(line.distance || 0),
                            dur:  +(line.duration || 0)
                        });
                        stepFirstPoints.push(bCoords[0]);
                        lastEndCoord = bCoords[bCoords.length - 1];
                    }
                });
            }

            // railway 也可能是数组
            const rArr = Array.isArray(seg.railway) ? seg.railway : (seg.railway ? [seg.railway] : []);
            rArr.forEach(r => {
                if (!r?.polyline) return;
                const rCoords = polylineToWgsCoords(r.polyline);
                if (!rCoords.length) return;
                segments.push({ type: 'rail', coords: rCoords, name: r.name || '地铁' });
                stepTexts.push({
                    idx: stepTexts.length + 1,
                    text: `${r.name || '地铁'}（${r.departure_stop?.name || ''} → ${r.arrival_stop?.name || ''}）`,
                    dist: +(r.distance || 0),
                    dur:  +(r.duration || 0)
                });
                stepFirstPoints.push(rCoords[0]);
                lastEndCoord = rCoords[rCoords.length - 1];
            });

            if (!lastEndCoord && Array.isArray(seg.exit) && seg.exit[0]?.location) {
                lastEndCoord = locStrToWgs(seg.exit[0].location);
            }
        });

        if (!segments.length) return null;
        return { segments, stepTexts, stepFirstPoints };
    }

    // 选择解析器
    let parsed = (currentMode === 'transit')
        ? parseTransit(route)
        : parseDrivingLike(route);

    if (!parsed) parsed = parseDrivingLike(route) || parseTransit(route);
    if (!parsed) { alert('没有可用的路线'); return; }

    // ========== 渲染 ==========
    clearRoute();

    // 分段画线：步行灰虚线、地铁橙点划线、公交/驾车蓝色
    const bounds = [];
    parsed.segments.forEach(seg => {
        if (!seg.coords?.length) return;
        const style = (seg.type === 'walk')
            ? { weight: 5, opacity: 0.95, color: '#6b7280', dashArray: '8,6' }
            : (seg.type === 'rail')
                ? { weight: 5, opacity: 0.95, color: '#f59e0b', dashArray: '2,8' }
                : { weight: 6, opacity: 0.95, color: '#1a73e8' };
        const pl = L.polyline(seg.coords, style).addTo(map);
        routeLayers.push(pl);
        seg.coords.forEach(c => bounds.push(c));
    });
    if (bounds.length) map.fitBounds(L.latLngBounds(bounds));

    // 步骤小圆点（红色）
    (parsed.stepFirstPoints || []).forEach(p => {
        if (!p) return;
        const dot = L.circleMarker(p, {
            pane: 'stepsPane', radius: 4, weight: 2,
            color: '#ffffff', fillColor: '#ff3b30', fillOpacity: 1
        }).addTo(map);
        dot.bringToFront();
        stepDots.push(dot);
    });

    // 左侧步骤面板
    const stepsEl = $('#steps'); if (stepsEl) {
        stepsEl.innerHTML = '';
        (parsed.stepTexts || []).forEach(s => {
            const dist = s.dist ? (s.dist/1000).toFixed(1) + ' 公里' : '';
            const dur  = s.dur  ? Math.round(s.dur/60) + '分钟' : '';
            const el = document.createElement('div');
            el.className = 'step';
            el.innerHTML = `<div>${s.idx}. ${s.text}</div>
                      <small>${[dist, dur].filter(Boolean).join(' · ')}</small>`;
            stepsEl.appendChild(el);
        });
    }
}

function readLatestPlanDraft() {
    try {
        const raw = localStorage.getItem(PLAN_CACHE_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch {
        return null;
    }
}

function getAuthToken() {
    return localStorage.getItem('nav_jwt') || sessionStorage.getItem('nav_jwt') || null;
}

function clearAuthToken() {
    localStorage.removeItem('nav_jwt');
    sessionStorage.removeItem('nav_jwt');
}

async function refreshMapAccessToken() {
    try {
        const resp = await fetch('/auth/refresh', {
            method: 'POST',
            credentials: 'include'
        });
        const data = await resp.json();
        if (!resp.ok || !data?.token) {
            clearAuthToken();
            return null;
        }
        clearAuthToken();
        sessionStorage.setItem('nav_jwt', data.token);
        return data.token;
    } catch {
        clearAuthToken();
        return null;
    }
}

function isMapLoggedIn() {
    return !!getAuthToken();
}

async function mapAuthFetch(url, options = {}) {
    const token = getAuthToken();
    const headers = new Headers(options.headers || {});
    if (token) headers.set('Authorization', token.startsWith('Bearer ') ? token : `Bearer ${token}`);
    if (!headers.has('Content-Type') && options.method && options.method !== 'GET') {
        headers.set('Content-Type', 'application/json');
    }
    let resp = await fetch(url, { ...options, headers, credentials: 'include' });
    if (resp.status === 401) {
        const refreshedToken = await refreshMapAccessToken();
        if (refreshedToken) {
            const retryHeaders = new Headers(options.headers || {});
            retryHeaders.set('Authorization', `Bearer ${refreshedToken}`);
            if (!retryHeaders.has('Content-Type') && options.method && options.method !== 'GET') {
                retryHeaders.set('Content-Type', 'application/json');
            }
            resp = await fetch(url, { ...options, headers: retryHeaders, credentials: 'include' });
        }
    }
    return resp;
}

function haversineMeters(a, b) {
    const toRad = deg => deg * Math.PI / 180;
    const r = 6371000;
    const dLat = toRad((b.lat || 0) - (a.lat || 0));
    const dLng = toRad((b.lng || 0) - (a.lng || 0));
    const lat1 = toRad(a.lat || 0);
    const lat2 = toRad(b.lat || 0);
    const x = Math.sin(dLat / 2) ** 2
        + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
    return 2 * r * Math.asin(Math.sqrt(x));
}

async function geocodeCityCenter(cityHint) {
    const city = (cityHint || '').trim();
    if (!city) return null;
    if (cityCenterCache.has(city)) return cityCenterCache.get(city);

    const url = new URL('/api/v1/map/geocode', location.origin);
    url.searchParams.set('address', city);
    const res = await fetch(url);
    if (!res.ok) return null;

    const data = await res.json();
    const coords = data?.features?.[0]?.geometry?.coordinates;
    if (!Array.isArray(coords) || coords.length < 2) return null;

    const center = { lng: Number(coords[0]), lat: Number(coords[1]) };
    cityCenterCache.set(city, center);
    return center;
}

function buildDayPoints(day, fallbackCity) {
    const points = [];
    const hotelPoint = day?.hotel ? {
        role: 'hotel',
        name: day.hotel.name || 'Hotel',
        address: day.hotel.addressLine || '',
        city: day.hotel.city || fallbackCity || '',
        lat: toOptionalNumber(day.hotel.latitude),
        lng: toOptionalNumber(day.hotel.longitude)
    } : null;

    if (hotelPoint) {
        points.push(hotelPoint);
    }

    (Array.isArray(day?.stops) ? day.stops : []).forEach((stop, index) => {
        points.push({
            role: 'stop',
            order: index + 1,
            name: stop.name || `Stop ${index + 1}`,
            category: stop.category || stop.type || '',
            address: stop.addressLine || '',
            city: stop.city || fallbackCity || '',
            lat: toOptionalNumber(stop.latitude),
            lng: toOptionalNumber(stop.longitude)
        });
    });

    if (hotelPoint && points.length > 1) {
        points.push({
            ...hotelPoint,
            role: 'hotel_return'
        });
    }
    return points;
}

function buildPointQuery(point) {
    const address = getPointAddressValue(point);
    const name = (point.name || '').trim();
    const raw = [name, address]
        .filter(Boolean)
        .join(', ')
        .trim();

    const seen = new Set();
    const parts = raw
        .split(',')
        .map(part => part.trim())
        .filter(Boolean)
        .filter(part => {
            const key = part.toLowerCase();
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
        });

    // Keep the place name and the first specific address segments, while trimming duplicated tails.
    return parts.slice(0, 6).join(', ');
}

function getPointAddressValue(point) {
    return String(point?.address || point?.addressLine || '').trim();
}

function sanitizePointName(name) {
    return String(name || '')
        .replace(/\([^)]*\)/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
}

function buildPointNameVariants(name) {
    const variants = [];
    const pushVariant = value => {
        const normalized = String(value || '').trim();
        if (normalized && !variants.includes(normalized)) variants.push(normalized);
    };

    const base = sanitizePointName(name);
    pushVariant(name);
    pushVariant(base);
    const acronymMatch = String(name || '').match(/\(([^)]+)\)/);
    if (acronymMatch && acronymMatch[1]) {
        pushVariant(acronymMatch[1]);
        pushVariant([base, acronymMatch[1]].filter(Boolean).join(' '));
    }

    if (/\bmarket\b/i.test(base) && !/\bmarkets\b/i.test(base)) {
        pushVariant(base.replace(/\bmarket\b/i, 'Markets'));
    }
    if (/\bmarkets\b/i.test(base)) {
        pushVariant(base.replace(/\bmarkets\b/i, 'Market'));
    }
    if (/\bfood court\b/i.test(base)) {
        pushVariant(base.replace(/\bfood court\b/ig, '').replace(/\s+/g, ' ').trim());
    }
    const businessSuffixCleaned = cleanBusinessSuffix(base);
    if (businessSuffixCleaned) {
        pushVariant(businessSuffixCleaned);
    }

    return variants;
}

function cleanBusinessSuffix(name) {
    const cleaned = String(name || '')
        .replace(/\b(food court|shopping centre|shopping center|sports centre|sports center|visitor centre|visitor center)\b/ig, ' ')
        .replace(/\s+/g, ' ')
        .trim();
    return cleaned && cleaned !== String(name || '').trim() ? cleaned : '';
}

function buildAddressOnlyQuery(point) {
    const raw = getPointAddressValue(point);
    if (!raw) return '';

    const seen = new Set();
    const parts = raw
        .split(',')
        .map(part => part.trim())
        .filter(Boolean)
        .filter(part => {
            const key = part.toLowerCase();
            if (seen.has(key)) return false;
            seen.add(key);
            return true;
        });

    const normalizedParts = [];
    const normalizedSeen = new Set();
    parts.forEach(part => {
        const compact = normalizeText(part).replace(/\baustralia\b/g, '').trim();
        if (!compact || normalizedSeen.has(compact)) return;
        normalizedSeen.add(compact);
        normalizedParts.push(part);
    });

    return normalizedParts.slice(0, 5).join(', ');
}

function normalizeText(value) {
    return String(value || '')
        .toLowerCase()
        .replace(/[^a-z0-9\s]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
}

function normalizeCityHintForGeocode(cityHint) {
    const raw = String(cityHint || '').trim();
    if (!raw) return '';

    const normalized = raw
        .replace(/\bcentral business district\b/ig, ' ')
        .replace(/\bcity centre\b/ig, ' ')
        .replace(/\bcity center\b/ig, ' ')
        .replace(/\bcbd\b/ig, ' ')
        .replace(/\bdowntown\b/ig, ' ')
        .replace(/\bcity\b$/ig, ' ')
        .replace(/\s+/g, ' ')
        .trim();

    return normalized || raw;
}

function buildCityHintVariants(cityHint) {
    const variants = [];
    const pushVariant = value => {
        const normalized = String(value || '').trim();
        if (normalized && !variants.includes(normalized)) variants.push(normalized);
    };

    pushVariant(cityHint);
    pushVariant(normalizeCityHintForGeocode(cityHint));
    return variants;
}

function extractPostcode(text) {
    const match = String(text || '').match(/\b(\d{4})\b/);
    return match ? match[1] : '';
}

function isAreaLandmarkName(name) {
    const text = String(name || '');
    if (/\b(food court|shopping centre|shopping center)\b/i.test(text)) return false;
    return /\b(harbour|harbor|bay|beach|square|quay|waterfront|foreshore|precinct|district|parklands|botanic gardens|botanical gardens|domain|reserve)\b/i.test(text);
}

function hasCommercialMismatch(pointName, text) {
    const normalizedPointName = normalizeText(pointName);
    const normalizedText = normalizeText(text);
    const commercialTokens = [
        'hotel', 'inn', 'plaza', 'parkroyal', 'crowne', 'novotel',
        'ibis', 'marina', 'resort', 'suite', 'suites', 'hostel',
        'apartment', 'apartments', 'mall', 'shopping', 'casino'
    ];

    return commercialTokens.some(token =>
        normalizedText.includes(token) && !normalizedPointName.includes(token)
    );
}

function hasCategoryMismatch(pointName, text) {
    const normalizedPointName = normalizeText(pointName);
    const normalizedText = normalizeText(text);
    const categoryTokens = [
        'food court', 'shopping centre', 'shopping center', 'sports centre', 'sports center'
    ];

    return categoryTokens.some(token =>
        normalizedText.includes(token) && !normalizedPointName.includes(token)
    );
}

function hasProxyMismatch(pointName, text) {
    const normalizedPointName = normalizeText(pointName);
    const normalizedText = normalizeText(text);
    const proxyTokens = [
        'wharf', 'ferry', 'terminal', 'sports centre', 'sports center',
        'offramp', 'cycleway', 'car park', 'parking', 'station'
    ];

    return proxyTokens.some(token =>
        normalizedText.includes(token) && !normalizedPointName.includes(token)
    );
}

function isCommercialFeature(props = {}, text = '') {
    const resultType = String(props.result_type || '').toLowerCase();
    const normalizedText = normalizeText(text || props.name || props.formatted || '');
    return resultType === 'commercial'
        || resultType === 'accommodation'
        || /\b(hotel|inn|plaza|parkroyal|crowne|novotel|ibis|marina|resort|suite|suites|hostel|apartment|apartments|mall|shopping|casino)\b/.test(normalizedText);
}

function distanceBetweenCoords(a, b) {
    return haversineMeters({ lat: a.lat, lng: a.lng }, { lat: b.lat, lng: b.lng });
}

function buildAreaLandmarkRepresentative(ranked, point) {
    const pointName = normalizeText(point?.name || '');
    const usable = ranked.filter(item => {
        const props = item.feature?.properties || {};
        const text = featureText(item.feature);
        const exactish = pointName && text.includes(pointName);
        return exactish && !isCommercialFeature(props, text) && !hasProxyMismatch(point?.name || '', text);
    });

    if (!usable.length) return null;

    const cluster = usable
        .filter(item => distanceBetweenCoords(usable[0], item) <= 2500)
        .slice(0, 4);

    if (cluster.length === 1) {
        const first = cluster[0];
        return {
            name: first.feature?.properties?.formatted || point?.name || '',
            lat: first.lat,
            lng: first.lng,
            raw: first.feature,
            source: 'geocode-area'
        };
    }

    const lat = cluster.reduce((sum, item) => sum + item.lat, 0) / cluster.length;
    const lng = cluster.reduce((sum, item) => sum + item.lng, 0) / cluster.length;
    return {
        name: point?.name || cluster[0].feature?.properties?.formatted || '',
        lat,
        lng,
        raw: cluster[0].feature,
        source: 'geocode-area'
    };
}

function featureText(feature) {
    const props = feature?.properties || {};
    return normalizeText([
        props.name,
        props.address_line1,
        props.address_line2,
        props.street,
        props.suburb,
        props.district,
        props.city,
        props.county,
        props.state,
        props.postcode,
        props.formatted
    ].filter(Boolean).join(' '));
}

function candidateText(candidate) {
    return normalizeText([
        candidate?.displayName,
        candidate?.name,
        candidate?.formatted,
        candidate?.address
    ].filter(Boolean).join(' '));
}

function hasStrongNameMatch(pointName, text) {
    const normalizedName = normalizeText(pointName);
    if (!normalizedName) return true;
    const tokens = normalizedName.split(' ').filter(token => token.length > 2);
    if (!tokens.length) return true;
    const matched = tokens.filter(token => text.includes(token));
    return matched.length >= Math.min(tokens.length, 2);
}

function hasAddressConfidence(point, text, postcodeValue = '') {
    const rawAddress = getPointAddressValue(point);
    const address = normalizeText(rawAddress);
    if (!address) return false;

    const postcode = extractPostcode(rawAddress);
    const hasPostcode = postcode && String(postcodeValue || '').trim() === postcode;
    const numberMatch = rawAddress.match(/\b(\d+[a-zA-Z]?)\b/);
    const streetNumber = numberMatch ? numberMatch[1].toLowerCase() : '';
    const hasStreetNumber = streetNumber ? text.includes(streetNumber) : false;
    const addressTokens = address
        .split(' ')
        .map(token => token.trim())
        .filter(token => token.length > 2 && !/^\d+[a-zA-Z]?$/.test(token));
    const matchedTokens = addressTokens.filter(token => text.includes(token));

    const streetTypeRegex = /\b(street|st|road|rd|avenue|ave|lane|ln|drive|dr|boulevard|blvd|place|pl|court|ct|way|terrace|tce)\b/i;
    const hasStreetType = streetTypeRegex.test(rawAddress);
    const streetNameMatches = matchedTokens.filter(token => !streetTypeRegex.test(token)).length;
    const genericAddressMatch = streetNameMatches >= 2 || (hasStreetType && streetNameMatches >= 1);

    return Boolean(
        (hasStreetNumber && genericAddressMatch) ||
        (hasPostcode && genericAddressMatch) ||
        (hasPostcode && matchedTokens.length >= 2)
    );
}

function buildGeocodeQueries(point, cityHint) {
    const queries = [];
    const name = String(point?.name || '').trim();
    const nameVariants = buildPointNameVariants(name);
    const cleanName = nameVariants[1] || sanitizePointName(name);
    const address = getPointAddressValue(point);
    const addressOnly = buildAddressOnlyQuery(point);
    const city = String(cityHint || '').trim();
    const normalizedCity = normalizeCityHintForGeocode(cityHint);
    const suburbMatch = address.match(/([A-Za-z]+(?:\s+[A-Za-z]+){0,2})\s+(?:VIC|NSW|QLD|ACT|WA|SA|TAS|NT)\s+\d{4}\b/i);
    const suburb = suburbMatch ? suburbMatch[1] : '';
    const hasStreetNumber = /\b\d+[a-zA-Z]?\b/.test(address);
    const hasStreetAndPostcode = /\b(street|road|avenue|lane|place|drive)\b/i.test(address) && /\b\d{4}\b/.test(address);
    const isAreaStyleName = /\b(precinct|district|area|mall|markets?|harbour|harbor|bay|square|quay|waterfront|parklands|park|gardens|garden|domain|reserve)\b/i.test(name);
    const areaLandmark = isAreaLandmarkName(name);
    const isStrongPoiName = /\b(wharves|museum|gallery|galleries|art|modern art|gardens|parklands|botanic|lookout|bridge|market|mall|zoo|westfield|aquarium|stadium|casino|theatre|center|centre)\b/i.test(name);
    const genericBusinessPoi = /\b(food court|shopping centre|shopping center)\b/i.test(name);

    const nameOnlyQueries = [];
    nameVariants.forEach(variant => {
        nameOnlyQueries.push(variant);
        nameOnlyQueries.push([variant, city].filter(Boolean).join(', '));
        if (normalizedCity && normalizedCity !== city) {
            nameOnlyQueries.push([variant, normalizedCity].filter(Boolean).join(', '));
        }
        if (suburb) {
            nameOnlyQueries.push([variant, suburb, city].filter(Boolean).join(', '));
            if (normalizedCity && normalizedCity !== city) {
                nameOnlyQueries.push([variant, suburb, normalizedCity].filter(Boolean).join(', '));
            }
        }
    });

    const preferAddressFirst = (!areaLandmark && !isStrongPoiName && (hasStreetNumber || hasStreetAndPostcode || isAreaStyleName))
        || (genericBusinessPoi && !!addressOnly);
    const orderedQueries = areaLandmark
        ? nameOnlyQueries
        : genericBusinessPoi
        ? [
            addressOnly,
            buildPointQuery(point),
            cleanName ? [cleanName, city].filter(Boolean).join(', ') : '',
            ...nameOnlyQueries
        ]
        : preferAddressFirst
        ? [
            addressOnly,
            buildPointQuery(point),
            ...nameOnlyQueries
        ]
        : [
            ...nameOnlyQueries,
            addressOnly,
            buildPointQuery(point)
        ];

    orderedQueries.forEach(query => {
        const value = String(query || '').trim();
        if (value && !queries.includes(value)) queries.push(value);
    });

    return queries;
}

function shouldBypassStoredCoords(point) {
    const pointName = String(point?.name || '').trim();
    const addressValue = getPointAddressValue(point);
    if (isStrongPoiForGeocodeRefresh(pointName)) return true;
    if (isAreaLandmarkName(pointName)) return true;
    if (isParkStopForGeocodeRefresh(point)) return true;
    if (!pointName || !addressValue) return false;

    const genericBusinessPoi = /(food court|shopping centre|shopping center)/i.test(pointName);
    const hasStreetNumber = /\b\d{1,6}[a-z]?\b/i.test(addressValue);
    const hasPostcode = /\b\d{4,6}\b/.test(addressValue);
    return genericBusinessPoi && (hasStreetNumber || hasPostcode);
}

function isStrongPoiForGeocodeRefresh(name) {
    return /\b(museum|gallery|goma|qagoma|planetarium|sciencentre|aquarium|shrine|memorial|monument)\b/i.test(String(name || ''));
}

function isParkStopForGeocodeRefresh(point) {
    const name = String(point?.name || '');
    const category = String(point?.category || point?.type || '').toLowerCase();
    if (!/\bpark\b/i.test(name)) return false;
    if (/\b(car park|parking|park royal|parkroyal|park hotel|hotel|restaurant|cafe|bar)\b/i.test(name)) return false;
    return /\b(park|nature|attraction|outdoor|beach)\b/.test(category) || /\b(national park|reserve|gardens?|lookout|beach)\b/i.test(name);
}

function hasUsablePlanCoordinate(point, cityCenter = null) {
    const lat = Number(point?.lat);
    const lng = Number(point?.lng);
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) return false;
    if (lat === 0 && lng === 0) return false;
    if (cityCenter && haversineMeters(cityCenter, { lat, lng }) > 120000) return false;
    return true;
}

async function fetchPlanGeocodeFeatures(queryValue, cityHint) {
    const geocodeUrl = new URL('/api/v1/map/geocode', location.origin);
    geocodeUrl.searchParams.set('address', queryValue);
    if (cityHint) geocodeUrl.searchParams.set('city', cityHint);

    const geocodeRes = await fetch(geocodeUrl);
    if (!geocodeRes.ok) {
        return {
            ok: false,
            status: geocodeRes.status,
            features: []
        };
    }

    const geo = await geocodeRes.json();
    return {
        ok: true,
        status: geocodeRes.status,
        features: Array.isArray(geo?.features) ? geo.features : []
    };
}

async function fetchAreaLandmarkCenter(point, cityHint, cityCenter = null) {
    const areaName = String(point?.name || '').trim();
    if (!isAreaLandmarkName(areaName)) return null;

    const cityVariants = buildCityHintVariants(cityHint);
    const directCities = cityVariants.length ? cityVariants : [''];

    for (const city of directCities) {
        const result = await fetchPlanGeocodeFeatures(areaName, city);
        if (!result.ok || !Array.isArray(result.features) || !result.features.length) continue;
        const feature = result.features[0];
        const coords = feature?.geometry?.coordinates || [];
        const lat = Number(coords[1]);
        const lng = Number(coords[0]);
        if (Number.isNaN(lat) || Number.isNaN(lng)) continue;
        if (cityCenter && haversineMeters(cityCenter, { lat, lng }) > 120000) {
            window.__planPointDebug = Array.isArray(window.__planPointDebug) ? window.__planPointDebug : [];
            window.__planPointDebug.push(
                `${areaName}: rejected area-center outside city range (${Math.round(haversineMeters(cityCenter, { lat, lng }) / 1000)} km)`
            );
            continue;
        }
        return {
            name: feature?.properties?.formatted || areaName,
            lat,
            lng,
            raw: feature,
            source: 'geocode-area-center',
            debugQuery: areaName,
            debugCityHint: city
        };
    }
    return null;
}

function rankGeocodeCandidate(feature, point, cityCenter) {
    const coords = feature?.geometry?.coordinates || [];
    const lat = Number(coords[1]);
    const lng = Number(coords[0]);
    if (Number.isNaN(lat) || Number.isNaN(lng)) return null;

    const text = featureText(feature);
    const props = feature?.properties || {};
    const pointName = normalizeText(point?.name || '');
    const address = normalizeText(point?.address || '');
    const postcode = extractPostcode(point?.address || '');
    const suburbToken = address.includes('south bank')
        ? 'south bank'
        : (address.includes('west end') ? 'west end' : (address.includes('brisbane city') ? 'brisbane city' : ''));
    const distance = cityCenter ? haversineMeters(cityCenter, { lat, lng }) : 0;
    const strongNameMatch = hasStrongNameMatch(pointName, text);
    const addressConfidence = hasAddressConfidence(point, text, props.postcode);
    const areaLandmark = isAreaLandmarkName(point?.name || '');
    const commercialMismatch = areaLandmark && hasCommercialMismatch(pointName, text);
    const categoryMismatch = hasCategoryMismatch(pointName, text);
    const proxyMismatch = hasProxyMismatch(pointName, text);
    const resultType = String(props.result_type || '').toLowerCase();
    const importance = Number(props.rank?.importance || 0);
    const confidence = Number(props.rank?.confidence || 0);
    let score = 0;
    if (pointName && text.includes(pointName)) score += 5000;
    if (pointName && props.name && normalizeText(props.name) === pointName) score += 2500;
    if (postcode && String(props.postcode || '').trim() === postcode) score += 1200;
    if (suburbToken && text.includes(suburbToken)) score += 800;
    if (address) {
        const addressTokens = address.split(' ').filter(token => token.length > 2);
        const matchedTokens = addressTokens.filter(token => text.includes(token)).length;
        score += matchedTokens * 80;
    }
    score += importance * 1800;
    score += confidence * 400;
    if (areaLandmark && (resultType === 'amenity' || resultType === 'tourism' || resultType === 'entertainment')) {
        score += 350;
    }
    if (areaLandmark && resultType === 'street') {
        score -= 1800;
    }
    if (areaLandmark && props.result_type === 'amenity' && !hasCommercialMismatch(pointName, props.name || '')) {
        score += 300;
    }
    if (commercialMismatch) score -= 2800;
    if (categoryMismatch) score -= 2200;
    if (proxyMismatch) score -= areaLandmark ? 2600 : 1800;
    score -= distance / 250;

    return { feature, lat, lng, distance, score, strongNameMatch, addressConfidence, commercialMismatch, categoryMismatch, proxyMismatch };
}

async function geocodePlanPoint(point, cityHint) {
    const cityVariants = buildCityHintVariants(cityHint);
    const cityCenter = await geocodeCityCenter(cityVariants[cityVariants.length - 1] || cityHint);

    if (hasUsablePlanCoordinate(point, cityCenter)) {
        const stored = {
            name: point.name || '',
            lat: Number(point.lat),
            lng: Number(point.lng),
            raw: null,
            source: 'stored'
        };
        window.__planPointDebug = Array.isArray(window.__planPointDebug) ? window.__planPointDebug : [];
        window.__planPointDebug.push(
            `${point.name}: used stored coordinates (${stored.lat.toFixed(6)}, ${stored.lng.toFixed(6)}) [stored]`
        );
        return stored;
    }

    if (shouldBypassStoredCoords(point)) {
        window.__planPointDebug = Array.isArray(window.__planPointDebug) ? window.__planPointDebug : [];
        window.__planPointDebug.push(
            `${point.name}: bypassed stored coordinates in favor of detailed address geocoding`
        );
    }

    const queries = buildGeocodeQueries(point, cityHint);
    const query = queries[queries.length - 1];
    const addressOnly = buildAddressOnlyQuery(point);
    if (!query) throw new Error('Missing waypoint query');

    const cacheKey = `v12__${queries.join(' || ')}__${cityVariants.join(' || ')}`;
    if (geocodeCache.has(cacheKey)) {
        const cached = geocodeCache.get(cacheKey);
        window.__planPointDebug = Array.isArray(window.__planPointDebug) ? window.__planPointDebug : [];
        window.__planPointDebug.push(
            `${point.name}: ${query} -> ${cached.name || ''} (${Number(cached.lat).toFixed(6)}, ${Number(cached.lng).toFixed(6)}) [cache/${cached.source || 'unknown'}]`
        );
        return cached;
    }
    const areaLandmarkCenter = await fetchAreaLandmarkCenter(point, cityHint, cityCenter);
    if (areaLandmarkCenter) {
        window.__planPointDebug = Array.isArray(window.__planPointDebug) ? window.__planPointDebug : [];
        window.__planPointDebug.push(
            `${point.name}: ${areaLandmarkCenter.debugQuery} [city=${areaLandmarkCenter.debugCityHint || cityHint || '-'}] -> ${areaLandmarkCenter.name} (${Number(areaLandmarkCenter.lat).toFixed(6)}, ${Number(areaLandmarkCenter.lng).toFixed(6)}) [${areaLandmarkCenter.source}]`
        );
        geocodeCache.set(cacheKey, areaLandmarkCenter);
        return areaLandmarkCenter;
    }

    let found = null;
    for (const queryVariant of queries) {
        for (const cityVariant of cityVariants.length ? cityVariants : ['']) {
            let geocodeResult = await fetchPlanGeocodeFeatures(queryVariant, cityVariant);
            let usedQuery = queryVariant;
            let usedCityHint = cityVariant;

            if (!geocodeResult.ok && (geocodeResult.status === 422 || geocodeResult.status === 400)) {
                if (addressOnly && addressOnly !== queryVariant) {
                    window.__planPointDebug = Array.isArray(window.__planPointDebug) ? window.__planPointDebug : [];
                    window.__planPointDebug.push(`${point.name}: ${queryVariant} [city=${cityVariant || '-'}] -> invalid geocode query (${geocodeResult.status}), fallback to detailed address`);
                    geocodeResult = await fetchPlanGeocodeFeatures(addressOnly, cityVariant);
                    usedQuery = addressOnly;
                } else {
                    window.__planPointDebug = Array.isArray(window.__planPointDebug) ? window.__planPointDebug : [];
                    window.__planPointDebug.push(`${point.name}: ${queryVariant} [city=${cityVariant || '-'}] -> skipped invalid geocode query (${geocodeResult.status})`);
                    continue;
                }
            }
            if (!geocodeResult.ok) {
                throw new Error(`Geocode failed (${geocodeResult.status})`);
            }

            const features = geocodeResult.features;
            if (!features.length) continue;

            const ranked = features
                .map(feature => rankGeocodeCandidate(feature, point, cityCenter))
                .filter(Boolean)
                .sort((a, b) => {
                    if (b.score !== a.score) return b.score - a.score;
                    return a.distance - b.distance;
                });

            const areaRepresentative = isAreaLandmarkName(point?.name || '')
                ? buildAreaLandmarkRepresentative(ranked, point)
                : null;
            if (areaRepresentative && (!cityCenter || haversineMeters(cityCenter, areaRepresentative) <= 120000)) {
                found = {
                    ...areaRepresentative,
                    debugQuery: usedQuery,
                    debugCityHint: usedCityHint
                };
                break;
            }

            const best = ranked[0];
            if (best && !best.proxyMismatch && (best.strongNameMatch || best.addressConfidence) && (!cityCenter || best.distance <= 120000)) {
                found = {
                    name: best.feature?.properties?.formatted || point.name || usedQuery,
                    lat: best.lat,
                    lng: best.lng,
                    raw: best.feature,
                    source: 'geocode',
                    debugQuery: usedQuery,
                    debugCityHint: usedCityHint
                };
                break;
            }
        }
        if (found) break;
    }

    if (!found) {
        const suggestionQueries = [query, addressOnly, buildPointQuery(point)]
            .map(value => String(value || '').trim())
            .filter((value, index, arr) => value && arr.indexOf(value) === index);

        for (const suggestionQuery of suggestionQueries) {
            for (const cityVariant of cityVariants.length ? cityVariants : ['']) {
                const url = new URL('/api/v1/map/suggestions', location.origin);
                url.searchParams.set('address', suggestionQuery);
                if (cityVariant) url.searchParams.set('city', cityVariant);

                const res = await fetch(url);
                if (!res.ok) {
                    if ((res.status === 422 || res.status === 400) && addressOnly && suggestionQuery !== addressOnly) {
                        window.__planPointDebug = Array.isArray(window.__planPointDebug) ? window.__planPointDebug : [];
                        window.__planPointDebug.push(`${point.name}: ${suggestionQuery} [city=${cityVariant || '-'}] -> invalid suggestions query (${res.status}), fallback to detailed address`);
                        continue;
                    }
                    throw new Error(`Suggestions failed (${res.status})`);
                }

                const arr = await res.json();
                const candidates = Array.isArray(arr) ? arr : [];
                if (!candidates.length) continue;

                const rankedCandidates = candidates
                    .map(item => {
                        const lat = Number(item.lat);
                        const lng = Number(item.lng);
                        const text = candidateText(item);
                        const distance = cityCenter ? haversineMeters(cityCenter, { lat, lng }) : 0;
                        const strongNameMatch = hasStrongNameMatch(point?.name || '', text);
                        const addressConfidence = hasAddressConfidence(point, text, extractPostcode(text));
                        const commercialMismatch = isAreaLandmarkName(point?.name || '') && hasCommercialMismatch(point?.name || '', text);
                        const categoryMismatch = hasCategoryMismatch(point?.name || '', text);
                        const proxyMismatch = hasProxyMismatch(point?.name || '', text);
                        let score = 0;
                        if (strongNameMatch) score += 3000;
                        if (normalizeText(point?.name || '') && text.includes(normalizeText(point?.name || ''))) score += 2000;
                        const postcode = extractPostcode(getPointAddressValue(point));
                        if (postcode && text.includes(postcode)) score += 1000;
                        if (addressConfidence) score += 1200;
                        if (commercialMismatch) score -= 2800;
                        if (categoryMismatch) score -= 2200;
                        if (proxyMismatch) score -= 2200;
                        score -= distance / 250;
                        return { item, lat, lng, distance, score, strongNameMatch, addressConfidence, commercialMismatch, categoryMismatch, proxyMismatch };
                    })
                    .filter(item => item && !Number.isNaN(item.lat) && !Number.isNaN(item.lng))
                    .sort((a, b) => {
                        if (b.score !== a.score) return b.score - a.score;
                        return a.distance - b.distance;
                    });

                const first = rankedCandidates[0];
                if (!first) continue;
                if (!first.strongNameMatch && !first.addressConfidence) continue;
                if (cityCenter && first.distance > 120000) continue;

                found = {
                    name: first.item.name || first.item.displayName || point.name || suggestionQuery,
                    lat: Number(first.lat),
                    lng: Number(first.lng),
                    raw: first.item,
                    source: 'suggestion',
                    debugQuery: suggestionQuery,
                    debugCityHint: cityVariant
                };
                break;
            }
            if (found) break;
        }
        if (!found) throw new Error(`No location found for ${addressOnly || query}`);
    }

    const sourceName = found?.raw?.properties?.formatted || found?.raw?.name || found?.name || '';
    window.__planPointDebug = Array.isArray(window.__planPointDebug) ? window.__planPointDebug : [];
    window.__planPointDebug.push(
        `${point.name}: ${(found.debugQuery || query)} [city=${found.debugCityHint || cityHint || '-'}] -> ${sourceName} (${Number(found.lat).toFixed(6)}, ${Number(found.lng).toFixed(6)}) [${found.source || 'unknown'}]`
    );
    geocodeCache.set(cacheKey, found);
    return found;
}

async function fetchPlanSegmentRoute(originPoint, destinationPoint, cityHint) {
    const origin = await geocodePlanPoint(originPoint, cityHint);
    const destination = await geocodePlanPoint(destinationPoint, cityHint);
    const straightDistance = haversineMeters(origin, destination);
    if (straightDistance > 120000) {
        const originName = originPoint?.name || origin?.name || 'Previous stop';
        const destinationName = destinationPoint?.name || destination?.name || 'Next stop';
        const originCoords = `${Number(origin.lat).toFixed(6)},${Number(origin.lng).toFixed(6)}`;
        const destinationCoords = `${Number(destination.lat).toFixed(6)},${Number(destination.lng).toFixed(6)}`;
        throw new Error(`Skipped an overlong segment: ${originName} -> ${destinationName} (${Math.round(straightDistance / 1000)} km, ${originCoords} -> ${destinationCoords})`);
    }
    const cacheKey = `${currentMode}__${origin.lat},${origin.lng}__${destination.lat},${destination.lng}__${cityHint || ''}`;
    if (planRouteCache.has(cacheKey)) return planRouteCache.get(cacheKey);

    const qs = new URLSearchParams({
        type: currentMode,
        origin: `${origin.lat},${origin.lng}`,
        destination: `${destination.lat},${destination.lng}`
    });
    if (cityHint) qs.set('city', cityHint);

    const res = await fetch(`/api/v1/map/route?${qs.toString()}`);
    if (!res.ok) throw new Error(`Route failed (${res.status})`);

    const data = await res.json();
    const payload = { data, origin, destination };
    planRouteCache.set(cacheKey, payload);
    return payload;
}

async function fetchPlanSegmentRouteByMode(originPoint, destinationPoint, cityHint, mode) {
    const previousMode = currentMode;
    currentMode = mode;
    try {
        return await fetchPlanSegmentRoute(originPoint, destinationPoint, cityHint);
    } finally {
        currentMode = previousMode;
    }
}

function parsePlanRoute(data) {
    const feature = data?.main?.features?.[0];
    if (!feature) return null;

    const props = feature.properties || {};
    const geom = feature.geometry || {};

    function toLeafletCoords(geometry) {
        if (!geometry || !geometry.type || !geometry.coordinates) return [];
        if (geometry.type === 'LineString') {
            return [(geometry.coordinates || [])
                .filter(pt => Array.isArray(pt) && pt.length >= 2)
                .map(([lng, lat]) => [lat, lng])];
        }
        if (geometry.type === 'MultiLineString') {
            return (geometry.coordinates || []).map(line =>
                (line || [])
                    .filter(pt => Array.isArray(pt) && pt.length >= 2)
                    .map(([lng, lat]) => [lat, lng])
            ).filter(line => line.length);
        }
        return [];
    }

    const segments = toLeafletCoords(geom);
    if (!segments.length) return null;

    const steps = props.legs?.[0]?.steps || [];
    return {
        segments,
        steps: steps.map((step, index) => ({
            idx: index + 1,
            text: step.instruction?.text || step.instruction || `Step ${index + 1}`,
            dist: Number(step.distance || 0),
            dur: Number(step.time || step.duration || 0)
        }))
    };
}

function hideSummaryCards() {
    ['car', 'transit', 'walk'].forEach(key => {
        const card = document.getElementById(`${key}Card`);
        if (card) card.hidden = true;
    });
}

function getSegmentMode(segment) {
    return segment?.mode || currentMode;
}

function formatSegmentMode(mode) {
    if (mode === 'walk') return 'Walk';
    if (mode === 'drive') return 'Drive';
    return 'Transit';
}

function closeWalkConfirmModal(result = false) {
    const modal = document.getElementById('walkConfirmModal');
    if (modal) modal.style.display = 'none';
    if (walkConfirmResolver) {
        const resolve = walkConfirmResolver;
        walkConfirmResolver = null;
        resolve(result);
    }
}

function openWalkConfirmModal(message) {
    const modal = document.getElementById('walkConfirmModal');
    const messageEl = document.getElementById('walkConfirmMessage');
    if (!modal) return Promise.resolve(window.confirm(message));
    if (messageEl) messageEl.textContent = message;
    modal.style.display = 'flex';
    return new Promise(resolve => {
        walkConfirmResolver = resolve;
    });
}

function renderSummaryCards(segment) {
    hideSummaryCards();
    const data = segment?.data;
    const mode = getSegmentMode(segment);
    const modeToCard = mode === 'transit' ? 'transit' : (mode === 'walk' ? 'walk' : 'car');
    const summary = modeToCard === 'transit'
        ? data?.transit_summary
        : (modeToCard === 'walk' ? data?.walk_summary : data?.car_summary);
    const card = document.getElementById(`${modeToCard}Card`);
    if (!card || !summary) return;

    card.hidden = false;
    document.getElementById(`${modeToCard}Time`).textContent = fmtMin(Number(summary.durationSeconds || 0));
    document.getElementById(`${modeToCard}Dist`).textContent = km(Number(summary.distanceMeters || 0));
    document.getElementById('summary').hidden = false;
}

function renderPlanSteps(parsed, titleText) {
    const stepsEl = $('#steps');
    if (!stepsEl) return;

    const header = titleText
        ? `<div class="step"><div><strong>${titleText}</strong></div><small>Route steps</small></div>`
        : '<div class="step"><div><strong>Route steps</strong></div></div>';
    const rows = (parsed?.steps || []).map(step => {
        const dist = step.dist ? `${(step.dist / 1000).toFixed(1)} km` : '';
        const dur = step.dur ? `${Math.round(step.dur / 60)} min` : '';
        return `<div class="step"><div>${step.idx}. ${step.text}</div><small>${[dist, dur].filter(Boolean).join(' · ')}</small></div>`;
    }).join('');

    stepsEl.innerHTML = header + (rows || '<div class="step"><div>No detailed steps available for this route segment.</div></div>');
    stepsEl.hidden = false;
}

function renderEmptyPlanSteps() {
    const stepsEl = $('#steps');
    if (!stepsEl) return;
    stepsEl.innerHTML = '<div class="step"><div><strong>Route details</strong></div><small>Click a route segment on the map or in the route list to view turn-by-turn steps.</small></div>';
    stepsEl.hidden = true;
}

function buildSegmentPopupHtml(segment) {
    const steps = (segment?.parsed?.steps || []).map(step => {
        const dist = step.dist ? `${(step.dist / 1000).toFixed(1)} km` : '';
        const dur = step.dur ? `${Math.round(step.dur / 60)} min` : '';
        return `<div style="margin-top:6px;"><strong>${step.idx}.</strong> ${step.text}<br><small>${[dist, dur].filter(Boolean).join(' · ')}</small></div>`;
    }).join('');

    return `
        <div style="max-width:320px;">
            <div style="font-weight:700; margin-bottom:6px;">${segment.originPoint.name} -> ${segment.destinationPoint.name} (${formatSegmentMode(getSegmentMode(segment))})</div>
            ${steps || '<div>No detailed steps available.</div>'}
        </div>
    `;
}

function renderPlanMarkers(points) {
    const coordinateGroups = new Map();
    points.forEach(point => {
        if (!point?.coords) return;
        const lat = Number(point.coords.lat);
        const lng = Number(point.coords.lng);
        if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
        const key = `${lat.toFixed(5)}__${lng.toFixed(5)}`;
        if (!coordinateGroups.has(key)) coordinateGroups.set(key, []);
        coordinateGroups.get(key).push(point);
    });

    points.forEach((point, index) => {
        if (!point?.coords) return;
        const lat = Number(point.coords.lat);
        const lng = Number(point.coords.lng);
        if (!Number.isFinite(lat) || !Number.isFinite(lng)) return;
        const key = `${lat.toFixed(5)}__${lng.toFixed(5)}`;
        const group = coordinateGroups.get(key) || [point];
        const groupIndex = group.indexOf(point);
        const offset = markerOverlapOffset(groupIndex, group.length);
        const marker = L.marker([lat + offset.lat, lng + offset.lng]).addTo(map);
        const label = point.role === 'hotel' || point.role === 'hotel_return'
            ? `Hotel: ${point.name}`
            : `${point.order || index}. ${point.name}`;
        const sharedLabel = group.length > 1
            ? `<div style="margin-top:8px;"><strong>Same mapped location:</strong><br>${group.map(item => escapeHtml(item.role === 'hotel' || item.role === 'hotel_return'
                ? `Hotel: ${item.name}`
                : `${item.order || ''}. ${item.name}`)).join('<br>')}</div>`
            : '';
        marker.bindPopup(`${escapeHtml(label)}${sharedLabel}`);
        routeMarkers.push(marker);
    });
}

function escapeHtml(value) {
    return String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

function markerOverlapOffset(index, total) {
    if (!Number.isFinite(index) || !Number.isFinite(total) || total <= 1) {
        return { lat: 0, lng: 0 };
    }
    const angle = (2 * Math.PI * index) / total;
    const radius = 0.00007;
    return {
        lat: Math.sin(angle) * radius,
        lng: Math.cos(angle) * radius
    };
}

function updatePlanRouteStyles() {
    routeLayers.forEach(layer => {
        if (!layer?.setStyle) return;
        const isActive = layer.__segmentIndex === activeSegmentIndex;
        layer.setStyle({
            color: layer.__segmentColor || '#1a73e8',
            weight: isActive ? 7 : 5,
            opacity: isActive ? 0.96 : 0.7,
            dashArray: layer.__segmentMode === 'walk' ? '8,6' : null
        });
    });
}

function uniquePoints(points) {
    const seen = new Set();
    return points.filter(point => {
        const key = `${point.name}__${point.address}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });
}

function renderPlanSegmentList() {
    const host = document.getElementById('planSegments');
    const status = document.getElementById('planDayStatus');
    const debugEl = document.getElementById('planPointDebug');
    const debugToggleBtn = document.getElementById('btnTogglePlanDebug');
    if (!host) return;
    const detail = (window.__planSegmentErrors || []).join('\n');
    const debugText = Array.isArray(window.__planPointDebug)
        ? window.__planPointDebug.join('\n')
        : '';

    if (debugEl) {
        debugEl.style.display = debugText && planDebugVisible ? 'block' : 'none';
        debugEl.textContent = debugText;
    }
    if (debugToggleBtn) {
        debugToggleBtn.hidden = !debugText;
        debugToggleBtn.textContent = planDebugVisible ? 'Hide Debug' : 'Show Debug';
    }

    if (!activePlanSegments.length) {
        if (status) {
            status.style.display = detail ? 'block' : 'none';
            status.textContent = detail || '';
        }
        host.innerHTML = `<div class="muted">No route segments could be built for this day.</div>${detail ? `<div class="map-plan-error">${detail}</div>` : ''}`;
        document.getElementById('summary').hidden = true;
        document.getElementById('steps').hidden = true;
        clearRoute();
        return;
    }

    if (status) {
        status.style.display = detail ? 'block' : 'none';
        status.textContent = detail || '';
    }

    host.innerHTML = activePlanSegments.map((segment, index) => {
        const segmentMode = getSegmentMode(segment);
        const summary = segmentMode === 'transit'
            ? segment.data?.transit_summary
            : (segmentMode === 'walk' ? segment.data?.walk_summary : segment.data?.car_summary);
        const meta = summary
            ? `${km(Number(summary.distanceMeters || 0))} · ${fmtMin(Number(summary.durationSeconds || 0))}`
            : 'No summary';
        const targetMode = segmentMode === 'walk' ? activePlanBaseMode : 'walk';
        const actionLabel = segmentMode === 'walk' ? formatSegmentMode(activePlanBaseMode) : 'Walk';
        const modeAction = targetMode === segmentMode
            ? ''
            : `<button class="plan-segment-action" type="button" data-action="switch-mode" data-mode="${targetMode}" data-segment-index="${index}">${actionLabel}</button>`;
        return `
            <div class="plan-segment ${index === activeSegmentIndex ? 'active' : ''}" data-segment-index="${index}">
                <div class="plan-segment-head">
                    <div class="plan-segment-title">${index + 1}. ${segment.originPoint.name} -> ${segment.destinationPoint.name} <span class="plan-segment-mode">(${formatSegmentMode(segmentMode)})</span></div>
                    ${modeAction}
                </div>
                <div class="plan-segment-meta">${meta}</div>
            </div>
        `;
    }).join('');

    host.querySelectorAll('.plan-segment').forEach(node => {
        node.addEventListener('click', () => selectPlanSegment(Number(node.dataset.segmentIndex)));
    });
    host.querySelectorAll('[data-action="switch-mode"]').forEach(node => {
        node.addEventListener('click', async event => {
            event.stopPropagation();
            await switchPlanSegmentMode(Number(node.dataset.segmentIndex), node.dataset.mode || 'walk');
        });
    });
}

function buildDraftFromPlanDetail(detail) {
    const plan = detail?.plan || {};
    const daysPlan = Array.isArray(detail?.daysPlan) ? detail.daysPlan : [];

    return {
        planId: plan.id || null,
        title: plan.title || '',
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
        daysPlan: daysPlan.map(day => ({
            dayIndex: day.dayIndex || 0,
            hotel: day.hotel ? {
                id: day.hotel.id || null,
                name: day.hotel.name || '',
                addressLine: day.hotel.addressLine || '',
                suburb: day.hotel.district || '',
                city: day.hotel.city || '',
                state: '',
                postcode: '',
                country: day.hotel.country || '',
                latitude: day.hotel.latitude ?? null,
                longitude: day.hotel.longitude ?? null
            } : null,
            stops: Array.isArray(day.stops) ? day.stops
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
                    stayMinutes: stop.dwellMinutes ?? null
                })) : [],
            note: day.note || ''
        }))
    };
}

function renderSidebarShell() {
    const sidebar = document.getElementById('sidebar');
    if (!sidebar) return;
    sidebar.innerHTML = `
        <div class="map-sidebar">
            <div class="map-sidebar-section">
                <div class="map-sidebar-section-head">
                    <div class="map-sidebar-title">Account</div>
                    <button id="mapGeneratePlanBtn" class="secondary map-generate-plan-btn" type="button">Plan Generator</button>
                </div>
                <div class="map-user-card">
                    <div id="mapSidebarUserName" class="map-user-name">Guest</div>
                    <div id="mapSidebarUserMeta" class="map-user-meta">Log in to switch between saved plans on the map.</div>
                    <div class="map-sidebar-actions">
                        <button id="mapSidebarLoginBtn" class="secondary" type="button">Log In</button>
                        <button id="mapSidebarRefreshBtn" class="secondary" type="button">Refresh Plans</button>
                    </div>
                </div>
            </div>
            <div id="mapPlanViewerSection" class="map-sidebar-section">
                <div class="map-sidebar-title">Plan Viewer</div>
                <div class="map-plan-tabs">
                    <button id="mapHistoryTab" class="map-plan-tab active" type="button">History</button>
                    <button id="mapFavoritesTab" class="map-plan-tab" type="button">Favorites</button>
                </div>
                <div id="mapPlanViewerBody">
                    <div id="mapCurrentPlanTitle" class="map-current-plan">No plan selected</div>
                    <div id="mapPlanList" class="map-plan-list" hidden></div>
                </div>
            </div>
            <div id="mapPlanRoutesMount" class="map-sidebar-section"></div>
        </div>
    `;
}

function setCurrentPlanTitle(text) {
    const titleEl = document.getElementById('mapCurrentPlanTitle');
    if (titleEl) titleEl.textContent = text || 'No plan selected';
}

function updatePlanListVisibility() {
    const host = document.getElementById('mapPlanList');
    const titleEl = document.getElementById('mapCurrentPlanTitle');
    const bodyEl = document.getElementById('mapPlanViewerBody');
    const sectionEl = document.getElementById('mapPlanViewerSection');
    if (!host) return;
    host.hidden = !activePlanListOpen;
    host.style.display = activePlanListOpen ? 'flex' : 'none';
    if (titleEl) titleEl.hidden = !activePlanListOpen;
    if (bodyEl) bodyEl.classList.toggle('collapsed', !activePlanListOpen);
    if (sectionEl) sectionEl.classList.toggle('collapsed', !activePlanListOpen);
}

function updatePlanTabState() {
    document.getElementById('mapHistoryTab')?.classList.toggle('active', activePlanListType === 'history');
    document.getElementById('mapFavoritesTab')?.classList.toggle('active', activePlanListType === 'favorites');
    updatePlanListVisibility();
}

function renderMapAccountState() {
    const nameEl = document.getElementById('mapSidebarUserName');
    const metaEl = document.getElementById('mapSidebarUserMeta');
    const loginBtn = document.getElementById('mapSidebarLoginBtn');
    const profile = typeof getCurrentAuthProfile === 'function' ? getCurrentAuthProfile() : window.currentAuthProfile;

    if (!nameEl || !metaEl || !loginBtn) return;

    if (!profile?.loggedIn) {
        nameEl.textContent = 'Guest';
        metaEl.textContent = 'Log in to switch between saved plans on the map.';
        loginBtn.textContent = 'Log In';
        return;
    }

    const detailParts = [profile.email, profile.phone].filter(Boolean);
    nameEl.textContent = profile.username || 'User';
    metaEl.textContent = detailParts.length
        ? `Signed in as ${detailParts.join(' · ')}`
        : 'Signed in. Use the plan list below to switch the map view.';
    loginBtn.textContent = 'Log Out';
}

function renderMapPlanItems(items) {
    const host = document.getElementById('mapPlanList');
    if (!host) return;
    updatePlanListVisibility();

    if (!items.length) {
        host.innerHTML = `<div class="map-plan-empty">${isMapLoggedIn() ? 'No plans found in this list.' : 'Generate a plan first, or log in to browse history and favorites.'}</div>`;
        return;
    }

    host.innerHTML = items.map(item => `
        <button class="map-plan-item ${item.id === activeSelectedPlanId ? 'active' : ''}" type="button" data-plan-id="${item.id}">
            <div class="map-plan-item-title">${item.title || 'Untitled Plan'}</div>
            <div class="map-plan-item-meta">${item.city || '-'} · ${item.days || '-'} days · ${item.favorite ? 'Favorite' : 'Plan'}</div>
        </button>
    `).join('');

    host.querySelectorAll('.map-plan-item').forEach(node => {
        node.addEventListener('click', () => {
            loadPlanFromServer(Number(node.dataset.planId));
        });
    });
}

async function fetchPlansForMap(type) {
    if (!isMapLoggedIn()) return [];
    const url = type === 'favorites' ? '/api/v1/plans/me/favorites?page=0&size=50' : '/api/v1/plans/me?page=0&size=50';
    const res = await mapAuthFetch(url);
    if (!res.ok) throw new Error(`Failed to load plans (${res.status})`);
    const data = await res.json();
    return Array.isArray(data?.items) ? data.items : [];
}

async function renderMapPlanLibrary() {
    const host = document.getElementById('mapPlanList');
    if (!host) return;
    updatePlanListVisibility();

    if (!isMapLoggedIn()) {
        renderMapPlanItems([]);
        return;
    }

    host.innerHTML = '<div class="map-plan-empty">Loading plans...</div>';
    try {
        const items = await fetchPlansForMap(activePlanListType);
        renderMapPlanItems(items);
    } catch (error) {
        host.innerHTML = `<div class="map-plan-error">${error.message || 'Failed to load plans'}</div>`;
    }
}

async function loadPlanFromServer(planId) {
    if (!planId) return;
    const res = await mapAuthFetch(`/api/v1/plans/${planId}`);
    if (!res.ok) throw new Error(`Failed to load plan (${res.status})`);
    const detail = await res.json();
    activeSelectedPlanId = planId;
    activePlanDraft = buildDraftFromPlanDetail(detail);
    activePlanDayIndex = 0;
    setCurrentPlanTitle(detail?.plan?.title || 'Untitled Plan');
    localStorage.setItem('latest_trip_plan_id', String(planId));
    localStorage.setItem(PLAN_CACHE_KEY, JSON.stringify(activePlanDraft));
    populatePlanDaySelect();
    await loadPlanRoutesForDay(0, true);
    await renderMapPlanLibrary();
}

function initMapUiText() {
    document.title = 'Trip Map';
    const textMap = [
        ['#btnGo', 'Start Route'],
        ['#btnLoadPlanRoutes', 'Reload Plan'],
        ['#btnTogglePlanDebug', 'Show Debug'],
        ['#planRoutesHint', 'Day-by-day route segments from your latest generated trip plan.'],
        ['#planPrevDay', 'Prev'],
        ['#planNextDay', 'Next'],
        ['#btnOpenAuth', 'Log In / Register'],
        ['#gotoRecent', 'Recent Routes'],
        ['#btnLogout', 'Log Out']
    ];

    textMap.forEach(([selector, value]) => {
        const el = document.querySelector(selector);
        if (el) el.textContent = value;
    });

    const placeholders = [
        ['#origin', 'Choose origin'],
        ['#destination', 'Choose destination'],
        ['#city', 'City (optional, e.g. Brisbane)'],
        ['#loginId', 'Username / Email / Phone'],
        ['#loginPassword', 'Password'],
        ['#regEmail', 'Email'],
        ['#regPhone', 'Phone number'],
        ['#regPassword', 'Password'],
        ['#regUsername', 'Username (optional)']
    ];

    placeholders.forEach(([selector, value]) => {
        const el = document.querySelector(selector);
        if (el) el.setAttribute('placeholder', value);
    });

    const sidebarHeader = document.querySelector('.sidebar-header');
    if (sidebarHeader) sidebarHeader.textContent = 'Menu';
    const menuRecent = document.getElementById('menu-recent');
    if (menuRecent) menuRecent.textContent = 'Recent';
    const summaryTitle = document.querySelector('#summary .title');
    if (summaryTitle) summaryTitle.textContent = 'Route Summary';
    if (cityEl && !cityEl.value.trim()) cityEl.value = 'Brisbane';
}

function renderPlanSegmentsOnMap(selectedIndex) {
    clearRoute();
    const bounds = [];
    const palette = ['#1a73e8', '#e11d48', '#059669', '#7c3aed', '#ea580c'];

    activePlanSegments.forEach((segment, index) => {
        const color = palette[index % palette.length];
        const isActive = index === selectedIndex;
        const segmentMode = getSegmentMode(segment);
        (segment.parsed?.segments || []).forEach(coords => {
            const line = L.polyline(coords, {
                color,
                weight: isActive ? 7 : 5,
                opacity: isActive ? 0.96 : 0.7,
                dashArray: segmentMode === 'walk' ? '8,6' : null
            }).addTo(map);
            line.__segmentIndex = index;
            line.__segmentColor = color;
            line.__segmentMode = segmentMode;
            line.bindPopup(buildSegmentPopupHtml(segment), { maxWidth: 360 });
            line.on('click', () => {
                focusPlanSegment(index, { rerenderMap: false, openPopup: true, popupLayer: line });
            });
            line.on('popupopen', () => {
                focusPlanSegment(index, { rerenderMap: false });
            });
            routeLayers.push(line);
            coords.forEach(coord => bounds.push(coord));
        });
    });

    const points = uniquePoints(activePlanSegments.flatMap(segment => [segment.originPoint, segment.destinationPoint]));
    renderPlanMarkers(points);

    if (bounds.length) {
        map.fitBounds(L.latLngBounds(bounds), { padding: [24, 24] });
    }
}

function focusPlanSegment(index, options = {}) {
    const segment = activePlanSegments[index];
    if (!segment) return;
    const { rerenderMap = true, openPopup = false, popupLayer = null } = options;

    activeSegmentIndex = index;
    if (rerenderMap) {
        renderPlanSegmentsOnMap(index);
    } else {
        updatePlanRouteStyles();
    }
    renderPlanSegmentList();
    renderSummaryCards(segment);
    renderPlanSteps(segment.parsed, `${segment.originPoint.name} -> ${segment.destinationPoint.name} (${formatSegmentMode(getSegmentMode(segment))})`);
    if (openPopup && popupLayer?.openPopup) popupLayer.openPopup();
}

function selectPlanSegment(index) {
    focusPlanSegment(index, { rerenderMap: true });
}

async function switchPlanSegmentMode(index, nextMode) {
    const segment = activePlanSegments[index];
    if (!segment || getSegmentMode(segment) === nextMode) return;

    if (nextMode === 'walk') {
        const walkSeconds = Number(segment.data?.walk_summary?.durationSeconds || 0);
        if (walkSeconds > 1800) {
            const confirmed = await openWalkConfirmModal('Walking time is too long (>30min). Continue to view?');
            if (!confirmed) return;
        }
    }

    const cityHint = (segment.originPoint?.city || segment.destinationPoint?.city || activePlanDraft?.city || cityEl?.value || '').trim();
    try {
        const result = await fetchPlanSegmentRouteByMode(segment.originPoint, segment.destinationPoint, cityHint, nextMode);
        const parsed = parsePlanRoute(result.data);
        if (!parsed) throw new Error('No route available for this mode');
        activePlanSegments[index] = {
            ...segment,
            originPoint: { ...segment.originPoint, coords: result.origin },
            destinationPoint: { ...segment.destinationPoint, coords: result.destination },
            data: result.data,
            parsed,
            mode: nextMode
        };
        focusPlanSegment(index, { rerenderMap: true });
    } catch (error) {
        alert(error?.message || 'Failed to switch route mode');
    }
}

async function loadPlanRoutesForDay(dayIndex, forceRefresh = false) {
    if (!activePlanDraft) return;

    const days = Array.isArray(activePlanDraft.daysPlan) ? activePlanDraft.daysPlan : [];
    const day = days[dayIndex];
    const host = document.getElementById('planSegments');
    if (!day || !host) return;

    activePlanDayIndex = dayIndex;
    activePlanBaseMode = currentMode;
    host.innerHTML = '<div class="muted">Loading route segments...</div>';

    const cityHint = (day.hotel?.city || activePlanDraft.city || cityEl?.value || '').trim();
    if (cityEl && cityHint && !cityEl.value.trim()) cityEl.value = cityHint;

    const points = buildDayPoints(day, cityHint);
    if (points.length < 2) {
        activePlanSegments = [];
        activeSegmentIndex = -1;
        window.__planSegmentErrors = ['This day does not have enough points to build a route.'];
        window.__planPointDebug = [];
        renderPlanSegmentList();
        return;
    }

    window.__planPointDebug = [];
    const tasks = [];
    for (let index = 0; index < points.length - 1; index += 1) {
        const originPoint = points[index];
        const destinationPoint = points[index + 1];
        tasks.push((async () => {
            const result = forceRefresh
                ? await fetchPlanSegmentRoute(originPoint, destinationPoint, cityHint)
                : await fetchPlanSegmentRoute(originPoint, destinationPoint, cityHint);
            const parsed = parsePlanRoute(result.data);
            if (!parsed) return null;
            originPoint.coords = result.origin;
            destinationPoint.coords = result.destination;
            return { originPoint, destinationPoint, data: result.data, parsed, mode: currentMode };
        })());
    }

    const settled = await Promise.allSettled(tasks);
    window.__planSegmentErrors = settled
        .filter(item => item.status === 'rejected')
        .map(item => item.reason?.message || 'Unknown route loading error');
    activePlanSegments = settled
        .filter(item => item.status === 'fulfilled' && item.value)
        .map(item => item.value);

    activeSegmentIndex = activePlanSegments.length ? 0 : -1;
    renderPlanSegmentList();
    if (activePlanSegments.length) {
        renderPlanSegmentsOnMap(activeSegmentIndex);
        focusPlanSegment(activeSegmentIndex, { rerenderMap: false });
    }
}

function populatePlanDaySelect() {
    const select = document.getElementById('planDaySelect');
    const days = Array.isArray(activePlanDraft?.daysPlan) ? activePlanDraft.daysPlan : [];
    if (!select) return;

    select.innerHTML = days.map((day, index) =>
        `<option value="${index}">Day ${day.dayIndex || index + 1}</option>`
    ).join('');
    select.value = String(activePlanDayIndex);
}

async function loadLatestPlanRoutes() {
    activePlanDraft = readLatestPlanDraft();
    const host = document.getElementById('planSegments');
    if (!host) return;

    if (!activePlanDraft) {
        setCurrentPlanTitle('');
        host.innerHTML = '<div class="muted">No latest plan found. Generate one from the planner page first.</div>';
        return;
    }

    activeSelectedPlanId = Number(activePlanDraft.planId || localStorage.getItem('latest_trip_plan_id') || 0) || activeSelectedPlanId;
    setCurrentPlanTitle(activePlanDraft.title || (activeSelectedPlanId ? `Plan #${activeSelectedPlanId}` : 'Latest generated plan'));

    const useSelfDrive = localStorage.getItem(PLAN_DRIVE_KEY) === 'true';
    currentMode = useSelfDrive ? 'drive' : 'transit';
    document.querySelectorAll('.mode').forEach(btn => {
        const shouldBeActive =
            (currentMode === 'drive' && btn.dataset.mode === 'car')
            || btn.dataset.mode === currentMode;
        btn.classList.toggle('active', shouldBeActive);
    });

    populatePlanDaySelect();
    await loadPlanRoutesForDay(activePlanDayIndex);
}

function initPlanRouteControls() {
    renderSidebarShell();
    initMapUiText();
    renderMapAccountState();
    const mount = document.getElementById('mapPlanRoutesMount');
    const panel = document.getElementById('planRoutesPanel');
    if (mount && panel && panel.parentElement !== mount) {
        document.body.appendChild(panel);
    }
    const toolbar = document.querySelector('.plan-day-switch');
    if (toolbar && toolbar.parentElement !== document.body) {
        document.body.appendChild(toolbar);
    }
    if (panel && !document.getElementById('planPointDebug')) {
        const debug = document.createElement('div');
        debug.id = 'planPointDebug';
        debug.className = 'map-plan-debug';
        debug.style.display = 'none';
        const segments = document.getElementById('planSegments');
        if (segments) panel.insertBefore(debug, segments);
    }
    const select = document.getElementById('planDaySelect');
    if (select && !select.options.length) {
        select.innerHTML = '<option value="0">Day 1</option>';
        select.value = '0';
    }
    updatePlanTabState();
    renderEmptyPlanSteps();
    document.getElementById('btnLoadPlanRoutes')?.addEventListener('click', () => {
        loadLatestPlanRoutes();
    });
    document.getElementById('btnTogglePlanDebug')?.addEventListener('click', () => {
        planDebugVisible = !planDebugVisible;
        renderPlanSegmentList();
    });
    document.getElementById('closeWalkConfirm')?.addEventListener('click', () => closeWalkConfirmModal(false));
    document.getElementById('cancelWalkConfirm')?.addEventListener('click', () => closeWalkConfirmModal(false));
    document.getElementById('confirmWalkConfirm')?.addEventListener('click', () => closeWalkConfirmModal(true));
    document.getElementById('walkConfirmModal')?.addEventListener('click', event => {
        if (event.target?.id === 'walkConfirmModal') {
            closeWalkConfirmModal(false);
        }
    });
    document.getElementById('mapGeneratePlanBtn')?.addEventListener('click', () => {
        window.location.href = '/index.html';
    });
    document.getElementById('mapSidebarRefreshBtn')?.addEventListener('click', () => {
        renderMapAccountState();
        renderMapPlanLibrary();
        loadLatestPlanRoutes();
    });
    document.getElementById('mapSidebarLoginBtn')?.addEventListener('click', async () => {
        if (isMapLoggedIn()) {
            try {
                await fetch('/auth/logout', {
                    method: 'POST',
                    credentials: 'include'
                });
            } catch (_) {
            }
            clearAuthToken();
            activeSelectedPlanId = null;
            activePlanDraft = null;
            activePlanSegments = [];
            setCurrentPlanTitle('No plan selected');
            if (typeof renderUserLoggedOut === 'function') {
                renderUserLoggedOut();
            } else {
                renderMapAccountState();
            }
            renderMapPlanLibrary();
            populatePlanDaySelect();
            clearRoute();
        } else {
            document.getElementById('btnOpenAuth')?.click();
        }
    });
    document.getElementById('mapHistoryTab')?.addEventListener('click', () => {
        if (activePlanListType === 'history') {
            activePlanListOpen = !activePlanListOpen;
        } else {
            activePlanListType = 'history';
            activePlanListOpen = true;
        }
        updatePlanTabState();
        if (activePlanListOpen) renderMapPlanLibrary();
    });
    document.getElementById('mapFavoritesTab')?.addEventListener('click', () => {
        if (activePlanListType === 'favorites') {
            activePlanListOpen = !activePlanListOpen;
        } else {
            activePlanListType = 'favorites';
            activePlanListOpen = true;
        }
        updatePlanTabState();
        if (activePlanListOpen) renderMapPlanLibrary();
    });
    document.getElementById('planDaySelect')?.addEventListener('change', event => {
        loadPlanRoutesForDay(Number(event.target.value || 0));
    });
    document.getElementById('planPrevDay')?.addEventListener('click', () => {
        if (!activePlanDraft) return;
        const nextIndex = Math.max(0, activePlanDayIndex - 1);
        document.getElementById('planDaySelect').value = String(nextIndex);
        loadPlanRoutesForDay(nextIndex);
    });
    document.getElementById('planNextDay')?.addEventListener('click', () => {
        if (!activePlanDraft) return;
        const days = Array.isArray(activePlanDraft.daysPlan) ? activePlanDraft.daysPlan : [];
        const nextIndex = Math.min(days.length - 1, activePlanDayIndex + 1);
        document.getElementById('planDaySelect').value = String(nextIndex);
        loadPlanRoutesForDay(nextIndex);
    });

    if (readLatestPlanDraft()) {
        loadLatestPlanRoutes();
    }
    renderMapPlanLibrary();
}

initPlanRouteControls();
