package com.analytics.brain.analysis;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.exception.ExceptionAnalitikData;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;
import com.analytics.brain.mathematics.FinalAnovaTest;
import com.analytics.brain.mathematics.FinalMultipleComparisons;
import com.analytics.brain.mathematics.FinalProbabilityDistributions;
import com.analytics.brain.mathematics.FinalRegressionCoefficientTest;
import com.analytics.brain.mathematics.FinalSpearmanTest;
import com.analytics.brain.query.FinalCondition;
import com.analytics.brain.query.FinalQueryEngine;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.QRDecomposition;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.analytics.brain.text.FinalFormatRu.fmt;
import static com.analytics.brain.text.FinalFormatRu.fmtP;
import static com.analytics.brain.text.FinalFormatRu.fmtPq;

public final class FinalDriversAnalysis extends AbstractAnalitikAnalysis {

    private static final double LEAK_THRESHOLD = 0.9;
    private static final int ROWS_PER_FACTOR = 15;

    private final String target;
    private final List<String> factorNames;
    private final List<FinalCondition> filters;

    public FinalDriversAnalysis(FinalDataset ds, String target, List<String> factorNames, List<FinalCondition> filters) {
        super(ds);
        this.target = target;
        this.factorNames = factorNames;
        this.filters = filters;
    }

    private static final class Factor {

        final String name;
        final double[] values;
        final String column;

        Factor(String name, double[] values, String column) {
            this.name = name;
            this.values = values;
            this.column = column;
        }

        boolean dummy() {
            return name.contains(" = ");
        }
    }

    @Override
    protected void compute(Map<String, Object> result) {
        FinalColumn y = FinalQueryEngine.need(ds, target);
        if (y.type != EnumColumnType.NUMERIC) throw new ExceptionAnalitikData("Целевая метрика должна быть числовой");
        int[] idx = FinalQueryEngine.filter(ds, filters);

        List<FinalColumn> candidates = new ArrayList<>();
        if (factorNames != null && !factorNames.isEmpty()) {
            for (String f : factorNames) candidates.add(FinalQueryEngine.need(ds, f));
        } else {
            for (FinalColumn c : ds.columns) {
                if (c.analyzable() && !c.name.equals(y.name) && c.type != EnumColumnType.DATE) candidates.add(c);
            }
        }

        List<String> excluded = new ArrayList<>();
        List<FinalColumn> used = withoutLeaks(y, candidates, excluded);
        List<Integer> rows = completeRows(idx, y, used);
        int n = rows.size();

        Map<String, String> baseLevel = new LinkedHashMap<>();
        List<Factor> factors = designMatrix(used, rows, baseLevel);

        double[] yv = new double[n];
        for (int k = 0; k < n; k++) yv[k] = y.num[rows.get(k)];
        double ySd = FinalAnalitikMathematics.sd(yv);

        List<String> collinear = new ArrayList<>();
        factors = dropCollinear(factors, yv, n, collinear);
        if (!collinear.isEmpty()) {
            excluded.add("коллинеарны с другими факторами: "
                    + String.join(", ", collinear.subList(0, Math.min(6, collinear.size())))
                    + (collinear.size() > 6 ? " …" : ""));
        }

        int p = factors.size();
        if (p == 0 || n < p + 10) {
            throw new ExceptionAnalitikData("Недостаточно данных для модели: наблюдений " + n + ", признаков " + p);
        }

        double[][] x = new double[n][p];
        for (int k = 0; k < n; k++) {
            for (int j = 0; j < p; j++) x[k][j] = factors.get(j).values[k];
        }
        OLSMultipleLinearRegression ols = new OLSMultipleLinearRegression();
        ols.newSampleData(yv, x);
        double[] beta = ols.estimateRegressionParameters();
        double[] se = ols.estimateRegressionParametersStandardErrors();
        double r2 = ols.calculateRSquared();
        double adjustedR2 = ols.calculateAdjustedRSquared();
        int df = n - p - 1;
        double f = (r2 / p) / ((1 - r2) / Math.max(1, df));
        double fP = FinalProbabilityDistributions.fP(f, p, df);

        List<Object[]> coefs = new ArrayList<>();
        List<FinalRegressionCoefficientTest> tests = new ArrayList<>();
        for (int j = 0; j < p; j++) {
            Factor factor = factors.get(j);
            double b = beta[j + 1];
            FinalRegressionCoefficientTest test = new FinalRegressionCoefficientTest(factor.name, b, se[j + 1], df);
            double standardized = ySd == 0 ? 0 : b * (factor.dummy() ? FinalAnalitikMathematics.sd(factor.values) : 1) / ySd;
            coefs.add(new Object[]{factor.name, b, standardized, test.p, factor.dummy(), factor.column});
            tests.add(test);
        }
        FinalMultipleComparisons.applyFdr(tests);
        for (int j = 0; j < coefs.size(); j++) coefs.get(j)[3] = tests.get(j).q;
        coefs.sort((a, b) -> Double.compare(Math.abs((double) b[2]), Math.abs((double) a[2])));

        List<List<Object>> table = new ArrayList<>();
        for (Object[] c : coefs) {
            boolean dummy = (boolean) c[4];
            String comparedTo = dummy ? "vs «" + baseLevel.get((String) c[5]) + "»" : "на +1 σ фактора";
            double q = (double) c[3];
            table.add(Arrays.asList(c[0], FinalQueryEngine.num((double) c[1]), comparedTo,
                    FinalQueryEngine.num((double) c[2]), fmtP(q) + " " + FinalAnalitikMathematics.stars(q)));
        }

        boolean binaryTarget = y.countLike || isBinary(yv);
        String summary = "МНК-регрессия, n = " + n + ", признаков " + p + ": R² = " + fmt(r2)
                + " (скорр. " + fmt(adjustedR2) + "), F-тест p " + fmtPq(fP)
                + ". Эффекты очищены от влияния остальных факторов; q — с поправкой Бенджамини–Хохберга."
                + (binaryTarget ? " Для счётной/бинарной цели это линейная вероятностная модель — "
                + "трактуйте эффекты как изменение среднего." : "");

        result.put("title", "Драйверы «" + y.name + "»: многофакторная модель");
        result.put("head", List.of("Фактор", "Эффект, ед. цели", "Сравнение", "Станд. β", "q (FDR)"));
        result.put("formats", List.of("text", "num", "text", "num", "text"));
        result.put("rows", table);
        result.put("summary", summary);
        result.put("r2", FinalQueryEngine.num(r2));
        result.put("n", n);
        if (!excluded.isEmpty()) result.put("note", "Исключены из модели: " + String.join("; ", excluded));
    }

    private List<FinalColumn> withoutLeaks(FinalColumn y, List<FinalColumn> candidates, List<String> excluded) {
        Set<String> derivedFromTarget = ds.derivedFrom.getOrDefault(y.name, Set.of());
        List<FinalColumn> used = new ArrayList<>();
        for (FinalColumn c : candidates) {
            if (c.name.equals(y.name)) continue;
            boolean computed = derivedFromTarget.contains(c.name)
                    || ds.derivedFrom.getOrDefault(c.name, Set.of()).contains(y.name);
            if (computed) {
                excluded.add(c.name + " (вычислена из цели)");
                continue;
            }
            double strength = c.type == EnumColumnType.NUMERIC
                    ? Math.abs(new FinalSpearmanTest(c.num, y.num).effect)
                    : new FinalAnovaTest(c.cat, y.num).effect;
            if (strength >= LEAK_THRESHOLD) {
                excluded.add(c.name + " (почти тождественна цели: " + fmt(strength) + " — вероятная утечка)");
                continue;
            }
            used.add(c);
        }
        return used;
    }

    private static List<Integer> completeRows(int[] idx, FinalColumn y, List<FinalColumn> used) {
        List<Integer> rows = new ArrayList<>();
        for (int i : idx) {
            if (Double.isNaN(y.num[i])) continue;
            boolean complete = true;
            for (FinalColumn c : used) {
                boolean missing = c.type == EnumColumnType.NUMERIC ? Double.isNaN(c.num[i]) : c.cat[i] == null;
                if (missing) {
                    complete = false;
                    break;
                }
            }
            if (complete) rows.add(i);
        }
        return rows;
    }

    private static List<Factor> designMatrix(List<FinalColumn> used, List<Integer> rows, Map<String, String> baseLevel) {
        int n = rows.size();
        List<Factor> factors = new ArrayList<>();
        for (FinalColumn c : used) {
            if (c.type == EnumColumnType.NUMERIC) {
                double[] v = new double[n];
                for (int k = 0; k < n; k++) v[k] = c.num[rows.get(k)];
                double mean = FinalAnalitikMathematics.mean(v);
                double sd = FinalAnalitikMathematics.sd(v);
                if (sd == 0) continue;
                for (int k = 0; k < n; k++) v[k] = (v[k] - mean) / sd;
                factors.add(new Factor(c.name, v, c.name));
                continue;
            }

            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int i : rows) counts.merge(c.cat[i], 1, Integer::sum);
            List<String> levels = counts.entrySet().stream()
                    .sorted((a, b) -> b.getValue() - a.getValue())
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            if (levels.size() < 2) continue;
            baseLevel.put(c.name, levels.get(0));

            int minCount = Math.max(5, n / 200);
            for (String level : levels.subList(1, Math.min(levels.size(), 10))) {
                if (counts.get(level) < minCount) continue;
                double[] v = new double[n];
                for (int k = 0; k < n; k++) v[k] = level.equals(c.cat[rows.get(k)]) ? 1 : 0;
                factors.add(new Factor(c.name + " = " + level, v, c.name));
            }
        }
        return factors;
    }

    private static List<Factor> dropCollinear(List<Factor> factors, double[] yv, int n, List<String> collinear) {
        Integer[] order = new Integer[factors.size()];
        double[] strength = new double[factors.size()];
        for (int j = 0; j < order.length; j++) {
            order[j] = j;
            strength[j] = Math.abs(FinalAnalitikMathematics.pearson(factors.get(j).values, yv));
        }
        Arrays.sort(order, (a, b) -> Double.compare(strength[b], strength[a]));

        List<Integer> keep = new ArrayList<>();
        int limit = Math.max(1, n / ROWS_PER_FACTOR);
        for (int j : order) {
            if (keep.size() >= limit) break;
            List<Integer> trial = new ArrayList<>(keep);
            trial.add(j);
            double[][] a = new double[n][trial.size() + 1];
            for (int k = 0; k < n; k++) {
                a[k][0] = 1;
                for (int q = 0; q < trial.size(); q++) a[k][q + 1] = factors.get(trial.get(q)).values[k];
            }
            boolean ok = new QRDecomposition(new Array2DRowRealMatrix(a, false), 1e-8).getSolver().isNonSingular();
            if (ok) {
                keep.add(j);
            } else {
                collinear.add(factors.get(j).name);
            }
        }

        List<Factor> kept = new ArrayList<>();
        for (int j : keep) kept.add(factors.get(j));
        return kept;
    }

    private static boolean isBinary(double[] v) {
        for (double x : v) {
            if (x != 0 && x != 1) return false;
        }
        return true;
    }
}
