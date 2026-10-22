package com.analytics.brain.graph;

public final class FinalGraphBar {

    public String label;
    public String text;
    public double value;
    public double lo;
    public double hi;
    public boolean highlight;

    public FinalGraphBar() {
    }

    public FinalGraphBar(String label, double value, double lo, double hi, String text) {
        this.label = label;
        this.value = value;
        this.lo = lo;
        this.hi = hi;
        this.text = text;
    }
}
