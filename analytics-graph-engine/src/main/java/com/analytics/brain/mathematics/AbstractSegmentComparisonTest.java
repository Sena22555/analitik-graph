package com.analytics.brain.mathematics;

import java.util.List;

public abstract class AbstractSegmentComparisonTest extends AbstractAnalitikMathematicsTest {

    public int nA;
    public int nB;

    protected AbstractSegmentComparisonTest(String test, String effectName, int nA, int nB) {
        super(test, effectName);
        this.nA = nA;
        this.nB = nB;
        this.n = nA + nB;
    }

    public static AbstractSegmentComparisonTest compare(boolean countLike, double[] segment, double[] others) {
        if (countLike) return FinalTwoProportionTest.ofPositive(segment, others);
        return new FinalWelchTest(segment, others);
    }

    @Override
    protected final EnumEffectScale scale() {
        return EnumEffectScale.DIFFERENCE;
    }

    public final boolean effectIntervalShown() {
        return !Double.isNaN(lo) && intervalDescribesEffect();
    }

    protected abstract boolean intervalDescribesEffect();

    public abstract String statisticSymbol();

    public abstract String leafSubtitle(String metric, boolean hasRest);

    public abstract List<Object[]> leafStats(String metric, double[] segment, double[] others, boolean hasRest);

    public abstract String insightText(String metric);
}
