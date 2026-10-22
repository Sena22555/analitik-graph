package com.analytics.brain.data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class FinalDemoData {

    private static long seed;

    private FinalDemoData() {
    }

    private static double rnd() {
        seed = (seed * 1664525L + 1013904223L) & 0xFFFFFFFFL;
        return seed / 4294967296.0;
    }

    private static <T> T pick(T[] options) {
        return options[(int) Math.floor(rnd() * options.length)];
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    public static synchronized List<Map<String, Object>> users(int count, boolean live) {
        seed = 20260921L + (live ? System.currentTimeMillis() / 20000 : 0);

        String[] genders = {"Женщина", "Мужчина"};
        String[] cities = {"Москва", "Санкт-Петербург", "Казань", "Екатеринбург", "Новосибирск", "Краснодар"};
        String[] sources = {"Органический поиск", "Реклама", "Социальные сети", "Рекомендация", "Email"};
        String[] campaigns = {"Весна", "Ретаргетинг", "Промокод", "Контент"};
        String[] dayParts = {"Утро", "День", "Вечер", "Ночь"};
        Map<String, Integer> sourceBoost = Map.of(
                "Рекомендация", 13, "Органический поиск", 8, "Email", 6, "Реклама", 2, "Социальные сети", -1);
        LocalDate start = LocalDate.of(2026, 7, 1);

        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            int dayIdx = (int) Math.floor(rnd() * 60);
            String gender = pick(genders);
            String age = pick(new String[]{"18-24", "25-34", "35-44", "45+"});
            String device = rnd() < 0.66 ? "Mobile" : "Desktop";
            String os = device.equals("Mobile")
                    ? pick(new String[]{"Android", "iOS"})
                    : pick(new String[]{"Windows", "macOS"});
            String source = pick(sources);
            boolean noCampaign = source.equals("Органический поиск") || source.equals("Рекомендация");
            String campaign = noCampaign ? "Без кампании" : pick(campaigns);
            String userType = rnd() < 0.6 ? "Новый" : "Вернувшийся";
            String subscription = rnd() < 0.6 ? "Нет" : rnd() < 0.72 ? "Базовая" : "Премиум";
            boolean parentAge = age.equals("25-34") || age.equals("35-44");
            String kids = parentAge && rnd() < 0.47 ? "Да" : "Нет";
            String dayPart = pick(dayParts);

            int sourceBonus = sourceBoost.get(source);
            int subscriptionBonus = subscription.equals("Нет") ? 0 : subscription.equals("Базовая") ? 9 : 18;
            double deviceBonus = device.equals("Desktop") ? 5 : 0;
            double kidsPenalty = kids.equals("Да") ? 4 : 0;
            double trend = dayIdx * 0.08;
            double genderBonus = gender.equals("Женщина") ? 2.5 : 0;
            double session = round2(clamp(8 + sourceBonus + subscriptionBonus + deviceBonus - kidsPenalty + trend + genderBonus
                    + (rnd() - 0.5) * 18, 2, 75));
            double pages = round2(clamp(session * 0.31 + (rnd() - 0.5) * 3, 1, 28));
            double events = round2(clamp(pages * 1.8 + (rnd() - 0.5) * 5, 1, 55));
            int cart = (int) clamp(Math.round((events - 5) / 12 + (rnd() < 0.3 ? 1 : 0)), 0, 5);

            double buyChance = clamp(0.04 + cart * 0.15 + subscriptionBonus / 150.0
                    + (source.equals("Рекомендация") ? 0.1 : 0)
                    + (gender.equals("Женщина") ? 0.04 : 0), 0.02, 0.9);
            if (kids.equals("Да")) buyChance *= 0.8;
            int purchases = rnd() < buyChance ? (rnd() < 0.15 ? 2 : 1) : 0;
            double check = purchases > 0 ? round2(750 + subscriptionBonus * 45 + rnd() * 900) : 0;
            double revenue = round2(purchases * check);

            String stage;
            if (purchases >= 2) {
                stage = "Повторная покупка";
            } else if (purchases == 1) {
                stage = "Оплата";
            } else if (cart > 0) {
                stage = "Корзина";
            } else if (events > 9) {
                stage = "Просмотр товара";
            } else {
                stage = "Регистрация";
            }

            int nps = (int) Math.round(clamp(4 + session / 11 + purchases * 1.7 + (rnd() - 0.5) * 4, 0, 10));
            double rating = round2(clamp(2.8 + nps * 0.2 + (rnd() - 0.5) * 0.6, 1, 5));
            int support = (int) clamp(Math.round((5 - rating) + (rnd() < 0.12 ? 1 : 0)), 0, 5);
            int loyalty = (int) Math.round(clamp(purchases * 120 + session * 4
                    + (userType.equals("Вернувшийся") ? 80 : 0) + (rnd() - 0.5) * 40, 0, 800));
            String city = pick(cities);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("Дата", start.plusDays(dayIdx).toString());
            m.put("ID пользователя", String.format("u%05d", i));
            m.put("Пол", gender);
            m.put("Возраст", age);
            m.put("Город", city);
            m.put("Устройство", device);
            m.put("ОС", os);
            m.put("Источник трафика", source);
            m.put("Кампания", campaign);
            m.put("Тип пользователя", userType);
            m.put("Подписка", subscription);
            m.put("Этап воронки", stage);
            m.put("Дети", kids);
            m.put("Время суток", dayPart);
            m.put("Время на сайте, мин", session);
            m.put("Просмотрено страниц", pages);
            m.put("Добавлено в корзину", cart);
            m.put("Покупки", purchases);
            m.put("Выручка", revenue);
            m.put("NPS", nps);
            m.put("Оценка сервиса", rating);
            m.put("Обращения в поддержку", support);
            m.put("Баллы лояльности", loyalty);
            out.add(m);
        }
        return out;
    }

    public static String toCsv(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder();
        List<String> header = new ArrayList<>(rows.get(0).keySet());
        sb.append(String.join(",", header)).append('\n');
        for (Map<String, Object> row : rows) {
            StringJoiner line = new StringJoiner(",");
            for (String key : header) {
                String v = String.valueOf(row.get(key));
                boolean needsQuotes = v.contains(",") || v.contains("\"");
                line.add(needsQuotes ? "\"" + v.replace("\"", "\"\"") + "\"" : v);
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
