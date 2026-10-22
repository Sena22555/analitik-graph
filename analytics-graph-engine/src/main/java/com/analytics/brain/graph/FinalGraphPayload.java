package com.analytics.brain.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FinalGraphPayload {

    public Map<String, Object> dataset;
    public List<FinalGraphNode> nodes = new ArrayList<>();
    public List<FinalGraphLink> links = new ArrayList<>();
    public List<FinalGraphInsight> insights;
    public List<String> remove;
}
