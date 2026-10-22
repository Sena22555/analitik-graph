package com.analytics.brain.query;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataLoader;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalRows;
import com.analytics.brain.exception.ExceptionAnalitikData;
import com.analytics.brain.formula.FinalFormulaParser;
import com.analytics.brain.formula.InterfaceFormulaNode;
import com.analytics.brain.mathematics.AbstractAnalitikMathematicsTest;
import com.analytics.brain.mathematics.EnumEffectScale;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;
import com.analytics.brain.mathematics.FinalAnovaTest;
import com.analytics.brain.mathematics.FinalChiSquareTest;
import com.analytics.brain.mathematics.FinalMultipleComparisons;
import com.analytics.brain.mathematics.FinalSpearmanTest;
import com.analytics.brain.mathematics.FinalWelchTest;
import com.analytics.brain.service.FinalGraphPaths;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.analytics.brain.text.FinalFormatRu.*;

public final class FinalQueryEngine {

    public static final Map<String, String> AGG_RU = new LinkedHashMap<>();

    static {
        AGG_RU.put("count", "Количество");
        AGG_RU.put("share", "Доля");
        AGG_RU.put("sum", "Сумма");
        AGG_RU.put("mean", "Среднее");
        AGG_RU.put("median", "Медиана");
        AGG_RU.put("min", "Минимум");
        AGG_RU.put("max", "Максимум");
        AGG_RU.put("rate", "Доля > 0");
        AGG_RU.put("distinct", "Уникальных");
    }

    private FinalQueryEngine() {
    }

    public static int[] filter(FinalDataset ds, List<FinalCondition> conds) {
        int[] rows = FinalRows.all(ds);
        if (conds == null) return rows;
        for (FinalCondition cond : conds) {
            if (cond == null || cond.column == null) continue;
            FinalColumn col = ds.col(cond.column);
            if (col == null) {
                throw new ExceptionAnalitikData("Нет колонки «" + cond.column + "». Доступны: "
                        + String.join(", ", ds.header));
            }
            int j = ds.header.indexOf(cond.column);
            rows = Arrays.stream(rows).filter(i -> matches(ds, col, j, i, cond)).toArray();
        }
        return rows;
    }

    public static boolean matches(FinalDataset ds, FinalColumn col, int j, int i, FinalCondition cond) {
        String op = cond.op == null ? "=" : cond.op.trim().toLowerCase(Locale.ROOT);
        String cellText = ds.records.get(i)[j];
        String raw = cellText == null ? "" : cellText.trim();

        String label = col.cat != null && col.cat[i] != null ? col.cat[i] : raw;

        switch (op) {
            case "in":
            case "not in": {
                List<String> list;
                if (cond.values != null) {
                    list = cond.values;
                } else if (cond.value == null) {
                    list = List.of();
                } else {
                    list = Arrays.stream(cond.value.split(",")).map(String::trim).collect(Collectors.toList());
                }
                boolean inList = list.stream().anyMatch(v -> v.equalsIgnoreCase(label) || v.equalsIgnoreCase(raw));
                return op.equals("in") == inList;
            }
            case "contains":
                return raw.toLowerCase(Locale.ROOT).contains(String.valueOf(cond.value).toLowerCase(Locale.ROOT));
            case "is_empty":
                return raw.isEmpty();
            case "not_empty":
                return !raw.isEmpty();
            default:
                break;
        }

        double x = col.num != null ? col.num[i] : FinalDataLoader.parseNum(raw);
        double v = cond.value == null ? Double.NaN : FinalDataLoader.parseNum(cond.value);
        if (col.type == EnumColumnType.DATE && cond.value != null) {
            LocalDate d = FinalDataLoader.parseDate(cond.value);
            if (d != null) v = d.toEpochDay();
        }
        boolean numeric = !Double.isNaN(x) && !Double.isNaN(v)
                && (col.type == EnumColumnType.NUMERIC || col.type == EnumColumnType.DATE || op.matches("[<>]=?"));
        boolean sameText = label.equalsIgnoreCase(String.valueOf(cond.value))
                || raw.equalsIgnoreCase(String.valueOf(cond.value));

        switch (op) {
            case "=":
            case "==":
            case "eq":
                return numeric ? x == v : sameText;
            case "!=":
            case "<>":
            case "ne":
                return numeric ? x != v : !sameText;
            case ">":
                return numeric && x > v;
            case ">=":
                return numeric && x >= v;
            case "<":
                return numeric && x < v;
            case "<=":
                return numeric && x <= v;
            default:
                throw new ExceptionAnalitikData("Неизвестный оператор фильтра: " + cond.op);
        }
    }

    public static String describe(List<FinalCondition> conds) {
        if (conds == null || conds.isEmpty()) return "";
        return conds.stream()
                .map(c -> c.column + " " + (c.op == null ? "=" : c.op) + " "
                        + (c.values != null ? String.join(", ", c.values) : c.value))
                .collect(Collectors.joining("; "));
    }

    public static FinalGrouping groups(FinalDataset ds, FinalColumn c, int[] rows) {
        if (c.cat != null) return groupByCategory(ds, c, rows);
        if (c.num != null) return groupByNumber(ds, c, rows);
        return groupByText(ds, c, rows);
    }

    private static FinalGrouping groupByCategory(FinalDataset ds, FinalColumn c, int[] rows) {
        FinalGrouping g = new FinalGrouping();
        g.labels = new String[ds.rows];
        Map<String, Integer> counts = new HashMap<>();
        for (int i : rows) {
            g.labels[i] = c.cat[i];
            if (c.cat[i] != null) counts.merge(c.cat[i], 1, Integer::sum);
        }
        List<String> order = new ArrayList<>(c.levels);
        order.removeIf(level -> !counts.containsKey(level));
        boolean naturalOrder = c.type == EnumColumnType.DATE || c.name.equals(ds.funnelColumn);
        if (!naturalOrder) order.sort((a, b) -> counts.get(b) - counts.get(a));
        g.order = order;
        return g;
    }

    private static FinalGrouping groupByNumber(FinalDataset ds, FinalColumn c, int[] rows) {
        FinalGrouping g = new FinalGrouping();
        g.labels = new String[ds.rows];
        double[] x = FinalAnalitikMathematics.clean(FinalRows.values(c, rows));
        TreeSet<Double> distinct = new TreeSet<>();
        for (double v : x) distinct.add(v);

        if (distinct.size() <= 12) {
            g.order = new ArrayList<>();
            for (double v : distinct) g.order.add(fmt(v));
            for (int i : rows) {
                if (!Double.isNaN(c.num[i])) g.labels[i] = fmt(c.num[i]);
            }
            return g;
        }

        double[] sorted = FinalAnalitikMathematics.sorted(x);
        double[] cuts = new double[5];
        for (int k = 0; k <= 4; k++) cuts[k] = FinalAnalitikMathematics.quantile(sorted, k / 4.0);
        String[] names = new String[4];
        for (int k = 0; k < 4; k++) {
            names[k] = "Q" + (k + 1) + ": " + fmt(cuts[k]) + "–" + fmt(cuts[k + 1]);
        }
        Set<String> used = new HashSet<>();
        for (int i : rows) {
            double v = c.num[i];
            if (Double.isNaN(v)) continue;
            int k = 0;
            while (k < 3 && v > cuts[k + 1]) k++;
            g.labels[i] = names[k];
            used.add(names[k]);
        }
        g.order = new ArrayList<>();
        for (String name : names) {
            if (used.contains(name) && !g.order.contains(name)) g.order.add(name);
        }
        return g;
    }

    private static FinalGrouping groupByText(FinalDataset ds, FinalColumn c, int[] rows) {
        FinalGrouping g = new FinalGrouping();
        g.labels = new String[ds.rows];
        int j = ds.header.indexOf(c.name);
        Map<String, Integer> counts = new HashMap<>();
        for (int i : rows) {
            String v = ds.records.get(i)[j];
            if (v == null || v.isBlank()) continue;
            g.labels[i] = v.trim();
            counts.merge(g.labels[i], 1, Integer::sum);
        }
        g.order = counts.keySet().stream()
                .sorted((a, b) -> counts.get(b) - counts.get(a))
                .collect(Collectors.toList());
        return g;
    }

    public static double agg(FinalDataset ds, FinalColumn v, List<Integer> rows, String agg, int total) {
        if (agg.equals("count")) return rows.size();
        if (agg.equals("share")) return total == 0 ? 0 : (double) rows.size() / total;
        if (v == null) throw new ExceptionAnalitikData("Для агрегата «" + agg + "» нужна колонка values");
        if (agg.equals("distinct")) {
            Set<String> distinct = new HashSet<>();
            int j = ds.header.indexOf(v.name);
            for (int i : rows) distinct.add(ds.records.get(i)[j]);
            return distinct.size();
        }
        if (v.num == null || v.type != EnumColumnType.NUMERIC) {
            throw new ExceptionAnalitikData("Колонка «" + v.name + "» не числовая — для неё доступны count, share, distinct");
        }

        double[] x = new double[rows.size()];
        int k = 0;
        for (int i : rows) x[k++] = v.num[i];
        x = FinalAnalitikMathematics.clean(x);
        if (x.length == 0) return Double.NaN;

        switch (agg) {
            case "sum": {
                double sum = 0;
                for (double d : x) sum += d;
                return sum;
            }
            case "mean":
                return FinalAnalitikMathematics.mean(x);
            case "median":
                return FinalAnalitikMathematics.quantile(FinalAnalitikMathematics.sorted(x), .5);
            case "min":
                return Arrays.stream(x).min().getAsDouble();
            case "max":
                return Arrays.stream(x).max().getAsDouble();
            case "rate": {
                int positive = 0;
                for (double d : x) {
                    if (d > 0) positive++;
                }
                return (double) positive / x.length;
            }
            default:
                throw new ExceptionAnalitikData("Неизвестный агрегат: " + agg + ". Доступны: " + AGG_RU.keySet());
        }
    }

    public static String aggFmt(String agg, FinalColumn v) {
        if (agg.equals("share") || agg.equals("rate")) return "pct";
        if (agg.equals("count") || agg.equals("distinct")) return "int";
        return "num";
    }

    public static Map<String, Object> pivot(FinalDataset ds, FinalPivotSpec spec) {
        int[] idx = filter(ds, spec.filters);
        String agg = spec.agg == null
                ? (spec.values == null ? "count" : "mean")
                : spec.agg.toLowerCase(Locale.ROOT);
        FinalColumn valueCol = spec.values == null || spec.values.isBlank() ? null : need(ds, spec.values);

        List<FinalColumn> rowCols = new ArrayList<>();
        if (spec.rows != null) {
            for (String r : spec.rows) {
                if (r != null && !r.isBlank()) rowCols.add(need(ds, r));
            }
        }
        FinalColumn colCol = spec.columns == null || spec.columns.isBlank() ? null : need(ds, spec.columns);
        List<FinalGrouping> rowGroups = new ArrayList<>();
        for (FinalColumn c : rowCols) rowGroups.add(groups(ds, c, idx));
        FinalGrouping colGroup = colCol == null ? null : groups(ds, colCol, idx);

        Map<List<String>, List<Integer>> buckets = bucketRows(idx, rowGroups);

        List<String> head = new ArrayList<>();
        List<String> formats = new ArrayList<>();
        for (FinalColumn c : rowCols) {
            head.add(c.name);
            formats.add("text");
        }
        if (rowCols.isEmpty()) {
            head.add("");
            formats.add("text");
        }
        boolean isCount = agg.equals("count");
        boolean isShare = agg.equals("share");
        String valueName = AGG_RU.getOrDefault(agg, agg)
                + (valueCol != null && !isCount && !isShare ? " · " + valueCol.name : "");
        String valueFormat = aggFmt(agg, valueCol);
        List<String> colLevels = colGroup == null ? List.of() : colGroup.order;
        if (colGroup != null) {
            for (String level : colLevels) {
                head.add(level);
                formats.add(valueFormat);
            }
            head.add("Итого");
            formats.add(valueFormat);
        } else {
            head.add(valueName);
            formats.add(valueFormat);
            if (isCount) {
                head.add("Доля");
                formats.add("pct");
            } else {
                head.add("n");
                formats.add("int");
            }
        }

        int total = idx.length;
        List<List<Object>> table = new ArrayList<>();
        for (Map.Entry<List<String>, List<Integer>> bucket : buckets.entrySet()) {
            List<Object> row = new ArrayList<>(bucket.getKey());
            List<Integer> rows = bucket.getValue();
            if (colGroup != null) {
                Map<String, List<Integer>> byColumn = new LinkedHashMap<>();
                for (String level : colLevels) byColumn.put(level, new ArrayList<>());
                for (int i : rows) {
                    String label = colGroup.labels[i];
                    if (label != null && byColumn.containsKey(label)) byColumn.get(label).add(i);
                }
                for (String level : colLevels) {
                    List<Integer> cell = byColumn.get(level);
                    boolean emptyCell = cell.isEmpty() && !isCount && !isShare;

                    row.add(emptyCell ? null : num(agg(ds, valueCol, cell, agg, isShare ? rows.size() : total)));
                }
                row.add(num(agg(ds, valueCol, rows, agg, total)));
            } else {
                row.add(num(agg(ds, valueCol, rows, agg, total)));
                if (isCount) {
                    row.add(num(total == 0 ? 0 : (double) rows.size() / total));
                } else {
                    row.add(rows.size());
                }
            }
            table.add(row);
        }

        int keyCols = Math.max(1, rowCols.size());
        boolean naturalOrder = rowCols.size() == 1 && (rowCols.get(0).type == EnumColumnType.DATE
                || rowCols.get(0).name.equals(ds.funnelColumn)
                || rowCols.get(0).type == EnumColumnType.NUMERIC);
        if (!naturalOrder && rowCols.size() >= 1 && !"none".equals(spec.sort)) {
            int sortCol = colGroup == null ? keyCols : head.size() - 1;
            table.sort((a, b) -> Double.compare(asNumber(b.get(sortCol)), asNumber(a.get(sortCol))));
        }

        int top = spec.top == null ? 50 : Math.max(1, Math.min(500, spec.top));
        String note = null;
        if (table.size() > top) {
            note = "Показаны первые " + top + " из " + table.size() + " строк";
            table = new ArrayList<>(table.subList(0, top));
        }

        List<Object> totals = new ArrayList<>();
        List<Integer> allRows = Arrays.stream(idx).boxed().collect(Collectors.toList());
        for (int k = 0; k < keyCols; k++) totals.add(k == 0 ? "Итого" : "");
        if (colGroup != null) {
            for (String level : colLevels) {
                List<Integer> cell = new ArrayList<>();
                for (int i : idx) {
                    if (level.equals(colGroup.labels[i])) cell.add(i);
                }
                totals.add(num(agg(ds, valueCol, cell, agg, total)));
            }
            totals.add(num(agg(ds, valueCol, allRows, agg, total)));
        } else {
            totals.add(num(agg(ds, valueCol, allRows, agg, total)));

            totals.add(isCount ? 1.0 : (double) total);
        }

        String title = spec.title != null && !spec.title.isBlank() ? spec.title : valueName
                + (rowCols.isEmpty() ? "" : " по «" + rowCols.stream().map(c -> c.name).collect(Collectors.joining(" × ")) + "»")
                + (colCol != null ? " × «" + colCol.name + "»" : "");
        String subtitle = fmtInt(idx.length) + " строк"
                + (spec.filters == null || spec.filters.isEmpty() ? "" : " · фильтр: " + describe(spec.filters));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "table");
        result.put("title", title);
        result.put("subtitle", subtitle);
        result.put("head", head);
        result.put("formats", formats);
        result.put("rows", table);
        result.put("totals", totals);
        result.put("keyCols", keyCols);
        result.put("heat", colGroup != null);
        if (note != null) result.put("note", note);
        result.put("spec", spec);
        return result;
    }

    private static Map<List<String>, List<Integer>> bucketRows(int[] idx, List<FinalGrouping> rowGroups) {
        LinkedHashMap<List<String>, List<Integer>> buckets = new LinkedHashMap<>();
        if (rowGroups.isEmpty()) {
            buckets.put(List.of("Все"), Arrays.stream(idx).boxed().collect(Collectors.toList()));
            return buckets;
        }

        List<List<String>> keys = new ArrayList<>();
        keys.add(new ArrayList<>());
        for (FinalGrouping g : rowGroups) {
            List<List<String>> longer = new ArrayList<>();
            for (List<String> key : keys) {
                for (String level : g.order) {
                    List<String> next = new ArrayList<>(key);
                    next.add(level);
                    longer.add(next);
                }
            }
            keys = longer;
        }
        for (List<String> key : keys) buckets.put(key, new ArrayList<>());

        for (int i : idx) {
            List<String> key = new ArrayList<>();
            boolean complete = true;
            for (FinalGrouping g : rowGroups) {
                String label = g.labels[i];
                if (label == null) {
                    complete = false;
                    break;
                }
                key.add(label);
            }
            if (complete && buckets.containsKey(key)) buckets.get(key).add(i);
        }
        buckets.values().removeIf(List::isEmpty);
        return buckets;
    }

    public static Map<String, Object> chart(FinalDataset ds, FinalChartSpec spec) {
        String type = spec.type == null ? "bar" : spec.type.toLowerCase(Locale.ROOT);
        int[] idx = filter(ds, spec.filters);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "chart");
        result.put("chartType", type);
        String subtitle = fmtInt(idx.length) + " строк"
                + (spec.filters == null || spec.filters.isEmpty() ? "" : " · " + describe(spec.filters));
        result.put("subtitle", subtitle);

        if (type.equals("histogram")) {
            histogramChart(ds, spec, idx, result);
        } else if (type.equals("scatter")) {
            scatterChart(ds, spec, idx, result);
        } else {
            categoryChart(ds, spec, idx, result);
        }
        return result;
    }

    private static void histogramChart(FinalDataset ds, FinalChartSpec spec, int[] idx, Map<String, Object> result) {
        FinalColumn c = need(ds, spec.x);
        double[] x = FinalAnalitikMathematics.clean(FinalRows.values(c, idx));
        List<String> categories = new ArrayList<>();
        List<Object> data = new ArrayList<>();
        for (double[] bin : FinalAnalitikMathematics.histogram(x)) {
            categories.add(bin[0] == bin[1] ? fmt(bin[0]) : fmt(bin[0]) + "–" + fmt(bin[1]));
            data.add(bin[2]);
        }
        result.put("title", spec.title != null ? spec.title : "Распределение «" + c.name + "»");
        result.put("categories", categories);
        result.put("series", List.of(series("Количество", data)));
        result.put("format", "int");
        result.put("xLabel", c.name);
        result.put("yLabel", "Количество");
    }

    private static void scatterChart(FinalDataset ds, FinalChartSpec spec, int[] idx, Map<String, Object> result) {
        FinalColumn a = need(ds, spec.x);
        FinalColumn b = need(ds, spec.y);
        List<double[]> points = new ArrayList<>();
        for (int i : idx) {
            if (!Double.isNaN(a.num[i]) && !Double.isNaN(b.num[i])) points.add(new double[]{a.num[i], b.num[i]});
        }

        Collections.shuffle(points, new Random(7));
        AbstractAnalitikMathematicsTest t = new FinalSpearmanTest(FinalRows.values(a, idx), FinalRows.values(b, idx));

        result.put("title", spec.title != null ? spec.title : "«" + a.name + "» vs «" + b.name + "»");
        result.put("points", points.subList(0, Math.min(900, points.size())));
        result.put("xLabel", a.name);
        result.put("yLabel", b.name);
        result.put("stat", "ρ Спирмена = " + sgn(t.effect) + ", p " + fmtPq(t.p) + ", n = " + t.n);
    }

    private static void categoryChart(FinalDataset ds, FinalChartSpec spec, int[] idx, Map<String, Object> result) {
        FinalColumn xCol = need(ds, spec.x);
        FinalColumn yCol = spec.y == null || spec.y.isBlank() ? null : need(ds, spec.y);
        String agg = spec.agg != null ? spec.agg.toLowerCase(Locale.ROOT) : yCol == null ? "count" : "mean";
        boolean hasGroup = spec.group != null && !spec.group.isBlank();

        FinalGrouping xGroups = groups(ds, xCol, idx);
        List<String> categories = new ArrayList<>(xGroups.order);
        Map<String, List<Integer>> byX = new LinkedHashMap<>();
        for (String level : categories) byX.put(level, new ArrayList<>());
        for (int i : idx) {
            String label = xGroups.labels[i];
            if (label != null && byX.containsKey(label)) byX.get(label).add(i);
        }

        List<Map<String, Object>> seriesList = new ArrayList<>();
        if (hasGroup) {
            FinalGrouping g = groups(ds, need(ds, spec.group), idx);
            for (String groupLevel : g.order.subList(0, Math.min(8, g.order.size()))) {
                List<Object> data = new ArrayList<>();
                for (String level : categories) {
                    List<Integer> cell = byX.get(level).stream()
                            .filter(i -> groupLevel.equals(g.labels[i]))
                            .collect(Collectors.toList());
                    int shareBase = agg.equals("share") ? byX.get(level).size() : idx.length;
                    data.add(cell.isEmpty() ? null : num(agg(ds, yCol, cell, agg, shareBase)));
                }
                seriesList.add(series(groupLevel, data));
            }
        } else {
            List<Object> data = new ArrayList<>();
            for (String level : categories) data.add(num(agg(ds, yCol, byX.get(level), agg, idx.length)));
            String name = AGG_RU.getOrDefault(agg, agg) + (yCol != null && !agg.equals("count") ? " · " + yCol.name : "");
            seriesList.add(series(name, data));
        }

        int top = spec.top == null ? 20 : spec.top;
        if (categories.size() > top) {
            categories = new ArrayList<>(categories.subList(0, top));
            for (Map<String, Object> s : seriesList) {
                @SuppressWarnings("unchecked")
                List<Object> data = (List<Object>) s.get("data");
                s.put("data", new ArrayList<>(data.subList(0, top)));
            }
        }

        boolean showColumn = yCol != null && !agg.equals("count") && !agg.equals("share");
        String yLabel = AGG_RU.getOrDefault(agg, agg) + (showColumn ? " «" + yCol.name + "»" : "");
        String title = spec.title != null ? spec.title
                : yLabel + " по «" + xCol.name + "»" + (hasGroup ? " и «" + spec.group + "»" : "");
        result.put("title", title);
        result.put("categories", categories);
        result.put("series", seriesList);
        result.put("format", aggFmt(agg, yCol));
        result.put("xLabel", xCol.name);
        result.put("yLabel", yLabel);
    }

    private static Map<String, Object> series(String name, List<Object> data) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("data", data);
        return s;
    }

    public static Map<String, Object> compare(FinalDataset ds, String metric, String groupBy, List<FinalCondition> filters) {
        int[] idx = filter(ds, filters);
        FinalColumn m = need(ds, metric);
        FinalColumn g = need(ds, groupBy);
        FinalGrouping groups = groups(ds, g, idx);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "table");
        List<String> head;
        List<String> formats;
        List<List<Object>> rows = new ArrayList<>();
        String summary;

        if (m.type == EnumColumnType.NUMERIC) {
            head = List.of(g.name, "n", "Среднее", "95% ДИ", "Медиана", "Δ к остальным", "g Хеджеса", "q (FDR)");
            formats = List.of("text", "int", "num", "text", "num", "pct", "num", "text");
            summary = compareNumeric(m, groups, idx, rows);
        } else {
            head = new ArrayList<>(List.of(g.name, "n"));
            formats = new ArrayList<>(List.of("text", "int"));
            summary = compareCategories(ds, m, groups, idx, head, formats, rows);
        }

        String filterText = filters == null || filters.isEmpty() ? "" : " · " + describe(filters);
        result.put("title", "«" + m.name + "» по группам «" + g.name + "»");
        result.put("subtitle", fmtInt(idx.length) + " строк" + filterText + " · " + summary);
        result.put("head", head);
        result.put("formats", formats);
        result.put("rows", rows);
        result.put("keyCols", 1);
        result.put("summary", summary);
        return result;
    }

    private static String compareNumeric(FinalColumn m, FinalGrouping groups, int[] idx, List<List<Object>> rows) {
        List<FinalWelchTest> tests = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<double[]> segments = new ArrayList<>();
        for (String level : groups.order) {
            double[] a = FinalAnalitikMathematics.clean(valuesWhere(m, idx, i -> level.equals(groups.labels[i])));
            double[] b = FinalAnalitikMathematics.clean(valuesWhere(m, idx, i -> groups.labels[i] != null && !level.equals(groups.labels[i])));
            if (a.length < 2) continue;
            tests.add(new FinalWelchTest(a, b));
            names.add(level);
            segments.add(a);
        }
        FinalMultipleComparisons.applyFdr(tests);

        for (int k = 0; k < tests.size(); k++) {
            FinalWelchTest w = tests.get(k);
            double[] ci = FinalAnalitikMathematics.meanCI(segments.get(k));
            double median = FinalAnalitikMathematics.quantile(FinalAnalitikMathematics.sorted(segments.get(k)), .5);
            rows.add(Arrays.asList(names.get(k), w.nA, num(w.meanA), fmt(ci[0]) + " – " + fmt(ci[1]), num(median),
                    num(w.lift), num(w.effect), fmtP(w.q) + " " + FinalAnalitikMathematics.stars(w.q)));
        }

        String[] labels = new String[idx.length];
        double[] y = new double[idx.length];
        for (int k = 0; k < idx.length; k++) {
            labels[k] = groups.labels[idx[k]];
            y[k] = m.num[idx[k]];
        }
        FinalAnovaTest a = new FinalAnovaTest(labels, y);
        return "ANOVA F(" + (int) a.df1 + ", " + (int) a.df2 + ") = " + fmt(a.stat) + ", p " + fmtPq(a.p)
                + "; η² = " + fmt(a.eta2) + " (" + levelRu(EnumEffectScale.CORRELATION.level(a.effect)) + " эффект); "
                + "Краскел–Уоллис p " + fmtPq(a.kwP);
    }

    private static String compareCategories(FinalDataset ds, FinalColumn m, FinalGrouping groups, int[] idx,
                                            List<String> head, List<String> formats, List<List<Object>> rows) {
        FinalGrouping values = groups(ds, m, idx);
        List<String> shown = values.order.subList(0, Math.min(8, values.order.size()));
        for (String level : shown) {
            head.add(level);
            formats.add("pct");
        }
        for (String level : groups.order) {
            List<Integer> segment = new ArrayList<>();
            for (int i : idx) {
                if (level.equals(groups.labels[i]) && values.labels[i] != null) segment.add(i);
            }
            if (segment.isEmpty()) continue;
            List<Object> row = new ArrayList<>();
            row.add(level);
            row.add(segment.size());
            for (String v : shown) {
                long count = segment.stream().filter(i -> v.equals(values.labels[i])).count();
                row.add(num((double) count / segment.size()));
            }
            rows.add(row);
        }

        String[] a = new String[idx.length];
        String[] b = new String[idx.length];
        for (int k = 0; k < idx.length; k++) {
            a[k] = groups.labels[idx[k]];
            b[k] = values.labels[idx[k]];
        }
        FinalChiSquareTest chi = new FinalChiSquareTest(a, b);
        return "χ² = " + fmt(chi.stat) + ", df = " + (int) chi.df1 + ", p " + fmtPq(chi.p)
                + "; V Крамера = " + fmt(chi.effect) + " (" + levelRuF(chi.level) + " связь)";
    }

    public static Map<String, Object> funnel(FinalDataset ds, String by, List<FinalCondition> filters) {
        if (ds.funnelColumn == null) {
            throw new ExceptionAnalitikData("В данных не найдена колонка-воронка (этап/stage/funnel)");
        }
        FinalColumn funnel = ds.col(ds.funnelColumn);
        int[] idx = filter(ds, filters);
        boolean split = by != null && !by.isBlank();

        List<String> head = new ArrayList<>(List.of("Этап"));
        List<String> formats = new ArrayList<>(List.of("text"));
        List<List<Object>> rows = new ArrayList<>();

        if (!split) {
            int[] reached = FinalGraphPaths.funnelReached(funnel, idx);
            head.addAll(List.of("Дошли", "% от входа", "Конверсия шага", "Потеряно"));
            formats.addAll(List.of("int", "pct", "pct", "int"));
            for (int k = 0; k < reached.length; k++) {
                Double fromStart = num(reached[0] == 0 ? 0 : (double) reached[k] / reached[0]);
                Double step = k == 0 ? null : num(reached[k - 1] == 0 ? 0 : (double) reached[k] / reached[k - 1]);
                Integer lost = k == 0 ? null : reached[k - 1] - reached[k];
                rows.add(Arrays.asList(funnel.levels.get(k), reached[k], fromStart, step, lost));
            }
        } else {
            FinalGrouping g = groups(ds, need(ds, by), idx);
            List<String> shown = g.order.subList(0, Math.min(8, g.order.size()));
            Map<String, int[]> reachedByGroup = new LinkedHashMap<>();
            for (String level : shown) {
                head.add(level);
                formats.add("pct");
                int[] groupRows = Arrays.stream(idx).filter(i -> level.equals(g.labels[i])).toArray();
                reachedByGroup.put(level, FinalGraphPaths.funnelReached(funnel, groupRows));
            }
            for (int k = 0; k < funnel.levels.size(); k++) {
                List<Object> row = new ArrayList<>();
                row.add(funnel.levels.get(k));
                for (String level : shown) {
                    int[] reached = reachedByGroup.get(level);
                    row.add(num(reached[0] == 0 ? 0 : (double) reached[k] / reached[0]));
                }
                rows.add(row);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "table");
        result.put("title", "Воронка «" + funnel.name + "»" + (split ? " в разрезе «" + by + "»" : ""));
        result.put("subtitle", fmtInt(idx.length) + " строк · значение = последний достигнутый этап");
        result.put("head", head);
        result.put("formats", formats);
        result.put("rows", rows);
        result.put("keyCols", 1);
        result.put("heat", split);
        return result;
    }

    public static Map<String, Object> addColumn(FinalDataset ds, String name, String formula) {
        if (name == null || name.isBlank()) throw new ExceptionAnalitikData("Нужно имя новой колонки");
        InterfaceFormulaNode f = FinalFormulaParser.parse(formula, ds);

        List<String> values = new ArrayList<>();
        for (int i = 0; i < ds.rows; i++) {
            Object v;
            try {
                v = f.evaluate(i);
            } catch (ArithmeticException e) {
                v = null;
            }
            values.add(cellText(v));
        }

        int j = ds.header.indexOf(name);
        if (j < 0) {
            ds.header.add(name);
            j = ds.header.size() - 1;
            for (int i = 0; i < ds.records.size(); i++) {
                ds.records.set(i, Arrays.copyOf(ds.records.get(i), ds.header.size()));
            }
        }
        for (int i = 0; i < ds.rows; i++) ds.records.get(i)[j] = values.get(i);

        Set<String> sources = new HashSet<>();
        Matcher ref = Pattern.compile("\\[([^\\]]+)\\]").matcher(formula);
        while (ref.find()) {
            sources.add(ref.group(1));
            sources.addAll(ds.derivedFrom.getOrDefault(ref.group(1), Set.of()));
        }
        ds.derivedFrom.put(name, sources);

        FinalDataLoader.build(ds);
        ds.version++;

        FinalColumn c = ds.col(name);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "column");
        result.put("name", name);
        result.put("formula", formula);
        result.put("columnType", c.typeRu());
        result.put("preview", values.subList(0, Math.min(8, values.size())));
        result.put("empty", (int) values.stream().filter(String::isEmpty).count());
        if (c.type == EnumColumnType.NUMERIC) {
            double[] x = FinalAnalitikMathematics.clean(c.num);
            result.put("mean", num(FinalAnalitikMathematics.mean(x)));
            result.put("min", num(Arrays.stream(x).min().orElse(Double.NaN)));
            result.put("max", num(Arrays.stream(x).max().orElse(Double.NaN)));
        } else if (c.levels != null) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String level : c.levels) counts.put(level, Arrays.stream(c.cat).filter(level::equals).count());
            result.put("levels", counts);
        }
        return result;
    }

    private static String cellText(Object v) {
        if (v == null) return "";
        if (v instanceof Double) {
            double d = (Double) v;
            return Double.isNaN(d) || Double.isInfinite(d) ? "" : trimNum(d);
        }
        if (v instanceof Boolean) return (Boolean) v ? "Да" : "Нет";
        return v.toString();
    }

    public static FinalDataset subset(FinalDataset ds, String name, List<FinalCondition> filters) {
        int[] idx = filter(ds, filters);
        FinalDataset part = new FinalDataset();
        part.id = UUID.randomUUID().toString().substring(0, 8);
        part.name = name != null && !name.isBlank() ? name : ds.name + " · " + describe(filters);
        part.sourceType = "derived";
        part.fetchedAt = System.currentTimeMillis();
        part.header = new ArrayList<>(ds.header);
        for (int i : idx) part.records.add(ds.records.get(i).clone());
        FinalDataLoader.build(part);
        return part;
    }

    public static FinalColumn need(FinalDataset ds, String name) {
        FinalColumn c = ds.col(name);
        if (c == null) {
            for (FinalColumn x : ds.columns) {
                if (x.name.equalsIgnoreCase(name.trim())) c = x;
            }
        }
        if (c == null) {
            throw new ExceptionAnalitikData("Нет колонки «" + name + "». Доступны: " + String.join(", ", ds.header));
        }
        return c;
    }

    public static double[] valuesWhere(FinalColumn c, int[] idx, IntPredicate condition) {
        return Arrays.stream(idx).filter(condition).mapToDouble(i -> c.num[i]).toArray();
    }

    public static Double num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return null;
        return Math.round(v * 1e6) / 1e6;
    }

    public static double asNumber(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : Double.NEGATIVE_INFINITY;
    }

    public static String trimNum(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return String.valueOf((long) v);
        String s = String.format(Locale.US, "%.6f", v);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
