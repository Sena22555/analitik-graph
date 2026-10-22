package com.analytics.brain.query;

import java.util.ArrayList;
import java.util.List;

public final class FinalChartSpec {

    public String type = "bar";
    public String x;
    public String y;
    public String agg;
    public String group;
    public String title;
    public List<FinalCondition> filters = new ArrayList<>();
    public Integer top;
}
