package com.analytics.brain.analysis;

import com.analytics.brain.data.FinalDataset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class AbstractAnalitikAnalysis {

    protected final FinalDataset ds;

    protected AbstractAnalitikAnalysis(FinalDataset ds) {
        this.ds = ds;
    }

    public final Map<String, Object> run() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "table");
        compute(result);
        result.put("subtitle", result.get("summary"));
        result.put("keyCols", 1);
        return result;
    }

    protected abstract void compute(Map<String, Object> result);

    protected static Map<String, Object> series(String name, List<?> data) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("data", data);
        return s;
    }

    protected static <T> List<T> first(List<T> list, int count) {
        return list.size() > count ? list.subList(0, count) : list;
    }
}
