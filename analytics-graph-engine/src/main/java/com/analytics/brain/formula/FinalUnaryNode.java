package com.analytics.brain.formula;

public final class FinalUnaryNode implements InterfaceFormulaNode {

    private final EnumFormulaOperator operator;
    private final InterfaceFormulaNode operand;

    public FinalUnaryNode(EnumFormulaOperator operator, InterfaceFormulaNode operand) {
        this.operator = operator;
        this.operand = operand;
    }

    @Override
    public Object evaluate(int row) {
        return operator.apply(operand.evaluate(row), null);
    }
}
