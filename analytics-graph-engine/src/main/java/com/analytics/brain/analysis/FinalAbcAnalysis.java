package com.analytics.brain.analysis;

import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.query.FinalCondition;
import com.analytics.brain.query.FinalGrouping;
import com.analytics.brain.query.FinalQueryEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.analytics.brain.text.FinalFormatRu.fmt;

public final class FinalAbcAnalysis extends AbstractAnalitikAnalysis {

    private static final double CLASS_A_LIMIT = 0.8;
    private static final double CLASS_B_LIMIT = 0.95;

    private final String dim;
    private final String value;
    private final List<FinalCondition> filters;

    public FinalAbcAnalysis(FinalDataset ds, String dim, String value, List<FinalCondition> filters) {
        super(ds);
        this.dim = dim;
        this.value = value;
        this.filters = filters;
    }

    @Override
    protected void compute(Map<String, Object> result) {
        int[] idx = FinalQueryEngine.filter(ds, filters);
        FinalColumn d = FinalQueryEngine.need(ds, dim);
        FinalColumn v = value == null || value.isBlank() ? null : FinalQueryEngine.need(ds, value);
        FinalGrouping groups = FinalQueryEngine.groups(ds, d, idx);

        Map<String, Double> totals = new LinkedHashMap<>();
        for (int i : idx) {
            String label = groups.labels[i];
            if (label == null) continue;
            double x = v == null ? 1 : v.num[i];
            if (Double.isNaN(x)) continue;
            totals.merge(label, x, Double::sum);
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        double sum = sorted.stream().mapToDouble(Map.Entry::getValue).sum();

        List<List<Object>> rows = new ArrayList<>();
        int[] classCount = new int[3];
        List<String> categories = new ArrayList<>();
        List<Object> bars = new ArrayList<>();
        List<Object> cumulative = new ArrayList<>();
        double accumulated = 0;
        for (Map.Entry<String, Double> e : sorted) {
            double share = sum == 0 ? 0 : e.getValue() / sum;
            String cls = accumulated < CLASS_A_LIMIT ? "A" : accumulated < CLASS_B_LIMIT ? "B" : "C";
            accumulated += share;
            classCount[cls.charAt(0) - 'A']++;
            rows.add(Arrays.asList(e.getKey(), FinalQueryEngine.num(e.getValue()), FinalQueryEngine.num(share),
                    FinalQueryEngine.num(accumulated), cls));
            categories.add(e.getKey());
            bars.add(FinalQueryEngine.num(e.getValue()));
            cumulative.add(FinalQueryEngine.num(accumulated));
        }

        String valueName = v == null ? "Количество" : v.name;
        String summary = "Группа A (" + classCount[0] + " из " + sorted.size() + ") даёт ~80% «" + valueName + "», B — "
                + classCount[1] + ", C — " + classCount[2] + ". Всего: " + fmt(sum) + ".";

        result.put("title", "ABC-анализ «" + d.name + "» по «" + valueName + "»");
        result.put("summary", summary);
        result.put("head", List.of(d.name, valueName, "Доля", "Накопленная доля", "Класс"));
        result.put("formats", List.of("text", "num", "pct", "pct", "text"));
        result.put("rows", rows.size() > 60 ? rows.subList(0, 60) : rows);

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", "chart");
        chart.put("chartType", "pareto");
        chart.put("title", "Парето: «" + d.name + "»");
        chart.put("categories", first(categories, 25));
        chart.put("series", List.of(series(valueName, first(bars, 25)), series("Накопленная доля", first(cumulative, 25))));
        chart.put("format", "num");
        chart.put("xLabel", d.name);
        chart.put("yLabel", valueName);
        result.put("chart", chart);
    }
}
