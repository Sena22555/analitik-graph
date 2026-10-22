package com.analytics.brain.mathematics;

import org.apache.commons.math3.distribution.ChiSquaredDistribution;
import org.apache.commons.math3.distribution.FDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.TDistribution;

public final class FinalProbabilityDistributions {

    private static final NormalDistribution NORMAL = new NormalDistribution();

    private FinalProbabilityDistributions() {
    }

    public static double tP(double t, double df) {
        if (Double.isNaN(t) || df <= 0) return 1;
        if (Double.isInfinite(t)) return 0;
        return 2 * new TDistribution(df).cumulativeProbability(-Math.abs(t));
    }

    public static double tQuantile(double df, double probability) {
        return new TDistribution(df).inverseCumulativeProbability(probability);
    }

    public static double chi2P(double x, double df) {
        if (Double.isNaN(x) || df <= 0) return 1;
        if (x <= 0) return 1;
        return 1 - new ChiSquaredDistribution(df).cumulativeProbability(x);
    }

    public static double fP(double f, double df1, double df2) {
        if (Double.isNaN(f) || df1 <= 0 || df2 <= 0) return 1;
        if (f <= 0) return 1;
        return 1 - new FDistribution(df1, df2).cumulativeProbability(f);
    }

    public static double zP(double z) {
        if (Double.isNaN(z)) return 1;
        return 2 * NORMAL.cumulativeProbability(-Math.abs(z));
    }
}
