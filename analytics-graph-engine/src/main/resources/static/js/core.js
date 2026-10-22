/* ═══════════════════════ базовое ═══════════════════════ */
const API = location.protocol.startsWith('http') ? '' : 'http://localhost:8080';
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const LEVEL_RU = {strong:'сильная', medium:'средняя', weak:'слабая', none:'незначимая'};
const LEVEL_W = {strong:3, medium:2, weak:1, none:0};
const KIND = {
  root:  {ru:'Центр', v:'--c-root'},
  table: {ru:'Таблица', v:'--c-root'},
  dim:   {ru:'Измерение', v:'--c-dim'},
  time:  {ru:'Время', v:'--c-time'},
  metric:{ru:'Метрика', v:'--c-metric'},
  cat:   {ru:'Сегмент', v:'--c-cat'},
  kpi:   {ru:'Показатель сегмента', v:'--pos'},
  funnel:{ru:'Воронка', v:'--c-funnel'},
  stage: {ru:'Этап воронки', v:'--c-funnel'},
  bin:   {ru:'Интервал', v:'--c-bin'},
  more:  {ru:'Ещё', v:'--faint'}
};
/** Значение CSS-переменной текущей темы. */
const cssVar = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
/** Цвета силы/знака — читаются из темы при каждом рендере. */
const COLOR = new Proxy({}, {get: (_, k) => cssVar({strong: '--strong', medium: '--medium', weak: '--weak', none: '--none', pos: '--pos', neg: '--neg'}[k] || '--muted')});
/** Смешение цвета с фоном (для «подкрашенной» заливки узлов). */
function tint(col, k) { const a = d3.color(col), b = d3.color(cssVar('--surface')); if (!a || !b) return col; return d3.interpolateRgb(b, a)(k).toString(); }
function isDark() { return document.documentElement.dataset.theme === 'dark'; }

async function api(path, opts = {}) {
  const r = await fetch(API + '/api/v2' + path, {headers: {'Content-Type': 'application/json'}, ...opts});
  let d; try { d = await r.json(); } catch { d = {}; }
  if (!r.ok) throw new Error(d.error || ('HTTP ' + r.status));
  return d;
}
/** Уведомление; action = {label, run} — кнопка действия прямо в уведомлении (держится дольше). */
function toast(msg, ok, action) {
  const t = $('toast');
  t.textContent = msg;
  if (action) {
    const b = document.createElement('button'); b.className = 'toast-act'; b.textContent = action.label;
    b.onclick = () => { t.classList.remove('show'); action.run(); };
    t.appendChild(b);
  }
  t.className = (ok ? '' : 'err ') + 'show';
  clearTimeout(t._t); t._t = setTimeout(() => t.classList.remove('show'), action ? 12000 : ok ? 3200 : 6000);
}
function loading(on, text) { $('loader').classList.toggle('show', on); if (text) $('loaderT').textContent = text; }

/* ═══════════════════════ формат ═══════════════════════ */
function fmt(v) {
  v = +v; if (!isFinite(v)) return '—'; const a = Math.abs(v);
  const s = a >= 1000 ? v.toLocaleString('ru-RU', {maximumFractionDigits: 0}) : a >= 100 ? v.toLocaleString('ru-RU', {maximumFractionDigits: 1}) : v.toLocaleString('ru-RU', {maximumFractionDigits: a < 0.01 && a > 0 ? 4 : 3});
  return s.replace('-', '−');
}
const fmtInt = v => (+v).toLocaleString('ru-RU');
const fmtP = p => { p = +p; return !isFinite(p) ? '—' : p < 0.001 ? '< 0,001' : p.toLocaleString('ru-RU', {maximumFractionDigits: 3, minimumFractionDigits: 3}); };
const pctRu = v => v == null ? '—' : (v * 100).toLocaleString('ru-RU', {maximumFractionDigits: v < 0.1 ? 1 : 0}) + '%';
function plural(n, one, few, many) {
  n = Math.abs(n) % 100; const n1 = n % 10;
  if (n > 10 && n < 20) return many; if (n1 > 1 && n1 < 5) return few; if (n1 === 1) return one; return many;
}
const trunc = (s, n) => (s = String(s ?? '')).length > n ? s.slice(0, n - 1) + '…' : s;

