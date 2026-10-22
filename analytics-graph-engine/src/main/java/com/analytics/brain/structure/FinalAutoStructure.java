package com.analytics.brain.structure;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataLoader;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalDatasetRoles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * «Сортировка» сырых данных: плоский лог → чистые связанные таблицы.
 * <ol>
 *     <li><b>Чистка</b>: пробелы, токены пропусков, числа в одном формате («1 290,00 ₽» → 1290),
 *     даты в ISO, слияние вариантов написания категорий («москва », «МОСКВА» → «Москва»), дубликаты строк.</li>
 *     <li><b>Функциональные зависимости</b> K → C: внутри каждой группы значения ключа K колонка C почти всегда одна и та же
 *     (≥ 99,5 % строк). Оцениваются только группы из ≥ 2 строк — иначе любой ключ «определял» бы всё.</li>
 *     <li><b>Иерархия сущностей</b>: колонка уходит в таблицу ключа с наименьшим числом значений, который её определяет
 *     (город — свойство пользователя, а не сессии). Ключ сессии, определяющий пользователя, становится вложенной сущностью.</li>
 *     <li><b>Факты</b>: всё, что меняется от строки к строке, остаётся в таблице событий / фактов вместе с ключами.</li>
 * </ol>
 * Результат — план с объяснением каждого шага; применение плана создаёт таблицы, которые помнят источник
 * и пересчитываются при его изменении.
 */
public final class FinalAutoStructure {

    static final double FD_THRESHOLD = 0.995;
    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern KEY_NAME = Pattern.compile("(?iu)(^|[\\s_.-])(id|uuid|guid|key|код|номер)($|[\\s_.-])|^id|идентификатор");

    private FinalAutoStructure() {
    }

    /** Одна операция чистки — для журнала («что сделано и почему»). */
    public static final class Op {
        public String kind;
        public String column;
        public int cells;
        public List<String> examples = new ArrayList<>();
        public String text;
    }

    /** Найденная зависимость: ключ определяет колонки. */
    public static final class Dependency {
        public String key;
        public int keyValues;
        public List<String> columns = new ArrayList<>();
        public double strength;
    }

    /** Таблица, которая получится. */
    public static final class Table {
        public String part;
        public String name;
        public String role;
        public String key;
        public String parentKey;
        public List<String> columns = new ArrayList<>();
        public int rows;
    }

    public static final class Plan {
        public String sourceId;
        public String sourceName;
        public int sourceVersion;
        public int rows;
        public int rowsAfter;
        public int duplicates;
        public List<Op> cleaning = new ArrayList<>();
        public List<Dependency> dependencies = new ArrayList<>();
        public List<Table> tables = new ArrayList<>();
        /** Очищенные данные (не сериализуются: нужны только для сборки таблиц). */
        transient List<String> header;
        transient List<String[]> cleaned;

        /** Есть смысл предлагать: таблицу можно разложить на несколько. */
        public boolean splittable() {
            return tables.size() > 1;
        }

        public int fixes() {
            int n = duplicates;
            for (Op o : cleaning) n += o.cells;
            return n;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sourceId", sourceId);
            m.put("sourceName", sourceName);
            m.put("rows", rows);
            m.put("rowsAfter", rowsAfter);
            m.put("duplicates", duplicates);
            m.put("fixes", fixes());
            m.put("splittable", splittable());
            List<Map<String, Object>> ops = new ArrayList<>();
            for (Op o : cleaning) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("kind", o.kind);
                x.put("column", o.column);
                x.put("cells", o.cells);
                x.put("examples", o.examples);
                x.put("text", o.text);
                ops.add(x);
            }
            m.put("cleaning", ops);
            List<Map<String, Object>> deps = new ArrayList<>();
            for (Dependency d : dependencies) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("key", d.key);
                x.put("keyValues", d.keyValues);
                x.put("columns", d.columns);
                x.put("strength", d.strength);
                deps.add(x);
            }
            m.put("dependencies", deps);
            List<Map<String, Object>> ts = new ArrayList<>();
            for (Table t : tables) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("part", t.part);
                x.put("name", t.name);
                x.put("role", t.role);
                x.put("key", t.key);
                x.put("parentKey", t.parentKey);
                x.put("columns", t.columns);
                x.put("rows", t.rows);
                ts.add(x);
            }
            m.put("tables", ts);
            return m;
        }
    }

    /* ══════════════════════ план ══════════════════════ */

    public static Plan plan(FinalDataset raw) {
        Plan p = new Plan();
        p.sourceId = raw.id;
        p.sourceName = raw.name;
        p.sourceVersion = raw.version;
        p.rows = raw.rows;
        p.header = new ArrayList<>(raw.header);

        int cols = raw.header.size();
        String[][] cells = new String[raw.rows][cols];
        int[] trimmed = new int[cols];
        int[] missing = new int[cols];
        for (int i = 0; i < raw.rows; i++) {
            String[] r = raw.records.get(i);
            for (int j = 0; j < cols; j++) {
                String v = j < r.length ? r[j] : null;
                if (v == null) continue;
                String t = v.replace(' ', ' ').trim().replaceAll("\\s{2,}", " ");
                if (FinalDataLoader.isMissing(t)) {
                    if (!t.isEmpty()) missing[j]++;
                    continue;
                }
                if (!t.equals(v)) trimmed[j]++;
                cells[i][j] = t;
            }
        }
        for (int j = 0; j < cols; j++) {
            if (missing[j] > 0) p.cleaning.add(op("missing", raw.header.get(j), missing[j], List.of(),
                    "значения вроде «—», «n/a», «null» приведены к пустым"));
            if (trimmed[j] > 0) p.cleaning.add(op("spaces", raw.header.get(j), trimmed[j], List.of(),
                    "лишние пробелы по краям и внутри"));
        }

        for (int j = 0; j < cols; j++) {
            FinalColumn c = raw.col(raw.header.get(j));
            EnumColumnType type = c == null || c.type == null ? EnumColumnType.TEXT : c.type;
            if (type == EnumColumnType.NUMERIC) normalizeNumbers(p, cells, j);
            else if (type == EnumColumnType.DATE) normalizeDates(p, cells, j);
            else if (type == EnumColumnType.CATEGORICAL || type == EnumColumnType.TEXT) mergeVariants(p, cells, j);
        }

        // дубликаты строк — после чистки, чтобы «Москва» и «москва » считались одним и тем же
        List<String[]> rows = new ArrayList<>(raw.rows);
        Set<String> seen = new HashSet<>();
        for (String[] r : cells) {
            if (seen.add(String.join("\u0001", nulls(r)))) rows.add(r);
        }
        p.duplicates = raw.rows - rows.size();
        p.cleaned = rows;
        p.rowsAfter = rows.size();

        structure(p, raw);
        return p;
    }

    private static Op op(String kind, String column, int cells, List<String> examples, String text) {
        Op o = new Op();
        o.kind = kind;
        o.column = column;
        o.cells = cells;
        o.examples = new ArrayList<>(examples);
        o.text = text;
        return o;
    }

    private static String[] nulls(String[] r) {
        String[] out = new String[r.length];
        for (int j = 0; j < r.length; j++) out[j] = r[j] == null ? "\u0000" : r[j];
        return out;
    }

    private static void normalizeNumbers(Plan p, String[][] cells, int j) {
        int changed = 0;
        List<String> ex = new ArrayList<>();
        for (String[] r : cells) {
            if (r[j] == null) continue;
            double d = FinalDataLoader.parseNum(r[j]);
            if (Double.isNaN(d)) continue;
            String canon = d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
            if (!canon.equals(r[j])) {
                if (ex.size() < 3 && !ex.contains(r[j] + " → " + canon)) ex.add(r[j] + " → " + canon);
                r[j] = canon;
                changed++;
            }
        }
        if (changed > 0) p.cleaning.add(op("numbers", p.header.get(j), changed, ex, "числа в одном формате: без пробелов, валюты и с точкой"));
    }

    private static void normalizeDates(Plan p, String[][] cells, int j) {
        int changed = 0;
        List<String> ex = new ArrayList<>();
        for (String[] r : cells) {
            if (r[j] == null) continue;
            String canon = isoDateTime(r[j]);
            if (canon != null && !canon.equals(r[j])) {
                if (ex.size() < 3) ex.add(r[j] + " → " + canon);
                r[j] = canon;
                changed++;
            }
        }
        if (changed > 0) p.cleaning.add(op("dates", p.header.get(j), changed, ex, "даты и время в одном формате (ISO)"));
    }

    /** Дата (и время, если есть) в ISO: «03.07.2026 14:22» → «2026-07-03 14:22:00». */
    static String isoDateTime(String s) {
        LocalDate d = FinalDataLoader.parseDate(s);
        if (d == null) return null;
        int cut = s.indexOf(' ') >= 0 ? s.indexOf(' ') : s.indexOf('T');
        if (cut < 0) return d.toString();
        Matcher t = TIME.matcher(s.substring(cut));
        if (!t.find()) return d.toString();
        int h = Integer.parseInt(t.group(1));
        int m = Integer.parseInt(t.group(2));
        int sec = t.group(3) == null ? 0 : Integer.parseInt(t.group(3));
        return String.format(Locale.ROOT, "%s %02d:%02d:%02d", d, h, m, sec);
    }

    /** Варианты написания одной категории сливаются в самый частый: регистр, «ё», пунктуация, пробелы. */
    private static void mergeVariants(Plan p, String[][] cells, int j) {
        Map<String, Integer> freq = new HashMap<>();
        for (String[] r : cells) {
            if (r[j] != null) freq.merge(r[j], 1, Integer::sum);
        }
        if (freq.size() < 2 || freq.size() > 400) return;
        Map<String, List<String>> groups = new HashMap<>();
        for (String v : freq.keySet()) groups.computeIfAbsent(normKey(v), k -> new ArrayList<>()).add(v);
        Map<String, String> canon = new HashMap<>();
        List<String> ex = new ArrayList<>();
        for (List<String> g : groups.values()) {
            if (g.size() < 2) continue;
            g.sort(Comparator.comparingInt((String v) -> -freq.get(v)).thenComparing(v -> Character.isUpperCase(v.charAt(0)) ? 0 : 1));
            String best = g.get(0);
            for (String v : g.subList(1, g.size())) {
                canon.put(v, best);
                if (ex.size() < 4) ex.add("«" + v + "» → «" + best + "»");
            }
        }
        if (canon.isEmpty()) return;
        int changed = 0;
        for (String[] r : cells) {
            String to = r[j] == null ? null : canon.get(r[j]);
            if (to != null) {
                r[j] = to;
                changed++;
            }
        }
        p.cleaning.add(op("variants", p.header.get(j), changed, ex, "варианты написания одного значения объединены"));
    }

    static String normKey(String v) {
        return NON_WORD.matcher(v.toLowerCase(Locale.ROOT).replace('ё', 'е')).replaceAll(" ").trim();
    }

    /* ══════════════════════ зависимости и таблицы ══════════════════════ */

    private static void structure(Plan p, FinalDataset raw) {
        List<String[]> rows = p.cleaned;
        int cols = p.header.size();
        int n = rows.size();

        // кандидаты в ключи: идентификаторы с повторами (у строки лога ключ сущности повторяется)
        List<Integer> keys = new ArrayList<>();
        Map<Integer, Integer> distinct = new HashMap<>();
        for (int j = 0; j < cols; j++) {
            FinalColumn c = raw.col(p.header.get(j));
            boolean idLike = c != null && c.type == EnumColumnType.ID || KEY_NAME.matcher(p.header.get(j)).find();
            if (!idLike) continue;
            Set<String> values = new HashSet<>();
            int filled = 0;
            for (String[] r : rows) {
                if (r[j] != null) {
                    values.add(r[j]);
                    filled++;
                }
            }
            distinct.put(j, values.size());
            if (values.size() >= 2 && values.size() < filled * 0.9 && filled >= n * 0.9) keys.add(j);
        }
        keys.sort(Comparator.comparingInt(distinct::get));

        // K → C для каждой пары
        Map<Integer, Map<Integer, Double>> fd = new HashMap<>();
        for (int k : keys) {
            Map<Integer, Double> deps = new HashMap<>();
            for (int c = 0; c < cols; c++) {
                if (c == k) continue;
                double s = dependency(rows, k, c);
                if (s >= FD_THRESHOLD) deps.put(c, s);
            }
            fd.put(k, deps);
        }

        // каждая не-ключевая колонка — в сущность с наименьшим числом значений ключа, которая её определяет
        Map<Integer, List<Integer>> assigned = new LinkedHashMap<>();
        Set<Integer> moved = new HashSet<>();
        for (int c = 0; c < cols; c++) {
            if (keys.contains(c) || constant(rows, c)) continue;
            for (int k : keys) {
                if (fd.get(k).containsKey(c)) {
                    assigned.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
                    moved.add(c);
                    break;
                }
            }
        }

        for (int k : keys) {
            List<Integer> attrs = assigned.getOrDefault(k, List.of());
            if (attrs.isEmpty()) continue;
            Dependency d = new Dependency();
            d.key = p.header.get(k);
            d.keyValues = distinct.get(k);
            double min = 1;
            for (int c : attrs) {
                d.columns.add(p.header.get(c));
                min = Math.min(min, fd.get(k).get(c));
            }
            d.strength = min;
            p.dependencies.add(d);

            Table t = new Table();
            t.part = "entity:" + p.header.get(k);
            t.name = entityName(p.header.get(k));
            t.role = "entity";
            t.key = p.header.get(k);
            // родительская сущность: ключ с меньшим числом значений, который определяется этим (сессия → пользователь)
            Integer parent = null;
            for (int other : keys) {
                if (other != k && distinct.get(other) < distinct.get(k) && fd.get(k).containsKey(other)
                        && (parent == null || distinct.get(other) > distinct.get(parent))) parent = other;
            }
            t.columns.add(t.key);
            if (parent != null) {
                t.parentKey = p.header.get(parent);
                t.columns.add(t.parentKey);
            }
            for (int c : attrs) t.columns.add(p.header.get(c));
            t.rows = distinct.get(k);
            p.tables.add(t);
        }

        Table fact = new Table();
        fact.part = p.tables.isEmpty() ? "clean" : "fact";
        for (int c = 0; c < cols; c++) {
            if (!moved.contains(c)) fact.columns.add(p.header.get(c));
        }
        fact.rows = n;
        boolean events = hasStepAndTime(raw, fact.columns);
        fact.role = events ? "events" : "facts";
        fact.name = p.tables.isEmpty() ? raw.name + " (очищено)" : events ? "События" : raw.name + " — факты";
        p.tables.add(fact);
    }

    /** Доля строк, где значение C совпадает с самым частым в своей группе K. Только группы из ≥ 2 строк. */
    static double dependency(List<String[]> rows, int k, int c) {
        Map<String, Map<String, Integer>> groups = new HashMap<>();
        for (String[] r : rows) {
            if (r[k] == null || r[c] == null) continue;
            groups.computeIfAbsent(r[k], x -> new HashMap<>()).merge(r[c], 1, Integer::sum);
        }
        long agree = 0;
        long total = 0;
        int evidenceGroups = 0;
        for (Map<String, Integer> g : groups.values()) {
            int size = 0;
            int best = 0;
            for (int v : g.values()) {
                size += v;
                best = Math.max(best, v);
            }
            if (size < 2) continue;
            evidenceGroups++;
            agree += best;
            total += size;
        }
        if (evidenceGroups < 10 || total < 50) return 0;
        return (double) agree / total;
    }

    private static boolean constant(List<String[]> rows, int c) {
        String first = null;
        for (String[] r : rows) {
            if (r[c] == null) continue;
            if (first == null) first = r[c];
            else if (!first.equals(r[c])) return false;
        }
        return true;
    }

    private static boolean hasStepAndTime(FinalDataset raw, List<String> columns) {
        boolean step = false;
        boolean time = false;
        for (String name : columns) {
            FinalColumn c = raw.col(name);
            if (c == null) continue;
            if (c.type == EnumColumnType.DATE) time = true;
            if (c.type == EnumColumnType.CATEGORICAL && Pattern.compile("(?iu)страниц|page|экран|screen|событи|event|шаг|step|url|path|action|действи|раздел")
                    .matcher(name).find()) step = true;
        }
        return step && time;
    }

    static String entityName(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        String[][] names = {
                {"пользоват|user", "Пользователи"}, {"клиент|customer|client", "Клиенты"}, {"сесси|session|визит|visit", "Сессии"},
                {"заказ|order", "Заказы"}, {"товар|продукт|product|sku|item", "Товары"}, {"магазин|store|shop", "Магазины"},
                {"сотрудник|employee|менеджер|manager", "Сотрудники"}, {"компан|company|account|аккаунт", "Компании"},
                {"устройств|device", "Устройства"}, {"кампан|campaign", "Кампании"}, {"платеж|платёж|payment", "Платежи"}};
        for (String[] x : names) {
            if (Pattern.compile(x[0]).matcher(k).find()) return x[1];
        }
        return "Справочник «" + key + "»";
    }

    /* ══════════════════════ сборка таблиц ══════════════════════ */

    /** Записи для части плана: сущность — одна строка на ключ (самое частое значение), факты — очищенные строки. */
    public static List<String[]> records(Plan p, Table t) {
        List<Integer> idx = new ArrayList<>();
        for (String c : t.columns) idx.add(p.header.indexOf(c));
        List<String[]> out = new ArrayList<>();
        if (!"entity".equals(t.role)) {
            for (String[] r : p.cleaned) {
                String[] x = new String[idx.size()];
                for (int i = 0; i < x.length; i++) x[i] = r[idx.get(i)] == null ? "" : r[idx.get(i)];
                out.add(x);
            }
            return out;
        }
        int key = idx.get(0);
        Map<String, List<Map<String, Integer>>> groups = new LinkedHashMap<>();
        for (String[] r : p.cleaned) {
            if (r[key] == null) continue;
            List<Map<String, Integer>> g = groups.computeIfAbsent(r[key], x -> {
                List<Map<String, Integer>> l = new ArrayList<>();
                for (int i = 0; i < idx.size(); i++) l.add(new HashMap<>());
                return l;
            });
            for (int i = 1; i < idx.size(); i++) {
                String v = r[idx.get(i)];
                if (v != null) g.get(i).merge(v, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, List<Map<String, Integer>>> e : groups.entrySet()) {
            String[] x = new String[idx.size()];
            x[0] = e.getKey();
            for (int i = 1; i < x.length; i++) {
                String best = "";
                int count = 0;
                for (Map.Entry<String, Integer> v : e.getValue().get(i).entrySet()) {
                    if (v.getValue() > count) {
                        best = v.getKey();
                        count = v.getValue();
                    }
                }
                x[i] = best;
            }
            out.add(x);
        }
        return out;
    }

    /** Название роли — для журнала. */
    public static String roleRu(String role) {
        return FinalDatasetRoles.roleRu(role);
    }
}
