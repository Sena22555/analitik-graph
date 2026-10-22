package com.analytics.brain.mathematics;

public abstract class AbstractCorrelationTest extends AbstractAnalitikMathematicsTest {

    protected AbstractCorrelationTest(String test, String effectName) {
        super(test, effectName);
    }

    @Override
    protected final EnumEffectScale scale() {
        return EnumEffectScale.CORRELATION;
    }
}
