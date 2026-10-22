package com.analytics.brain.ai;

import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.query.FinalChartSpec;
import com.analytics.brain.query.FinalQueryEngine;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.analytics.brain.ai.ServiceAiClient.JSON;
import static com.analytics.brain.text.FinalFormatRu.*;

@Service
public class ServiceAiDocuments {

    private final ServiceAiClient client;

    public ServiceAiDocuments(ServiceAiClient client) {
        this.client = client;
    }

    Map<String, Object> buildDeck(FinalDataset ds, JsonNode spec) {
        Map<String, Object> deck = new LinkedHashMap<>();
        deck.put("type", "deck");
        deck.put("id", UUID.randomUUID().toString().substring(0, 8));
        deck.put("title", spec.path("title").asText("Аналитический отчёт"));
        deck.put("subtitle", spec.path("subtitle").asText(ds.name + " · " + ds.rows + " строк"));
        deck.put("dataset", ds.name);
        deck.put("createdAt", System.currentTimeMillis());

        List<Map<String, Object>> slides = new ArrayList<>();
        for (JsonNode s : spec.path("slides")) slides.add(buildSlide(ds, s));
        deck.put("theme", spec.path("theme").asText("executive"));
        deck.put("slides", slides);

        addImages(slides, String.valueOf(deck.get("theme")));
        return deck;
    }

    private Map<String, Object> buildSlide(FinalDataset ds, JsonNode s) {
        Map<String, Object> slide = new LinkedHashMap<>();
        slide.put("layout", s.path("layout").asText("bullets"));
        slide.put("title", s.path("title").asText(""));
        if (s.has("subtitle")) slide.put("subtitle", s.path("subtitle").asText());
        if (s.has("notes")) slide.put("notes", s.path("notes").asText());
        List<String> bullets = ServiceAiTools.strings(s.path("bullets"));
        if (!bullets.isEmpty()) slide.put("bullets", bullets);

        try {
            if (s.has("chart") && s.get("chart").isObject()) {
                slide.put("chart", FinalQueryEngine.chart(ds, JSON.treeToValue(s.get("chart"), FinalChartSpec.class)));
            }
            if (s.has("table") && s.get("table").isObject()) {
                slide.put("table", ServiceAiTools.table(ds, s.get("table")));
            }
            if (s.has("kpis")) slide.put("kpis", kpis(ds, s.path("kpis")));
            for (String side : new String[]{"left", "right"}) {
                if (s.has(side) && s.get(side).isObject()) slide.put(side, JSON.treeToValue(s.get(side), Map.class));
            }
            if (s.hasNonNull("image_prompt") && !s.get("image_prompt").asText().isBlank()) {
                slide.put("image_prompt", s.get("image_prompt").asText());
            }
        } catch (Exception e) {
            slide.put("error", e.getMessage());
        }
        return slide;
    }

    private void addImages(List<Map<String, Object>> slides, String theme) {
        String imageModel = client.imageModel();
        List<Map<String, Object>> wantImage = slides.stream()
                .filter(s -> s.containsKey("image_prompt"))
                .limit(4)
                .collect(Collectors.toList());
        if (imageModel == null || wantImage.isEmpty()) return;

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(4, wantImage.size()));
        List<Future<?>> jobs = new ArrayList<>();
        for (Map<String, Object> slide : wantImage) {
            jobs.add(pool.submit(() -> {
                boolean wide = "title".equals(slide.get("layout")) || "image".equals(slide.get("layout"));
                try {
                    String id = client.generateImage((String) slide.get("image_prompt"), wide ? "landscape" : "portrait", theme);
                    slide.put("image", "/api/v2/ai/images/" + id);
                } catch (Exception e) {
                    slide.put("imageError", e.getMessage());
                }
            }));
        }
        for (Future<?> job : jobs) {
            try {
                job.get(180, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        pool.shutdownNow();
    }

    List<Map<String, Object>> kpis(FinalDataset ds, JsonNode list) throws Exception {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (JsonNode k : list) {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("label", k.path("label").asText());
            boolean computed = k.hasNonNull("column") || "count".equals(k.path("agg").asText());
            if (computed) {
                String agg = k.path("agg").asText("mean");
                int[] idx = FinalQueryEngine.filter(ds, ServiceAiTools.conds(k));
                List<Integer> rows = Arrays.stream(idx).boxed().collect(Collectors.toList());
                FinalColumn c = k.hasNonNull("column") ? FinalQueryEngine.need(ds, k.get("column").asText()) : null;
                double v = FinalQueryEngine.agg(ds, c, rows, agg, ds.rows);
                String value;
                if (agg.equals("share") || agg.equals("rate")) {
                    value = pct(v);
                } else if (agg.equals("count")) {
                    value = fmtInt((long) v);
                } else {
                    value = fmt(v);
                }
                card.put("value", value);
            } else {
                card.put("value", k.path("value").asText("—"));
            }
            if (k.has("note")) card.put("note", k.path("note").asText());
            cards.add(card);
        }
        return cards;
    }

    Map<String, Object> buildReport(FinalDataset ds, JsonNode spec) throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("type", "report");
        report.put("id", UUID.randomUUID().toString().substring(0, 8));
        report.put("createdAt", System.currentTimeMillis());
        for (String field : new String[]{"title", "report_type", "organization", "author", "period", "abstract", "introduction"}) {
            if (spec.hasNonNull(field)) report.put(field, spec.get(field).asText());
        }
        report.putIfAbsent("report_type", "Аналитический отчёт");
        report.put("dataset", ds.name);
        report.put("rows", ds.rows);
        report.put("keywords", ServiceAiTools.strings(spec.path("keywords")));
        report.put("conclusions", ServiceAiTools.strings(spec.path("conclusions")));
        report.put("limitations", ServiceAiTools.strings(spec.path("limitations")));

        List<Object> recommendations = new ArrayList<>();
        for (JsonNode r : spec.path("recommendations")) recommendations.add(JSON.treeToValue(r, Map.class));
        report.put("recommendations", recommendations);

        List<String> errors = new ArrayList<>();
        List<Map<String, Object>> sections = new ArrayList<>();
        for (JsonNode s : spec.path("sections")) {
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("title", s.path("title").asText());
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (JsonNode b : s.path("blocks")) blocks.add(buildBlock(ds, b, s.path("title").asText(), errors));
            section.put("blocks", blocks);
            sections.add(section);
        }
        report.put("sections", sections);
        report.put("appendix", dataDescription(ds));
        report.put("methods", "Описательная статистика; доверительные интервалы (Стьюдент, Уилсон); ранговая корреляция "
                + "Спирмена; χ²-тест с V Крамера; однофакторный ANOVA и критерий Краскела–Уоллиса; t-тест Уэлча с g Хеджеса; "
                + "z-тест долей; многофакторная МНК-регрессия; ABC-анализ; линейный тренд с интервалом предсказания; "
                + "робастные z-оценки (MAD); контроль доли ложных открытий (Бенджамини–Хохберг). Уровень значимости α = 0,05.");
        report.put("errors", errors);
        return report;
    }

    private Map<String, Object> buildBlock(FinalDataset ds, JsonNode b, String sectionTitle, List<String> errors) {
        Map<String, Object> block = new LinkedHashMap<>();
        String type = b.path("type").asText("text");
        block.put("type", type);
        if (b.has("text")) block.put("text", b.get("text").asText());
        if (b.has("caption")) block.put("caption", b.get("caption").asText());
        if (b.has("tone")) block.put("tone", b.get("tone").asText());
        if (b.has("items")) block.put("items", ServiceAiTools.strings(b.get("items")));
        try {
            if (type.equals("chart") && b.has("chart")) {
                block.put("chart", FinalQueryEngine.chart(ds, JSON.treeToValue(b.get("chart"), FinalChartSpec.class)));
            }
            if (type.equals("table") && b.has("table")) block.put("table", ServiceAiTools.table(ds, b.get("table")));
            if (type.equals("kpis") && b.has("kpis")) block.put("kpis", kpis(ds, b.get("kpis")));
        } catch (Exception e) {
            block.put("error", e.getMessage());
            errors.add(sectionTitle + ": " + e.getMessage());
        }
        return block;
    }

    private static Map<String, Object> dataDescription(FinalDataset ds) {
        List<List<Object>> rows = new ArrayList<>();
        for (FinalColumn c : ds.columns) {
            rows.add(Arrays.asList(c.name, c.typeRu(), c.unique, FinalQueryEngine.num((double) c.missing / ds.rows)));
        }
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("type", "table");
        table.put("title", "Описание исходных данных");
        table.put("head", List.of("Показатель", "Тип", "Уникальных значений", "Доля пропусков"));
        table.put("formats", List.of("text", "text", "int", "pct"));
        table.put("keyCols", 1);
        table.put("rows", rows);
        return table;
    }
}
