package com.analytics.brain.graph;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class FinalGraphFilter {

    @JsonProperty("c")
    public String column;
    @JsonProperty("v")
    public String value;

    public FinalGraphFilter() {
    }

    public FinalGraphFilter(String column, String value) {
        this.column = column;
        this.value = value;
    }
}
