package com.analytics.model;

public class PatternNode {
    private String id;
    private String label;
    private String kind;
    private String summary;
    private int size;

    public PatternNode() { }
    public PatternNode(String id, String label, String kind, String summary, int size) {
        this.id=id; this.label=label; this.kind=kind; this.summary=summary; this.size=size;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id=id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label=label; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind=kind; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary=summary; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size=size; }
}
