package com.analytics.brain.mathematics;

public abstract class AbstractAnalitikMathematicsTest implements InterfaceSignificanceTest {

    public static final double ALPHA = 0.05;

    public String test;
    public double stat;
    public double df1 = Double.NaN;
    public double df2 = Double.NaN;
    public double p = 1;
    public double q = 1;
    public String effectName;
    public double effect;
    public double lo = Double.NaN;
    public double hi = Double.NaN;
    public int n;
    public String level = "none";
    public double strength;

    protected AbstractAnalitikMathematicsTest(String test, String effectName) {
        this.test = test;
        this.effectName = effectName;
    }

    protected abstract EnumEffectScale scale();

    protected final void classifyEffect() {
        level = scale().level(effect);
        strength = scale().strength(effect);
    }

    public final boolean significant() {
        return q < ALPHA && !"none".equals(level);
    }

    @Override
    public final double pValue() {
        return p;
    }

    @Override
    public final void acceptQValue(double q) {
        this.q = q;
    }
}
