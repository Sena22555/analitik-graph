/**
 * Генератор тестовых данных пользовательской аналитики
 *
 * Использование:
 *   node generate-user-monitoring-1000.js [количество_строк] [путь_к_файлу.csv]
 *
 * Примеры:
 *   node generate-user-monitoring-1000.js
 *   node generate-user-monitoring-1000.js 500
 *   node generate-user-monitoring-1000.js 2000 ./data/users.csv
 */

const fs   = require('fs');
const path = require('path');

// ─── Аргументы командной строки ────────────────────────────
const COUNT    = parseInt(process.argv[2], 10) || 1000;
const OUT_PATH = process.argv[3] || path.join(
  require('os').homedir(), 'Downloads',
  `user-monitoring-${COUNT}x30.csv`
);

// ─── PRNG (детерминированный, воспроизводимый результат) ───
let seed = 20260921;
const random = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
const pick   = items => items[Math.floor(random() * items.length)];
const clamp  = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const round  = (v, d = 2) => Number(v.toFixed(d));

// ─── Справочники ────────────────────────────────────────────
const GENDERS       = ['Женщина', 'Мужчина', 'Ребёнок'];
const CITIES        = ['Москва', 'Санкт-Петербург', 'Казань', 'Екатеринбург', 'Новосибирск', 'Самара', 'Краснодар', 'Ростов-на-Дону'];
const COUNTRIES     = ['Россия', 'Россия', 'Россия', 'Россия', 'Казахстан', 'Беларусь'];
const SOURCES       = ['Органический поиск', 'Реклама', 'Социальные сети', 'Рекомендация', 'Email'];
const CAMPAIGNS     = ['Весна 2024', 'Ретаргетинг', 'Промокод', 'Контент', 'Без кампании'];
const SUBSCRIPTIONS = ['Нет', 'Базовая', 'Премиум'];
const DAY_PARTS     = ['Утро', 'День', 'Вечер', 'Ночь'];

const SOURCE_BOOST = {
  'Рекомендация': 13, 'Органический поиск': 8,
  'Email': 6, 'Реклама': 2, 'Социальные сети': -1
};
const SUB_BOOST = { 'Нет': 0, 'Базовая': 9, 'Премиум': 18 };

// ─── Заголовки CSV ──────────────────────────────────────────
const HEADERS = [
  'Дата', 'ID пользователя', 'Пол', 'Возрастная группа', 'Город', 'Страна',
  'Устройство', 'ОС', 'Браузер', 'Источник трафика', 'Кампания',
  'Тип пользователя', 'Подписка', 'Этап воронки', 'Дети', 'Время суток',
  'Время в продукте мин', 'Просмотрено страниц', 'События', 'Добавлено в корзину',
  'Покупки', 'Выручка', 'Средний чек', 'NPS', 'Оценка сервиса',
  'Обращения в поддержку', 'Скидка %', 'Баллы лояльности', 'Активных дней', 'Оценка конверсии'
];

// ─── Генерация строк ────────────────────────────────────────
function generateRow(i) {
  const day          = String(1 + ((i - 1) % 30)).padStart(2, '0');
  const date         = `2024-05-${day}`;
  const gender       = pick(GENDERS);
  const ageGroup     = gender === 'Ребёнок' ? 'До 18' : pick(['18-24', '25-34', '35-44', '45+']);
  const city         = pick(CITIES);
  const country      = pick(COUNTRIES);
  const device       = random() < 0.68 ? 'Mobile' : 'Desktop';
  const os           = device === 'Mobile' ? pick(['Android', 'iOS']) : pick(['Windows', 'macOS', 'Linux']);
  const browser      = device === 'Mobile' ? pick(['Chrome Mobile', 'Safari Mobile']) : pick(['Chrome', 'Safari', 'Firefox']);
  const source       = pick(SOURCES);
  const campaign     = source === 'Органический поиск' ? 'Без кампании' : pick(CAMPAIGNS);
  const userType     = random() < 0.62 ? 'Новый' : 'Вернувшийся';
  const subscription = random() < 0.60 ? 'Нет' : random() < 0.72 ? 'Базовая' : 'Премиум';
  const children     = (ageGroup === '25-34' || ageGroup === '35-44') ? (random() < 0.47 ? 'Да' : 'Нет') : 'Нет';
  const dayPart      = pick(DAY_PARTS);

  const srcBoost  = SOURCE_BOOST[source];
  const subBoost  = SUB_BOOST[subscription];
  const devBoost  = device === 'Desktop' ? 5 : 0;
  const childPen  = children === 'Да' ? 4 : 0;

  const session   = round(clamp(8 + srcBoost + subBoost + devBoost - childPen + (random() - 0.5) * 18, 2, 75));
  const pages     = round(clamp(session * 0.31 + (random() - 0.5) * 3, 1, 28));
  const events    = round(clamp(pages * 1.8 + (random() - 0.5) * 5, 1, 55));
  const addCart   = clamp(Math.round((events - 5) / 12 + (random() < 0.30 ? 1 : 0)), 0, 5);

  const basePurch = clamp(0.05 + addCart * 0.16 + subBoost / 150 + (source === 'Рекомендация' ? 0.10 : 0), 0.02, 0.88);
  const purchProb = children === 'Да' ? basePurch * 0.80 : basePurch;
  const purchases = random() < purchProb ? (random() < 0.15 ? 2 : 1) : 0;
  const avgCheck  = purchases ? round(750 + subBoost * 45 + random() * 900, 2) : 0;
  const revenue   = round(purchases * avgCheck, 2);

  const stage  = purchases >= 2 ? 'Повторная покупка'
               : purchases === 1 ? 'Оплата'
               : addCart > 0 ? 'Корзина'
               : events > 12 ? 'Просмотр' : 'Регистрация';

  const nps      = Math.round(clamp(4 + session / 11 + purchases * 1.7 + (random() - 0.5) * 4, 0, 10));
  const rating   = round(clamp(2.8 + nps * 0.2 + (random() - 0.5) * 0.6, 1, 5));
  const support  = clamp(Math.round((5 - rating) + (random() < 0.12 ? 1 : 0)), 0, 5);
  const discount = purchases ? pick([0, 0, 5, 10, 15, 20]) : 0;
  const loyalty  = Math.round(clamp(purchases * 120 + session * 4 + (userType === 'Вернувшийся' ? 80 : 0) + (random() - 0.5) * 40, 0, 800));
  const activeDays = Math.round(clamp(session / 5 + (userType === 'Вернувшийся' ? 6 : 1) + (random() - 0.5) * 3, 1, 30));
  const conversion = round(clamp(purchases * 55 + addCart * 9 + session * 0.4 + srcBoost + (random() - 0.5) * 15, 0, 100));

  return [
    date, `u${String(i).padStart(4, '0')}`, gender, ageGroup, city, country,
    device, os, browser, source, campaign, userType, subscription, stage, children, dayPart,
    session, pages, events, addCart, purchases, revenue, avgCheck, nps, rating,
    support, discount, loyalty, activeDays, conversion
  ];
}

// ─── CSV-экранирование ───────────────────────────────────────
const quote = v => {
  const s = String(v);
  return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};

// ─── Запись файла ────────────────────────────────────────────
const rows = [HEADERS];
for (let i = 1; i <= COUNT; i++) rows.push(generateRow(i));

const csv = rows.map(r => r.map(quote).join(',')).join('\n') + '\n';
fs.writeFileSync(OUT_PATH, csv, 'utf8');

console.log(`✓ Создано строк: ${COUNT}; колонок: ${HEADERS.length}`);
console.log(`  Файл: ${OUT_PATH}`);
