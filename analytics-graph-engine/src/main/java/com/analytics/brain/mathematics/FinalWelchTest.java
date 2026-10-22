package com.analytics.brain.mathematics;

import java.util.ArrayList;
import java.util.List;

import static com.analytics.brain.text.FinalFormatRu.fmt;
import static com.analytics.brain.text.FinalFormatRu.fmtPq;
import static com.analytics.brain.text.FinalFormatRu.pctAbs;
import static com.analytics.brain.text.FinalFormatRu.sgnPct;

public final class FinalWelchTest extends AbstractSegmentComparisonTest {

    public double meanA;
    public double meanB;
    public double lift;

    public FinalWelchTest(double[] a, double[] b) {
        super("t-тест Уэлча", "g", a.length, b.length);
        meanA = FinalAnalitikMathematics.mean(a);
        meanB = FinalAnalitikMathematics.mean(b);
        boolean noBase = b.length == 0 || Math.abs(meanB) < 1e-12;
        lift = noBase ? Double.NaN : (meanA - meanB) / Math.abs(meanB);
        if (a.length < 2 || b.length < 2) return;

        double va = FinalAnalitikMathematics.variance(a);
        double vb = FinalAnalitikMathematics.variance(b);
        double se2 = va / a.length + vb / b.length;
        if (se2 == 0) {
            p = meanA == meanB ? 1 : 0;
        } else {
            stat = (meanA - meanB) / Math.sqrt(se2);
            double partA = Math.pow(va / a.length, 2) / (a.length - 1);
            double partB = Math.pow(vb / b.length, 2) / (b.length - 1);
            df1 = se2 * se2 / (partA + partB);
            p = FinalProbabilityDistributions.tP(stat, df1);
        }

        double pooledSd = Math.sqrt(((a.length - 1) * va + (b.length - 1) * vb) / (a.length + b.length - 2));
        double d = pooledSd == 0 ? 0 : (meanA - meanB) / pooledSd;
        double correction = 1 - 3.0 / (4.0 * (a.length + b.length) - 9);
        effect = d * correction;

        double seG = Math.sqrt((double) (a.length + b.length) / (a.length * b.length)
                + effect * effect / (2.0 * (a.length + b.length)));
        lo = effect - 1.96 * seG;
        hi = effect + 1.96 * seG;
        classifyEffect();
    }

    @Override
    protected boolean intervalDescribesEffect() {
        return true;
    }

    @Override
    public String statisticSymbol() {
        return "t = ";
    }

    @Override
    public String leafSubtitle(String metric, boolean hasRest) {
        boolean showLift = hasRest && !Double.isNaN(lift);
        return "x̄ " + fmt(meanA) + (showLift ? " · " + sgnPct(lift) : "");
    }

    @Override
    public List<Object[]> leafStats(String metric, double[] segment, double[] others, boolean hasRest) {
        List<Object[]> stats = new ArrayList<>();
        double[] ci = FinalAnalitikMathematics.meanCI(segment);
        stats.add(new Object[]{"Среднее в сегменте", fmt(meanA) + "  [" + fmt(ci[0]) + " – " + fmt(ci[1]) + "]"});
        stats.add(new Object[]{"Медиана", fmt(FinalAnalitikMathematics.median(segment))});
        if (hasRest) {
            stats.add(new Object[]{"Среднее у остальных", fmt(meanB)});
            stats.add(new Object[]{"Относительная разница", Double.isNaN(lift) ? "—" : sgnPct(lift)});
        }
        return stats;
    }

    @Override
    public String insightText(String metric) {
        String by = Double.isNaN(lift) ? "—" : pctAbs(lift);
        return "«" + metric + "» " + (effect >= 0 ? "выше" : "ниже") + " на " + by
                + " (" + fmt(meanA) + " vs " + fmt(meanB) + ", g = " + fmt(effect) + ", q " + fmtPq(q) + ")";
    }
}
