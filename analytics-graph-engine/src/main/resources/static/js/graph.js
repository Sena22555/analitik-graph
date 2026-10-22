/* ═══════════════════════ состояние ═══════════════════════ */
const S = {
  ds: null,               // активная таблица (сайдбар, сводные, ИИ)
  nodes: new Map(),       // id -> node; узел таблицы «t:<id>», внутри таблицы «<id>|<id узла на сервере>»
  links: [],              // все связи
  expanded: new Map(),    // id -> ref (в порядке раскрытия)
  open: new Set(),        // таблица, чей граф сейчас построен (внутри таблицы — одна)
  focus: null,            // null — экран «Все таблицы», иначе id таблицы, в которую «провалились»
  wsLinks: [],            // рёбра между таблицами из /workspace/graph
  versions: new Map(),    // id таблицы -> версия, с которой построен её граф
  payloads: new Map(),    // id таблицы -> обзор (для сайдбара)
  tables: new Map(),      // id таблицы -> метаданные из /workspace/graph
  selected: null,
  version: 0,
  timers: [],
};
const tableId = dsId => 't:' + dsId;
const dsOf = id => id.startsWith('t:') ? id.slice(2) : id.split('|')[0];

/* ═══════════════════════ SVG / зум ═══════════════════════ */
const svg = d3.select('#g'), vp = d3.select('#vp');
const gL = d3.select('#gLinks'), gP = d3.select('#gPulse'), gH = d3.select('#gHit'), gLL = d3.select('#gLL'), gN = d3.select('#gNodes');
let zk = 1;
const zoom = d3.zoom().scaleExtent([0.08, 4]).on('zoom', e => {
  vp.attr('transform', e.transform); zk = e.transform.k;
  const st = $('stage'); st.classList.toggle('lowz', zk < 1.05); st.classList.toggle('far', zk < 0.6); st.classList.toggle('vfar', zk < 0.32);
});
svg.call(zoom).on('dblclick.zoom', null);
svg.on('click', () => { select(null); unhighlight(); });

const sim = d3.forceSimulation()
  .force('link', d3.forceLink().id(d => d.id).distance(linkDist).strength(linkStr))
  .force('charge', d3.forceManyBody().strength(d => d.kind === 'table' ? -1800 : d.depth === 1 ? -700 : -420).distanceMax(900))
  .force('collide', d3.forceCollide().radius(d => radius(d) + (d.kind === 'table' ? 40 : 30)).iterations(2))
  .force('x', d3.forceX(0).strength(0.004)).force('y', d3.forceY(0).strength(0.004))
  .alphaDecay(0.028).velocityDecay(0.38)
  .on('tick', tick);

function linkDist(l) {
  if (l.type === 'join') return 420;
  if (l.type === 'brain') return 260 - 120 * l.strength;
  if (l.type === 'effect') return 300;
  if (l.type === 'flow') return 110;
  const s = typeof l.source === 'object' ? l.source : S.nodes.get(l.source);
  if (s && (s.kind === 'root' || s.kind === 'table')) return 330;
  const t = typeof l.target === 'object' ? l.target : S.nodes.get(l.target);
  return t && (t.kind === 'kpi' || t.kind === 'bin') ? 120 : 150;
}
function linkStr(l) {
  if (l.type === 'join') return 0.12;
  if (l.type === 'brain') return 0.004 + 0.018 * l.strength;
  if (l.type === 'effect') return 0.004;
  if (l.type === 'flow') return 0.6;
  return 0.75;
}
function radius(d) {
  switch (d.kind) {
    case 'root': return 38;
    case 'table': return 78;
    case 'dim': case 'time': return d.depth === 1 ? 17 : 13;
    case 'metric': return d.level === 'kpi' ? 19 : 15;
    case 'cat': return 8 + 20 * Math.sqrt(Math.max(0.01, d.share ?? 0.1));
    case 'kpi': return 10 + LEVEL_W[d.level] * 2.2;
    case 'stage': return 7 + 15 * Math.sqrt(Math.max(0.02, d.share ?? 0.1));
    case 'bin': return 5 + 12 * Math.sqrt(Math.max(0.02, d.share ?? 0.1));
    case 'funnel': return 14;
    default: return 11;
  }
}
function nodeColor(d) {
  if (d.kind === 'table') return typeof WS !== 'undefined' ? WS.colorOf(d.dsId) : cssVar('--c-root');
  if (d.kind === 'kpi') return d.level === 'none' ? cssVar('--c-bin') : d.direction === 'negative' ? COLOR.neg : COLOR.pos;
  if (d.kind === 'metric' && d.level === 'kpi') return cssVar('--c-kpi');
  if (d.kind === 'stage' && d.level !== 'none' && d.direction) return d.direction === 'negative' ? COLOR.neg : COLOR.pos;
  return cssVar(KIND[d.kind]?.v || '--faint') || '#888';
}

/* ═══════════════════════ загрузка данных: «Все таблицы» → провал в таблицу ═══════════════════════ */
async function createDataset(body, auto) {
  loading(true, body.url ? 'Забираем данные по API и считаем статистику…' : 'Считаем статистику…');
  try {
    const ds = await api('/datasets', {method: 'POST', body: JSON.stringify(body)});
    await openDataset(ds, auto);
    const meta = S.tables.get(ds.id);
    if (meta?.splittable) toast(`«${ds.name}» похожа на несортированную выгрузку: свойства клиентов повторяются в каждой строке. Разложить на связанные таблицы?`, true, {label: 'Разложить', run: () => Structure.open(ds.id)});
    else toast(`Готово: «${ds.name}» — ${fmtInt(ds.rows)} строк, ${S.links.filter(l => l.type === 'brain').length} значимых связей`, true);
  } catch (e) { toast(e.message); } finally { loading(false); }
}

/** Открыть таблицу: провалиться в её граф. */
async function openDataset(ds, auto) {
  await loadWorkspace(false);
  await drill(ds.id);
  $('auto').value = String(auto || 0);
  setupTimers();
}

/** Список таблиц и связей между ними. На экране «Все таблицы» — перестраивает граф таблиц. */
async function loadWorkspace(fit) {
  const w = await api('/workspace/graph');
  S.tables = new Map(w.tables.map(t => [t.id, t]));
  S.wsLinks = w.links;
  S.wsFlows = w.flows || [];
  if (S.focus && !S.tables.has(S.focus)) S.focus = null;          // таблицу удалили
  if (S.ds && !S.tables.has(S.ds.id)) { S.ds = null; S.payloads.clear(); }

  const any = w.tables.length > 0;
  $('hero').style.display = any ? 'none' : '';
  $('bPng').style.display = any ? '' : 'none';
  if (w.tables.length === 1 && !S.focus) { await drill(w.tables[0].id); return; }
  if (!S.focus) { buildOverview(); if (fit) setTimeout(fitAll, 650); }
  else renderCrumb();
}

/** Экран «Все таблицы»: карточки таблиц, рёбра — общий ключ и число значимых связей между таблицами. */
function buildOverview() {
  S.focus = null; S.nodes = new Map(); S.links = []; S.expanded.clear(); S.open.clear();
  [...S.tables.values()].forEach(t => S.nodes.set(tableId(t.id), {id: tableId(t.id), kind: 'table', depth: 0, dsId: t.id, expandable: true,
    label: t.name, n: t.rows, meta: t, sub: `${fmtInt(t.rows)} строк · ${t.columns} колонок`, x: 0, y: 0}));
  S.wsLinks.forEach(l => S.links.push({...l, id: 'join:' + l.source + '→' + l.target, type: 'join', source: tableId(l.source), target: tableId(l.target),
    level: l.strength >= 0.5 ? 'strong' : l.strength >= 0.3 ? 'medium' : l.count ? 'weak' : 'none',
    label: l.count ? l.count + ' ' + plural(l.count, 'связь', 'связи', 'связей') : l.key, via: l.via}));
  (S.wsFlows || []).forEach(f => S.links.push({id: 'lineage:' + f.source + '→' + f.target, type: 'lineage', source: tableId(f.source), target: tableId(f.target),
    recipe: f.recipe, level: 'none', label: f.recipe === 'structure' ? 'разложено' : f.recipe === 'sql' ? 'SQL' : ''}));
  layoutTables();
  closePanel();
  renderCrumb();
  renderOverviewSide();
  restart(0.4);
}

/** Провалиться в таблицу: экран целиком становится графом этой таблицы. */
async function drill(dsId) {
  const meta = S.tables.get(dsId); if (!meta) return;
  select(null);
  S.focus = dsId; S.nodes = new Map(); S.links = []; S.expanded.clear(); S.open.clear();
  S.nodes.set(tableId(dsId), {id: tableId(dsId), kind: 'table', depth: 0, dsId, expandable: false, label: meta.name, n: meta.rows, meta,
    sub: `${fmtInt(meta.rows)} строк · ${meta.columns} колонок`, x: 0, y: 0, fx: 0, fy: 0});
  try { await openTable(dsId); } catch (e) { toast(e.message); }
  renderCrumb();
  WS.render();
  restart(1);
  setTimeout(fit, 850);
}

/** Вернулись на вкладку «Граф»: сайдбар должен описывать то, что на экране. */
function onGraphView() {
  if (!S.tables.size) return;
  if (!S.focus) renderOverviewSide();
  else if (!S.ds || S.ds.id !== S.focus) setActive(S.focus);
}

function backToAll() {
  if (!S.focus) return;
  select(null);
  buildOverview();
  WS.render();
  setTimeout(fitAll, 650);
}

async function openTable(dsId) {
  const t = S.nodes.get(tableId(dsId)); if (!t) return;
  const p = await api(`/datasets/${dsId}/overview`);
  S.open.add(dsId); S.versions.set(dsId, p.dataset.version); S.payloads.set(dsId, p);
  addPayload(p, t, null, dsId);
  // переменные — кольцом вокруг карточки таблицы
  const vars = children(t.id);
  vars.forEach((n, i) => { const a = -Math.PI / 2 + i / vars.length * Math.PI * 2; n.x = t.x + Math.cos(a) * 330; n.y = t.y + Math.sin(a) * 330; });
  S.ds = null; await setActive(dsId);
}

/** Раскладка карточек таблиц: центральная таблица в середине, остальные — по кругу. Позиции закреплены. */
function layoutTables() {
  const all = [...S.nodes.values()].filter(n => n.kind === 'table');
  if (!all.length) return;
  const sources = all.filter(t => t.meta?.source);
  const tables = all.filter(t => !t.meta?.source);
  const center = tables.length > 2 ? tables.find(t => t.meta?.isEntity) : null;
  const ring = tables.filter(t => t !== center);
  if (center) { center.x = center.fx = 0; center.y = center.fy = 0; }
  const r = Math.max(center ? 290 : 220, ring.length * 290 / (2 * Math.PI));
  ring.forEach((t, i) => {
    const a = -Math.PI / 2 + i / ring.length * Math.PI * 2;
    t.x = t.fx = Math.cos(a) * r * 1.35; t.y = t.fy = Math.sin(a) * r;
  });
  // сырые источники — слева от всего графа, поток данных идёт слева направо
  const minX = Math.min(0, ...tables.map(t => t.x));
  sources.forEach((t, i) => { t.x = t.fx = minX - 380; t.y = t.fy = (i - (sources.length - 1) / 2) * 160; });
}

function closeTable(dsId) {
  const t = S.nodes.get(tableId(dsId));
  if (t) children(t.id).forEach(c => removeNode(c.id));
  S.links = S.links.filter(l => l.type === 'join' || dsOf(idOf(l.source)) !== dsId);
  S.open.delete(dsId);
}

/** Строка навигации над графом: «Все таблицы › Пользователи» + действия таблицы. */
function renderCrumb() {
  renderHints();
  try { if (S.focus) localStorage.setItem('ag.focus', S.focus); else localStorage.removeItem('ag.focus'); } catch (e) { }
  const c = $('crumb'), side = $('tblLinks');
  if (!S.tables.size) { c.innerHTML = ''; side.innerHTML = ''; side.classList.remove('show'); return; }
  if (!S.focus) {
    const n = S.tables.size;
    c.innerHTML = `<span class="cr-t">Все таблицы</span><span class="cr-s">${n} ${plural(n, 'таблица', 'таблицы', 'таблиц')} · клик по карточке — открыть граф таблицы</span>
      <span class="cr-acts">${n > 1 ? '<button class="btn sm primary" id="crCompose"><svg class="i "><use href="#i-atlas"/></svg>Собрать отчёт из графа</button>' : ''}</span>`;
    if ($('crCompose')) $('crCompose').onclick = () => Compose.open();
    side.innerHTML = ''; side.classList.remove('show');
    return;
  }
  const t = S.tables.get(S.focus);
  c.innerHTML = `<button class="btn sm" id="crBack"><svg class="i "><use href="#i-chevl"/></svg>Все таблицы</button>
    <span class="cr-t"><span class="cr-dot" style="background:${WS.colorOf(t.id)}"></span>${esc(t.name)}</span>
    <span class="cr-s">${fmtInt(t.rows)} строк · ${t.columns} колонок · ${t.source ? 'сырой источник, разложен' : esc(t.roleRu)}${t.parents?.length ? ' · получена из ' + t.parents.map(p => '«' + esc(S.tables.get(p)?.name || '?') + '»').join(', ') : ''}</span>
    <span class="cr-acts">
      ${t.splittable ? '<button class="btn sm warn" id="crSplit"><svg class="i "><use href="#i-atlas"/></svg>Разложить по таблицам</button>' : ''}
      <button class="btn sm" id="crData"><svg class="i "><use href="#i-table"/></svg>Данные и профиль</button>
      ${S.tables.size > 1 ? '<button class="btn sm" id="crCompose"><svg class="i "><use href="#i-atlas"/></svg>Собрать отчёт</button>' : ''}
      <button class="btn sm" id="crSql"><svg class="i "><use href="#i-sql"/></svg>SQL</button>
      <button class="btn sm" id="crPivot"><svg class="i "><use href="#i-table"/></svg>Сводная</button>
      ${t.stepColumn ? '<button class="btn sm" id="crPaths"><svg class="i "><use href="#i-route"/></svg>Пути клиентов</button>' : ''}
      <button class="btn sm" id="crAsk"><svg class="i "><use href="#i-chat"/></svg>Спросить ИИ</button>
    </span>`;
  $('crBack').onclick = backToAll;
  $('crData').onclick = () => TablePanel.open(t.id);
  if ($('crSplit')) $('crSplit').onclick = () => Structure.open(t.id);
  if ($('crCompose')) $('crCompose').onclick = () => Compose.open();
  $('crPivot').onclick = () => App.view('Pivot');
  $('crSql').onclick = () => Sql.open(`SELECT *\nFROM ${SqlQ(t.name)}\nLIMIT 100`);
  if ($('crPaths')) $('crPaths').onclick = () => { Journey.dataset = t.id; App.view('Journey'); };
  $('crAsk').onclick = () => { AI.setCtx({label: 'Таблица «' + t.name + '»', node: {kind: 'table', label: t.name, rows: t.rows}}); AI.open();
    $('aiInput').value = `Что главное в таблице «${t.name}»: что на что влияет и как она связана с другими таблицами?`; $('aiInput').dispatchEvent(new Event('input')); $('aiInput').focus(); };

  // связи с другими таблицами
  const links = S.wsLinks.filter(l => l.source === t.id || l.target === t.id).sort((a, b) => (b.count || 0) - (a.count || 0));
  if (!links.length) { side.innerHTML = ''; side.classList.remove('show'); return; }
  side.innerHTML = `<h5>Связи с другими таблицами</h5>` + links.map((l, i) => {
    const o = S.tables.get(l.source === t.id ? l.target : l.source);
    return `<div class="tl-it" data-i="${i}"><span class="cr-dot" style="background:${WS.colorOf(o.id)}"></span><span class="nm">${esc(o.name)}<small>${l.via ? 'через «' + esc(l.via) + '»' : 'ключ «' + esc(l.key) + '»'}</small></span><b>${l.count || 0}</b><button class="btn sm ghost" data-go="${esc(o.id)}" title="Перейти в таблицу">→</button></div>`;
  }).join('');
  side.classList.add('show');
  side.querySelectorAll('.tl-it').forEach(el => el.onclick = e => {
    if (e.target.closest('[data-go]')) { drill(e.target.closest('[data-go]').dataset.go); return; }
    showJoin(joinLink(links[+el.dataset.i]));
  });
}

/** Ребро между таблицами в формате связи графа (для панели). */
function joinLink(l) {
  const node = id => S.nodes.get(tableId(id)) || {id: tableId(id), label: S.tables.get(id)?.name, dsId: id};
  return {...l, type: 'join', source: node(l.source), target: node(l.target),
    level: l.strength >= 0.5 ? 'strong' : l.strength >= 0.3 ? 'medium' : l.count ? 'weak' : 'none'};
}

/** Сайдбар на экране «Все таблицы»: сводка и главные связи между таблицами. */
function renderOverviewSide() {
  const tables = [...S.tables.values()];
  $('secData').style.display = tables.length ? '' : 'none';
  $('secView').style.display = 'none'; $('secLegend').style.display = 'none';
  $('secDataT').textContent = 'Все таблицы';
  $('sRows').textContent = fmtInt(tables.reduce((a, t) => a + t.rows, 0)); $('sRowsL').textContent = 'строк всего';
  $('sVars').textContent = tables.length; $('sVarsL').textContent = plural(tables.length, 'таблица', 'таблицы', 'таблиц');
  const cross = S.wsLinks.reduce((a, l) => a + (l.count || 0), 0);
  $('sLinks').textContent = cross; $('sLinksL').textContent = 'связей между таблицами';
  $('sMeta').innerHTML = 'Связи между таблицами найдены через общий ключ: каждая таблица сведена к одной строке на клиента, пары признаков проверены тестами с поправкой Бенджамини–Хохберга.';
  $('rowRefresh').style.display = 'none';
  const all = [];
  S.wsLinks.forEach(l => (l.links || []).filter(x => !x.same).forEach(x => all.push({x, l})));
  all.sort((a, b) => b.x.strength - a.x.strength);
  const name = id => S.tables.get(id)?.name || '';
  const used = new Map(), top = [];
  for (const it of all) { const k = it.x.sourceDs + it.x.targetDs; if ((used.get(k) || 0) >= 2) continue; used.set(k, (used.get(k) || 0) + 1); top.push(it); if (top.length >= 10) break; }
  $('insCnt').textContent = top.length || '';
  $('insights').innerHTML = top.length ? top.map((it, i) => `<div class="ins ${it.x.level}" data-i="${i}" title="${esc(it.x.text)}">
      <span class="k">${esc(name(it.x.sourceDs))} ↔ ${esc(name(it.x.targetDs))}</span>
      <span class="ins-pair"><b>${esc(it.x.sourceLabel)}</b><i>${it.x.direction === 'negative' ? '↘ обратная связь' : '↗ связаны'}</i><b>${esc(it.x.targetLabel)}</b></span>
      <span class="lvl ${it.x.level}">${esc(LEVEL_RU[it.x.level])} · ${esc(it.x.label)}</span></div>`).join('')
    : '<div class="empty-s">Загрузите несколько таблиц с общим ключом — здесь появятся связи между ними.</div>';
  $('insights').querySelectorAll('.ins').forEach(el => el.onclick = () => showJoin(joinLink(top[+el.dataset.i].l)));
}

/** Активная таблица: сайдбар, сводные, контекст ИИ. */
async function setActive(dsId) {
  if (S.ds && S.ds.id === dsId && S.payloads.has(dsId)) return;
  let p = S.payloads.get(dsId);
  if (!p) { p = await api(`/datasets/${dsId}/overview`); S.payloads.set(dsId, p); }
  S.ds = p.dataset; S.version = p.dataset.version;
  ['secData', 'secView', 'secLegend'].forEach(id => $(id).style.display = '');
  $('secDataT').textContent = 'Таблица «' + S.ds.name + '»';
  $('sRowsL').textContent = 'строк'; $('sVarsL').textContent = 'переменных'; $('sLinksL').textContent = 'значимых связей';
  $('rowRefresh').style.display = S.ds.sourceUrl || S.ds.query ? 'flex' : 'none';
  $('pushCode').textContent = pushSnippet();
  renderSide(p);
  document.dispatchEvent(new CustomEvent('ds:open', {detail: S.ds}));
}

/** Перестроить граф таблицы на новой версии данных, сохранив раскрытые ветки. */
async function reloadTable(dsId) {
  const refs = [...S.expanded.entries()].filter(([id]) => dsOf(id) === dsId);
  const sel = S.selected;
  const pos = new Map(); S.nodes.forEach((n, id) => { if (dsOf(id) === dsId) pos.set(id, {x: n.x, y: n.y}); });
  closeTable(dsId); S.payloads.delete(dsId);
  const t = S.nodes.get(tableId(dsId)); if (!t) return;
  const p = await api(`/datasets/${dsId}/overview`);
  S.open.add(dsId); S.versions.set(dsId, p.dataset.version); S.payloads.set(dsId, p);
  addPayload(p, t, pos, dsId);
  for (const [id, ref] of refs) {
    const n = S.nodes.get(id); if (!n) continue;
    try { const x = await api(`/datasets/${dsId}/expand`, {method: 'POST', body: JSON.stringify(ref)}); S.expanded.set(id, ref); addPayload(x, n, pos, dsId); } catch (e) { }
  }
  if (S.ds && S.ds.id === dsId) { S.ds = null; await setActive(dsId); }
  restart(0.15);
  if (sel && S.nodes.has(sel)) select(sel);
}

/** Узлы и связи с сервера → пространство имён таблицы; корень таблицы — это сам узел таблицы. */
function addPayload(p, parent, pos, dsId) {
  const P = id => id === 'root' ? tableId(dsId) : dsId + '|' + id;
  (p.remove || []).forEach(id => removeNode(P(id)));
  (p.nodes || []).forEach((n, i) => {
    if (n.kind === 'root') { const t = S.nodes.get(tableId(dsId)); if (t) t.rootInfo = {stats: n.stats, table: n.table, text: n.text}; return; }
    n.id = P(n.id); n.parent = n.parent ? P(n.parent) : tableId(dsId); n.dsId = dsId;
    (n.effects || []).forEach(e => e.target = P(e.target));
    if (S.nodes.has(n.id)) return;
    n.depth = (S.nodes.get(n.parent)?.depth ?? 0) + 1;
    const old = pos && pos.get(n.id);
    if (old && old.x != null) { n.x = old.x; n.y = old.y; }
    else if (parent) {
      const par = S.nodes.get(n.parent) || parent;
      const gp = S.nodes.get(par.parent);
      const base = gp ? Math.atan2(par.y - gp.y, par.x - gp.x) : Math.random() * 6.28;
      const k = p.nodes.length, spread = Math.min(Math.PI * 1.3, 0.5 * k);
      const a = base + (k === 1 ? 0 : -spread / 2 + spread * i / (k - 1));
      n.x = par.x + Math.cos(a) * 90; n.y = par.y + Math.sin(a) * 90;
    }
    S.nodes.set(n.id, n);
  });
  (p.links || []).forEach(l => {
    l.source = P(l.source); l.target = P(l.target);
    if (!S.nodes.has(l.source) || !S.nodes.has(l.target)) return;
    l.id = l.type + ':' + l.source + '→' + l.target;
    if (!S.links.some(x => x.id === l.id)) S.links.push(l);
  });
}

/* ═══════════════════════ раскрытие / сворачивание ═══════════════════════ */
async function toggle(d) {
  if (!d.expandable) return;
  try {
    if (d.kind === 'table') { if (!S.focus) await drill(d.dsId); return; }
    if (d.dsId) await setActive(d.dsId);
    if (S.expanded.has(d.id)) { collapse(d.id); restart(0.3); return; }
    const p = await api(`/datasets/${d.dsId}/expand`, {method: 'POST', body: JSON.stringify(d.ref)});
    if (d.kind !== 'more') S.expanded.set(d.id, d.ref);
    addPayload(p, d, null, d.dsId);
    restart(0.55);
  } catch (e) { toast(e.message); }
}
function children(id) { return [...S.nodes.values()].filter(n => n.parent === id && n.kind !== 'table'); }
function collapse(id) {
  S.expanded.delete(id);
  children(id).forEach(c => removeNode(c.id));
}
function removeNode(id) {
  children(id).forEach(c => removeNode(c.id));
  S.expanded.delete(id);
  S.nodes.delete(id);
  S.links = S.links.filter(l => idOf(l.source) !== id && idOf(l.target) !== id);
  if (S.selected === id) S.selected = null;
}
const idOf = x => typeof x === 'object' ? x.id : x;

/* ═══════════════════════ рендер ═══════════════════════ */
let linkSel, pulseSel, hitSel, llSel, nodeSel;

function visibleLinks() {
  const minS = +$('minS').value, br = $('tBrain').checked, ef = $('tEff').checked;
  const out = [];
  S.links.forEach(l => {
    if (!S.nodes.has(idOf(l.source)) || !S.nodes.has(idOf(l.target))) return;
    if (l.type === 'brain' && (!br || l.strength < minS)) return;
    if (l.type === 'join' && !(S.nodes.has(idOf(l.source)) && S.nodes.has(idOf(l.target)))) return;
    out.push(l);
  });
  if (ef) S.nodes.forEach(n => (n.effects || []).forEach(e => {
    if (!S.nodes.has(e.target) || LEVEL_W[e.level] < 1) return;
    const st = {strong: .8, medium: .5, weak: .25}[e.level];
    if (st < minS) return;
    out.push({id: 'effect:' + n.id + '→' + e.target, source: n.id, target: e.target, type: 'effect', level: e.level, direction: e.direction, label: e.label, text: e.text, strength: st});
  }));
  return out;
}

function restart(alpha) {
  const nodes = [...S.nodes.values()];
  const links = visibleLinks();
  links.forEach(l => { l.source = S.nodes.get(idOf(l.source)); l.target = S.nodes.get(idOf(l.target)); });

  linkSel = gL.selectAll('path').data(links, d => d.id).join('path')
    .attr('class', d => `lk ${d.type} ${d.level || 'none'} ${d.direction === 'negative' && d.type === 'brain' ? 'neg' : ''} ${d.via ? 'via' : ''}`)
    .attr('stroke-width', d => d.type === 'join' ? 2.5 + Math.min(10, (d.count || 0) * 0.5) : d.type === 'brain' ? 0.8 + d.strength * 4.2 : d.type === 'tree' ? (LEVEL_W[d.level] ? 1.4 + LEVEL_W[d.level] * .7 : 1.3) : d.type === 'flow' ? 2.2 : 1.8)
    .attr('marker-end', d => d.type === 'flow' ? (d.level === 'strong' ? 'url(#arS)' : 'url(#arF)') : d.type === 'lineage' ? 'url(#arL)' : null);

  const pul = $('tPulse').checked ? links.filter(l => (l.type === 'brain' && l.level === 'strong') || (l.type === 'effect' && l.level !== 'weak') || l.type === 'flow') : [];
  pulseSel = gP.selectAll('path').data(pul, d => d.id).join('path').attr('class', 'pulse')
    .attr('stroke', d => d.type === 'flow' ? cssVar('--c-funnel') : COLOR[d.level]).attr('stroke-width', d => d.type === 'brain' ? 2 + d.strength * 2 : 2.4)
    .style('animation-delay', () => (-Math.random() * 2.6) + 's');

  hitSel = gH.selectAll('path').data(links, d => d.id).join('path').attr('class', 'hit')
    .on('mouseenter', (e, d) => { showTip(e, `<b>${esc(linkTitle(d))}</b>${esc(d.label || '')}${d.text ? '<div class="m">' + esc(d.text) + '</div>' : ''}`); highlightLink(d); })
    .on('mousemove', moveTip).on('mouseleave', () => { hideTip(); unhighlight(); })
    .on('click', (e, d) => { e.stopPropagation(); showLink(d); });

  llSel = gLL.selectAll('text').data(links.filter(l => l.label), d => d.id).join('text')
    .attr('class', d => `ll ${d.type} ${d.type === 'tree' ? (d.level !== 'none' && d.direction ? (d.direction === 'negative' ? 'neg' : 'pos') : '') : (d.level || '')}`)
    .text(d => d.label);

  nodeSel = gN.selectAll('g.nd').data(nodes, d => d.id).join(enter => {
    const g = enter.append('g').attr('class', d => 'nd ' + d.kind);
    g.append('circle').attr('class', 'halo');
    g.append('circle').attr('class', 'c');
    g.append('path').attr('class', 'arc');
    g.append('text').attr('class', 't1');
    g.append('text').attr('class', 't2');
    const b = g.append('g').attr('class', 'bg');
    b.append('circle').attr('class', 'bdg').attr('r', 6.5);
    b.append('text').attr('class', 'badge').attr('dy', 3.8);
    // карточка таблицы
    const c = g.append('g').attr('class', 'card');
    c.append('rect').attr('class', 'cbox'); c.append('rect').attr('class', 'cbar');
    c.append('text').attr('class', 'cn'); c.append('text').attr('class', 'cs'); c.append('text').attr('class', 'cr'); c.append('text').attr('class', 'cgo');
    g.call(d3.drag().on('start', dragStart).on('drag', dragMove).on('end', dragEnd))
      .on('click', (e, d) => { e.stopPropagation(); select(d.id); toggle(d); })
      .on('mouseenter', (e, d) => { showTip(e, `<b>${esc(d.label)}</b>${esc(d.sub || '')}<div class="m">${esc(KIND[d.kind]?.ru || '')}${d.kind === 'table' ? (S.focus ? ' · клик — о таблице' : ' · клик — открыть граф таблицы') : d.expandable ? ' · клик — ' + (S.expanded.has(d.id) ? 'свернуть' : 'раскрыть') : ''}</div>`); highlightNode(d); })
      .on('mousemove', moveTip).on('mouseleave', () => { hideTip(); unhighlight(); });
    return g;
  });
  nodeSel.each(function (d) {
    const g = d3.select(this), r = radius(d), col = nodeColor(d);
    g.classed('sel', d.id === S.selected);
    const isT = d.kind === 'table';
    g.selectAll('.halo,.c,.arc,.t1,.t2').style('display', isT ? 'none' : null);
    g.select('.card').style('display', isT ? null : 'none');
    if (isT) {
      const W = 236, H = 104, m = d.meta || {};
      g.select('.cbox').attr('x', -W / 2).attr('y', -H / 2).attr('width', W).attr('height', H).attr('rx', 16).attr('stroke', col);
      g.select('.cbar').attr('x', -W / 2 + 12).attr('y', -H / 2 + 16).attr('width', 5).attr('height', H - 32).attr('rx', 2.5).attr('fill', col);
      g.select('.cn').attr('x', -W / 2 + 30).attr('y', -14).text(trunc(d.label, 22));
      g.select('.cs').attr('x', -W / 2 + 30).attr('y', 8).text(d.sub);
      const tag = m.source ? 'СЫРОЙ ИСТОЧНИК · РАЗЛОЖЕН' : m.splittable ? 'НЕСОРТИРОВАНО · МОЖНО РАЗЛОЖИТЬ'
        : (m.roleRu || '').toUpperCase() + (m.recipe === 'structure' ? ' · ИЗ СОРТИРОВКИ' : m.recipe === 'sql' ? ' · ИЗ SQL' : m.keyColumn ? ' · ' + trunc(m.keyColumn, 18) : '');
      g.select('.cr').attr('x', -W / 2 + 30).attr('y', 30).attr('fill', m.splittable ? cssVar('--warn') : col).text(tag);
      g.classed('src', !!m.source).classed('raw', !!m.splittable);
      g.select('.cgo').attr('x', W / 2 - 16).attr('y', -14).text(S.focus ? '' : '→');
      g.select('.bg').style('display', 'none');
      return;
    }
    g.select('.c').attr('r', r).attr('stroke', col)
      .attr('fill', d.kind === 'root' ? col : d.kind === 'table' ? (S.open.has(d.dsId) ? col : tint(col, 0.25)) : d.kind === 'more' ? cssVar('--surface') : tint(col, (+cssVar('--node-tint') || .1) * (d.kind === 'cat' || d.kind === 'kpi' || d.kind === 'stage' ? 1.8 : 1)))
      .attr('stroke-dasharray', d.kind === 'more' ? '3 3' : null)
      .attr('filter', null);
    g.select('.halo').attr('r', r + 5).attr('stroke', col).attr('opacity', d.kind === 'root' || d.kind === 'table' ? .35 : LEVEL_W[d.level] >= 2 ? .45 : 0);
    const share = d.share;
    if ((d.kind === 'cat' || d.kind === 'stage' || d.kind === 'bin') && share != null && share < 0.999) {
      const arc = d3.arc()({innerRadius: r + 3, outerRadius: r + 3, startAngle: 0, endAngle: Math.max(0.05, share * Math.PI * 2)});
      g.select('.arc').attr('d', arc).attr('stroke', col).attr('opacity', .95);
    } else g.select('.arc').attr('d', null);
    g.select('.t1').attr('dy', r + 15).text(trunc(d.label, 30)).classed('big', d.kind === 'root' || d.depth === 1).classed('tbl', d.kind === 'table');
    g.select('.t2').attr('dy', r + (d.kind === 'table' ? 31 : 28)).text(d.kind === 'root' || d.kind === 'table' ? d.sub : ['cat', 'kpi', 'bin'].includes(d.kind) ? '' : trunc(d.sub || '', 38));
    const ex = d.expandable;
    g.select('.bg').style('display', ex ? null : 'none').attr('transform', `translate(${r * .72},${-r * .72})`);
    const isOpen = d.kind === 'table' ? S.open.has(d.dsId) : S.expanded.has(d.id);
    g.select('.bdg').attr('fill', isOpen ? cssVar('--faint') : col);
    g.select('.badge').text(isOpen ? '−' : '+');
  });

  sim.nodes(nodes);
  sim.force('link').links(links);
  sim.alpha(alpha ?? 0.3).restart();
  $('stage').classList.toggle('show-bl', $('tLbl').checked);
  updateCounts();
}

function tick() {
  if (!linkSel) return;
  linkSel.attr('d', pathOf); pulseSel.attr('d', pathOf); hitSel.attr('d', pathOf);
  llSel.attr('x', d => mid(d).x).attr('y', d => mid(d).y - 4);
  nodeSel.attr('transform', d => `translate(${d.x},${d.y})`);
}
function pathOf(d) {
  const s = d.source, t = d.target; if (!s || !t) return '';
  if (d.type === 'tree') return `M${s.x},${s.y}L${t.x},${t.y}`;
  if (d.type === 'lineage') {
    const dx = t.x - s.x, dy = t.y - s.y, k = Math.min(126 / Math.max(1e-6, Math.abs(dx)), 60 / Math.max(1e-6, Math.abs(dy)));
    const ex = t.x - dx * k, ey = t.y - dy * k, c = ctrl(s, {x: ex, y: ey}, 0.12);
    return `M${s.x},${s.y}Q${c.x},${c.y} ${ex},${ey}`;
  }
  let tx = t.x, ty = t.y;
  if (d.type === 'flow') { const dx = t.x - s.x, dy = t.y - s.y, L = Math.hypot(dx, dy) || 1, r = radius(t) + 5; tx = t.x - dx / L * r; ty = t.y - dy / L * r; }
  const c = ctrl(s, {x: tx, y: ty}, d.type === 'flow' ? 0.08 : 0.18);
  return `M${s.x},${s.y}Q${c.x},${c.y} ${tx},${ty}`;
}
function ctrl(s, t, k) { const mx = (s.x + t.x) / 2, my = (s.y + t.y) / 2, dx = t.x - s.x, dy = t.y - s.y; return {x: mx - dy * k, y: my + dx * k}; }
function mid(d) {
  const s = d.source, t = d.target; if (!s || !t) return {x: 0, y: 0};
  if (d.type === 'tree') return {x: (s.x + t.x) / 2, y: (s.y + t.y) / 2};
  const c = ctrl(s, t, d.type === 'flow' ? 0.08 : 0.18);
  return {x: .25 * s.x + .5 * c.x + .25 * t.x, y: .25 * s.y + .5 * c.y + .25 * t.y};
}
function dragStart(e, d) { if (!e.active) sim.alphaTarget(0.25).restart(); d.fx = d.x; d.fy = d.y; }
function dragMove(e, d) {
  if (d.kind === 'table') { const dx = e.x - d.fx, dy = e.y - d.fy; S.nodes.forEach(n => { if (n.dsId === d.dsId && n !== d) { n.x += dx; n.y += dy; } }); }
  d.fx = e.x; d.fy = e.y;
}
function dragEnd(e, d) { if (!e.active) sim.alphaTarget(0); if (d.kind !== 'table') { d.fx = null; d.fy = null; } }

/* ═══════════════════════ подсветка ═══════════════════════ */
function highlightNode(d) {
  const nb = new Set([d.id]);
  linkSel.each(l => { if (l.source.id === d.id) nb.add(l.target.id); if (l.target.id === d.id) nb.add(l.source.id); });
  $('stage').classList.add('dim-out');
  nodeSel.classed('hl', n => nb.has(n.id));
  const on = l => l.source.id === d.id || l.target.id === d.id;
  linkSel.classed('hl', on); pulseSel.classed('hl', on); llSel.classed('hl', on);
}
function highlightLink(l) {
  $('stage').classList.add('dim-out');
  nodeSel.classed('hl', n => n.id === l.source.id || n.id === l.target.id);
  linkSel.classed('hl', x => x === l); pulseSel.classed('hl', x => x === l); llSel.classed('hl', x => x === l);
}
function highlightIds(ids) {
  const set = new Set(ids);
  $('stage').classList.add('dim-out');
  nodeSel.classed('hl', n => set.has(n.id) || set.has(n.parent));
  const on = l => set.has(l.source.id) && set.has(l.target.id);
  linkSel.classed('hl', on); pulseSel.classed('hl', on); llSel.classed('hl', on);
}
function unhighlight() {
  if (S._pinnedHl) return;
  $('stage').classList.remove('dim-out');
  if (nodeSel) { nodeSel.classed('hl', false); linkSel.classed('hl', false); pulseSel.classed('hl', false); llSel.classed('hl', false); }
}

/* ═══════════════════════ подсказки ═══════════════════════ */
function showTip(e, html) { const t = $('tip'); t.innerHTML = html; t.classList.add('show'); moveTip(e); }
function moveTip(e) { const t = $('tip'); const x = Math.min(e.clientX + 14, innerWidth - t.offsetWidth - 10); t.style.left = x + 'px'; t.style.top = (e.clientY + 14) + 'px'; }
function hideTip() { $('tip').classList.remove('show'); }
function linkTitle(d) {
  const a = d.source.label || d.source, b = d.target.label || d.target;
  if (d.type === 'brain') return `${a} ⟷ ${b} · ${LEVEL_RU[d.level]} связь`;
  if (d.type === 'effect') return `${a} → ${b} · ${LEVEL_RU[d.level]} эффект`;
  if (d.type === 'lineage') return `${a} → ${b}: ${d.recipe === 'structure' ? 'таблица получена сортировкой сырых данных' : 'таблица получена SQL-запросом'} · пересчитывается сама, когда меняется источник`;
  if (d.type === 'join') return `${a} ↔ ${b}: ${d.count || 0} значимых связей между признаками · ${d.via ? 'через «' + d.via + '»' : 'ключ «' + d.key + '»'} · клик — список`;
  if (d.type === 'flow') return `${a} → ${b}`;
  return `${a} → ${b}`;
}

/* ═══════════════════════ панель деталей ═══════════════════════ */
function select(id) {
  S.selected = id;
  if (nodeSel) nodeSel.classed('sel', d => d.id === id);
  if (!id) { closePanel(); return; }
  const d = S.nodes.get(id); if (d) showNode(d);
}
function closePanel() { $('panel').classList.remove('show'); }
function kindBadge(kind, extra) {
  const col = `var(${KIND[kind]?.v || '--muted'})`;
  return `<span class="ty" style="background:color-mix(in srgb, ${col} 12%, transparent);color:${col}">${esc(extra || KIND[kind]?.ru || kind)}</span>`;
}
function showNode(d) {
  if (d.kind === 'table') return showTable(d);
  if (d.dsId) setActive(d.dsId);
  const f = d.ref?.filters || [];
  let h = `<div class="ph">${kindBadge(d.kind)}${d.level && d.level !== 'none' && d.level !== 'kpi' ? ` <span class="lvl ${d.level}">${LEVEL_RU[d.level]}</span>` : ''}${d.level === 'kpi' ? ' <span class="lvl medium">KPI</span>' : ''}
    <h2>${esc(d.label)}</h2><div class="sb">${esc(d.sub || '')}</div>
    ${f.length ? `<div class="crumbs">${f.map(x => `<span>${esc(x.c)}: ${esc(x.v)}</span>`).join('<span class="arr">→</span>')}</div>` : ''}
    <button class="x" onclick="select(null)">×</button></div><div class="pb">`;
  if (d.text) h += `<div class="txt">${esc(d.text)}</div>`;
  if (d.stats?.length) h += `<h5>Статистика</h5>` + d.stats.map(([k, v]) => `<div class="kv"><span>${esc(k)}</span><b>${esc(v)}</b></div>`).join('');
  if (d.effects?.length) {
    h += `<h5>Синапсы: влияние на метрики</h5>` + d.effects.map(e => `<div class="eff" data-t="${esc(e.target)}"><span class="lvl ${e.level}">${esc(e.label)}</span><span>${esc(e.text)}</span></div>`).join('');
  }
  if (d.bars?.length) {
    const mx = Math.max(...d.bars.map(b => Math.max(b.value, b.hi || 0)), 0.0001);
    h += `<h5>${esc(d.barsTitle || 'Распределение')}</h5><div class="bars">` + d.bars.map(b =>
      `<div class="b"><div class="bl"><span title="${esc(b.label)}">${esc(b.label)}</span><span>${esc(b.text)}</span></div><div class="tr"><div class="fl" style="width:${b.value / mx * 100}%"></div>${b.hi > 0 ? `<div class="ci" style="left:${b.lo / mx * 100}%;width:${(b.hi - b.lo) / mx * 100}%"></div>` : ''}</div></div>`).join('') + '</div>';
  }
  if (d.table?.rows?.length) {
    h += `<h5>${esc(d.table.title || '')}</h5><div style="max-height:320px;overflow:auto"><table class="tb"><tr>${d.table.head.map(x => `<th>${esc(x)}</th>`).join('')}</tr>` +
      d.table.rows.map(r => `<tr class="${String(r[r.length - 1]).includes('*') ? 'sig' : ''}">${r.map(c => `<td>${esc(c)}</td>`).join('')}</tr>`).join('') + '</table></div>';
  }
  const links = S.links.filter(l => l.type === 'brain' && (idOf(l.source) === d.id || idOf(l.target) === d.id)).sort((a, b) => b.strength - a.strength);
  if (links.length) {
    h += `<h5>Связи в мозге (${links.length})</h5>` + links.slice(0, 12).map(l => {
      const other = S.nodes.get(idOf(l.source) === d.id ? idOf(l.target) : idOf(l.source));
      return `<div class="eff" data-t="${esc(other?.id)}"><span class="lvl ${l.level}">${esc(l.label)}</span><span>${esc(other?.label)}</span></div>`;
    }).join('');
  }
  h += `<div class="acts">${d.expandable ? `<button class="btn primary sm" id="pTog">${S.expanded.has(d.id) ? 'Свернуть ветку' : 'Раскрыть ветку'}</button><button class="btn sm" id="pFocus">Фокус</button>` : ''}<button class="btn sm" id="pAsk">Спросить аналитика</button></div>`;
  h += '</div>';
  $('panel').innerHTML = h; $('panel').classList.add('show');
  $('panel').querySelectorAll('.eff').forEach(el => el.onclick = () => { const n = S.nodes.get(el.dataset.t); if (n) { select(n.id); focusOn([n]); } });
  $('pAsk').onclick = () => AI.askAbout(d);
  if ($('pTog')) $('pTog').onclick = async () => { await toggle(d); showNode(d); };
  if ($('pFocus')) $('pFocus').onclick = () => focusOn([d, ...children(d.id)]);
}
function showLink(l) {
  if (l.type === 'join') return showJoin(l);
  const t = l.test || {};
  const n = v => (v === null || v === undefined || v === 'NaN' || Number.isNaN(+v)) ? null : +v;
  let h = `<div class="ph">${kindBadge('dim', l.type === 'brain' ? 'Связь' : l.type === 'effect' ? 'Синапс' : l.type === 'flow' ? 'Поток' : 'Ветка')} <span class="lvl ${l.level || 'none'}">${LEVEL_RU[l.level || 'none']}</span>
    <h2>${esc(l.source.label)} ${l.type === 'brain' ? '⟷' : '→'} ${esc(l.target.label)}</h2><div class="sb">${esc(l.label || '')}</div><button class="x" onclick="select(null)">×</button></div><div class="pb">`;
  if (l.text) h += `<div class="txt">${esc(l.text)}</div>`;
  if (t.test) {
    h += `<h5>Статистический тест</h5><div class="kv"><span>Метод</span><b>${esc(t.test)}</b></div>`;
    if (n(t.stat) !== null) h += `<div class="kv"><span>Статистика</span><b>${fmt(t.stat)}${n(t.df1) !== null ? ` (df = ${fmt(t.df1)}${n(t.df2) !== null ? ', ' + fmt(t.df2) : ''})` : ''}</b></div>`;
    h += `<div class="kv"><span>p-value</span><b>${fmtP(t.p)}</b></div><div class="kv"><span>q-value (FDR, Бенджамини–Хохберг)</span><b>${fmtP(t.q)}</b></div>`;
    h += `<div class="kv"><span>Размер эффекта</span><b>${esc(t.effectName)} = ${fmt(t.effect)}${n(t.lo) !== null ? ` [${fmt(t.lo)}; ${fmt(t.hi)}]` : ''}</b></div><div class="kv"><span>Наблюдений</span><b>${fmtInt(t.n)}</b></div>`;
    if (t.eta2 !== undefined) h += `<div class="kv"><span>η² / ω²</span><b>${fmt(t.eta2)} / ${fmt(t.omega2)}</b></div><div class="kv"><span>Краскел–Уоллис</span><b>H = ${fmt(t.kwH)}, p ${fmtP(t.kwP)}</b></div>`;
    h += `<h5>Как читать</h5><div class="txt" style="font-size:12px">Шкала силы по Коэну: |ρ|, V, η ≥ 0,5 — сильная, ≥ 0,3 — средняя, ≥ 0,1 — слабая; для g/h: 0,8 / 0,5 / 0,2. Связь показана, только если q < 0,05 после поправки на множественные сравнения. Корреляция ≠ причинность.</div>`;
  }
  h += '</div>'; $('panel').innerHTML = h; $('panel').classList.add('show');
}

/** Панель таблицы: что внутри, роль, ключ, связи с другими таблицами. */
function showTable(d) {
  const m = d.meta || S.tables.get(d.dsId) || {}, info = d.rootInfo;
  const joins = S.wsLinks.filter(l => l.source === d.dsId || l.target === d.dsId);
  let h = `<div class="ph"><span class="ty" style="color:${nodeColor(d)}">Таблица · ${esc(m.roleRu || '')}</span>
    <h2>${esc(d.label)}</h2><div class="sb">${fmtInt(m.rows)} строк · ${m.columns} колонок${m.keyColumn ? ' · ключ «' + esc(m.keyColumn) + '»' : ''}</div><button class="x" onclick="select(null)">×</button></div><div class="pb">`;
  h += `<div class="txt">${S.focus ? 'Вокруг — колонки таблицы, цветные линии — значимые связи между ними. Клик по колонке раскрывает ветку: сегменты, метрики, воронку.' : 'Откройте таблицу, чтобы увидеть, что на что влияет внутри неё.'}${m.isEntity ? ' Это центральная таблица: остальные связаны с ней по ключу.' : ''}</div>`;
  if (joins.length) {
    h += `<h5>Связи с другими таблицами</h5>` + joins.map((l, i) => {
      const o = S.tables.get(l.source === d.dsId ? l.target : l.source);
      return `<div class="eff" data-j="${i}"><span class="lvl ${l.count ? 'medium' : 'none'}">${l.count || 0}</span><span><b>${esc(o?.name)}</b> · ${l.via ? 'через «' + esc(l.via) + '»' : 'ключ «' + esc(l.key) + '»'}</span></div>`;
    }).join('');
  }
  if (info?.table?.rows?.length) h += `<h5>Колонки</h5><div style="max-height:260px;overflow:auto"><table class="tb"><tr>${info.table.head.map(x => `<th>${esc(x)}</th>`).join('')}</tr>${info.table.rows.map(r => `<tr>${r.map(c => `<td>${esc(c)}</td>`).join('')}</tr>`).join('')}</table></div>`;
  if (m.splittable) h += `<div class="txt warnbox">Похоже на несортированную выгрузку: свойства клиентов или сессий повторяются в каждой строке. «Разложить» вынесет их в отдельные связанные таблицы, почистит написание и форматы.</div>`;
  if (m.source) h += `<div class="txt">Сырой источник: уже разложен на связанные таблицы (стрелки на графе). Если в него допишут строки — таблицы пересчитаются сами.</div>`;
  h += `<div class="acts">${m.splittable ? '<button class="btn primary sm" id="tSplit">Разложить по таблицам</button>' : ''}${S.focus ? '' : `<button class="btn ${m.splittable ? '' : 'primary '}sm" id="tOpen">Открыть граф таблицы</button>`}<button class="btn sm" id="tData">Данные</button>${m.stepColumn ? '<button class="btn sm" id="tPaths">Пути клиентов</button>' : ''}<button class="btn sm" id="tAsk">Спросить ИИ</button></div></div>`;
  $('panel').innerHTML = h; $('panel').classList.add('show');
  if ($('tOpen')) $('tOpen').onclick = () => drill(d.dsId);
  if ($('tSplit')) $('tSplit').onclick = () => Structure.open(d.dsId);
  $('tData').onclick = () => TablePanel.open(d.dsId);
  if ($('tPaths')) $('tPaths').onclick = () => { Journey.dataset = d.dsId; App.view('Journey'); };
  $('tAsk').onclick = async () => { await setActive(d.dsId); AI.setCtx({label: 'Таблица «' + d.label + '»', node: {kind: 'table', label: d.label, rows: m.rows, role: m.roleRu}}); AI.open();
    $('aiInput').value = `Что главное в таблице «${d.label}»: что на что влияет и как она связана с другими таблицами?`; $('aiInput').dispatchEvent(new Event('input')); $('aiInput').focus(); };
  $('panel').querySelectorAll('[data-j]').forEach(el => el.onclick = () => showJoin(joinLink(joins[+el.dataset.j])));
}

/** Панель ребра между таблицами: общий ключ и значимые связи признаков двух таблиц. */
function showJoin(l) {
  const asNode = x => typeof x === 'object' ? x : S.nodes.get(x);
  const a = asNode(l.source), b = asNode(l.target);
  const ls = (l.links || []).filter(x => !x.same).sort((x, y) => y.strength - x.strength);
  const same = (l.links || []).filter(x => x.same);
  const name = id => S.tables.get(id)?.name || '';
  let h = `<div class="ph"><span class="ty">Связь таблиц</span> <span class="lvl ${l.level}">${ls.length} ${plural(ls.length, 'связь', 'связи', 'связей')}</span>
    <h2>${esc(a.label)} ⟷ ${esc(b.label)}</h2><div class="sb">${l.via ? 'общего ключа нет — обе таблицы сведены к «' + esc(l.key) + '» через «' + esc(l.via) + '»' : 'ключ «' + esc(l.key) + '» · ' + Math.round(l.overlap * 100) + '% значений совпадает'}</div><button class="x" onclick="select(null)">×</button></div><div class="pb">`;
  h += `<div class="txt">Обе таблицы сведены к одной строке на «${esc(l.entityKey)}»: число записей, суммы и средние, «было ли хоть раз» (для событий — «был на шаге»). Каждая пара признаков проверена тестом, показаны связи с q < 0,05 после поправки Бенджамини–Хохберга. Связь ≠ причина.</div>`;
  h += `<h5>Что с чем связано</h5>`;
  h += ls.length ? ls.slice(0, 25).map((x, i) => `<div class="eff" data-i="${i}" title="${esc(x.text)}"><span class="lvl ${x.level}">${esc(x.label)}</span><span>${esc(name(x.sourceDs))}: <b>${esc(x.sourceLabel)}</b> ⟷ ${esc(name(x.targetDs))}: <b>${esc(x.targetLabel)}</b>${x.direction === 'negative' ? ' · обратная' : ''}</span></div>`).join('')
    : '<div class="empty-s">Значимых связей между признаками этих таблиц не найдено.</div>';
  if (same.length) h += `<h5>Сверка таблиц</h5><div class="txt" style="font-size:12px">${same.map(x => `«${esc(x.sourceLabel)}» = «${esc(x.targetLabel)}» (${esc(x.label)})`).join('; ')} — один и тот же факт записан в обеих таблицах, данные согласованы.</div>`;
  h += `<div id="joinDetail"></div><div class="acts">${S.focus === a.dsId ? '' : `<button class="btn primary sm" data-go="${esc(a.dsId)}">Открыть «${esc(trunc(a.label, 16))}»</button>`}${S.focus === b.dsId ? '' : `<button class="btn ${S.focus === a.dsId ? 'primary ' : ''}sm" data-go="${esc(b.dsId)}">Открыть «${esc(trunc(b.label, 16))}»</button>`}<button class="btn sm" id="jAsk">Спросить ИИ</button></div></div>`;
  $('panel').innerHTML = h; $('panel').classList.add('show');
  $('panel').querySelectorAll('[data-i]').forEach(el => el.onclick = () => {
    const x = ls[+el.dataset.i]; const t = x.test || {};
    $('joinDetail').innerHTML = `<h5>${esc(x.sourceLabel)} ⟷ ${esc(x.targetLabel)}</h5><div class="txt">${esc(x.text)}</div><div class="kv"><span>Метод</span><b>${esc(t.test)}</b></div><div class="kv"><span>p / q</span><b>${fmtP(t.p)} / ${fmtP(t.q)}</b></div><div class="kv"><span>Эффект</span><b>${esc(t.effectName)} = ${fmt(t.effect)}</b></div><div class="kv"><span>Наблюдений</span><b>${fmtInt(t.n)}</b></div>`;
    $('joinDetail').scrollIntoView({behavior: 'smooth', block: 'nearest'});
  });
  $('panel').querySelectorAll('[data-go]').forEach(btn => btn.onclick = () => drill(btn.dataset.go));
  $('jAsk').onclick = () => { AI.setCtx({label: `${a.label} ⟷ ${b.label}`, node: {kind: 'table-link', key: l.key, links: ls.slice(0, 12).map(x => x.text)}}); AI.open();
    $('aiInput').value = `Как таблицы «${a.label}» и «${b.label}» влияют друг на друга? Что из этого важно для бизнеса, что может быть совпадением или третьим фактором, и что проверить дальше?`; $('aiInput').dispatchEvent(new Event('input')); $('aiInput').focus(); };
}

/* ═══════════════════════ сайдбар ═══════════════════════ */
function renderSide(p) {
  const ds = p.dataset;
  $('sRows').textContent = fmtInt(ds.rows);
  $('sVars').textContent = ds.columns.filter(c => ['NUMERIC', 'CATEGORICAL', 'DATE'].includes(c.type)).length;
  const src = {url: 'API: ' + (ds.sourceUrl || ''), file: 'файл CSV', push: 'push API', demo: 'демо', 'demo-product': 'демо-продукт (4 связанные базы)', sql: 'SQL-запрос'}[ds.sourceType] || ds.sourceType;
  $('sMeta').innerHTML = `Источник: <b>${esc(src.length > 60 ? src.slice(0, 60) + '…' : src)}</b><br>Обновлено: <b>${new Date(ds.fetchedAt).toLocaleTimeString('ru-RU')}</b> · версия <b>${ds.version}</b><br>KPI: <b>${esc(ds.kpis.join(', ') || '—')}</b>${ds.funnelColumn ? `<br>Воронка: <b>${esc(ds.funnelColumn)}</b>` : ''}`;
  const ins = p.insights || [];
  $('insCnt').textContent = ins.length;
  const icon = {segment: 'Сегмент', funnel: 'Воронка', link: 'Связь', trend: 'Тренд', quality: 'Качество данных'};
  $('insights').innerHTML = ins.length ? ins.map((x, i) => `<div class="ins ${x.level}" data-i="${i}"><span class="k">${icon[x.kind] || x.kind}</span>${esc(x.text)}</div>`).join('') : '<div class="empty-s">Значимых закономерностей не найдено.</div>';
  $('insights').querySelectorAll('.ins').forEach(el => el.onclick = () => focusInsight(ins[+el.dataset.i]));
}
function updateCounts() { if (S.focus) $('sLinks').textContent = linkSel ? linkSel.filter(d => d.type === 'brain').size() : '–'; }

async function focusInsight(x) {
  const dsId = S.ds.id;
  if (S.focus !== dsId) await drill(dsId);
  const refs = x.refs.map(r => r === 'root' ? tableId(dsId) : dsId + '|' + r);
  // для сегментных инсайтов раскрываем измерение, чтобы было видно категорию
  if (x.kind === 'segment' || x.kind === 'funnel') {
    const dim = S.nodes.get(refs[0]);
    if (dim && !S.expanded.has(dim.id)) await toggle(dim);
  }
  const ids = new Set(refs);
  const m = x.text.match(/«([^»:]+): ([^»]+)»/);
  if (m) S.nodes.forEach(n => { if (n.dsId === dsId && n.kind === 'cat' && n.column === m[1] && n.value === m[2] && !(n.ref.filters.length > 1)) ids.add(n.id); });
  const nodes = [...ids].map(id => S.nodes.get(id)).filter(Boolean);
  setTimeout(() => { focusOn(nodes); S._pinnedHl = false; highlightIds([...ids]); S._pinnedHl = true; setTimeout(() => { S._pinnedHl = false; unhighlight(); }, 3500); }, 350);
}

/* ═══════════════════════ камера ═══════════════════════ */
function focusOn(nodes) {
  nodes = nodes.filter(n => n && n.x != null); if (!nodes.length) return;
  const W = $('stage').clientWidth - ($('panel').classList.contains('show') ? 400 : 0), H = $('stage').clientHeight;
  const xs = nodes.map(n => n.x), ys = nodes.map(n => n.y);
  const x0 = Math.min(...xs) - 120, x1 = Math.max(...xs) + 120, y0 = Math.min(...ys) - 110, y1 = Math.max(...ys) + 110;
  const k = Math.min(1.6, W / (x1 - x0), H / (y1 - y0));
  svg.transition().duration(650).call(zoom.transform, d3.zoomIdentity.translate(W / 2 - k * (x0 + x1) / 2, H / 2 - k * (y0 + y1) / 2).scale(k));
}
function fit() { focusOn([...S.nodes.values()]); }
const fitAll = fit;

/* ═══════════════════════ live-обновление ═══════════════════════ */
function setupTimers() {
  S.timers.forEach(clearInterval); S.timers = [];
  const sec = +$('auto').value;
  $('live').classList.toggle('on', sec > 0 || S.ds?.sourceType === 'push');
  if (sec > 0 && (S.ds?.sourceUrl || S.ds?.query)) S.timers.push(setInterval(() => refresh(true), sec * 1000));
  S.timers.push(setInterval(pollVersion, 5000)); // ловим push-обновления и новые таблицы
}
async function pollVersion() {
  try {
    const w = await api('/workspace');
    const ids = new Set(w.datasets.map(d => d.id));
    if (ids.size !== S.tables.size || [...ids].some(id => !S.tables.has(id))) await loadWorkspace(false);
    for (const d of w.datasets) if (S.open.has(d.id) && S.versions.get(d.id) !== d.version) await reloadTable(d.id);
  } catch (e) { /* сервер недоступен */ }
}
async function refresh(silent) {
  try {
    if (!silent) loading(true, 'Забираем свежие данные…');
    await api(S.ds.query ? `/sql/refresh/${S.ds.id}` : `/datasets/${S.ds.id}/refresh`, {method: 'POST'});
    await rebuild();
    if (!silent) toast('Данные обновлены', true);
  } catch (e) { toast('Обновление: ' + e.message); } finally { loading(false); }
}
/** Перестроить активную таблицу на новой версии данных (используется ИИ после новых колонок). */
async function rebuild() { if (S.ds) await reloadTable(S.ds.id); }

function pushSnippet() {
  const base = location.origin.startsWith('http') ? location.origin : 'http://localhost:8080';
  const id = S.ds ? S.ds.id : '{id}';
  return `# создать граф из своих данных\ncurl -X POST ${base}/api/v2/datasets \\\n  -H 'Content-Type: application/json' \\\n  -d '{"name":"Мои пользователи","data":[{"Пол":"Женщина","Покупки":1}]}'\n\n# дописать строки в открытый граф — он обновится сам\ncurl -X POST ${base}/api/v2/datasets/${id}/append \\\n  -H 'Content-Type: application/json' \\\n  -d '{"data":[{"Пол":"Мужчина","Покупки":0}]}'`;
}

/* ═══════════════════════ UI-обработчики ═══════════════════════ */
$('file').onchange = e => { const f = e.target.files[0]; if (f) readFile(f); e.target.value = ''; };
async function readFile(f) {
  const text = await f.text();
  const isJson = /\.json$/i.test(f.name) || /^\s*[\[{]/.test(text);
  createDataset(isJson ? {name: f.name, data: JSON.parse(text)} : {name: f.name, csv: text}, 0);
}
$('stage').addEventListener('dragover', e => e.preventDefault());
$('stage').addEventListener('drop', e => { e.preventDefault(); const f = e.dataTransfer.files[0]; if (f) readFile(f); });

const demoUrl = (fmt, live) => `${location.origin.startsWith('http') ? location.origin : 'http://localhost:8080'}/api/v2/demo/users?rows=1000&format=${fmt}${live ? '&live=true' : ''}`;
function openApiModal() { $('pushCode').textContent = pushSnippet(); $('mApi').classList.add('show'); setTimeout(() => $('aUrl').focus(), 50); }
function openSingleDemo() { return createDataset({name: 'Пользователи сайта (демо)', url: demoUrl('json')}, 0); }
document.querySelectorAll('[data-close]').forEach(b => b.onclick = () => b.closest('.modal-bg').classList.remove('show'));
$('mApi').onclick = e => { if (e.target === $('mApi')) $('mApi').classList.remove('show'); };
document.querySelectorAll('.ex button').forEach(b => b.onclick = () => {
  $('aUrl').value = b.dataset.u === 'demo-live' ? demoUrl('json', true) : demoUrl('csv');
  if (!$('aName').value) $('aName').value = 'Пользователи сайта (API)';
});
$('aGo').onclick = () => {
  const url = $('aUrl').value.trim(); if (!url) return toast('Укажите URL');
  const headers = {};
  $('aHdr').value.split('\n').forEach(line => { const i = line.indexOf(':'); if (i > 0) headers[line.slice(0, i).trim()] = line.slice(i + 1).trim(); });
  $('mApi').classList.remove('show');
  createDataset({name: $('aName').value.trim() || undefined, url, headers}, +$('aAuto').value);
};
document.querySelectorAll('.card[data-act]').forEach(c => c.onclick = () => ({api: openApiModal, csv: () => $('file').click(), demo: openSingleDemo})[c.dataset.act]());

$('auto').onchange = setupTimers;
$('bRefresh').onclick = () => refresh(false);
['minS', 'tBrain', 'tEff', 'tLbl', 'tPulse'].forEach(id => $(id).addEventListener('input', () => { $('minSv').textContent = (+$('minS').value).toLocaleString('ru-RU'); restart(0.08); }));
$('zIn').onclick = () => svg.transition().duration(250).call(zoom.scaleBy, 1.35);
$('zOut').onclick = () => svg.transition().duration(250).call(zoom.scaleBy, 0.74);
$('zFit').onclick = fit;
$('bPng').onclick = exportPng;
$('bHelp').onclick = e => { e.stopPropagation(); toggleHelp(); };
document.addEventListener('click', e => { if (!e.target.closest('#helpPop')) $('helpPop').classList.remove('show'); });
window.addEventListener('keydown', e => {
  if (e.target.matches('input,textarea,select')) return;
  if (e.key === 'Escape') {
    const panelOpen = $('panel').classList.contains('show');
    select(null); S._pinnedHl = false; unhighlight(); $('mApi').classList.remove('show');
    if (!panelOpen && S.focus && App.current === 'Graph') backToAll();
  }
  if ((e.key === 'f' || e.key === 'а') && App.current === 'Graph') fit();
  if (e.key === '?' && App.current === 'Graph') toggleHelp();
});

/** Справка «Как читать граф» — разная для экрана всех таблиц и графа таблицы. */
function toggleHelp(force) {
  const p = $('helpPop'), on = force ?? !p.classList.contains('show');
  if (!on) { p.classList.remove('show'); return; }
  const row = (k, v) => `<div class="hp-r"><span>${k}</span><div>${v}</div></div>`;
  p.innerHTML = `<h4>${S.focus ? 'Как читать граф таблицы' : 'Как читать граф таблиц'}</h4>` + (S.focus
    ? row('<span class="d" style="border-color:var(--c-dim)"></span>', '<b>Измерение</b> — категория (город, источник). Клик раскрывает сегменты с долей и 95% интервалом.')
      + row('<span class="d" style="border-color:var(--c-metric)"></span>', '<b>Метрика</b> — числовая колонка. Клик — распределение и влияние сегментов.')
      + row('<span class="d" style="border-color:var(--c-time)"></span>', '<b>Время</b> — дата: тренды по периодам.')
      + row('<span class="l" style="background:var(--strong)"></span>', '<b>Цвет линии</b> — сила связи: красная сильная, жёлтая средняя, синяя слабая. Показаны только значимые после поправки на множественные сравнения.')
      + row('<span class="l dash"></span>', '<b>Пунктир</b> — обратная связь: больше одного → меньше другого.')
      + row('<span class="d" style="border-color:var(--pos)"></span>', '<b>Зелёный / красный</b> сегмент — показатель выше / ниже, чем у остальных.')
    : row('<span class="cardi"></span>', '<b>Карточка</b> — таблица. Клик открывает её граф: что на что влияет внутри.')
      + row('<span class="l" style="background:var(--strong)"></span>', '<b>Линия</b> — таблицы связаны общим ключом (например, «ID пользователя»). Число на линии — сколько значимых связей нашлось между признаками двух таблиц. Клик — список.')
      + row('<span class="l dash"></span>', '<b>Пунктир</b> — прямого ключа нет, таблицы связаны через центральную.'))
    + `<h4>Клавиши</h4>` + row('<kbd>⌘K</kbd>', 'поиск таблиц, колонок и действий') + row('<kbd>Esc</kbd>', S.focus ? 'закрыть панель → назад ко всем таблицам' : 'закрыть панель')
    + row('<kbd>F</kbd>', 'вписать граф в экран') + row('<kbd>?</kbd>', 'эта справка');
  p.classList.add('show');
}

/** Подсказка внизу сцены — по режиму. */
function renderHints() {
  $('help').innerHTML = !S.tables.size ? ''
    : S.focus ? '<kbd>клик</kbd> раскрыть колонку · <kbd>наведение</kbd> связи · <kbd>Esc</kbd> все таблицы · <kbd>⌘K</kbd> поиск · <kbd>?</kbd> справка'
    : '<kbd>клик</kbd> открыть таблицу · <kbd>линия</kbd> связи таблиц · <kbd>⌘K</kbd> поиск · <kbd>?</kbd> справка';
}

/** Открыть таблицу и показать колонку на графе (из поиска, профиля колонок, ИИ). */
async function focusColumn(dsId, column) {
  if (App.current !== 'Graph') App.view('Graph');
  if (S.focus !== dsId) await drill(dsId);
  const n = [...S.nodes.values()].find(x => x.dsId === dsId && x.depth === 1 && x.column === column)
    || [...S.nodes.values()].find(x => x.dsId === dsId && x.label === column);
  if (!n) { toast(`Колонка «${column}» не участвует в графе (идентификатор или текст)`); return; }
  setTimeout(() => {
    focusOn([n, ...children(n.id)]);
    S._pinnedHl = false; highlightNode(n); S._pinnedHl = true;
    setTimeout(() => { S._pinnedHl = false; unhighlight(); }, 2500);
    select(n.id);
  }, 500);
}

/** Снимок графа как PNG dataURL (для экспорта и для отправки картинки ИИ). */
function graphDataUrl(scale = 2) {
  return new Promise(res => {
    const el = $('g'), W = el.clientWidth, H = el.clientHeight;
    const clone = el.cloneNode(true); clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg'); clone.setAttribute('width', W); clone.setAttribute('height', H);
    const css = [...document.styleSheets].filter(x => (x.href || '').includes('app.css')).flatMap(x => [...x.cssRules]).map(r => r.cssText).join('\n')
      .replace(/var\(--([a-z0-9]+)\)/g, (m, v) => getComputedStyle(document.documentElement).getPropertyValue('--' + v));
    const st = document.createElement('style'); st.textContent = css; clone.insertBefore(st, clone.firstChild);
    const bg = document.createElementNS('http://www.w3.org/2000/svg', 'rect'); bg.setAttribute('width', '100%'); bg.setAttribute('height', '100%'); bg.setAttribute('fill', cssVar('--bg')); clone.insertBefore(bg, clone.firstChild.nextSibling);
    clone.querySelectorAll('.pulse').forEach(p => p.remove());
    const img = new Image(), url = URL.createObjectURL(new Blob([new XMLSerializer().serializeToString(clone)], {type: 'image/svg+xml;charset=utf-8'}));
    img.onload = () => { const c = document.createElement('canvas'); c.width = W * scale; c.height = H * scale; const x = c.getContext('2d'); x.scale(scale, scale); x.drawImage(img, 0, 0); URL.revokeObjectURL(url); res(c.toDataURL(scale > 1 ? 'image/png' : 'image/jpeg', 0.85)); };
    img.src = url;
  });
}
async function exportPng() { const u = await graphDataUrl(2); const a = document.createElement('a'); a.download = 'analitik-graph.png'; a.href = u; a.click(); }

/* ═══════════════════════ API графа для ИИ ═══════════════════════ */
const GraphAPI = {
  /** action: expand | focus | highlight | reset; targets: [{column, value?}] */
  async act(action, targets = []) {
    if (!S.ds) return;
    if (action === 'reset' || S.focus !== S.ds.id) { await drill(S.ds.id); if (action === 'reset') return; }
    const ids = [];
    for (const t of targets) {
      const col = S.ds.columns.find(c => c.name === t.column || c.name.toLowerCase() === String(t.column).toLowerCase());
      if (!col) continue;
      const varId = S.ds.id + '|' + (col.type === 'NUMERIC' ? 'm::' : 'd::') + col.name;
      const v = S.nodes.get(varId); if (!v) continue;
      ids.push(varId);
      if (t.value !== undefined && t.value !== null && t.value !== '' && col.type !== 'NUMERIC') {
        if (!S.expanded.has(varId)) await toggle(v);
        const cat = [...S.nodes.values()].find(n => n.kind === 'cat' && n.parent === varId && String(n.value).toLowerCase() === String(t.value).toLowerCase());
        if (cat) { ids.push(cat.id); if (action === 'expand' && !S.expanded.has(cat.id)) await toggle(cat); children(cat.id).forEach(c => ids.push(c.id)); }
      } else if (action === 'expand' && !S.expanded.has(varId)) { await toggle(v); children(varId).forEach(c => ids.push(c.id)); }
    }
    if (!ids.length) return;
    setTimeout(() => {
      focusOn(ids.map(id => S.nodes.get(id)).filter(Boolean));
      S._pinnedHl = false; highlightIds(ids); S._pinnedHl = true;
      setTimeout(() => { S._pinnedHl = false; unhighlight(); }, 4000);
      select(ids.find(id => id.includes('|c:')) || ids[0]);
    }, 450);
  },
};

async function openDatasetById(id) {
  try { loading(true, 'Открываю таблицу…'); const ds = await api('/datasets/' + id); await openDataset(ds, 0); } catch (e) { toast(e.message); } finally { loading(false); }
}
// центрируем пустую сцену; граф таблиц строит shell.js при старте (после загрузки всех модулей)
svg.call(zoom.transform, d3.zoomIdentity.translate($('stage').clientWidth / 2, $('stage').clientHeight / 2));
// сцена поменяла ширину (открыли/закрыли ИИ-панель, окно) — вписываем граф заново
if (window.ResizeObserver) {
  let rt, rw = 0;
  new ResizeObserver(() => {
    const w = $('stage').clientWidth; if (!w || Math.abs(w - rw) < 40) return; rw = w;
    clearTimeout(rt); rt = setTimeout(() => { if (S.nodes.size && App.current === 'Graph') fit(); }, 200);
  }).observe($('stage'));
}

/* ═══════════════════════ тема ═══════════════════════ */
$('bTheme').onclick = () => {
  const t = isDark() ? 'light' : 'dark'; document.documentElement.dataset.theme = t;
  try { localStorage.setItem('ag.theme', t); } catch (e) { }
  if (S.nodes.size) restart(0.02);
  document.dispatchEvent(new Event('theme'));
};
