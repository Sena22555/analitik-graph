package com.analytics.brain.formula;

import static com.analytics.brain.formula.FinalFormulaValues.bool;
import static com.analytics.brain.formula.FinalFormulaValues.number;
import static com.analytics.brain.formula.FinalFormulaValues.text;

public enum EnumFormulaOperator {

    OR {
        @Override
        public Object apply(Object a, Object b) {
            return bool(a) || bool(b);
        }
    },
    AND {
        @Override
        public Object apply(Object a, Object b) {
            return bool(a) && bool(b);
        }
    },
    GREATER_OR_EQUAL {
        @Override
        public Object apply(Object a, Object b) {
            return bothNumbers(a, b) ? number(a) >= number(b) : sameText(a, b);
        }
    },
    LESS_OR_EQUAL {
        @Override
        public Object apply(Object a, Object b) {
            return bothNumbers(a, b) ? number(a) <= number(b) : sameText(a, b);
        }
    },
    NOT_EQUAL {
        @Override
        public Object apply(Object a, Object b) {
            return bothNumbers(a, b) ? number(a) != number(b) : !sameText(a, b);
        }
    },
    EQUAL {
        @Override
        public Object apply(Object a, Object b) {
            return bothNumbers(a, b) ? number(a) == number(b) : sameText(a, b);
        }
    },
    GREATER {
        @Override
        public Object apply(Object a, Object b) {
            return bothNumbers(a, b) ? number(a) > number(b) : sameText(a, b);
        }
    },
    LESS {
        @Override
        public Object apply(Object a, Object b) {
            return bothNumbers(a, b) ? number(a) < number(b) : sameText(a, b);
        }
    },
    PLUS {
        @Override
        public Object apply(Object a, Object b) {
            if (a instanceof String || b instanceof String) return text(a) + text(b);
            return number(a) + number(b);
        }
    },
    MINUS {
        @Override
        public Object apply(Object a, Object b) {
            return number(a) - number(b);
        }
    },
    MULTIPLY {
        @Override
        public Object apply(Object a, Object b) {
            return number(a) * number(b);
        }
    },
    DIVIDE {
        @Override
        public Object apply(Object a, Object b) {
            double divisor = number(b);
            return divisor == 0 ? Double.NaN : number(a) / divisor;
        }
    },
    MODULO {
        @Override
        public Object apply(Object a, Object b) {
            return number(a) % number(b);
        }
    },
    NEGATE {
        @Override
        public Object apply(Object a, Object ignored) {
            return -number(a);
        }
    },
    NOT {
        @Override
        public Object apply(Object a, Object ignored) {
            return !bool(a);
        }
    };

    public abstract Object apply(Object a, Object b);

    private static boolean bothNumbers(Object a, Object b) {
        return a instanceof Double && b instanceof Double;
    }

    private static boolean sameText(Object a, Object b) {
        return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
    }
}
