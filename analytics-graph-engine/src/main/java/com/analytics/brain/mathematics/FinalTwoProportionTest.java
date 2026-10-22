package com.analytics.brain.mathematics;

import java.util.ArrayList;
import java.util.List;

import static com.analytics.brain.text.FinalFormatRu.fmt;
import static com.analytics.brain.text.FinalFormatRu.fmtPq;
import static com.analytics.brain.text.FinalFormatRu.pct;
import static com.analytics.brain.text.FinalFormatRu.purchaseVerb;
import static com.analytics.brain.text.FinalFormatRu.sgnPP;

public final class FinalTwoProportionTest extends AbstractSegmentComparisonTest {

    public double pA;
    public double pB;
    public int kA;
    public int kB;

    public FinalTwoProportionTest(int kA, int nA, int kB, int nB) {
        super("z-тест двух долей", "h", nA, nB);
        this.kA = kA;
        this.kB = kB;
        if (nA == 0 || nB == 0) return;

        pA = (double) kA / nA;
        pB = (double) kB / nB;
        double pooled = (double) (kA + kB) / (nA + nB);
        double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / nA + 1.0 / nB));
        stat = se == 0 ? 0 : (pA - pB) / se;
        p = se == 0 ? 1 : FinalProbabilityDistributions.zP(stat);

        double seDiff = Math.sqrt(pA * (1 - pA) / nA + pB * (1 - pB) / nB);
        lo = (pA - pB) - 1.96 * seDiff;
        hi = (pA - pB) + 1.96 * seDiff;
        effect = 2 * Math.asin(Math.sqrt(pA)) - 2 * Math.asin(Math.sqrt(pB));
        classifyEffect();
    }

    public static FinalTwoProportionTest ofPositive(double[] segment, double[] others) {
        return new FinalTwoProportionTest(countPositive(segment), segment.length, countPositive(others), others.length);
    }

    public static int countPositive(double[] values) {
        int count = 0;
        for (double v : values) {
            if (v > 0) count++;
        }
        return count;
    }

    @Override
    protected boolean intervalDescribesEffect() {
        return false;
    }

    @Override
    public String statisticSymbol() {
        return "z = ";
    }

    @Override
    public String leafSubtitle(String metric, boolean hasRest) {
        return purchaseVerb(metric) + " " + pct(pA) + (hasRest ? " · " + sgnPP(pA - pB) : "");
    }

    @Override
    public List<Object[]> leafStats(String metric, double[] segment, double[] others, boolean hasRest) {
        List<Object[]> stats = new ArrayList<>();
        double[] ci = FinalAnalitikMathematics.wilson(kA, nA);
        stats.add(new Object[]{"Доля «" + metric + "» > 0", pct(pA) + "  [" + pct(ci[0]) + " – " + pct(ci[1]) + "]"});
        stats.add(new Object[]{"Среднее значение", fmt(FinalAnalitikMathematics.mean(segment))
                + (hasRest ? " vs " + fmt(FinalAnalitikMathematics.mean(others)) : "")});
        if (hasRest) {
            stats.add(new Object[]{"У остальных", pct(pB)});
            stats.add(new Object[]{"Разница", sgnPP(pA - pB) + "  [" + sgnPP(lo) + "; " + sgnPP(hi) + "]"});
            stats.add(new Object[]{"Во сколько раз чаще", pB > 0 ? fmt(pA / pB) + "×" : "—"});
        }
        return stats;
    }

    @Override
    public String insightText(String metric) {
        return purchaseVerb(metric) + " " + pct(pA) + " против " + pct(pB) + " у остальных ("
                + sgnPP(pA - pB) + ", h = " + fmt(effect) + ", q " + fmtPq(q) + ")";
    }
}
