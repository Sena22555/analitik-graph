package com.analytics.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

/**
 * Статистика графа аналитики
 */
public class GraphStatistics {
    @JsonProperty("variableCount")
    private int variableCount;
    
    @JsonProperty("eventCount")
    private int eventCount;
    
    @JsonProperty("edgeCount")
    private int edgeCount;
    
    @JsonProperty("density")
    private double density;
    
    @JsonProperty("avgCorrelation")
    private double avgCorrelation;
    
    @JsonProperty("strongestCorrelations")
    private List<Correlation> strongestCorrelations;
    
    @JsonProperty("temporalPatterns")
    private List<TemporalPattern> temporalPatterns;
    
    @JsonProperty("processingTimeMs")
    private long processingTimeMs;

    public GraphStatistics() {
        this.strongestCorrelations = new ArrayList<>();
        this.temporalPatterns = new ArrayList<>();
    }

    // Getters и Setters
    public int getVariableCount() {
        return variableCount;
    }

    public void setVariableCount(int variableCount) {
        this.variableCount = variableCount;
    }

    public int getEventCount() {
        return eventCount;
    }

    public void setEventCount(int eventCount) {
        this.eventCount = eventCount;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    public void setEdgeCount(int edgeCount) {
        this.edgeCount = edgeCount;
    }

    public double getDensity() {
        return density;
    }

    public void setDensity(double density) {
        this.density = density;
    }

    public double getAvgCorrelation() {
        return avgCorrelation;
    }

    public void setAvgCorrelation(double avgCorrelation) {
        this.avgCorrelation = avgCorrelation;
    }

    public List<Correlation> getStrongestCorrelations() {
        return strongestCorrelations;
    }

    public void setStrongestCorrelations(List<Correlation> strongestCorrelations) {
        this.strongestCorrelations = strongestCorrelations;
    }

    public List<TemporalPattern> getTemporalPatterns() {
        return temporalPatterns;
    }

    public void setTemporalPatterns(List<TemporalPattern> temporalPatterns) {
        this.temporalPatterns = temporalPatterns;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    public void setProcessingTimeMs(long processingTimeMs) {
        this.processingTimeMs = processingTimeMs;
    }

    public static class Correlation {
        @JsonProperty("var1")
        private String var1;
        
        @JsonProperty("var2")
        private String var2;
        
        @JsonProperty("value")
        private double value;
        
        @JsonProperty("type")
        private String type;

        public Correlation() {}

        public Correlation(String var1, String var2, double value, String type) {
            this.var1 = var1;
            this.var2 = var2;
            this.value = value;
            this.type = type;
        }

        public String getVar1() { return var1; }
        public void setVar1(String var1) { this.var1 = var1; }
        public String getVar2() { return var2; }
        public void setVar2(String var2) { this.var2 = var2; }
        public double getValue() { return value; }
        public void setValue(double value) { this.value = value; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
    }

    public static class TemporalPattern {
        @JsonProperty("pattern")
        private String pattern;
        
        @JsonProperty("frequency")
        private int frequency;
        
        @JsonProperty("confidence")
        private double confidence;
        
        @JsonProperty("events")
        private List<String> events;

        public TemporalPattern() {
            this.events = new ArrayList<>();
        }

        public TemporalPattern(String pattern, int frequency, double confidence) {
            this();
            this.pattern = pattern;
            this.frequency = frequency;
            this.confidence = confidence;
        }

        public String getPattern() { return pattern; }
        public void setPattern(String pattern) { this.pattern = pattern; }
        public int getFrequency() { return frequency; }
        public void setFrequency(int frequency) { this.frequency = frequency; }
        public double getConfidence() { return confidence; }
        public void setConfidence(double confidence) { this.confidence = confidence; }
        public List<String> getEvents() { return events; }
        public void setEvents(List<String> events) { this.events = events; }
    }
}
