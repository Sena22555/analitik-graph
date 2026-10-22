/* ═══════════════════════ оболочка приложения: вкладки ═══════════════════════ */
const App = {
  current: 'Graph',
  view(name) {
    if (!$('view' + name)) name = 'Graph';
    App.current = name;
    try { localStorage.setItem('ag.view', name); } catch (e) { }
    document.querySelectorAll('#tabs .tab').forEach(t => t.classList.toggle('on', t.dataset.view === name));
    ['Graph', 'Journey', 'Sql', 'Pivot', 'Reports'].forEach(v => $('view' + v).hidden = v !== name);
    // секции сайдбара: data-for — в каких видах секция видна
    document.querySelectorAll('#side .sec[data-for]').forEach(sec => sec.classList.toggle('off', !sec.dataset.for.split(' ').includes(name)));
    if ($('secView')) $('secView').style.display = S.ds && S.focus ? '' : 'none';
    if (name === 'Graph' && typeof onGraphView === 'function') onGraphView();
    if (name === 'Pivot' && typeof Pivot !== 'undefined') Pivot.showTables();
    if (name === 'Reports') setTimeout(() => Deck.fit(), 30);
    if (name === 'Journey' && typeof Journey !== 'undefined') Journey.show();
    if (name === 'Sql' && typeof Sql !== 'undefined') Sql.show();
  },
};
document.querySelectorAll('#tabs .tab').forEach(t => t.onclick = () => App.view(t.dataset.view));

/* ═══════════════════════ ИИ-аналитик ═══════════════════════ */
const AI = {
  history: [],          // {role, content} — текст для модели
  images: [],           // приложенные картинки (dataURL)
  ctx: null,            // контекст (выбранный узел)
  busy: false, ctrl: null, cfg: null,

  SUGG: [
    ['Анализ', 'Что сильнее всего влияет на выручку? Проверь многофакторной моделью и отдели корреляцию от реального эффекта.'],
    ['Анализ', 'Где мы теряем пользователей в воронке и у каких сегментов конверсия значимо хуже?'],
    ['Финансы', 'Сделай финансовую сводку и юнит-экономику по подпискам и источникам трафика: ARPU, ARPPU, средний чек, концентрация выручки.'],
    ['Финансы', 'ABC-анализ выручки по городам и источникам: на чём держится 80% денег?'],
    ['Документы', 'Подготовь официальный аналитический отчёт по ГОСТ с выводами и планом мероприятий.'],
    ['Документы', 'Сделай презентацию для совета директоров: ключевые цифры, драйверы, риски, рекомендации.'],
  ],

  init() {
    $('bAi').onclick = () => this.toggle();
    $('aiClose').onclick = () => this.toggle(false);
    $('aiClear').onclick = () => { if (this.ctrl) this.ctrl.abort(); this.history = []; this.empty(); };
    $('aiSend').onclick = () => this.busy ? this.stop() : this.submit();
    const ta = $('aiInput');
    ta.addEventListener('input', () => { ta.style.height = 'auto'; ta.style.height = Math.min(160, ta.scrollHeight) + 'px'; });
    ta.addEventListener('keydown', e => { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); if (!this.busy) this.submit(); } });
    ta.addEventListener('paste', e => { const f = [...(e.clipboardData?.files || [])].filter(x => x.type.startsWith('image/')); if (f.length) { e.preventDefault(); f.forEach(x => this.addImageFile(x)); } });
    $('aiAttach').onclick = () => $('aiFile').click();
    $('aiFile').onchange = e => { [...e.target.files].forEach(f => this.addImageFile(f)); e.target.value = ''; };
    $('aiShot').onclick = async () => { if (!S.ds) return toast("Карта ещё не построена"); App.view('Graph'); this.addImage(await graphDataUrl(1)); toast("Снимок карты приложен", true); };
    $('ai').addEventListener('dragover', e => e.preventDefault());
    $('ai').addEventListener('drop', e => { e.preventDefault(); [...e.dataTransfer.files].filter(f => f.type.startsWith('image/')).forEach(f => this.addImageFile(f)); });
    $('aiModel').onclick = () => Settings.open();
    this.empty(); this.loadCfg();
  },
  toggle(on) {
    const o = on ?? !$('app').classList.contains('ai-open');
    $('app').classList.toggle('ai-open', o);
    try { localStorage.setItem('ag.ai', o ? '1' : '0'); } catch (e) { }
    setTimeout(() => { Deck.fit(); }, 280);
    if (o) setTimeout(() => $('aiInput').focus(), 260);
  },
  open() { this.toggle(true); },
  async loadCfg() {
    try { this.cfg = await api('/ai/config'); } catch (e) { this.cfg = null; }
    const b = $('aiModel');
    if (this.cfg && this.cfg.configured) { b.textContent = this.cfg.model; b.className = 'chip ok'; }
    else { b.textContent = 'настроить ИИ'; b.className = 'chip bad'; }
  },

  empty() {
    const groups = [...new Set(this.SUGG.map(x => x[0]))];
    $('aiBody').innerHTML = `<div class="ai-empty"><h3>Задайте вопрос к данным</h3>
      <p>Ассистент считает реальные цифры по вашей таблице инструментами сервера, проверяет значимость, строит сводные и графики, готовит отчёты по ГОСТ и презентации. Понимает изображения — можно приложить скриншот или фото таблицы.</p>
      ${groups.map(g => `<div class="grp">${g}</div><div class="sugg">${this.SUGG.map((x, k) => x[0] === g ? `<button data-k="${k}">${esc(x[1])}</button>` : '').join('')}</div>`).join('')}</div>`;
    $('aiBody').querySelectorAll('.sugg button').forEach(b => b.onclick = () => this.send(this.SUGG[+b.dataset.k][1]));
  },

  /* ── вложения и контекст ── */
  addImageFile(f) {
    if (f.size > 12e6) return toast('Картинка больше 12 МБ');
    const r = new FileReader(); r.onload = () => this.shrink(r.result).then(u => this.addImage(u)); r.readAsDataURL(f);
  },
  shrink(url) { // уменьшаем до 1600px, чтобы не гонять мегабайты в модель
    return new Promise(res => { const img = new Image(); img.onload = () => { const k = Math.min(1, 1600 / Math.max(img.width, img.height)); if (k === 1 && url.length < 1.5e6) return res(url);
      const c = document.createElement('canvas'); c.width = img.width * k; c.height = img.height * k; c.getContext('2d').drawImage(img, 0, 0, c.width, c.height); res(c.toDataURL('image/jpeg', 0.88)); }; img.src = url; });
  },
  addImage(u) { this.images.push(u); this.renderAtts(); },
  renderAtts() {
    $('aiAtts').innerHTML = this.images.map((u, i) => `<div class="att"><img src="${u}"><button data-i="${i}">×</button></div>`).join('');
    $('aiAtts').querySelectorAll('button').forEach(b => b.onclick = () => { this.images.splice(+b.dataset.i, 1); this.renderAtts(); });
  },
  setCtx(c) {
    this.ctx = c;
    $('aiCtx').innerHTML = c ? `<span class="ctxchip"><span>Контекст: ${esc(c.label)}</span><button title="Убрать контекст">×</button></span>` : '';
    if (c) $('aiCtx').querySelector('button').onclick = () => this.setCtx(null);
  },
  askAbout(d) {
    const f = d.ref?.filters || [];
    this.setCtx({label: (f.length ? f.map(x => `${x.c} = ${x.v}`).join(' → ') : d.label) + (d.kind === 'metric' ? ' (метрика)' : ''),
      node: {kind: d.kind, label: d.label, sub: d.sub, column: d.column, filters: f, stats: (d.stats || []).slice(0, 14)}});
    this.open();
    const q = d.kind === 'cat' ? `Что особенного в сегменте «${f.map(x => x.v).join(' / ')}»? Чем он отличается от остальных, сколько приносит и что с ним делать?`
      : d.kind === 'metric' ? `Что влияет на «${d.label}»? Найди главные драйверы и покажи их.`
      : d.kind === 'dim' || d.kind === 'time' ? `Разбери измерение «${d.label}»: как распределены группы и как они влияют на ключевые метрики?`
      : `Объясни узел «${d.label}» простыми словами и что из этого следует для команды.`;
    $('aiInput').value = q; $('aiInput').dispatchEvent(new Event('input')); $('aiInput').focus();
  },

  /* ── отправка ── */
  submit() { const t = $('aiInput').value.trim(); if (!t && !this.images.length) return; this.send(t || 'Что на картинке? Свяжи с нашими данными.'); },
  stop() { if (this.ctrl) this.ctrl.abort(); },
  async send(text, extraCtx) {
    if (this.busy) return;
    if (!this.cfg || !this.cfg.configured) { await this.loadCfg(); if (!this.cfg || !this.cfg.configured) { Settings.open(); return toast('Сначала укажите API-ключ в настройках'); } }
    this.open();
    if ($('aiBody').querySelector('.ai-empty')) $('aiBody').innerHTML = '';
    const imgs = this.images.slice(), ctx = extraCtx ? {...(this.ctx?.node ? {node: this.ctx.node} : {}), ...extraCtx} : this.ctx?.node ? {node: this.ctx.node} : null;
    const u = document.createElement('div'); u.className = 'msg user';
    u.innerHTML = `<div class="bubble">${this.ctx ? `<div class="ctxtag">Контекст: ${esc(this.ctx.label)}</div>` : ''}${esc(text)}${imgs.map(x => `<img src="${x}">`).join('')}</div>`;
    $('aiBody').appendChild(u);
    this.history.push({role: 'user', content: text});
    $('aiInput').value = ''; $('aiInput').style.height = 'auto'; this.images = []; this.renderAtts();

    const bot = document.createElement('div'); bot.className = 'msg bot';
    bot.innerHTML = '<div class="steps"></div><div class="arts" style="display:flex;flex-direction:column;gap:10px"></div><div class="thinking"><i></i><i></i><i></i></div><div class="md"></div>';
    $('aiBody').appendChild(bot); this.scroll();
    const steps = bot.querySelector('.steps'), arts = bot.querySelector('.arts'), md = bot.querySelector('.md'), think = bot.querySelector('.thinking');
    let lastStep = null, answer = '', artSummary = [];
    this.busy = true; this.sendBtn(true); this.ctrl = new AbortController();
    const hist = this.history.slice(0, -1).slice(-12).concat([{role: 'user', content: text, images: imgs}]);
    try {
      const r = await fetch(API + '/api/v2/ai/chat', {method: 'POST', headers: {'Content-Type': 'application/json'}, signal: this.ctrl.signal,
        body: JSON.stringify({datasetId: S.ds ? S.ds.id : null, messages: hist, context: ctx})});
      if (!r.ok || !r.body) throw new Error('Сервер ответил ' + r.status);
      const reader = r.body.getReader(), dec = new TextDecoder(); let buf = '';
      const handle = async (ev, data) => {
        if (ev === 'step') { if (lastStep) lastStep.className = 'step ok'; lastStep = document.createElement('div'); lastStep.className = 'step run'; lastStep.innerHTML = `<span class="ic"></span><span>${esc(data.label)}</span>`; steps.appendChild(lastStep); }
        else if (ev === 'stepError') { if (lastStep) { lastStep.className = 'step err'; lastStep.innerHTML += ` — ${esc(data.message)}`; lastStep = null; } }
        else if (ev === 'artifact') { if (lastStep) { lastStep.className = 'step ok'; } arts.appendChild(await this.artifact(data)); artSummary.push(this.artText(data)); }
        else if (ev === 'vision') { if (lastStep) lastStep.className = 'step ok'; arts.insertAdjacentHTML('beforeend', `<details class="vision"><summary>Распознано на изображении (${esc(data.model)})</summary><div class="md">${this.md(data.text)}</div></details>`); }
        else if (ev === 'datasetChanged') { if (S.ds && S.ds.id === data.id) { await rebuild(); Pivot.fill(); } }
        else if (ev === 'answer') { if (lastStep) lastStep.className = 'step ok'; answer = data.text || ''; md.innerHTML = this.md(answer); }
        else if (ev === 'error') throw new Error(data.message);
        this.scroll();
      };
      while (true) {
        const {value, done} = await reader.read(); if (done) break;
        buf += dec.decode(value, {stream: true});
        let i; while ((i = buf.indexOf('\n\n')) >= 0) {
          const chunk = buf.slice(0, i); buf = buf.slice(i + 2);
          let ev = 'message', data = '';
          chunk.split('\n').forEach(l => { if (l.startsWith('event:')) ev = l.slice(6).trim(); else if (l.startsWith('data:')) data += l.slice(5); });
          if (data) await handle(ev, JSON.parse(data));
        }
      }
      this.history.push({role: 'assistant', content: (answer || '') + (artSummary.length ? '\n\n[Построено: ' + artSummary.join('; ') + ']' : '')});
    } catch (e) {
      if (lastStep) lastStep.className = 'step err';
      if (e.name === 'AbortError') md.insertAdjacentHTML('beforeend', '<div class="tnote">Остановлено.</div>');
      else { md.innerHTML = `<div class="errbox">${esc(e.message)}<div class="acts"><button class="btn sm" data-set>Настройки ИИ</button><button class="btn sm" data-retry>Повторить</button></div></div>`;
        md.querySelector('[data-set]').onclick = () => Settings.open(); md.querySelector('[data-retry]').onclick = () => { this.history.pop(); this.send(text, extraCtx); }; }
    } finally {
      think.remove(); this.busy = false; this.ctrl = null; this.sendBtn(false); this.scroll();
    }
  },
  sendBtn(stop) { $('aiSend').innerHTML = stop ? '<svg class="i"><use href="#i-stop"/></svg><span>Остановить</span>' : '<svg class="i"><use href="#i-send"/></svg><span>Отправить</span>'; },
  scroll() { const b = $('aiBody'); b.scrollTop = b.scrollHeight; },
  md(t) {
    const safe = String(t || '').replace(/</g, '&lt;');   // HTML от модели не исполняем
    try { return marked.parse(safe, {breaks: true}); } catch (e) { return esc(t); }
  },
  artText(a) { return a.type === 'report' ? `отчёт «${a.title}»` : a.type === 'image' ? 'изображение' : a.type === 'deck' ? `презентация «${a.title}»` : a.type === 'column' ? `колонка «${a.name}»` : a.type === 'dataset' ? `срез «${a.name}»` : a.type === 'graph_action' ? 'действие на графе' : `${a.type === 'chart' ? 'график' : 'таблица'} «${a.title}»`; },

  /* ── карточки артефактов ── */
  async artifact(a) {
    const el = document.createElement('div');
    if (a.type === 'table') {
      el.className = 'art';
      el.innerHTML = `<div class="art-h"><span class="art-k">таблица</span><div class="tt"><b>${esc(a.title)}</b><small>${esc(a.subtitle || '')}</small></div></div><div class="art-b">${tableHtml(a, {maxRows: 12})}</div>
        <div class="art-f"><button class="btn ghost" data-x>Excel</button><button class="btn ghost" data-c>CSV</button><button class="btn ghost" data-b>Развернуть</button>${a.spec ? '<button class="btn ghost" data-p>Открыть в сводных</button>' : ''}</div>`;
      el.querySelector('[data-x]').onclick = () => exportXlsx(a); el.querySelector('[data-c]').onclick = () => exportCsv(a);
      el.querySelector('[data-b]').onclick = () => openBig(`<h3 style="margin-bottom:4px">${esc(a.title)}</h3><div class="tnote" style="margin-bottom:10px">${esc(a.subtitle || '')}</div>` + tableHtml(a) + `<div class="mf"><button class="btn sm" id="bgX">Скачать Excel</button></div>`, b => b.querySelector('#bgX').onclick = () => exportXlsx(a));
      if (a.spec) { el.querySelector('[data-p]').onclick = () => Pivot.load(a.spec); Pivot.addSaved(a); }
    } else if (a.type === 'chart') {
      el.className = 'art';
      el.innerHTML = `<div class="art-h"><span class="art-k">график</span><div class="tt"><b>${esc(a.title)}</b><small>${esc(a.subtitle || '')}</small></div></div><div class="art-b" data-v></div>
        <div class="art-f"><button class="btn ghost" data-png>PNG</button><button class="btn ghost" data-b>Развернуть</button></div>`;
      chartHtmlInto(el.querySelector('[data-v]'), a, {w: 390, h: 230});
      el.querySelector('[data-png]').onclick = async () => { const u = await svgToPng(chartSvg(a, {w: 1200, h: 620, big: true, fs: 15, palette: uiPalette()}), 1200, 620, {bg: cssVar('--surface'), ink: cssVar('--text'), muted: cssVar('--muted'), grid: cssVar('--line')}); const l = document.createElement('a'); l.href = u; l.download = safeName(a.title) + '.png'; l.click(); };
      el.querySelector('[data-b]').onclick = () => openBig(`<h3 style="margin-bottom:10px">${esc(a.title)}</h3><div id="bgC"></div>`, b => chartHtmlInto(b.querySelector('#bgC'), a, {w: 1100, h: 500, big: true, fs: 13}));
    } else if (a.type === 'deck') {
      Deck.add(a);
      const th = THEMES[a.theme] || THEMES.executive;
      el.className = 'art';
      el.innerHTML = `<div class="art-h"><span class="art-k">презентация</span><div class="tt"><b>${esc(a.title)}</b><small>${a.slides.length} слайдов · оформление «${th.ru}» · цифры рассчитаны по данным</small></div></div>
        <div class="art-b"><div class="deck-prev" data-o style="aspect-ratio:16/9"><span class="cnt">${a.slides.length} слайдов</span></div></div>
        <div class="art-f"><button class="btn ghost" data-o2>Открыть</button><button class="btn ghost" data-pp>PowerPoint</button><button class="btn ghost" data-pdf>PDF</button></div>`;
      const prev = el.querySelector('.deck-prev'); requestAnimationFrame(() => { const w = prev.clientWidth || 380, sl = Deck.render(a, 0); sl.style.transform = `scale(${w / 1280})`; sl.style.boxShadow = 'none'; prev.insertBefore(sl, prev.firstChild); });
      el.querySelector('[data-o]').onclick = el.querySelector('[data-o2]').onclick = () => Deck.open(a.id);
      el.querySelector('[data-pp]').onclick = () => Deck.pptx(a); el.querySelector('[data-pdf]').onclick = () => Deck.pdf(a);
    } else if (a.type === 'report') {
      Report.add(a);
      const o = Report.outline(JSON.parse(JSON.stringify(a)));
      el.className = 'art';
      el.innerHTML = `<div class="art-h"><span class="art-k">отчёт</span><div class="tt"><b>${esc(a.title)}</b><small>${esc(a.report_type || 'Аналитический отчёт')} · ГОСТ 7.32-2017 · ${o.secs.length} разделов, ${o.figs} рис., ${o.tabs} табл.</small></div></div>
        <div class="art-b" style="font-size:12.3px;color:var(--text-2);line-height:1.55">${esc(trunc(a.abstract || '', 260))}</div>
        <div class="art-f"><button class="btn ghost" data-o>Открыть</button><button class="btn ghost" data-pdf>Скачать PDF</button></div>`;
      el.querySelector('[data-o]').onclick = () => Report.open(a.id); el.querySelector('[data-pdf]').onclick = () => Report.pdf(a);
    } else if (a.type === 'image') {
      el.className = 'art';
      el.innerHTML = `<div class="art-b" style="padding:10px"><img class="gen" src="${esc(a.url)}" alt=""></div><div class="art-f"><button class="btn ghost" data-dl>Скачать</button><button class="btn ghost" data-b>Развернуть</button></div>`;
      el.querySelector('[data-dl]').onclick = () => { const l = document.createElement('a'); l.href = a.url; l.download = 'image.png'; l.click(); };
      el.querySelector('[data-b]').onclick = () => openBig(`<img src="${esc(a.url)}" style="width:100%;border-radius:8px">`);
    } else if (a.type === 'graph_action') {
      el.className = 'art mini'; const t = (a.targets || []).map(x => x.value ? `${x.column} = ${x.value}` : x.column).join(', ');
      el.innerHTML = `<span style="flex:1">${a.action === 'reset' ? 'Карта свёрнута к обзору' : 'Показано на карте: <b>' + esc(t) + '</b>'}</span><button class="btn sm ghost">Показать</button>`;
      const go = () => { App.view('Graph'); GraphAPI.act(a.action, a.targets); };
      el.querySelector('button').onclick = go; go();
    } else if (a.type === 'column') {
      el.className = 'art';
      const lv = a.levels ? Object.entries(a.levels).map(([k, v]) => `${esc(k)}: <b>${fmtInt(v)}</b>`).join(' · ') : a.mean !== undefined ? `среднее <b>${fmt(a.mean)}</b>, от ${fmt(a.min)} до ${fmt(a.max)}` : '';
      el.innerHTML = `<div class="art-h"><span class="art-k">данные</span><div class="tt"><b>Добавлена колонка «${esc(a.name)}» (${esc(a.columnType)})</b><small><code>${esc(a.formula)}</code></small></div></div><div class="art-b" style="font-size:12px;color:var(--text-2)">${lv}${a.empty ? ` · пустых: ${a.empty}` : ''}<div class="tnote">Граф и статистика пересчитаны автоматически.</div></div>`;
    } else if (a.type === 'dataset') {
      el.className = 'art mini';
      el.innerHTML = `<span style="flex:1">Новый срез «${esc(a.name)}» · ${fmtInt(a.rows)} строк</span><button class="btn sm">Открыть карту</button>`;
      el.querySelector('button').onclick = () => { App.view('Graph'); openDatasetById(a.id); };
    } else { el.className = 'art mini'; el.textContent = JSON.stringify(a).slice(0, 200); }
    return el;
  },
};

/* ═══════════════════════ настройки ИИ ═══════════════════════ */
const Settings = {
  async open() {
    const c = await api('/ai/config').catch(() => ({}));
    $('setUrl').value = c.baseUrl || ''; $('setKey').value = ''; $('setKey').placeholder = c.key ? `сохранён: ${c.key} (оставьте пустым, чтобы не менять)` : 'sk-…';
    $('setModel').value = c.model || ''; $('setVision').value = c.visionModel || ''; $('setImage').value = c.imageModel || ''; $('setFile').textContent = c.configFile || 'analitik-ai.properties';
    $('setStatus').textContent = ''; $('setStatus').className = 'set-status';
    $('mSet').classList.add('show');
    if (c.configured) this.models(true);
  },
  async save() {
    await api('/ai/config', {method: 'POST', body: JSON.stringify({baseUrl: $('setUrl').value, apiKey: $('setKey').value, model: $('setModel').value, visionModel: $('setVision').value, imageModel: $('setImage').value})});
    await AI.loadCfg();
  },
  async models(quiet) {
    const st = $('setStatus');
    try {
      st.textContent = 'Проверяю…'; st.className = 'set-status';
      const r = await api('/ai/models');
      $('modelList').innerHTML = r.models.map(m => `<option value="${esc(m)}">`).join('');
      const want = ($('setModel').value || '').toLowerCase().replace(/[^a-z0-9]/g, '');
      const hit = r.models.find(m => m.toLowerCase().replace(/[^a-z0-9]/g, '').includes(want));
      const img = r.models.filter(m => /image|dall|flux|imagen|seedream|recraft|kandinsky|sdxl|ideogram/i.test(m));
      st.textContent = `Подключено: ${r.models.length} моделей` + (img.length ? ` · генерация изображений: ${img.slice(0, 3).join(', ')}` : ' · моделей генерации изображений не найдено') + (want ? (hit ? ` · основная: ${hit}` : ' · ⚠ указанная модель не найдена — выберите из списка') : '');
      st.className = 'set-status ' + (want && !hit ? 'bad' : 'ok');
    } catch (e) { if (!quiet) { st.textContent = e.message; st.className = 'set-status bad'; } else st.textContent = ''; }
  },
};
$('setSave').onclick = async () => { try { await Settings.save(); $('mSet').classList.remove('show'); toast('Настройки ИИ сохранены', true); } catch (e) { toast(e.message); } };
$('setTest').onclick = async () => { try { await Settings.save(); await Settings.models(false); } catch (e) { $('setStatus').textContent = e.message; $('setStatus').className = 'set-status bad'; } };
$('bSettings').onclick = () => Settings.open();
document.querySelectorAll('[data-close]').forEach(b => b.onclick = () => b.closest('.modal-bg').classList.remove('show'));
document.querySelectorAll('.modal-bg').forEach(m => m.addEventListener('click', e => { if (e.target === m) m.classList.remove('show'); }));

Pivot.init(); Deck.init(); Report.init(); AI.init(); Library.list(); App.view('Graph');
