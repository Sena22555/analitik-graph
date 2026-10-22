package com.analytics.brain.mathematics;

public enum EnumEffectScale {

    CORRELATION(0.1, 0.3, 0.5, 1.0),
    DIFFERENCE(0.2, 0.5, 0.8, 1.2);

    private final double weak;
    private final double medium;
    private final double strong;
    private final double fullStrength;

    EnumEffectScale(double weak, double medium, double strong, double fullStrength) {
        this.weak = weak;
        this.medium = medium;
        this.strong = strong;
        this.fullStrength = fullStrength;
    }

    public String level(double effect) {
        double size = Math.abs(effect);
        if (size >= strong) return "strong";
        if (size >= medium) return "medium";
        if (size >= weak) return "weak";
        return "none";
    }

    public double strength(double effect) {
        return Math.min(1, Math.abs(effect) / fullStrength);
    }
}
