package com.analytics.brain.api;

import com.analytics.brain.data.FinalDataLoader;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalDemoProduct;
import com.analytics.brain.data.FinalDemoRawLog;
import com.analytics.brain.structure.ServiceStructure;
import com.analytics.brain.data.FinalJsonParser;
import com.analytics.brain.service.FinalWorkspaceLinks;
import com.analytics.brain.service.ServiceAtlas;
import com.analytics.brain.service.ServiceBrainGraph;
import com.analytics.brain.service.ServiceJourney;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Рабочее пространство из нескольких баз: атлас (влияние между базами) и граф путей. */
@RestController
@RequestMapping("/api/v2")
public class ControllerWorkspaceApi extends AbstractApiController {

    private static final String DEMO_PRODUCT = "demo-product";

    private final ServiceAtlas atlas;
    private final ServiceJourney journey;
    private final ServiceStructure structure;

    public ControllerWorkspaceApi(ServiceBrainGraph brain, ServiceAtlas atlas, ServiceJourney journey, ServiceStructure structure) {
        super(brain);
        this.atlas = atlas;
        this.journey = journey;
        this.structure = structure;
    }

    @Override
    protected String notFoundHint() {
        return "";
    }

    @Override
    protected String unknownErrorText(Exception e) {
        return e.getClass().getSimpleName();
    }

    @GetMapping("/workspace")
    public Map<String, Object> workspace() {
        List<FinalDataset> all = brain.allTables();
        FinalWorkspaceLinks.Resolved w = FinalWorkspaceLinks.resolve(brain.workspace(null));
        List<Map<String, Object>> datasets = new ArrayList<>();
        for (FinalDataset ds : all) {
            Map<String, Object> m = ds.summary();
            m.remove("columns");
            m.put("columnCount", ds.columns.size());
            m.put("isEntity", ds == w.entity);
            m.put("linked", ds == w.entity || w.linkOf(ds) != null);
            datasets.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasets", datasets);
        out.put("entity", w.entity == null ? null : w.entity.id);
        out.put("entityKey", w.entityKey);
        return out;
    }

    /**
     * Граф таблиц: узел — база, ребро — общий ключ между базами и сводка значимых связей их признаков (из атласа).
     */
    @GetMapping("/workspace/graph")
    @SuppressWarnings("unchecked")
    public Map<String, Object> workspaceGraph() {
        List<FinalDataset> all = brain.allTables();
        FinalWorkspaceLinks.Resolved w = FinalWorkspaceLinks.resolve(brain.workspace(null));

        List<Map<String, Object>> tables = new ArrayList<>();
        for (FinalDataset ds : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ds.id);
            m.put("name", ds.name);
            m.put("rows", ds.rows);
            m.put("columns", ds.columns.size());
            m.put("version", ds.version);
            m.put("role", ds.role);
            m.put("roleRu", com.analytics.brain.data.FinalDatasetRoles.roleRu(ds.role));
            m.put("keyColumn", ds.keyColumn);
            m.put("stepColumn", ds.stepColumn);
            m.put("sourceType", ds.sourceType);
            m.put("isEntity", ds == w.entity);
            List<Map<String, String>> cols = new ArrayList<>();
            for (com.analytics.brain.data.FinalColumn c : ds.columns) {
                Map<String, String> col = new LinkedHashMap<>();
                col.put("name", c.name);
                col.put("type", c.type == null ? "TEXT" : c.type.name());
                col.put("typeRu", c.typeRu());
                cols.add(col);
            }
            m.put("columnList", cols);
            m.put("source", ds.source);
            m.put("recipe", ds.recipe);
            m.put("parents", ds.parents);
            m.put("splittable", structure.splittable(ds));
            tables.add(m);
        }

        Map<String, Map<String, Object>> edges = new LinkedHashMap<>();
        for (FinalWorkspaceLinks.Link l : w.links) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("source", l.entity.id);
            e.put("target", l.other.id);
            e.put("key", l.otherKey);
            e.put("entityKey", l.entityKey);
            e.put("overlap", l.overlap);
            e.put("links", new ArrayList<Map<String, Object>>());
            edges.put(l.entity.id + "|" + l.other.id, e);
        }
        if (all.size() > 1 && w.entity != null) {
            Map<String, Object> a = atlas.atlas(null);
            Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
            for (Map<String, Object> n : (List<Map<String, Object>>) a.getOrDefault("nodes", List.of())) nodes.put((String) n.get("id"), n);
            for (Map<String, Object> l : (List<Map<String, Object>>) a.getOrDefault("links", List.of())) {
                Map<String, Object> s = nodes.get(l.get("source"));
                Map<String, Object> t = nodes.get(l.get("target"));
                String sd = (String) s.get("ds");
                String td = (String) t.get("ds");
                // связь признаков двух баз кладём на ребро между ними; если общего ключа напрямую нет —
                // создаём ребро «через центр» (обе базы сведены к ключу базы-сущности)
                Map<String, Object> e = edges.get(sd + "|" + td);
                if (e == null) e = edges.get(td + "|" + sd);
                if (e == null) {
                    e = new LinkedHashMap<>();
                    e.put("source", sd);
                    e.put("target", td);
                    e.put("key", w.entityKey);
                    e.put("entityKey", w.entityKey);
                    e.put("via", w.entity.name);
                    e.put("links", new ArrayList<Map<String, Object>>());
                    edges.put(sd + "|" + td, e);
                }
                Map<String, Object> item = new LinkedHashMap<>(l);
                item.put("sourceLabel", s.get("label"));
                item.put("targetLabel", t.get("label"));
                item.put("sourceDs", sd);
                item.put("targetDs", td);
                ((List<Map<String, Object>>) e.get("links")).add(item);
            }
        }
        for (Map<String, Object> e : edges.values()) {
            List<Map<String, Object>> ls = (List<Map<String, Object>>) e.get("links");
            long real = ls.stream().filter(x -> !Boolean.TRUE.equals(x.get("same"))).count();
            e.put("count", real);
            e.put("strength", ls.stream().filter(x -> !Boolean.TRUE.equals(x.get("same")))
                    .mapToDouble(x -> ((Number) x.get("strength")).doubleValue()).max().orElse(0));
        }

        // происхождение: откуда получена каждая производная таблица
        List<Map<String, Object>> flows = new ArrayList<>();
        for (FinalDataset ds : all) {
            for (String p : ds.parents) {
                if (brain.store.get(p) == null) continue;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("source", p);
                f.put("target", ds.id);
                f.put("recipe", ds.recipe);
                flows.add(f);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", tables);
        out.put("links", new ArrayList<>(edges.values()));
        out.put("flows", flows);
        out.put("entity", w.entity == null ? null : w.entity.id);
        return out;
    }

    /** Четыре связанные демо-базы; предыдущий демо-продукт заменяется. */
    @PostMapping("/demo/product")
    public Map<String, Object> demoProduct(@RequestParam(defaultValue = "1500") int users) throws Exception {
        brain.store.values().removeIf(d -> DEMO_PRODUCT.equals(d.sourceType));
        Map<String, List<Map<String, Object>>> bases = FinalDemoProduct.generate(users);
        List<Map<String, Object>> created = new ArrayList<>();
        long now = System.currentTimeMillis();
        int order = 0;
        for (Map.Entry<String, List<Map<String, Object>>> e : bases.entrySet()) {
            FinalDataset ds = new FinalDataset();
            ds.id = UUID.randomUUID().toString().substring(0, 8);
            ds.name = e.getKey();
            ds.sourceType = DEMO_PRODUCT;
            ds.fetchedAt = now + order++;
            new FinalJsonParser().parse(ds, JSON.writeValueAsString(e.getValue()));
            FinalDataLoader.build(ds);
            brain.store.put(ds.id, ds);
            created.add(ds.summary());
        }
        return Map.of("datasets", created);
    }

    /** План сортировки сырой таблицы: что почистить, какие зависимости найдены, какие таблицы получатся. */
    @GetMapping("/datasets/{id}/structure")
    public Map<String, Object> structurePlan(@PathVariable String id) {
        return structure.plan(dataset(id)).toJson();
    }

    /** Разложить сырую таблицу по плану: появятся связанные таблицы, которые сами обновляются вслед за источником. */
    @PostMapping("/datasets/{id}/structure")
    public Map<String, Object> structureApply(@PathVariable String id) {
        List<Map<String, Object>> created = new ArrayList<>();
        for (FinalDataset ds : structure.apply(dataset(id))) created.add(ds.summary());
        return Map.of("datasets", created);
    }

    /** Демо «несортированных» данных: плоский лог сайта с мусором. */
    @PostMapping("/demo/raw")
    public Map<String, Object> demoRaw(@RequestParam(defaultValue = "600") int users) throws Exception {
        FinalDataset ds = new FinalDataset();
        ds.id = UUID.randomUUID().toString().substring(0, 8);
        ds.name = "Сырой лог сайта";
        ds.sourceType = "demo-raw";
        ds.fetchedAt = System.currentTimeMillis();
        new FinalJsonParser().parse(ds, JSON.writeValueAsString(FinalDemoRawLog.generate(Math.max(100, Math.min(users, 5000)))));
        FinalDataLoader.build(ds);
        brain.store.put(ds.id, ds);
        return ds.summary();
    }

    @GetMapping("/atlas")
    public Map<String, Object> atlas(@RequestParam(required = false) String ids) {
        List<String> selected = ids == null || ids.isBlank() ? null : Arrays.asList(ids.split(","));
        return atlas.atlas(selected);
    }

    @PostMapping("/journey")
    public Map<String, Object> journey(@RequestBody ServiceJourney.Request r) {
        return journey.journey(r);
    }

    @PostMapping("/journey/profile")
    public Map<String, Object> profile(@RequestBody ServiceJourney.Request r) {
        return journey.profile(r);
    }
}
