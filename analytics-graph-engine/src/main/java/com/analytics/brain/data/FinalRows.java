package com.analytics.brain.data;

import com.analytics.brain.graph.FinalGraphFilter;

import java.util.Arrays;
import java.util.List;

public final class FinalRows {

    private FinalRows() {
    }

    public static int[] all(FinalDataset ds) {
        int[] rows = new int[ds.rows];
        for (int i = 0; i < rows.length; i++) rows[i] = i;
        return rows;
    }

    public static int[] matching(FinalDataset ds, List<FinalGraphFilter> path) {
        int[] rows = all(ds);
        for (FinalGraphFilter f : path) {
            FinalColumn c = ds.col(f.column);
            if (c == null || c.cat == null) continue;
            rows = where(rows, c, f.value);
        }
        return rows;
    }

    public static int[] where(int[] rows, FinalColumn c, String value) {
        return Arrays.stream(rows).filter(i -> value.equals(c.cat[i])).toArray();
    }

    public static int[] whereNot(int[] rows, FinalColumn c, String value) {
        return Arrays.stream(rows).filter(i -> c.cat[i] != null && !c.cat[i].equals(value)).toArray();
    }

    public static int[] restOf(FinalDataset ds, List<FinalGraphFilter> path) {
        FinalGraphFilter last = path.get(path.size() - 1);
        List<FinalGraphFilter> parentPath = path.subList(0, path.size() - 1);
        return whereNot(matching(ds, parentPath), ds.col(last.column), last.value);
    }

    public static double[] values(FinalColumn c, int[] rows) {
        double[] out = new double[rows.length];
        for (int i = 0; i < rows.length; i++) out[i] = c.num[rows[i]];
        return out;
    }

    public static String[] labels(FinalColumn c, int[] rows) {
        String[] out = new String[rows.length];
        for (int i = 0; i < rows.length; i++) out[i] = c.cat[rows[i]];
        return out;
    }
}
