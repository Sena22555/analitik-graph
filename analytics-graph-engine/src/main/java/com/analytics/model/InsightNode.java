package com.analytics.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Узел раскрываемой аналитической карты. */
public class InsightNode {
    private String id;
    private String label;
    private String kind;
    private double value;
    private String displayValue;
    private Map<String, Object> details = new LinkedHashMap<>();
    private List<InsightNode> children = new ArrayList<>();

    public InsightNode() { }

    public InsightNode(String id, String label, String kind) {
        this.id = id;
        this.label = label;
        this.kind = kind;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }
    public String getDisplayValue() { return displayValue; }
    public void setDisplayValue(String displayValue) { this.displayValue = displayValue; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
    public List<InsightNode> getChildren() { return children; }
    public void setChildren(List<InsightNode> children) { this.children = children; }
    public void addChild(InsightNode child) { children.add(child); }
}
