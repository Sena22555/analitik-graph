/* ═══════════════════════ командная палитра (⌘K): таблицы, колонки, узлы, разделы, действия ═══════════════════════ */
const Palette = {
  items: [], shown: [], index: 0,

  el: null,
  ensure() {
    if (this.el) return;
    this.el = document.createElement('div');
    this.el.className = 'modal-bg pal-bg';
    this.el.innerHTML = `<div class="pal" role="dialog" aria-label="Поиск">
      <div class="pal-in"><svg class="i "><use href="#i-search"/></svg><input id="palQ" placeholder="Таблица, колонка, раздел или действие…" autocomplete="off" spellcheck="false"><kbd>Esc</kbd></div>
      <div class="pal-list" id="palList"></div>
      <div class="pal-foot"><span><kbd>↑</kbd><kbd>↓</kbd> выбор</span><span><kbd>Enter</kbd> открыть</span><span><kbd>Esc</kbd> закрыть</span></div>
    </div>`;
    document.body.appendChild(this.el);
    this.el.onclick = e => { if (e.target === this.el) this.close(); };
    const q = this.el.querySelector('#palQ');
    q.oninput = () => { this.filter(q.value); };
    q.onkeydown = e => {
      if (e.key === 'ArrowDown') { e.preventDefault(); this.move(1); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); this.move(-1); }
      else if (e.key === 'Enter') { e.preventDefault(); this.choose(this.index); }
      else if (e.key === 'Escape') { e.preventDefault(); this.close(); }
    };
  },

  open(query = '') {
    this.ensure();
    this.build();
    this.el.classList.add('show');
    const q = this.el.querySelector('#palQ');
    q.value = query; this.filter(query);
    setTimeout(() => q.focus(), 20);
  },
  close() { if (this.el) this.el.classList.remove('show'); },

  /** Все варианты: что есть в рабочем пространстве прямо сейчас. */
  build() {
    const it = [];
    const add = (group, icon, title, sub, run, extra = '') => it.push({group, icon, title, sub, run, key: norm(title + ' ' + sub + ' ' + extra)});
    const tables = [...S.tables.values()];

    // действия, зависящие от места
    if (S.focus) {
      const t = S.tables.get(S.focus);
      add('Действия', 'i-chevl', 'Назад ко всем таблицам', 'Esc', () => { App.view('Graph'); backToAll(); });
      add('Действия', 'i-table', `Данные и профиль «${t.name}»`, 'строки, поиск, сортировка, профиль колонок', () => TablePanel.open(t.id));
      add('Действия', 'i-sql', `SQL по «${t.name}»`, 'открыть консоль с запросом', () => Sql.open(`SELECT *\nFROM ${SqlQ(t.name)}\nLIMIT 100`));
      if (t.stepColumn) add('Действия', 'i-route', `Пути клиентов по «${t.name}»`, 'как ходят по продукту', () => { Journey.dataset = t.id; App.view('Journey'); });
    }
    if (tables.length > 1) add('Действия', 'i-atlas', 'Собрать отчёт из графа', 'колонки из разных таблиц → один отчёт без раздувания сумм', () => Compose.open(), 'отчёт join соединить собрать конструктор');
    add('Действия', 'i-upload', 'Загрузить файл', 'CSV или JSON', () => $('file').click(), 'импорт csv json excel');
    add('Действия', 'i-plug', 'Подключить по API', 'JSON/CSV по ссылке, Google Sheets', () => openApiModal(), 'api url google sheets');
    add('Действия', 'i-atlas', 'Открыть демо-продукт', '4 связанные таблицы', () => WS.demoProduct(), 'demo демо');
    add('Действия', 'i-chat', 'Спросить ИИ-аналитика', 'открыть панель аналитика', () => AI.open(), 'ai ии чат вопрос');
    add('Действия', 'i-fit', 'Вписать граф в экран', 'F', () => { App.view('Graph'); fit(); });
    add('Действия', 'i-download', 'Сохранить граф в PNG', 'картинка', () => { App.view('Graph'); exportPng(); }, 'png картинка экспорт');
    add('Действия', 'i-moon', 'Сменить тему', 'светлая / тёмная', () => $('bTheme').click(), 'тема dark light');

    tables.filter(t => t.splittable).forEach(t => add('Действия', 'i-atlas', `Разложить «${t.name}» по таблицам`, 'сортировка несортированных данных', () => Structure.open(t.id), 'сортировка разложить нормализация'));
    add('Действия', 'i-collapse', 'Демо: несортированный лог', 'плоская выгрузка, которую можно разложить', () => openRawDemo(), 'демо сырой raw лог');

    // разделы
    [['Graph', 'i-graph', 'Граф', 'все таблицы и связи'], ['Journey', 'i-route', 'Пути', 'как клиенты ходят по продукту'], ['Sql', 'i-sql', 'SQL', 'запросы по всем таблицам'],
      ['Pivot', 'i-table', 'Сводные', 'конструктор сводных таблиц'], ['Reports', 'i-doc', 'Отчёты', 'ГОСТ-отчёты и презентации']]
      .forEach(([v, icon, title, sub]) => add('Разделы', icon, title, sub, () => App.view(v)));

    // таблицы и их колонки
    tables.forEach(t => add('Таблицы', 'i-table', t.name, `${fmtInt(t.rows)} строк · ${t.columns} колонок · ${t.roleRu}`, () => { App.view('Graph'); drill(t.id); }, 'таблица база'));
    tables.forEach(t => (t.columnList || []).forEach(c => {
      const graphable = ['NUMERIC', 'CATEGORICAL', 'DATE'].includes(c.type);
      add('Колонки', graphable ? 'i-graph' : 'i-table', c.name, `${t.name} · ${c.typeRu}${graphable ? '' : ' · откроется в данных'}`,
        () => graphable ? focusColumn(t.id, c.name) : TablePanel.open(t.id), 'колонка поле');
    }));

    // узлы текущего графа таблицы (сегменты, метрики, этапы)
    if (S.focus) [...S.nodes.values()].filter(n => n.kind !== 'table' && n.depth > 1).forEach(n =>
      add('На графе', 'i-graph', n.label, `${KIND[n.kind]?.ru || ''}${n.sub ? ' · ' + n.sub : ''}`, () => { App.view('Graph'); select(n.id); focusOn([n]); }));
    this.items = it;
  },

  filter(q) {
    const words = norm(q).split(/\s+/).filter(Boolean);
    const ORDER = {'Действия': 0, 'Таблицы': 1, 'Колонки': 2, 'На графе': 3, 'Разделы': 4};
    let list;
    if (!words.length) {
      list = this.items.filter(x => x.group !== 'Колонки' && x.group !== 'На графе');
    } else {
      list = this.items.map(x => {
        const t = norm(x.title); let score = 0;
        for (const w of words) {
          if (!x.key.includes(w)) return null;
          score += t.startsWith(w) ? 6 : (' ' + t).includes(' ' + w) ? 4 : t.includes(w) ? 2 : 1;
        }
        return {...x, score};
      }).filter(Boolean).sort((a, b) => b.score - a.score || ORDER[a.group] - ORDER[b.group]);
      // группируем, сохраняя порядок лучших совпадений внутри группы
      const groups = [...new Set(list.map(x => x.group))];
      list = groups.flatMap(g => list.filter(x => x.group === g).slice(0, 12));
    }
    this.shown = list.slice(0, 60);
    this.index = 0;
    this.render(words);
  },

  render(words) {
    // подсвечиваем самое длинное совпавшее слово запроса в названии
    const mark = s => {
      const w = words.filter(x => x.length > 1 && norm(s).includes(x)).sort((a, b) => b.length - a.length)[0];
      if (!w) return esc(s);
      const i = norm(s).indexOf(w);
      return esc(s.slice(0, i)) + '<mark>' + esc(s.slice(i, i + w.length)) + '</mark>' + esc(s.slice(i + w.length));
    };
    let last = null, html = '';
    this.shown.forEach((x, i) => {
      if (x.group !== last) { html += `<div class="pal-g">${esc(x.group)}</div>`; last = x.group; }
      html += `<div class="pal-it ${i === this.index ? 'on' : ''}" data-i="${i}"><svg class="i "><use href="#${x.icon}"/></svg><span class="t">${mark(x.title)}</span><span class="s">${esc(x.sub)}</span></div>`;
    });
    $('palList').innerHTML = html || '<div class="pal-empty">Ничего не нашлось. Попробуйте часть названия таблицы или колонки.</div>';
    $('palList').querySelectorAll('.pal-it').forEach(el => {
      el.onmousemove = () => { if (this.index !== +el.dataset.i) { this.index = +el.dataset.i; this.highlight(); } };
      el.onclick = () => this.choose(+el.dataset.i);
    });
  },

  highlight() {
    $('palList').querySelectorAll('.pal-it').forEach(el => el.classList.toggle('on', +el.dataset.i === this.index));
    const on = $('palList').querySelector('.pal-it.on'); if (on) on.scrollIntoView({block: 'nearest'});
  },
  move(d) { if (!this.shown.length) return; this.index = (this.index + d + this.shown.length) % this.shown.length; this.highlight(); },
  choose(i) { const x = this.shown[i]; if (!x) return; this.close(); setTimeout(() => x.run(), 10); },
};

/** Нормализация для поиска: регистр, «ё». */
function norm(s) { return String(s ?? '').toLowerCase().replace(/ё/g, 'е'); }

$('bFind').onclick = () => Palette.open();
