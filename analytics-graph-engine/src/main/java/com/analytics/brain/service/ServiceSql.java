package com.analytics.brain.service;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.exception.ExceptionAnalitikData;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL по всем таблицам рабочего пространства на встроенной DuckDB (колоночная аналитическая СУБД, в памяти).
 * <p>
 * Каждая база — таблица с именем базы ({@code "Пользователи"}); числа — DOUBLE, даты — TIMESTAMP, остальное — текст.
 * Таблицы досинхронизируются перед запросом, если версия базы изменилась. Разрешены только читающие запросы,
 * доступ DuckDB к файлам и сети выключен — запрос не может прочитать ничего, кроме загруженных таблиц.
 */
@Service
public class ServiceSql {

    public static final int MAX_ROWS = 5000;
    private static final Pattern READ_ONLY = Pattern.compile(
            "(?is)^\\s*(--[^\\n]*\\n\\s*)*(select|with|from|values|describe|summarize|show|pivot|unpivot|explain)\\b.*");
    private static final Pattern ISO = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?$");

    private final ServiceBrainGraph brain;
    private Connection db;
    private final Map<String, Integer> loadedVersion = new HashMap<>();
    private final Map<String, String> tableName = new HashMap<>();

    public ServiceSql(ServiceBrainGraph brain) {
        this.brain = brain;
    }

    private Connection db() throws SQLException {
        if (db == null) {
            db = DriverManager.getConnection("jdbc:duckdb:");
            try (Statement st = db.createStatement()) {
                st.execute("SET enable_external_access = false");
                st.execute("SET lock_configuration = true");
            }
        }
        return db;
    }

    @PreDestroy
    public synchronized void close() throws SQLException {
        if (db != null) db.close();
    }

    public static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /** Синхронизировать таблицы DuckDB с базами рабочего пространства. */
    private void sync() throws SQLException {
        List<FinalDataset> all = brain.allTables();
        Set<String> alive = new HashSet<>();
        Set<String> usedNames = new HashSet<>();
        Map<String, String> wanted = new LinkedHashMap<>();
        for (FinalDataset ds : all) {
            String name = ds.name == null || ds.name.isBlank() ? "table_" + ds.id : ds.name.trim();
            String unique = name;
            for (int k = 2; !usedNames.add(unique.toLowerCase(Locale.ROOT)); k++) unique = name + " (" + k + ")";
            wanted.put(ds.id, unique);
            alive.add(ds.id);
        }
        // удалённые или переименованные базы
        for (String id : new ArrayList<>(tableName.keySet())) {
            if (!alive.contains(id) || !tableName.get(id).equals(wanted.get(id))) {
                exec("DROP TABLE IF EXISTS " + quote(tableName.get(id)));
                tableName.remove(id);
                loadedVersion.remove(id);
            }
        }
        for (FinalDataset ds : all) {
            Integer v = loadedVersion.get(ds.id);
            if (v != null && v == ds.version) continue;
            synchronized (ds) {
                load(ds, wanted.get(ds.id));
            }
            tableName.put(ds.id, wanted.get(ds.id));
            loadedVersion.put(ds.id, ds.version);
        }
    }

    private void exec(String sql) throws SQLException {
        try (Statement st = db().createStatement()) {
            st.execute(sql);
        }
    }

    /** Загрузка через appender в текстовую таблицу и приведение типов одним CREATE TABLE AS. */
    private void load(FinalDataset ds, String name) throws SQLException {
        String stage = "__stage_" + ds.id;
        StringBuilder cols = new StringBuilder();
        for (int j = 0; j < ds.header.size(); j++) cols.append(j == 0 ? "" : ", ").append(quote("c" + j)).append(" VARCHAR");
        exec("DROP TABLE IF EXISTS " + quote(stage));
        exec("CREATE TABLE " + quote(stage) + " (" + cols + ")");

        FinalColumn[] typed = new FinalColumn[ds.header.size()];
        for (int j = 0; j < typed.length; j++) typed[j] = ds.col(ds.header.get(j));

        try (DuckDBAppender ap = ((DuckDBConnection) db()).createAppender(DuckDBConnection.DEFAULT_SCHEMA, stage)) {
            for (int i = 0; i < ds.rows; i++) {
                String[] r = ds.records.get(i);
                ap.beginRow();
                for (int j = 0; j < typed.length; j++) {
                    String raw = j < r.length && r[j] != null && !r[j].isBlank() ? r[j].trim() : null;
                    FinalColumn c = typed[j];
                    String v = raw;
                    if (c != null && c.type == EnumColumnType.NUMERIC) {
                        v = Double.isNaN(c.num[i]) ? null : Double.toString(c.num[i]);
                    } else if (c != null && c.type == EnumColumnType.DATE) {
                        v = raw != null && ISO.matcher(raw).matches() ? raw
                                : Double.isNaN(c.num[i]) ? null : LocalDate.ofEpochDay((long) c.num[i]).toString();
                    }
                    ap.append(v);
                }
                ap.endRow();
            }
        }

        StringBuilder select = new StringBuilder();
        for (int j = 0; j < typed.length; j++) {
            FinalColumn c = typed[j];
            String src = quote("c" + j);
            String expr = c != null && c.type == EnumColumnType.NUMERIC ? "TRY_CAST(" + src + " AS DOUBLE)"
                    : c != null && c.type == EnumColumnType.DATE ? "TRY_CAST(" + src + " AS TIMESTAMP)"
                    : src;
            select.append(j == 0 ? "" : ", ").append(expr).append(" AS ").append(quote(ds.header.get(j)));
        }
        exec("DROP TABLE IF EXISTS " + quote(name));
        exec("CREATE TABLE " + quote(name) + " AS SELECT " + select + " FROM " + quote(stage));
        exec("DROP TABLE " + quote(stage));
    }

    /** Схема для подсказок: таблицы, колонки, типы и примеры запросов. */
    public synchronized Map<String, Object> schema() throws SQLException {
        sync();
        List<Map<String, Object>> tables = new ArrayList<>();
        for (FinalDataset ds : brain.allTables()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("id", ds.id);
            t.put("name", tableName.get(ds.id));
            t.put("rows", ds.rows);
            t.put("role", ds.role);
            List<Map<String, String>> columns = new ArrayList<>();
            for (String h : ds.header) {
                FinalColumn c = ds.col(h);
                Map<String, String> col = new LinkedHashMap<>();
                col.put("name", h);
                col.put("type", c == null || c.type == null ? "TEXT" : c.type.name());
                columns.add(col);
            }
            t.put("columns", columns);
            tables.add(t);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", tables);
        out.put("examples", examples());
        return out;
    }

    /** Примеры под реальные таблицы: просмотр, группировка, JOIN сущности с фактами. */
    private List<Map<String, String>> examples() {
        List<Map<String, String>> out = new ArrayList<>();
        List<FinalDataset> all = brain.workspace(null);
        if (all.isEmpty()) return out;
        FinalWorkspaceLinks.Resolved w = FinalWorkspaceLinks.resolve(all);
        FinalDataset main = w.entity != null ? w.entity : all.get(0);
        String t = quote(tableName.get(main.id));
        out.add(example("Первые 100 строк", "SELECT *\nFROM " + t + "\nLIMIT 100"));

        FinalColumn dim = exampleDimension(main);
        if (dim != null) {
            out.add(example("Распределение по «" + dim.name + "»",
                    "SELECT " + quote(dim.name) + ", COUNT(*) AS строк,\n       ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 1) AS доля_проц\nFROM " + t
                            + "\nGROUP BY 1\nORDER BY 2 DESC"));
        }
        if (w.entity != null && dim != null) {
            for (FinalWorkspaceLinks.Link l : w.links) {
                FinalColumn money = null;
                for (FinalColumn c : l.other.columns) {
                    if (c.type == EnumColumnType.NUMERIC && Pattern.compile("(?iu)сумм|выруч|amount|revenue|цена|price|оплат").matcher(c.name).find()) {
                        money = c;
                        break;
                    }
                }
                String o = quote(tableName.get(l.other.id));
                String key = quote(w.entityKey);
                String okey = quote(l.otherKey);
                if (money != null) {
                    out.add(example("«" + main.name + "» × «" + l.other.name + "»: конверсия и деньги по «" + dim.name + "»",
                            "SELECT e." + quote(dim.name) + ",\n       COUNT(DISTINCT e." + key + ") AS клиентов,\n"
                                    + "       COUNT(DISTINCT f." + okey + ") AS платящих,\n"
                                    + "       ROUND(100.0 * COUNT(DISTINCT f." + okey + ") / COUNT(DISTINCT e." + key + "), 1) AS конверсия_проц,\n"
                                    + "       ROUND(SUM(f." + quote(money.name) + ")) AS " + quote(money.name + ", всего") + "\n"
                                    + "FROM " + t + " e\nLEFT JOIN " + o + " f ON f." + okey + " = e." + key + "\nGROUP BY 1\nORDER BY 5 DESC NULLS LAST"));
                } else if ("events".equals(l.other.role) && l.other.stepColumn != null) {
                    out.add(example("Сколько клиентов дошли до каждого шага по «" + dim.name + "»",
                            "SELECT e." + quote(dim.name) + ", f." + quote(l.other.stepColumn) + ",\n       COUNT(DISTINCT f." + okey + ") AS клиентов\n"
                                    + "FROM " + t + " e\nJOIN " + o + " f ON f." + okey + " = e." + key + "\nGROUP BY 1, 2\nORDER BY 1, 3 DESC"));
                } else {
                    out.add(example("Записей «" + l.other.name + "» на клиента по «" + dim.name + "»",
                            "SELECT e." + quote(dim.name) + ",\n       ROUND(COUNT(f." + okey + ") * 1.0 / COUNT(DISTINCT e." + key + "), 2) AS на_клиента\n"
                                    + "FROM " + t + " e\nLEFT JOIN " + o + " f ON f." + okey + " = e." + key + "\nGROUP BY 1\nORDER BY 2 DESC"));
                }
                if (out.size() >= 6) break;
            }
        }
        return out;
    }

    /** Измерение для примеров: источник / канал / тариф / устройство, иначе категория с 3–8 значениями. */
    private static FinalColumn exampleDimension(FinalDataset ds) {
        String[] preferred = {"источник|source|канал|channel", "тариф|plan|подписк", "сегмент|segment", "устройств|device", "регион|город|city"};
        for (String p : preferred) {
            Pattern re = Pattern.compile("(?iu)" + p);
            for (FinalColumn c : ds.columns) {
                if (c.type == EnumColumnType.CATEGORICAL && re.matcher(c.name).find()) return c;
            }
        }
        FinalColumn fallback = null;
        for (FinalColumn c : ds.columns) {
            if (c.type != EnumColumnType.CATEGORICAL || c.levels == null) continue;
            if (fallback == null && c.levels.size() >= 3 && c.levels.size() <= 8) fallback = c;
        }
        if (fallback != null) return fallback;
        for (FinalColumn c : ds.columns) {
            if (c.type == EnumColumnType.CATEGORICAL) return c;
        }
        return null;
    }

    private static Map<String, String> example(String title, String sql) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("sql", sql);
        return m;
    }

    /** Выполнить читающий запрос. Возвращает колонки, типы, строки (не больше {@code limit}) и время. */
    public synchronized Map<String, Object> query(String sql, int limit) {
        String text = sql == null ? "" : sql.trim();
        while (text.endsWith(";")) text = text.substring(0, text.length() - 1).trim();
        if (text.isEmpty()) throw new ExceptionAnalitikData("Пустой запрос");
        if (text.contains(";")) throw new ExceptionAnalitikData("Выполняется один запрос за раз — уберите «;» внутри");
        if (!READ_ONLY.matcher(text).matches()) {
            throw new ExceptionAnalitikData("Разрешены только читающие запросы: SELECT, WITH, FROM, DESCRIBE, SUMMARIZE, PIVOT");
        }
        long t0 = System.nanoTime();
        try {
            sync();
            try (Statement st = db().createStatement()) {
                st.setMaxRows(limit + 1);
                try (ResultSet rs = st.executeQuery(text)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    List<String> header = new ArrayList<>();
                    List<String> types = new ArrayList<>();
                    for (int j = 1; j <= n; j++) {
                        header.add(md.getColumnLabel(j));
                        types.add(kind(md.getColumnType(j)));
                    }
                    List<List<Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() >= limit) {
                            truncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>(n);
                        for (int j = 1; j <= n; j++) {
                            Object v = rs.getObject(j);
                            if (v instanceof Number) {
                                double d = ((Number) v).doubleValue();
                                row.add(Double.isFinite(d) ? v : null);
                            } else {
                                row.add(v == null ? null : v.toString());
                            }
                        }
                        rows.add(row);
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("header", header);
                    out.put("types", types);
                    out.put("rows", rows);
                    out.put("truncated", truncated);
                    out.put("ms", Math.round((System.nanoTime() - t0) / 1e6));
                    return out;
                }
            }
        } catch (SQLException e) {
            String m = e.getMessage() == null ? e.toString() : e.getMessage();
            throw new ExceptionAnalitikData("Ошибка SQL: " + m.replaceFirst("^(java\\.sql\\.SQLException: )?", ""));
        }
    }

    private static String kind(int sqlType) {
        switch (sqlType) {
            case Types.DOUBLE: case Types.FLOAT: case Types.REAL: case Types.DECIMAL: case Types.NUMERIC:
            case Types.INTEGER: case Types.BIGINT: case Types.SMALLINT: case Types.TINYINT:
                return "NUMERIC";
            case Types.DATE: case Types.TIMESTAMP: case Types.TIMESTAMP_WITH_TIMEZONE:
                return "DATE";
            default:
                return "TEXT";
        }
    }

    /**
     * Заполнить таблицу результатом её запроса и запомнить, из каких таблиц она получена (происхождение).
     * Родители — таблицы, чьи имена в кавычках встречаются в запросе.
     */
    public synchronized void refill(FinalDataset ds) {
        Map<String, Object> r = query(ds.query, 200_000);
        if (Boolean.TRUE.equals(r.get("truncated"))) {
            throw new ExceptionAnalitikData("Результат больше 200 000 строк — добавьте фильтр или агрегацию");
        }
        @SuppressWarnings("unchecked")
        List<String> header = (List<String>) r.get("header");
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) r.get("rows");
        ds.header = new ArrayList<>(header);
        ds.records = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            String[] rec = new String[row.size()];
            for (int j = 0; j < rec.length; j++) {
                Object v = row.get(j);
                if (v instanceof Double && ((Double) v) == Math.rint((Double) v) && Math.abs((Double) v) < 1e15) {
                    rec[j] = Long.toString(((Double) v).longValue());
                } else {
                    rec[j] = v == null ? "" : v.toString();
                }
            }
            ds.records.add(rec);
        }
        com.analytics.brain.data.FinalDataLoader.build(ds);

        ds.recipe = "sql";
        ds.parents.clear();
        ds.parentVersions.clear();
        for (FinalDataset other : brain.allTables()) {
            String name = tableName.get(other.id);
            if (other == ds || name == null) continue;
            if (ds.query.contains(quote(name))) {
                ds.parents.add(other.id);
                ds.parentVersions.put(other.id, other.version);
            }
        }
    }

    /** Имя таблицы в SQL для базы (после синхронизации). */
    public synchronized String sqlName(String datasetId) throws SQLException {
        sync();
        return tableName.get(datasetId);
    }

    /** Для подсказки ИИ: «таблица (колонка тип, …)». */
    public synchronized String schemaText() {
        try {
            sync();
        } catch (SQLException e) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (FinalDataset ds : brain.allTables()) {
            sb.append("- ").append(quote(tableName.get(ds.id))).append(" (").append(ds.rows).append(" строк): ");
            List<String> cols = new ArrayList<>();
            for (String h : ds.header) {
                FinalColumn c = ds.col(h);
                String t = c == null || c.type == null ? "TEXT" : c.type == EnumColumnType.NUMERIC ? "DOUBLE"
                        : c.type == EnumColumnType.DATE ? "TIMESTAMP" : "VARCHAR";
                cols.add(quote(h) + " " + t);
            }
            sb.append(String.join(", ", cols)).append("\n");
        }
        return sb.toString();
    }
}
