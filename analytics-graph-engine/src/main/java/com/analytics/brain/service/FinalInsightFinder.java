package com.analytics.brain.service;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalRows;
import com.analytics.brain.graph.FinalGraphInsight;
import com.analytics.brain.graph.FinalGraphLink;
import com.analytics.brain.mathematics.AbstractAnalitikMathematicsTest;
import com.analytics.brain.mathematics.AbstractSegmentComparisonTest;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;
import com.analytics.brain.mathematics.FinalMultipleComparisons;
import com.analytics.brain.mathematics.FinalSpearmanTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.analytics.brain.service.FinalGraphPaths.*;
import static com.analytics.brain.text.FinalFormatRu.*;

public final class FinalInsightFinder {

    private FinalInsightFinder() {
    }

    private static final class SegmentFinding {

        final FinalColumn kpi;
        final FinalColumn dimension;
        final String value;
        final AbstractSegmentComparisonTest test;

        SegmentFinding(FinalColumn kpi, FinalColumn dimension, String value, AbstractSegmentComparisonTest test) {
            this.kpi = kpi;
            this.dimension = dimension;
            this.value = value;
            this.test = test;
        }
    }

    static List<FinalGraphInsight> insights(FinalDataset ds, int[] all, List<FinalGraphLink> pairs) {
        List<FinalGraphInsight> out = new ArrayList<>();
        addSegmentInsights(ds, all, out);
        addFunnelInsight(ds, all, out);
        addLinkInsights(ds, pairs, out);
        addTrendInsights(ds, out);
        addQualityInsights(ds, out);
        return out;
    }

    private static void addSegmentInsights(FinalDataset ds, int[] all, List<FinalGraphInsight> out) {
        List<SegmentFinding> found = new ArrayList<>();
        List<AbstractSegmentComparisonTest> tests = new ArrayList<>();
        for (String kpiName : ds.kpis.subList(0, Math.min(3, ds.kpis.size()))) {
            FinalColumn kpi = ds.col(kpiName);
            for (FinalColumn d : ds.columns) {
                boolean skip = d.type != EnumColumnType.CATEGORICAL
                        || d.name.equals(ds.funnelColumn)
                        || ds.derivedFrom.getOrDefault(d.name, Set.of()).contains(kpiName);
                if (skip) continue;
                for (String value : d.levels) {
                    int[] segment = FinalRows.where(all, d, value);
                    if (segment.length < 10) continue;
                    int[] rest = FinalRows.whereNot(all, d, value);
                    double[] a = FinalAnalitikMathematics.clean(FinalRows.values(kpi, segment));
                    double[] b = FinalAnalitikMathematics.clean(FinalRows.values(kpi, rest));
                    if (a.length < 5 || b.length < 5) continue;
                    AbstractSegmentComparisonTest t = AbstractSegmentComparisonTest.compare(kpi.countLike, a, b);
                    found.add(new SegmentFinding(kpi, d, value, t));
                    tests.add(t);
                }
            }
        }
        FinalMultipleComparisons.applyFdr(tests);

        found.stream()
                .filter(f -> f.test.significant())
                .sorted((x, y) -> Double.compare(Math.abs(y.test.effect), Math.abs(x.test.effect)))
                .limit(8)
                .forEach(f -> out.add(segmentInsight(f)));
    }

    private static FinalGraphInsight segmentInsight(SegmentFinding f) {
        FinalGraphInsight in = new FinalGraphInsight();
        in.kind = "segment";
        in.level = f.test.level;
        in.score = Math.abs(f.test.effect);
        in.text = "«" + f.dimension.name + ": " + f.value + "» — " + f.test.insightText(f.kpi.name);
        in.refs.add("d::" + f.dimension.name);
        in.refs.add("m::" + f.kpi.name);
        return in;
    }

    private static void addFunnelInsight(FinalDataset ds, int[] all, List<FinalGraphInsight> out) {
        if (ds.funnelColumn == null) return;
        FinalColumn funnel = ds.col(ds.funnelColumn);
        int[] reached = funnelReached(funnel, all);
        int worst = worstStep(reached);
        if (worst <= 0) return;

        double conversion = (double) reached[worst] / reached[worst - 1];
        FinalGraphInsight in = new FinalGraphInsight();
        in.kind = "funnel";
        in.level = conversion < 0.5 ? "strong" : "medium";
        in.score = 1 - conversion;
        in.text = "Самый большой отвал воронки: «" + funnel.levels.get(worst - 1) + "» → «" + funnel.levels.get(worst)
                + "», проходит только " + pct(conversion) + ". До последнего этапа доходят "
                + pct((double) reached[reached.length - 1] / reached[0]) + ".";
        in.refs.add("d::" + funnel.name);
        out.add(in);
    }

    private static void addLinkInsights(FinalDataset ds, List<FinalGraphLink> pairs, List<FinalGraphInsight> out) {
        pairs.stream()
                .filter(l -> l.test.significant() && l.test.strength < 0.97)
                .filter(l -> !derivedPair(ds, l.source.substring(3), l.target.substring(3)))
                .sorted((x, y) -> Double.compare(y.test.strength, x.test.strength))
                .limit(6)
                .forEach(l -> {
                    FinalGraphInsight in = new FinalGraphInsight();
                    in.kind = "link";
                    in.level = l.test.level;
                    in.score = l.test.strength;
                    String firstSentence = l.text.split("\\. ")[0];
                    String almost = l.test.strength > 0.95 ? ", почти детерминированная" : "";
                    in.text = firstSentence + " (" + l.label + ", " + levelRuF(l.test.level) + " связь" + almost + ").";
                    in.refs.add(l.source);
                    in.refs.add(l.target);
                    out.add(in);
                });
    }

    private static void addTrendInsights(FinalDataset ds, List<FinalGraphInsight> out) {
        for (FinalColumn date : ds.columns) {
            if (date.type != EnumColumnType.DATE) continue;
            for (String kpiName : ds.kpis) {
                AbstractAnalitikMathematicsTest t = new FinalSpearmanTest(date.num, ds.col(kpiName).num);
                boolean notable = t.p < 0.01 && Math.abs(t.effect) >= 0.1;
                if (!notable) continue;
                FinalGraphInsight in = new FinalGraphInsight();
                in.kind = "trend";
                in.level = t.level;
                in.score = Math.abs(t.effect);
                in.text = "Тренд: «" + kpiName + "» " + (t.effect > 0 ? "растёт" : "снижается")
                        + " во времени (ρ = " + sgn(t.effect) + ", p " + fmtPq(t.p) + ").";
                in.refs.add("d::" + date.name);
                in.refs.add("m::" + kpiName);
                out.add(in);
            }
        }
    }

    private static void addQualityInsights(FinalDataset ds, List<FinalGraphInsight> out) {
        for (FinalColumn c : ds.columns) {
            if (!c.analyzable() || c.missing <= 0.05 * ds.rows) continue;
            FinalGraphInsight in = new FinalGraphInsight();
            in.kind = "quality";
            in.level = "weak";
            in.score = 0;
            in.text = "В «" + c.name + "» пропущено " + pct((double) c.missing / ds.rows)
                    + " значений — выводы по ней менее надёжны.";
            in.refs.add(varId(c));
            out.add(in);
        }
    }
}
