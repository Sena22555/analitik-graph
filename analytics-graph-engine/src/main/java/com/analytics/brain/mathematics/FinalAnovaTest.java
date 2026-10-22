package com.analytics.brain.mathematics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FinalAnovaTest extends AbstractCorrelationTest {

    public double eta2;
    public double omega2;
    public double kwH;
    public double kwP;
    public Map<String, double[]> groups = new LinkedHashMap<>();

    public FinalAnovaTest(String[] groupOf, double[] y) {
        super("Однофакторный ANOVA", "η");
        Map<String, List<Double>> byGroup = new LinkedHashMap<>();
        for (int i = 0; i < groupOf.length; i++) {
            if (groupOf[i] != null && !Double.isNaN(y[i])) {
                byGroup.computeIfAbsent(groupOf[i], g -> new ArrayList<>()).add(y[i]);
            }
        }
        byGroup.values().removeIf(list -> list.size() < 2);

        int groupCount = byGroup.size();
        for (List<Double> list : byGroup.values()) n += list.size();
        if (groupCount < 2 || n - groupCount < 1) return;

        double[] all = new double[n];
        int pos = 0;
        for (Map.Entry<String, List<Double>> e : byGroup.entrySet()) {
            double[] values = e.getValue().stream().mapToDouble(Double::doubleValue).toArray();
            groups.put(e.getKey(), values);
            for (double v : values) all[pos++] = v;
        }

        double grandMean = FinalAnalitikMathematics.mean(all);
        double ssBetween = 0;
        double ssWithin = 0;
        for (double[] values : groups.values()) {
            double m = FinalAnalitikMathematics.mean(values);
            ssBetween += values.length * (m - grandMean) * (m - grandMean);
            for (double v : values) ssWithin += (v - m) * (v - m);
        }
        double ssTotal = ssBetween + ssWithin;
        double dfBetween = groupCount - 1;
        double dfWithin = n - groupCount;
        double msWithin = ssWithin / dfWithin;

        df1 = dfBetween;
        df2 = dfWithin;
        if (msWithin == 0) {
            stat = ssBetween > 0 ? Double.POSITIVE_INFINITY : 0;
        } else {
            stat = (ssBetween / dfBetween) / msWithin;
        }
        p = Double.isInfinite(stat) ? 0 : FinalProbabilityDistributions.fP(stat, dfBetween, dfWithin);
        eta2 = ssTotal == 0 ? 0 : ssBetween / ssTotal;
        omega2 = ssTotal == 0 ? 0 : Math.max(0, (ssBetween - dfBetween * msWithin) / (ssTotal + msWithin));
        effect = Math.sqrt(eta2);
        classifyEffect();
        kruskalWallis(all, dfBetween);
    }

    private void kruskalWallis(double[] all, double dfBetween) {
        double[] rank = FinalAnalitikMathematics.ranks(all);
        double h = 0;
        int pos = 0;
        for (double[] values : groups.values()) {
            double rankSum = 0;
            for (int i = 0; i < values.length; i++) rankSum += rank[pos++];
            h += rankSum * rankSum / values.length;
        }
        h = 12.0 / (n * (n + 1.0)) * h - 3 * (n + 1.0);
        kwH = h;
        kwP = FinalProbabilityDistributions.chi2P(h, dfBetween);
    }
}
