/* ═══════════════════════ оболочка: меню «Данные», секции сайдбара, клавиши, старт ═══════════════════════ */
const IS_MAC = /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch (e) { return d; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) { } },
};

/* ── меню «+ Данные» ── */
const AddMenu = {
  init() {
    $('bAdd').onclick = e => { e.stopPropagation(); this.toggle(); };
    document.addEventListener('click', e => { if (!e.target.closest('.menu-wrap')) this.toggle(false); });
    $('addMenu').querySelectorAll('[data-add]').forEach(b => b.onclick = () => { this.toggle(false); this.run(b.dataset.add); });
  },
  toggle(on) { $('addMenu').classList.toggle('show', on ?? !$('addMenu').classList.contains('show')); },
  run(kind) {
    switch (kind) {
      case 'file': $('file').click(); break;
      case 'api': openApiModal(); break;
      case 'sql': Sql.open(''); break;
      case 'product': WS.demoProduct(); break;
      case 'demo': openSingleDemo(); break;
      case 'raw': openRawDemo(); break;
    }
  },
};

/** Демо несортированных данных: создаём сырой лог и сразу показываем план сортировки. */
async function openRawDemo() {
  loading(true, 'Генерирую сырой лог сайта…');
  try {
    const ds = await api('/demo/raw', {method: 'POST'});
    App.view('Graph'); S.focus = null;
    await WS.load(); await loadWorkspace(true);
    Structure.open(ds.id);
  } catch (e) { toast(e.message); } finally { loading(false); }
}

/* ── сворачиваемые секции сайдбара (data-fold="closed" — свёрнута по умолчанию) ── */
function initFolds() {
  document.querySelectorAll('#side .sec').forEach((sec, i) => {
    const h = sec.querySelector('h4'); if (!h) return;
    const key = 'ag.fold.' + (sec.id || h.textContent.trim().slice(0, 24) || i);
    const closed = store.get(key, sec.dataset.fold === 'closed' ? '1' : '0') === '1';
    sec.classList.add('foldable'); sec.classList.toggle('closed', closed);
    h.addEventListener('click', e => {
      if (e.target.closest('button,input,select,a')) return;
      const c = !sec.classList.contains('closed');
      sec.classList.toggle('closed', c); store.set(key, c ? '1' : '0');
    });
  });
}

/* ── горячие клавиши уровня приложения ── */
window.addEventListener('keydown', e => {
  if ((e.metaKey || e.ctrlKey) && (e.key === 'k' || e.key === 'л')) { e.preventDefault(); Palette.open(); return; }
  if (e.target.matches('input,textarea,select,[contenteditable]')) return;
  if (e.key === '/') { e.preventDefault(); Palette.open(); }
});

/* ── старт: вернуть пользователя туда, где он был ── */
async function boot() {
  $('kFind').textContent = IS_MAC ? '⌘K' : 'Ctrl K';
  AddMenu.init();
  initFolds();
  AI.toggle(store.get('ag.ai', '0') === '1');
  try {
    await loadWorkspace(true);
    const focus = store.get('ag.focus', null);
    if (focus && S.tables.has(focus) && !S.focus) await drill(focus);
  } catch (e) { /* сервер ещё не поднялся — граф построит опрос */ }
  setupTimers();
  const view = store.get('ag.view', 'Graph');
  App.view(view);
}
boot();
