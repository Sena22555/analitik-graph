package com.analytics.brain.mathematics;

public final class FinalSpearmanTest extends AbstractCorrelationTest {

    public FinalSpearmanTest(double[] a, double[] b) {
        super("Корреляция Спирмена", "ρ");
        double[][] pairs = FinalAnalitikMathematics.bothPresent(a, b);
        double[] x = pairs[0];
        double[] y = pairs[1];
        n = x.length;
        if (n < 5) return;

        double rho = FinalAnalitikMathematics.pearson(FinalAnalitikMathematics.ranks(x), FinalAnalitikMathematics.ranks(y));
        rho = Math.max(-0.999999, Math.min(0.999999, rho));
        effect = rho;
        stat = rho * Math.sqrt((n - 2) / (1 - rho * rho));
        df1 = n - 2;
        p = FinalProbabilityDistributions.tP(stat, n - 2);

        double z = 0.5 * Math.log((1 + rho) / (1 - rho));
        double se = Math.sqrt(1.06 / Math.max(1, n - 3));
        lo = Math.tanh(z - 1.96 * se);
        hi = Math.tanh(z + 1.96 * se);
        classifyEffect();
    }
}
