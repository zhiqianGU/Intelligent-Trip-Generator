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
const map = L.map('map').setView([39.9092,116.3975], 12);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{
    attribution:'&copy; OpenStreetMap contributors'
}).addTo(map);

// 步骤小圆点放到更高的 pane
map.createPane('stepsPane');
map.getPane('stepsPane').style.zIndex = 650;

let routeLayers = [];   // 可能多段线
let stepDots = [];

function clearRoute(){
    routeLayers.forEach(l => map.removeLayer(l));
    routeLayers = [];
    stepDots.forEach(d => map.removeLayer(d));
    stepDots = [];
}

// ==================== 出行方式切换 ====================
let currentMode = 'car'; // car | transit | walk | bike(同步用walk)
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
        currentMode = (m === 'bike') ? 'walk' : m;

        const cityOk = (currentMode !== 'transit') || (cityEl && cityEl.value.trim());
        if (cityOk && lastQuerySig && lastQuerySig === currentSig()) {
            runRoute(); // 只有城市具备时才自动触发
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

        if (currentMode === 'transit' && !city) {
            alert('公交查询需要城市，例如：北京');
            return;
        }

        const qs = new URLSearchParams({
            type: currentMode,
            origin: `${o.lng},${o.lat}`,
            destination: `${d.lng},${d.lat}`
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
            segments: [{ type: 'car', coords }],
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
