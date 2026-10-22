package com.analytics.brain.data;

import java.util.List;

public final class FinalColumn {

    public final String name;
    public EnumColumnType type;
    public double[] num;
    public String[] cat;
    public List<String> levels;
    public int missing;
    public int unique;
    public String granularity;
    public boolean countLike;

    public FinalColumn(String name) {
        this.name = name;
    }

    public boolean analyzable() {
        return type != null && type.analyzable();
    }

    public boolean isDim() {
        return type != null && type.dimension();
    }

    public String typeRu() {
        return type == null ? EnumColumnType.TEXT.russianName() : type.russianName();
    }
}
