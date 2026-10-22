package com.analytics.service;

import com.analytics.model.*;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.springframework.stereotype.Service;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import java.io.StringReader;
import java.text.DecimalFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Движок для анализа графа - вычисляет корреляции, зависимости и паттерны
 */
@Service
public class AnalyticsGraphEngine {
    
    private final DataParserService dataParser = new DataParserService();
    private final AdaptiveInsightService adaptiveInsightService = new AdaptiveInsightService();
    private final RelationshipGraphService relationshipGraphService = new RelationshipGraphService();
    private static final double CORRELATION_THRESHOLD = 0.5;
    private static final double STRONG_CORRELATION_THRESHOLD = 0.7;
    
    /**
     * Создает и анализирует граф из CSV данных
     */
    public AnalyticsGraph buildGraph(String csvData, String graphName) throws Exception {
        long startTime = System.currentTimeMillis();
        
        // Парсим данные
        List<Variable> variables = dataParser.parseCSVData(csvData);
        
        if (!dataParser.validateVariables(variables)) {
            throw new IllegalArgumentException("Invalid data: all variables must have the same number of values");
        }
        
        // Создаем граф
        AnalyticsGraph graph = new AnalyticsGraph(graphName);
        
        // Добавляем переменные
        for (Variable var : variables) {
            graph.addVariable(var);
        }
        
        // Вычисляем корреляции и зависимости
        computeCorrelations(graph);
        
        // Создаем временной ряд событий
        List<TimeSeriesEvent> events = dataParser.createTimeSeriesEvents(variables);
        for (TimeSeriesEvent event : events) {
            graph.addEvent(event);
        }
        
        // Анализируем временные паттерны
        analyzeTemporalPatterns(graph);
        
        // Вычисляем статистику
        GraphStatistics stats = computeStatistics(graph);
        stats.setProcessingTimeMs(System.currentTimeMillis() - startTime);
        graph.setStatistics(stats);
        graph.setInsights(adaptiveInsightService.analyze(csvData, graphName));
        graph.setPatternGraph(relationshipGraphService.analyze(csvData));
        
        return graph;
    }

    /** Строит раскрываемую карту: время -> показатели -> категории и распределения. */
    public InsightMap buildInsightMap(String csvData, String graphName) throws Exception {
        CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(false)
                .setIgnoreEmptyLines(true).setTrim(true).build().parse(new StringReader(csvData));
        List<CSVRecord> records = parser.getRecords();
        if (records.isEmpty()) return new InsightMap(graphName, 0, null,
                new InsightNode("root", "Нет данных", "root"));

        List<String> columns = new ArrayList<>();
        for (String value : parser.getHeaderNames()) columns.add(value.trim());
        String timeColumn = findTimeColumn(columns);
        Map<String, List<CSVRecord>> periods = new LinkedHashMap<>();
        for (CSVRecord row : records) {
            String period = timeColumn == null ? "Все данные" : value(row, columns, timeColumn);
            if (period.isEmpty()) period = "Без даты";
            periods.computeIfAbsent(period, ignored -> new ArrayList<>()).add(row);
        }

        InsightNode root = new InsightNode("root", "Аналитика пользователей", "root");
        root.setValue(records.size());
        root.setDisplayValue("Строк: " + records.size());
        root.getDetails().put("описание", "Нажимайте на узлы, чтобы раскрывать детали");

        for (Map.Entry<String, List<CSVRecord>> entry : periods.entrySet()) {
            String period = entry.getKey();
            List<CSVRecord> rows = entry.getValue();
            InsightNode periodNode = new InsightNode("time-" + safeId(period), period,
                    timeColumn == null ? "group" : "time");
            periodNode.setValue(rows.size());
            periodNode.setDisplayValue(rows.size() + " пользователей");
            periodNode.getDetails().put("доля", percent(rows.size(), records.size()));

            InsightNode count = new InsightNode(periodNode.getId() + "-count",
                    "Количество пользователей", "metric");
            count.setValue(rows.size());
            count.setDisplayValue(String.valueOf(rows.size()));
            periodNode.addChild(count);

            for (String column : columns) {
                if (column.equals(timeColumn) || isIdColumn(column)) continue;
                List<String> values = new ArrayList<>();
                for (CSVRecord row : rows) values.add(value(row, columns, column));
                if (values.isEmpty()) continue;

                if (allNumbers(values)) {
                    double[] numbers = values.stream().mapToDouble(Double::parseDouble).toArray();
                    InsightNode metric = new InsightNode(periodNode.getId() + "-" + safeId(column),
                            russianMetric(column), "metric");
                    metric.setValue(average(numbers));
                    metric.setDisplayValue("среднее " + format(average(numbers)));
                    metric.getDetails().put("минимум", format(Arrays.stream(numbers).min().orElse(0)));
                    metric.getDetails().put("максимум", format(Arrays.stream(numbers).max().orElse(0)));
                    metric.getDetails().put("медиана", format(median(numbers)));
                    metric.getDetails().put("стандартное отклонение", format(stddev(numbers)));
                    periodNode.addChild(metric);
                } else {
                    InsightNode dimension = new InsightNode(periodNode.getId() + "-" + safeId(column),
                            russianMetric(column), "dimension");
                    Map<String, Long> distribution = values.stream().filter(v -> !v.isEmpty())
                            .collect(java.util.stream.Collectors.groupingBy(v -> v, LinkedHashMap::new, Collectors.counting()));
                    distribution.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .limit(15).forEach(item -> {
                                InsightNode category = new InsightNode(dimension.getId() + "-" + safeId(item.getKey()),
                                        item.getKey(), "category");
                                category.setValue(item.getValue());
                                category.setDisplayValue(item.getValue() + " (" + percent(item.getValue(), rows.size()) + ")");
                                dimension.addChild(category);
                            });
                    periodNode.addChild(dimension);
                }
            }
            root.addChild(periodNode);
        }
        parser.close();
        return new InsightMap(graphName, records.size(), timeColumn, root);
    }

    private String findTimeColumn(List<String> columns) {
        for (String column : columns) {
            String lower = column.toLowerCase(Locale.ROOT);
            if (lower.contains("date") || lower.contains("time") || lower.contains("дата") || lower.contains("время")) return column;
        }
        return null;
    }

    private boolean isIdColumn(String column) {
        String lower = column.toLowerCase(Locale.ROOT);
        return lower.equals("id") || lower.endsWith(" id") || lower.contains("identifier") || lower.equals("index");
    }

    private String value(CSVRecord row, List<String> columns, String column) {
        int index = columns.indexOf(column);
        return index >= 0 && index < row.size() ? row.get(index).trim() : "";
    }

    private boolean allNumbers(List<String> values) {
        boolean found = false;
        for (String value : values) {
            if (value.isEmpty()) continue;
            try { Double.parseDouble(value); found = true; } catch (NumberFormatException e) { return false; }
        }
        return found;
    }

    private String russianMetric(String column) {
        Map<String, String> names = new HashMap<>();
        names.put("users", "Пользователи"); names.put("gender", "Пол"); names.put("sex", "Пол");
        names.put("age", "Возраст"); names.put("children", "Дети"); names.put("stage", "Этап");
        names.put("progress", "Прогресс"); names.put("status", "Статус"); names.put("country", "Страна");
        names.put("city", "Город"); names.put("income", "Доход");
        return names.getOrDefault(column.toLowerCase(Locale.ROOT), column);
    }

    private String safeId(String text) { return text.replaceAll("[^a-zA-Zа-яА-Я0-9]+", "-"); }
    private String percent(double part, double total) { return total == 0 ? "0%" : format(part * 100 / total) + "%"; }
    private String format(double number) { return new DecimalFormat("0.##").format(number); }
    private double average(double[] values) { return Arrays.stream(values).average().orElse(0); }
    private double median(double[] values) { double[] copy = values.clone(); Arrays.sort(copy); return copy.length % 2 == 0 ? (copy[copy.length / 2 - 1] + copy[copy.length / 2]) / 2 : copy[copy.length / 2]; }
    private double stddev(double[] values) { double avg = average(values); return Math.sqrt(Arrays.stream(values).map(v -> Math.pow(v - avg, 2)).average().orElse(0)); }
    
    /**
     * Вычисляет корреляции между всеми переменными
     */
    private void computeCorrelations(AnalyticsGraph graph) {
        List<Variable> variables = new ArrayList<>(graph.getVariables().values());
        int n = variables.size();
        
        if (n < 2) {
            return;
        }
        
        // Создаем матрицу данных
        int sampleSize = variables.get(0).getValues().size();
        double[][] data = new double[sampleSize][n];
        
        for (int i = 0; i < sampleSize; i++) {
            for (int j = 0; j < n; j++) {
                data[i][j] = variables.get(j).getValues().get(i);
            }
        }
        
        // Вычисляем корреляции
        PearsonsCorrelation correlation = new PearsonsCorrelation(data);
        double[][] corrMatrix = correlation.getCorrelationMatrix().getData();
        
        // Заполняем корреляции и зависимости
        Map<String, Map<String, Double>> adjacency = new HashMap<>();
        
        for (int i = 0; i < n; i++) {
            Variable var1 = variables.get(i);
            adjacency.putIfAbsent(var1.getName(), new HashMap<>());
            
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                
                Variable var2 = variables.get(j);
                double corrValue = corrMatrix[i][j];
                
                // Добавляем корреляцию
                var1.addCorrelation(var2.getName(), corrValue);
                
                // Добавляем в матрицу смежности
                adjacency.get(var1.getName()).put(var2.getName(), Math.abs(corrValue));
                
                // Если корреляция сильная - добавляем зависимость
                if (Math.abs(corrValue) >= CORRELATION_THRESHOLD) {
                    var1.addDependency(var2.getName());
                }
            }
        }
        
        graph.setAdjacencyMatrix(adjacency);
    }
    
    /**
     * Анализирует временные паттерны в событиях
     */
    private void analyzeTemporalPatterns(AnalyticsGraph graph) {
        List<TimeSeriesEvent> events = graph.getTimeSeries();
        
        if (events.size() < 3) {
            return;
        }
        
        // Ищем причинно-следственные связи между событиями
        for (int i = 1; i < events.size(); i++) {
            TimeSeriesEvent currentEvent = events.get(i);
            TimeSeriesEvent previousEvent = events.get(i - 1);
            
            // Анализируем изменения переменных
            Map<String, Double> currentVars = currentEvent.getVariables();
            Map<String, Double> previousVars = previousEvent.getVariables();
            
            for (String varName : currentVars.keySet()) {
                double currentVal = currentVars.get(varName);
                double previousVal = previousVars.getOrDefault(varName, 0.0);
                
                // Если есть значительное изменение
                double change = Math.abs(currentVal - previousVal);
                if (change > 0.5) {
                    currentEvent.addPredecessor(previousEvent.getEventType());
                    previousEvent.addSuccessor(currentEvent.getEventType());
                }
            }
        }
    }
    
    /**
     * Вычисляет общую статистику графа
     */
    private GraphStatistics computeStatistics(AnalyticsGraph graph) {
        GraphStatistics stats = new GraphStatistics();
        
        stats.setVariableCount(graph.getVariableCount());
        stats.setEventCount(graph.getEventCount());
        stats.setEdgeCount(graph.getEdgeCount());
        
        // Вычисляем плотность графа
        int n = graph.getVariableCount();
        if (n > 1) {
            double maxEdges = n * (n - 1);
            stats.setDensity(graph.getEdgeCount() / maxEdges);
        }
        
        // Находим сильнейшие корреляции
        List<GraphStatistics.Correlation> strongCorrelations = findStrongestCorrelations(graph);
        stats.setStrongestCorrelations(strongCorrelations);
        
        // Вычисляем среднюю корреляцию
        double avgCorr = strongCorrelations.stream()
                .mapToDouble(GraphStatistics.Correlation::getValue)
                .average()
                .orElse(0.0);
        stats.setAvgCorrelation(avgCorr);
        
        // Анализируем временные паттерны
        List<GraphStatistics.TemporalPattern> patterns = analyzeEventPatterns(graph);
        stats.setTemporalPatterns(patterns);
        
        return stats;
    }
    
    /**
     * Находит самые сильные корреляции в графе
     */
    private List<GraphStatistics.Correlation> findStrongestCorrelations(AnalyticsGraph graph) {
        List<GraphStatistics.Correlation> correlations = new ArrayList<>();
        Set<String> processed = new HashSet<>();
        
        for (Variable var : graph.getVariables().values()) {
            for (Map.Entry<String, Double> entry : var.getCorrelations().entrySet()) {
                String pair = var.getName() + "-" + entry.getKey();
                String reversePair = entry.getKey() + "-" + var.getName();
                
                if (!processed.contains(pair) && !processed.contains(reversePair)) {
                    double corrValue = entry.getValue();
                    
                    if (Math.abs(corrValue) >= STRONG_CORRELATION_THRESHOLD) {
                        String type = corrValue > 0 ? "positive" : "negative";
                        correlations.add(new GraphStatistics.Correlation(
                                var.getName(),
                                entry.getKey(),
                                corrValue,
                                type
                        ));
                        processed.add(pair);
                    }
                }
            }
        }
        
        // Сортируем по силе корреляции
        correlations.sort((a, b) -> Double.compare(
                Math.abs(b.getValue()),
                Math.abs(a.getValue())
        ));
        
        return correlations.stream().limit(10).collect(Collectors.toList());
    }
    
    /**
     * Анализирует паттерны в последовательности событий
     */
    private List<GraphStatistics.TemporalPattern> analyzeEventPatterns(AnalyticsGraph graph) {
        List<GraphStatistics.TemporalPattern> patterns = new ArrayList<>();
        List<TimeSeriesEvent> events = graph.getTimeSeries();
        
        if (events.size() < 5) {
            return patterns;
        }
        
        // Ищем повторяющиеся последовательности причинно-следственных связей
        Map<String, Integer> sequenceFrequency = new HashMap<>();
        
        for (int i = 0; i < events.size() - 2; i++) {
            TimeSeriesEvent e1 = events.get(i);
            TimeSeriesEvent e2 = events.get(i + 1);
            TimeSeriesEvent e3 = events.get(i + 2);
            
            if (!e2.getPredecessors().isEmpty() && !e3.getPredecessors().isEmpty()) {
                String sequence = e1.getEventType() + "->" + e2.getEventType() + "->" + e3.getEventType();
                sequenceFrequency.put(sequence, sequenceFrequency.getOrDefault(sequence, 0) + 1);
            }
        }
        
        // Создаем паттерны из частых последовательностей
        for (Map.Entry<String, Integer> entry : sequenceFrequency.entrySet()) {
            if (entry.getValue() >= 2) {
                GraphStatistics.TemporalPattern pattern = new GraphStatistics.TemporalPattern(
                        entry.getKey(),
                        entry.getValue(),
                        entry.getValue() / (double) events.size()
                );
                
                String[] eventTypes = entry.getKey().split("->");
                pattern.setEvents(Arrays.asList(eventTypes));
                
                patterns.add(pattern);
            }
        }
        
        // Сортируем по частоте
        patterns.sort((a, b) -> Integer.compare(b.getFrequency(), a.getFrequency()));
        
        return patterns.stream().limit(5).collect(Collectors.toList());
    }
}
