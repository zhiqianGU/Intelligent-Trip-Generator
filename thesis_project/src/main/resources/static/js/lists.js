// ============== 基础 ==============
const auth = {
    get token() {
        const t = (typeof getToken === 'function' ? getToken() : (localStorage.getItem('token') || sessionStorage.getItem('token') || '')) || '';
        return t;
    },
    headers() {
        const h = { 'Content-Type': 'application/json' };
        const t = this.token;
        if (t) h['Authorization'] = t.startsWith('Bearer ') ? t : `Bearer ${t}`;
        return h;
    },
    ensure() { if (!this.token) { alert('请先登录'); return false; } return true; }
};


// ============== DOM 引用 ==============
const savedPanel = document.getElementById('savedPanel');
const listsContainer = document.getElementById('listsContainer');
const itemsContainer = document.getElementById('itemsContainer');
const savedListsView = document.getElementById('savedListsView');
const savedItemsView = document.getElementById('savedItemsView');

const savedBack = document.getElementById('savedBack');
const btnNewList = document.getElementById('btnNewList');
const btnBackToLists = document.getElementById('btnBackToLists');
const btnAddPlace = document.getElementById('btnAddPlace');

const editListName = document.getElementById('editListName');
const editListNote = document.getElementById('editListNote');
const btnSaveListMeta = document.getElementById('btnSaveListMeta');
const btnDeleteList = document.getElementById('btnDeleteList');

// 新建列表 modal
const newListModal = document.getElementById('newListModal');
const closeNewList = document.getElementById('closeNewList');
const newListName = document.getElementById('newListName');
const newListNote = document.getElementById('newListNote');
const createList = document.getElementById('createList');

// 添加地点 modal
const addPlaceModal = document.getElementById('addPlaceModal');
const closeAddPlace = document.getElementById('closeAddPlace');
const addPlaceAddress = document.getElementById('addPlaceAddress');
const btnFetchCandidates = document.getElementById('btnFetchCandidates');
const addPlaceCandidates = document.getElementById('addPlaceCandidates');
const addPlaceNote = document.getElementById('addPlaceNote');
const confirmAddPlace = document.getElementById('confirmAddPlace');

// 左侧边栏菜单
const menuSaved = document.getElementById('menu-saved');

// 当前上下文
let currentList = null;      // {id, name, note}
let currentCandidates = [];  // geocode 候选

// ============== 入口/事件绑定 ==============
menuSaved && menuSaved.addEventListener('click', async ()=>{
    if (!auth.ensure()) return;
    openSavedPanel();
    await loadLists();
});
savedBack && savedBack.addEventListener('click', ()=> savedPanel.style.display='none');

btnNewList && btnNewList.addEventListener('click', ()=>{
    newListName.value = ''; newListNote.value = '';
    newListModal.style.display = 'flex';
});
closeNewList && closeNewList.addEventListener('click', ()=> newListModal.style.display='none');
createList && createList.addEventListener('click', async ()=>{
    if (!auth.ensure()) return;
    const name = newListName.value.trim();
    const note = newListNote.value.trim();
    if (!name) { alert('请填写列表名称'); return; }
    await apiCreateList(name, note);
    newListModal.style.display='none';
    await loadLists();
});

btnBackToLists && btnBackToLists.addEventListener('click', ()=>{
    savedItemsView.style.display = 'none';
    savedListsView.style.display = 'block';
    currentList = null;
});
btnAddPlace && btnAddPlace.addEventListener('click', ()=>{
    // 预填：取顶部或面板目的地输入框
    const dst = document.getElementById('destination')?.value || document.getElementById('destination-panel')?.value || '';
    addPlaceAddress.value = dst;
    addPlaceCandidates.innerHTML = '';
    addPlaceNote.value = '';
    addPlaceModal.style.display = 'flex';
});

closeAddPlace && closeAddPlace.addEventListener('click', ()=> addPlaceModal.style.display='none');
btnFetchCandidates && btnFetchCandidates.addEventListener('click', fetchCandidates);
confirmAddPlace && confirmAddPlace.addEventListener('click', async ()=>{
    if (!auth.ensure()) return;
    if (!currentList) { alert('未选择列表'); return; }
    const address = addPlaceAddress.value.trim();
    if (!address) { alert('请输入地址'); return; }
    const idx = parseInt(addPlaceCandidates.value ?? '-1', 10);
    if (isNaN(idx) || idx < 0) { alert('请先搜索并选择候选'); return; }
    const note = addPlaceNote.value.trim();

    await apiAddPlaceByAddress(currentList.id, { address, selectedIndex: idx, note });
    addPlaceModal.style.display = 'none';
    await loadItems(currentList);
});

btnSaveListMeta && btnSaveListMeta.addEventListener('click', async ()=>{
    if (!auth.ensure() || !currentList) return;
    const name = editListName.value.trim();
    const note = editListNote.value.trim();
    if (!name) { alert('名称不能为空'); return; }
    await apiUpdateList(currentList.id, name, note);
    await loadLists(currentList.id); // 重新加载并停留当前列表
});
btnDeleteList && btnDeleteList.addEventListener('click', async ()=>{
    if (!auth.ensure() || !currentList) return;
    if (!confirm('确定删除该列表？该操作不可恢复。')) return;
    await apiDeleteList(currentList.id);
    currentList = null;
    await loadLists();
});

// ============== 打开/渲染 ==============
function openSavedPanel(){
    // 与 directions 共存时，抽屉覆盖在上面
    savedPanel.style.display = 'flex';
    savedListsView.style.display = 'block';
    savedItemsView.style.display = 'none';
}

async function loadLists(focusListId){
    listsContainer.innerHTML = '加载中…';
    try{
        const res = await fetch('/api/v1/user/lists', { headers: auth.headers() });
        if (!res.ok) throw new Error('lists failed');
        const lists = await res.json(); // 数组 [{id,name,note,createdAt,...}]
        renderLists(lists);

        // 如果指定了 focusListId，自动进入该列表
        if (focusListId) {
            const hit = lists.find(x => x.id === focusListId);
            if (hit) await enterList(hit);
        }
    }catch(e){
        console.error(e);
        listsContainer.innerHTML = '<div class="muted">加载失败</div>';
    }
}

function renderLists(lists){
    listsContainer.innerHTML = '';
    if (!lists.length){
        listsContainer.innerHTML = '<div class="muted">暂无列表，点击右上角“新建列表”。</div>';
        return;
    }
    lists.forEach(l=>{
        const row = document.createElement('div');
        row.className = 'list-item';
        row.innerHTML = `
      <div class="grow">
        <div class="title">${escapeHtml(l.name)}</div>
        <div class="subtitle">${escapeHtml(l.note || '')}</div>
      </div>
      <button class="secondary small">打开</button>
    `;
        row.querySelector('button').addEventListener('click', ()=> enterList(l));
        listsContainer.appendChild(row);
    });
}

async function enterList(list){
    currentList = list;
    savedListsView.style.display = 'none';
    savedItemsView.style.display = 'block';

    editListName.value = list.name || '';
    editListNote.value = list.note || '';
    await loadItems(list);
}

async function loadItems(list){
    itemsContainer.innerHTML = '加载中…';
    try{
        const res = await fetch(`/api/v1/user/lists/${list.id}/items`, { headers: auth.headers() });
        if (!res.ok) throw new Error('items failed');
        const items = await res.json(); // [{id, placeId, name, address, longitude, latitude, note, ...}]
        renderItems(items);
    }catch(e){
        console.error(e);
        itemsContainer.innerHTML = '<div class="muted">加载失败</div>';
    }
}

function renderItems(items){
    itemsContainer.innerHTML = '';
    if (!items.length){
        itemsContainer.innerHTML = '<div class="muted">该列表暂无地点，点击上方“向此列表添加地点”。</div>';
        return;
    }
    items.forEach(it=>{
        const row = document.createElement('div');
        row.className = 'list-item';
        row.innerHTML = `
      <div class="grow">
        <div class="title">${escapeHtml(it.name || it.address || '')}</div>
        <div class="subtitle">${escapeHtml(it.address || '')}</div>
        <div class="row">
          <input class="inline-input it-note" value="${escapeAttr(it.note || '')}" placeholder="备注（可选）"/>
          <button class="primary small btn-save-item">保存</button>
          <button class="danger small btn-del-item">删除</button>
        </div>
      </div>
    `;
        row.querySelector('.btn-save-item').addEventListener('click', async ()=>{
            const note = row.querySelector('.it-note').value.trim();
            await apiUpdateItem(currentList.id, it.id, note);
            // 可选：toast
        });
        row.querySelector('.btn-del-item').addEventListener('click', async ()=>{
            if (!confirm('删除该地点？')) return;
            await apiDeleteItem(currentList.id, it.id);
            await loadItems(currentList);
        });
        // 点击标题在地图上定位
        row.querySelector('.title').addEventListener('click', ()=>{
            if (typeof L !== 'undefined' && it.longitude && it.latitude) {
                const [x,y] = gcj02towgs84(it.longitude, it.latitude);
                const latLng = [y, x];
                try {
                    const marker = L.marker(latLng).addTo(window.map || map);
                    marker.bindPopup(it.name || it.address || '').openPopup();
                    (window.map || map).setView(latLng, 16);
                    // 临时标记3秒后移除
                    setTimeout(()=> marker.remove(), 3000);
                } catch {}
            }
        });
        itemsContainer.appendChild(row);
    });
}

// ============== API ==============


// === 列表 ===
async function apiCreateList(name, note){
    const res = await fetch('/api/v1/user/lists', {
        method:'POST',
        headers: auth.headers(),
        body: JSON.stringify({ listname: name, note })
    });
    if (!res.ok) throw new Error('create list failed');
    return res.json(); // 你的后端 POST 会返回 UserList
}

async function apiUpdateList(listId, name, note){
    const res = await fetch(`/api/v1/user/lists/${listId}`, {
        method:'PUT',                               // ✅ 你的接口是 PUT
        headers: auth.headers(),
        body: JSON.stringify({ listname: name, note })
    });
    if (!res.ok) throw new Error('update list failed');
}

async function apiDeleteList(listId){
    const res = await fetch(`/api/v1/user/lists/${listId}`, {
        method:'DELETE',
        headers: auth.headers()
    });
    if (!res.ok) throw new Error('delete list failed');
}

// === 列表项 ===
async function apiAddPlaceByAddress(listId, payload){
    // payload: { address, selectedIndex, note }
    const res = await fetch(`/api/v1/user/lists/${listId}/items/by-address`, {  // ✅ 走 by-address
        method:'POST',
        headers: auth.headers(),
        body: JSON.stringify(payload)
    });
    if (!res.ok) throw new Error('add place failed');
}

async function apiUpdateItem(listId, itemId, note){
    const res = await fetch(`/api/v1/user/lists/${listId}/items/${itemId}/note`, { // ✅ /note
        method:'PATCH',
        headers: auth.headers(),
        body: JSON.stringify({ note })
    });
    if (!res.ok) throw new Error('update item failed');
}

async function apiDeleteItem(listId, itemId){
    const res = await fetch(`/api/v1/user/lists/${listId}/items/${itemId}`, {
        method:'DELETE',
        headers: auth.headers()
    });
    if (!res.ok) throw new Error('delete item failed');
}


// ============== 候选获取（供“添加地点”用） ==============
async function fetchCandidates(){
    const address = addPlaceAddress.value.trim();
    if (!address){ alert('请输入地址'); return; }
    try{
        const r = await fetch(`/api/v1/map/suggestions?address=${encodeURIComponent(address)}`);
        if (!r.ok) throw new Error('suggestions failed');
        currentCandidates = await r.json(); // [{displayName, longitude, latitude, address, ...}]
        addPlaceCandidates.innerHTML = '';
        currentCandidates.forEach((c, i)=>{
            const opt = document.createElement('option');
            opt.value = String(i);
            opt.textContent = c.displayName;
            addPlaceCandidates.appendChild(opt);
        });
        if (!currentCandidates.length) alert('未找到候选结果');
    }catch(e){
        console.error(e); alert('候选获取失败');
    }
}

// ============== 工具 ==============
function escapeHtml(s){ return (s || '').replace(/[&<>"']/g, m => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[m])); }
function escapeAttr(s){ return escapeHtml(s).replace(/"/g,'&quot;'); }

// ============== 顶部“已保存/最近”按钮可选绑定（若你有这些菜单） ==============
document.getElementById('gotoSaved')?.addEventListener('click', async ()=>{
    if (!auth.ensure()) return;
    openSavedPanel(); await loadLists();
});
