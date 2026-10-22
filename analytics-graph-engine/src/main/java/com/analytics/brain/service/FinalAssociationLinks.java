package com.analytics.brain.service;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.graph.FinalGraphLink;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;
import com.analytics.brain.mathematics.FinalAnovaTest;
import com.analytics.brain.mathematics.FinalChiSquareTest;
import com.analytics.brain.mathematics.FinalSpearmanTest;

import java.util.Map;

import static com.analytics.brain.service.FinalGraphPaths.*;
import static com.analytics.brain.text.FinalFormatRu.*;

public final class FinalAssociationLinks {

    private FinalAssociationLinks() {
    }

    public static FinalGraphLink association(FinalColumn a, FinalColumn b) {
        boolean aNumber = a.type == EnumColumnType.NUMERIC;
        boolean bNumber = b.type == EnumColumnType.NUMERIC;
        boolean aDate = a.type == EnumColumnType.DATE;
        boolean bDate = b.type == EnumColumnType.DATE;

        if ((aNumber || aDate) && (bNumber || bDate) && !(aDate && bDate)) {
            return correlationLink(a, b, aDate, bDate);
        }
        if (a.isDim() && b.isDim()) {
            return chiSquareLink(a, b);
        }
        FinalColumn category = a.isDim() ? a : b;
        FinalColumn number = a.isDim() ? b : a;
        return anovaLink(category, number);
    }

    private static FinalGraphLink correlationLink(FinalColumn a, FinalColumn b, boolean aDate, boolean bDate) {
        FinalSpearmanTest t = new FinalSpearmanTest(a.num, b.num);
        if (t.n < 8) return null;

        FinalGraphLink link = new FinalGraphLink(varId(a), varId(b), "brain", "");
        link.test = t;
        link.direction = t.effect >= 0 ? "positive" : "negative";
        link.label = "ρ = " + sgn(t.effect);

        String ci = "95% ДИ [" + fmt(t.lo) + "; " + fmt(t.hi) + "]";
        if (aDate || bDate) {
            FinalColumn metric = aDate ? b : a;
            link.text = "Тренд во времени: «" + metric.name + "» " + (t.effect >= 0 ? "растёт" : "снижается")
                    + " со временем. ρ Спирмена = " + sgn(t.effect) + ", " + ci + ", p " + fmtPq(t.p) + ".";
        } else {
            double[][] both = FinalAnalitikMathematics.bothPresent(a.num, b.num);
            double r = FinalAnalitikMathematics.pearson(both[0], both[1]);
            link.text = "Чем выше «" + a.name + "», тем " + (t.effect >= 0 ? "выше" : "ниже") + " «" + b.name + "». "
                    + "ρ Спирмена = " + sgn(t.effect) + ", " + ci + ", r Пирсона = " + sgn(r)
                    + ", n = " + t.n + ", p " + fmtPq(t.p) + ".";
        }
        return link;
    }

    private static FinalGraphLink chiSquareLink(FinalColumn a, FinalColumn b) {
        FinalChiSquareTest chi = new FinalChiSquareTest(a.cat, b.cat);
        if (chi.observed == null) return null;

        FinalGraphLink link = new FinalGraphLink(varId(a), varId(b), "brain", "");
        link.test = chi;
        link.label = "V = " + fmt(chi.effect);

        double bestResidual = 0;
        String cell = "";
        for (int i = 0; i < chi.rows.size(); i++) {
            for (int j = 0; j < chi.cols.size(); j++) {
                double expected = chi.expected[i][j];
                if (expected < 3) continue;
                double residual = chi.residual(i, j);
                if (residual > bestResidual) {
                    bestResidual = residual;
                    cell = "«" + chi.rows.get(i) + "» чаще сочетается с «" + chi.cols.get(j) + "» (в "
                            + fmt(chi.observed[i][j] / expected) + "× чаще ожидаемого)";
                }
            }
        }

        link.text = "Распределения «" + a.name + "» и «" + b.name + "» зависят друг от друга. "
                + "χ² = " + fmt(chi.stat) + ", df = " + (int) chi.df1 + ", p " + fmtPq(chi.p)
                + ", V Крамера = " + fmt(chi.effect) + ". " + (cell.isEmpty() ? "" : cell + ".");
        return link;
    }

    private static FinalGraphLink anovaLink(FinalColumn category, FinalColumn number) {
        FinalAnovaTest anova = new FinalAnovaTest(category.cat, number.num);
        if (anova.groups.size() < 2) return null;

        FinalGraphLink link = new FinalGraphLink(varId(category), varId(number), "brain", "");
        link.test = anova;
        link.label = "η = " + fmt(anova.effect);

        String top = null;
        String bottom = null;
        double topMean = -Double.MAX_VALUE;
        double bottomMean = Double.MAX_VALUE;
        for (Map.Entry<String, double[]> group : anova.groups.entrySet()) {
            if (group.getValue().length < 5) continue;
            double m = FinalAnalitikMathematics.mean(group.getValue());
            if (m > topMean) {
                topMean = m;
                top = group.getKey();
            }
            if (m < bottomMean) {
                bottomMean = m;
                bottom = group.getKey();
            }
        }

        String extremes = top == null ? ""
                : "Максимум у «" + top + "» (x̄ = " + fmt(topMean) + "), минимум у «" + bottom
                + "» (x̄ = " + fmt(bottomMean) + ").";
        link.text = "«" + number.name + "» различается между группами «" + category.name + "». "
                + "ANOVA F(" + (int) anova.df1 + ", " + (int) anova.df2 + ") = " + fmt(anova.stat)
                + ", p " + fmtPq(anova.p) + "; η² = " + fmt(anova.eta2) + ", ω² = " + fmt(anova.omega2)
                + "; Краскел–Уоллис H = " + fmt(anova.kwH) + ", p " + fmtPq(anova.kwP) + ". " + extremes;
        return link;
    }
}
