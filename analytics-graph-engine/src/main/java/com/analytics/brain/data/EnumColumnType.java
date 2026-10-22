package com.analytics.brain.data;

public enum EnumColumnType {

    NUMERIC("число", true, false),
    CATEGORICAL("категория", true, true),
    DATE("дата", true, true),
    ID("идентификатор", false, false),
    TEXT("текст", false, false);

    private final String russianName;
    private final boolean analyzable;
    private final boolean dimension;

    EnumColumnType(String russianName, boolean analyzable, boolean dimension) {
        this.russianName = russianName;
        this.analyzable = analyzable;
        this.dimension = dimension;
    }

    public String russianName() {
        return russianName;
    }

    public boolean analyzable() {
        return analyzable;
    }

    public boolean dimension() {
        return dimension;
    }
}
