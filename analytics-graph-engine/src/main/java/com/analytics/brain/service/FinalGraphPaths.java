package com.analytics.brain.service;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.graph.FinalGraphFilter;
import com.analytics.brain.graph.FinalGraphRef;
import com.analytics.brain.mathematics.FinalChiSquareTest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class FinalGraphPaths {

    private FinalGraphPaths() {
    }

    public static boolean derivedPair(FinalDataset ds, String a, String b) {
        return ds.derivedFrom.getOrDefault(a, Set.of()).contains(b)
                || ds.derivedFrom.getOrDefault(b, Set.of()).contains(a);
    }

    public static FinalChiSquareTest segmentVsRest(FinalColumn d, int[] segment, int[] rest) {
        int size = segment.length + rest.length;
        String[] group = new String[size];
        String[] value = new String[size];
        int pos = 0;
        for (int i : segment) {
            group[pos] = "seg";
            value[pos++] = d.cat[i];
        }
        for (int i : rest) {
            group[pos] = "rest";
            value[pos++] = d.cat[i];
        }
        return new FinalChiSquareTest(group, value);
    }

    public static int[] funnelReached(FinalColumn funnel, int[] rows) {
        int stages = funnel.levels.size();
        Map<String, Integer> position = new HashMap<>();
        for (int i = 0; i < stages; i++) position.put(funnel.levels.get(i), i);

        int[] stoppedAt = new int[stages];
        for (int i : rows) {
            Integer k = funnel.cat[i] == null ? null : position.get(funnel.cat[i]);
            if (k != null) stoppedAt[k]++;
        }

        int[] reached = new int[stages];
        int sum = 0;
        for (int k = stages - 1; k >= 0; k--) {
            sum += stoppedAt[k];
            reached[k] = sum;
        }
        return reached;
    }

    public static int worstStep(int[] reached) {
        int worst = -1;
        double worstConversion = 2;
        for (int k = 1; k < reached.length; k++) {
            if (reached[k - 1] == 0) continue;
            double conversion = (double) reached[k] / reached[k - 1];
            if (conversion < worstConversion) {
                worstConversion = conversion;
                worst = k;
            }
        }
        return worst;
    }

    static Map<String, Integer> countByLevel(FinalColumn c, int[] rows) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i : rows) {
            if (c.cat[i] != null) counts.merge(c.cat[i], 1, Integer::sum);
        }
        return counts;
    }

    static int sum(Map<String, Integer> counts) {
        int total = 0;
        for (int v : counts.values()) total += v;
        return total;
    }

    static List<String> displayOrder(FinalDataset ds, FinalColumn c, Map<String, Integer> counts) {
        List<String> order = new ArrayList<>(c.levels);
        if (c.type == EnumColumnType.CATEGORICAL && !c.name.equals(ds.funnelColumn)) {
            order.sort(Comparator.comparingInt((String v) -> counts.getOrDefault(v, 0)).reversed());
        }
        return order;
    }

    public static String varId(FinalColumn c) {
        return (c.type == EnumColumnType.NUMERIC ? "m::" : "d::") + c.name;
    }

    public static String pathKey(List<FinalGraphFilter> path) {
        return path.stream().map(f -> f.column + "=" + f.value).collect(Collectors.joining("|"));
    }

    public static String segmentId(List<FinalGraphFilter> path) {
        return "c:" + pathKey(path);
    }

    public static FinalGraphRef ref(String kind, List<FinalGraphFilter> path, String column, String nodeId) {
        FinalGraphRef r = new FinalGraphRef();
        r.kind = kind;
        r.filters = new ArrayList<>(path);
        r.column = column;
        r.nodeId = nodeId;
        return r;
    }
}
