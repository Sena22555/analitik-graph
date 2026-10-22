/* ═══════════════════════ SQL-консоль (DuckDB по всем таблицам) ═══════════════════════ */
const Sql = {
  schema: null, result: null, lastSql: '', busy: false, series: null,
  HIST_KEY: 'ag.sqlHistory',

  init() {
    $('sqlRun').onclick = () => this.run();
    $('sqlEd').addEventListener('keydown', e => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); this.run(); }
      if (e.key === 'Tab') { e.preventDefault(); this.insert('  '); }
    });
    $('sqlSave').onclick = () => this.save();
    $('sqlXlsx').onclick = () => this.exportXlsx();
    $('sqlAsk').onclick = () => {
      AI.setCtx({label: 'SQL-консоль', node: {kind: 'sql', current: $('sqlEd').value.slice(0, 2000)}}); AI.open();
      $('aiInput').value = 'Напиши и выполни SQL-запрос (run_sql), который покажет: '; $('aiInput').dispatchEvent(new Event('input')); $('aiInput').focus();
    };
    document.querySelectorAll('.sql-tabs button').forEach(b => b.onclick = () => this.tab(b.dataset.t));
    this.renderHistory();
  },

  async show(sql, run) {
    try { this.schema = await api('/sql/schema'); } catch (e) { toast(e.message); return; }
    this.renderSchema();
    if (sql) { $('sqlEd').value = sql; if (run) this.run(); }
    else if (!$('sqlEd').value && this.schema.examples.length) $('sqlEd').value = this.schema.examples[0].sql;
    setTimeout(() => $('sqlEd').focus(), 30);
  },

  /** Открыть консоль с готовым запросом (например, из графа таблицы). */
  open(sql) { App.view('Sql'); this.show(sql, true); },

  renderSchema() {
    const t = this.schema.tables;
    $('sqlTn').textContent = t.length || '';
    const TYPE = {NUMERIC: 'число', CATEGORICAL: 'категория', DATE: 'дата', ID: 'ID', TEXT: 'текст'};
    $('sqlSchema').innerHTML = t.length ? t.map((x, i) => `<details class="sq-t" ${i === 0 ? 'open' : ''}><summary><span class="dot" style="border-color:var(${WS.color[x.id] || '--ds1'})"></span><b data-ins="${esc(SqlQ(x.name))}" title="Вставить имя таблицы">${esc(x.name)}</b><small>${fmtInt(x.rows)}</small></summary>
      ${x.columns.map(c => `<div class="sq-c" data-ins="${esc(SqlQ(c.name))}" title="Вставить колонку"><span>${esc(c.name)}</span><small>${TYPE[c.type] || ''}</small></div>`).join('')}</details>`).join('')
      : '<div class="empty-s">Таблиц пока нет.</div>';
    $('sqlSchema').querySelectorAll('[data-ins]').forEach(el => el.onclick = e => { e.preventDefault(); e.stopPropagation(); this.insert(el.dataset.ins); });
    $('sqlEx').innerHTML = this.schema.examples.map((x, i) => `<div class="ws-path sq-ex" data-i="${i}">${esc(x.title)}</div>`).join('') || '<div class="empty-s">—</div>';
    $('sqlEx').querySelectorAll('.sq-ex').forEach(el => el.onclick = () => { $('sqlEd').value = this.schema.examples[+el.dataset.i].sql; this.run(); });
  },

  insert(text) {
    const ed = $('sqlEd'), a = ed.selectionStart, b = ed.selectionEnd;
    ed.value = ed.value.slice(0, a) + text + ed.value.slice(b);
    ed.selectionStart = ed.selectionEnd = a + text.length; ed.focus();
  },

  async run() {
    const sql = $('sqlEd').value.trim(); if (!sql || this.busy) return;
    this.busy = true; $('sqlSt').textContent = 'Выполняется…'; $('sqlSt').className = 'sql-st';
    try {
      const r = await api('/sql/query', {method: 'POST', body: JSON.stringify({sql, limit: 5000})});
      this.result = r; this.lastSql = sql; this.series = null;
      $('sqlSt').textContent = `${fmtInt(r.rows.length)}${r.truncated ? '+' : ''} ${plural(r.rows.length, 'строка', 'строки', 'строк')} · ${r.ms} мс${r.truncated ? ' · показаны первые 5 000' : ''}`;
      $('sqlSave').disabled = false; $('sqlXlsx').disabled = false;
      this.renderGrid();
      if (!$('sqlChart').hidden) this.renderChart();
      this.remember(sql);
    } catch (e) {
      this.result = null; $('sqlSave').disabled = true; $('sqlXlsx').disabled = true;
      $('sqlSt').textContent = 'Ошибка'; $('sqlSt').className = 'sql-st err';
      $('sqlGrid').innerHTML = `<div class="sql-err">${esc(e.message)}</div>`;
    } finally { this.busy = false; }
  },

  renderGrid() {
    const r = this.result, num = r.types.map(t => t === 'NUMERIC');
    const cell = (v, i) => v == null ? '<span class="nul">null</span>' : num[i] ? fmt(v) : esc(v);
    $('sqlGrid').innerHTML = `<table class="dt"><thead><tr><th class="rn">#</th>${r.header.map((h, i) => `<th class="${num[i] ? 'r' : ''}">${esc(h)}</th>`).join('')}</tr></thead>
      <tbody>${r.rows.slice(0, 1000).map((row, k) => `<tr><td class="rn">${k + 1}</td>${row.map((v, i) => `<td class="${num[i] ? 'r' : ''}">${cell(v, i)}</td>`).join('')}</tr>`).join('')}</tbody></table>`
      + (r.rows.length > 1000 ? '<div class="empty-s" style="padding:10px">В таблице показаны первые 1 000 строк — полный результат в Excel.</div>' : '')
      + (r.rows.length ? '' : '<div class="empty-big">Запрос выполнен, строк нет.</div>');
  },

  tab(t) {
    document.querySelectorAll('.sql-tabs button').forEach(b => b.classList.toggle('on', b.dataset.t === t));
    $('sqlGrid').hidden = t !== 'grid'; $('sqlChart').hidden = t !== 'chart';
    if (t === 'chart') this.renderChart();
  },

  /** График по результату: первая нечисловая колонка — подписи, числовые — серии (столбцы, для дат — линия). */
  renderChart() {
    const el = $('sqlChart'), r = this.result;
    if (!r || !r.rows.length) { el.innerHTML = '<div class="empty-big">Сначала выполните запрос.</div>'; return; }
    const numIdx = r.types.map((t, i) => t === 'NUMERIC' ? i : -1).filter(i => i >= 0);
    let labIdx = r.types.findIndex(t => t !== 'NUMERIC');
    if (!numIdx.length) { el.innerHTML = '<div class="empty-big">Для графика нужна хотя бы одна числовая колонка.</div>'; return; }
    const all = numIdx.filter(i => i !== labIdx);
    if (!this.series || this.series.some(i => !all.includes(i))) this.series = all.slice(0, 1);
    const series = this.series;
    if (labIdx < 0) labIdx = null;
    const rows = r.rows.slice(0, 60);
    const labels = rows.map((row, k) => labIdx == null ? String(k + 1) : String(row[labIdx] ?? '—'));
    const isTime = labIdx != null && r.types[labIdx] === 'DATE';
    const W = el.clientWidth || 800, H = Math.max(240, (el.clientHeight || 360) - 56), m = {l: 64, r: 16, t: 12, b: 70};
    const x = d3.scaleBand().domain(labels.map((l, i) => i)).range([m.l, W - m.r]).padding(0.18);
    const vals = rows.flatMap(row => series.map(i => +row[i] || 0));
    const y = d3.scaleLinear().domain([Math.min(0, d3.min(vals)), d3.max(vals) || 1]).nice().range([H - m.b, m.t]);
    const color = s => cssVar(`--ds${all.indexOf(series[s]) % 6 + 1}`);
    const sv = d3.create('svg').attr('width', W).attr('height', H).attr('class', 'sq-svg');
    sv.append('g').attr('transform', `translate(${m.l},0)`).call(d3.axisLeft(y).ticks(6).tickFormat(v => fmt(v)).tickSize(-(W - m.l - m.r))).call(g => g.select('.domain').remove()).call(g => g.selectAll('line').attr('stroke', cssVar('--line')));
    const step = Math.ceil(labels.length / 20);
    sv.append('g').attr('transform', `translate(0,${H - m.b})`).call(d3.axisBottom(x).tickFormat(i => i % step ? '' : trunc(labels[i], 14)))
      .selectAll('text').attr('transform', 'rotate(-35)').style('text-anchor', 'end');
    const bw = x.bandwidth() / series.length;
    series.forEach((ci, s) => {
      if (isTime) {
        sv.append('path').datum(rows).attr('fill', 'none').attr('stroke', color(s)).attr('stroke-width', 2.2)
          .attr('d', d3.line().x((row, k) => x(k) + x.bandwidth() / 2).y(row => y(+row[ci] || 0)));
      } else {
        sv.append('g').selectAll('rect').data(rows).join('rect').attr('fill', color(s)).attr('rx', 2)
          .attr('x', (row, k) => x(k) + s * bw).attr('width', Math.max(1, bw - 1))
          .attr('y', row => y(Math.max(0, +row[ci] || 0))).attr('height', row => Math.abs(y(+row[ci] || 0) - y(0)))
          .append('title').text((row, k) => `${labels[k]}: ${fmt(row[ci])}`);
      }
    });
    el.innerHTML = `<div class="sq-series">${all.map(ci => `<button class="chip ${series.includes(ci) ? 'on' : ''}" data-c="${ci}"><i style="background:var(--ds${all.indexOf(ci) % 6 + 1})"></i>${esc(trunc(r.header[ci], 26))}</button>`).join('')}<span class="empty-s">клик — показать / скрыть серию</span></div>`;
    el.appendChild(sv.node());
    el.querySelectorAll('.sq-series [data-c]').forEach(b => b.onclick = () => {
      const ci = +b.dataset.c;
      this.series = series.includes(ci) ? (series.length > 1 ? series.filter(x => x !== ci) : series) : [...series, ci].sort((a, b) => all.indexOf(a) - all.indexOf(b));
      this.renderChart();
    });
  },

  async save() {
    if (!this.lastSql) return;
    const name = prompt('Название новой таблицы (появится на графе и будет обновляться этим запросом):', 'Результат SQL');
    if (!name) return;
    try {
      const ds = await api('/sql/save', {method: 'POST', body: JSON.stringify({sql: this.lastSql, name})});
      await WS.load(); await loadWorkspace(false);
      toast(`Таблица «${ds.name}» (${fmtInt(ds.rows)} строк) добавлена на граф`, true);
      this.show();
    } catch (e) { toast(e.message); }
  },

  exportXlsx() {
    const r = this.result; if (!r) return;
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([r.header, ...r.rows]), 'Результат');
    XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([['SQL'], [this.lastSql]]), 'Запрос');
    XLSX.writeFile(wb, 'sql-результат.xlsx');
  },

  remember(sql) {
    let h = [];
    try { h = JSON.parse(localStorage.getItem(this.HIST_KEY) || '[]'); } catch (e) { }
    h = [sql, ...h.filter(x => x !== sql)].slice(0, 12);
    try { localStorage.setItem(this.HIST_KEY, JSON.stringify(h)); } catch (e) { }
    this.renderHistory(h);
  },
  renderHistory(h) {
    if (!h) { try { h = JSON.parse(localStorage.getItem(this.HIST_KEY) || '[]'); } catch (e) { h = []; } }
    $('sqlHist').innerHTML = h.length ? h.map((x, i) => `<div class="ws-path sq-h" data-i="${i}" title="${esc(x)}">${esc(trunc(x.replace(/\s+/g, ' '), 90))}</div>`).join('') : '<div class="empty-s">Выполненные запросы появятся здесь.</div>';
    $('sqlHist').querySelectorAll('.sq-h').forEach(el => el.onclick = () => { $('sqlEd').value = h[+el.dataset.i]; $('sqlEd').focus(); });
  },
};
const SqlQ = name => '"' + String(name).replace(/"/g, '""') + '"';

Sql.init();
