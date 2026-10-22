package com.analytics.brain.formula;

import java.util.ArrayList;
import java.util.List;

public final class FinalFunctionNode implements InterfaceFormulaNode {

    private final EnumFormulaFunction function;
    private final List<InterfaceFormulaNode> arguments;

    public FinalFunctionNode(EnumFormulaFunction function, List<InterfaceFormulaNode> arguments) {
        this.function = function;
        this.arguments = arguments;
    }

    @Override
    public Object evaluate(int row) {
        List<Object> values = new ArrayList<>();
        for (InterfaceFormulaNode argument : arguments) values.add(argument.evaluate(row));
        return function.call(values);
    }
}
