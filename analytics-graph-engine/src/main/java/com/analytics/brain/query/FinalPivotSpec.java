package com.analytics.brain.query;

import java.util.ArrayList;
import java.util.List;

public final class FinalPivotSpec {

    public List<String> rows = new ArrayList<>();
    public String columns;
    public String values;
    public String agg = "count";
    public String title;
    public String sort;
    public List<FinalCondition> filters = new ArrayList<>();
    public Integer top;
}
