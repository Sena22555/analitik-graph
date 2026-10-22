package com.analytics.brain.data;

import com.analytics.brain.mathematics.FinalAnalitikMathematics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Профиль колонок таблицы — то, что аналитик проверяет первым делом: заполненность, уникальность,
 * распределение, частые значения и проблемы данных (пропуски, константы, выбросы, колонки вне анализа).
 */
public final class FinalColumnProfile {

    private static final int BINS = 14;

    private FinalColumnProfile() {
    }

    public static List<Map<String, Object>> of(FinalDataset ds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : ds.header) out.add(column(ds, name));
        return out;
    }

    private static Map<String, Object> column(FinalDataset ds, String name) {
        FinalColumn c = ds.col(name);
        String[] raw = FinalDatasetRoles.values(ds, name);
        int n = raw.length;
        int filled = 0;
        Map<String, Integer> freq = new HashMap<>();
        for (String v : raw) {
            if (v == null) continue;
            filled++;
            freq.merge(v, 1, Integer::sum);
        }

        EnumColumnType type = c == null || c.type == null ? EnumColumnType.TEXT : c.type;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("type", type.name());
        m.put("typeRu", type.russianName());
        m.put("analyzable", type.analyzable());
        m.put("rows", n);
        m.put("filled", filled);
        m.put("filledShare", n == 0 ? 0 : (double) filled / n);
        m.put("unique", freq.size());

        List<String> issues = new ArrayList<>();
        if (n > 0 && filled < n * 0.8) issues.add("пропусков " + Math.round(100.0 * (n - filled) / n) + "%");
        if (freq.size() == 1) issues.add("одно значение на всю колонку");
        if (type == EnumColumnType.ID) issues.add("идентификатор — в статистике не участвует");
        if (type == EnumColumnType.TEXT && freq.size() > 1) issues.add("свободный текст — в статистике не участвует");

        if (type == EnumColumnType.NUMERIC && c.num != null) {
            double[] x = FinalAnalitikMathematics.sorted(FinalAnalitikMathematics.clean(c.num));
            if (x.length > 0) {
                double q1 = FinalAnalitikMathematics.quantile(x, .25);
                double q3 = FinalAnalitikMathematics.quantile(x, .75);
                m.put("min", x[0]);
                m.put("p25", q1);
                m.put("median", FinalAnalitikMathematics.quantile(x, .5));
                m.put("mean", FinalAnalitikMathematics.mean(x));
                m.put("p75", q3);
                m.put("max", x[x.length - 1]);
                double sum = 0;
                for (double v : x) sum += v;
                m.put("sum", sum);
                m.put("hist", histogram(x));
                double iqr = q3 - q1;
                if (iqr > 0) {
                    int outliers = 0;
                    for (double v : x) {
                        if (v < q1 - 3 * iqr || v > q3 + 3 * iqr) outliers++;
                    }
                    if (outliers > 0) issues.add("сильных выбросов: " + outliers);
                }
                int zeros = 0;
                for (double v : x) {
                    if (v == 0) zeros++;
                }
                m.put("zeros", zeros);
            }
        } else if (type == EnumColumnType.DATE && c.num != null) {
            double[] x = FinalAnalitikMathematics.sorted(FinalAnalitikMathematics.clean(c.num));
            if (x.length > 0) {
                m.put("from", LocalDate.ofEpochDay((long) x[0]).toString());
                m.put("to", LocalDate.ofEpochDay((long) x[x.length - 1]).toString());
                m.put("days", (long) (x[x.length - 1] - x[0]) + 1);
                m.put("hist", histogram(x));
            }
        }

        if (type != EnumColumnType.NUMERIC) {
            List<Map.Entry<String, Integer>> top = new ArrayList<>(freq.entrySet());
            top.sort((a, b) -> b.getValue() - a.getValue());
            List<Map<String, Object>> values = new ArrayList<>();
            for (Map.Entry<String, Integer> e : top.subList(0, Math.min(6, top.size()))) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("value", e.getKey());
                v.put("count", e.getValue());
                v.put("share", filled == 0 ? 0 : (double) e.getValue() / filled);
                values.add(v);
            }
            m.put("top", values);
            m.put("other", Math.max(0, freq.size() - values.size()));
        }
        m.put("issues", issues);
        return m;
    }

    /** Гистограмма с равными интервалами: [{lo, hi, count}]. */
    private static List<double[]> histogram(double[] sorted) {
        List<double[]> out = new ArrayList<>();
        double lo = sorted[0];
        double hi = sorted[sorted.length - 1];
        if (hi == lo) {
            out.add(new double[]{lo, hi, sorted.length});
            return out;
        }
        int bins = Math.min(BINS, Math.max(4, (int) Math.sqrt(sorted.length)));
        double w = (hi - lo) / bins;
        double[] counts = new double[bins];
        for (double v : sorted) counts[Math.min(bins - 1, (int) ((v - lo) / w))]++;
        for (int i = 0; i < bins; i++) out.add(new double[]{lo + i * w, lo + (i + 1) * w, counts[i]});
        return out;
    }
}
