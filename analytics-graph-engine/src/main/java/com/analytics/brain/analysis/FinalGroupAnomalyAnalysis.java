package com.analytics.brain.analysis;

import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;
import com.analytics.brain.query.FinalCondition;
import com.analytics.brain.query.FinalGrouping;
import com.analytics.brain.query.FinalQueryEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class FinalGroupAnomalyAnalysis extends AbstractAnomalyAnalysis {

    private static final double MAD_TO_SIGMA = 1.4826;
    private static final double ANOMALY_Z = 3;
    private static final double WARNING_Z = 2;

    private final String by;

    public FinalGroupAnomalyAnalysis(FinalDataset ds, String metric, String by, List<FinalCondition> filters) {
        super(ds, metric, filters);
        this.by = by;
    }

    @Override
    protected void findAnomalies(FinalColumn m, int[] idx, List<List<Object>> rows, Map<String, Object> result) {
        FinalColumn g = FinalQueryEngine.need(ds, by);
        FinalGrouping groups = FinalQueryEngine.groups(ds, g, idx);
        List<String> names = new ArrayList<>();
        List<Double> means = new ArrayList<>();
        List<Integer> sizes = new ArrayList<>();
        for (String level : groups.order) {
            List<Integer> members = new ArrayList<>();
            for (int i : idx) {
                if (level.equals(groups.labels[i])) members.add(i);
            }
            if (members.isEmpty()) continue;
            names.add(level);
            means.add(FinalQueryEngine.agg(ds, m, members, "mean", idx.length));
            sizes.add(members.size());
        }

        double[] v = means.stream().mapToDouble(Double::doubleValue).toArray();
        double median = FinalAnalitikMathematics.median(v);
        double[] deviations = Arrays.stream(v).map(x -> Math.abs(x - median)).toArray();
        double mad = MAD_TO_SIGMA * FinalAnalitikMathematics.median(deviations);
        for (int i = 0; i < names.size(); i++) {
            double z = mad == 0 ? 0 : (v[i] - median) / mad;
            String status = Math.abs(z) >= ANOMALY_Z ? "аномалия" : Math.abs(z) >= WARNING_Z ? "внимание" : "";
            rows.add(Arrays.asList(names.get(i), sizes.get(i), FinalQueryEngine.num(v[i]), FinalQueryEngine.num(z), status));
        }
        rows.sort((a, b) -> Double.compare(Math.abs(FinalQueryEngine.asNumber(b.get(3))), Math.abs(FinalQueryEngine.asNumber(a.get(3)))));

        long anomalies = rows.stream().filter(r -> "аномалия".equals(r.get(4))).count();
        result.put("title", "Аномалии «" + m.name + "» по «" + g.name + "»");
        result.put("head", List.of(g.name, "n", "Среднее", "Робастный z (MAD)", "Статус"));
        result.put("formats", List.of("text", "int", "num", "num", "text"));
        result.put("summary", "Робастная z-оценка по медиане и MAD: |z| ≥ 3 — аномалия (" + anomalies
                + "), |z| ≥ 2 — зона внимания.");
    }
}
