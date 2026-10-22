package com.analytics.brain.data;

import com.analytics.brain.graph.FinalGraphPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FinalDataset {

    public String id;
    public String name;
    public String sourceType;
    public String sourceUrl;
    /** Для таблиц из SQL-консоли: запрос, которым таблица построена (по нему же и обновляется). */
    public String query;

    /* ── происхождение (lineage): граф — это ещё и конвейер данных ── */
    /** Таблицы, из которых получена эта (пустой список — исходные данные). */
    public List<String> parents = new ArrayList<>();
    /** Версии родителей, с которых таблица посчитана в последний раз: изменились — пересчитать. */
    public Map<String, Integer> parentVersions = new LinkedHashMap<>();
    /** Как получена: "sql" — запросом, "structure" — сортировкой сырых данных. */
    public String recipe;
    /** Часть рецепта: для сортировки — "entity:<ключ>", "fact" или "clean". */
    public String recipePart;
    /** Сырой источник, уже разложенный по таблицам: в анализ не идёт, на графе — как начало потока. */
    public boolean source;
    public Map<String, String> headers = new LinkedHashMap<>();
    public long fetchedAt;
    public int version = 1;

    public List<String> header = new ArrayList<>();
    public List<String[]> records = new ArrayList<>();

    public int rows;
    public List<FinalColumn> columns = new ArrayList<>();
    public Map<String, FinalColumn> byName = new LinkedHashMap<>();

    public List<String> kpis = new ArrayList<>();
    public String funnelColumn;
    public String entity = "записей";

    /** Роль базы в рабочем пространстве (сущность, события, факты, таблица) — см. FinalDatasetRoles. */
    public String role = "table";
    public String keyColumn;
    public String stepColumn;
    public String timeColumn;

    public Map<String, Set<String>> derivedFrom = new HashMap<>();

    public transient FinalGraphPayload overviewCache;
    public transient int overviewVersion = -1;

    public FinalColumn col(String name) {
        return byName.get(name);
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("rows", rows);
        m.put("version", version);
        m.put("sourceType", sourceType);
        m.put("sourceUrl", sourceUrl);
        m.put("query", query);
        m.put("parents", parents);
        m.put("recipe", recipe);
        m.put("source", source);
        m.put("fetchedAt", fetchedAt);
        m.put("entity", entity);
        m.put("kpis", kpis);
        m.put("funnelColumn", funnelColumn);
        m.put("role", role);
        m.put("roleRu", FinalDatasetRoles.roleRu(role));
        m.put("keyColumn", keyColumn);
        m.put("stepColumn", stepColumn);
        m.put("timeColumn", timeColumn);

        List<Map<String, Object>> cols = new ArrayList<>();
        for (FinalColumn c : columns) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("name", c.name);
            x.put("type", c.type.name());
            x.put("typeRu", c.typeRu());
            x.put("unique", c.unique);
            x.put("missing", c.missing);
            cols.add(x);
        }
        m.put("columns", cols);
        return m;
    }
}
