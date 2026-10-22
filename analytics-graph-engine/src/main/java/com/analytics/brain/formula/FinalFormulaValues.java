package com.analytics.brain.formula;

import com.analytics.brain.data.FinalDataLoader;
import com.analytics.brain.query.FinalQueryEngine;

public final class FinalFormulaValues {

    private FinalFormulaValues() {
    }

    public static double number(Object o) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof Boolean) return (Boolean) o ? 1 : 0;
        if (o == null) return Double.NaN;
        return FinalDataLoader.parseNum(o.toString());
    }

    public static boolean bool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof Double) return (Double) o != 0 && !((Double) o).isNaN();
        return o != null && !o.toString().isEmpty();
    }

    public static String text(Object o) {
        if (o == null) return "";
        if (o instanceof Double) return FinalQueryEngine.trimNum((Double) o);
        return o.toString();
    }
}
