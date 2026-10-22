/* ═══════════════════════ рабочее пространство: несколько баз ═══════════════════════ */
const WS = {
  list: [],               // базы с ролями
  entity: null,           // id базы-сущности
  entityKey: null,
  color: {},              // id базы → CSS-переменная цвета

  async load() {
    try {
      const w = await api('/workspace');
      this.list = w.datasets; this.entity = w.entity; this.entityKey = w.entityKey;
    } catch (e) { this.list = []; }
    this.list.forEach((d, i) => this.color[d.id] = `--ds${i % 6 + 1}`);
    $('wsCnt').textContent = this.list.length > 1 ? this.list.length : '';
    // цвет таблицы на графе зависит от порядка — обновляем отрисовку
    if (typeof restart === 'function' && S.nodes.size) restart(0.02);
    this.render();
    return this.list;
  },

  colorOf(id) { return cssVar(this.color[id] || '--ds1'); },

  render() {
    $('wsN').textContent = this.list.length || '';
    if (!this.list.length) { $('wsList').innerHTML = '<div class="empty-s">Таблиц пока нет. Откройте «Демо-продукт» или загрузите файл.</div>'; return; }
    const journey = App.current === 'Journey';
    $('wsList').innerHTML = this.list.map(d => `<div class="ws-it ${typeof S !== 'undefined' && S.focus === d.id ? 'on' : ''}" data-id="${esc(d.id)}" title="${journey ? 'Клик — открыть в графе' : 'Клик — открыть граф таблицы'}">
      <span class="dot" style="border-color:var(${this.color[d.id]})"></span>
      <span class="nm">${esc(d.name)}<small>${fmtInt(d.rows)} строк${d.keyColumn ? ' · ключ «' + esc(d.keyColumn) + '»' : ''}${d.isEntity ? ' · центр' : ''}</small></span>
      <span class="role">${esc(d.roleRu || '')}</span></div>`).join('');
    $('wsList').querySelectorAll('.ws-it').forEach(el => el.onclick = async () => {
      const id = el.dataset.id;
      if (App.current !== 'Graph') App.view('Graph');
      await drill(id);
    });
  },

  async demoProduct() {
    loading(true, 'Генерирую 4 связанные базы и считаю статистику…');
    try {
      const r = await api('/demo/product', {method: 'POST'});
      await this.load();
      App.view('Graph');
      S.focus = null;
      await loadWorkspace(true);
      toast(`Демо-продукт: ${r.datasets.map(d => `${d.name} (${fmtInt(d.rows)})`).join(', ')}. Кликните по таблице, чтобы открыть её граф.`, true);
    } catch (e) { toast(e.message); } finally { loading(false); }
  },
};

/** Список находок в сайдбаре (общий для атласа и путей). */
function renderWsInsights(title, items, onClick) {
  $('wsInsT').firstChild.textContent = title + ' ';
  $('wsInsCnt').textContent = items.length || '';
  $('wsIns').innerHTML = items.length
    ? items.map((x, i) => `<div class="ins ${x.level || 'none'}" data-i="${i}">${x.k ? `<span class="k">${esc(x.k)}</span>` : ''}${esc(x.text)}</div>`).join('')
    : '<div class="empty-s">Значимых закономерностей не найдено.</div>';
  $('wsIns').querySelectorAll('.ins').forEach(el => el.onclick = () => onClick && onClick(items[+el.dataset.i]));
}

/** Зум и панорамирование для сцены. */
function wsZoom(svgSel, vpSel, stageId, onZoom, onResize) {
  const svgEl = d3.select(svgSel), vpEl = d3.select(vpSel);
  const z = d3.zoom().scaleExtent([0.15, 3.5]).on('zoom', e => { vpEl.attr('transform', e.transform); onZoom && onZoom(e.transform.k); });
  svgEl.call(z).on('dblclick.zoom', null);
  document.querySelectorAll(`#${stageId} .wzoom [data-z]`).forEach(b => b.onclick = () => {
    if (b.dataset.z === 'in') svgEl.transition().duration(250).call(z.scaleBy, 1.35);
    else if (b.dataset.z === 'out') svgEl.transition().duration(250).call(z.scaleBy, 0.74);
  });
  // сцена меняет размер (открыли/закрыли ИИ-панель, окно) — вписываем граф заново
  if (onResize && window.ResizeObserver) {
    let t, w = 0;
    new ResizeObserver(() => {
      const nw = $(stageId).clientWidth; if (!nw || Math.abs(nw - w) < 40) return; w = nw;
      clearTimeout(t); t = setTimeout(onResize, 180);
    }).observe($(stageId));
  }
  return {
    zoom: z,
    fit(points, pad = 90, rightPanel) {
      if (!points.length) return;
      const st = $(stageId), W = st.clientWidth - (rightPanel ? 390 : 0), H = st.clientHeight;
      const xs = points.map(p => p.x), ys = points.map(p => p.y);
      const x0 = Math.min(...xs) - pad, x1 = Math.max(...xs) + pad, y0 = Math.min(...ys) - pad, y1 = Math.max(...ys) + pad;
      const k = Math.min(1.4, W / (x1 - x0), (H - 50) / (y1 - y0));
      svgEl.transition().duration(600).call(z.transform, d3.zoomIdentity.translate(W / 2 - k * (x0 + x1) / 2, 25 + (H - 50) / 2 - k * (y0 + y1) / 2).scale(k));
    },
  };
}

function wsEmpty(id, title, text, withDemo) {
  const el = $(id);
  if (!title) { el.classList.remove('show'); el.innerHTML = ''; return; }
  el.innerHTML = `<div class="box"><h3>${esc(title)}</h3><div>${esc(text)}</div>${withDemo ? '<button class="btn primary" data-demo><svg class="i "><use href="#i-play"/></svg>Открыть демо-продукт</button>' : ''}</div>`;
  el.classList.add('show');
  const b = el.querySelector('[data-demo]'); if (b) b.onclick = () => WS.demoProduct();
}

$('wsDemo').onclick = () => WS.demoProduct();
$('wsAdd').onclick = () => $('file').click();
document.querySelectorAll('.hero-main[data-act="product"]').forEach(b => b.onclick = () => WS.demoProduct());
// любые изменения набора баз (новый файл, API, срез от ИИ) — обновляем список
document.addEventListener('ds:open', () => WS.load());
WS.load();
