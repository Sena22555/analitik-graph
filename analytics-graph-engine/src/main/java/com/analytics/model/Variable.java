package com.analytics.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.*;

/**
 * Узел в графе зависимостей переменных
 */
public class Variable {
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("type")
    private VariableType type;
    
    @JsonProperty("values")
    private List<Double> values;
    
    @JsonProperty("dependencies")
    private Set<String> dependencies;
    
    @JsonProperty("correlations")
    private Map<String, Double> correlations;
    
    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    public enum VariableType {
        NUMERIC, CATEGORICAL, TEMPORAL, EVENT
    }

    public Variable() {
        this.values = new ArrayList<>();
        this.dependencies = new HashSet<>();
        this.correlations = new HashMap<>();
        this.metadata = new HashMap<>();
    }

    public Variable(String name, VariableType type) {
        this();
        this.name = name;
        this.type = type;
    }

    // Getters и Setters
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public VariableType getType() {
        return type;
    }

    public void setType(VariableType type) {
        this.type = type;
    }

    public List<Double> getValues() {
        return values;
    }

    public void setValues(List<Double> values) {
        this.values = values;
    }

    public Set<String> getDependencies() {
        return dependencies;
    }

    public void setDependencies(Set<String> dependencies) {
        this.dependencies = dependencies;
    }

    public Map<String, Double> getCorrelations() {
        return correlations;
    }

    public void setCorrelations(Map<String, Double> correlations) {
        this.correlations = correlations;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public void addDependency(String variableName) {
        this.dependencies.add(variableName);
    }

    public void addCorrelation(String variableName, double correlation) {
        this.correlations.put(variableName, correlation);
    }

    @Override
    public String toString() {
        return "Variable{" +
                "name='" + name + '\'' +
                ", type=" + type +
                ", values=" + values.size() +
                ", dependencies=" + dependencies.size() +
                '}';
    }
}
