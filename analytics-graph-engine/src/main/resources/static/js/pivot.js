/* ═══════════════════════ «Сводные»: конструктор сводных таблиц ═══════════════════════ */
const Pivot = {
  current: null,          // последняя построенная таблица
  saved: [],              // таблицы из чата ИИ
  aggs: [['count', 'Количество'], ['share', 'Доля строк'], ['sum', 'Сумма'], ['mean', 'Среднее'], ['median', 'Медиана'], ['rate', 'Доля > 0'], ['min', 'Минимум'], ['max', 'Максимум'], ['distinct', 'Уникальных']],

  init() {
    $('pvAgg').innerHTML = this.aggs.map(([k, v]) => `<option value="${k}">${v}</option>`).join('');
    $('pvRun').onclick = () => this.run();
    $('pvXlsx').onclick = () => this.current ? exportXlsx(this.current) : toast('Сначала постройте таблицу');
    $('pvCsv').onclick = () => this.current ? exportCsv(this.current) : toast('Сначала постройте таблицу');
    $('pvAddF').onclick = () => this.addFilter();
    $('pvAsk').onclick = () => {
      if (!this.current) return toast('Сначала постройте таблицу');
      AI.open(); AI.send(`Разбери сводную таблицу «${this.current.title}»: главные выводы, аномалии, значимы ли различия (проверь compare_segments), и что делать команде.`, {pivot: this.current.spec});
    };
    $('pvDs').onchange = async () => {
      try { await setActive($('pvDs').value); this.current = null; $('pvOut').innerHTML = '<div class="empty-big">Выберите строки, столбцы и показатель или один из готовых срезов.</div>'; $('pvChart').hidden = true; }
      catch (e) { toast(e.message); }
    };
    ['pvR1', 'pvR2', 'pvC', 'pvAgg', 'pvV'].forEach(id => $(id).addEventListener('change', () => { this.syncAgg(); if (this.current) this.run(); }));
    document.addEventListener('ds:open', () => this.fill());
  },
  /** Список таблиц в выпадающем меню; если активной нет — берём первую. */
  async showTables() {
    const tables = [...S.tables.values()];
    $('pvDs').innerHTML = tables.map(t => `<option value="${esc(t.id)}">${esc(t.name)} · ${fmtInt(t.rows)}</option>`).join('') || '<option value="">— нет таблиц —</option>';
    if (!tables.length) return;
    if (!S.ds || !S.tables.has(S.ds.id)) await setActive(S.focus || tables[0].id);
    $('pvDs').value = S.ds.id;
  },
  cols(types) { return S.ds ? S.ds.columns.filter(c => types.includes(c.type)).map(c => c.name) : []; },
  fill() {
    const dims = this.cols(['CATEGORICAL', 'DATE']).concat(this.cols(['NUMERIC'])), nums = this.cols(['NUMERIC']);
    const opt = (arr, none) => (none ? `<option value="">${none}</option>` : '') + arr.map(c => `<option>${esc(c)}</option>`).join('');
    const keep = id => $(id).value;
    const v = {r1: keep('pvR1'), r2: keep('pvR2'), c: keep('pvC'), val: keep('pvV')};
    $('pvR1').innerHTML = opt(dims, '— нет —'); $('pvR2').innerHTML = opt(dims, '+ второе'); $('pvC').innerHTML = opt(dims, '— нет —'); $('pvV').innerHTML = opt(nums, '— колонка —');
    if (v.r1 && dims.includes(v.r1)) $('pvR1').value = v.r1; if (v.r2 && dims.includes(v.r2)) $('pvR2').value = v.r2; if (v.c && dims.includes(v.c)) $('pvC').value = v.c; if (v.val && nums.includes(v.val)) $('pvV').value = v.val;
    this.syncAgg(); this.presets();
    $('pvFilters').querySelectorAll('.fl-row').forEach(r => this.fillFilterCols(r.querySelector('select')));
  },
  syncAgg() { const a = $('pvAgg').value; $('pvV').style.display = ['count', 'share'].includes(a) ? 'none' : ''; },
  presets() {
    if (!S.ds) return;
    const k = S.ds.kpis, dims = this.cols(['CATEGORICAL']).filter(d => d !== S.ds.funnelColumn), ps = [];
    const cnt = S.ds.columns.find(c => /покуп|purchase|заказ|order/i.test(c.name) && c.type === 'NUMERIC');
    if (dims[0]) ps.push({label: `Структура: ${dims[0]} × ${dims[1] || ''}`.replace(/ × $/, ''), spec: {rows: [dims[0]], columns: dims[1], agg: 'count'}});
    if (cnt) dims.slice(0, 3).forEach(d => ps.push({label: `Доля купивших по «${d}»`, spec: {rows: [d], values: cnt.name, agg: 'rate'}}));
    if (k[0]) dims.slice(0, 3).forEach(d => ps.push({label: `${k[0]}: среднее по «${d}»`, spec: {rows: [d], values: k[0], agg: 'mean'}}));
    if (k[0] && dims[1]) ps.push({label: `${k[0]}: сумма ${dims[0]} × ${dims[1]}`, spec: {rows: [dims[0]], columns: dims[1], values: k[0], agg: 'sum'}});
    const dt = S.ds.columns.find(c => c.type === 'DATE'); if (dt && k[0]) ps.push({label: `${k[0]} по времени`, spec: {rows: [dt.name], values: k[0], agg: 'sum'}});
    $('pvPresets').innerHTML = ps.map((p, i) => `<button data-i="${i}">${esc(p.label)}</button>`).join('');
    $('pvPresets').querySelectorAll('button').forEach(b => b.onclick = () => this.load(ps[+b.dataset.i].spec));
  },
  addFilter(f = {}) {
    const row = document.createElement('div'); row.className = 'fl-row';
    row.innerHTML = `<select></select><select><option>=</option><option>!=</option><option>&gt;</option><option>&gt;=</option><option>&lt;</option><option>&lt;=</option><option value="contains">содержит</option></select><input placeholder="значение" list=""><button title="Убрать">×</button>`;
    const [cs, os] = row.querySelectorAll('select'), inp = row.querySelector('input');
    this.fillFilterCols(cs); if (f.column) cs.value = f.column; if (f.op) os.value = f.op; if (f.value !== undefined) inp.value = f.value ?? (f.values || []).join(',');
    const dl = 'dl' + Math.random().toString(36).slice(2); inp.setAttribute('list', dl);
    const upd = () => { let d = document.getElementById(dl); if (!d) { d = document.createElement('datalist'); d.id = dl; row.appendChild(d); } const n = S.nodes.get(S.ds.id + '|d::' + cs.value); d.innerHTML = (n?.bars || []).map(b => `<option value="${esc(b.label)}">`).join(''); };
    cs.onchange = upd; upd();
    row.querySelector('button').onclick = () => row.remove();
    $('pvFilters').appendChild(row);
  },
  fillFilterCols(sel) { const v = sel.value; sel.innerHTML = (S.ds?.columns || []).filter(c => c.type !== 'ID').map(c => `<option>${esc(c.name)}</option>`).join(''); if (v) sel.value = v; },
  spec() {
    const rows = [$('pvR1').value, $('pvR2').value].filter(Boolean);
    const agg = $('pvAgg').value;
    const filters = [...$('pvFilters').querySelectorAll('.fl-row')].map(r => { const [c, o] = r.querySelectorAll('select'); return {column: c.value, op: o.value, value: r.querySelector('input').value}; }).filter(f => f.value !== '');
    return {rows, columns: $('pvC').value || null, agg, values: ['count', 'share'].includes(agg) ? null : $('pvV').value || null, filters};
  },
  load(spec) {
    $('pvR1').value = (spec.rows || [])[0] || ''; $('pvR2').value = (spec.rows || [])[1] || ''; $('pvC').value = spec.columns || '';
    $('pvAgg').value = spec.agg || 'count'; if (spec.values) $('pvV').value = spec.values; this.syncAgg();
    $('pvFilters').innerHTML = ''; (spec.filters || []).forEach(f => this.addFilter(f));
    App.view('Pivot'); this.run();
  },
  async run() {
    if (!S.ds) return toast('Сначала загрузите данные');
    const spec = this.spec();
    if (!['count', 'share'].includes(spec.agg) && !spec.values) return toast('Выберите числовую колонку для показателя');
    try {
      const t = await api(`/datasets/${S.ds.id}/pivot`, {method: 'POST', body: JSON.stringify(spec)});
      this.show(t);
    } catch (e) { toast(e.message); }
  },
  show(t) {
    this.current = t;
    $('pvOut').innerHTML = `<h3>${esc(t.title)}</h3><div class="sub">${esc(t.subtitle || '')}</div>` + tableHtml(t);
    // автографик по первой числовой колонке
    const k = t.keyCols || 1, cats = t.rows.map(r => r.slice(0, k).join(' · '));
    const vi = t.head.length - (t.heat ? 1 : (t.formats[k + 1] === 'int' || t.formats[k + 1] === 'pct' ? 2 : 1));
    const valCols = t.heat ? t.head.slice(k, -1).map((h, i) => ({name: h, data: t.rows.map(r => r[k + i])})) : [{name: t.head[k], data: t.rows.map(r => r[k])}];
    if (cats.length > 1 && cats.length <= 40) {
      $('pvChart').hidden = false;
      const c = {chartType: 'bar', categories: cats, series: valCols.slice(0, 8), format: t.formats[k]};
      $('pvChart').innerHTML = '<div class="sub" style="margin-bottom:6px">Визуализация</div><div id="pvChartIn"></div>';
      chartHtmlInto($('pvChartIn'), c, {w: 900, h: 300, fs: 12});
    } else $('pvChart').hidden = true;
  },
  addSaved(t) {
    if (!t.spec) return;
    this.saved.unshift({t, at: Date.now()});
    $('pvSaved').innerHTML = this.saved.map((x, i) => `<div class="it" data-i="${i}">${esc(x.t.title)}<small>${new Date(x.at).toLocaleTimeString('ru-RU', {hour: '2-digit', minute: '2-digit'})} · ${esc(x.t.subtitle || '')}</small></div>`).join('');
    $('pvSaved').querySelectorAll('.it').forEach(el => el.onclick = () => this.load(this.saved[+el.dataset.i].t.spec));
  },
};
