/* ═══════════════════════ граф путей: как клиенты ходят по продукту ═══════════════════════ */
const Journey = {
  data: null,
  dataset: null,          // id базы событий
  goal: null,
  seg: {col: '', val: ''},
  nodes: [], links: [],
  byId: new Map(),
  view: null,
  selected: null,

  init() {
    this.view = wsZoom('#jrSvg', '#jrVp', 'jrStage', null, () => { if (this.data && App.current === 'Journey') this.fit(); });
    d3.select('#jrSvg').on('click', () => this.select(null));
    $('jrDs').onchange = () => { this.dataset = $('jrDs').value; this.goal = null; this.seg = {col: '', val: ''}; this.load(true); };
    $('jrGoal').onchange = () => { this.goal = $('jrGoal').value; this.load(false); };
    $('jrSegCol').onchange = () => { this.seg = {col: $('jrSegCol').value, val: ''}; this.fillSegValues(); if (!this.seg.col) this.load(false); };
    $('jrSegVal').onchange = () => { this.seg.val = $('jrSegVal').value; this.load(false); };
    $('jrMin').addEventListener('input', () => { $('jrMinV').textContent = Math.round(+$('jrMin').value * 100) + '%'; });
    $('jrMin').addEventListener('change', () => this.load(false));
  },

  eventsDatasets() { return WS.list.filter(d => d.role === 'events' || (d.stepColumn && d.keyColumn)); },

  async show() {
    await WS.load();
    const evs = this.eventsDatasets();
    $('jrDs').innerHTML = evs.map(d => `<option value="${esc(d.id)}">${esc(d.name)}</option>`).join('') || '<option value="">— нет базы событий —</option>';
    if (!evs.length) {
      this.clear();
      wsEmpty('jrEmpty', 'Нужна база событий', 'Граф путей строится по таблице «кто · когда · какой шаг»: ID клиента, время и страница / экран / событие. Добавьте такую базу или откройте демо-продукт.', true);
      renderWsInsights('Находки путей', []);
      return;
    }
    if (!evs.some(d => d.id === this.dataset)) this.dataset = evs[0].id;
    $('jrDs').value = this.dataset;
    await this.load(!this.data);
  },

  async load(fit) {
    loading(true, 'Собираем визиты и считаем переходы…');
    try {
      const body = {dataset: this.dataset, goal: this.goal || undefined, minShare: +$('jrMin').value};
      if (this.seg.col && this.seg.val) { body.segmentColumn = this.seg.col; body.segmentValue = this.seg.val; }
      const d = await api('/journey', {method: 'POST', body: JSON.stringify(body)});
      wsEmpty('jrEmpty');
      this.data = d; this.goal = d.goal;
      this.fillControls();
      this.layout();
      this.draw();
      this.closePanel();
      setTimeout(() => this.fit(), fit ? 30 : 10);
    } catch (e) { toast(e.message); } finally { loading(false); }
  },

  clear() {
    this.data = null;
    ['#jrLinks', '#jrLabels', '#jrNodes'].forEach(s => d3.select(s).selectAll('*').remove());
    $('jrBar').innerHTML = ''; this.closePanel();
  },

  fillControls() {
    const d = this.data;
    $('jrGoal').innerHTML = '<option value="">— без цели —</option>' + d.steps.filter(s => s !== 'Другое').map(s => `<option ${s === d.goal ? 'selected' : ''}>${esc(s)}</option>`).join('');
    const cols = Object.keys(d.segments || {});
    $('jrSegCol').innerHTML = '<option value="">все клиенты</option>' + cols.map(c => `<option ${c === this.seg.col ? 'selected' : ''} value="${esc(c)}">${esc(c)}</option>`).join('');
    this.fillSegValues();
  },
  fillSegValues() {
    const s = this.data && this.data.segments[this.seg.col];
    $('jrSegVal').disabled = !s;
    $('jrSegVal').innerHTML = s ? '<option value="">— выберите —</option>' + s.values.map(v => `<option ${v === this.seg.val ? 'selected' : ''}>${esc(v)}</option>`).join('') : '<option value="">—</option>';
  },
  setSegment(col, val) { this.seg = {col, val}; this.load(false); },

  radius(n) { return n.kind === 'start' ? 22 : 12 + 26 * Math.sqrt(Math.max(0.005, n.userShare || 0)); },
  color(n) {
    if (n.kind === 'goal') return cssVar('--accent');
    if (n.kind === 'start') return cssVar('--text');
    if (n.level && n.level !== 'none') return n.direction === 'negative' ? COLOR.neg : COLOR.pos;
    return cssVar('--c-bin');
  },

  /** X — среднее место шага в визите, Y — силовая раскладка без пересечений. */
  layout() {
    const d = this.data, X = 210;
    this.nodes = d.nodes.map(n => ({...n, tx: n.pos * X, x: n.pos * X, y: (Math.random() - 0.5) * 200}));
    this.byId = new Map(this.nodes.map(n => [n.id, n]));
    this.links = d.links.map(l => ({...l}));
    const start = this.byId.get('Вход'); start.fx = 0; start.fy = 0;
    const forward = this.links.filter(l => !l.back).map(l => ({source: l.source, target: l.target, w: l.share}));
    const sim = d3.forceSimulation(this.nodes)
      .force('x', d3.forceX(n => n.tx).strength(1))
      .force('y', d3.forceY(0).strength(n => n.kind === 'goal' ? 0.3 : 0.05))
      .force('collide', d3.forceCollide(n => this.radius(n) + 34).iterations(3))
      .force('link', d3.forceLink(forward).id(n => n.id).strength(l => 0.02 + 0.1 * l.w).distance(l => Math.abs(l.target.tx - l.source.tx) || 60))
      .stop();
    for (let i = 0; i < 400; i++) sim.tick();
  },

  draw() {
    const d = this.data;
    const maxCount = d3.max(this.links, l => l.count) || 1;
    const seg = d.segment ? `<span class="stat seg">Сегмент: <b>${esc(d.segment)}</b><button title="Сбросить сегмент">×</button></span>` : '';
    $('jrBar').innerHTML = `<span class="t">Пути · ${esc(d.dataset.name)}</span>${seg}
      <span class="stat"><b>${fmtInt(d.users)}</b> клиентов · <b>${fmtInt(d.sessions)}</b> визитов</span>
      ${d.goal ? `<span class="stat">до «${esc(d.goal)}» дошли <b>${pctRu(d.conversion)}</b> (${fmtInt(d.goalUsers)})</span>` : ''}`;
    const sb = $('jrBar').querySelector('.seg button'); if (sb) sb.onclick = () => this.setSegment('', '');

    const geo = l => {
      const s = this.byId.get(l.source), t = this.byId.get(l.target);
      const rs = this.radius(s), rt = this.radius(t);
      if (l.back) {
        const lift = 70 + Math.abs(t.x - s.x) * 0.22, top = Math.min(s.y - rs, t.y - rt) - lift;
        return {d: `M${s.x},${s.y - rs}C${s.x},${top} ${t.x},${top} ${t.x},${t.y - rt - 4}`, mx: (s.x + t.x) / 2, my: top + lift * 0.25};
      }
      const x1 = s.x + rs, x2 = t.x - rt - 5, dx = Math.max(40, (x2 - x1) * 0.5);
      return {d: `M${x1},${s.y}C${x1 + dx},${s.y} ${x2 - dx},${t.y} ${x2},${t.y}`, mx: (x1 + x2) / 2, my: (s.y + t.y) / 2};
    };
    this.links.forEach(l => Object.assign(l, geo(l)));

    d3.select('#jrLinks').selectAll('path').data(this.links, l => l.id).join('path')
      .attr('class', l => `jl ${l.back ? 'back' : ''} ${l.source === 'Вход' ? 'start' : ''}`)
      .attr('d', l => l.d)
      .attr('stroke-width', l => Math.max(1.2, 1 + 15 * l.count / maxCount))
      .attr('marker-end', 'url(#jrArrow)')
      .on('mouseenter', (e, l) => {
        showTip(e, `<b>${esc(l.source)} → ${esc(l.target)}</b>${pctRu(l.share)} ${l.source === 'Вход' ? 'визитов начинаются здесь' : 'уходов с «' + esc(l.source) + '»'}<div class="m">${fmtInt(l.count)} переходов · ${fmtInt(l.users)} клиентов · клик — кто эти люди</div>`);
        this.highlight([l.source, l.target], [l.id]);
      })
      .on('mousemove', moveTip)
      .on('mouseleave', () => { hideTip(); this.unhighlight(); })
      .on('click', (e, l) => { e.stopPropagation(); this.profile({from: l.source, to: l.target}); });

    d3.select('#jrLabels').selectAll('text').data(this.links.filter(l => l.share >= 0.08 || l.count / maxCount > 0.2), l => l.id).join('text')
      .attr('class', 'jll').attr('x', l => l.mx).attr('y', l => l.my - 5).text(l => pctRu(l.share));

    const g = d3.select('#jrNodes').selectAll('g.jn').data(this.nodes, n => n.id).join(enter => {
      const x = enter.append('g').attr('class', 'jn');
      x.append('circle').attr('class', 'ring'); x.append('circle').attr('class', 'c'); x.append('path').attr('class', 'exit');
      x.append('text').attr('class', 't3'); x.append('text').attr('class', 't1'); x.append('text').attr('class', 't2');
      return x;
    });
    g.classed('sel', n => n.id === this.selected).attr('transform', n => `translate(${n.x},${n.y})`)
      .on('mouseenter', (e, n) => {
        showTip(e, `<b>${esc(n.label)}</b>${n.kind === 'start' ? 'начало визита' : `${pctRu(n.userShare)} клиентов · ${fmtInt(n.users)}`}${n.exitRate ? `<div class="m">выход ${pctRu(n.exitRate)} · клик — подробнее</div>` : ''}`);
        const nb = [n.id], ls = [];
        this.links.forEach(l => { if (l.source === n.id) { nb.push(l.target); ls.push(l.id); } if (l.target === n.id) { nb.push(l.source); ls.push(l.id); } });
        this.highlight(nb, ls);
      })
      .on('mousemove', moveTip)
      .on('mouseleave', () => { hideTip(); this.unhighlight(); })
      .on('click', (e, n) => { e.stopPropagation(); this.showNode(n); });
    g.select('.c').attr('r', n => this.radius(n)).attr('stroke', n => this.color(n))
      .attr('fill', n => n.kind === 'start' ? this.color(n) : tint(this.color(n), n.kind === 'goal' ? 0.28 : 0.16));
    g.select('.ring').attr('r', n => this.radius(n) + 7).style('display', n => n.kind === 'goal' ? null : 'none');
    g.select('.exit').attr('d', n => n.exitRate > 0.005 ? d3.arc()({innerRadius: this.radius(n) + 4, outerRadius: this.radius(n) + 4, startAngle: 0, endAngle: Math.max(0.08, n.exitRate * Math.PI * 2)}) : null);
    g.select('.t1').attr('dy', n => this.radius(n) + 17).text(n => n.label);
    g.select('.t2').attr('dy', n => this.radius(n) + 31).text(n => n.kind === 'start' ? `${fmtInt(n.sessions)} визитов` : `${pctRu(n.userShare)} клиентов${n.exitRate > 0.005 ? ' · выход ' + pctRu(n.exitRate) : ''}`);
    g.select('.t3').attr('dy', n => -this.radius(n) - 9)
      .attr('class', n => 't3 ' + (n.level && n.level !== 'none' ? (n.direction === 'negative' ? 'neg' : 'pos') : ''))
      .text(n => n.kind !== 'step' || !d.goal ? (n.kind === 'goal' ? 'цель' : '') : n.required ? 'без него — 0%' : n.level && n.level !== 'none' && n.lift ? `${times(n.lift)} к цели` : '');

    this.renderSide();
  },

  renderSide() {
    const d = this.data;
    const items = (d.insights || []).map(t => ({text: t, level: /уходят/.test(t) ? 'strong' : 'medium', k: /уходят/.test(t) ? 'Потери' : 'Влияние на цель'}));
    renderWsInsights('Находки путей', items, x => {
      const m = x.text.match(/«([^»]+)»/); const n = m && this.byId.get(m[1]); if (n) { this.showNode(n); this.view.fit([n], 220, true); }
    });
    const list = (title, arr) => arr && arr.length ? `<div class="ws-sub">${title}</div>` + arr.map(p => `<div class="ws-path"><b>${pctRu(p.share)}</b>${esc(p.path)}<span class="c">${fmtInt(p.sessions)} визитов${d.goal ? ' · до цели ' + pctRu(p.conv) : ''}</span></div>`).join('') : '';
    $('wsIns').insertAdjacentHTML('beforeend', list('Пути к цели', (d.goalPaths || []).slice(0, 5)) + list('Частые визиты', (d.paths || []).slice(0, 5)));
  },

  fit() { this.view.fit(this.nodes.map(n => ({x: n.x, y: n.y})), 110, $('jrPanel').classList.contains('show')); },

  highlight(ids, linkIds) {
    const ns = new Set(ids), ls = new Set(linkIds);
    $('jrStage').classList.add('wdim');
    d3.selectAll('#jrNodes .jn').classed('hl', n => ns.has(n.id));
    d3.selectAll('#jrLinks .jl').classed('hl', l => ls.has(l.id));
    d3.selectAll('#jrLabels .jll').classed('hl', l => ls.has(l.id));
  },
  unhighlight() {
    $('jrStage').classList.remove('wdim');
    d3.selectAll('#jrNodes .jn, #jrLinks .jl, #jrLabels .jll').classed('hl', false);
  },

  select(id) {
    this.selected = id;
    d3.selectAll('#jrNodes .jn').classed('sel', n => n.id === id);
    if (!id) this.closePanel();
  },
  closePanel() { $('jrPanel').classList.remove('show'); },
  panel(html) {
    $('jrPanel').innerHTML = html; $('jrPanel').classList.add('show');
    $('jrPanel').querySelector('.x').onclick = () => this.select(null);
  },

  showNode(n) {
    this.select(n.id);
    const d = this.data;
    const ins = this.links.filter(l => l.target === n.id).sort((a, b) => b.count - a.count);
    const outs = this.links.filter(l => l.source === n.id).sort((a, b) => b.share - a.share);
    let h = `<div class="ph"><span class="ty">${n.kind === 'goal' ? 'Цель' : n.kind === 'start' ? 'Начало визита' : 'Шаг пути'}</span>
      ${n.level && n.level !== 'none' ? ` <span class="lvl ${n.level}">${n.direction === 'negative' ? 'мешает цели' : 'помогает цели'}</span>` : ''}
      <h2>${esc(n.label)}</h2><div class="sb">${n.kind === 'start' ? fmtInt(n.sessions) + ' визитов' : `${pctRu(n.userShare)} клиентов · ${fmtInt(n.users)} · ${fmtInt(n.sessions)} визитов`}</div><button class="x">×</button></div><div class="pb">`;
    if (n.text) h += `<div class="txt">${esc(n.text)}</div>`;
    if (n.conv != null) h += `<div class="cmp"><div><span>Были здесь → «${esc(d.goal)}»</span><b>${pctRu(n.conv)}</b></div><div><span>Не были → «${esc(d.goal)}»</span><b>${pctRu(n.convRest)}</b></div></div>`;
    if (n.kind === 'step') h += `<div class="kv"><span>Визитов заканчиваются здесь (выход)</span><b>${pctRu(n.exitRate)} · ${fmtInt(n.exits)}</b></div>`;
    if (outs.length || n.exitRate) {
      h += `<h5>Куда уходят</h5><div class="bars">` + outs.map(l => `<div class="b"><div class="bl"><span>→ ${esc(l.target)}</span><span>${pctRu(l.share)}</span></div><div class="meter"><i style="width:${l.share * 100}%"></i></div></div>`).join('');
      if (n.exitRate) h += `<div class="b"><div class="bl"><span>✕ выход</span><span>${pctRu(n.exitRate)}</span></div><div class="meter"><i style="width:${n.exitRate * 100}%;background:var(--neg)"></i></div></div>`;
      h += '</div>';
    }
    if (ins.length) h += `<h5>Откуда приходят</h5>` + ins.slice(0, 6).map(l => `<div class="kv"><span>${esc(l.source)} →</span><b>${fmtInt(l.count)}</b></div>`).join('');
    h += `<div class="acts">${n.kind !== 'start' ? `<button class="btn primary sm" data-prof="node">Кто здесь бывает</button>` : ''}${n.exitRate ? `<button class="btn sm" data-prof="exit">Кто уходит отсюда</button>` : ''}${n.kind === 'step' ? `<button class="btn sm" data-goal>Сделать целью</button>` : ''}<button class="btn sm" data-ask>Спросить аналитика</button></div></div>`;
    this.panel(h);
    const P = $('jrPanel');
    P.querySelectorAll('[data-prof]').forEach(b => b.onclick = () => this.profile(b.dataset.prof === 'node' ? {node: n.id} : {exitFrom: n.id}));
    const g = P.querySelector('[data-goal]'); if (g) g.onclick = () => { this.goal = n.id; this.load(false); };
    P.querySelector('[data-ask]').onclick = () => this.ask(`Шаг «${n.label}»`, {kind: 'journey-step', step: n.label, goal: d.goal, segment: d.segment, users: n.users, userShare: n.userShare, exitRate: n.exitRate, conv: n.conv, convRest: n.convRest, text: n.text},
      `Разбери шаг «${n.label}» в пути клиентов${d.segment ? ' (сегмент ' + d.segment + ')' : ''}: почему с него уходят, как он влияет на «${d.goal || 'цель'}», и что изменить в продукте.`);
  },

  async profile(what) {
    const d = this.data;
    const body = {dataset: this.dataset, goal: this.goal || undefined, ...what};
    if (this.seg.col && this.seg.val) { body.segmentColumn = this.seg.col; body.segmentValue = this.seg.val; }
    try {
      const p = await api('/journey/profile', {method: 'POST', body: JSON.stringify(body)});
      if (what.node) this.select(what.node); else this.select(null);
      if (what.from) this.highlight([what.from, what.to], [what.from + '→' + what.to]);
      let h = `<div class="ph"><span class="ty">Кто эти люди</span><h2>${esc(p.title)}</h2><div class="sb">${fmtInt(p.size)} клиентов · ${pctRu(p.share)} от всех${p.segment ? ' в сегменте ' + esc(p.segment) : ''}</div><button class="x">×</button></div><div class="pb">`;
      if (p.goal && p.conv != null) h += `<div class="cmp"><div><span>Дошли до «${esc(p.goal)}»</span><b>${pctRu(p.conv)}</b></div><div><span>Остальные</span><b>${pctRu(p.convRest)}</b></div></div>`;
      h += `<h5>Чем отличаются от остальных</h5>`;
      h += p.traits.length ? p.traits.map((t, i) => `<div class="trait" data-t="${i}" title="Клик — показать пути только этого сегмента" style="cursor:pointer"><span class="r ${t.direction}">${t.ratio ? times(t.ratio) : '—'}</span><span><b>${esc(t.column)}: ${esc(t.value)}</b> — ${pctRu(t.share)} в группе против ${pctRu(t.shareRest)} у остальных<br><small style="color:var(--faint)">${esc(t.source)} · q ${fmtP(t.q)}</small></span></div>`).join('')
        : `<div class="empty-s">Значимых отличий нет (проверено ${p.tested} сравнений с поправкой на множественность).</div>`;
      if (p.sample?.length) h += `<h5>Примеры</h5><div class="txt" style="font-size:11.5px">${p.sample.map(esc).join(', ')}${p.size > p.sample.length ? '…' : ''}</div>`;
      h += `<div class="acts"><button class="btn sm" data-ask>Спросить аналитика</button></div></div>`;
      this.panel(h);
      $('jrPanel').querySelectorAll('[data-t]').forEach(el => el.onclick = () => { const t = p.traits[+el.dataset.t]; if (d.segments[t.column]) this.setSegment(t.column, t.value); });
      $('jrPanel').querySelector('[data-ask]').onclick = () => this.ask(p.title, {kind: 'journey-profile', title: p.title, size: p.size, conv: p.conv, convRest: p.convRest, traits: p.traits.map(t => t.text)},
        `Кто эти клиенты — «${p.title}»? Чем они отличаются, почему так ведут себя и что с этим делать?`);
    } catch (e) { toast(e.message); }
  },

  ask(label, node, question) {
    AI.setCtx({label: 'Пути: ' + label, node}); AI.open();
    $('aiInput').value = question; $('aiInput').dispatchEvent(new Event('input')); $('aiInput').focus();
  },
};

/** «×2,2» — во сколько раз. */
const times = v => '×' + (+v).toLocaleString('ru-RU', {maximumFractionDigits: v < 10 ? 1 : 0});

Journey.init();
document.addEventListener('theme', () => { if (Journey.data) Journey.draw(); });
