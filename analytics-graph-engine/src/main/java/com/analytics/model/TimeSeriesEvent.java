package com.analytics.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.*;

/**
 * Событие в временном ряду с метаданными
 */
public class TimeSeriesEvent {
    @JsonProperty("timestamp")
    private long timestamp;
    
    @JsonProperty("eventType")
    private String eventType;
    
    @JsonProperty("value")
    private double value;
    
    @JsonProperty("variables")
    private Map<String, Double> variables;
    
    @JsonProperty("causality")
    private List<String> predecessors;
    
    @JsonProperty("impact")
    private List<String> successors;
    
    @JsonProperty("metadata")
    private Map<String, Object> metadata;
    
    @JsonProperty("severity")
    private EventSeverity severity;

    public enum EventSeverity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public TimeSeriesEvent() {
        this.timestamp = System.currentTimeMillis();
        this.variables = new HashMap<>();
        this.predecessors = new ArrayList<>();
        this.successors = new ArrayList<>();
        this.metadata = new HashMap<>();
        this.severity = EventSeverity.MEDIUM;
    }

    public TimeSeriesEvent(long timestamp, String eventType, double value) {
        this();
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.value = value;
    }

    // Getters и Setters
    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public Map<String, Double> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, Double> variables) {
        this.variables = variables;
    }

    public List<String> getPredecessors() {
        return predecessors;
    }

    public void setPredecessors(List<String> predecessors) {
        this.predecessors = predecessors;
    }

    public List<String> getSuccessors() {
        return successors;
    }

    public void setSuccessors(List<String> successors) {
        this.successors = successors;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public EventSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(EventSeverity severity) {
        this.severity = severity;
    }

    public void addPredecessor(String eventId) {
        this.predecessors.add(eventId);
    }

    public void addSuccessor(String eventId) {
        this.successors.add(eventId);
    }

    public void addVariable(String name, double value) {
        this.variables.put(name, value);
    }

    @Override
    public String toString() {
        return "TimeSeriesEvent{" +
                "timestamp=" + timestamp +
                ", eventType='" + eventType + '\'' +
                ", value=" + value +
                ", severity=" + severity +
                '}';
    }
}
