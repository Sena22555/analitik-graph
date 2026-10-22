package com.analytics.brain.analysis;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;
import com.analytics.brain.query.FinalQueryEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.analytics.brain.text.FinalFormatRu.fmt;
import static com.analytics.brain.text.FinalFormatRu.pct;

public final class FinalQualityAnalysis extends AbstractAnalitikAnalysis {

    public FinalQualityAnalysis(FinalDataset ds) {
        super(ds);
    }

    @Override
    protected void compute(Map<String, Object> result) {
        Set<String> seen = new HashSet<>();
        int duplicates = 0;
        for (String[] record : ds.records) {
            if (!seen.add(String.join("\u0001", record))) duplicates++;
        }

        List<List<Object>> rows = new ArrayList<>();
        int columnsWithIssues = 0;
        for (FinalColumn c : ds.columns) {
            List<String> notes = notes(c);
            double missingShare = (double) c.missing / ds.rows;
            if (!notes.isEmpty() && c.type != EnumColumnType.ID) columnsWithIssues++;
            rows.add(Arrays.asList(c.name, c.typeRu(), c.unique, FinalQueryEngine.num(missingShare),
                    notes.isEmpty() ? "ок" : String.join("; ", notes)));
        }

        String summary = ds.rows + " строк × " + ds.columns.size() + " колонок; полных дубликатов строк: " + duplicates
                + "; колонок с замечаниями: " + columnsWithIssues + ".";
        result.put("title", "Аудит качества данных");
        result.put("summary", summary);
        result.put("head", List.of("Колонка", "Тип", "Уникальных", "Пропуски", "Замечания"));
        result.put("formats", List.of("text", "text", "int", "pct", "text"));
        result.put("rows", rows);
    }

    private List<String> notes(FinalColumn c) {
        List<String> notes = new ArrayList<>();
        double missingShare = (double) c.missing / ds.rows;
        if (missingShare > 0) notes.add("пропуски " + pct(missingShare));
        if (c.unique <= 1) notes.add("константа — не несёт информации");

        if (c.type == EnumColumnType.NUMERIC) {
            double[] x = FinalAnalitikMathematics.clean(c.num);
            double[] s = FinalAnalitikMathematics.sorted(x);
            if (s.length > 0) {
                double q1 = FinalAnalitikMathematics.quantile(s, .25);
                double q3 = FinalAnalitikMathematics.quantile(s, .75);
                double iqr = q3 - q1;
                int extremes = 0;
                for (double v : x) {
                    if (v < q1 - 3 * iqr || v > q3 + 3 * iqr) extremes++;
                }
                if (extremes > 0) notes.add("экстремумы (3×IQR): " + extremes);
                boolean shouldBePositive = c.name.toLowerCase(Locale.ROOT).matches(".*(выручк|цен|сумм|кол|покуп|заказ).*");
                if (s[0] < 0 && shouldBePositive) notes.add("отрицательные значения");
                double skew = FinalAnalitikMathematics.skewness(x);
                if (Math.abs(skew) > 2) notes.add("сильная асимметрия (" + fmt(skew) + ") — используйте медиану");
            }
        }
        if (c.type == EnumColumnType.CATEGORICAL && c.levels != null && c.levels.contains("Другое")) {
            notes.add("много редких значений — объединены в «Другое»");
        }
        if (c.type == EnumColumnType.TEXT) notes.add("свободный текст — исключён из анализа");
        return notes;
    }
}
