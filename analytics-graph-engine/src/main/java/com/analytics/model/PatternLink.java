package com.analytics.model;

public class PatternLink {
    private String source;
    private String target;
    private double strength;
    private String direction;
    private String label;
    private String explanation;

    public PatternLink() { }
    public PatternLink(String source, String target, double strength, String direction, String label, String explanation) {
        this.source=source; this.target=target; this.strength=strength; this.direction=direction; this.label=label; this.explanation=explanation;
    }
    public String getSource() { return source; }
    public void setSource(String source) { this.source=source; }
    public String getTarget() { return target; }
    public void setTarget(String target) { this.target=target; }
    public double getStrength() { return strength; }
    public void setStrength(double strength) { this.strength=strength; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction=direction; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label=label; }
    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation=explanation; }
}
