package com.analytics.service;

import com.analytics.model.Variable;
import com.analytics.model.TimeSeriesEvent;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import java.io.IOException;
import java.util.*;

/**
 * Сервис для парсинга и подготовки данных
 */
public class DataParserService {
    
    /**
     * Парсит CSV данные и создает переменные
     * Первая строка - заголовки (названия переменных)
     * Автоматически определяет тип переменной (NUMERIC или CATEGORICAL)
     */
    public List<Variable> parseCSVData(String csvData) throws IOException {
        List<Variable> variables = new ArrayList<>();
        CSVParser parser = CSVFormat.DEFAULT.parse(new java.io.StringReader(csvData));
        
        List<CSVRecord> records = parser.getRecords();
        if (records.isEmpty()) {
            return variables;
        }

        CSVRecord headerRecord = records.get(0);
        String[] headers = new String[headerRecord.size()];
        for (int i = 0; i < headerRecord.size(); i++) {
            headers[i] = headerRecord.get(i).trim();
        }

        // Определяем тип каждой переменной
        boolean[] isNumeric = new boolean[headers.length];
        for (int i = 0; i < headers.length; i++) {
            isNumeric[i] = true;
        }

        // Проверяем первые несколько строк для определения типа
        int checkRows = Math.min(5, records.size() - 1);
        for (int i = 1; i <= checkRows; i++) {
            CSVRecord record = records.get(i);
            for (int j = 0; j < headers.length && j < record.size(); j++) {
                try {
                    Double.parseDouble(record.get(j).trim());
                } catch (NumberFormatException e) {
                    isNumeric[j] = false;
                }
            }
        }

        // Создаем переменные с правильным типом
        for (int i = 0; i < headers.length; i++) {
            Variable.VariableType type = isNumeric[i] ? Variable.VariableType.NUMERIC : Variable.VariableType.CATEGORICAL;
            variables.add(new Variable(headers[i], type));
        }

        // Парсим значения
        for (int i = 1; i < records.size(); i++) {
            CSVRecord record = records.get(i);
            for (int j = 0; j < headers.length && j < record.size(); j++) {
                if (isNumeric[j]) {
                    try {
                        double value = Double.parseDouble(record.get(j).trim());
                        variables.get(j).getValues().add(value);
                    } catch (NumberFormatException e) {
                        // Пропускаем невалидные значения
                    }
                }
            }
        }

        // Удаляем переменные без значений
        variables.removeIf(v -> v.getValues().isEmpty());

        parser.close();
        return variables;
    }

    /**
     * Валидирует данные переменных
     */
    public boolean validateVariables(List<Variable> variables) {
        if (variables.isEmpty()) {
            return false;
        }
        
        // Все переменные должны иметь одинаковое количество значений
        int expectedSize = variables.get(0).getValues().size();
        
        for (Variable var : variables) {
            if (var.getValues().isEmpty() || var.getValues().size() != expectedSize) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Нормализует значения переменных (z-score)
     */
    public void normalizeVariables(List<Variable> variables) {
        for (Variable var : variables) {
            List<Double> values = var.getValues();
            
            // Вычисляем среднее
            double mean = values.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);
            
            // Вычисляем стандартное отклонение
            double variance = values.stream()
                    .mapToDouble(v -> Math.pow(v - mean, 2))
                    .average()
                    .orElse(0);
            double stdDev = Math.sqrt(variance);
            
            // Нормализуем
            if (stdDev > 0) {
                for (int i = 0; i < values.size(); i++) {
                    values.set(i, (values.get(i) - mean) / stdDev);
                }
            }
        }
    }

    /**
     * Создает временные события из данных
     */
    public List<TimeSeriesEvent> createTimeSeriesEvents(List<Variable> variables) {
        List<TimeSeriesEvent> events = new ArrayList<>();
        
        if (variables.isEmpty() || variables.get(0).getValues().isEmpty()) {
            return events;
        }

        int timePointCount = variables.get(0).getValues().size();
        long baseTime = System.currentTimeMillis();
        long timeInterval = 1000; // 1 секунда между точками

        for (int i = 0; i < timePointCount; i++) {
            TimeSeriesEvent event = new TimeSeriesEvent(
                    baseTime + (i * timeInterval),
                    "data_point_" + i,
                    0
            );

            // Добавляем значения всех переменных для этой временной точки
            for (Variable var : variables) {
                event.addVariable(var.getName(), var.getValues().get(i));
            }

            events.add(event);
        }

        return events;
    }

    /**
     * Вычисляет статистику для переменной
     */
    public Map<String, Double> calculateStatistics(Variable variable) {
        Map<String, Double> stats = new HashMap<>();
        List<Double> values = variable.getValues();
        
        if (values.isEmpty()) {
            return stats;
        }

        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average()
                .orElse(0);
        double stdDev = Math.sqrt(variance);

        stats.put("mean", mean);
        stats.put("median", calculateMedian(values));
        stats.put("min", min);
        stats.put("max", max);
        stats.put("stdDev", stdDev);
        stats.put("variance", variance);
        stats.put("count", (double) values.size());

        return stats;
    }

    private double calculateMedian(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        
        if (sorted.size() % 2 == 0) {
            return (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2.0;
        } else {
            return sorted.get(sorted.size() / 2);
        }
    }
}
