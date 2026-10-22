/* ═══════════════════════ визуализация: таблицы, графики, экспорт ═══════════════════════
 * Общие рендеры для чата ИИ, вкладки «Сводные» и слайдов. Данные всегда приходят с сервера. */

function fmtVal(v, f) {
  if (v === null || v === undefined || v === '') return '—';
  if (typeof v !== 'number') return String(v);
  if (f === 'pct') return (v * 100).toLocaleString('ru-RU', {maximumFractionDigits: Math.abs(v) < 0.1 ? 2 : 1}) + '%';
  if (f === 'int') return Math.round(v).toLocaleString('ru-RU');
  return fmt(v);
}

/** Таблица (сводная/сравнение). opts: {heat, maxRows} */
function tableHtml(t, opts = {}) {
  const f = t.formats || [], k = t.keyCols || 1;
  const rows = opts.maxRows ? t.rows.slice(0, opts.maxRows) : t.rows;
  // тепловая карта по числовым колонкам (кроме «Итого»)
  let mn = Infinity, mx = -Infinity;
  const heat = opts.heat ?? t.heat;
  if (heat) rows.forEach(r => r.forEach((c, i) => { if (i >= k && i < r.length - 1 && typeof c === 'number') { mn = Math.min(mn, c); mx = Math.max(mx, c); } }));
  const bg = (c, i, n) => {
    if (!heat || typeof c !== 'number' || i < k || i === n - 1 || mx === mn) return '';
    const a = (c - mn) / (mx - mn);
    return ` style="background:color-mix(in srgb, var(--accent) ${Math.round(6 + a * 38)}%, transparent)"`;
  };
  let h = '<div class="tbl-wrap"><table class="dt"><thead><tr>' + t.head.map((x, i) => `<th class="${i < k ? 'k' : ''}">${esc(x)}</th>`).join('') + '</tr></thead><tbody>';
  h += rows.map(r => '<tr>' + r.map((c, i) => `<td class="${i < k ? 'k' : ''}${c === null ? ' nil' : ''}${typeof c === 'string' && c.length > 40 ? ' wrap' : ''}"${bg(c, i, r.length)}>${esc(fmtVal(c, f[i]))}</td>`).join('') + '</tr>').join('');
  if (t.totals && !opts.noTotals) h += '<tr class="tot">' + t.totals.map((c, i) => `<td class="${i < k ? 'k' : ''}">${esc(fmtVal(c, f[i]))}</td>`).join('') + '</tr>';
  h += '</tbody></table></div>';
  if (opts.maxRows && t.rows.length > opts.maxRows) h += `<div class="tnote">Ещё ${t.rows.length - opts.maxRows} строк — в полной версии</div>`;
  if (t.note) h += `<div class="tnote">${esc(t.note)}</div>`;
  return h;
}

/** Палитра интерфейса (зависит от темы). */
function uiPalette() {
  return isDark() ? ['#7C93FF', '#3CC08A', '#F29A48', '#A58BFF', '#F0706A', '#35C2D6', '#E7B04A', '#8C93A3']
    : ['#2446D8', '#0F7B55', '#D0701B', '#6A4BD0', '#C23B34', '#0E8497', '#B7791F', '#7B8190'];
}
/** SVG-график. opts: {w, h, palette, fs, big} — big для слайдов/документов. */
function chartSvg(c, opts = {}) {
  const W = opts.w || 400, H = opts.h || 240, pal = opts.palette || uiPalette();
  const fs = opts.fs || (opts.big ? 17 : 10.5);
  const node = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  const svg = d3.select(node).attr('viewBox', `0 0 ${W} ${H}`).attr('xmlns', 'http://www.w3.org/2000/svg').attr('font-size', fs);
  const fmtY = v => fmtVal(v, c.format);
  const isFc = s => /прогноз/i.test(s.name || '');
  if (c.chartType === 'pie') {
    const s = c.series[0], data = c.categories.map((l, i) => ({l, v: s.data[i] || 0})).filter(d => d.v > 0);
    const R = Math.min(W * 0.3, H / 2 - 8), cx = R + 12, cy = H / 2, tot = d3.sum(data, d => d.v);
    const arcs = d3.pie().sort(null).value(d => d.v)(data);
    svg.append('g').attr('transform', `translate(${cx},${cy})`).selectAll('path').data(arcs).join('path')
      .attr('d', d3.arc().innerRadius(R * 0.58).outerRadius(R).padAngle(0.01)).attr('fill', (d, i) => pal[i % pal.length]);
    svg.append('text').attr('x', cx).attr('y', cy + fs * 0.35).attr('text-anchor', 'middle').attr('class', 'vl').style('font-size', fs * 1.3 + 'px').text(fmtVal(tot, c.format === 'pct' ? 'num' : c.format));
    const lg = svg.append('g').attr('transform', `translate(${cx + R + 22},${Math.max(fs + 4, cy - Math.min(10, data.length) * (fs + 9) / 2)})`);
    data.slice(0, 10).forEach((d, i) => {
      const g = lg.append('g').attr('transform', `translate(0,${i * (fs + 9)})`);
      g.append('rect').attr('width', fs * .8).attr('height', fs * .8).attr('rx', 2).attr('y', -fs * .75).attr('fill', pal[i % pal.length]);
      g.append('text').attr('x', fs * 1.3).attr('class', 'vl').style('font-weight', 500).text(`${trunc(d.l, 26)} — ${fmtVal(d.v / tot, 'pct')}`);
    });
    return node;
  }
  const m = opts.big ? {t: fs * 1.6, r: 20, b: fs * 2.8, l: fs * 4.2} : {t: 14, r: 12, b: 32, l: 46};
  if (c.chartType === 'scatter') {
    const pts = c.points || [];
    const x = d3.scaleLinear().domain(d3.extent(pts, p => p[0])).nice().range([m.l, W - m.r]);
    const y = d3.scaleLinear().domain(d3.extent(pts, p => p[1])).nice().range([H - m.b, m.t]);
    axes(svg, x, y, W, H, m, v => fmt(v), v => fmt(v));
    svg.append('g').selectAll('circle').data(pts).join('circle').attr('cx', p => x(p[0])).attr('cy', p => y(p[1])).attr('r', opts.big ? 3.5 : 2.3).attr('fill', pal[0]).attr('opacity', .4);
    return node;
  }
  const cats = c.categories || [];
  let series = c.series || [];
  const pareto = c.chartType === 'pareto';
  const cumSeries = pareto ? series[1] : null; if (pareto) series = [series[0]];
  const all = series.flatMap(s => s.data).filter(v => typeof v === 'number');
  const longest = d3.max(cats, s => String(s).length) || 1;
  const rot = cats.length * Math.min(longest, 14) * (fs * 0.58) > W - m.l;
  if (rot) m.b = Math.min(opts.big ? 130 : 90, fs * 2 + Math.min(longest, 18) * fs * 0.42);
  if (pareto) m.r = fs * 3.4;
  const y = d3.scaleLinear().domain([Math.min(0, d3.min(all) ?? 0), d3.max(all) || 1]).nice().range([H - m.b, m.t]);
  const line = c.chartType === 'line';
  const x = d3.scaleBand().domain(cats).range([m.l, W - m.r]).padding(line ? 0.5 : 0.24);
  axes(svg, x, y, W, H, m, s => trunc(s, 18), fmtY, rot);
  if (line) {
    series.forEach((s, si) => {
      const pts = cats.map((l, i) => [x(l) + x.bandwidth() / 2, s.data[i]]).filter(p => typeof p[1] === 'number');
      const col = isFc(s) ? pal[0] : pal[si % pal.length];
      svg.append('path').attr('d', d3.line().curve(d3.curveMonotoneX)(pts.map(p => [p[0], y(p[1])]))).attr('fill', 'none').attr('stroke', col)
        .attr('stroke-width', opts.big ? 3.2 : 2).attr('stroke-dasharray', isFc(s) ? (opts.big ? '8 6' : '5 4') : /скольз/i.test(s.name || '') ? '2 3' : null).attr('opacity', /скольз/i.test(s.name || '') ? .7 : 1);
      if (!/скольз/i.test(s.name || '')) svg.append('g').selectAll('circle').data(pts).join('circle').attr('cx', p => p[0]).attr('cy', p => y(p[1])).attr('r', opts.big ? 4 : 2.6).attr('fill', isFc(s) ? cssVar('--surface') || '#fff' : col).attr('stroke', col).attr('stroke-width', 1.5);
    });
  } else {
    const inner = d3.scaleBand().domain(series.map((s, i) => i)).range([0, x.bandwidth()]).padding(0.08);
    series.forEach((s, si) => {
      const g = svg.append('g');
      cats.forEach((l, i) => {
        const v = s.data[i]; if (typeof v !== 'number') return;
        const bx = x(l) + inner(si), bw = inner.bandwidth(), y0 = y(0), y1 = y(v);
        g.append('rect').attr('x', bx).attr('width', bw).attr('y', Math.min(y0, y1)).attr('height', Math.max(1, Math.abs(y0 - y1))).attr('rx', Math.min(3, bw / 5)).attr('fill', pal[si % pal.length]);
        if (series.length === 1 && cats.length <= 14 && !pareto) svg.append('text').attr('class', 'vl').attr('x', bx + bw / 2).attr('y', y1 - fs * 0.45).attr('text-anchor', 'middle').text(fmtY(v));
      });
    });
    if (pareto && cumSeries) {
      const y2 = d3.scaleLinear().domain([0, 1]).range([H - m.b, m.t]);
      const pts = cats.map((l, i) => [x(l) + x.bandwidth() / 2, cumSeries.data[i]]).filter(p => typeof p[1] === 'number');
      svg.append('line').attr('x1', m.l).attr('x2', W - m.r).attr('y1', y2(0.8)).attr('y2', y2(0.8)).attr('stroke', pal[4]).attr('stroke-dasharray', '4 4').attr('opacity', .7);
      svg.append('path').attr('d', d3.line()(pts.map(p => [p[0], y2(p[1])]))).attr('fill', 'none').attr('stroke', pal[2]).attr('stroke-width', opts.big ? 3 : 2);
      svg.append('g').selectAll('circle').data(pts).join('circle').attr('cx', p => p[0]).attr('cy', p => y2(p[1])).attr('r', opts.big ? 4 : 2.6).attr('fill', pal[2]);
      const ay2 = svg.append('g').attr('class', 'ax').attr('transform', `translate(${W - m.r},0)`).call(d3.axisRight(y2).ticks(5).tickSize(0).tickPadding(6).tickFormat(v => Math.round(v * 100) + '%'));
      ay2.select('.domain').remove();
    }
  }
  return node;
}
function axes(svg, x, y, W, H, m, fx, fy, rot) {
  svg.append('g').attr('class', 'grid').selectAll('line').data(y.ticks(5)).join('line').attr('x1', m.l).attr('x2', W - m.r).attr('y1', d => y(d)).attr('y2', d => y(d));
  const ay = svg.append('g').attr('class', 'ax').attr('transform', `translate(${m.l},0)`).call(d3.axisLeft(y).ticks(5).tickSize(0).tickPadding(6).tickFormat(fy));
  ay.select('.domain').remove();
  const ax = svg.append('g').attr('class', 'ax').attr('transform', `translate(0,${H - m.b})`).call((x.bandwidth ? d3.axisBottom(x) : d3.axisBottom(x).ticks(6)).tickSize(0).tickPadding(7).tickFormat(fx));
  if (rot) ax.selectAll('text').attr('text-anchor', 'end').attr('transform', 'rotate(-35)').attr('dx', '-4').attr('dy', '6');
}
function legendHtml(c, palette) {
  const ser = c.series || [];
  if (ser.length < 2) return '';
  const pal = palette || uiPalette();
  return '<div class="lgd">' + ser.map((s, i) => `<span><i style="background:${c.chartType === 'pareto' ? pal[i === 0 ? 0 : 2] : /прогноз/i.test(s.name) ? pal[0] : pal[i % pal.length]}${/прогноз/i.test(s.name) ? ';opacity:.5' : ''}"></i>${esc(s.name)}</span>`).join('') + '</div>';
}
function chartHtmlInto(el, c, opts = {}) {
  el.innerHTML = ''; const box = document.createElement('div'); box.className = 'chart-box';
  box.appendChild(chartSvg(c, opts)); el.appendChild(box);
  el.insertAdjacentHTML('beforeend', legendHtml(c, opts.palette) + (c.stat ? `<div class="tnote">${esc(c.stat)}</div>` : ''));
}

/* ── экспорт ── */
function safeName(s) { return String(s || 'таблица').replace(/>/g, 'больше').replace(/</g, 'меньше').replace(/[\\/:*?"|«»]/g, '').slice(0, 80).trim() || 'таблица'; }
function tableToAoa(t) {
  const f = t.formats || [];
  const conv = (v, i) => typeof v === 'number' && f[i] === 'pct' ? v : v; // проценты храним долей + формат ячейки
  const aoa = [t.head.slice()];
  t.rows.forEach(r => aoa.push(r.map(conv)));
  if (t.totals) aoa.push(t.totals.map(conv));
  return aoa;
}
function exportXlsx(t) {
  if (!window.XLSX) return toast('Библиотека Excel ещё загружается…');
  const ws = XLSX.utils.aoa_to_sheet(tableToAoa(t));
  const f = t.formats || [];
  const range = XLSX.utils.decode_range(ws['!ref']);
  for (let R = 1; R <= range.e.r; R++) for (let C = 0; C <= range.e.c; C++) {
    const cell = ws[XLSX.utils.encode_cell({r: R, c: C})]; if (!cell || cell.t !== 'n') continue;
    cell.z = f[C] === 'pct' ? '0.0%' : f[C] === 'int' ? '#,##0' : '#,##0.00';
  }
  ws['!cols'] = t.head.map((h, i) => ({wch: Math.max(10, Math.min(36, String(h).length + 4, ...t.rows.slice(0, 50).map(r => String(fmtVal(r[i], f[i])).length + 2)))}));
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, 'Сводная');
  const info = XLSX.utils.aoa_to_sheet([['Отчёт', t.title], ['Данные', t.subtitle || ''], ['Набор', S.ds ? S.ds.name : ''], ['Создано', new Date().toLocaleString('ru-RU')], ['Источник', 'Analitik Graph']]);
  XLSX.utils.book_append_sheet(wb, info, 'Описание');
  XLSX.writeFile(wb, safeName(t.title) + '.xlsx');
}
function exportCsv(t) {
  const f = t.formats || [];
  const q = v => { const s = v === null || v === undefined ? '' : typeof v === 'number' ? String(v).replace('.', ',') : String(v); return /[;"\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
  const lines = tableToAoa(t).map(r => r.map(q).join(';'));
  download(new Blob(['﻿' + lines.join('\n')], {type: 'text/csv;charset=utf-8'}), safeName(t.title) + '.csv');
}
function download(blob, name) { const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 2000); }
/** SVG → PNG dataURL (для экспорта графиков, PPTX и PDF). colors: {bg, ink, muted, grid, font} */
function svgToPng(svgNode, w, h, colors = {}) {
  const C = Object.assign({bg: '#FFFFFF', ink: '#17191E', muted: '#6B6E75', grid: '#E9E8E3', font: 'Plex, Arial, sans-serif'}, colors);
  return new Promise(res => {
    const clone = svgNode.cloneNode(true);
    clone.setAttribute('width', w); clone.setAttribute('height', h);
    const st = document.createElementNS('http://www.w3.org/2000/svg', 'style');
    st.textContent = `text{font-family:${C.font}} .ax text{fill:${C.muted}} .ax line,.ax path{stroke:${C.grid}} .grid line{stroke:${C.grid}} .vl{fill:${C.ink};font-weight:600}`;
    clone.insertBefore(st, clone.firstChild);
    const img = new Image();
    img.onload = () => { const c = document.createElement('canvas'); c.width = w * 2; c.height = h * 2; const x = c.getContext('2d'); x.fillStyle = C.bg; x.fillRect(0, 0, c.width, c.height); x.scale(2, 2); x.drawImage(img, 0, 0, w, h); res(c.toDataURL('image/png')); };
    img.onerror = () => res(null);
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(new XMLSerializer().serializeToString(clone));
  });
}
/** URL картинки → dataURL (для PPTX/PDF). */
async function urlToDataUrl(u) {
  try { const b = await (await fetch(u)).blob(); return await new Promise(r => { const f = new FileReader(); f.onload = () => r(f.result); f.readAsDataURL(b); }); } catch (e) { return null; }
}
function openBig(html, after) { $('bigBody').innerHTML = html; $('mBig').classList.add('show'); if (after) after($('bigBody')); }
