package com.analytics.brain.data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Демо-продукт: четыре связанные базы по общему «ID пользователя» — пользователи, события на сайте, платежи, поддержка.
 * <p>
 * В данные заложены эффекты, которые движок должен найти сам (это и есть проверка, что граф показывает правду):
 * <ul>
 *     <li>визит на «Тарифы» повышает вероятность дойти до оплаты и выбрать тариф «Про»;</li>
 *     <li>на iOS-смартфонах «сломана» корзина: переход Корзина → Оформление заметно ниже, чаще уходят в «Помощь»;</li>
 *     <li>раздражение (iOS + проблемы) → больше обращений в поддержку → меньше платежей и выше отток;</li>
 *     <li>пришедшие по рекомендации и из email покупают чаще, из соцсетей — реже;</li>
 *     <li>долгий ответ поддержки → низкая оценка обращения.</li>
 * </ul>
 */
public final class FinalDemoProduct {

    public static final String USERS = "Пользователи";
    public static final String EVENTS = "События на сайте";
    public static final String PAYMENTS = "Платежи";
    public static final String SUPPORT = "Поддержка";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Random rnd;

    private FinalDemoProduct(long seed) {
        this.rnd = new Random(seed);
    }

    /** Базы в порядке: пользователи, события, платежи, поддержка. */
    public static Map<String, List<Map<String, Object>>> generate(int users) {
        return new FinalDemoProduct(20261015L).build(Math.max(100, Math.min(users, 20000)));
    }

    private double r() {
        return rnd.nextDouble();
    }

    private String pick(String[] options, double[] weights) {
        double total = 0;
        for (double w : weights) total += w;
        double x = r() * total;
        for (int i = 0; i < options.length; i++) {
            x -= weights[i];
            if (x <= 0) return options[i];
        }
        return options[options.length - 1];
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private Map<String, List<Map<String, Object>>> build(int count) {
        List<Map<String, Object>> users = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> payments = new ArrayList<>();
        List<Map<String, Object>> support = new ArrayList<>();

        LocalDate start = LocalDate.of(2026, 7, 1);
        int sessionSeq = 0;
        int paymentSeq = 0;
        int ticketSeq = 0;

        for (int u = 1; u <= count; u++) {
            String id = String.format("u-%05d", u);
            LocalDate registered = start.plusDays(rnd.nextInt(75));
            String gender = r() < 0.52 ? "Женщина" : "Мужчина";
            String age = pick(new String[]{"18-24", "25-34", "35-44", "45+"}, new double[]{.24, .34, .24, .18});
            String city = pick(new String[]{"Москва", "Санкт-Петербург", "Казань", "Екатеринбург", "Новосибирск", "Краснодар"},
                    new double[]{.32, .2, .12, .12, .12, .12});
            String device = r() < 0.62 ? "Mobile" : "Desktop";
            String os = device.equals("Mobile")
                    ? (r() < 0.47 ? "iOS" : "Android")
                    : (r() < 0.7 ? "Windows" : "macOS");
            String source = pick(new String[]{"Органический поиск", "Реклама", "Социальные сети", "Рекомендация", "Email"},
                    new double[]{.3, .28, .17, .12, .13});
            boolean brokenCart = device.equals("Mobile") && os.equals("iOS");

            double intent = 0.5 + (source.equals("Рекомендация") ? 0.25 : source.equals("Email") ? 0.12
                    : source.equals("Органический поиск") ? 0.05 : source.equals("Реклама") ? -0.05 : -0.12)
                    + (age.equals("25-34") || age.equals("35-44") ? 0.05 : 0) + (r() - 0.5) * 0.4;
            intent = clamp(intent, 0.05, 0.95);
            double frustration = clamp(0.12 + (brokenCart ? 0.38 : 0) + r() * 0.25, 0, 1);

            int sessions = 1 + (r() < intent * 0.9 ? 1 : 0) + (r() < intent * 0.6 ? 1 : 0) + (r() < 0.2 ? 1 : 0);
            int paid = 0;
            int tickets = 0;
            LocalDateTime clock = registered.atTime(8 + rnd.nextInt(15), rnd.nextInt(60), rnd.nextInt(60));

            for (int s = 0; s < sessions; s++) {
                sessionSeq++;
                String sid = String.format("s%06d", sessionSeq);
                if (s > 0) clock = clock.plusDays(1 + rnd.nextInt(9)).withHour(8 + rnd.nextInt(15));

                List<String> path = walk(source, intent, frustration, brokenCart, paid);
                boolean sawPricing = path.contains("Тарифы");
                for (String page : path) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("ID пользователя", id);
                    e.put("ID сессии", sid);
                    e.put("Время", clock.format(TS));
                    e.put("Страница", page);
                    int seconds = (int) clamp(20 + r() * 120 + (page.equals("Корзина") && brokenCart ? 70 : 0)
                            + (page.equals("Тарифы") ? 40 : 0), 5, 600);
                    e.put("Секунд на странице", seconds);
                    events.add(e);
                    clock = clock.plusSeconds(seconds);
                }

                if (path.contains("Оплата")) {
                    paid++;
                    paymentSeq++;
                    boolean pro = r() < (sawPricing ? 0.62 : 0.22) + (intent - 0.5) * 0.2;
                    double base = pro ? 2490 : 990;
                    double amount = Math.round(base * (0.92 + r() * 0.3) * (source.equals("Рекомендация") ? 1.12 : 1));
                    String method = pick(new String[]{"Карта", "СБП", os.equals("iOS") ? "Apple Pay" : "Google Pay"},
                            new double[]{.55, .28, device.equals("Mobile") ? .3 : .05});
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("ID платежа", String.format("p%06d", paymentSeq));
                    p.put("ID пользователя", id);
                    p.put("Дата", clock.toLocalDate().toString());
                    p.put("Тариф", pro ? "Про" : "Базовый");
                    p.put("Способ оплаты", method);
                    p.put("Сумма", amount);
                    payments.add(p);
                }

                // жалуются те, у кого не получилось купить: застряли на корзине / оформлении или искали помощь
                boolean failed = !path.contains("Оплата")
                        && (path.contains("Корзина") || path.contains("Оформление") || path.contains("Помощь"));
                if ((failed && r() < 0.35 + frustration * 0.5) || r() < frustration * 0.06) {
                    tickets++;
                    ticketSeq++;
                    String topic = brokenCart && r() < 0.55 ? "Ошибка в приложении"
                            : pick(new String[]{"Оплата не проходит", "Доставка", "Возврат", "Вопрос по тарифу"},
                            new double[]{.3, .3, .2, .2});
                    double hours = Math.round(clamp(Math.exp(1 + r() * 2.6), 0.5, 72) * 10) / 10.0;
                    int score = (int) Math.round(clamp(5.4 - hours / 14 - (topic.equals("Ошибка в приложении") ? 1.1 : 0)
                            + (r() - 0.5) * 1.6, 1, 5));
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("ID обращения", String.format("t%05d", ticketSeq));
                    t.put("ID пользователя", id);
                    t.put("Дата", clock.toLocalDate().toString());
                    t.put("Тема", topic);
                    t.put("Время ответа, ч", hours);
                    t.put("Оценка", score);
                    support.add(t);
                    frustration = clamp(frustration + 0.2, 0, 1);
                }
            }

            double churn = clamp(0.08 + 0.2 * Math.min(tickets, 3) + (paid == 0 ? 0.18 : 0) - 0.05 * Math.min(paid, 3)
                    + frustration * 0.15, 0.02, 0.95);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ID пользователя", id);
            m.put("Дата регистрации", registered.toString());
            m.put("Пол", gender);
            m.put("Возраст", age);
            m.put("Город", city);
            m.put("Устройство", device);
            m.put("ОС", os);
            m.put("Источник трафика", source);
            m.put("Статус", r() < churn ? "Ушёл" : "Активен");
            users.add(m);
        }

        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put(USERS, users);
        out.put(EVENTS, events);
        out.put(PAYMENTS, payments);
        out.put(SUPPORT, support);
        return out;
    }

    /** Один визит: марковская цепь по страницам с заложенными эффектами. */
    private List<String> walk(String source, double intent, double frustration, boolean brokenCart, int paidBefore) {
        List<String> path = new ArrayList<>();
        String page;
        switch (source) {
            case "Реклама":
                page = pick(new String[]{"Каталог", "Карточка товара", "Главная"}, new double[]{.55, .3, .15});
                break;
            case "Email":
                page = pick(new String[]{"Тарифы", "Главная", "Каталог"}, new double[]{.4, .4, .2});
                break;
            default:
                page = pick(new String[]{"Главная", "Каталог", "Поиск"}, new double[]{.68, .2, .12});
        }

        double exitBoost = 1.45 - intent * 0.9 + paidBefore * 0.25;
        boolean sawPricing = false;
        for (int step = 0; step < 16 && page != null; step++) {
            path.add(page);
            if (page.equals("Тарифы")) sawPricing = true;
            if (page.equals("Оплата")) break;

            String[] next;
            double[] w;
            switch (page) {
                case "Главная":
                    next = new String[]{"Каталог", "Поиск", "Тарифы", "Отзывы", "Помощь", null};
                    w = new double[]{.45, .15, .13, .05, .03, .19 * exitBoost};
                    break;
                case "Каталог":
                    next = new String[]{"Карточка товара", "Поиск", "Тарифы", null};
                    w = new double[]{.56, .1, .08, .26 * exitBoost};
                    break;
                case "Поиск":
                    next = new String[]{"Карточка товара", "Каталог", null};
                    w = new double[]{.6, .1, .3 * exitBoost};
                    break;
                case "Карточка товара":
                    next = new String[]{"Корзина", "Отзывы", "Каталог", "Тарифы", null};
                    w = new double[]{.26 + intent * 0.12, .12, .2, .06, .32 * exitBoost};
                    break;
                case "Отзывы":
                    next = new String[]{"Карточка товара", "Корзина", null};
                    w = new double[]{.42, .16, .42 * exitBoost};
                    break;
                case "Тарифы":
                    next = new String[]{"Корзина", "Каталог", "Помощь", null};
                    w = new double[]{.5, .15, .05, .3 * exitBoost};
                    break;
                case "Корзина": {
                    double checkout = clamp(0.34 + intent * 0.42, 0.1, 0.9) * (brokenCart ? 0.42 : 1);
                    next = new String[]{"Оформление", "Каталог", "Помощь", null};
                    w = new double[]{checkout, .1, .04 + frustration * 0.25, (1 - checkout) * 0.6 * exitBoost};
                    break;
                }
                case "Оформление": {
                    double pay = clamp((sawPricing ? 0.86 : 0.6) * (1 - 0.45 * frustration) + (intent - 0.5) * 0.2, 0.1, 0.97);
                    next = new String[]{"Оплата", "Помощь", null};
                    w = new double[]{pay, .06 + frustration * 0.12, (1 - pay) * exitBoost};
                    break;
                }
                case "Помощь":
                    next = new String[]{"Каталог", "Корзина", null};
                    w = new double[]{.25, .1, .65};
                    break;
                default:
                    next = new String[]{null};
                    w = new double[]{1};
            }
            String chosen = pick(replaceNulls(next), w);
            page = chosen.equals("∅") ? null : chosen;
        }
        return path;
    }

    private static String[] replaceNulls(String[] options) {
        String[] out = new String[options.length];
        for (int i = 0; i < options.length; i++) out[i] = options[i] == null ? "∅" : options[i];
        return out;
    }
}
