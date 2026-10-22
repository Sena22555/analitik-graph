package com.analytics.brain.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class FinalGraphTable {

    public String title;
    public List<String> head = new ArrayList<>();
    public List<List<Object>> rows = new ArrayList<>();

    public FinalGraphTable() {
    }

    public FinalGraphTable(String title, String... head) {
        this.title = title;
        this.head.addAll(Arrays.asList(head));
    }

    public FinalGraphTable row(Object... cells) {
        rows.add(Arrays.asList(cells));
        return this;
    }
}
