package com.analytics.brain.text;

import java.util.Locale;

public final class FinalFormatRu {

    private static final String NBSP = " ";

    private FinalFormatRu() {
    }

    public static String fmt(double v) {
        if (Double.isNaN(v)) return "—";
        if (Double.isInfinite(v)) return v > 0 ? "∞" : "−∞";

        double abs = Math.abs(v);
        String s;
        if (abs >= 1000) {
            s = String.format(Locale.US, "%,.0f", v).replace(",", NBSP);
        } else if (abs >= 100) {
            s = String.format(Locale.US, "%.1f", v);
        } else if (abs >= 0.01 || abs == 0) {
            s = String.format(Locale.US, "%.2f", v);
        } else {
            s = String.format(Locale.US, "%.3g", v);
        }

        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace('.', ',').replace("-", "−");
    }

    public static String fmtInt(long v) {
        return String.format(Locale.US, "%,d", v).replace(",", NBSP);
    }

    public static String sgn(double v) {
        return (v > 0 ? "+" : "") + fmt(v);
    }

    public static String pct(double v) {
        if (Double.isNaN(v)) return "—";
        double p = v * 100;
        String s = Math.abs(p) >= 10 || p == 0
                ? String.format(Locale.US, "%.1f", p)
                : String.format(Locale.US, "%.2f", p);
        return s.replaceAll("\\.?0+$", "").replace('.', ',') + "%";
    }

    public static String pctAbs(double v) {
        return pct(Math.abs(v));
    }

    public static String sgnPct(double v) {
        return (v >= 0 ? "+" : "−") + pct(Math.abs(v));
    }

    public static String sgnPP(double v) {
        double p = v * 100;
        String s = String.format(Locale.US, "%.1f", Math.abs(p)).replace('.', ',');
        return (p >= 0 ? "+" : "−") + s + " п.п.";
    }

    public static String fmtP(double p) {
        if (p < 0.001) return "<0,001";
        return String.format(Locale.US, "%.3f", p).replace('.', ',');
    }

    public static String fmtPq(double p) {
        return p < 0.001 ? "< 0,001" : "= " + fmtP(p);
    }

    public static String levelRuF(String level) {
        switch (level == null ? "" : level) {
            case "strong":
                return "сильная";
            case "medium":
                return "средняя";
            case "weak":
                return "слабая";
            default:
                return "пренебрежимая";
        }
    }

    public static String levelRu(String level) {
        switch (level == null ? "" : level) {
            case "strong":
                return "сильный";
            case "medium":
                return "средний";
            case "weak":
                return "слабый";
            default:
                return "пренебрежимый";
        }
    }

    public static int levelRank(String level) {
        if ("strong".equals(level)) return 3;
        if ("medium".equals(level)) return 2;
        if ("weak".equals(level)) return 1;
        return 0;
    }

    public static String plural(int n, String one, String few, String many) {
        int lastTwo = Math.abs(n) % 100;
        int last = lastTwo % 10;
        if (lastTwo > 10 && lastTwo < 20) return many;
        if (last == 1) return one;
        if (last >= 2 && last <= 4) return few;
        return many;
    }

    public static String granularityRu(String granularity) {
        if (granularity == null) return "";
        switch (granularity) {
            case "day":
                return " · по дням";
            case "week":
                return " · по неделям";
            case "month":
                return " · по месяцам";
            default:
                return " · по годам";
        }
    }

    public static String purchaseVerb(String columnName) {
        String x = columnName.toLowerCase(Locale.ROOT);
        if (x.contains("покуп") || x.contains("purchase") || x.contains("заказ") || x.contains("order")) {
            return "купили";
        }
        if (x.contains("корзин") || x.contains("cart")) return "добавили в корзину";
        if (x.contains("обращ") || x.contains("support") || x.contains("тикет")) return "обращались";
        return "«" + columnName + "» > 0 у";
    }
}
