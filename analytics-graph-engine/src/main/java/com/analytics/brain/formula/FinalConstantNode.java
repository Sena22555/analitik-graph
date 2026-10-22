package com.analytics.brain.formula;

public final class FinalConstantNode implements InterfaceFormulaNode {

    private final Object value;

    public FinalConstantNode(Object value) {
        this.value = value;
    }

    @Override
    public Object evaluate(int row) {
        return value;
    }
}
