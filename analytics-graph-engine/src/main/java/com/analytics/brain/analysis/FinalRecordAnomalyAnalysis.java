package com.analytics.brain.analysis;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalRows;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;
import com.analytics.brain.query.FinalCondition;
import com.analytics.brain.query.FinalQueryEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.analytics.brain.text.FinalFormatRu.fmt;
import static com.analytics.brain.text.FinalFormatRu.pct;

public final class FinalRecordAnomalyAnalysis extends AbstractAnomalyAnalysis {

    private static final double TUKEY_K = 3;

    public FinalRecordAnomalyAnalysis(FinalDataset ds, String metric, List<FinalCondition> filters) {
        super(ds, metric, filters);
    }

    @Override
    protected void findAnomalies(FinalColumn m, int[] idx, List<List<Object>> rows, Map<String, Object> result) {
        double[] x = FinalAnalitikMathematics.clean(FinalRows.values(m, idx));
        double[] s = FinalAnalitikMathematics.sorted(x);
        double q1 = FinalAnalitikMathematics.quantile(s, .25);
        double q3 = FinalAnalitikMathematics.quantile(s, .75);
        double iqr = q3 - q1;
        double lo = q1 - TUKEY_K * iqr;
        double hi = q3 + TUKEY_K * iqr;
        double median = FinalAnalitikMathematics.quantile(s, .5);

        String idColumn = ds.columns.stream().filter(c -> c.type == EnumColumnType.ID).map(c -> c.name).findFirst().orElse("");
        int j = ds.header.indexOf(idColumn);

        List<Integer> hits = new ArrayList<>();
        for (int i : idx) {
            double v = m.num[i];
            if (!Double.isNaN(v) && (v < lo || v > hi)) hits.add(i);
        }
        hits.sort((a, b) -> Double.compare(Math.abs(m.num[b] - median), Math.abs(m.num[a] - median)));
        for (int i : hits.subList(0, Math.min(30, hits.size()))) {
            String name = j >= 0 ? ds.records.get(i)[j] : "строка " + (i + 1);
            rows.add(Arrays.asList(name, FinalQueryEngine.num(m.num[i]), m.num[i] > hi ? "выше нормы" : "ниже нормы"));
        }

        result.put("title", "Экстремальные значения «" + m.name + "»");
        result.put("head", List.of("Запись", m.name, "Статус"));
        result.put("formats", List.of("text", "num", "text"));
        result.put("summary", "Правило Тьюки с жёстким порогом 3×IQR: норма [" + fmt(lo) + "; " + fmt(hi) + "], вне нормы "
                + hits.size() + " из " + x.length + " (" + pct((double) hits.size() / Math.max(1, x.length)) + ").");
    }
}
