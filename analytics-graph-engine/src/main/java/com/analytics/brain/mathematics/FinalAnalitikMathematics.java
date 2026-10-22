package com.analytics.brain.mathematics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

public final class FinalAnalitikMathematics {

    public static final double Z_95 = 1.959964;

    private FinalAnalitikMathematics() {
    }

    public static double[] clean(double[] x) {
        int count = 0;
        for (double v : x) {
            if (!Double.isNaN(v)) count++;
        }
        double[] result = new double[count];
        int i = 0;
        for (double v : x) {
            if (!Double.isNaN(v)) result[i++] = v;
        }
        return result;
    }

    public static double mean(double[] x) {
        if (x.length == 0) return Double.NaN;
        double sum = 0;
        for (double v : x) sum += v;
        return sum / x.length;
    }

    public static double variance(double[] x) {
        if (x.length < 2) return 0;
        double m = mean(x);
        double sum = 0;
        for (double v : x) sum += (v - m) * (v - m);
        return sum / (x.length - 1);
    }

    public static double sd(double[] x) {
        return Math.sqrt(variance(x));
    }

    public static double[] sorted(double[] x) {
        double[] copy = x.clone();
        Arrays.sort(copy);
        return copy;
    }

    public static double quantile(double[] sorted, double p) {
        if (sorted.length == 0) return Double.NaN;
        double pos = (sorted.length - 1) * p;
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return sorted[lo] + (pos - lo) * (sorted[hi] - sorted[lo]);
    }

    public static double median(double[] x) {
        return quantile(sorted(x), .5);
    }

    public static double skewness(double[] x) {
        int n = x.length;
        if (n < 3) return 0;
        double m = mean(x);
        double m2 = 0;
        double m3 = 0;
        for (double v : x) {
            double d = v - m;
            m2 += d * d;
            m3 += d * d * d;
        }
        m2 /= n;
        m3 /= n;
        if (m2 == 0) return 0;
        return m3 / Math.pow(m2, 1.5);
    }

    public static double kurtosisExcess(double[] x) {
        int n = x.length;
        if (n < 4) return 0;
        double m = mean(x);
        double m2 = 0;
        double m4 = 0;
        for (double v : x) {
            double d = v - m;
            m2 += d * d;
            m4 += d * d * d * d;
        }
        m2 /= n;
        m4 /= n;
        if (m2 == 0) return 0;
        return m4 / (m2 * m2) - 3;
    }

    public static double[] meanCI(double[] x) {
        int n = x.length;
        double m = mean(x);
        if (n < 2) return new double[]{m, m};
        double t = FinalProbabilityDistributions.tQuantile(n - 1, 0.975);
        double half = t * sd(x) / Math.sqrt(n);
        return new double[]{m - half, m + half};
    }

    public static double[] wilson(int k, int n) {
        if (n == 0) return new double[]{0, 0};
        double z = Z_95;
        double p = (double) k / n;
        double denom = 1 + z * z / n;
        double center = (p + z * z / (2.0 * n)) / denom;
        double half = z * Math.sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / denom;
        return new double[]{Math.max(0, center - half), Math.min(1, center + half)};
    }

    public static double jarqueBeraP(double[] x) {
        int n = x.length;
        if (n < 8) return Double.NaN;
        double s = skewness(x);
        double k = kurtosisExcess(x);
        double jb = n / 6.0 * (s * s + k * k / 4);
        return FinalProbabilityDistributions.chi2P(jb, 2);
    }

    public static double[] ranks(double[] x) {
        int n = x.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> x[i]));

        double[] rank = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && x[order[j + 1]] == x[order[i]]) j++;
            double average = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) rank[order[k]] = average;
            i = j + 1;
        }
        return rank;
    }

    public static double pearson(double[] a, double[] b) {
        int n = a.length;
        if (n < 3) return 0;
        double ma = mean(a);
        double mb = mean(b);
        double sab = 0;
        double saa = 0;
        double sbb = 0;
        for (int i = 0; i < n; i++) {
            double x = a[i] - ma;
            double y = b[i] - mb;
            sab += x * y;
            saa += x * x;
            sbb += y * y;
        }
        if (saa == 0 || sbb == 0) return 0;
        return sab / Math.sqrt(saa * sbb);
    }

    public static double[][] bothPresent(double[] a, double[] b) {
        List<double[]> pairs = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            if (!Double.isNaN(a[i]) && !Double.isNaN(b[i])) pairs.add(new double[]{a[i], b[i]});
        }
        double[] x = new double[pairs.size()];
        double[] y = new double[pairs.size()];
        for (int i = 0; i < pairs.size(); i++) {
            x[i] = pairs.get(i)[0];
            y[i] = pairs.get(i)[1];
        }
        return new double[][]{x, y};
    }

    public static List<double[]> histogram(double[] x) {
        List<double[]> bins = new ArrayList<>();
        if (x.length == 0) return bins;

        TreeMap<Double, Integer> distinct = new TreeMap<>();
        for (double v : x) distinct.merge(v, 1, Integer::sum);
        if (distinct.size() <= 8) {
            distinct.forEach((value, count) -> bins.add(new double[]{value, value, count}));
            return bins;
        }

        double[] s = sorted(x);
        double iqr = quantile(s, .75) - quantile(s, .25);
        double min = s[0];
        double max = s[s.length - 1];
        double h = 2 * iqr * Math.pow(x.length, -1.0 / 3);
        int k = h <= 0 ? 6 : (int) Math.ceil((max - min) / h);
        k = Math.max(4, Math.min(8, k));

        double width = niceStep((max - min) / k);
        double start = Math.floor(min / width) * width;
        k = (int) Math.ceil((max - start) / width + 1e-9);
        if (k < 1) k = 1;

        int[] counts = new int[k];
        for (double v : x) {
            int b = (int) Math.floor((v - start) / width);
            if (b >= k) b = k - 1;
            if (b < 0) b = 0;
            counts[b]++;
        }
        for (int b = 0; b < k; b++) {
            if (counts[b] > 0 || (b > 0 && b < k - 1)) {
                bins.add(new double[]{start + b * width, start + (b + 1) * width, counts[b]});
            }
        }
        return bins;
    }

    public static double niceStep(double v) {
        if (v <= 0) return 1;
        double power = Math.pow(10, Math.floor(Math.log10(v)));
        double f = v / power;
        double nice;
        if (f <= 1) {
            nice = 1;
        } else if (f <= 2) {
            nice = 2;
        } else if (f <= 2.5) {
            nice = 2.5;
        } else if (f <= 5) {
            nice = 5;
        } else {
            nice = 10;
        }
        return nice * power;
    }

    public static String stars(double p) {
        if (p < 0.001) return "***";
        if (p < 0.01) return "**";
        if (p < 0.05) return "*";
        return "";
    }
}
