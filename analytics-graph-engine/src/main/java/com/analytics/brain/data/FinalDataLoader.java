package com.analytics.brain.data;

import com.analytics.brain.exception.ExceptionAnalitikData;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FinalDataLoader {

    private FinalDataLoader() {
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final int MAX_LEVELS = 14;

    private static final Set<String> MISSING = new HashSet<>(Arrays.asList(
            "", "na", "n/a", "nan", "null", "none", "-", "—", "?", "undefined"));

    private static final Pattern ID_NAME = Pattern.compile(
            "(?iu)^(id|uuid|guid|index|#)$|(^|[\\s_.-])(id|uuid|guid)$|^id[\\s_.-]"
                    + "|e-?mail|телефон|phone|url|website|идентификатор|токен|token|паспорт");

    private static final Pattern SOFT_ID_NAME = Pattern.compile(
            "(?iu)имя|name$|фио|address|адрес|комментар|comment|описани|description");

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d.M.yyyy")
    };

    private static final String[] KPI_WORDS = {
            "выручк", "revenue", "доход", "продаж", "sales", "gmv", "покупк", "purchase", "заказ", "order",
            "конверс", "conversion", "ltv", "чек", "retention", "удержан", "nps", "churn", "отток"
    };

    public static final String[] STAGE_WORDS = {
            "визит", "visit", "лендинг", "landing", "регистр", "signup", "sign up", "sign-up", "lead", "лид",
            "активац", "activat", "просмотр", "view", "карточ", "product", "корзин", "cart", "оформ", "checkout",
            "оплат", "payment", "pay", "покуп", "purchase", "заказ", "order", "повтор", "repeat", "retention",
            "лояль", "loyal"
    };

    public static String[] fetch(String url, Map<String, String> headers) throws Exception {
        String target = googleSheetsToCsv(url.trim());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(target))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .header("Accept", "application/json, text/csv, */*");
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (name != null && !name.isBlank() && value != null) request.header(name, value);
            });
        }

        HttpResponse<String> response;
        try {
            response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException e) {
            throw new ExceptionAnalitikData("Не удалось подключиться к " + target
                    + " — проверьте адрес и что сервис запущен");
        } catch (HttpTimeoutException e) {
            throw new ExceptionAnalitikData("Источник не ответил вовремя: " + target);
        }
        if (response.statusCode() >= 400) {
            throw new ExceptionAnalitikData("Источник ответил HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("content-type").orElse("");
        return new String[]{response.body(), contentType};
    }

    public static String googleSheetsToCsv(String url) {
        Matcher sheet = Pattern.compile("docs\\.google\\.com/spreadsheets/d/([\\w-]+)").matcher(url);
        boolean alreadyCsv = url.contains("export?format=csv") || url.contains("output=csv");
        if (!sheet.find() || alreadyCsv) return url;

        Matcher gid = Pattern.compile("[#&?]gid=(\\d+)").matcher(url);
        String result = "https://docs.google.com/spreadsheets/d/" + sheet.group(1) + "/export?format=csv";
        if (gid.find()) result += "&gid=" + gid.group(1);
        return result;
    }

    /** Токен пропуска: пусто, «—», «n/a», «null» и т.п. */
    public static boolean isMissing(String v) {
        return v == null || MISSING.contains(v.trim().toLowerCase(Locale.ROOT));
    }

    public static void parseAuto(FinalDataset ds, String body, String contentType) throws Exception {
        String text = body == null ? "" : body.replace("﻿", "").trim();
        if (text.isEmpty()) throw new ExceptionAnalitikData("Источник вернул пустые данные");

        boolean json = (contentType != null && contentType.contains("json"))
                || text.startsWith("[") || text.startsWith("{");
        InterfaceDataParser parser = json ? new FinalJsonParser() : new FinalCsvParser();
        parser.parse(ds, text);
    }

    public static void setRows(FinalDataset ds, List<Map<String, String>> rows) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Map<String, String> row : rows) keys.addAll(row.keySet());
        ds.header = new ArrayList<>(keys);
        ds.records = new ArrayList<>();
        for (Map<String, String> row : rows) ds.records.add(toRecord(ds.header, row));
    }

    public static String[] toRecord(List<String> header, Map<String, String> row) {
        String[] record = new String[header.size()];
        for (int i = 0; i < record.length; i++) {
            String value = row.get(header.get(i));
            record[i] = value == null ? "" : value;
        }
        return record;
    }

    public static void build(FinalDataset ds) {
        ds.rows = ds.records.size();
        if (ds.rows < 5) throw new ExceptionAnalitikData("Слишком мало строк для анализа (нужно ≥ 5)");

        ds.columns = new ArrayList<>();
        ds.byName = new LinkedHashMap<>();
        for (int j = 0; j < ds.header.size(); j++) {
            String[] raw = new String[ds.rows];
            for (int i = 0; i < ds.rows; i++) {
                String value = ds.records.get(i)[j];
                value = value == null ? "" : value.trim();
                raw[i] = MISSING.contains(value.toLowerCase(Locale.ROOT)) ? null : value;
            }
            FinalColumn column = infer(ds.header.get(j), raw, ds.rows);
            ds.columns.add(column);
            ds.byName.put(column.name, column);
        }

        detectKpis(ds);
        detectFunnel(ds);
        detectEntity(ds);
        FinalDatasetRoles.detect(ds);
    }

    public static FinalColumn infer(String name, String[] raw, int n) {
        FinalColumn column = new FinalColumn(name);
        int filled = 0;
        int numbers = 0;
        int dates = 0;
        Set<String> distinct = new HashSet<>();
        double[] num = new double[n];
        long[] days = new long[n];

        for (int i = 0; i < n; i++) {
            num[i] = Double.NaN;
            days[i] = Long.MIN_VALUE;
            if (raw[i] == null) continue;
            filled++;
            distinct.add(raw[i]);

            double d = parseNum(raw[i]);
            if (!Double.isNaN(d)) {
                num[i] = d;
                numbers++;
            }
            LocalDate date = parseDate(raw[i]);
            if (date != null) {
                days[i] = date.toEpochDay();
                dates++;
            }
        }
        column.missing = n - filled;
        column.unique = distinct.size();

        String trimmed = name.trim();
        boolean looksLikeId = ID_NAME.matcher(trimmed).find()
                || (SOFT_ID_NAME.matcher(trimmed).find() && distinct.size() > 30);

        if (filled == 0) {
            column.type = EnumColumnType.TEXT;
            return column;
        }
        if (dates >= 0.95 * filled && !looksLikeId) {
            makeDate(column, days, n);
            return column;
        }
        if (numbers >= 0.95 * filled) {
            makeNumeric(column, name, raw, num, filled, looksLikeId);
            return column;
        }
        if (looksLikeId || (column.unique > 60 && column.unique > filled * 0.5)) {
            column.type = looksLikeId ? EnumColumnType.ID : EnumColumnType.TEXT;
            return column;
        }
        makeCategorical(column, raw, n);
        return column;
    }

    private static void makeNumeric(FinalColumn column, String name, String[] raw, double[] num,
                                    int filled, boolean looksLikeId) {
        boolean allUniqueInts = column.unique == filled && column.unique > 20
                && Arrays.stream(num).filter(v -> !Double.isNaN(v)).allMatch(v -> v == Math.rint(v));
        boolean numberName = name.toLowerCase(Locale.ROOT).matches(".*(id|номер|код|no|№).*");
        if (looksLikeId || (allUniqueInts && numberName)) {
            column.type = EnumColumnType.ID;
            return;
        }

        column.type = EnumColumnType.NUMERIC;
        column.num = num;

        for (int i = 0; i < num.length; i++) {
            if (raw[i] != null && Double.isNaN(num[i])) column.missing++;
        }

        double[] values = FinalAnalitikMathematics.clean(num);
        int zeros = 0;
        boolean wholeNonNegative = true;
        for (double v : values) {
            if (v == 0) zeros++;
            if (v < 0 || v != Math.rint(v)) wholeNonNegative = false;
        }
        double zeroShare = values.length == 0 ? 0 : (double) zeros / values.length;
        column.countLike = wholeNonNegative && zeroShare >= 0.08 && zeroShare <= 0.92;
    }

    private static void makeCategorical(FinalColumn column, String[] raw, int n) {
        Map<String, Integer> freq = new HashMap<>();
        for (String v : raw) {
            if (v != null) freq.merge(v, 1, Integer::sum);
        }
        List<String> byFrequency = new ArrayList<>(freq.keySet());
        byFrequency.sort((a, b) -> {
            int diff = freq.get(b) - freq.get(a);
            return diff != 0 ? diff : a.compareTo(b);
        });
        if (byFrequency.size() < 2) {
            column.type = EnumColumnType.TEXT;
            return;
        }

        Set<String> keep = new HashSet<>(byFrequency.subList(0, Math.min(MAX_LEVELS, byFrequency.size())));
        column.cat = new String[n];
        for (int i = 0; i < n; i++) {
            if (raw[i] == null) {
                column.cat[i] = null;
            } else {
                column.cat[i] = keep.contains(raw[i]) ? raw[i] : "Другое";
            }
        }

        List<String> levels = new ArrayList<>();
        for (String level : byFrequency) {
            if (keep.contains(level)) levels.add(level);
        }
        if (byFrequency.size() > MAX_LEVELS) levels.add("Другое");
        column.levels = levels;
        column.type = EnumColumnType.CATEGORICAL;
    }

    private static void makeDate(FinalColumn column, long[] days, int n) {
        column.type = EnumColumnType.DATE;
        column.num = new double[n];
        column.cat = new String[n];

        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        Set<Long> distinctDays = new HashSet<>();
        for (long d : days) {
            if (d == Long.MIN_VALUE) continue;
            min = Math.min(min, d);
            max = Math.max(max, d);
            distinctDays.add(d);
        }
        long span = max - min;
        if (distinctDays.size() <= 12) {
            column.granularity = "day";
        } else if (span <= 120) {
            column.granularity = "week";
        } else if (span <= 1100) {
            column.granularity = "month";
        } else {
            column.granularity = "year";
        }

        TreeMap<Long, String> periodsInOrder = new TreeMap<>();
        DateTimeFormatter dayMonth = DateTimeFormatter.ofPattern("dd.MM");
        for (int i = 0; i < n; i++) {
            if (days[i] == Long.MIN_VALUE) {
                column.num[i] = Double.NaN;
                continue;
            }
            column.num[i] = days[i];
            LocalDate date = LocalDate.ofEpochDay(days[i]);
            long sortKey;
            String label;
            switch (column.granularity) {
                case "day":
                    sortKey = days[i];
                    label = date.toString();
                    break;
                case "week":
                    LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    sortKey = monday.toEpochDay();
                    label = "нед. " + monday.format(dayMonth) + "–" + monday.plusDays(6).format(dayMonth);
                    break;
                case "month":
                    sortKey = date.getYear() * 12L + date.getMonthValue();
                    label = String.format("%d-%02d", date.getYear(), date.getMonthValue());
                    break;
                default:
                    sortKey = date.getYear();
                    label = String.valueOf(date.getYear());
            }
            column.cat[i] = label;
            periodsInOrder.put(sortKey, label);
        }
        column.levels = new ArrayList<>(new LinkedHashSet<>(periodsInOrder.values()));
    }

    public static double parseNum(String s) {
        String t = s.replace(" ", "").replace(" ", "").replace(" ", "");
        if (t.endsWith("%")) t = t.substring(0, t.length() - 1);
        if (t.startsWith("$") || t.startsWith("€") || t.startsWith("₽")) t = t.substring(1);
        if (t.endsWith("₽") || t.endsWith("$") || t.endsWith("€")) t = t.substring(0, t.length() - 1);

        boolean decimalComma = t.indexOf(',') >= 0 && t.indexOf('.') < 0 && t.indexOf(',') == t.lastIndexOf(',');
        if (decimalComma) t = t.replace(',', '.');

        if (t.isEmpty() || !t.matches("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?")) return Double.NaN;
        try {
            return Double.parseDouble(t);
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    public static LocalDate parseDate(String s) {
        if (s.length() < 8 || s.length() > 30) return null;
        boolean isoLike = s.length() >= 10 && (s.charAt(4) == '-' || s.charAt(4) == '/');
        String datePart = isoLike ? s.substring(0, 10) : s.split("[ T]")[0];
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(datePart, format);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public static void detectKpis(FinalDataset ds) {
        List<String> kpis = new ArrayList<>();
        for (String word : KPI_WORDS) {
            for (FinalColumn c : ds.columns) {
                boolean match = c.type == EnumColumnType.NUMERIC && c.name.toLowerCase(Locale.ROOT).contains(word);
                if (match && !kpis.contains(c.name)) kpis.add(c.name);
            }
        }

        if (kpis.isEmpty()) {
            for (FinalColumn c : ds.columns) {
                if (c.type != EnumColumnType.NUMERIC) continue;
                kpis.add(c.name);
                if (kpis.size() >= 2) break;
            }
        }
        ds.kpis = kpis.size() > 4 ? new ArrayList<>(kpis.subList(0, 4)) : kpis;
    }

    public static void detectFunnel(FinalDataset ds) {
        for (FinalColumn c : ds.columns) {
            if (c.type != EnumColumnType.CATEGORICAL) continue;
            String name = c.name.toLowerCase(Locale.ROOT);
            boolean funnelName = name.contains("этап") || name.contains("stage") || name.contains("воронк")
                    || name.contains("funnel") || name.contains("step") || name.contains("шаг");
            if (!funnelName) continue;

            List<String> stages = new ArrayList<>(c.levels);
            stages.remove("Другое");
            Map<String, Integer> position = new HashMap<>();
            for (String stage : stages) position.put(stage, stagePosition(stage));
            stages.sort(Comparator.comparingInt(position::get));

            c.levels = stages;
            ds.funnelColumn = c.name;
            return;
        }
    }

    private static int stagePosition(String stage) {
        String lower = stage.toLowerCase(Locale.ROOT);
        for (int i = 0; i < STAGE_WORDS.length; i++) {
            if (lower.contains(STAGE_WORDS[i])) return i;
        }
        return 1000;
    }

    public static void detectEntity(FinalDataset ds) {
        for (String header : ds.header) {
            String h = header.toLowerCase(Locale.ROOT);
            if (h.contains("пользоват") || h.contains("user")) {
                ds.entity = "пользователей";
                return;
            }
            if (h.contains("клиент") || h.contains("customer") || h.contains("client")) {
                ds.entity = "клиентов";
                return;
            }
            if (h.contains("заказ") || h.contains("order")) {
                ds.entity = "заказов";
                return;
            }
            if (h.contains("сесси") || h.contains("session")) {
                ds.entity = "сессий";
                return;
            }
        }
    }
}
