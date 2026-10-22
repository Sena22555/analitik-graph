package com.analytics.brain.mathematics;

public final class FinalRegressionCoefficientTest extends AbstractCorrelationTest {

    public final String factor;
    public final double beta;

    public FinalRegressionCoefficientTest(String factor, double beta, double standardError, int degreesOfFreedom) {
        super("t-тест коэффициента регрессии", "β");
        this.factor = factor;
        this.beta = beta;
        stat = standardError == 0 ? 0 : beta / standardError;
        df1 = degreesOfFreedom;
        p = FinalProbabilityDistributions.tP(stat, degreesOfFreedom);
    }
}
