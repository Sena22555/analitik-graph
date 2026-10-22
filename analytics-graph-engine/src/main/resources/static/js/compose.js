/* ═══════════════════════ «Собрать из графа»: отчёт из нескольких таблиц без раздувания сумм ═══════════════════════ */
const Compose = {
  dims: [], measures: [], result: null, timer: null, seq: 0,

  AGG: {sum: 'сумма', avg: 'среднее', min: 'минимум', max: 'максимум', distinct: 'уникальных', rows: 'записей', entities: 'клиентов', share: 'доля клиентов, %'},
  GRAIN: {day: 'по дням', week: 'по неделям', month: 'по месяцам', quarter: 'по кварталам', year: 'по годам'},

  tables() { return [...S.tables.values()].filter(t => !t.source); },
  tname(id) { return S.tables.get(id)?.name || '?'; },

  /** preset: {dims, measures} — открыть с готовым набором (например, из панели таблицы). */
  open(preset) {
    if (!this.tables().length) return toast('Сначала загрузите таблицы');
    if (preset) { this.dims = preset.dims || []; this.measures = preset.measures || []; }
    if (!this.measures.length && !this.dims.length) this.suggest();
    $('bigBody').innerHTML = `<div class="cp">
      <div class="cp-h"><div><div class="eyebrow">Собрать из графа</div><h3>Отчёт из нескольких таблиц</h3>
        <div class="st-sub">Выберите разрезы и показатели из любых таблиц — граф сам соединит их по общим ключам и посчитает так, чтобы суммы не раздувались.</div></div></div>
      <div class="cp-body">
        <aside class="cp-src" id="cpSrc"></aside>
        <section class="cp-main">
          <div class="cp-sel"><div class="cp-row"><span class="cp-lbl">Разрезы</span><div id="cpDims" class="cp-chips"></div></div>
            <div class="cp-row"><span class="cp-lbl">Показатели</span><div id="cpMeasures" class="cp-chips"></div></div>
            <div class="cp-row"><span class="cp-lbl">Путь</span><div id="cpPath" class="cp-path"></div></div></div>
          <div id="cpNotes"></div>
          <div class="cp-res dv-grid" id="cpRes"></div>
          <details class="cp-sql"><summary>Показать SQL, который построил граф</summary><pre id="cpSql"></pre></details>
          <div class="cp-acts"><button class="btn primary sm" id="cpSave" disabled><svg class="i "><use href="#i-plus"/></svg>Сохранить как таблицу</button>
            <button class="btn sm" id="cpSqlOpen" disabled><svg class="i "><use href="#i-sql"/></svg>Открыть в SQL (график)</button>
            <button class="btn sm" id="cpXlsx" disabled><svg class="i "><use href="#i-download"/></svg>Excel</button></div>
        </section>
      </div></div>`;
    $('mBig').classList.add('show');
    $('cpSave').onclick = () => this.save();
    $('cpSqlOpen').onclick = () => { $('mBig').classList.remove('show'); Sql.open(this.result.sql); };
    $('cpXlsx').onclick = () => this.exportXlsx();
    this.renderSource(); this.renderSelection(); this.run();
  },

  /** Стартовый набор: разрез из центральной таблицы и «доля клиентов» из первой связанной таблицы фактов. */
  suggest() {
    const tables = this.tables(), center = tables.find(t => t.isEntity) || tables[0];
    const cats = (center.columnList || []).filter(c => c.type === 'CATEGORICAL');
    const dim = [/источник|source|канал|channel/i, /тариф|plan|сегмент|segment/i, /устройств|device/i].map(re => cats.find(c => re.test(c.name))).find(Boolean) || cats[0];
    if (dim) this.dims = [{table: center.id, column: dim.name}];
    this.measures = [{table: center.id, agg: 'entities'}];
    const fact = tables.find(t => t.id !== center.id && (t.columnList || []).some(c => c.type === 'NUMERIC' && /сумм|выруч|amount|revenue/i.test(c.name)));
    if (fact) {
      this.measures.push({table: fact.id, agg: 'share'});
      const money = fact.columnList.find(c => c.type === 'NUMERIC' && /сумм|выруч|amount|revenue/i.test(c.name));
      this.measures.push({table: fact.id, column: money.name, agg: 'sum'});
    }
  },

  renderSource() {
    const TYPE = {NUMERIC: 'число', CATEGORICAL: 'категория', DATE: 'дата', ID: 'ID', TEXT: 'текст'};
    $('cpSrc').innerHTML = this.tables().map(t => `<div class="cp-t">
      <div class="cp-th"><span class="dot" style="border-color:var(${WS.color[t.id] || '--ds1'})"></span><b>${esc(t.name)}</b><small>${fmtInt(t.rows)}</small></div>
      <div class="cp-quick"><button data-m="${esc(t.id)}|rows">+ записей</button>${t.isEntity ? `<button data-m="${esc(t.id)}|entities">+ клиентов</button>` : `<button data-m="${esc(t.id)}|share">+ доля клиентов</button><button data-m="${esc(t.id)}|entities">+ клиентов</button>`}</div>
      ${(t.columnList || []).map(c => {
        const num = c.type === 'NUMERIC', dimable = ['CATEGORICAL', 'DATE'].includes(c.type);
        return `<div class="cp-c"><span title="${esc(TYPE[c.type] || '')}">${esc(c.name)}</span>
          ${dimable ? `<button data-d="${esc(t.id)}|${esc(c.name)}|${c.type === 'DATE' ? 'month' : ''}" title="Добавить как разрез">разрез</button>` : ''}
          ${num ? `<button data-mc="${esc(t.id)}|${esc(c.name)}|sum" title="Сумма">Σ</button><button data-mc="${esc(t.id)}|${esc(c.name)}|avg" title="Среднее">x̄</button>` : ''}
          ${!num && c.type !== 'DATE' ? `<button data-mc="${esc(t.id)}|${esc(c.name)}|distinct" title="Число уникальных">#</button>` : ''}</div>`;
      }).join('')}</div>`).join('');
    const src = $('cpSrc');
    src.querySelectorAll('[data-d]').forEach(b => b.onclick = () => { const [table, column, grain] = b.dataset.d.split('|'); this.add('dims', {table, column, grain: grain || undefined}); });
    src.querySelectorAll('[data-m]').forEach(b => b.onclick = () => { const [table, agg] = b.dataset.m.split('|'); this.add('measures', {table, agg}); });
    src.querySelectorAll('[data-mc]').forEach(b => b.onclick = () => { const [table, column, agg] = b.dataset.mc.split('|'); this.add('measures', {table, column, agg}); });
  },

  add(kind, item) {
    const same = x => x.table === item.table && x.column === item.column && (kind === 'dims' || x.agg === item.agg);
    if (this[kind].some(same)) return toast('Уже добавлено', true);
    this[kind].push(item); this.renderSelection(); this.run();
  },

  renderSelection() {
    const chip = (x, i, kind) => {
      const title = kind === 'dims' ? x.column : x.column || this.AGG[x.agg];
      let ctl = '';
      if (kind === 'dims' && x.grain) ctl = `<select data-g="${i}">${Object.entries(this.GRAIN).map(([k, v]) => `<option value="${k}" ${k === x.grain ? 'selected' : ''}>${v}</option>`).join('')}</select>`;
      if (kind === 'measures' && x.column) {
        const opts = (S.tables.get(x.table)?.columnList || []).find(c => c.name === x.column)?.type === 'NUMERIC' ? ['sum', 'avg', 'min', 'max'] : ['distinct'];
        ctl = `<select data-a="${i}">${opts.map(k => `<option value="${k}" ${k === x.agg ? 'selected' : ''}>${this.AGG[k]}</option>`).join('')}</select>`;
      }
      return `<span class="cp-chip ${kind}"><i class="dot" style="background:var(${WS.color[x.table] || '--ds1'})"></i><small>${esc(this.tname(x.table))}</small><b>${esc(title)}</b>${ctl}<button data-x="${kind}|${i}" title="Убрать">×</button></span>`;
    };
    $('cpDims').innerHTML = this.dims.map((x, i) => chip(x, i, 'dims')).join('') || '<span class="empty-s">без разреза — один итог; добавьте «разрез» слева</span>';
    $('cpMeasures').innerHTML = this.measures.map((x, i) => chip(x, i, 'measures')).join('') || '<span class="empty-s">добавьте показатель слева</span>';
    document.querySelectorAll('.cp-chip [data-x]').forEach(b => b.onclick = () => { const [k, i] = b.dataset.x.split('|'); this[k].splice(+i, 1); this.renderSelection(); this.run(); });
    document.querySelectorAll('.cp-chip [data-g]').forEach(s => s.onchange = () => { this.dims[+s.dataset.g].grain = s.value; this.run(); });
    document.querySelectorAll('.cp-chip [data-a]').forEach(s => s.onchange = () => { this.measures[+s.dataset.a].agg = s.value; this.renderSelection(); this.run(); });
  },

  run() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.exec(), 250);
  },

  async exec() {
    const my = ++this.seq;
    ['cpSave', 'cpSqlOpen', 'cpXlsx'].forEach(id => $(id).disabled = true);
    if (!this.measures.length) { $('cpRes').innerHTML = '<div class="empty-big">Добавьте хотя бы один показатель.</div>'; $('cpPath').innerHTML = ''; $('cpNotes').innerHTML = ''; return; }
    $('cpRes').innerHTML = '<div class="empty-big">Считаю…</div>';
    try {
      const r = await api('/sql/compose', {method: 'POST', body: JSON.stringify({dims: this.dims, measures: this.measures})});
      if (my !== this.seq) return;
      this.result = r;
      this.renderPath(r.path);
      $('cpNotes').innerHTML = (r.explain || []).map(t => `<div class="cp-note">${esc(t)}</div>`).join('') + (r.warnings || []).map(t => `<div class="cp-note warn">${esc(t)}</div>`).join('');
      $('cpSql').textContent = r.sql;
      this.renderTable(r);
      ['cpSave', 'cpSqlOpen', 'cpXlsx'].forEach(id => $(id).disabled = false);
    } catch (e) {
      if (my !== this.seq) return;
      this.result = null;
      $('cpRes').innerHTML = `<div class="sql-err">${esc(e.message)}</div>`;
      $('cpNotes').innerHTML = ''; $('cpPath').innerHTML = '';
    }
  },

  renderPath(path) {
    if (!path || !path.length) { $('cpPath').innerHTML = ''; return; }
    const c = path.find(p => p.center) || path[0], rest = path.filter(p => p !== c);
    $('cpPath').innerHTML = `<span class="cp-node"><i class="dot" style="background:var(${WS.color[c.id] || '--ds1'})"></i>${esc(c.table)}</span>`
      + rest.map(p => `<span class="cp-edge">${esc(p.key || '')}</span><span class="cp-node"><i class="dot" style="background:var(${WS.color[p.id] || '--ds1'})"></i>${esc(p.table)}</span>`).join('');
  },

  /** Таблица результата; у первого показателя — полоска величины для быстрого сравнения групп. */
  renderTable(r) {
    const num = r.types.map(t => t === 'NUMERIC');
    const first = num.findIndex(Boolean), max = first < 0 ? 1 : Math.max(...r.rows.map(x => Math.abs(+x[first] || 0)), 1e-9);
    $('cpRes').innerHTML = `<table class="dt"><thead><tr>${r.header.map((h, i) => `<th class="${num[i] ? 'r' : ''}">${esc(h)}</th>`).join('')}</tr></thead><tbody>
      ${r.rows.map(row => `<tr>${row.map((v, i) => `<td class="${num[i] ? 'r' : ''}">${i === first && v != null ? `<span class="cp-bar"><i style="width:${Math.abs(+v) / max * 100}%"></i></span>` : ''}${v == null ? '<span class="nul">—</span>' : num[i] ? fmt(v) : esc(v)}</td>`).join('')}</tr>`).join('')}</tbody></table>`
      + (r.rows.length ? '' : '<div class="empty-big">Нет строк.</div>');
  },

  async save() {
    const name = prompt('Название таблицы (появится на графе и будет пересчитываться сама):', this.measures.map(m => m.column || this.AGG[m.agg]).join(', ') + (this.dims.length ? ' по ' + this.dims.map(d => d.column).join(', ') : ''));
    if (!name) return;
    try {
      const ds = await api('/sql/save', {method: 'POST', body: JSON.stringify({sql: this.result.sql, name})});
      $('mBig').classList.remove('show');
      await WS.load(); await loadWorkspace(true);
      toast(`Таблица «${ds.name}» на графе: стрелки показывают, из каких таблиц она собрана`, true);
    } catch (e) { toast(e.message); }
  },

  exportXlsx() {
    const r = this.result; if (!r) return;
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([r.header, ...r.rows]), 'Отчёт');
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([['Как посчитано'], ...(r.explain || []).map(x => [x]), [''], ['SQL'], [r.sql]]), 'Как посчитано');
    XLSX.writeFile(wb, 'отчёт-из-графа.xlsx');
  },
};
