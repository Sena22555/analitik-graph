package com.analytics.brain.mathematics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FinalChiSquareTest extends AbstractCorrelationTest {

    public double[][] observed;
    public double[][] expected;
    public List<String> rows;
    public List<String> cols;

    public FinalChiSquareTest(String[] a, String[] b) {
        super("χ² Пирсона (независимость)", "V");
        Map<String, Integer> rowIndex = new LinkedHashMap<>();
        Map<String, Integer> colIndex = new LinkedHashMap<>();
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (a[i] == null || b[i] == null) continue;
            int r = rowIndex.computeIfAbsent(a[i], key -> rowIndex.size());
            int c = colIndex.computeIfAbsent(b[i], key -> colIndex.size());
            pairs.add(new int[]{r, c});
        }

        n = pairs.size();
        rows = new ArrayList<>(rowIndex.keySet());
        cols = new ArrayList<>(colIndex.keySet());

        int r = rowIndex.size();
        int k = colIndex.size();
        if (r < 2 || k < 2 || n < 5) return;

        double[][] table = new double[r][k];
        for (int[] pair : pairs) table[pair[0]][pair[1]]++;

        double[] rowSum = new double[r];
        double[] colSum = new double[k];
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < k; j++) {
                rowSum[i] += table[i][j];
                colSum[j] += table[i][j];
            }
        }

        double[][] model = new double[r][k];
        double chi2 = 0;
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < k; j++) {
                model[i][j] = rowSum[i] * colSum[j] / n;
                if (model[i][j] > 0) {
                    double diff = table[i][j] - model[i][j];
                    chi2 += diff * diff / model[i][j];
                }
            }
        }

        observed = table;
        expected = model;
        stat = chi2;
        df1 = (r - 1) * (k - 1);
        p = FinalProbabilityDistributions.chi2P(chi2, df1);
        effect = Math.min(1, correctedCramersV(chi2, r, k));
        classifyEffect();
    }

    private double correctedCramersV(double chi2, int r, int k) {
        double phi2 = chi2 / n;
        double phi2c = Math.max(0, phi2 - (double) (k - 1) * (r - 1) / (n - 1));
        double rc = r - (double) (r - 1) * (r - 1) / (n - 1);
        double kc = k - (double) (k - 1) * (k - 1) / (n - 1);
        double denom = Math.min(kc - 1, rc - 1);
        return denom <= 0 ? 0 : Math.sqrt(phi2c / denom);
    }

    public double residual(int row, int col) {
        return (observed[row][col] - expected[row][col]) / Math.sqrt(expected[row][col]);
    }
}
