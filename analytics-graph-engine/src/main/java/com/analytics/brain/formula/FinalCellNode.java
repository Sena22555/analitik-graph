package com.analytics.brain.formula;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataLoader;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.exception.ExceptionAnalitikData;

public final class FinalCellNode implements InterfaceFormulaNode {

    private final FinalDataset ds;
    private final int index;
    private final FinalColumn column;

    public FinalCellNode(FinalDataset ds, String name) {
        int j = ds.header.indexOf(name);
        if (j < 0) {
            for (int k = 0; k < ds.header.size(); k++) {
                if (ds.header.get(k).equalsIgnoreCase(name.trim())) j = k;
            }
        }
        if (j < 0) throw new ExceptionAnalitikData("Нет колонки [" + name + "]");
        this.ds = ds;
        this.index = j;
        this.column = ds.col(ds.header.get(j));
    }

    @Override
    public Object evaluate(int row) {
        String raw = ds.records.get(row)[index];
        raw = raw == null ? "" : raw.trim();
        if (column != null && column.type == EnumColumnType.NUMERIC) return column.num[row];

        double d = FinalDataLoader.parseNum(raw);
        boolean asNumber = !Double.isNaN(d) && column != null && column.type != EnumColumnType.DATE;
        return asNumber ? (Object) d : raw;
    }
}
