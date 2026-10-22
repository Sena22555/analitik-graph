package com.analytics.brain.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Роль базы в рабочем пространстве и её ключи.
 * <ul>
 *     <li>{@code entity} — одна строка на объект (пользователи, клиенты): ключ уникален;</li>
 *     <li>{@code events} — много строк на объект во времени, есть колонка шага (страница, экран, событие);</li>
 *     <li>{@code facts} — много строк на объект (платежи, обращения), у каждой строки свой ID;</li>
 *     <li>{@code table} — всё остальное.</li>
 * </ul>
 */
public final class FinalDatasetRoles {

    private static final Pattern STEP_NAME = Pattern.compile(
            "(?iu)страниц|page|экран|screen|событи|event|шаг|step|url|path|action|действи|раздел");
    private static final Pattern TIME_NAME = Pattern.compile("(?iu)время|time|дата|date|когда|ts$|timestamp");

    private FinalDatasetRoles() {
    }

    public static String roleRu(String role) {
        switch (role == null ? "" : role) {
            case "entity":
                return "сущности";
            case "events":
                return "события";
            case "facts":
                return "факты";
            default:
                return "таблица";
        }
    }

    /** Колонки, похожие на ключ: идентификаторы и высококардинальный текст. */
    public static List<String> keyColumns(FinalDataset ds) {
        List<String> keys = new ArrayList<>();
        for (FinalColumn c : ds.columns) {
            if (c.type == EnumColumnType.ID) keys.add(c.name);
        }
        return keys;
    }

    /** Сырые значения колонки (обрезанные, пустые → null). */
    public static String[] values(FinalDataset ds, String column) {
        int j = ds.header.indexOf(column);
        String[] out = new String[ds.rows];
        if (j < 0) return out;
        for (int i = 0; i < ds.rows; i++) {
            String[] record = ds.records.get(i);
            String v = j < record.length && record[j] != null ? record[j].trim() : "";
            out[i] = v.isEmpty() ? null : v;
        }
        return out;
    }

    public static boolean unique(String[] values) {
        Set<String> seen = new HashSet<>();
        int filled = 0;
        for (String v : values) {
            if (v == null) continue;
            filled++;
            if (!seen.add(v)) return false;
        }
        return filled >= 0.98 * values.length;
    }

    public static void detect(FinalDataset ds) {
        ds.role = "table";
        ds.keyColumn = null;
        ds.stepColumn = null;
        ds.timeColumn = null;

        List<String> keys = keyColumns(ds);
        String uniqueKey = null;
        String repeatedKey = null;
        for (String k : keys) {
            String[] v = values(ds, k);
            if (unique(v)) {
                if (uniqueKey == null) uniqueKey = k;
            } else if (repeatedKey == null) {
                repeatedKey = k;
            }
        }

        for (FinalColumn c : ds.columns) {
            if (ds.timeColumn == null && c.type == EnumColumnType.DATE) ds.timeColumn = c.name;
        }
        if (ds.timeColumn == null) {
            for (String h : ds.header) {
                if (TIME_NAME.matcher(h).find()) {
                    ds.timeColumn = h;
                    break;
                }
            }
        }
        for (FinalColumn c : ds.columns) {
            if (c.type == EnumColumnType.CATEGORICAL && STEP_NAME.matcher(c.name.toLowerCase(Locale.ROOT)).find()) {
                ds.stepColumn = c.name;
                break;
            }
        }

        if (repeatedKey != null && ds.stepColumn != null && ds.timeColumn != null) {
            ds.role = "events";
            ds.keyColumn = repeatedKey;
        } else if (repeatedKey != null) {
            ds.role = "facts";
            ds.keyColumn = repeatedKey;
        } else if (uniqueKey != null) {
            ds.role = "entity";
            ds.keyColumn = uniqueKey;
        }
    }
}
