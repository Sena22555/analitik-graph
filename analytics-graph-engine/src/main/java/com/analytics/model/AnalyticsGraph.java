package com.analytics.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

/**
 * Комбинированный граф аналитики - объединяет граф зависимостей и временный ряд
 */
public class AnalyticsGraph {
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("variables")
    private Map<String, Variable> variables;
    
    @JsonProperty("timeSeries")
    private List<TimeSeriesEvent> timeSeries;
    
    @JsonProperty("adjacencyMatrix")
    private Map<String, Map<String, Double>> adjacencyMatrix;
    
    @JsonProperty("statistics")
    private GraphStatistics statistics;
    
    @JsonProperty("createdAt")
    private long createdAt;
    
    @JsonProperty("updatedAt")
    private long updatedAt;
    private InsightMap insights;
    private PatternGraph patternGraph;

    public AnalyticsGraph() {
        this.id = UUID.randomUUID().toString();
        this.variables = new LinkedHashMap<>();
        this.timeSeries = new ArrayList<>();
        this.adjacencyMatrix = new HashMap<>();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }

    public AnalyticsGraph(String name) {
        this();
        this.name = name;
    }

    // Getters и Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, Variable> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Variable> variables) {
        this.variables = variables;
    }

    public List<TimeSeriesEvent> getTimeSeries() {
        return timeSeries;
    }

    public void setTimeSeries(List<TimeSeriesEvent> timeSeries) {
        this.timeSeries = timeSeries;
    }

    public Map<String, Map<String, Double>> getAdjacencyMatrix() {
        return adjacencyMatrix;
    }

    public void setAdjacencyMatrix(Map<String, Map<String, Double>> adjacencyMatrix) {
        this.adjacencyMatrix = adjacencyMatrix;
    }

    public GraphStatistics getStatistics() {
        return statistics;
    }

    public void setStatistics(GraphStatistics statistics) {
        this.statistics = statistics;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public InsightMap getInsights() { return insights; }
    public void setInsights(InsightMap insights) { this.insights = insights; }
    public PatternGraph getPatternGraph() { return patternGraph; }
    public void setPatternGraph(PatternGraph patternGraph) { this.patternGraph = patternGraph; }

    // Методы для работы с переменными
    public void addVariable(Variable variable) {
        this.variables.put(variable.getName(), variable);
        this.updatedAt = System.currentTimeMillis();
    }

    public Variable getVariable(String name) {
        return this.variables.get(name);
    }

    public void addEvent(TimeSeriesEvent event) {
        this.timeSeries.add(event);
        this.updatedAt = System.currentTimeMillis();
    }

    public int getVariableCount() {
        return this.variables.size();
    }

    public int getEventCount() {
        return this.timeSeries.size();
    }

    public int getEdgeCount() {
        return (int) this.variables.values().stream()
                .mapToLong(v -> v.getDependencies().size())
                .sum();
    }

    @Override
    public String toString() {
        return "AnalyticsGraph{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", variables=" + variables.size() +
                ", events=" + timeSeries.size() +
                ", edges=" + getEdgeCount() +
                '}';
    }
}
