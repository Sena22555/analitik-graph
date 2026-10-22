package com.analytics.brain.mathematics;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class FinalMultipleComparisons {

    private FinalMultipleComparisons() {
    }

    public static double[] benjaminiHochberg(double[] p) {
        int m = p.length;
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> p[i]));

        double[] q = new double[m];
        double prev = 1;
        for (int rank = m - 1; rank >= 0; rank--) {
            int i = order[rank];
            prev = Math.min(prev, p[i] * m / (rank + 1));
            q[i] = Math.min(1, prev);
        }
        return q;
    }

    public static void applyFdr(List<? extends InterfaceSignificanceTest> tests) {
        double[] p = new double[tests.size()];
        for (int i = 0; i < p.length; i++) p[i] = tests.get(i).pValue();
        double[] q = benjaminiHochberg(p);
        for (int i = 0; i < q.length; i++) tests.get(i).acceptQValue(q[i]);
    }
}
