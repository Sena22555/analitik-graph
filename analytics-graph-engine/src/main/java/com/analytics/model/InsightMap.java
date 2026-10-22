package com.analytics.model;

public class InsightMap {
    private String name;
    private int rowCount;
    private String timeColumn;
    private InsightNode root;

    public InsightMap() { }

    public InsightMap(String name, int rowCount, String timeColumn, InsightNode root) {
        this.name = name;
        this.rowCount = rowCount;
        this.timeColumn = timeColumn;
        this.root = root;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public String getTimeColumn() { return timeColumn; }
    public void setTimeColumn(String timeColumn) { this.timeColumn = timeColumn; }
    public InsightNode getRoot() { return root; }
    public void setRoot(InsightNode root) { this.root = root; }
}
