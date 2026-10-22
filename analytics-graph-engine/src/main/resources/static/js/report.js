/* ═══════════════════════ Отчёты-документы (ГОСТ 7.32-2017) и общая библиотека ═══════════════════════ */
const Report = {
  docs: [], cur: null,
  init() {
    try { this.docs = JSON.parse(localStorage.getItem('ag.reports') || '[]'); } catch (e) { this.docs = []; }
    $('rpNewDoc').onclick = () => { AI.open(); AI.send('Подготовь официальный аналитический отчёт по ГОСТ 7.32-2017 по этим данным: качество данных, структура аудитории, финансовые показатели и юнит-экономика, драйверы выручки (многофакторная модель), воронка, динамика с прогнозом, выводы и план мероприятий.'); };
  },
  persist() { try { localStorage.setItem('ag.reports', JSON.stringify(this.docs.slice(0, 30))); } catch (e) { } },
  add(r) { this.docs = this.docs.filter(x => x.id !== r.id); this.docs.unshift(r); this.persist(); Library.list(); },

  /** Сквозная нумерация рисунков/таблиц и разделов — общая для предпросмотра и PDF. */
  outline(r) {
    let fig = 0, tab = 0;
    const secs = [{title: 'Методология и исходные данные', auto: true, blocks: [
      {type: 'text', text: `Источник данных — набор «${r.dataset}» (${fmtInt(r.rows)} записей)${r.period ? ', период: ' + r.period : ''}. Обработка выполнена в системе Analitik Graph; расчёты воспроизводимы.`},
      {type: 'text', text: 'Применённые методы: ' + (r.methods || '') },
      {type: 'text', text: 'Описание структуры данных приведено в приложении А.'}]}].concat(r.sections || []);
    secs.forEach((s, i) => { s.no = i + 1; (s.blocks || []).forEach(b => { if (b.type === 'chart' && b.chart) b.fig = ++fig; if (b.type === 'table' && b.table) b.tab = ++tab; }); });
    const recTab = (r.recommendations || []).length ? ++tab : null;
    const appTab = ++tab;
    return {secs, figs: fig, tabs: tab, recTab, appTab};
  },

  open(id) {
    const r = this.docs.find(x => x.id === id); if (!r) return;
    this.cur = r; Deck.cur = null; App.view('Reports'); Library.list();
    const o = this.outline(r);
    $('rpMain').innerHTML = `<div class="rp-bar"><h3>${esc(r.title)}</h3><span class="doc-bar-note">${o.secs.length} разд. · ${o.figs} рис. · ${o.tabs} табл.</span>
      <button class="btn sm primary" id="dcPdf"><svg class="i"><use href="#i-download"/></svg>Скачать PDF</button>
      <button class="btn sm" id="dcEdit"><svg class="i"><use href="#i-chat"/></svg>Доработать</button><button class="btn sm icon ghost" id="dcDel" title="Удалить"><svg class="i"><use href="#i-trash"/></svg></button></div>
      <div class="doc-scroll" id="dcScroll"></div>`;
    $('dcPdf').onclick = () => this.pdf(r);
    $('dcEdit').onclick = () => { AI.open(); $('aiInput').value = `Доработай отчёт «${r.title}»: `; $('aiInput').focus(); };
    $('dcDel').onclick = () => { this.docs = this.docs.filter(x => x.id !== r.id); this.persist(); this.cur = null; Library.list(); $('rpMain').innerHTML = '<div class="empty-big">Отчёт удалён.</div>'; };
    this.preview(r, o, $('dcScroll')); this.zoom();
  },

  preview(r, o, host) {
    const yr = new Date(r.createdAt || Date.now()).getFullYear(), P = t => `<p>${esc(t)}</p>`;
    let h = `<div class="sheet title-page"><div class="org">${esc(r.organization || '')}</div>
      <div class="mid"><div class="rtype">${esc(r.report_type || 'Аналитический отчёт')}</div><div class="rtitle">${esc(r.title)}</div>${r.period ? `<div class="per">за период ${esc(r.period)}</div>` : ''}</div>
      <div><div class="who">Исполнитель: ${esc(r.author || 'аналитический отдел')}<br>Подготовлено с использованием Analitik Graph</div><br><div class="yr">${yr}</div></div></div>`;
    h += `<div class="sheet"><h1>Реферат</h1><p>Отчёт ${o.figs} рис., ${o.tabs} табл., 1 прил.</p>${(r.keywords || []).length ? `<p class="kw">${esc(r.keywords.join(', '))}</p>` : ''}${P(r.abstract || '')}
      <h1 style="margin-top:40px">Содержание</h1><div class="toc">${['Введение'].concat(o.secs.map(s => `${s.no} ${s.title}`), ['Заключение', 'Приложение А. Описание исходных данных']).map(x => `<div><span>${esc(x)}</span><span></span><span></span></div>`).join('')}</div></div>`;
    h += `<div class="sheet"><h1>Введение</h1>${(r.introduction || '').split(/\n+/).map(P).join('')}`;
    o.secs.forEach(s => {
      h += `<h2>${s.no} ${esc(s.title)}</h2>`;
      (s.blocks || []).forEach(b => {
        if (b.error) h += `<div class="call warning">Не удалось построить: ${esc(b.error)}</div>`;
        else if (b.type === 'text') h += String(b.text || '').split(/\n+/).map(P).join('');
        else if (b.type === 'bullets') h += `<ul>${(b.items || []).map(x => `<li>${esc(x)}</li>`).join('')}</ul>`;
        else if (b.type === 'callout') h += `<div class="call ${esc(b.tone || '')}">${esc(b.text)}</div>`;
        else if (b.type === 'kpis') h += `<div class="kgrid">${(b.kpis || []).map(k => `<div><b>${esc(k.value)}</b><span>${esc(k.label)}${k.note ? ' — ' + esc(k.note) : ''}</span></div>`).join('')}</div>`;
        else if (b.type === 'chart' && b.chart) h += `<div class="fig" data-fig="${b.fig}"></div><div class="cap">Рисунок ${b.fig} — ${esc(b.caption || b.chart.title)}</div>`;
        else if (b.type === 'table' && b.table) h += `<div class="tcap">Таблица ${b.tab} — ${esc(b.caption || b.table.title)}</div>${tableHtml(b.table, {maxRows: 25, heat: false})}${b.table.summary ? `<div class="tnote">Примечание — ${esc(b.table.summary)}</div>` : ''}`;
      });
    });
    h += `<h2 style="text-align:center;text-transform:uppercase;margin-top:34px">Заключение</h2><ol>${(r.conclusions || []).map(x => `<li>${esc(x)}</li>`).join('')}</ol>`;
    if (o.recTab) h += `<div class="tcap">Таблица ${o.recTab} — План мероприятий</div>` + tableHtml(this.recTable(r), {heat: false});
    if ((r.limitations || []).length) h += `<p><b>Ограничения исследования.</b> ${esc(r.limitations.join(' '))}</p>`;
    h += `</div><div class="sheet"><h1>Приложение А<br><span style="font-weight:400;text-transform:none">(справочное)</span><br>Описание исходных данных</h1><div class="tcap">Таблица ${o.appTab} — Состав показателей набора данных</div>${tableHtml(r.appendix, {heat: false})}</div>`;
    host.innerHTML = h;
    o.secs.forEach(s => (s.blocks || []).forEach(b => { if (b.fig) { const el = host.querySelector(`[data-fig="${b.fig}"]`); if (el) chartHtmlInto(el, b.chart, {w: 620, h: 300, palette: ['#1F3A93', '#A0521A', '#2E7D5B', '#6B6B6B', '#8C2F39', '#3B6E8F']}); } }));
  },
  zoom() { const sc = $('dcScroll'); if (!sc) return; const k = Math.min(1, (sc.clientWidth - 40) / 794); sc.style.setProperty('--dz', k); },
  recTable(r) {
    return {type: 'table', head: ['№', 'Мероприятие', 'Ожидаемый эффект', 'Приоритет', 'Ответственный', 'Метрика контроля'], formats: ['text', 'text', 'text', 'text', 'text', 'text'], keyCols: 1,
      rows: (r.recommendations || []).map((x, i) => [String(i + 1), x.action || '', x.effect || '—', x.priority || '—', x.owner || '—', x.metric || '—'])};
  },

  /** PDF по ГОСТ 7.32-2017: поля 30/15/20/20 мм, PT Serif 12–14 pt, полуторный интервал, номера страниц внизу по центру. */
  async pdf(r) {
    if (!window.pdfMake) return toast('Модуль PDF ещё загружается, повторите через пару секунд');
    loading(true, 'Формирую PDF по ГОСТ…');
    try {
      const base = location.origin.startsWith('http') ? location.origin : 'http://localhost:8080';
      pdfMake.addFonts({PTSerif: {normal: base + '/fonts/PTSerif_400Regular.ttf', bold: base + '/fonts/PTSerif_700Bold.ttf', italics: base + '/fonts/PTSerif_400Regular_Italic.ttf', bolditalics: base + '/fonts/PTSerif_700Bold_Italic.ttf'}});
      if (pdfMake.setUrlAccessPolicy) pdfMake.setUrlAccessPolicy(u => u.startsWith(base));
      const o = this.outline(r), figs = {};
      for (const s of o.secs) for (const b of (s.blocks || [])) if (b.fig) figs[b.fig] = await svgToPng(chartSvg(b.chart, {w: 900, h: 440, big: true, fs: 20, palette: ['#1F3A93', '#A0521A', '#2E7D5B', '#6B6B6B', '#8C2F39', '#3B6E8F']}), 900, 440, {font: '"PT Serif", Georgia, serif', ink: '#111', muted: '#333', grid: '#DDDDDD'});
      let pages = 0;
      const build = () => this.docDef(r, o, figs, pages);
      // двухпроходная сборка: сначала узнаём число страниц для реферата
      const dd = build(); const origFooter = dd.footer;
      dd.footer = (cur, cnt) => { pages = cnt; return origFooter(cur, cnt); };
      await pdfMake.createPdf(pdfSafe(dd)).getBlob();
      await pdfMake.createPdf(pdfSafe(build())).download(safeName(r.title) + '.pdf');
      toast('PDF сохранён', true);
    } catch (e) { console.error(e); toast('PDF: ' + e.message); } finally { loading(false); }
  },

  docDef(r, o, figs, pages) {
    const yr = new Date(r.createdAt || Date.now()).getFullYear();
    const para = t => String(t || '').split(/\n+/).filter(Boolean).map(x => ({text: x, style: 'p'}));
    const table = (tb, maxRows = 40) => {
      const f = tb.formats || [], k = tb.keyCols || 1, rows = tb.rows.slice(0, maxRows), nc = tb.head.length, fs = nc > 8 ? 7.5 : nc > 6 ? 8.5 : 10;
      const body = [tb.head.map(h => ({text: String(h), style: 'th', fontSize: fs}))]
        .concat(rows.map(rw => rw.map((c, i) => ({text: fmtVal(c, f[i]), style: 'td', fontSize: fs, alignment: i < k || typeof c === 'string' && String(c).length > 14 ? 'left' : 'right'}))));
      if (tb.totals) body.push(tb.totals.map((c, i) => ({text: fmtVal(c, f[i]), style: 'td', fontSize: fs, bold: true, alignment: i < k ? 'left' : 'right'})));
      return {table: {headerRows: 1, widths: tb.head.map((h, i) => i < k && nc <= 7 ? 'auto' : '*'), body, dontBreakRows: true}, layout: {hLineWidth: () => .6, vLineWidth: () => .6, paddingTop: () => 2, paddingBottom: () => 2, paddingLeft: () => 4, paddingRight: () => 4}, margin: [0, 0, 0, 4]};
    };
    const H1 = (t, toc = true) => ({text: t.toUpperCase(), style: 'h1', pageBreak: 'before', tocItem: toc, tocStyle: {bold: false}, tocMargin: [0, 2, 0, 2]});
    const content = [];
    // титульный лист
    content.push({text: (r.organization || '').toUpperCase(), style: 'center', margin: [0, 0, 0, 0]});
    content.push({text: '', margin: [0, 200, 0, 0]});
    content.push({text: (r.report_type || 'Аналитический отчёт').toUpperCase(), style: 'center', fontSize: 14, characterSpacing: .5});
    content.push({text: r.title, style: 'center', bold: true, fontSize: 18, margin: [0, 14, 0, 10]});
    if (r.period) content.push({text: 'за период ' + r.period, style: 'center', fontSize: 14});
    content.push({text: '', margin: [0, 190, 0, 0]});
    content.push({text: ['Исполнитель: ', r.author || 'аналитический отдел', '\nПодготовлено с использованием Analitik Graph'], alignment: 'right', fontSize: 12, lineHeight: 1.3});
    content.push({text: String(yr), style: 'center', margin: [0, 40, 0, 0], fontSize: 13});
    // реферат
    content.push({text: 'РЕФЕРАТ', style: 'h1', pageBreak: 'before'});
    content.push({text: `Отчёт ${pages ? pages + ' с., ' : ''}${o.figs} рис., ${o.tabs} табл., 1 прил.`, style: 'p'});
    if ((r.keywords || []).length) content.push({text: r.keywords.join(', ').toUpperCase(), style: 'p'});
    content.push(...para(r.abstract));
    // содержание
    content.push({toc: {title: {text: 'СОДЕРЖАНИЕ', style: 'h1'}, textStyle: {fontSize: 13}, numberStyle: {fontSize: 13}, textMargin: [0, 2, 0, 2]}, pageBreak: 'before'});
    // введение
    content.push(H1('Введение'));
    content.push(...para(r.introduction));
    // основная часть
    o.secs.forEach(s => {
      content.push({text: `${s.no} ${s.title}`, style: 'h2', headlineLevel: 1, tocItem: true, tocMargin: [0, 2, 0, 2], pageBreak: s.no === 1 ? 'before' : undefined, keepWithNext: true});
      (s.blocks || []).forEach(b => {
        if (b.error) content.push({text: 'Не удалось построить: ' + b.error, style: 'p', italics: true});
        else if (b.type === 'text') content.push(...para(b.text));
        else if (b.type === 'bullets') content.push({ul: (b.items || []).map(x => ({text: x, style: 'li'})), margin: [35, 0, 0, 8]});
        else if (b.type === 'callout') content.push({table: {widths: ['*'], body: [[{text: b.text, style: 'p', margin: [4, 4, 4, 2]}]]}, layout: {hLineWidth: () => 0, vLineWidth: i => i === 0 ? 2 : 0, fillColor: () => '#F4F4F4'}, margin: [0, 4, 0, 10]});
        else if (b.type === 'kpis') content.push({table: {widths: (b.kpis || []).map(() => '*'), body: [(b.kpis || []).map(k => ({stack: [{text: k.value, bold: true, fontSize: 15}, {text: k.label + (k.note ? ' — ' + k.note : ''), fontSize: 11}], margin: [4, 4, 4, 4]}))]}, layout: {hLineWidth: () => .6, vLineWidth: () => .6}, margin: [0, 4, 0, 12]});
        else if (b.type === 'chart' && figs[b.fig]) content.push({stack: [{image: figs[b.fig], width: 440, alignment: 'center', margin: [0, 6, 0, 4]}, {text: `Рисунок ${b.fig} — ${b.caption || b.chart.title}`, style: 'cap'}], unbreakable: true});
        else if (b.type === 'table' && b.table) {
          content.push({text: `Таблица ${b.tab} — ${b.caption || b.table.title}`, style: 'tcap', headlineLevel: 1});
          content.push(table(b.table, 30));
          if (b.table.summary) content.push({text: 'Примечание — ' + b.table.summary, fontSize: 11, margin: [0, 2, 0, 10], alignment: 'justify'});
        }
      });
    });
    // заключение
    content.push(H1('Заключение'));
    content.push({ol: (r.conclusions || []).map(x => ({text: x, style: 'li'})), margin: [35, 0, 0, 10]});
    if (o.recTab) { content.push({text: `Таблица ${o.recTab} — План мероприятий`, style: 'tcap', keepWithNext: true}); content.push(table(this.recTable(r))); }
    if ((r.limitations || []).length) content.push({text: [{text: 'Ограничения исследования. ', bold: true}, r.limitations.join(' ')], style: 'p', margin: [0, 10, 0, 0]});
    // приложение
    content.push({text: ['ПРИЛОЖЕНИЕ А\n', {text: '(справочное)\n', bold: false}, 'Описание исходных данных'], style: 'h1', pageBreak: 'before', tocItem: true});
    content.push({text: `Таблица ${o.appTab} — Состав показателей набора данных`, style: 'tcap'});
    content.push(table(r.appendix, 80));
    return {
      pageSize: 'A4', pageMargins: [85, 57, 43, 57],
      pageBreakBefore: (node, next) => node.headlineLevel === 1 && next.length < 2,
      info: {title: r.title, author: r.author || 'Analitik Graph', subject: r.report_type || 'Аналитический отчёт', creator: 'Analitik Graph'},
      defaultStyle: {font: 'PTSerif', fontSize: 13, lineHeight: 1.25},
      footer: (cur) => cur === 1 ? null : {text: String(cur), alignment: 'center', fontSize: 12, margin: [0, 20, 0, 0]},
      styles: {
        p: {alignment: 'justify', leadingIndent: 35, margin: [0, 0, 0, 6]},
        li: {alignment: 'justify', margin: [0, 0, 0, 3]},
        h1: {fontSize: 14, bold: true, alignment: 'center', margin: [0, 0, 0, 16]},
        h2: {fontSize: 14, bold: true, margin: [35, 14, 0, 10]},
        center: {alignment: 'center'},
        cap: {alignment: 'center', fontSize: 12, margin: [0, 2, 0, 12]},
        tcap: {fontSize: 12, margin: [0, 8, 0, 4]},
        th: {bold: true, fontSize: 10, alignment: 'center'},
        td: {fontSize: 10},
      },
      content,
    };
  },
};

/* ═══════════════════════ Библиотека: документы и презентации ═══════════════════════ */
const Library = {
  list() {
    const items = Report.docs.map(x => ({k: 'doc', x})).concat(Deck.decks.map(x => ({k: 'deck', x}))).sort((a, b) => (b.x.createdAt || 0) - (a.x.createdAt || 0));
    $('deckCnt').textContent = items.length || '';
    const cur = (Report.cur && Report.cur.id) || (Deck.cur && Deck.cur.id);
    $('rpList').innerHTML = items.length ? '<div class="saved">' + items.map(({k, x}) => `<div class="it ${cur === x.id ? 'on' : ''}" data-k="${k}" data-id="${esc(x.id)}"><span class="tag">${k === 'doc' ? 'Отчёт · PDF' : 'Презентация · ' + ((THEMES[x.theme] || THEMES.executive).ru)}</span><br>${esc(x.title)}<small>${k === 'deck' ? x.slides.length + ' слайдов · ' : ''}${esc(x.dataset || '')} · ${new Date(x.createdAt || Date.now()).toLocaleString('ru-RU', {day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit'})}</small></div>`).join('') + '</div>'
      : '<div class="empty-s">Попросите аналитика подготовить отчёт или презентацию — они появятся здесь.</div>';
    $('rpList').querySelectorAll('.it').forEach(el => el.onclick = () => el.dataset.k === 'doc' ? Report.open(el.dataset.id) : Deck.open(el.dataset.id));
  },
};

/** PT Serif не содержит греческих букв и некоторых символов — заменяем на типографские эквиваленты. */
const PDF_MAP = [[/\u202F/g, '\u00A0'], [/χ²/g, 'хи-квадрат'], [/χ/g, 'хи'], [/σ/g, 'ст. откл.'], [/ρ/g, 'ро'], [/η²/g, 'эта²'], [/η/g, 'эта'], [/ω²/g, 'омега²'], [/β/g, 'бета'], [/α/g, 'альфа'],
  [/→/g, '–>'], [/⟷/g, '<–>'], [/≥/g, '>='], [/≤/g, '<='], [/≈/g, '~'], [/x̄/g, 'среднее'], [/[★✓✗]/g, '']];
function pdfSafeText(t) { let s = String(t); for (const [re, v] of PDF_MAP) s = s.replace(re, v); return s; }
function pdfSafe(node, key) {
  if (typeof node === 'string') return key === 'image' || key === 'style' || key === 'font' || key === 'alignment' ? node : pdfSafeText(node);
  if (Array.isArray(node)) return node.map(x => pdfSafe(x, key));
  if (node && typeof node === 'object') { for (const k of Object.keys(node)) if (typeof node[k] !== 'function') node[k] = pdfSafe(node[k], k); return node; }
  return node;
}
