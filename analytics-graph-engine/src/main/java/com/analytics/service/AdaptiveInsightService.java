package com.analytics.service;

import com.analytics.model.InsightMap;
import com.analytics.model.InsightNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Builds an exploratory analytics map from an arbitrary CSV table. */
@Service
public class AdaptiveInsightService {
    private static final int MAX_CATEGORY_VALUES = 24;
    private static final int MAX_TIME_POINTS = 40;

    public InsightMap analyze(String csvData, String name) throws Exception {
        CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).build().parse(new StringReader(csvData));
        List<String> headers = parser.getHeaderNames();
        List<CSVRecord> rows = parser.getRecords();
        if (headers.isEmpty() || rows.isEmpty()) {
            throw new IllegalArgumentException("Таблица должна содержать заголовки и хотя бы одну строку данных");
        }

        List<Column> columns = new ArrayList<>();
        for (String header : headers) columns.add(profile(header, rows));
        List<Column> useful = columns.stream().filter(c -> !c.identifier).collect(Collectors.toList());
        Column time = useful.stream().filter(c -> c.date).findFirst().orElse(null);

        InsightNode root = node("root", "Общая аналитика", "root", rows.size(), rows.size() + " записей");
        root.getDetails().put("столбцов", headers.size());
        root.getDetails().put("аналитических полей", useful.size());
        if (time != null) root.getDetails().put("временное поле", time.name);

        InsightNode overview = node("overview", "Сводка", "group", rows.size(), "главные показатели");
        for (Column column : useful) {
            if (column.numeric) overview.addChild(numericNode("overview", column));
            else if (column.categorical) overview.addChild(categoryNode("overview", column, rows));
        }
        root.addChild(overview);

        InsightNode patterns = node("patterns", "Закономерности", "group", 0, "выявлено автоматически");
        addPatterns(patterns, useful, rows);
        if (!patterns.getChildren().isEmpty()) root.addChild(patterns);

        if (time != null) {
            InsightNode timeline = node("timeline", "Динамика во времени", "time", rows.size(), time.name);
            Map<String, List<CSVRecord>> groups = rows.stream().collect(Collectors.groupingBy(
                    r -> value(r, time.name), LinkedHashMap::new, Collectors.toList()));
            groups.entrySet().stream().sorted(Map.Entry.comparingByKey()).limit(MAX_TIME_POINTS).forEach(entry -> {
                InsightNode period = node("period-" + safe(entry.getKey()), entry.getKey(), "time",
                        entry.getValue().size(), entry.getValue().size() + " записей");
                for (Column column : useful) {
                    if (column == time) continue;
                    if (column.numeric) period.addChild(numericNode("period-" + safe(entry.getKey()), column, entry.getValue()));
                    else if (column.categorical) period.addChild(categoryNode("period-" + safe(entry.getKey()), column, entry.getValue()));
                }
                timeline.addChild(period);
            });
            root.addChild(timeline);
        }
        addRelationships(root, null);
        parser.close();
        return new InsightMap(name, rows.size(), time == null ? null : time.name, root);
    }

    private void addRelationships(InsightNode node, InsightNode parent) {
        if (parent != null) {
            node.getDetails().put("связь с разделом", parent.getLabel());
            if ("category".equals(node.getKind())) {
                node.getDetails().put("смысл", "Сегмент внутри показателя «" + parent.getLabel() + "»");
            } else if ("metric".equals(node.getKind())) {
                node.getDetails().put("смысл", "Статистика показателя для выбранного раздела");
            } else if ("time".equals(node.getKind())) {
                node.getDetails().put("смысл", "Срез данных за выбранный период");
            }
        }
        for (InsightNode child : node.getChildren()) addRelationships(child, node);
    }

    private Column profile(String name, List<CSVRecord> rows) {
        List<String> values = rows.stream().map(r -> value(r, name)).filter(v -> !v.isEmpty()).collect(Collectors.toList());
        Column column = new Column(name, values);
        column.numeric = !values.isEmpty() && values.stream().allMatch(this::number);
        column.date = !values.isEmpty() && values.stream().allMatch(this::date);
        column.unique = values.stream().distinct().count();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean technicalName = lower.equals("index") || lower.equals("id") || lower.endsWith(" id") || lower.contains("email") || lower.contains("phone") || lower.contains("website") || lower.contains("url");
        column.identifier = technicalName || (!column.numeric && !column.date && column.unique >= values.size() * .95);
        column.categorical = !column.numeric && !column.date && !column.identifier && column.unique <= MAX_CATEGORY_VALUES;
        return column;
    }

    private InsightNode numericNode(String prefix, Column column) { return numericNode(prefix, column, null); }
    private InsightNode numericNode(String prefix, Column column, List<CSVRecord> group) {
        List<Double> values = group == null ? column.numbers() : group.stream().map(r -> value(r, column.name)).filter(this::number).map(Double::parseDouble).collect(Collectors.toList());
        double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        InsightNode result = node(prefix + "-" + safe(column.name), column.name, "metric", avg, "среднее: " + fmt(avg));
        result.getDetails().put("количество", values.size());
        result.getDetails().put("медиана", fmt(median(values)));
        result.getDetails().put("минимум", fmt(values.stream().mapToDouble(Double::doubleValue).min().orElse(0)));
        result.getDetails().put("максимум", fmt(values.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
        result.getDetails().put("стандартное отклонение", fmt(stddev(values, avg)));
        return result;
    }

    private InsightNode categoryNode(String prefix, Column column, List<CSVRecord> rows) {
        Map<String, Long> groups = rows.stream().map(r -> value(r, column.name)).filter(v -> !v.isEmpty())
                .collect(Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()));
        InsightNode result = node(prefix + "-" + safe(column.name), column.name, "dimension", groups.size(), groups.size() + " категорий");
        groups.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(MAX_CATEGORY_VALUES).forEach(entry -> {
            InsightNode item = node(result.getId() + "-" + safe(entry.getKey()), entry.getKey(), "category", entry.getValue(),
                    entry.getValue() + " (" + fmt(entry.getValue() * 100.0 / rows.size()) + "%)");
            item.getDetails().put("доля", fmt(entry.getValue() * 100.0 / rows.size()) + "%");
            result.addChild(item);
        });
        return result;
    }

    private void addPatterns(InsightNode patterns, List<Column> columns, List<CSVRecord> rows) {
        for (Column column : columns) {
            if (!column.categorical) continue;
            Map<String, Long> values = rows.stream().map(r -> value(r, column.name)).filter(v -> !v.isEmpty())
                    .collect(Collectors.groupingBy(v -> v, Collectors.counting()));
            Map.Entry<String, Long> top = values.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
            if (top != null && top.getValue() * 100.0 / rows.size() >= 35) {
                patterns.addChild(node("pattern-" + safe(column.name), "Преобладает: " + column.name, "pattern", top.getValue(),
                        top.getKey() + " — " + fmt(top.getValue() * 100.0 / rows.size()) + "%"));
            }
        }
    }

    private InsightNode node(String id, String label, String kind, double value, String display) {
        InsightNode node = new InsightNode(id, label, kind); node.setValue(value); node.setDisplayValue(display); return node;
    }
    private String value(CSVRecord row, String name) { return row.isMapped(name) ? row.get(name).trim() : ""; }
    private boolean number(String value) { try { Double.parseDouble(value); return true; } catch (Exception e) { return false; } }
    private boolean date(String value) {
        for (DateTimeFormatter f : Arrays.asList(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd.MM.yyyy"), DateTimeFormatter.ofPattern("MM/dd/yyyy"))) {
            try { LocalDate.parse(value, f); return true; } catch (DateTimeParseException ignored) { }
        } return false;
    }
    private String safe(String value) { return value.replaceAll("[^a-zA-Zа-яА-Я0-9]+", "-"); }
    private String fmt(double value) { return String.format(Locale.US, "%.2f", value).replaceAll("\\.00$", ""); }
    private double median(List<Double> values) { if (values.isEmpty()) return 0; List<Double> copy = new ArrayList<>(values); Collections.sort(copy); int n=copy.size(); return n % 2 == 0 ? (copy.get(n/2-1)+copy.get(n/2))/2 : copy.get(n/2); }
    private double stddev(List<Double> values, double avg) { return Math.sqrt(values.stream().mapToDouble(v -> (v-avg)*(v-avg)).average().orElse(0)); }

    private static class Column {
        final String name; final List<String> values; boolean numeric; boolean date; boolean categorical; boolean identifier; long unique;
        Column(String name, List<String> values) { this.name=name; this.values=values; }
        List<Double> numbers() { return values.stream().filter(v -> { try { Double.parseDouble(v); return true; } catch (Exception e) { return false; } }).map(Double::parseDouble).collect(Collectors.toList()); }
    }
}
