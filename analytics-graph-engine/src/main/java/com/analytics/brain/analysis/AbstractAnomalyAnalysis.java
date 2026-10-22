package com.analytics.brain.analysis;

import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.query.FinalCondition;
import com.analytics.brain.query.FinalQueryEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class AbstractAnomalyAnalysis extends AbstractAnalitikAnalysis {

    protected final String metric;
    protected final List<FinalCondition> filters;

    protected AbstractAnomalyAnalysis(FinalDataset ds, String metric, List<FinalCondition> filters) {
        super(ds);
        this.metric = metric;
        this.filters = filters;
    }

    public static AbstractAnomalyAnalysis create(FinalDataset ds, String metric, String by, List<FinalCondition> filters) {
        if (by != null && !by.isBlank()) return new FinalGroupAnomalyAnalysis(ds, metric, by, filters);
        return new FinalRecordAnomalyAnalysis(ds, metric, filters);
    }

    @Override
    protected final void compute(Map<String, Object> result) {
        FinalColumn m = FinalQueryEngine.need(ds, metric);
        int[] idx = FinalQueryEngine.filter(ds, filters);
        List<List<Object>> rows = new ArrayList<>();
        findAnomalies(m, idx, rows, result);
        result.put("rows", rows);
    }

    protected abstract void findAnomalies(FinalColumn m, int[] idx, List<List<Object>> rows, Map<String, Object> result);
}
