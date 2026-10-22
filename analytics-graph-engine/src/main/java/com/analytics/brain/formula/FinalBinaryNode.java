package com.analytics.brain.formula;

public final class FinalBinaryNode implements InterfaceFormulaNode {

    private final EnumFormulaOperator operator;
    private final InterfaceFormulaNode left;
    private final InterfaceFormulaNode right;

    public FinalBinaryNode(EnumFormulaOperator operator, InterfaceFormulaNode left, InterfaceFormulaNode right) {
        this.operator = operator;
        this.left = left;
        this.right = right;
    }

    @Override
    public Object evaluate(int row) {
        Object a = left.evaluate(row);
        Object b = right.evaluate(row);
        return operator.apply(a, b);
    }
}
