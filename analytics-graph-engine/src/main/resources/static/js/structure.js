/* ═══════════════════════ сортировка сырых данных: план и применение ═══════════════════════ */
const Structure = {
  id: null, plan: null,

  KIND: {missing: 'Пропуски', spaces: 'Пробелы', numbers: 'Числа', dates: 'Даты', variants: 'Написание'},

  async open(id) {
    this.id = id; this.plan = null;
    $('bigBody').innerHTML = '<div class="empty-big">Ищу зависимости и проблемы в данных…</div>';
    $('mBig').classList.add('show');
    try { this.plan = await api(`/datasets/${id}/structure`); this.render(); }
    catch (e) { $('bigBody').innerHTML = `<div class="sql-err">${esc(e.message)}</div>`; }
  },

  render() {
    const p = this.plan;
    const ent = p.tables.filter(t => t.role === 'entity'), fact = p.tables.find(t => t.role !== 'entity');
    const moved = new Set(ent.flatMap(t => t.columns.slice(1)));
    let h = `<div class="st">
      <div class="st-h"><div><div class="eyebrow">Сортировка данных</div><h3>${esc(p.sourceName)}</h3>
        <div class="st-sub">${fmtInt(p.rows)} строк → ${p.splittable ? `<b>${p.tables.length} ${plural(p.tables.length, 'связанная таблица', 'связанные таблицы', 'связанных таблиц')}</b>` : '<b>одна чистая таблица</b>'} · исправлено ${fmtInt(p.fixes)} ${plural(p.fixes, 'значение', 'значения', 'значений')}</div></div></div>`;

    // 1. что почищено
    h += `<div class="st-sec"><h4><span class="st-n">1</span>Чистка</h4>`;
    h += p.cleaning.length || p.duplicates ? `<div class="st-ops">${p.duplicates ? `<div class="st-op"><span class="st-k">Дубликаты</span><div><b>${fmtInt(p.duplicates)}</b> одинаковых строк удалено</div></div>` : ''}
      ${p.cleaning.map(o => `<div class="st-op"><span class="st-k">${this.KIND[o.kind] || o.kind}</span><div><b>${esc(o.column)}</b>: ${esc(o.text)} — <b>${fmtInt(o.cells)}</b>${o.examples.length ? `<div class="st-ex">${o.examples.map(esc).join(' · ')}</div>` : ''}</div></div>`).join('')}</div>`
      : '<div class="empty-s">Данные уже чистые.</div>';
    h += `</div>`;

    // 2. найденные зависимости
    h += `<div class="st-sec"><h4><span class="st-n">2</span>Зависимости в данных</h4>`;
    h += p.dependencies.length ? p.dependencies.map(d => `<div class="st-fd"><code>${esc(d.key)}</code><span class="st-arrow">определяет</span>${d.columns.map(c => `<code>${esc(c)}</code>`).join('')}
        <small>у каждого из ${fmtInt(d.keyValues)} значений ключа эти колонки одинаковы в ${pctRu(d.strength)} строк</small></div>`).join('')
      : '<div class="empty-s">Ключей, которые определяют другие колонки, не найдено — таблица уже на своём уровне детализации.</div>';
    h += `</div>`;

    // 3. какие таблицы получатся
    h += `<div class="st-sec"><h4><span class="st-n">3</span>Что получится</h4><div class="st-tables">`;
    h += p.tables.map(t => `<div class="st-t ${t.role}">
        <div class="st-th"><b>${esc(t.name)}</b><span>${fmtInt(t.rows)} ${plural(t.rows, 'строка', 'строки', 'строк')} · ${t.role === 'entity' ? 'сущность' : t.role === 'events' ? 'события' : 'факты'}</span></div>
        ${t.columns.map(c => `<div class="st-c ${c === t.key ? 'key' : c === t.parentKey ? 'fk' : t.role !== 'entity' && [...p.tables].some(x => x.role === 'entity' && x.key === c) ? 'fk' : ''}">${esc(c)}${c === t.key ? '<i>ключ</i>' : c === t.parentKey ? '<i>→ ' + esc(ent.find(x => x.key === c)?.name || '') + '</i>' : ''}</div>`).join('')}
      </div>`).join('');
    h += `</div>${p.splittable ? `<div class="st-note">Колонки ${[...moved].map(c => `«${esc(c)}»`).join(', ')} больше не повторяются в каждой строке — они хранятся один раз в своей таблице. Таблицы связаны ключами и появятся на графе. Исходная таблица останется как источник: если в неё допишут строки, разложенные таблицы пересчитаются сами.</div>` : ''}</div>`;

    h += `<div class="st-acts"><button class="btn primary" id="stGo"><svg class="i "><use href="#i-atlas"/></svg>${p.splittable ? `Разложить на ${p.tables.length} ${plural(p.tables.length, 'таблицу', 'таблицы', 'таблиц')}` : 'Сохранить очищенную таблицу'}</button><button class="btn ghost" data-close-big>Отмена</button></div></div>`;
    $('bigBody').innerHTML = h;
    $('stGo').onclick = () => this.apply();
    $('bigBody').querySelector('[data-close-big]').onclick = () => $('mBig').classList.remove('show');
  },

  async apply() {
    const btn = $('stGo'); btn.disabled = true; btn.textContent = 'Раскладываю…';
    try {
      const r = await api(`/datasets/${this.id}/structure`, {method: 'POST'});
      $('mBig').classList.remove('show');
      App.view('Graph');
      await WS.load();
      S.focus = null;
      await loadWorkspace(true);
      toast(`Готово: ${r.datasets.map(d => `«${d.name}» (${fmtInt(d.rows)})`).join(', ')}. Таблицы связаны и обновляются вслед за источником.`, true);
    } catch (e) { toast(e.message); btn.disabled = false; }
  },
};
