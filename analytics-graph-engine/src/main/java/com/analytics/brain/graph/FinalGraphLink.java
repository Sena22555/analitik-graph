package com.analytics.brain.graph;

import com.analytics.brain.mathematics.AbstractAnalitikMathematicsTest;

public final class FinalGraphLink {

    public String source;
    public String target;
    public String type;
    public String label;
    public String level = "none";
    public String direction;
    public String text;
    public double strength;
    public AbstractAnalitikMathematicsTest test;

    public FinalGraphLink() {
    }

    public FinalGraphLink(String source, String target, String type, String label) {
        this.source = source;
        this.target = target;
        this.type = type;
        this.label = label;
    }
}
