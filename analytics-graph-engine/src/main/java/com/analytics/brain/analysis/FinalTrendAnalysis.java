package com.analytics.brain.analysis;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.exception.ExceptionAnalitikData;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;
import com.analytics.brain.mathematics.FinalProbabilityDistributions;
import com.analytics.brain.query.FinalCondition;
import com.analytics.brain.query.FinalQueryEngine;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.analytics.brain.text.FinalFormatRu.fmt;
import static com.analytics.brain.text.FinalFormatRu.fmtPq;
import static com.analytics.brain.text.FinalFormatRu.sgn;

public final class FinalTrendAnalysis extends AbstractAnalitikAnalysis {

    private final String metric;
    private final String agg;
    private final String dateCol;
    private final String granularity;
    private final int horizon;
    private final List<FinalCondition> filters;

    public FinalTrendAnalysis(FinalDataset ds, String metric, String agg, String dateCol, String granularity,
                              int horizon, List<FinalCondition> filters) {
        super(ds);
        this.metric = metric;
        this.agg = agg;
        this.dateCol = dateCol;
        this.granularity = granularity;
        this.horizon = horizon;
        this.filters = filters;
    }

    @Override
    protected void compute(Map<String, Object> result) {
        FinalColumn date = dateCol != null && !dateCol.isBlank()
                ? FinalQueryEngine.need(ds, dateCol)
                : ds.columns.stream().filter(c -> c.type == EnumColumnType.DATE).findFirst().orElse(null);
        if (date == null || date.type != EnumColumnType.DATE) throw new ExceptionAnalitikData("В данных нет колонки с датой");
        FinalColumn m = metric == null || metric.isBlank() ? null : FinalQueryEngine.need(ds, metric);
        String aggName = agg != null ? agg : m == null ? "count" : "sum";
        int[] idx = FinalQueryEngine.filter(ds, filters);
        String step = granularity == null || granularity.isBlank() ? date.granularity : granularity;

        TreeMap<Long, List<Integer>> periods = new TreeMap<>();
        Map<Long, String> labels = new HashMap<>();
        for (int i : idx) {
            if (Double.isNaN(date.num[i])) continue;
            LocalDate d = LocalDate.ofEpochDay((long) date.num[i]);
            long key = periodKey(d, step);
            periods.computeIfAbsent(key, z -> new ArrayList<>()).add(i);
            labels.put(key, periodLabel(d, step));
        }
        if (periods.size() < 3) {
            throw new ExceptionAnalitikData("Слишком мало периодов (" + periods.size() + ") — выберите более мелкую гранулярность");
        }

        List<String> categories = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Map.Entry<Long, List<Integer>> e : periods.entrySet()) {
            categories.add(labels.get(e.getKey()));
            values.add(FinalQueryEngine.agg(ds, m, e.getValue(), aggName, idx.length));
        }

        int k = values.size();
        double[] x = new double[k];
        double[] yv = new double[k];
        for (int i = 0; i < k; i++) {
            x[i] = i;
            yv[i] = values.get(i);
        }
        double mx = FinalAnalitikMathematics.mean(x);
        double my = FinalAnalitikMathematics.mean(yv);
        double sxx = 0;
        double sxy = 0;
        for (int i = 0; i < k; i++) {
            sxx += (x[i] - mx) * (x[i] - mx);
            sxy += (x[i] - mx) * (yv[i] - my);
        }
        double slope = sxx == 0 ? 0 : sxy / sxx;
        double intercept = my - slope * mx;
        double sse = 0;
        for (int i = 0; i < k; i++) {
            double residual = yv[i] - (intercept + slope * x[i]);
            sse += residual * residual;
        }
        double s2 = k > 2 ? sse / (k - 2) : 0;
        double seSlope = sxx == 0 ? 0 : Math.sqrt(s2 / sxx);
        double t = seSlope == 0 ? 0 : slope / seSlope;
        double pSlope = FinalProbabilityDistributions.tP(t, k - 2);
        double sst = 0;
        for (double v : yv) sst += (v - my) * (v - my);
        double r2 = sst == 0 ? 0 : 1 - sse / sst;
        double tq = FinalProbabilityDistributions.tQuantile(Math.max(1, k - 2), 0.975);
        int h = Math.max(0, Math.min(horizon <= 0 ? 3 : horizon, 12));

        List<List<Object>> rows = new ArrayList<>();
        List<Object> fact = new ArrayList<>();
        List<Object> forecast = new ArrayList<>();
        List<Object> movingAvg = new ArrayList<>();
        for (int i = 0; i < k; i++) {
            Double change = i == 0 || yv[i - 1] == 0 ? null : FinalQueryEngine.num((yv[i] - yv[i - 1]) / Math.abs(yv[i - 1]));
            Double avg3 = i < 2 ? null : FinalQueryEngine.num((yv[i] + yv[i - 1] + yv[i - 2]) / 3);
            rows.add(Arrays.asList(categories.get(i), FinalQueryEngine.num(yv[i]), change, avg3, "факт"));
            fact.add(FinalQueryEngine.num(yv[i]));
            forecast.add(i == k - 1 ? FinalQueryEngine.num(yv[i]) : null);
            movingAvg.add(avg3);
        }

        List<String> allCategories = new ArrayList<>(categories);
        for (int j = 1; j <= h; j++) {
            double xi = k - 1 + j;
            double predicted = intercept + slope * xi;
            double halfWidth = tq * Math.sqrt(s2 * (1 + 1.0 / k + (xi - mx) * (xi - mx) / sxx));
            String label = "+" + j;
            allCategories.add(label);
            rows.add(Arrays.asList(label, FinalQueryEngine.num(predicted), null, null, "прогноз ±" + fmt(halfWidth)));
            fact.add(null);
            forecast.add(FinalQueryEngine.num(predicted));
            movingAvg.add(null);
        }

        String direction = pSlope < 0.05
                ? (slope > 0 ? "значимый рост" : "значимое снижение")
                : "статистически значимого тренда нет";
        String summary = "Линейный тренд: " + direction + " (наклон " + sgn(slope) + " за период, p " + fmtPq(pSlope)
                + ", R² = " + fmt(r2) + "). Прогноз на " + h + " пер. — линейная экстраполяция с 95% интервалом "
                + "предсказания; используйте как ориентир, не как план.";
        String valueName = FinalQueryEngine.AGG_RU.getOrDefault(aggName, aggName)
                + (m != null && !aggName.equals("count") ? " «" + m.name + "»" : "");

        result.put("title", "Динамика: " + valueName);
        result.put("summary", summary);
        result.put("head", List.of("Период", valueName, "Изм. к пред.", "Скольз. среднее (3)", "Тип"));
        result.put("formats", List.of("text", FinalQueryEngine.aggFmt(aggName, m), "pct", FinalQueryEngine.aggFmt(aggName, m), "text"));
        result.put("rows", rows);

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", "chart");
        chart.put("chartType", "line");
        chart.put("title", valueName + " по периодам");
        chart.put("categories", allCategories);
        chart.put("series", List.of(series("Факт", fact), series("Прогноз", forecast), series("Скольз. среднее", movingAvg)));
        chart.put("format", FinalQueryEngine.aggFmt(aggName, m));
        chart.put("xLabel", "Период");
        chart.put("yLabel", valueName);
        result.put("chart", chart);
    }

    private static long periodKey(LocalDate d, String step) {
        switch (step) {
            case "day":
                return d.toEpochDay();
            case "week":
                return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay();
            case "year":
                return d.getYear();
            case "quarter":
                return d.getYear() * 4L + (d.getMonthValue() - 1) / 3;
            default:
                return d.getYear() * 12L + d.getMonthValue();
        }
    }

    private static String periodLabel(LocalDate d, String step) {
        DateTimeFormatter dayMonth = DateTimeFormatter.ofPattern("dd.MM");
        switch (step) {
            case "day":
                return d.format(dayMonth);
            case "week":
                return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).format(dayMonth);
            case "year":
                return String.valueOf(d.getYear());
            case "quarter":
                return "Q" + ((d.getMonthValue() - 1) / 3 + 1) + " " + d.getYear();
            default:
                return String.format("%02d.%d", d.getMonthValue(), d.getYear());
        }
    }
}
