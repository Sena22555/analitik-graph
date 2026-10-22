package com.analytics.brain.formula;

import com.analytics.brain.data.FinalDataLoader;
import com.analytics.brain.exception.ExceptionAnalitikData;
import com.analytics.brain.query.FinalQueryEngine;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static com.analytics.brain.formula.FinalFormulaValues.bool;
import static com.analytics.brain.formula.FinalFormulaValues.number;
import static com.analytics.brain.formula.FinalFormulaValues.text;

public enum EnumFormulaFunction {

    IF("if", "если"),
    ABS("abs"),
    ROUND("round", "округл"),
    LOG("log", "ln"),
    SQRT("sqrt"),
    MIN("min"),
    MAX("max"),
    COALESCE("coalesce"),
    LOWER("lower"),
    UPPER("upper"),
    CONTAINS("contains"),
    YEAR("year"),
    MONTH("month"),
    WEEKDAY("weekday"),
    BUCKET("bucket");

    private final String[] names;

    EnumFormulaFunction(String... names) {
        this.names = names;
    }

    public Object call(List<Object> args) {
        switch (this) {
            case IF: {
                if (bool(args.get(0))) return args.get(1);
                return args.size() > 2 ? args.get(2) : null;
            }
            case ABS: {
                return Math.abs(number(args.get(0)));
            }
            case ROUND: {
                double scale = args.size() > 1 ? Math.pow(10, number(args.get(1))) : 1;
                return Math.round(number(args.get(0)) * scale) / scale;
            }
            case LOG: {
                return Math.log(number(args.get(0)));
            }
            case SQRT: {
                return Math.sqrt(number(args.get(0)));
            }
            case MIN: {
                return args.stream().mapToDouble(FinalFormulaValues::number).min().orElse(Double.NaN);
            }
            case MAX: {
                return args.stream().mapToDouble(FinalFormulaValues::number).max().orElse(Double.NaN);
            }
            case COALESCE: {
                for (Object o : args) {
                    boolean empty = o == null || (o instanceof Double && ((Double) o).isNaN()) || "".equals(o);
                    if (!empty) return o;
                }
                return null;
            }
            case LOWER: {
                return text(args.get(0)).toLowerCase(Locale.ROOT);
            }
            case UPPER: {
                return text(args.get(0)).toUpperCase(Locale.ROOT);
            }
            case CONTAINS: {
                return text(args.get(0)).toLowerCase(Locale.ROOT).contains(text(args.get(1)).toLowerCase(Locale.ROOT));
            }
            case YEAR: {
                LocalDate d = date(args);
                return d == null ? Double.NaN : (double) d.getYear();
            }
            case MONTH: {
                LocalDate d = date(args);
                return d == null ? Double.NaN : (double) d.getMonthValue();
            }
            case WEEKDAY: {
                LocalDate d = date(args);
                return d == null ? Double.NaN : (double) d.getDayOfWeek().getValue();
            }
            case BUCKET: {
                double x = number(args.get(0));
                for (int k = 1; k < args.size(); k++) {
                    if (x < number(args.get(k))) return "< " + FinalQueryEngine.trimNum(number(args.get(k)));
                }
                return "≥ " + FinalQueryEngine.trimNum(number(args.get(args.size() - 1)));
            }
            default:
                throw new ExceptionAnalitikData("Функция не реализована: " + this);
        }
    }

    public static EnumFormulaFunction byName(String name) {
        for (EnumFormulaFunction function : values()) {
            for (String alias : function.names) {
                if (alias.equals(name)) return function;
            }
        }
        throw new ExceptionAnalitikData("Неизвестная функция «" + name + "». Есть: if, abs, round, log, sqrt, "
                + "min, max, coalesce, lower, upper, contains, year, month, weekday, bucket");
    }

    private static LocalDate date(List<Object> args) {
        return FinalDataLoader.parseDate(text(args.get(0)));
    }
}
