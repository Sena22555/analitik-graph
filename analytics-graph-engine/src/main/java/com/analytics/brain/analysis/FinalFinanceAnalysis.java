package com.analytics.brain.analysis;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.exception.ExceptionAnalitikData;
import com.analytics.brain.query.FinalCondition;
import com.analytics.brain.query.FinalGrouping;
import com.analytics.brain.query.FinalQueryEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.analytics.brain.text.FinalFormatRu.fmt;
import static com.analytics.brain.text.FinalFormatRu.pct;

public final class FinalFinanceAnalysis extends AbstractAnalitikAnalysis {

    private final String revenue;
    private final String cost;
    private final String orders;
    private final String groupBy;
    private final List<FinalCondition> filters;

    public FinalFinanceAnalysis(FinalDataset ds, String revenue, String cost, String orders, String groupBy,
                                List<FinalCondition> filters) {
        super(ds);
        this.revenue = revenue;
        this.cost = cost;
        this.orders = orders;
        this.groupBy = groupBy;
        this.filters = filters;
    }

    @Override
    protected void compute(Map<String, Object> result) {
        FinalColumn rev = findColumn(revenue, "выручк", "revenue", "доход", "продаж", "sales", "gmv", "оборот");
        if (rev == null) throw new ExceptionAnalitikData("Не нашёл колонку выручки — укажите revenue");
        FinalColumn costCol = findColumn(cost, "себестоим", "затрат", "расход", "cost", "expense", "cogs");
        FinalColumn ordersCol = findColumn(orders, "покупк", "заказ", "orders", "purchases", "транзакц");
        FinalColumn discount = findColumn(null, "скидк", "discount");
        int[] idx = FinalQueryEngine.filter(ds, filters);

        List<String> head = new ArrayList<>(List.of("Сегмент", "Клиентов", "Выручка", "Доля выручки", "Платящих", "ARPU", "ARPPU"));
        List<String> formats = new ArrayList<>(List.of("text", "int", "num", "pct", "pct", "num", "num"));
        if (ordersCol != null) {
            head.add("Покупок");
            formats.add("int");
            head.add("Средний чек");
            formats.add("num");
        }
        if (costCol != null) {
            head.add("Валовая прибыль");
            formats.add("num");
            head.add("Маржа");
            formats.add("pct");
        }
        if (discount != null) {
            head.add("Скидки (оценка)");
            formats.add("num");
        }

        double totalRevenue = 0;
        for (int i : idx) {
            if (!Double.isNaN(rev.num[i])) totalRevenue += rev.num[i];
        }

        LinkedHashMap<String, List<Integer>> segments = new LinkedHashMap<>();
        if (groupBy != null && !groupBy.isBlank()) {
            FinalGrouping g = FinalQueryEngine.groups(ds, FinalQueryEngine.need(ds, groupBy), idx);
            for (String level : g.order) segments.put(level, new ArrayList<>());
            for (int i : idx) {
                if (g.labels[i] != null && segments.containsKey(g.labels[i])) segments.get(g.labels[i]).add(i);
            }
        }
        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : segments.entrySet()) {
            rows.add(financeRow(e.getKey(), e.getValue(), rev, costCol, ordersCol, discount, totalRevenue));
        }
        List<Integer> allRows = Arrays.stream(idx).boxed().collect(Collectors.toList());
        List<Object> totalRow = financeRow("Итого", allRows, rev, costCol, ordersCol, discount, totalRevenue);
        rows.sort((a, b) -> Double.compare(FinalQueryEngine.asNumber(b.get(2)), FinalQueryEngine.asNumber(a.get(2))));

        double[] sortedRevenue = Arrays.stream(idx)
                .mapToDouble(i -> Double.isNaN(rev.num[i]) ? 0 : rev.num[i])
                .sorted()
                .toArray();
        double all = Arrays.stream(sortedRevenue).sum();
        double top20 = 0;
        int cut = (int) Math.ceil(sortedRevenue.length * 0.8);
        for (int i = cut; i < sortedRevenue.length; i++) top20 += sortedRevenue[i];
        double gini = gini(sortedRevenue, all);
        double top20Share = all == 0 ? 0 : top20 / all;

        String summary = "Выручка: " + fmt(totalRevenue) + " («" + rev.name + "»"
                + (costCol != null ? ", себестоимость «" + costCol.name + "»" : ", себестоимости в данных нет — маржа не рассчитывается")
                + "). Концентрация: топ-20% клиентов дают " + pct(top20Share) + " выручки, коэффициент Джини " + fmt(gini) + ". "
                + "ARPU = выручка / клиенты; ARPPU = выручка / платящие"
                + (ordersCol != null ? "; средний чек = выручка / покупки" : "")
                + (discount != null ? "; скидки оценены как выручка × d/(100 − d)" : "") + ".";

        result.put("title", "Финансовая сводка и юнит-экономика"
                + (groupBy != null && !groupBy.isBlank() ? " по «" + groupBy + "»" : ""));
        result.put("summary", summary);
        result.put("head", head);
        result.put("formats", formats);
        result.put("rows", rows);
        result.put("totals", totalRow);

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("revenue", FinalQueryEngine.num(totalRevenue));
        kpis.put("customers", idx.length);
        kpis.put("top20share", FinalQueryEngine.num(top20Share));
        kpis.put("gini", FinalQueryEngine.num(gini));
        result.put("kpis", kpis);
    }

    private static double gini(double[] sorted, double total) {
        if (total <= 0) return 0;
        double weighted = 0;
        for (int i = 0; i < sorted.length; i++) weighted += (i + 1) * sorted[i];
        int n = sorted.length;
        return (2 * weighted) / (n * total) - (n + 1.0) / n;
    }

    private FinalColumn findColumn(String given, String... words) {
        if (given != null && !given.isBlank()) return FinalQueryEngine.need(ds, given);
        for (String w : words) {
            for (FinalColumn c : ds.columns) {
                if (c.type == EnumColumnType.NUMERIC && c.name.toLowerCase(Locale.ROOT).contains(w)) return c;
            }
        }
        return null;
    }

    private static List<Object> financeRow(String name, List<Integer> rows, FinalColumn rev, FinalColumn cost,
                                           FinalColumn orders, FinalColumn discount, double totalRevenue) {
        double revenueSum = 0;
        double costSum = 0;
        double orderCount = 0;
        double discountSum = 0;
        int paying = 0;
        for (int i : rows) {
            double r = Double.isNaN(rev.num[i]) ? 0 : rev.num[i];
            revenueSum += r;
            if (r > 0) paying++;
            if (cost != null && !Double.isNaN(cost.num[i])) costSum += cost.num[i];
            if (orders != null && !Double.isNaN(orders.num[i])) orderCount += orders.num[i];
            if (discount != null && !Double.isNaN(discount.num[i]) && discount.num[i] > 0 && discount.num[i] < 100) {
                discountSum += r * discount.num[i] / (100 - discount.num[i]);
            }
        }

        int n = rows.size();
        List<Object> row = new ArrayList<>(Arrays.asList(
                name,
                n,
                FinalQueryEngine.num(revenueSum),
                FinalQueryEngine.num(totalRevenue == 0 ? 0 : revenueSum / totalRevenue),
                FinalQueryEngine.num(n == 0 ? 0 : (double) paying / n),
                FinalQueryEngine.num(n == 0 ? 0 : revenueSum / n),
                FinalQueryEngine.num(paying == 0 ? 0 : revenueSum / paying)));
        if (orders != null) {
            row.add(FinalQueryEngine.num(orderCount));
            row.add(FinalQueryEngine.num(orderCount == 0 ? 0 : revenueSum / orderCount));
        }
        if (cost != null) {
            row.add(FinalQueryEngine.num(revenueSum - costSum));
            row.add(FinalQueryEngine.num(revenueSum == 0 ? 0 : (revenueSum - costSum) / revenueSum));
        }
        if (discount != null) row.add(FinalQueryEngine.num(discountSum));
        return row;
    }
}
