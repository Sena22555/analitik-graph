package com.analytics.brain.service;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalRows;
import com.analytics.brain.graph.FinalGraphFilter;
import com.analytics.brain.graph.FinalGraphLink;
import com.analytics.brain.graph.FinalGraphNode;
import com.analytics.brain.graph.FinalGraphPayload;
import com.analytics.brain.graph.FinalGraphRef;
import com.analytics.brain.graph.FinalGraphTable;
import com.analytics.brain.mathematics.AbstractAnalitikMathematicsTest;
import com.analytics.brain.mathematics.FinalMultipleComparisons;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.analytics.brain.text.FinalFormatRu.*;

@Service
public class ServiceBrainGraph {

    public final Map<String, FinalDataset> store = new ConcurrentHashMap<>();

    /**
     * Таблицы для анализа в порядке создания; {@code ids} — выбор пользователя (null или пусто — все).
     * Сырые источники, уже разложенные по таблицам, в анализ не идут (их данные — в дочерних таблицах).
     */
    public List<FinalDataset> workspace(List<String> ids) {
        return store.values().stream()
                .filter(d -> ids == null || ids.isEmpty() ? !d.source : ids.contains(d.id))
                .sorted(Comparator.comparingLong((FinalDataset d) -> d.fetchedAt).thenComparing(d -> d.id))
                .collect(Collectors.toList());
    }

    /** Все таблицы, включая сырые источники (для списка, SQL и графа происхождения). */
    public List<FinalDataset> allTables() {
        return store.values().stream()
                .sorted(Comparator.comparingLong((FinalDataset d) -> d.fetchedAt).thenComparing(d -> d.id))
                .collect(Collectors.toList());
    }

    public synchronized FinalGraphPayload overview(FinalDataset ds) {
        if (ds.overviewVersion == ds.version && ds.overviewCache != null) return ds.overviewCache;

        FinalGraphPayload p = new FinalGraphPayload();
        p.dataset = ds.summary();
        int[] all = FinalRows.all(ds);
        p.nodes.add(rootNode(ds));

        List<FinalColumn> vars = ds.columns.stream().filter(FinalColumn::analyzable).collect(Collectors.toList());
        for (FinalColumn c : vars) {
            FinalGraphNode n = c.type == EnumColumnType.NUMERIC
                    ? FinalBranchBuilder.metricNode(ds, List.of(), all, c, "root")
                    : FinalBranchBuilder.dimNode(ds, List.of(), all, null, c, "root");
            p.nodes.add(n);
            p.links.add(new FinalGraphLink("root", n.id, "tree", ""));
        }

        List<FinalGraphLink> pairs = new ArrayList<>();
        List<AbstractAnalitikMathematicsTest> tests = new ArrayList<>();
        for (int i = 0; i < vars.size(); i++) {
            for (int j = i + 1; j < vars.size(); j++) {
                FinalGraphLink link = FinalAssociationLinks.association(vars.get(i), vars.get(j));
                if (link != null) {
                    pairs.add(link);
                    tests.add(link.test);
                }
            }
        }
        FinalMultipleComparisons.applyFdr(tests);

        for (FinalGraphLink link : pairs) {
            if (!link.test.significant()) continue;
            link.level = link.test.level;
            link.strength = link.test.strength;
            link.text = link.text + " q " + fmtPq(link.test.q) + " (с поправкой на множественные сравнения).";
            p.links.add(link);
        }

        p.insights = FinalInsightFinder.insights(ds, all, pairs);
        ds.overviewCache = p;
        ds.overviewVersion = ds.version;
        return p;
    }

    private FinalGraphNode rootNode(FinalDataset ds) {
        FinalGraphNode root = new FinalGraphNode();
        root.id = "root";
        root.kind = "root";
        root.n = ds.rows;
        root.share = 1.0;
        root.label = fmtInt(ds.rows) + " " + ds.entity;
        root.sub = ds.name;
        root.expandable = false;
        root.stat("Записей", fmtInt(ds.rows))
                .stat("Колонок", ds.columns.size())
                .stat("Анализируемых переменных", ds.columns.stream().filter(FinalColumn::analyzable).count());
        if (!ds.kpis.isEmpty()) root.stat("Ключевые метрики (KPI)", String.join(", ", ds.kpis));
        if (ds.funnelColumn != null) root.stat("Воронка", ds.funnelColumn);

        FinalGraphTable table = new FinalGraphTable("Колонки", "Колонка", "Тип", "Уник.", "Пропуски");
        for (FinalColumn c : ds.columns) {
            String type = c.typeRu() + (c.analyzable() ? "" : " (исключ.)");
            table.row(c.name, type, c.unique, pct((double) c.missing / ds.rows));
        }
        root.table = table;
        root.text = "Центр карты. Вокруг — все переменные набора данных. Цветные связи между ними — "
                + "статистически значимые зависимости (после поправки Бенджамини–Хохберга, FDR 5%). "
                + "Нажмите на любой узел, чтобы раскрыть его как ветку ментальной карты.";
        return root;
    }

    public FinalGraphPayload expand(FinalDataset ds, FinalGraphRef ref) {
        FinalGraphPayload p = new FinalGraphPayload();
        List<FinalGraphFilter> path = ref.filters == null ? new ArrayList<>() : ref.filters;
        int[] rows = FinalRows.matching(ds, path);

        switch (ref.kind) {
            case "dim":
                FinalBranchBuilder.expandDimension(ds, path, rows, ds.col(ref.column), ref.nodeId, p);
                break;
            case "cat":
                FinalBranchBuilder.expandSegment(ds, path, rows, ref.nodeId, p, false);
                break;
            case "more":
                FinalBranchBuilder.expandSegment(ds, path, rows, ref.nodeId, p, true);
                break;
            case "metric":
                FinalBranchBuilder.expandMetric(ds, path, rows, ds.col(ref.column), ref.nodeId, p);
                break;
            case "funnel":
                FinalBranchBuilder.expandFunnel(ds, path, rows, ref.nodeId, p);
                break;
            default:
                break;
        }
        return p;
    }
}
