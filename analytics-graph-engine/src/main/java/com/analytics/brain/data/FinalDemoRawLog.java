package com.analytics.brain.data;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Демо «несортированных» данных: один плоский лог сайта, как его часто выгружают из трекера или BI-экспорта.
 * В каждой строке событие, а рядом — повторяющиеся свойства пользователя, сессии и платежа. Плюс типичный мусор:
 * варианты написания городов, деньги в разных форматах, даты в двух форматах, токены пропусков, дубликаты строк.
 * Сортировка должна сама разложить это на Пользователей, Сессии и События.
 */
public final class FinalDemoRawLog {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter RU = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private FinalDemoRawLog() {
    }

    public static List<Map<String, Object>> generate(int users) {
        Map<String, List<Map<String, Object>>> product = FinalDemoProduct.generate(users);
        Map<String, Map<String, Object>> byUser = new HashMap<>();
        for (Map<String, Object> u : product.get(FinalDemoProduct.USERS)) byUser.put((String) u.get("ID пользователя"), u);
        Map<String, List<Map<String, Object>>> payments = new HashMap<>();
        for (Map<String, Object> p : product.get(FinalDemoProduct.PAYMENTS)) {
            payments.computeIfAbsent((String) p.get("ID пользователя"), k -> new ArrayList<>()).add(p);
        }

        Random rnd = new Random(42);
        Map<String, String> partOfDay = new HashMap<>();
        Map<String, Integer> paidIndex = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        int seq = 0;
        for (Map<String, Object> e : product.get(FinalDemoProduct.EVENTS)) {
            String uid = (String) e.get("ID пользователя");
            String sid = (String) e.get("ID сессии");
            Map<String, Object> u = byUser.get(uid);
            LocalDateTime t = LocalDateTime.parse((String) e.get("Время"), ISO);
            partOfDay.computeIfAbsent(sid, k -> t.getHour() < 12 ? "Утро" : t.getHour() < 17 ? "День" : t.getHour() < 22 ? "Вечер" : "Ночь");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ID события", String.format("e%06d", ++seq));
            row.put("Время", rnd.nextDouble() < 0.3 ? t.format(RU) : t.format(ISO));
            row.put("ID пользователя", uid);
            row.put("ID сессии", sid);
            row.put("Страница", e.get("Страница"));
            row.put("Секунд на странице", e.get("Секунд на странице"));
            row.put("Город", messyCity((String) u.get("Город"), rnd));
            row.put("Устройство", u.get("Устройство"));
            row.put("ОС", u.get("ОС"));
            row.put("Источник трафика", u.get("Источник трафика"));
            row.put("Возраст", rnd.nextDouble() < 0.03 ? (rnd.nextBoolean() ? "—" : "n/a") : u.get("Возраст"));
            row.put("Время суток", partOfDay.get(sid));

            String amount = "";
            String plan = "";
            if ("Оплата".equals(e.get("Страница"))) {
                List<Map<String, Object>> list = payments.getOrDefault(uid, List.of());
                int k = paidIndex.merge(uid, 1, Integer::sum) - 1;
                if (k < list.size()) {
                    double sum = ((Number) list.get(k).get("Сумма")).doubleValue();
                    amount = rnd.nextDouble() < 0.5 ? String.format(java.util.Locale.ROOT, "%,.2f ₽", sum).replace(',', ' ').replace('.', ',') : String.valueOf((long) sum);
                    plan = (String) list.get(k).get("Тариф");
                }
            }
            row.put("Сумма платежа", amount);
            row.put("Тариф", plan);
            out.add(row);
            if (rnd.nextDouble() < 0.015) out.add(new LinkedHashMap<>(row));
        }
        return out;
    }

    private static String messyCity(String city, Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.06) return city.toLowerCase();
        if (r < 0.10) return city + " ";
        if (r < 0.13) return city.toUpperCase();
        if (r < 0.16 && city.contains("-")) return city.replace('-', ' ');
        return city;
    }
}
