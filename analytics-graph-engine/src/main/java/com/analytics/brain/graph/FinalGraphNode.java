package com.analytics.brain.graph;

import java.util.ArrayList;
import java.util.List;

public final class FinalGraphNode {

    public String id;
    public String kind;
    public String label;
    public String sub;
    public String parent;
    public String column;
    public String value;
    public String level = "none";
    public String direction;
    public int n;
    public Double share;
    public boolean expandable;
    public FinalGraphRef ref;
    public String text;
    public List<Object[]> stats = new ArrayList<>();
    public FinalGraphTable table;
    public String barsTitle;
    public List<FinalGraphBar> bars;
    public List<FinalGraphEffect> effects;

    public FinalGraphNode stat(String name, Object value) {
        stats.add(new Object[]{name, value});
        return this;
    }
}
