package com.analytics.brain.service;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalDatasetRoles;
import com.analytics.brain.graph.FinalGraphLink;
import com.analytics.brain.mathematics.AbstractAnalitikMathematicsTest;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;
import com.analytics.brain.mathematics.FinalMultipleComparisons;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.analytics.brain.text.FinalFormatRu.*;

/**
 * Атлас: несколько баз на одном графе и влияние между ними.
 * <p>
 * Все связанные базы сворачиваются до уровня сущности (одна строка на клиента): число записей, суммы и средние
 * числовых колонок, «было ли хоть раз» для категорий (для событий это «был на шаге X» — мост между путями и деньгами).
 * Затем каждая пара признаков из <b>разных</b> баз проверяется тем же движком тестов, что и «мозг» одной базы,
 * с поправкой Бенджамини–Хохберга по всем парам сразу.
 */
@Service
public class ServiceAtlas {

    private static final Pattern ADDITIVE = Pattern.compile(
            "(?iu)сумм|выручк|amount|revenue|sum|цена|price|стоимост|кол-во|количеств|qty|оплат|доход|расход|затрат");
    private static final int MAX_LINKS = 80;

    private final ServiceBrainGraph brain;

    public ServiceAtlas(ServiceBrainGraph brain) {
        this.brain = brain;
    }

    private static final class Feature {
        String id;
        FinalDataset ds;
        String label;
        String kind;
        String stat;
        FinalColumn column;
    }

    private String cacheKey;
    private Map<String, Object> cache;

    /** Атлас считается заново, только если изменился состав баз или версия какой-то из них. */
    public synchronized Map<String, Object> atlas(List<String> ids) {
        List<FinalDataset> all = brain.workspace(ids);
        StringBuilder key = new StringBuilder();
        for (FinalDataset d : all) key.append(d.id).append(':').append(d.version).append(',');
        if (key.toString().equals(cacheKey)) return cache;
        Map<String, Object> result = compute(all);
        cacheKey = key.toString();
        cache = result;
        return result;
    }

    private Map<String, Object> compute(List<FinalDataset> all) {
        FinalWorkspaceLinks.Resolved w = FinalWorkspaceLinks.resolve(all);

        Map<String, Object> out = new LinkedHashMap<>();
        if (w.entity == null) {
            out.put("error", all.size() < 2
                    ? "Для атласа нужны хотя бы две базы с общим ключом (например «ID пользователя»)."
                    : "Не найден общий ключ между базами: нужна колонка-идентификатор, значения которой встречаются в разных базах.");
            out.put("datasets", datasetsInfo(w, all));
            return out;
        }

        int n = w.entity.rows;
        List<Feature> features = new ArrayList<>();
        for (FinalColumn c : w.entity.columns) {
            if (!c.analyzable() || c.type == EnumColumnType.DATE) continue;
            Feature f = new Feature();
            f.id = w.entity.id + "::" + c.name;
            f.ds = w.entity;
            f.label = c.name;
            f.kind = "attr";
            f.column = c;
            f.stat = c.type == EnumColumnType.NUMERIC
                    ? "среднее " + fmt(FinalAnalitikMathematics.mean(FinalAnalitikMathematics.clean(c.num)))
                    : (c.levels == null ? 0 : c.levels.size()) + " " + plural(c.levels == null ? 0 : c.levels.size(), "значение", "значения", "значений");
            features.add(f);
        }
        for (FinalWorkspaceLinks.Link link : w.links) features.addAll(aggregate(w, link, n));

        List<FinalGraphLink> candidates = new ArrayList<>();
        List<Feature[]> pairs = new ArrayList<>();
        List<AbstractAnalitikMathematicsTest> tests = new ArrayList<>();
        for (int i = 0; i < features.size(); i++) {
            for (int j = i + 1; j < features.size(); j++) {
                Feature a = features.get(i);
                Feature b = features.get(j);
                if (a.ds == b.ds) continue;
                FinalGraphLink link = FinalAssociationLinks.association(a.column, b.column);
                if (link == null || link.test == null) continue;
                candidates.add(link);
                pairs.add(new Feature[]{a, b});
                tests.add(link.test);
            }
        }
        FinalMultipleComparisons.applyFdr(tests);

        List<Map<String, Object>> links = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).test.significant()) order.add(i);
        }
        order.sort(Comparator.comparingDouble(i -> -candidates.get(i).test.strength));
        Map<String, Integer> degree = new HashMap<>();
        for (int k = 0; k < Math.min(MAX_LINKS, order.size()); k++) {
            int i = order.get(k);
            FinalGraphLink l = candidates.get(i);
            Feature a = pairs.get(i)[0];
            Feature b = pairs.get(i)[1];
            AbstractAnalitikMathematicsTest t = l.test;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id + "⟷" + b.id);
            m.put("source", a.id);
            m.put("target", b.id);
            m.put("level", t.level);
            m.put("strength", t.strength);
            m.put("direction", l.direction);
            m.put("label", l.label);
            boolean same = isSameFact(t);
            m.put("same", same);
            m.put("text", (same ? "Один и тот же факт записан в двух базах (|ρ| ≥ 0,9) — это сверка: базы согласованы между собой. " : "")
                    + l.text + " q " + fmtPq(t.q) + " (поправка на множественные сравнения по " + tests.size() + " парам).");
            m.put("test", testInfo(t));
            links.add(m);
            degree.merge(a.id, 1, Integer::sum);
            degree.merge(b.id, 1, Integer::sum);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Feature f : features) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.id);
            m.put("ds", f.ds.id);
            m.put("label", f.label);
            m.put("kind", f.kind);
            m.put("type", f.column.type.name());
            m.put("stat", f.stat);
            m.put("degree", degree.getOrDefault(f.id, 0));
            nodes.add(m);
        }

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("id", w.entity.id);
        entity.put("name", w.entity.name);
        entity.put("key", w.entityKey);
        entity.put("rows", n);
        entity.put("word", w.entity.entity);
        out.put("entity", entity);
        out.put("datasets", datasetsInfo(w, all));
        out.put("nodes", nodes);
        out.put("links", links);
        out.put("tested", tests.size());
        out.put("significant", order.size());
        out.put("insights", insights(links, features));
        return out;
    }

    /** Признаки связанной базы на уровне сущности. */
    private List<Feature> aggregate(FinalWorkspaceLinks.Resolved w, FinalWorkspaceLinks.Link link, int n) {
        FinalDataset ds = link.other;
        int[] owner = w.owners(ds);
        List<Feature> out = new ArrayList<>();

        double[] count = new double[n];
        for (int o : owner) {
            if (o >= 0) count[o]++;
        }
        int covered = 0;
        for (double c : count) {
            if (c > 0) covered++;
        }
        String word = "events".equals(ds.role) ? "событий" : "записей";
        Feature cnt = numeric(ds, "число " + word, "count", count);
        cnt.stat = "в среднем " + fmt(FinalAnalitikMathematics.mean(count)) + " · есть у " + pct((double) covered / n);
        out.add(cnt);

        for (String key : FinalDatasetRoles.keyColumns(ds)) {
            if (key.equals(link.otherKey)) continue;
            String[] v = FinalDatasetRoles.values(ds, key);
            if (FinalDatasetRoles.unique(v)) continue;
            List<Set<String>> per = new ArrayList<>(n);
            for (int i = 0; i < n; i++) per.add(null);
            for (int i = 0; i < ds.rows; i++) {
                if (owner[i] < 0 || v[i] == null) continue;
                if (per.get(owner[i]) == null) per.set(owner[i], new HashSet<>());
                per.get(owner[i]).add(v[i]);
            }
            double[] d = new double[n];
            for (int i = 0; i < n; i++) d[i] = per.get(i) == null ? 0 : per.get(i).size();
            String label = key.toLowerCase(Locale.ROOT).contains("сесси") || key.toLowerCase(Locale.ROOT).contains("session")
                    ? "число сессий" : "уникальных «" + key + "»";
            Feature f = numeric(ds, label, "distinct", d);
            f.stat = "в среднем " + fmt(FinalAnalitikMathematics.mean(d));
            if (variable(d)) out.add(f);
        }

        for (FinalColumn c : ds.columns) {
            if (c.type == EnumColumnType.NUMERIC) {
                boolean additive = ADDITIVE.matcher(c.name).find() || c.countLike;
                double[] sum = new double[n];
                double[] cntv = new double[n];
                for (int i = 0; i < ds.rows; i++) {
                    if (owner[i] < 0 || Double.isNaN(c.num[i])) continue;
                    sum[owner[i]] += c.num[i];
                    cntv[owner[i]]++;
                }
                double[] value = new double[n];
                int present = 0;
                for (int i = 0; i < n; i++) {
                    if (additive) {
                        value[i] = sum[i];
                    } else {
                        value[i] = cntv[i] == 0 ? Double.NaN : sum[i] / cntv[i];
                    }
                    if (cntv[i] > 0) present++;
                }
                if (present < 30 || !variable(value)) continue;
                Feature f = numeric(ds, c.name + (additive ? " (итого)" : " (среднее)"), additive ? "sum" : "mean", value);
                f.stat = (additive ? "в среднем " : "среднее ") + fmt(FinalAnalitikMathematics.mean(FinalAnalitikMathematics.clean(value)));
                out.add(f);
            } else if (c.type == EnumColumnType.CATEGORICAL && c.levels != null) {
                boolean step = c.name.equals(ds.stepColumn);
                int limit = step ? 12 : 6;
                int used = 0;
                for (String level : c.levels) {
                    if (level.equals("Другое") || used >= limit) continue;
                    double[] flag = new double[n];
                    for (int i = 0; i < ds.rows; i++) {
                        if (owner[i] >= 0 && level.equals(c.cat[i])) flag[owner[i]] = 1;
                    }
                    int yes = 0;
                    for (double x : flag) yes += (int) x;
                    if (yes < 15 || yes > n - 15) continue;
                    String label = step ? "был на «" + level + "»" : c.name + ": " + level;
                    Feature f = numeric(ds, label, "flag", flag);
                    f.stat = "у " + pct((double) yes / n) + " " + w.entity.entity;
                    out.add(f);
                    used++;
                }
            }
        }
        return out;
    }

    private static Feature numeric(FinalDataset ds, String label, String kind, double[] values) {
        Feature f = new Feature();
        f.id = ds.id + "::" + kind + "::" + label;
        f.ds = ds;
        f.label = label;
        f.kind = kind;
        f.column = new FinalColumn(ds.name + ": " + label);
        f.column.type = EnumColumnType.NUMERIC;
        f.column.num = values;
        return f;
    }

    private static boolean variable(double[] x) {
        double first = Double.NaN;
        for (double v : x) {
            if (Double.isNaN(v)) continue;
            if (Double.isNaN(first)) {
                first = v;
            } else if (v != first) {
                return true;
            }
        }
        return false;
    }

    private static List<Map<String, Object>> datasetsInfo(FinalWorkspaceLinks.Resolved w, List<FinalDataset> all) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FinalDataset ds : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ds.id);
            m.put("name", ds.name);
            m.put("rows", ds.rows);
            m.put("role", ds.role);
            m.put("roleRu", FinalDatasetRoles.roleRu(ds.role));
            boolean isEntity = ds == w.entity;
            FinalWorkspaceLinks.Link l = w.linkOf(ds);
            m.put("isEntity", isEntity);
            m.put("linked", isEntity || l != null);
            if (isEntity) m.put("key", w.entityKey);
            if (l != null) {
                m.put("key", l.otherKey);
                m.put("overlap", l.overlap);
                int[] owner = w.owners(ds);
                Set<Integer> covered = new HashSet<>();
                for (int o : owner) {
                    if (o >= 0) covered.add(o);
                }
                m.put("coverage", (double) covered.size() / Math.max(1, w.entity.rows));
            }
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> testInfo(AbstractAnalitikMathematicsTest t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("test", t.test);
        m.put("stat", num(t.stat));
        m.put("df1", num(t.df1));
        m.put("df2", num(t.df2));
        m.put("p", t.p);
        m.put("q", t.q);
        m.put("effectName", t.effectName);
        m.put("effect", num(t.effect));
        m.put("lo", num(t.lo));
        m.put("hi", num(t.hi));
        m.put("n", t.n);
        return m;
    }

    /** Почти полное совпадение двух признаков — не находка, а сверка баз («был на Оплате» = «есть платёж»). */
    private static boolean isSameFact(AbstractAnalitikMathematicsTest t) {
        return "ρ".equals(t.effectName) && Math.abs(t.effect) >= 0.9;
    }

    private static Double num(double v) {
        return Double.isFinite(v) ? v : null;
    }

    /** Главные находки: самые сильные связи, не больше двух на признак и на пару баз — чтобы список был разнообразным. */
    private static List<Map<String, Object>> insights(List<Map<String, Object>> links, List<Feature> features) {
        Map<String, Feature> byId = new HashMap<>();
        for (Feature f : features) byId.put(f.id, f);
        Map<String, Integer> used = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : links) {
            if (Boolean.TRUE.equals(l.get("same"))) continue;
            String s = (String) l.get("source");
            String t = (String) l.get("target");
            Feature a = byId.get(s);
            Feature b = byId.get(t);
            String pair = a.ds.id.compareTo(b.ds.id) < 0 ? a.ds.id + "|" + b.ds.id : b.ds.id + "|" + a.ds.id;
            if (used.getOrDefault(s, 0) >= 2 || used.getOrDefault(t, 0) >= 2 || used.getOrDefault(pair, 0) >= 2) continue;
            used.merge(s, 1, Integer::sum);
            used.merge(t, 1, Integer::sum);
            used.merge(pair, 1, Integer::sum);
            String sign = "negative".equals(l.get("direction")) ? "обратная связь" : "связь";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", "«" + a.ds.name + ": " + a.label + "» ⟷ «" + b.ds.name + ": " + b.label + "» — "
                    + levelRuF((String) l.get("level")) + " " + sign + " (" + l.get("label") + ")");
            m.put("level", l.get("level"));
            m.put("link", l.get("id"));
            out.add(m);
            if (out.size() >= 10) break;
        }
        return out;
    }
}
