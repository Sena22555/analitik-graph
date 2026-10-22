/* ═══════════════════════ просмотр данных таблицы ═══════════════════════ */
const TablePanel = {
  id: null, offset: 0, limit: 100, q: '', sort: null, desc: false, data: null, timer: null, profile: null,

  TYPE_RU: {NUMERIC: 'число', CATEGORICAL: 'категория', DATE: 'дата', ID: 'ID', TEXT: 'текст'},

  /** tab: 'rows' — строки, 'profile' — профиль колонок. */
  async open(id, tab = 'rows') {
    this.id = id; this.offset = 0; this.q = ''; this.sort = null; this.desc = false; this.profile = null;
    const t = S.tables.get(id) || {name: ''};
    $('bigBody').innerHTML = `<div class="dv">
      <div class="dv-h"><h3>${esc(t.name)}</h3><span class="dv-n" id="dvN"></span>
        <div class="seg-tabs"><button data-dv="rows" class="on">Строки</button><button data-dv="profile">Профиль колонок</button></div></div>
      <div id="dvProfile" class="dv-prof" hidden></div>
      <div id="dvRows">
      <div class="dv-bar">
        <div class="search dv-s" style="display:flex"><svg class="i "><use href="#i-search"/></svg><input id="dvQ" placeholder="Поиск по всем колонкам"></div>
        <div class="sp"></div>
        <button class="btn sm" id="dvPrev"><svg class="i "><use href="#i-chevl"/></svg></button>
        <span class="dv-pg" id="dvPg"></span>
        <button class="btn sm" id="dvNext"><svg class="i "><use href="#i-chev"/></svg></button>
        <button class="btn sm" id="dvXlsx"><svg class="i "><use href="#i-download"/></svg>Excel</button>
      </div>
      <div class="dv-grid" id="dvGrid"><div class="empty-big">Загрузка…</div></div>
      </div>
    </div>`;
    $('bigBody').querySelectorAll('[data-dv]').forEach(b => b.onclick = () => this.tab(b.dataset.dv));
    $('mBig').classList.add('show');
    $('dvQ').oninput = e => { clearTimeout(this.timer); this.timer = setTimeout(() => { this.q = e.target.value; this.offset = 0; this.load(); }, 250); };
    $('dvPrev').onclick = () => { if (this.offset > 0) { this.offset = Math.max(0, this.offset - this.limit); this.load(); } };
    $('dvNext').onclick = () => { if (this.data && this.offset + this.limit < this.data.filtered) { this.offset += this.limit; this.load(); } };
    $('dvXlsx').onclick = () => this.exportXlsx();
    await this.load();
    if (tab === 'profile') this.tab('profile'); else setTimeout(() => $('dvQ').focus(), 50);
  },

  tab(t) {
    $('bigBody').querySelectorAll('[data-dv]').forEach(b => b.classList.toggle('on', b.dataset.dv === t));
    $('dvRows').hidden = t !== 'rows'; $('dvProfile').hidden = t !== 'profile';
    if (t === 'profile') this.loadProfile();
  },

  async loadProfile() {
    if (this.profile) return;
    $('dvProfile').innerHTML = '<div class="empty-big">Считаю профиль…</div>';
    try {
      const p = await api(`/datasets/${this.id}/profile`);
      this.profile = p;
      this.renderProfile(p);
    } catch (e) { toast(e.message); }
  },

  /** Карточка на каждую колонку: тип, заполненность, статистика / частые значения, мини-распределение, проблемы. */
  renderProfile(p) {
    const issues = p.columns.reduce((a, c) => a + c.issues.filter(x => !/не участвует/.test(x)).length, 0);
    const pct = v => pctRu(v);
    const card = c => {
      const tchip = `<span class="pf-type t-${c.type.toLowerCase()}">${esc(c.typeRu)}</span>`;
      let body = '';
      if (c.type === 'NUMERIC' && c.min !== undefined) {
        body += `<div class="pf-stats"><div><span>мин</span><b>${fmt(c.min)}</b></div><div><span>медиана</span><b>${fmt(c.median)}</b></div><div><span>среднее</span><b>${fmt(c.mean)}</b></div><div><span>макс</span><b>${fmt(c.max)}</b></div></div>`;
        body += this.spark(c.hist, v => fmt(v));
        body += `<div class="pf-note">сумма ${fmt(c.sum)} · нулей ${pct(c.zeros / Math.max(1, c.filled))} · межквартильный размах ${fmt(c.p25)}–${fmt(c.p75)}</div>`;
      } else if (c.type === 'DATE' && c.from) {
        body += `<div class="pf-stats"><div><span>с</span><b>${esc(c.from)}</b></div><div><span>по</span><b>${esc(c.to)}</b></div><div><span>дней</span><b>${fmtInt(c.days)}</b></div></div>`;
        body += this.spark(c.hist, v => new Date(v * 864e5).toLocaleDateString('ru-RU'));
      }
      const mostlyUnique = c.unique > Math.max(50, c.filled * 0.5);
      if (c.top && c.top.length && (c.type === 'ID' || c.type === 'TEXT') && mostlyUnique) {
        body += `<div class="pf-note">почти все значения разные — например: ${c.top.slice(0, 4).map(v => esc(trunc(v.value, 24))).join(', ')}</div>`;
      } else if (c.top && c.top.length && c.type !== 'DATE') {
        body += `<div class="pf-top">${c.top.map(v => `<div class="pf-v"><span class="pf-l" title="${esc(v.value)}">${esc(v.value)}</span><span class="pf-bar"><i style="width:${Math.max(2, v.share * 100)}%"></i></span><b>${pct(v.share)}</b></div>`).join('')}
          ${c.other ? `<div class="pf-note">и ещё ${fmtInt(c.other)} ${plural(c.other, 'значение', 'значения', 'значений')}</div>` : ''}</div>`;
      }
      return `<div class="pf-card ${c.issues.some(x => !/не участвует/.test(x)) ? 'warn' : ''}">
        <div class="pf-h"><b title="${esc(c.name)}">${esc(c.name)}</b>${tchip}</div>
        <div class="pf-fill"><span>заполнено ${pct(c.filledShare)}</span><span class="pf-bar"><i style="width:${c.filledShare * 100}%"></i></span><span>${fmtInt(c.unique)} уник.</span></div>
        ${body}
        ${c.issues.length ? `<div class="pf-iss">${c.issues.map(x => `<span>${esc(x)}</span>`).join('')}</div>` : ''}
        ${c.analyzable ? `<button class="btn sm ghost pf-go" data-col="${esc(c.name)}"><svg class="i "><use href="#i-graph"/></svg>Показать на графе</button>` : ''}
      </div>`;
    };
    $('dvProfile').innerHTML = `<div class="pf-sum">${fmtInt(p.rows)} строк · ${p.columns.length} колонок · ${issues ? `<b class="warn">${issues} ${plural(issues, 'замечание', 'замечания', 'замечаний')} к данным</b>` : '<b class="ok">замечаний к данным нет</b>'}</div>
      <div class="pf-grid">${p.columns.map(card).join('')}</div>`;
    $('dvProfile').querySelectorAll('[data-col]').forEach(b => b.onclick = () => { $('mBig').classList.remove('show'); focusColumn(this.id, b.dataset.col); });
  },

  /** Мини-гистограмма в SVG. */
  spark(hist, label) {
    if (!hist || hist.length < 2) return '';
    const W = 260, H = 46, max = Math.max(...hist.map(b => b[2])) || 1, bw = W / hist.length;
    return `<svg class="pf-spark" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none">${hist.map((b, i) => {
      const h = Math.max(1, b[2] / max * (H - 2));
      return `<rect x="${i * bw + 1}" y="${H - h}" width="${Math.max(1, bw - 2)}" height="${h}" rx="1.5"><title>${esc(label(b[0]))} – ${esc(label(b[1]))}: ${fmtInt(b[2])}</title></rect>`;
    }).join('')}</svg>`;
  },

  url(offset, limit) {
    const p = new URLSearchParams({offset, limit});
    if (this.q) p.set('q', this.q);
    if (this.sort) { p.set('sort', this.sort); p.set('desc', this.desc); }
    return `/datasets/${this.id}/rows?${p}`;
  },

  async load() {
    try {
      const d = await api(this.url(this.offset, this.limit));
      this.data = d;
      const to = Math.min(d.filtered, d.offset + d.rows.length);
      $('dvN').textContent = d.filtered === d.total ? `${fmtInt(d.total)} строк` : `найдено ${fmtInt(d.filtered)} из ${fmtInt(d.total)}`;
      $('dvPg').textContent = d.filtered ? `${fmtInt(d.offset + 1)}–${fmtInt(to)}` : '0';
      $('dvPrev').disabled = d.offset === 0; $('dvNext').disabled = to >= d.filtered;
      const num = d.types.map(t => t === 'NUMERIC');
      $('dvGrid').innerHTML = `<table class="dt"><thead><tr><th class="rn">#</th>${d.header.map((h, i) => `<th data-c="${esc(h)}" class="${num[i] ? 'r' : ''} ${this.sort === h ? 'on' : ''}">${esc(h)}${this.sort === h ? (this.desc ? ' ↓' : ' ↑') : ''}<small>${this.TYPE_RU[d.types[i]] || ''}</small></th>`).join('')}</tr></thead>
        <tbody>${d.rows.map((r, k) => `<tr><td class="rn">${fmtInt(d.offset + k + 1)}</td>${d.header.map((h, i) => `<td class="${num[i] ? 'r' : ''}">${esc(r[i] ?? '')}</td>`).join('')}</tr>`).join('')}</tbody></table>`
        + (d.rows.length ? '' : '<div class="empty-big">Ничего не найдено.</div>');
      $('dvGrid').querySelectorAll('th[data-c]').forEach(th => th.onclick = () => {
        const c = th.dataset.c;
        if (this.sort === c) this.desc = !this.desc; else { this.sort = c; this.desc = false; }
        this.offset = 0; this.load();
      });
    } catch (e) { toast(e.message); }
  },

  async exportXlsx() {
    try {
      const d = await api(this.url(0, 1000));
      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, XLSX.utils.aoa_to_sheet([d.header, ...d.rows]), 'Данные');
      const name = (S.tables.get(this.id)?.name || 'данные').replace(/[\\/:*?"<>|]/g, '_');
      XLSX.writeFile(wb, `${name}.xlsx`);
      if (d.filtered > 1000) toast('Выгружены первые 1 000 строк из ' + fmtInt(d.filtered), true);
    } catch (e) { toast(e.message); }
  },
};
