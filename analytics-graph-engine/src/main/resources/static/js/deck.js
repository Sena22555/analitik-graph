/* ═══════════════════════ Презентации: темы, макеты, экспорт PPTX/PDF ═══════════════════════ */
const THEMES = {
  executive: {ru: 'Деловая', head: 'Plex', body: 'Plex', hw: 700, track: '-.6px', quote: 'Plex', qw: 600, radius: '0px',
    bg: '#FFFFFF', surface: '#F2F4F8', ink: '#0B1B33', muted: '#5A6478', accent: '#1F4E9C', onAccent: '#fff', line: '#E1E5EC',
    coverBg: '#0B1B33', coverInk: '#FFFFFF', coverMuted: '#A9B6CC', coverAccent: '#C9A227', sectionBg: '#1F4E9C', sectionInk: '#FFFFFF', sectionAccent: '#C9A227',
    palette: ['#1F4E9C', '#C9A227', '#6C95D8', '#8C96A8', '#2E7D6B', '#B5543C'], pptHead: 'Arial', pptBody: 'Arial'},
  ledger: {ru: 'Финансовая', head: 'PT Serif', body: 'Plex', hw: 700, track: '-.3px', quote: 'PT Serif', qw: 400, radius: '0px',
    bg: '#FBFBF7', surface: '#EEF2EC', ink: '#13261C', muted: '#5F6B63', accent: '#0F5132', onAccent: '#fff', line: '#D9E0D8',
    coverBg: '#0F2E22', coverInk: '#F3F1E8', coverMuted: '#A8B8AC', coverAccent: '#C8A96A', sectionBg: '#0F5132', sectionInk: '#F3F1E8', sectionAccent: '#C8A96A',
    palette: ['#0F5132', '#B08D57', '#4F8A6B', '#8A9A8E', '#C2553A', '#2F5D7C'], pptHead: 'Georgia', pptBody: 'Arial'},
  editorial: {ru: 'Редакционная', head: 'PT Serif', body: 'PT Serif', hw: 700, track: '-.4px', quote: 'PT Serif', qw: 400, radius: '2px',
    bg: '#F6F1E7', surface: '#EDE5D6', ink: '#1D1B16', muted: '#6D665A', accent: '#B4532A', onAccent: '#fff', line: '#E0D6C4',
    coverBg: '#F6F1E7', coverInk: '#1D1B16', coverMuted: '#6D665A', coverAccent: '#B4532A', sectionBg: '#2F4858', sectionInk: '#F6F1E7', sectionAccent: '#E6A57E',
    palette: ['#B4532A', '#2F4858', '#D9A441', '#7A8B6F', '#8C5A7A', '#A89F91'], pptHead: 'Georgia', pptBody: 'Georgia'},
  midnight: {ru: 'Ночная', head: 'Plex', body: 'Plex', hw: 600, track: '-.5px', quote: 'Plex', qw: 500, radius: '14px',
    bg: '#0E1116', surface: '#171C24', ink: '#E8EAED', muted: '#9AA3AE', accent: '#2BC4A9', onAccent: '#0E1116', line: '#262D36',
    coverBg: '#0E1116', coverInk: '#FFFFFF', coverMuted: '#9AA3AE', coverAccent: '#2BC4A9', sectionBg: '#131A22', sectionInk: '#FFFFFF', sectionAccent: '#F2B84B',
    palette: ['#2BC4A9', '#F2B84B', '#7C93FF', '#F0706A', '#A58BFF', '#8C96A8'], pptHead: 'Arial', pptBody: 'Arial'},
  bold: {ru: 'Яркая', head: 'Plex', body: 'Plex', hw: 700, track: '-1px', quote: 'Plex', qw: 700, radius: '18px',
    bg: '#FFFFFF', surface: '#FFF1EC', ink: '#111111', muted: '#5B5B5B', accent: '#FF5A36', onAccent: '#fff', line: '#EDE7E4',
    coverBg: '#FF5A36', coverInk: '#111111', coverMuted: '#3A1A10', coverAccent: '#111111', sectionBg: '#111111', sectionInk: '#FFFFFF', sectionAccent: '#FF5A36',
    palette: ['#FF5A36', '#111111', '#FFB199', '#6B6B6B', '#2F6FED', '#FFC23D'], pptHead: 'Arial Black', pptBody: 'Arial'},
  minimal: {ru: 'Минимализм', head: 'Plex', body: 'Plex', hw: 700, track: '-1.2px', quote: 'Plex', qw: 500, radius: '0px',
    bg: '#FFFFFF', surface: '#F3F3F3', ink: '#0A0A0A', muted: '#6E6E6E', accent: '#E30613', onAccent: '#fff', line: '#DADADA',
    coverBg: '#FFFFFF', coverInk: '#0A0A0A', coverMuted: '#6E6E6E', coverAccent: '#E30613', sectionBg: '#0A0A0A', sectionInk: '#FFFFFF', sectionAccent: '#E30613',
    palette: ['#0A0A0A', '#E30613', '#8A8A8A', '#C8C8C8', '#555555', '#FF8A8A'], pptHead: 'Arial', pptBody: 'Arial'},
};
const FONT_CSS = {'Plex': '"Plex",Arial,sans-serif', 'PT Serif': '"PT Serif",Georgia,serif'};

const Deck = {
  decks: [], cur: null, idx: 0,

  init() {
    try { this.decks = JSON.parse(localStorage.getItem('ag.decks') || '[]'); } catch (e) { this.decks = []; }
    $('rpNew').onclick = () => { AI.open(); AI.send('Подготовь презентацию для руководства по этим данным: ключевые показатели, что влияет на выручку и покупки, воронка, риски и рекомендации. Выбери подходящее оформление.'); };
    window.addEventListener('resize', () => { this.fit(); Report.zoom(); });
    window.addEventListener('keydown', e => {
      if ($('viewReports').hidden || e.target.matches('input,textarea,select') || !this.cur) return;
      if (e.key === 'ArrowRight') this.go(this.idx + 1); if (e.key === 'ArrowLeft') this.go(this.idx - 1);
    });
  },
  persist() { try { localStorage.setItem('ag.decks', JSON.stringify(this.decks.slice(0, 30))); } catch (e) { /* хранилище недоступно */ } },
  add(d) { this.decks = this.decks.filter(x => x.id !== d.id); this.decks.unshift(d); this.persist(); Library.list(); },
  theme(d) { return THEMES[d.theme] || THEMES.executive; },
  vars(t) {
    return `--s-bg:${t.bg};--s-surface:${t.surface};--s-ink:${t.ink};--s-muted:${t.muted};--s-accent:${t.accent};--s-on-accent:${t.onAccent};--s-line:${t.line};` +
      `--s-cover-bg:${t.coverBg};--s-cover-ink:${t.coverInk};--s-cover-muted:${t.coverMuted};--s-cover-accent:${t.coverAccent};` +
      `--s-section-bg:${t.sectionBg};--s-section-ink:${t.sectionInk};--s-section-accent:${t.sectionAccent};` +
      `--s-head:${FONT_CSS[t.head]};--s-body:${FONT_CSS[t.body]};--s-quote:${FONT_CSS[t.quote]};--s-hw:${t.hw};--s-qw:${t.qw};--s-track:${t.track};--s-radius:${t.radius}`;
  },

  open(id) {
    const d = this.decks.find(x => x.id === id); if (!d) return;
    this.cur = d; this.idx = 0; Report.cur = null; App.view('Reports'); Library.list();
    $('rpMain').innerHTML = `<div class="rp-bar"><h3>${esc(d.title)}</h3>
      <select id="rpTheme" title="Оформление">${Object.entries(THEMES).map(([k, t]) => `<option value="${k}" ${k === (d.theme || 'executive') ? 'selected' : ''}>${t.ru}</option>`).join('')}</select>
      <button class="btn sm" id="rpPptx"><svg class="i"><use href="#i-download"/></svg>PowerPoint</button><button class="btn sm" id="rpPdf"><svg class="i"><use href="#i-print"/></svg>PDF</button>
      <button class="btn sm" id="rpEdit"><svg class="i"><use href="#i-chat"/></svg>Доработать</button><button class="btn sm icon ghost" id="rpDel" title="Удалить"><svg class="i"><use href="#i-trash"/></svg></button></div>
      <div class="rp-stage" id="rpStage"><button class="rp-nav l" id="rpPrev"><svg class="i"><use href="#i-chevl"/></svg></button><div class="slide-fit" id="rpFit"></div><button class="rp-nav r" id="rpNext"><svg class="i"><use href="#i-chev"/></svg></button></div>
      <div class="rp-thumbs" id="rpThumbs"></div>`;
    $('rpPrev').onclick = () => this.go(this.idx - 1); $('rpNext').onclick = () => this.go(this.idx + 1);
    $('rpTheme').onchange = e => { d.theme = e.target.value; this.persist(); this.open(d.id); };
    $('rpPptx').onclick = () => this.pptx(d); $('rpPdf').onclick = () => this.pdf(d);
    $('rpDel').onclick = () => { this.decks = this.decks.filter(x => x.id !== d.id); this.persist(); this.cur = null; Library.list(); $('rpMain').innerHTML = '<div class="empty-big">Презентация удалена.</div>'; };
    $('rpEdit').onclick = () => { AI.open(); $('aiInput').value = `Доработай презентацию «${d.title}»: `; $('aiInput').focus(); };
    this.thumbs(d); this.go(0);
  },
  thumbs(d) {
    $('rpThumbs').innerHTML = d.slides.map((s, i) => `<div class="thumb" data-i="${i}"><div class="tsl"></div><span class="n">${i + 1}</span></div>`).join('');
    $('rpThumbs').querySelectorAll('.thumb').forEach(t => { const i = +t.dataset.i; const el = this.render(d, i); el.style.transform = `scale(${128 / 1280})`; el.style.boxShadow = 'none'; t.querySelector('.tsl').appendChild(el); t.onclick = () => this.go(i); });
  },
  go(i) {
    const d = this.cur; if (!d) return; this.idx = Math.max(0, Math.min(d.slides.length - 1, i));
    const fit = $('rpFit'); fit.innerHTML = ''; fit.appendChild(this.render(d, this.idx)); this.fit();
    $('rpThumbs').querySelectorAll('.thumb').forEach(t => t.classList.toggle('on', +t.dataset.i === this.idx));
  },
  fit() {
    const st = $('rpStage'), fit = $('rpFit'); if (!st || !fit || !fit.firstChild) return;
    const k = Math.min((st.clientWidth - 120) / 1280, (st.clientHeight - 36) / 720);
    fit.style.width = 1280 * k + 'px'; fit.style.height = 720 * k + 'px'; fit.firstChild.style.transform = `scale(${k})`;
  },

  /** Слайд → DOM 1280×720. */
  render(d, i) {
    const t = this.theme(d), s = d.slides[i], n = d.slides.length, lay = s.layout || 'bullets';
    const el = document.createElement('div');
    el.className = `slide th-${d.theme || 'executive'} lay-${lay}`; el.setAttribute('style', this.vars(t));
    const img = s.image ? `background-image:url('${s.image}')` : '';
    const foot = `<div class="ft"><span><b>${esc(trunc(d.title, 60))}</b></span><span>${String(i + 1).padStart(2, '0')} / ${String(n).padStart(2, '0')}</span></div>`;
    const kick = txt => txt ? `<div class="kick">${esc(txt)}</div>` : '';
    const head = (k) => `<div class="hd">${kick(k)}<h2>${esc(s.title)}</h2>${s.subtitle ? `<div class="sub">${esc(s.subtitle)}</div>` : ''}</div>`;
    const bl = (arr) => arr && arr.length ? `<ul class="bl">${arr.map(b => `<li>${esc(b)}</li>`).join('')}</ul>` : '';
    const sec = d.slides.slice(0, i + 1).filter(x => x.layout === 'section').length;
    let html = '';
    switch (lay) {
      case 'title':
        if (!s.image) el.classList.add('noimg');
        html = `${s.image ? `<div class="cover-img img" style="${img}"></div>` : ''}<div class="bar"></div>
          <div class="hd">${kick((d.report_type || 'Аналитический отчёт') + ' · ' + new Date(d.createdAt || Date.now()).toLocaleDateString('ru-RU', {month: 'long', year: 'numeric'}))}<h2>${esc(s.title || d.title)}</h2><div class="sub">${esc(s.subtitle || d.subtitle || '')}</div></div>
          <div class="ft"><span><b>${esc(d.dataset || '')}</b></span><span>Analitik Graph</span></div>`;
        break;
      case 'section':
        if (s.image) el.classList.add('hasimg');
        html = `${s.image ? `<div class="sec-img img" style="${img}"></div>` : ''}<div class="num">${String(sec).padStart(2, '0')}</div>${head('')}${foot}`;
        break;
      case 'agenda':
        html = `${head('Содержание')}<div class="bd"><div class="ag">${(s.bullets || []).map(b => `<div>${esc(b)}</div>`).join('')}</div></div>${foot}`;
        break;
      case 'kpis':
        html = `${head('Ключевые показатели')}<div class="bd" style="flex-direction:column;gap:34px"><div class="kpis">${(s.kpis || []).map(k => `<div class="kpi-c"><b>${esc(k.value)}</b><span>${esc(k.label)}</span>${k.note ? `<small>${esc(k.note)}</small>` : ''}</div>`).join('')}</div>${bl(s.bullets)}</div>${foot}`;
        break;
      case 'big_number': {
        const k = (s.kpis || [])[0] || {value: '—', label: ''};
        html = `${head('')}<div class="bd"><div style="flex:1"><div class="big">${esc(k.value)}</div><div class="big-l">${esc(k.label)}</div>${k.note ? `<div class="big-n">${esc(k.note)}</div>` : ''}</div>${s.bullets ? `<div class="side">${bl(s.bullets)}</div>` : ''}</div>${foot}`;
        break;
      }
      case 'comparison': {
        const col = c => c ? `<div><h3>${esc(c.title || '')}</h3>${c.value ? `<div class="v">${esc(c.value)}</div>` : ''}${bl(c.bullets)}</div>` : '<div></div>';
        html = `${head('Сравнение')}<div class="bd"><div class="cmp">${col(s.left)}${col(s.right)}</div></div>${foot}`;
        break;
      }
      case 'quote':
        html = `<div class="hd">${kick(s.title)}</div><div class="q">${esc(s.subtitle || (s.bullets || [])[0] || s.title)}</div>${(s.bullets || []).length > 1 ? `<div class="qs">${esc(s.bullets.slice(1).join(' · '))}</div>` : ''}${foot}`;
        break;
      case 'timeline': {
        const st = s.bullets || [];
        html = `${head('План действий')}<div class="bd"><div class="tl" style="grid-template-columns:repeat(${Math.max(1, st.length)},1fr)">${st.map((b, j) => `<div class="st"><div class="dot">${j + 1}</div><div class="tx">${esc(b)}</div></div>`).join('')}</div></div>${foot}`;
        break;
      }
      case 'image':
        html = `<div class="full-img img" style="${img || 'background:var(--s-surface)'}"></div>${head('')}<div class="bd">${bl(s.bullets)}</div>${foot}`;
        break;
      case 'recommendations':
        html = `${head('Рекомендации')}<div class="bd"><div class="recs">${(s.bullets || []).map((b, j) => `<div class="rec"><i>${String(j + 1).padStart(2, '0')}</i><div>${esc(b)}</div></div>`).join('')}</div></div>${foot}`;
        break;
      case 'closing':
        html = `${head('Итог')}<div class="bd">${bl(s.bullets)}</div>${foot}`;
        break;
      default: {
        const hasVis = s.chart || s.table, wide = s.table && s.table.head.length > 6;
        const side = (s.bullets || []).length && !wide ? `<div class="side"><div class="lbl">Выводы</div>${bl(s.bullets)}</div>` : '';
        if (s.error) html = `${head('')}<div class="bd"><div class="serr">Не удалось построить визуализацию: ${esc(s.error)}</div>${side}</div>${foot}`;
        else if (hasVis) html = `${head('')}<div class="bd"${wide ? ' style="flex-direction:column;gap:18px"' : ''}><div class="vis${wide ? ' wide' : ''}" data-vis></div>${side}${wide && (s.bullets || []).length ? `<div class="tnote" style="font-size:17px;color:var(--s-ink)">${esc(s.bullets.join(' · '))}</div>` : ''}</div>${foot}`;
        else html = `${head('')}<div class="bd"><div style="flex:1">${bl(s.bullets)}</div>${s.image ? `<div class="with-img img" style="${img}"></div>` : ''}</div>${foot}`;
      }
    }
    el.innerHTML = html;
    const vis = el.querySelector('[data-vis]');
    if (vis) {
      const w = (s.bullets || []).length ? 700 : 1120, h = 410;
      if (s.chart) { const box = document.createElement('div'); box.className = 'chart-box'; box.appendChild(chartSvg(s.chart, {w, h: (s.chart.series || []).length > 1 ? h - 30 : h, big: true, fs: 19, palette: t.palette})); vis.appendChild(box); vis.insertAdjacentHTML('beforeend', legendHtml(s.chart, t.palette)); }
      else if (s.table) vis.innerHTML = tableHtml(s.table, {maxRows: 9, heat: false});
    }
    return el;
  },

  pdf(d) {
    let host = $('printDeck'); if (host) host.remove();
    host = document.createElement('div'); host.id = 'printDeck'; document.body.appendChild(host);
    d.slides.forEach((s, i) => host.appendChild(this.render(d, i)));
    setTimeout(() => { window.print(); setTimeout(() => host.remove(), 1000); }, 500);
  },

  /** PowerPoint: нативные графики и таблицы в цветах темы. */
  async pptx(d) {
    if (!window.PptxGenJS) return toast('Модуль PowerPoint ещё загружается, повторите через пару секунд');
    loading(true, 'Собираю PowerPoint…');
    try {
      const t = this.theme(d), H = x => x.replace('#', ''), W = 13.333;
      const p = new PptxGenJS(); p.layout = 'LAYOUT_WIDE'; p.title = d.title; p.company = 'Analitik Graph';
      const imgs = {};
      for (const s of d.slides) if (s.image && !imgs[s.image]) imgs[s.image] = await urlToDataUrl(s.image);
      const FH = t.pptHead, FB = t.pptBody;
      d.slides.forEach((s, i) => {
        const sl = p.addSlide(), lay = s.layout || 'bullets';
        const dark = ['title', 'closing'].includes(lay) ? t.coverBg : lay === 'section' ? t.sectionBg : t.bg;
        const ink = ['title', 'closing'].includes(lay) ? t.coverInk : lay === 'section' ? t.sectionInk : t.ink;
        const mut = ['title', 'closing'].includes(lay) ? t.coverMuted : lay === 'section' ? t.sectionInk : t.muted;
        const acc = ['title', 'closing'].includes(lay) ? t.coverAccent : lay === 'section' ? t.sectionAccent : t.accent;
        sl.background = {color: H(dark)};
        if (s.notes) sl.addNotes(s.notes);
        const foot = () => { sl.addText(d.title, {x: 0.83, y: 7.0, w: 9, h: 0.3, fontSize: 10, color: H(mut), fontFace: FB, bold: true}); sl.addText(`${String(i + 1).padStart(2, '0')} / ${String(d.slides.length).padStart(2, '0')}`, {x: W - 2.2, y: 7.0, w: 1.4, h: 0.3, fontSize: 10, color: H(mut), align: 'right', fontFace: FB}); };
        const title = (y = 0.65, size = 28) => { sl.addText(s.title || '', {x: 0.83, y, w: 11.6, h: 1.05, fontSize: size, bold: true, color: H(ink), fontFace: FH, valign: 'top', fit: 'shrink'}); if (s.subtitle && lay !== 'quote') sl.addText(s.subtitle, {x: 0.83, y: y + 1.05, w: 11.2, h: 0.5, fontSize: 14, color: H(mut), fontFace: FB}); };
        const bullets = (arr, box, size = 17) => { if (!arr || !arr.length) return; sl.addText(arr.map(b => ({text: b, options: {bullet: {code: '25AC'}, breakLine: true, paraSpaceAfter: 12}})), {...box, fontSize: size, color: H(ink), fontFace: FB, valign: 'top'}); };
        const image = (u, box) => { if (u && imgs[u]) sl.addImage({data: imgs[u], ...box, sizing: {type: 'cover', w: box.w, h: box.h}}); };
        const top = 2.35, hh = 4.4;
        switch (lay) {
          case 'title':
            image(s.image, {x: W * 0.54, y: 0, w: W * 0.46, h: 7.5});
            sl.addShape(p.ShapeType.rect, {x: 0.83, y: 0.65, w: 0.66, h: 0.07, fill: {color: H(acc)}, line: {color: H(acc)}});
            sl.addText(s.title || d.title, {x: 0.83, y: 3.1, w: s.image ? 6.2 : 11, h: 2.2, fontSize: 40, bold: true, color: H(ink), fontFace: FH, valign: 'bottom', fit: 'shrink'});
            sl.addText(s.subtitle || d.subtitle || '', {x: 0.83, y: 5.4, w: s.image ? 6.2 : 11, h: 0.8, fontSize: 16, color: H(mut), fontFace: FB, valign: 'top'});
            sl.addText(d.dataset || '', {x: 0.83, y: 7.0, w: 6, h: 0.3, fontSize: 10, color: H(mut), fontFace: FB});
            return;
          case 'section': {
            const n = d.slides.slice(0, i + 1).filter(x => x.layout === 'section').length;
            image(s.image, {x: W * 0.6, y: 0, w: W * 0.4, h: 7.5});
            sl.addText(String(n).padStart(2, '0'), {x: 0.83, y: 1.0, w: 4, h: 1.8, fontSize: 96, bold: true, color: H(acc), fontFace: FH});
            sl.addText(s.title, {x: 0.83, y: 4.2, w: s.image ? 6.8 : 11, h: 1.6, fontSize: 36, bold: true, color: H(ink), fontFace: FH, valign: 'bottom', fit: 'shrink'});
            foot(); return;
          }
          case 'quote':
            sl.addShape(p.ShapeType.rect, {x: 0.83, y: 1.3, w: 0.8, h: 0.07, fill: {color: H(acc)}, line: {color: H(acc)}});
            sl.addText(s.title, {x: 0.83, y: 0.6, w: 11, h: 0.5, fontSize: 13, bold: true, color: H(acc), fontFace: FB, charSpacing: 2});
            sl.addText(s.subtitle || (s.bullets || [])[0] || s.title, {x: 0.83, y: 1.7, w: 11.2, h: 3.8, fontSize: 34, color: H(ink), fontFace: FH, valign: 'top', fit: 'shrink'});
            foot(); return;
        }
        title();
        foot();
        if (s.error) { sl.addText('Не удалось построить визуализацию: ' + s.error, {x: 0.83, y: top, w: 11, h: 0.6, fontSize: 14, color: 'B4532A', fontFace: FB}); return; }
        switch (lay) {
          case 'kpis': {
            const ks = s.kpis || [], n = Math.max(1, ks.length), gap = 0.35, w = (11.67 - gap * (n - 1)) / n;
            ks.forEach((k, j) => { const x = 0.83 + j * (w + gap);
              sl.addShape(p.ShapeType.rect, {x, y: top, w, h: 0.05, fill: {color: H(t.accent)}, line: {color: H(t.accent)}});
              sl.addText(k.value, {x, y: top + 0.2, w, h: 0.95, fontSize: 36, bold: true, color: H(ink), fontFace: FH});
              sl.addText(k.label, {x, y: top + 1.15, w, h: 0.45, fontSize: 14, bold: true, color: H(ink), fontFace: FB});
              if (k.note) sl.addText(k.note, {x, y: top + 1.55, w, h: 0.5, fontSize: 11, color: H(mut), fontFace: FB, valign: 'top'}); });
            bullets(s.bullets, {x: 0.83, y: top + 2.4, w: 11.6, h: 2}, 15); return;
          }
          case 'big_number': {
            const k = (s.kpis || [])[0] || {value: '—', label: ''};
            sl.addText(k.value, {x: 0.83, y: top - 0.2, w: 7, h: 2.2, fontSize: 110, bold: true, color: H(t.accent), fontFace: FH});
            sl.addText(k.label, {x: 0.83, y: top + 2.1, w: 6.5, h: 0.9, fontSize: 18, bold: true, color: H(ink), fontFace: FB, valign: 'top'});
            bullets(s.bullets, {x: 8.1, y: top, w: 4.4, h: hh}, 15); return;
          }
          case 'comparison':
            [s.left, s.right].forEach((c, j) => { if (!c) return; const x = 0.83 + j * 6.1;
              sl.addText((c.title || '').toUpperCase(), {x, y: top, w: 5.6, h: 0.4, fontSize: 12, bold: true, color: H(mut), fontFace: FB, charSpacing: 1});
              if (c.value) sl.addText(c.value, {x, y: top + 0.45, w: 5.6, h: 1.1, fontSize: 44, bold: true, color: H(j ? t.accent : ink), fontFace: FH});
              bullets(c.bullets, {x, y: top + (c.value ? 1.7 : 0.6), w: 5.5, h: 2.6}, 14); });
            sl.addShape(p.ShapeType.line, {x: 6.66, y: top, w: 0, h: hh, line: {color: H(t.line), width: 1}}); return;
          case 'timeline': {
            const st = s.bullets || [], n = Math.max(1, st.length), w = 11.67 / n;
            sl.addShape(p.ShapeType.line, {x: 0.83, y: top + 0.55, w: 11.67, h: 0, line: {color: H(t.line), width: 1.5}});
            st.forEach((b, j) => { const x = 0.83 + j * w;
              sl.addShape(p.ShapeType.ellipse, {x, y: top + 0.3, w: 0.5, h: 0.5, fill: {color: H(t.accent)}, line: {color: H(t.accent)}});
              sl.addText(String(j + 1), {x, y: top + 0.3, w: 0.5, h: 0.5, fontSize: 13, bold: true, color: H(t.onAccent), align: 'center', valign: 'middle', fontFace: FB});
              sl.addText(b, {x, y: top + 1.0, w: w - 0.25, h: 3, fontSize: 14, color: H(ink), fontFace: FB, valign: 'top'}); });
            return;
          }
          case 'agenda':
            (s.bullets || []).forEach((b, j) => { const col = j % 2, row = Math.floor(j / 2), x = 0.83 + col * 6.1, y = top + row * 0.8;
              sl.addText([{text: String(j + 1).padStart(2, '0') + '   ', options: {color: H(t.accent), bold: true}}, {text: b, options: {color: H(ink)}}], {x, y, w: 5.6, h: 0.6, fontSize: 18, fontFace: FB});
              sl.addShape(p.ShapeType.line, {x, y: y + 0.66, w: 5.6, h: 0, line: {color: H(t.line), width: 0.75}}); });
            return;
          case 'recommendations':
            (s.bullets || []).forEach((b, j) => { const col = j % 2, row = Math.floor(j / 2), x = 0.83 + col * 6.1, y = top + row * 1.45;
              sl.addShape(p.ShapeType.line, {x, y, w: 5.6, h: 0, line: {color: H(t.line), width: 0.75}});
              sl.addText(String(j + 1).padStart(2, '0'), {x, y: y + 0.12, w: 0.7, h: 0.5, fontSize: 20, bold: true, color: H(t.accent), fontFace: FH});
              sl.addText(b, {x: x + 0.75, y: y + 0.1, w: 4.8, h: 1.2, fontSize: 14, color: H(ink), fontFace: FB, valign: 'top', fit: 'shrink'}); });
            return;
          case 'image':
            image(s.image, {x: 0, y: 0, w: W * 0.56, h: 7.5});
            bullets(s.bullets, {x: W * 0.56 + 0.5, y: 3.4, w: W * 0.44 - 1.1, h: 3.3}, 15); return;
          case 'closing':
            bullets(s.bullets, {x: 0.83, y: 3.6, w: 11.5, h: 3.2}, 18); return;
        }
        const wideT = s.table && s.table.head.length > 6, hasSide = (s.bullets || []).length && (s.chart || s.table) && !wideT, vw = hasSide ? 7.6 : 11.67;
        if (s.chart) this.pptxChart(p, sl, s.chart, {x: 0.75, y: top, w: vw, h: hh}, t);
        else if (s.table) this.pptxTable(sl, s.table, {x: 0.83, y: top, w: vw}, t);
        else if (s.image) image(s.image, {x: 8.6, y: top, w: 3.9, h: hh});
        if (hasSide) { sl.addText('ВЫВОДЫ', {x: 8.9, y: top, w: 3.6, h: 0.35, fontSize: 11, bold: true, color: H(mut), fontFace: FB, charSpacing: 1}); bullets(s.bullets, {x: 8.9, y: top + 0.45, w: 3.6, h: hh - 0.45}, 14); }
        else if (!s.chart && !s.table) bullets(s.bullets, {x: 0.83, y: top, w: s.image ? 7.4 : 11.6, h: hh}, 18);
        else if (wideT && (s.bullets || []).length) sl.addText(s.bullets.join('  ·  '), {x: 0.83, y: 6.35, w: 11.6, h: 0.45, fontSize: 13, color: H(ink), fontFace: FB});
      });
      await p.writeFile({fileName: safeName(d.title) + '.pptx'});
      toast('Презентация сохранена в PowerPoint', true);
    } catch (e) { toast('PowerPoint: ' + e.message); } finally { loading(false); }
  },
  pptxChart(p, sl, c, box, t) {
    const H = x => x.replace('#', ''), pct = c.format === 'pct', single = (c.series || []).length === 1;
    const common = {...box, chartColors: (single && c.chartType !== 'pie' ? t.palette.slice(0, 1) : t.palette).map(H), catAxisLabelFontSize: 11, valAxisLabelFontSize: 10, catAxisLabelColor: H(t.muted), valAxisLabelColor: H(t.muted),
      catAxisLabelFontFace: t.pptBody, valAxisLabelFontFace: t.pptBody, valGridLine: {color: H(t.line), size: 0.75}, catGridLine: {style: 'none'}, catAxisLineShow: false, valAxisLineShow: false,
      valAxisLabelFormatCode: pct ? '0%' : '#,##0', dataLabelFormatCode: pct ? '0.0%' : '#,##0', showLegend: !single, legendPos: 'b', legendFontSize: 11, legendColor: H(t.muted),
      dataLabelFontSize: 10, dataLabelColor: H(t.ink), dataLabelFontFace: t.pptBody, plotArea: {fill: {color: H(t.bg)}}};
    if (c.chartType === 'scatter') { const pts = c.points || []; sl.addChart(p.ChartType.scatter, [{name: c.xLabel || 'X', values: pts.map(q => q[0])}, {name: c.yLabel || 'Y', values: pts.map(q => q[1])}], {...common, lineSize: 0, lineDataSymbolSize: 5, showLegend: false}); return; }
    let series = c.series || []; if (c.chartType === 'pareto') series = [series[0]];
    const data = series.map(s => ({name: s.name, labels: c.categories.map(String), values: s.data.map(v => typeof v === 'number' ? v : null)}));
    if (c.chartType === 'pie') sl.addChart(p.ChartType.doughnut, data.slice(0, 1), {...common, holeSize: 58, showLegend: true, legendPos: 'r', showPercent: true, showValue: false, dataLabelColor: 'FFFFFF'});
    else if (c.chartType === 'line') sl.addChart(p.ChartType.line, data, {...common, lineSize: 3, lineDataSymbol: 'circle', lineDataSymbolSize: 7});
    else sl.addChart(p.ChartType.bar, data, {...common, barDir: 'col', barGapWidthPct: 55, showValue: single && c.categories.length <= 14});
  },
  pptxTable(sl, tb, box, t) {
    const H = x => x.replace('#', ''), f = tb.formats || [], k = tb.keyCols || 1, rows = tb.rows.slice(0, 10);
    const head = tb.head.map((h, i) => ({text: String(h).toUpperCase(), options: {bold: true, color: H(t.muted), fontSize: 10, align: i < k ? 'left' : 'right', border: [{type: 'none'}, {type: 'none'}, {pt: 1.5, color: H(t.ink)}, {type: 'none'}]}}));
    const line = {pt: 0.5, color: H(t.line)};
    const body = rows.map(r => r.map((c, i) => ({text: fmtVal(c, f[i]), options: {align: i < k ? 'left' : 'right', bold: i < k, color: H(t.ink), border: [{type: 'none'}, {type: 'none'}, line, {type: 'none'}]}})));
    if (tb.totals) body.push(tb.totals.map((c, i) => ({text: fmtVal(c, f[i]), options: {bold: true, align: i < k ? 'left' : 'right', color: H(t.ink), border: [{pt: 1.5, color: H(t.ink)}, {type: 'none'}, {type: 'none'}, {type: 'none'}]}})));
    sl.addTable([head, ...body], {...box, fontSize: tb.head.length > 8 ? 9 : tb.head.length > 6 ? 10 : 12, fontFace: t.pptBody, rowH: 0.36, autoPage: false});
  },
};
