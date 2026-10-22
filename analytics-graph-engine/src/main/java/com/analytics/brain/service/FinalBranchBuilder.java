package com.analytics.brain.service;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalRows;
import com.analytics.brain.graph.FinalGraphBar;
import com.analytics.brain.graph.FinalGraphEffect;
import com.analytics.brain.graph.FinalGraphFilter;
import com.analytics.brain.graph.FinalGraphLink;
import com.analytics.brain.graph.FinalGraphNode;
import com.analytics.brain.graph.FinalGraphPayload;
import com.analytics.brain.graph.FinalGraphTable;
import com.analytics.brain.mathematics.AbstractSegmentComparisonTest;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;
import com.analytics.brain.mathematics.FinalAnovaTest;
import com.analytics.brain.mathematics.FinalChiSquareTest;
import com.analytics.brain.mathematics.FinalMultipleComparisons;
import com.analytics.brain.mathematics.FinalTwoProportionTest;
import com.analytics.brain.mathematics.FinalWelchTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.analytics.brain.service.FinalGraphPaths.*;
import static com.analytics.brain.text.FinalFormatRu.*;

public final class FinalBranchBuilder {

    private static final int TOP_SUBDIMS = 5;
    private static final int MAX_KPI_LEAVES = 6;

    private FinalBranchBuilder() {
    }

    private static final class MetricTest {

        final FinalColumn column;
        final AbstractSegmentComparisonTest test;
        final double[] segment;
        final double[] others;

        MetricTest(FinalColumn column, AbstractSegmentComparisonTest test, double[] segment, double[] others) {
            this.column = column;
            this.test = test;
            this.segment = segment;
            this.others = others;
        }
    }

    static void expandDimension(FinalDataset ds, List<FinalGraphFilter> path, int[] rows, FinalColumn c, String parentId, FinalGraphPayload p) {
        if (c == null) return;
        Map<String, Integer> counts = countByLevel(c, rows);
        int total = sum(counts);
        FinalColumn kpi = ds.kpis.isEmpty() ? null : ds.col(ds.kpis.get(0));

        String prevId = null;
        double prevKpi = Double.NaN;
        for (String value : displayOrder(ds, c, counts)) {
            int count = counts.getOrDefault(value, 0);
            if (count == 0) continue;

            List<FinalGraphFilter> childPath = new ArrayList<>(path);
            childPath.add(new FinalGraphFilter(c.name, value));
            FinalGraphNode child = segmentNode(ds, childPath, rows, c, value, count, total, parentId);
            p.nodes.add(child);

            FinalGraphLink link = new FinalGraphLink(parentId, child.id, "tree", pct((double) count / total) + " · " + fmtInt(count));
            link.level = child.level;
            p.links.add(link);

            if (c.type == EnumColumnType.DATE && kpi != null) {
                double kpiMean = FinalAnalitikMathematics.mean(FinalAnalitikMathematics.clean(FinalRows.values(kpi, FinalRows.where(rows, c, value))));
                if (prevId != null && !Double.isNaN(prevKpi) && Math.abs(prevKpi) > 1e-9) {
                    p.links.add(periodFlow(kpi, prevId, child.id, prevKpi, kpiMean));
                }
                prevId = child.id;
                prevKpi = kpiMean;
            }
        }
    }

    private static FinalGraphLink periodFlow(FinalColumn kpi, String fromId, String toId, double before, double after) {
        double change = (after - before) / Math.abs(before);
        FinalGraphLink flow = new FinalGraphLink(fromId, toId, "flow", kpi.name + " " + sgnPct(change));
        flow.direction = change >= 0 ? "positive" : "negative";
        flow.text = "Изменение среднего «" + kpi.name + "» между периодами: " + fmt(before) + " → " + fmt(after);
        return flow;
    }

    static void expandSegment(FinalDataset ds, List<FinalGraphFilter> path, int[] rows, String nodeId, FinalGraphPayload p, boolean moreOnly) {
        if (path.isEmpty()) return;
        FinalGraphFilter last = path.get(path.size() - 1);
        int[] rest = FinalRows.restOf(ds, path);
        String segmentId = segmentId(path);

        if (!moreOnly) {
            addMetricLeaves(ds, rows, rest, last, segmentId, pathKey(path), p);
            addFunnelNode(ds, path, rows, segmentId, p);
        }
        addSubDimensions(ds, path, rows, rest, segmentId, moreOnly, p);
        if (moreOnly) p.remove = List.of(nodeId);
    }

    private static void addMetricLeaves(FinalDataset ds, int[] rows, int[] rest, FinalGraphFilter segment, String segmentId,
                                 String pathKey, FinalGraphPayload p) {
        List<MetricTest> all = new ArrayList<>();
        List<AbstractSegmentComparisonTest> tests = new ArrayList<>();
        for (FinalColumn m : ds.columns) {
            if (m.type != EnumColumnType.NUMERIC) continue;
            double[] a = FinalAnalitikMathematics.clean(FinalRows.values(m, rows));
            double[] b = FinalAnalitikMathematics.clean(FinalRows.values(m, rest));
            if (a.length < 2) continue;
            AbstractSegmentComparisonTest t = AbstractSegmentComparisonTest.compare(m.countLike, a, b);
            all.add(new MetricTest(m, t, a, b));
            tests.add(t);
        }
        FinalMultipleComparisons.applyFdr(tests);

        List<MetricTest> chosen = new ArrayList<>();
        for (String kpi : ds.kpis) {
            for (MetricTest mt : all) {
                if (mt.column.name.equals(kpi)) chosen.add(mt);
            }
        }
        List<MetricTest> strongest = all.stream()
                .filter(mt -> !chosen.contains(mt) && mt.test.significant())
                .sorted((x, y) -> Double.compare(Math.abs(y.test.effect), Math.abs(x.test.effect)))
                .limit(Math.max(0, MAX_KPI_LEAVES - chosen.size()))
                .collect(Collectors.toList());
        chosen.addAll(strongest);

        for (MetricTest mt : chosen) {
            FinalGraphNode leaf = metricLeaf(pathKey, mt, segment, segmentId, rest.length > 0);
            p.nodes.add(leaf);
            FinalGraphLink link = new FinalGraphLink(segmentId, leaf.id, "tree", leaf.sub);
            link.level = leaf.level;
            link.direction = leaf.direction;
            link.test = mt.test;
            p.links.add(link);
        }
    }

    private static void addFunnelNode(FinalDataset ds, List<FinalGraphFilter> path, int[] rows, String segmentId, FinalGraphPayload p) {
        if (ds.funnelColumn == null) return;
        if (path.stream().anyMatch(f -> f.column.equals(ds.funnelColumn))) return;

        FinalColumn funnel = ds.col(ds.funnelColumn);
        int[] reached = funnelReached(funnel, rows);
        if (reached[0] <= 0 || funnel.levels.size() < 2) return;

        String key = pathKey(path);
        FinalGraphNode n = new FinalGraphNode();
        n.id = "f:" + key;
        n.kind = "funnel";
        n.label = "Воронка";
        n.parent = segmentId;
        n.n = reached[0];
        n.expandable = true;
        String lastStage = funnel.levels.get(funnel.levels.size() - 1);
        n.sub = "до «" + lastStage + "» — " + pct((double) reached[reached.length - 1] / reached[0]);
        n.ref = ref("funnel", path, null, n.id);
        n.text = "Воронка по колонке «" + funnel.name + "». Значение трактуется как последний достигнутый этап; "
                + "«дошли до этапа» = достигли его или любого следующего.";
        p.nodes.add(n);
        p.links.add(new FinalGraphLink(segmentId, n.id, "tree", "воронка · " + fmtInt(reached[0])));
    }

    private static void addSubDimensions(FinalDataset ds, List<FinalGraphFilter> path, int[] rows, int[] rest, String segmentId,
                                  boolean moreOnly, FinalGraphPayload p) {
        Set<String> used = path.stream().map(f -> f.column).collect(Collectors.toSet());
        List<FinalColumn> dims = new ArrayList<>();
        Map<FinalColumn, FinalChiSquareTest> difference = new HashMap<>();
        for (FinalColumn d : ds.columns) {
            if (!d.isDim() || used.contains(d.name) || d.name.equals(ds.funnelColumn)) continue;
            Set<String> present = new HashSet<>();
            for (int i : rows) {
                if (d.cat[i] != null) present.add(d.cat[i]);
            }
            if (present.size() < 2) continue;
            dims.add(d);
            difference.put(d, segmentVsRest(d, rows, rest));
        }
        dims.sort((x, y) -> Double.compare(difference.get(y).effect, difference.get(x).effect));

        int from = moreOnly ? TOP_SUBDIMS : 0;
        int to = moreOnly ? dims.size() : Math.min(TOP_SUBDIMS, dims.size());
        for (int k = from; k < to; k++) {
            FinalColumn d = dims.get(k);
            FinalChiSquareTest chi = difference.get(d);
            FinalGraphNode n = dimNode(ds, path, rows, rest, d, segmentId);
            p.nodes.add(n);
            FinalGraphLink link = new FinalGraphLink(segmentId, n.id, "tree", chi.p < 0.05 ? "отличается · V=" + fmt(chi.effect) : "");
            link.level = n.level;
            link.test = chi;
            p.links.add(link);
        }

        if (!moreOnly && dims.size() > TOP_SUBDIMS) {
            FinalGraphNode more = new FinalGraphNode();
            more.id = "x:" + pathKey(path);
            more.kind = "more";
            more.parent = segmentId;
            more.expandable = true;
            more.label = "Ещё " + (dims.size() - TOP_SUBDIMS) + " измерений";
            more.sub = "раскрыть";
            more.ref = ref("more", path, null, more.id);
            more.text = "Остальные измерения, по которым сегмент отличается слабее.";
            p.nodes.add(more);
            p.links.add(new FinalGraphLink(segmentId, more.id, "tree", ""));
        }
    }

    static void expandMetric(FinalDataset ds, List<FinalGraphFilter> path, int[] rows, FinalColumn c, String parentId, FinalGraphPayload p) {
        if (c == null) return;
        double[] x = FinalAnalitikMathematics.clean(FinalRows.values(c, rows));
        List<double[]> bins = FinalAnalitikMathematics.histogram(x);

        for (int b = 0; b < bins.size(); b++) {
            double lo = bins.get(b)[0];
            double hi = bins.get(b)[1];
            int count = (int) bins.get(b)[2];
            boolean singleValue = lo == hi;
            boolean lastBin = b == bins.size() - 1;

            FinalGraphNode n = new FinalGraphNode();
            n.id = "b:" + pathKey(path) + ":" + c.name + ":" + b;
            n.kind = "bin";
            n.parent = parentId;
            n.label = singleValue ? fmt(lo) : fmt(lo) + " – " + fmt(hi);
            n.n = count;
            n.share = (double) count / x.length;
            n.sub = pct(n.share);

            double[] ci = FinalAnalitikMathematics.wilson(count, x.length);
            String interval = singleValue ? "= " + fmt(lo) : "[" + fmt(lo) + "; " + fmt(hi) + (lastBin ? "]" : ")");
            n.stat("Интервал", interval)
                    .stat("Наблюдений", fmtInt(count))
                    .stat("Доля", pct(n.share))
                    .stat("95% ДИ доли (Уилсон)", pct(ci[0]) + " – " + pct(ci[1]));
            p.nodes.add(n);
            p.links.add(new FinalGraphLink(parentId, n.id, "tree", pct(n.share) + " · " + fmtInt(count)));
        }
    }

    static void expandFunnel(FinalDataset ds, List<FinalGraphFilter> path, int[] rows, String parentId, FinalGraphPayload p) {
        FinalColumn funnel = ds.col(ds.funnelColumn);
        if (funnel == null) return;

        int[] reached = funnelReached(funnel, rows);
        int[] othersReached = path.isEmpty() ? null : funnelReached(funnel, FinalRows.restOf(ds, path));
        int worst = worstStep(reached);
        String key = pathKey(path);

        String prevId = parentId;
        for (int k = 0; k < reached.length; k++) {
            FinalGraphNode n = new FinalGraphNode();
            n.id = "s:" + key + ":" + k;
            n.kind = "stage";
            n.parent = prevId;
            n.n = reached[k];
            n.label = funnel.levels.get(k);
            n.share = reached[0] == 0 ? 0 : (double) reached[k] / reached[0];
            n.sub = "дошли " + pct(n.share);

            double[] ci = FinalAnalitikMathematics.wilson(reached[k], reached[0]);
            n.stat("Дошли до этапа", fmtInt(reached[k]))
                    .stat("Доля от входа", pct(n.share))
                    .stat("95% ДИ (Уилсон)", pct(ci[0]) + " – " + pct(ci[1]));

            String label;
            if (k == 0) {
                label = "вход · " + fmtInt(reached[0]);
            } else {
                double conversion = reached[k - 1] == 0 ? 0 : (double) reached[k] / reached[k - 1];
                int lost = reached[k - 1] - reached[k];
                double[] stepCi = FinalAnalitikMathematics.wilson(reached[k], reached[k - 1]);
                n.stat("Конверсия шага", pct(conversion))
                        .stat("95% ДИ шага", pct(stepCi[0]) + " – " + pct(stepCi[1]))
                        .stat("Потеряно на шаге", fmtInt(lost));
                label = "→ " + pct(conversion) + " (−" + fmtInt(lost) + ")";
            }

            if (othersReached != null && othersReached[0] > 0 && k > 0) {
                FinalTwoProportionTest t = new FinalTwoProportionTest(reached[k], reached[0], othersReached[k], othersReached[0]);
                n.stat("У остальных", pct(t.pB))
                        .stat("Разница", sgnPP(t.pA - t.pB) + " " + FinalAnalitikMathematics.stars(t.p))
                        .stat("z-тест", "z = " + fmt(t.stat) + ", p " + fmtPq(t.p))
                        .stat("h Коэна", fmt(t.effect));
                if (t.p < 0.05) {
                    n.level = t.level;
                    n.direction = t.effect >= 0 ? "positive" : "negative";
                }
            }
            p.nodes.add(n);

            FinalGraphLink link = new FinalGraphLink(prevId, n.id, k == 0 ? "tree" : "flow", label);
            if (k == worst) {
                double conversion = (double) reached[k] / reached[k - 1];
                link.level = "strong";
                link.direction = "negative";
                link.text = "Самый большой отвал в воронке";
                n.text = "⚠ Самый большой отвал воронки: конверсия шага " + pct(conversion) + ".";
            }
            p.links.add(link);
            prevId = n.id;
        }
    }

    static FinalGraphNode dimNode(FinalDataset ds, List<FinalGraphFilter> path, int[] rows, int[] rest, FinalColumn c, String parentId) {
        FinalGraphNode n = new FinalGraphNode();
        n.id = "d:" + pathKey(path) + ":" + c.name;
        n.kind = c.type == EnumColumnType.DATE ? "time" : "dim";
        n.column = c.name;
        n.label = c.name;
        n.parent = parentId;
        n.n = rows.length;
        n.expandable = true;
        n.ref = ref("dim", path, c.name, n.id);

        Map<String, Integer> counts = countByLevel(c, rows);
        int total = sum(counts);
        int groups = counts.size();
        String granularity = c.type == EnumColumnType.DATE ? granularityRu(c.granularity) : "";
        n.sub = groups + " " + plural(groups, "группа", "группы", "групп") + granularity;

        n.barsTitle = "Распределение (95% ДИ Уилсона)";
        n.bars = new ArrayList<>();
        double entropy = 0;
        for (String value : displayOrder(ds, c, counts)) {
            int count = counts.getOrDefault(value, 0);
            if (count == 0) continue;
            double share = (double) count / total;
            double[] ci = FinalAnalitikMathematics.wilson(count, total);
            n.bars.add(new FinalGraphBar(value, share, ci[0], ci[1], pct(share) + " · " + fmtInt(count)));
            entropy -= share * Math.log(share);
        }
        n.stat("Тип", c.type == EnumColumnType.DATE ? "время" + granularity : "категориальное")
                .stat("Групп", groups)
                .stat("Заполнено", fmtInt(total) + " из " + fmtInt(rows.length))
                .stat("Нормированная энтропия", groups > 1 ? fmt(entropy / Math.log(groups)) : "—");

        if (rest != null && rest.length > 0) {
            describeSegmentDifference(n, c, rows, rest);
        } else if (!ds.kpis.isEmpty()) {
            FinalColumn kpi = ds.col(ds.kpis.get(0));
            FinalAnovaTest a = new FinalAnovaTest(FinalRows.labels(c, rows), FinalRows.values(kpi, rows));
            if (a.groups.size() >= 2) {
                n.stat("Влияние на «" + kpi.name + "»", "F = " + fmt(a.stat) + ", p " + fmtPq(a.p))
                        .stat("η² / ω²", fmt(a.eta2) + " / " + fmt(a.omega2))
                        .stat("Краскел–Уоллис", "H = " + fmt(a.kwH) + ", p " + fmtPq(a.kwP));
            }
        }
        return n;
    }

    private static void describeSegmentDifference(FinalGraphNode n, FinalColumn c, int[] rows, int[] rest) {
        FinalChiSquareTest chi = segmentVsRest(c, rows, rest);
        if (chi.observed == null) return;

        n.stat("Отличие сегмента от остальных",
                        "χ² = " + fmt(chi.stat) + ", df = " + (int) chi.df1 + ", p " + fmtPq(chi.p))
                .stat("V Крамера", fmt(chi.effect) + " (" + levelRu(chi.level) + ")");
        if (chi.p < 0.05) {
            n.level = chi.level;
            n.sub += " · V=" + fmt(chi.effect);
            n.text = "Распределение «" + c.name + "» в этом сегменте статистически отличается от остальных.";
        } else {
            n.text = "Распределение «" + c.name + "» в сегменте не отличается от остальных значимо.";
        }
    }

    static FinalGraphNode metricNode(FinalDataset ds, List<FinalGraphFilter> path, int[] rows, FinalColumn c, String parentId) {
        FinalGraphNode n = new FinalGraphNode();
        n.id = "m:" + pathKey(path) + ":" + c.name;
        n.kind = "metric";
        n.column = c.name;
        n.label = c.name;
        n.parent = parentId;
        n.expandable = true;
        n.ref = ref("metric", path, c.name, n.id);

        double[] x = FinalAnalitikMathematics.clean(FinalRows.values(c, rows));
        n.n = x.length;
        if (x.length == 0) {
            n.sub = "нет данных";
            return n;
        }

        double[] s = FinalAnalitikMathematics.sorted(x);
        double[] ci = FinalAnalitikMathematics.meanCI(x);
        double q1 = FinalAnalitikMathematics.quantile(s, .25);
        double q3 = FinalAnalitikMathematics.quantile(s, .75);
        double iqr = q3 - q1;
        int outliers = 0;
        for (double v : x) {
            if (v < q1 - 1.5 * iqr || v > q3 + 1.5 * iqr) outliers++;
        }
        double mean = FinalAnalitikMathematics.mean(x);
        boolean isKpi = ds.kpis.contains(c.name);

        n.sub = "x̄ = " + fmt(mean);
        if (c.countLike) {
            n.sub += " · >0 у " + pct((double) FinalTwoProportionTest.countPositive(x) / x.length);
        }
        if (isKpi) n.level = "kpi";

        n.stat("Наблюдений", fmtInt(x.length))
                .stat("Среднее", fmt(mean))
                .stat("95% ДИ среднего", fmt(ci[0]) + " – " + fmt(ci[1]))
                .stat("Медиана", fmt(FinalAnalitikMathematics.quantile(s, .5)))
                .stat("Q1 – Q3 (IQR)", fmt(q1) + " – " + fmt(q3) + " (" + fmt(iqr) + ")")
                .stat("Ст. отклонение", fmt(FinalAnalitikMathematics.sd(x)))
                .stat("Мин – Макс", fmt(s[0]) + " – " + fmt(s[s.length - 1]))
                .stat("Асимметрия", fmt(FinalAnalitikMathematics.skewness(x)))
                .stat("Эксцесс", fmt(FinalAnalitikMathematics.kurtosisExcess(x)))
                .stat("Выбросы (Тьюки)", fmtInt(outliers) + " (" + pct((double) outliers / x.length) + ")");
        double jb = FinalAnalitikMathematics.jarqueBeraP(x);
        if (!Double.isNaN(jb)) {
            String verdict = jb < 0.05 ? " — не нормальное" : " — не отвергается";
            n.stat("Нормальность (Жарк–Бера)", "p " + fmtPq(jb) + verdict);
        }
        if (isKpi) n.stat("Роль", "ключевая метрика (KPI)");

        n.barsTitle = "Гистограмма";
        n.bars = new ArrayList<>();
        for (double[] bin : FinalAnalitikMathematics.histogram(x)) {
            String label = bin[0] == bin[1] ? fmt(bin[0]) : fmt(bin[0]) + "–" + fmt(bin[1]);
            double share = bin[2] / x.length;
            n.bars.add(new FinalGraphBar(label, share, 0, 0, pct(share)));
        }
        return n;
    }

    private static FinalGraphNode segmentNode(FinalDataset ds, List<FinalGraphFilter> path, int[] parentRows, FinalColumn c, String value,
                             int count, int total, String parentId) {
        FinalGraphNode n = new FinalGraphNode();
        n.id = segmentId(path);
        n.kind = "cat";
        n.column = c.name;
        n.value = value;
        n.label = value;
        n.parent = parentId;
        n.n = count;
        n.share = (double) count / total;
        n.expandable = count >= 3;
        n.ref = ref("cat", path, c.name, n.id);
        n.sub = pct(n.share) + " · " + fmtInt(count);

        double[] ci = FinalAnalitikMathematics.wilson(count, total);
        n.stat("Размер сегмента", fmtInt(count))
                .stat("Доля", pct(n.share))
                .stat("95% ДИ доли (Уилсон)", pct(ci[0]) + " – " + pct(ci[1]));

        if (path.size() > 1) {
            int globalCount = 0;
            int globalTotal = 0;
            for (String x : c.cat) {
                if (x == null) continue;
                globalTotal++;
                if (x.equals(value)) globalCount++;
            }
            double globalShare = (double) globalCount / globalTotal;
            n.stat("Доля во всём наборе", pct(globalShare))
                    .stat("Индекс представленности", fmt(n.share / globalShare) + "×");
        }
        n.stat("Путь", path.stream().map(f -> f.column + " = " + f.value).collect(Collectors.joining(" → ")));

        addMetricEffects(ds, n, c, value, parentRows);
        return n;
    }

    private static void addMetricEffects(FinalDataset ds, FinalGraphNode n, FinalColumn c, String value, int[] parentRows) {
        int[] segment = FinalRows.where(parentRows, c, value);
        int[] rest = FinalRows.whereNot(parentRows, c, value);

        List<FinalWelchTest> tests = new ArrayList<>();
        List<FinalColumn> metrics = new ArrayList<>();
        for (FinalColumn m : ds.columns) {
            if (m.type != EnumColumnType.NUMERIC) continue;
            double[] a = FinalAnalitikMathematics.clean(FinalRows.values(m, segment));
            double[] b = FinalAnalitikMathematics.clean(FinalRows.values(m, rest));
            if (a.length < 2 || b.length < 2) continue;
            tests.add(new FinalWelchTest(a, b));
            metrics.add(m);
        }
        FinalMultipleComparisons.applyFdr(tests);

        Integer[] order = new Integer[tests.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Double.compare(Math.abs(tests.get(y).effect), Math.abs(tests.get(x).effect)));

        FinalGraphTable table = new FinalGraphTable("Метрики сегмента vs остальные (Уэлч, FDR)",
                "Метрика", "Сегмент", "Остальные", "Δ", "g", "q");
        n.effects = new ArrayList<>();
        String strongest = "none";
        for (int i : order) {
            FinalWelchTest w = tests.get(i);
            FinalColumn m = metrics.get(i);
            String lift = Double.isNaN(w.lift) ? "—" : sgnPct(w.lift);
            if (table.rows.size() < 12) {
                table.row(m.name, fmt(w.meanA), fmt(w.meanB), lift, fmt(w.effect), fmtP(w.q) + " " + FinalAnalitikMathematics.stars(w.q));
            }
            if (w.significant() && n.effects.size() < 6) {
                n.effects.add(effect(c, value, m, w));
                if (levelRank(w.level) > levelRank(strongest)) strongest = w.level;
            }
        }
        if (!table.rows.isEmpty()) n.table = table;
        n.level = strongest;
    }

    private static FinalGraphEffect effect(FinalColumn c, String value, FinalColumn metric, FinalWelchTest w) {
        FinalGraphEffect e = new FinalGraphEffect();
        e.target = "m::" + metric.name;
        e.level = w.level;
        e.direction = w.effect >= 0 ? "positive" : "negative";
        e.effect = w.effect;
        e.q = w.q;
        e.label = Double.isNaN(w.lift) ? "g=" + fmt(w.effect) : sgnPct(w.lift);
        e.text = "«" + c.name + " = " + value + "»: «" + metric.name + "» " + (w.effect >= 0 ? "выше" : "ниже")
                + " остальных — " + fmt(w.meanA) + " vs " + fmt(w.meanB)
                + " (g Хеджеса = " + fmt(w.effect) + ", q = " + fmtP(w.q) + ")";
        return e;
    }

    private static FinalGraphNode metricLeaf(String pathKey, MetricTest mt, FinalGraphFilter segment, String parentId, boolean hasRest) {
        FinalColumn m = mt.column;
        AbstractSegmentComparisonTest t = mt.test;
        boolean significant = hasRest && t.q < 0.05;

        FinalGraphNode n = new FinalGraphNode();
        n.id = "k:" + pathKey + ":" + m.name;
        n.kind = "kpi";
        n.column = m.name;
        n.parent = parentId;
        n.n = mt.segment.length;
        n.label = m.name;
        n.expandable = false;
        n.direction = t.effect >= 0 ? "positive" : "negative";
        if (!significant) {
            n.level = "none";
        } else {
            n.level = "none".equals(t.level) ? "weak" : t.level;
        }
        String stars = significant ? " " + FinalAnalitikMathematics.stars(t.q) : "";

        n.sub = t.leafSubtitle(m.name, hasRest) + stars;
        n.stats.addAll(t.leafStats(m.name, mt.segment, mt.others, hasRest));

        if (hasRest) {
            String df = Double.isNaN(t.df1) ? "" : ", df = " + fmt(t.df1);
            String ci = t.effectIntervalShown() ? "  [" + fmt(t.lo) + "; " + fmt(t.hi) + "]" : "";
            n.stat("Тест", t.test)
                    .stat("Статистика", t.statisticSymbol() + fmt(t.stat) + df)
                    .stat("p / q (FDR)", fmtP(t.p) + " / " + fmtP(t.q))
                    .stat("Размер эффекта", t.effectName + " = " + fmt(t.effect) + ci + " — " + levelRu(t.level));
            if (significant) {
                n.text = "Сегмент «" + segment.column + " = " + segment.value + "» значимо "
                        + (t.effect >= 0 ? "превосходит" : "уступает") + " остальных по «" + m.name + "» ("
                        + levelRu(t.level) + " эффект).";
            } else {
                n.text = "Отличие от остальных статистически не подтверждено (q ≥ 0,05) — возможно, случайность.";
            }
        }
        return n;
    }
}
