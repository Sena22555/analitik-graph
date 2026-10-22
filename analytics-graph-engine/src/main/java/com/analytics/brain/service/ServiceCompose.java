package com.analytics.brain.service;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.exception.ExceptionAnalitikData;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.analytics.brain.service.ServiceSql.quote;

/**
 * «Собрать из графа»: колонки из разных таблиц → один отчёт. Путь соединения берётся из графа (общие ключи),
 * а SQL строится так, чтобы суммы не «раздувались» при соединении один-ко-многим.
 * <p>
 * Ловушки, которые обходятся автоматически:
 * <ul>
 *     <li><b>fan trap</b> — клиент с пятью платежами не должен считаться пять раз: факты сначала сворачиваются
 *     до одной строки на клиента, потом агрегируются по группам;</li>
 *     <li><b>chasm trap</b> — две таблицы фактов через общую сущность (тариф платежа × обращения в поддержку):
 *     клиент относится к группе, если у него есть хотя бы одна строка с этим значением, его факты учитываются
 *     один раз в каждой своей группе — и это явно объясняется в ответе.</li>
 * </ul>
 * Семантический слой (описания связей и кардинальности, как в Looker / Holistics) не нужен: ключи и кардинальность
 * граф находит сам.
 */
@Service
public class ServiceCompose {

    private final ServiceBrainGraph brain;
    private final ServiceSql sql;

    public ServiceCompose(ServiceBrainGraph brain, ServiceSql sql) {
        this.brain = brain;
        this.sql = sql;
    }

    public static final class Dim {
        public String table;
        public String column;
        /** Для дат: day | week | month | quarter | year. */
        public String grain;
    }

    public static final class Measure {
        public String table;
        public String column;
        /** rows | entities | share | sum | avg | min | max | distinct. */
        public String agg;
    }

    public static final class Request {
        public List<Dim> dims = new ArrayList<>();
        public List<Measure> measures = new ArrayList<>();
        public Integer limit;
    }

    /** Таблица запроса: её SQL-имя и ключ, которым она связана с сущностью. */
    private static final class Node {
        FinalDataset ds;
        String sqlName;
        String key;
    }

    public Map<String, Object> compose(Request r) throws SQLException {
        if (r.measures == null || r.measures.isEmpty()) throw new ExceptionAnalitikData("Добавьте хотя бы один показатель");
        FinalWorkspaceLinks.Resolved w = FinalWorkspaceLinks.resolve(brain.workspace(null));
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<String> explain = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // ── таблицы и путь: все таблицы должны быть сущностью или связаны с ней ──
        Set<String> used = new LinkedHashSet<>();
        for (Dim d : r.dims) used.add(d.table);
        for (Measure m : r.measures) used.add(m.table);
        if (used.size() > 1 && w.entity == null) {
            throw new ExceptionAnalitikData("Таблицы не связаны общим ключом — соединять их нечем");
        }
        FinalDataset entity = w.entity;
        for (String id : used) {
            FinalDataset ds = brain.store.get(id);
            if (ds == null) throw new ExceptionAnalitikData("Таблица не найдена: " + id);
            Node n = new Node();
            n.ds = ds;
            n.sqlName = sql.sqlName(id);
            if (entity != null && ds == entity) n.key = w.entityKey;
            else if (entity != null && w.linkOf(ds) != null) n.key = w.linkOf(ds).otherKey;
            else if (used.size() > 1) throw new ExceptionAnalitikData("Таблица «" + ds.name + "» не связана с остальными общим ключом");
            nodes.put(id, n);
            column(ds, r.dims, r.measures);
        }

        // одна таблица — обычная группировка, ловушек нет («доля клиентов» требует сущности — её считаем через граф)
        if (used.size() == 1 && r.measures.stream().noneMatch(m -> "share".equals(m.agg))) {
            return single(r, nodes.values().iterator().next(), explain);
        }
        if (entity == null) throw new ExceptionAnalitikData("Для доли клиентов нужна таблица клиентов, связанная по ключу");
        Node e = nodes.get(entity.id);
        if (e == null) {
            e = new Node();
            e.ds = entity;
            e.sqlName = sql.sqlName(entity.id);
            e.key = w.entityKey;
            nodes.put(entity.id, e);
        }

        // ── уровень детализации: разрезы из сущности и не более чем одной таблицы фактов ──
        Set<String> dimFacts = new LinkedHashSet<>();
        for (Dim d : r.dims) {
            if (!d.table.equals(entity.id)) dimFacts.add(d.table);
        }
        if (dimFacts.size() > 1) {
            throw new ExceptionAnalitikData("Разрезы из двух разных таблиц фактов («" + names(dimFacts) + "») не сводятся к одному уровню: "
                    + "непонятно, к какой строке относить значение. Возьмите разрезы из «" + entity.name + "» или из одной таблицы.");
        }
        Node f = dimFacts.isEmpty() ? null : nodes.get(dimFacts.iterator().next());
        String join = f == null ? "" : quote(f.sqlName) + " f JOIN " + quote(e.sqlName) + " e ON e." + quote(e.key) + " = f." + quote(f.key);

        // ── разрезы ──
        List<String> dimExpr = new ArrayList<>();
        List<String> dimAlias = new ArrayList<>();
        List<String> dimLabel = new ArrayList<>();
        for (int i = 0; i < r.dims.size(); i++) {
            Dim d = r.dims.get(i);
            dimExpr.add(dimExpression((d.table.equals(entity.id) ? "e." : "f.") + quote(d.column), d.grain));
            dimAlias.add("d" + i);
            dimLabel.add(d.column + (d.grain != null && !d.grain.isBlank() ? " (" + grainRu(d.grain) + ")" : ""));
        }
        if (r.dims.isEmpty()) {
            dimExpr.add("'Итого'");
            dimAlias.add("d0");
            dimLabel.add("Группа");
        }
        List<String> dimSelect = new ArrayList<>();
        for (int i = 0; i < dimExpr.size(); i++) dimSelect.add(dimExpr.get(i) + " AS " + dimAlias.get(i));
        String groupBy = String.join(", ", prefixed("d.", dimAlias));

        // ── d: какой клиент в какую группу попадает (одна строка на клиента и группу) ──
        List<String> ctes = new ArrayList<>();
        ctes.add("d AS (\n  SELECT DISTINCT " + String.join(", ", dimSelect) + ", e." + quote(e.key) + " AS __k\n  FROM "
                + (f == null ? quote(e.sqlName) + " e" : join) + "\n)");
        if (f != null) explain.add("Клиент попадает в группу, если у него есть хотя бы одна строка «" + f.ds.name + "» с этим значением.");

        // ── показатели ──
        Map<String, String> folded = new LinkedHashMap<>();
        List<String> measureCtes = new ArrayList<>();
        List<String> measureLabels = new ArrayList<>();
        boolean usedFold = false;
        for (int i = 0; i < r.measures.size(); i++) {
            Measure m = r.measures.get(i);
            Node t = nodes.get(m.table);
            String label = measureLabel(t.ds, m);
            measureLabels.add(label);
            String col = m.column == null ? null : quote(m.column);
            String body;
            if (t.ds == entity) {
                body = "SELECT " + groupBy + ", " + entityAgg(m, col) + " AS v\n  FROM d JOIN " + quote(e.sqlName) + " e ON e." + quote(e.key) + " = d.__k\n  GROUP BY ALL";
            } else if (f != null && t == f) {
                // показатель той же таблицы, из которой разрез: по её строкам — раздувания нет
                body = "SELECT " + String.join(", ", dimSelect) + ", " + rowAgg(m, "f." + (col == null ? "*" : col), "f." + quote(f.key))
                        + " AS v\n  FROM " + join + "\n  GROUP BY ALL";
            } else if ("distinct".equals(m.agg)) {
                body = "SELECT " + groupBy + ", COUNT(DISTINCT t." + col + ") AS v\n  FROM d JOIN " + quote(t.sqlName) + " t ON t." + quote(t.key) + " = d.__k\n  GROUP BY ALL";
            } else {
                // факты сворачиваются до одной строки на клиента — клиент с пятью платежами не считается пять раз
                String name = "fold_" + t.ds.id + (col == null ? "" : "_" + Integer.toHexString(m.column.hashCode()));
                folded.putIfAbsent(name, name + " AS (\n  SELECT " + quote(t.key) + " AS __k, COUNT(*) AS n"
                        + (col == null ? "" : ", SUM(" + col + ") AS s, COUNT(" + col + ") AS c, MIN(" + col + ") AS mn, MAX(" + col + ") AS mx")
                        + "\n  FROM " + quote(t.sqlName) + "\n  GROUP BY 1\n)");
                body = "SELECT " + groupBy + ", " + factAgg(m) + " AS v\n  FROM d LEFT JOIN " + name + " a ON a.__k = d.__k\n  GROUP BY ALL";
                usedFold = true;
                if (f != null) warnings.add("«" + label + "»: клиент с несколькими значениями из «" + f.ds.name + "» учтён в каждой своей группе — "
                        + "сумма по группам может быть больше общего итога. Это честный ответ на вопрос «у клиентов из группы…».");
            }
            measureCtes.add("m" + i + " AS (\n  " + body + "\n)");
        }
        if (usedFold) explain.add("Таблицы фактов сначала свёрнуты до одной строки на клиента (ключ «" + w.entityKey
                + "»), потом агрегированы по группам — суммы не раздуваются при соединении один-ко-многим.");
        ctes.addAll(folded.values());
        ctes.addAll(measureCtes);

        // ── итог: все группы и показатели рядом ──
        List<String> outCols = new ArrayList<>();
        for (int i = 0; i < dimAlias.size(); i++) outCols.add("g." + dimAlias.get(i) + " AS " + quote(dimLabel.get(i)));
        for (int i = 0; i < measureLabels.size(); i++) outCols.add("m" + i + ".v AS " + quote(measureLabels.get(i)));
        StringBuilder q = new StringBuilder("WITH ").append(String.join(",\n", ctes))
                .append("\nSELECT ").append(String.join(", ", outCols))
                .append("\nFROM (SELECT DISTINCT ").append(groupBy).append(" FROM d) g");
        for (int i = 0; i < measureLabels.size(); i++) {
            List<String> on = new ArrayList<>();
            for (String a : dimAlias) on.add("m" + i + "." + a + " IS NOT DISTINCT FROM g." + a);
            q.append("\nLEFT JOIN m").append(i).append(" ON ").append(String.join(" AND ", on));
        }
        q.append("\nORDER BY ").append(orderBy(r.dims, dimAlias.size()));

        Map<String, Object> out = new LinkedHashMap<>(sql.query(q.toString(), r.limit == null ? 1000 : r.limit));
        out.put("sql", q.toString());
        out.put("explain", explain);
        out.put("warnings", new ArrayList<>(new LinkedHashSet<>(warnings)));
        out.put("path", path(nodes, entity, w));
        return out;
    }

    /** Отчёт по одной таблице — обычная группировка. */
    private Map<String, Object> single(Request r, Node t, List<String> explain) throws SQLException {
        List<String> sel = new ArrayList<>();
        for (Dim d : r.dims) sel.add(dimExpression(quote(d.column), d.grain) + " AS " + quote(d.column + (d.grain != null && !d.grain.isBlank() ? " (" + grainRu(d.grain) + ")" : "")));
        for (Measure m : r.measures) sel.add(rowAgg(m, m.column == null ? "*" : quote(m.column), t.key == null ? "*" : quote(t.key)) + " AS " + quote(measureLabel(t.ds, m)));
        String q = "SELECT " + String.join(", ", sel) + "\nFROM " + quote(t.sqlName)
                + (r.dims.isEmpty() ? "" : "\nGROUP BY ALL\nORDER BY " + orderBy(r.dims, r.dims.size()));
        Map<String, Object> out = new LinkedHashMap<>(sql.query(q, r.limit == null ? 1000 : r.limit));
        out.put("sql", q);
        explain.add("Все колонки из одной таблицы «" + t.ds.name + "» — обычная группировка.");
        out.put("explain", explain);
        out.put("warnings", List.of());
        out.put("path", List.of(Map.of("table", t.ds.name, "id", t.ds.id)));
        return out;
    }

    /** Разрез по времени — по порядку дат; иначе — по первому показателю, крупные сверху. */
    private static String orderBy(List<Dim> dims, int dimCount) {
        boolean time = dims.stream().anyMatch(d -> d.grain != null && !d.grain.isBlank());
        return time ? "1" : (dimCount + 1) + " DESC NULLS LAST";
    }

    private static void column(FinalDataset ds, List<Dim> dims, List<Measure> measures) {
        for (Dim d : dims) {
            if (d.table.equals(ds.id) && !ds.header.contains(d.column)) throw new ExceptionAnalitikData("В «" + ds.name + "» нет колонки «" + d.column + "»");
        }
        for (Measure m : measures) {
            if (!m.table.equals(ds.id) || m.column == null) continue;
            FinalColumn c = ds.col(m.column);
            if (c == null) throw new ExceptionAnalitikData("В «" + ds.name + "» нет колонки «" + m.column + "»");
            boolean numeric = c.type == EnumColumnType.NUMERIC;
            if (!numeric && !"distinct".equals(m.agg)) throw new ExceptionAnalitikData("«" + m.column + "» — не число: для неё доступно только «уникальных»");
        }
    }

    private static String dimExpression(String col, String grain) {
        if (grain == null || grain.isBlank()) return col;
        return "CAST(date_trunc('" + grain.replaceAll("[^a-z]", "") + "', " + col + ") AS DATE)";
    }

    private static String entityAgg(Measure m, String col) {
        switch (m.agg) {
            case "rows": case "entities": return "COUNT(DISTINCT d.__k)";
            case "sum": return "SUM(e." + col + ")";
            case "avg": return "AVG(e." + col + ")";
            case "min": return "MIN(e." + col + ")";
            case "max": return "MAX(e." + col + ")";
            case "distinct": return "COUNT(DISTINCT e." + col + ")";
            default: throw new ExceptionAnalitikData("Неизвестная агрегация: " + m.agg);
        }
    }

    /** Агрегация по строкам одной таблицы. */
    private static String rowAgg(Measure m, String col, String key) {
        switch (m.agg) {
            case "rows": return "COUNT(*)";
            case "entities": case "share": return "COUNT(DISTINCT " + key + ")";
            case "sum": return "SUM(" + col + ")";
            case "avg": return "AVG(" + col + ")";
            case "min": return "MIN(" + col + ")";
            case "max": return "MAX(" + col + ")";
            case "distinct": return "COUNT(DISTINCT " + col + ")";
            default: throw new ExceptionAnalitikData("Неизвестная агрегация: " + m.agg);
        }
    }

    /** Агрегация по группам из свёрнутых до клиента фактов (a: __k, n, s, c, mn, mx). */
    private static String factAgg(Measure m) {
        switch (m.agg) {
            case "rows": return "COALESCE(SUM(a.n), 0)";
            case "entities": return "COUNT(DISTINCT CASE WHEN a.n > 0 THEN d.__k END)";
            case "share": return "ROUND(100.0 * AVG(CASE WHEN a.n > 0 THEN 1 ELSE 0 END), 1)";
            case "sum": return "SUM(a.s)";
            case "avg": return "SUM(a.s) / NULLIF(SUM(a.c), 0)";
            case "min": return "MIN(a.mn)";
            case "max": return "MAX(a.mx)";
            default: throw new ExceptionAnalitikData("Неизвестная агрегация: " + m.agg);
        }
    }

    static String measureLabel(FinalDataset ds, Measure m) {
        switch (m.agg) {
            case "rows": return ds.name + ": записей";
            case "entities": return ds.name + ": клиентов";
            case "share": return ds.name + ": доля клиентов, %";
            case "sum": return m.column + ", сумма";
            case "avg": return m.column + ", среднее";
            case "min": return m.column + ", мин";
            case "max": return m.column + ", макс";
            case "distinct": return m.column + ": уникальных";
            default: return m.agg;
        }
    }

    private static String grainRu(String g) {
        switch (g) {
            case "day": return "день";
            case "week": return "неделя";
            case "month": return "месяц";
            case "quarter": return "квартал";
            case "year": return "год";
            default: return g;
        }
    }

    private static List<String> prefixed(String p, List<String> xs) {
        List<String> out = new ArrayList<>();
        for (String x : xs) out.add(p + x);
        return out;
    }

    private String names(Set<String> ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) out.add(brain.store.get(id).name);
        return String.join("», «", out);
    }

    private static List<Map<String, Object>> path(Map<String, Node> nodes, FinalDataset entity, FinalWorkspaceLinks.Resolved w) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> center = new LinkedHashMap<>();
        center.put("id", entity.id);
        center.put("table", entity.name);
        center.put("key", w.entityKey);
        center.put("center", true);
        out.add(center);
        for (Node n : nodes.values()) {
            if (n.ds == entity) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", n.ds.id);
            x.put("table", n.ds.name);
            x.put("key", n.key);
            out.add(x);
        }
        return out;
    }
}
