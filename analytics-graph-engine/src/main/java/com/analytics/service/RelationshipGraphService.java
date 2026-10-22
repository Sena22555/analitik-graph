package com.analytics.service;

import com.analytics.model.PatternGraph;
import com.analytics.model.PatternLink;
import com.analytics.model.PatternNode;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Finds concise, explainable statistical relationships for the visual graph. */
@Service
public class RelationshipGraphService {
    private static final int MAX_LINKS = 40;
    private static final int MAX_CATEGORIES_PER_COLUMN = 8;

    public PatternGraph analyze(String csvData) throws Exception {
        CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setTrim(true).setIgnoreEmptyLines(true).build().parse(new StringReader(csvData));
        List<String> headers = parser.getHeaderNames();
        List<CSVRecord> rows = parser.getRecords();
        PatternGraph graph = new PatternGraph();
        if (rows.size() < 3) return graph;

        List<Column> columns = headers.stream().map(h -> profile(h, rows)).filter(c -> !c.identifier).collect(Collectors.toList());
        List<Column> numbers = columns.stream().filter(c -> c.numeric).collect(Collectors.toList());
        List<Column> categories = columns.stream().filter(c -> c.categorical).collect(Collectors.toList());
        Map<String, PatternNode> nodes = new LinkedHashMap<>();
        List<PatternLink> links = new ArrayList<>();

        for (Column number : numbers) {
            nodes.put(number.id(), new PatternNode(number.id(), number.name, "metric", "Числовой показатель", number.values.size()));
        }

        // Pearson correlations: only meaningful pairs survive the threshold.
        for (int i = 0; i < numbers.size(); i++) for (int j = i + 1; j < numbers.size(); j++) {
            Column left = numbers.get(i), right = numbers.get(j);
            double r = pearson(left.numbers(), right.numbers());
            if (Math.abs(r) < 0.45) continue;
            String sign = r >= 0 ? "positive" : "negative";
            String label = "r = " + fmt(r);
            String explanation = r >= 0
                    ? "При росте «" + left.name + "» обычно растет «" + right.name + "»"
                    : "При росте «" + left.name + "» обычно снижается «" + right.name + "»";
            links.add(new PatternLink(left.id(), right.id(), Math.abs(r), sign, label, explanation));
        }

        // Segment effects: compare each category average to the global metric average.
        for (Column category : categories) {
            Map<String, List<CSVRecord>> groups = rows.stream().filter(r -> !value(r, category.name).isEmpty())
                    .collect(Collectors.groupingBy(r -> value(r, category.name), LinkedHashMap::new, Collectors.toList()));
            for (Map.Entry<String, List<CSVRecord>> group : groups.entrySet().stream()
                    .sorted(Map.Entry.<String, List<CSVRecord>>comparingByValue(Comparator.comparingInt(List::size)).reversed())
                    .limit(MAX_CATEGORIES_PER_COLUMN).collect(Collectors.toList())) {
                String segmentId = "segment|" + safe(category.name) + "|" + safe(group.getKey());
                nodes.put(segmentId, new PatternNode(segmentId, group.getKey(), "segment", category.name + ": " + group.getKey(), group.getValue().size()));
                for (Column metric : numbers) {
                    List<Double> segment = group.getValue().stream().map(r -> value(r, metric.name)).filter(this::number).map(Double::parseDouble).collect(Collectors.toList());
                    if (segment.size() < 5) continue;
                    double globalMean = average(metric.numbers()), segmentMean = average(segment);
                    if (Math.abs(globalMean) < 0.0001) continue;
                    double difference = (segmentMean - globalMean) / Math.abs(globalMean) * 100;
                    if (Math.abs(difference) < 12) continue;
                    String direction = difference >= 0 ? "positive" : "negative";
                    String label = (difference >= 0 ? "+" : "") + fmt(difference) + "%";
                    String explanation = "У сегмента «" + category.name + ": " + group.getKey() + "» показатель «" + metric.name + "» "
                            + (difference >= 0 ? "выше" : "ниже") + " среднего на " + fmt(Math.abs(difference)) + "%";
                    links.add(new PatternLink(segmentId, metric.id(), Math.min(1, Math.abs(difference) / 100), direction, label, explanation));
                }
            }
        }

        links.sort(Comparator.comparingDouble(this::selectionScore).reversed());
        // Keep the strongest links, but reserve space for distinct segment sources.
        List<PatternLink> selected = new ArrayList<>();
        Set<String> coveredSegments = new HashSet<>();
        for (PatternLink link : links) {
            String[] parts = link.getSource().split("\\|", 3);
            String family = parts.length == 3 ? parts[1] : link.getSource();
            if (link.getSource().startsWith("segment|") && coveredSegments.add(family)) selected.add(link);
        }
        for (PatternLink link : links) {
            if (!selected.contains(link)) selected.add(link);
            if (selected.size() >= MAX_LINKS) break;
        }
        Set<String> used = new HashSet<>();
        for (PatternLink link : selected) { used.add(link.getSource()); used.add(link.getTarget()); }
        graph.setLinks(selected);
        graph.setNodes(nodes.values().stream().filter(n -> used.contains(n.getId())).collect(Collectors.toList()));
        parser.close();
        return graph;
    }

    private Column profile(String name, List<CSVRecord> rows) {
        List<String> values = rows.stream().map(r -> value(r, name)).filter(v -> !v.isEmpty()).collect(Collectors.toList());
        Column c = new Column(name, values);
        c.numeric = !values.isEmpty() && values.stream().allMatch(this::number);
        String lower = name.toLowerCase(Locale.ROOT);
        c.identifier = lower.equals("id") || lower.endsWith(" id") || lower.equals("index") || lower.contains("email") || lower.contains("phone") || lower.contains("website") || lower.contains("url");
        long unique = values.stream().distinct().count();
        c.categorical = !c.numeric && !c.identifier && unique > 1 && unique <= 20;
        return c;
    }

    private String value(CSVRecord row, String name) { return row.isMapped(name) ? row.get(name).trim() : ""; }
    private boolean number(String value) { try { Double.parseDouble(value); return true; } catch (Exception e) { return false; } }
    private double average(List<Double> values) { return values.stream().mapToDouble(Double::doubleValue).average().orElse(0); }
    private double pearson(List<Double> left, List<Double> right) { double a=average(left), b=average(right), up=0, aa=0, bb=0; for(int i=0;i<Math.min(left.size(),right.size());i++){double x=left.get(i)-a,y=right.get(i)-b;up+=x*y;aa+=x*x;bb+=y*y;} return aa==0||bb==0?0:up/Math.sqrt(aa*bb); }
    private String safe(String text) { return text.replaceAll("[^a-zA-Zа-яА-Я0-9]+", "-"); }
    private String fmt(double n) { return String.format(Locale.US, "%.1f", n).replace(".0", ""); }

    /** Business outcomes are more useful than incidental secondary differences in the first view. */
    private double selectionScore(PatternLink link) {
        String target = link.getTarget().toLowerCase(Locale.ROOT);
        double priority = target.contains("покупк") || target.contains("выручк") || target.contains("конверс")
                ? 0.55 : target.contains("время") || target.contains("оценк") || target.contains("nps") ? 0.15 : 0;
        return link.getStrength() + priority;
    }

    private static class Column {
        final String name, valuesId; final List<String> values; boolean numeric, categorical, identifier;
        Column(String name, List<String> values) { this.name=name; this.values=values; this.valuesId="metric-"+name.replaceAll("[^a-zA-Zа-яА-Я0-9]+", "-"); }
        String id() { return valuesId; }
        List<Double> numbers() { return values.stream().map(Double::parseDouble).collect(Collectors.toList()); }
    }
}
