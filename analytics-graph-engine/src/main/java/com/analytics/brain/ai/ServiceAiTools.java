package com.analytics.brain.ai;

import com.analytics.brain.analysis.AbstractAnomalyAnalysis;
import com.analytics.brain.analysis.FinalAbcAnalysis;
import com.analytics.brain.analysis.FinalDriversAnalysis;
import com.analytics.brain.analysis.FinalFinanceAnalysis;
import com.analytics.brain.analysis.FinalQualityAnalysis;
import com.analytics.brain.analysis.FinalTrendAnalysis;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.exception.ExceptionAnalitikData;
import com.analytics.brain.graph.FinalGraphLink;
import com.analytics.brain.query.FinalChartSpec;
import com.analytics.brain.query.FinalCondition;
import com.analytics.brain.query.FinalPivotSpec;
import com.analytics.brain.query.FinalQueryEngine;
import com.analytics.brain.service.ServiceAtlas;
import com.analytics.brain.service.ServiceBrainGraph;
import com.analytics.brain.service.ServiceJourney;
import com.analytics.brain.service.ServiceSql;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static com.analytics.brain.ai.ServiceAiClient.JSON;
import static com.analytics.brain.text.FinalFormatRu.levelRuF;
import static com.analytics.brain.text.FinalFormatRu.pct;

@Service
public class ServiceAiTools {

    private final ServiceBrainGraph brain;
    private final ServiceAiClient client;
    private final ServiceAiDocuments documents;
    private final ServiceAtlas atlas;
    private final ServiceJourney journey;
    private final ServiceSql sql;
    private final Map<String, InterfaceAiTool> tools = new LinkedHashMap<>();

    public ServiceAiTools(ServiceBrainGraph brain, ServiceAiClient client, ServiceAiDocuments documents,
                          ServiceAtlas atlas, ServiceJourney journey, ServiceSql sql) {
        this.brain = brain;
        this.client = client;
        this.documents = documents;
        this.atlas = atlas;
        this.journey = journey;
        this.sql = sql;
        registerTools();
    }

    String run(FinalDataset ds, String name, JsonNode args, BiConsumer<String, Object> emit) throws Exception {
        if (ds == null) throw new IllegalStateException("Нет загруженных данных");
        InterfaceAiTool tool = tools.get(name);
        if (tool == null) throw new ExceptionAnalitikData("Неизвестный инструмент " + name);
        return tool.run(ds, args, emit);
    }

    private void registerTools() {
        tools.put("pivot_table", (ds, args, emit) ->
                show(FinalQueryEngine.pivot(ds, JSON.treeToValue(args, FinalPivotSpec.class)), emit));
        tools.put("chart", (ds, args, emit) ->
                show(FinalQueryEngine.chart(ds, JSON.treeToValue(args, FinalChartSpec.class)), emit));
        tools.put("compare_segments", (ds, args, emit) ->
                show(FinalQueryEngine.compare(ds, args.path("metric").asText(), args.path("group_by").asText(), conds(args)), emit));
        tools.put("funnel", (ds, args, emit) ->
                show(FinalQueryEngine.funnel(ds, args.path("by").asText(null), conds(args)), emit));
        tools.put("data_quality", (ds, args, emit) ->
                show(new FinalQualityAnalysis(ds).run(), emit));
        tools.put("drivers", (ds, args, emit) ->
                show(new FinalDriversAnalysis(ds, args.path("target").asText(), strings(args.path("factors")),
                        conds(args)).run(), emit));
        tools.put("abc_analysis", (ds, args, emit) ->
                showWithChart(new FinalAbcAnalysis(ds, args.path("dim").asText(), args.path("value").asText(null),
                        conds(args)).run(), emit));
        tools.put("trend", (ds, args, emit) ->
                showWithChart(new FinalTrendAnalysis(ds, args.path("metric").asText(null), args.path("agg").asText(null),
                        args.path("date").asText(null), args.path("granularity").asText(null),
                        args.path("horizon").asInt(3), conds(args)).run(), emit));
        tools.put("anomalies", (ds, args, emit) ->
                show(AbstractAnomalyAnalysis.create(ds, args.path("metric").asText(), args.path("by").asText(null),
                        conds(args)).run(), emit));
        tools.put("financial_summary", (ds, args, emit) ->
                show(new FinalFinanceAnalysis(ds, args.path("revenue").asText(null), args.path("cost").asText(null),
                        args.path("orders").asText(null), args.path("group_by").asText(null), conds(args)).run(), emit));
        tools.put("correlations", (ds, args, emit) ->
                show(correlations(ds, args.path("column").asText(null), args.path("top").asInt(12)), emit));
        tools.put("generate_image", (ds, args, emit) -> generateImage(args, emit));
        tools.put("create_report", this::createReport);
        tools.put("create_presentation", this::createPresentation);
        tools.put("add_column", this::addColumn);
        tools.put("filter_dataset", this::filterDataset);
        tools.put("graph_action", (ds, args, emit) -> graphAction(args, emit));
        tools.put("workspace_atlas", (ds, args, emit) -> show(atlasTable(args.path("top").asInt(15)), emit));
        tools.put("journey", (ds, args, emit) -> show(journeyTable(journeyRequest(args)), emit));
        tools.put("journey_profile", (ds, args, emit) -> show(profileTable(journeyRequest(args)), emit));
        tools.put("run_sql", (ds, args, emit) -> show(sqlTable(args.path("sql").asText(), args.path("title").asText(null)), emit));
    }

    /* ── несколько баз: атлас и пути ── */

    @SuppressWarnings("unchecked")
    private Map<String, Object> atlasTable(int top) {
        Map<String, Object> a = atlas.atlas(null);
        if (a.containsKey("error")) throw new ExceptionAnalitikData((String) a.get("error"));
        Map<String, String> dsName = new LinkedHashMap<>();
        for (Map<String, Object> d : (List<Map<String, Object>>) a.get("datasets")) dsName.put((String) d.get("id"), (String) d.get("name"));
        Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        for (Map<String, Object> n : (List<Map<String, Object>>) a.get("nodes")) nodes.put((String) n.get("id"), n);

        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> l : (List<Map<String, Object>>) a.get("links")) {
            if (Boolean.TRUE.equals(l.get("same"))) continue;
            Map<String, Object> s = nodes.get(l.get("source"));
            Map<String, Object> t = nodes.get(l.get("target"));
            rows.add(Arrays.asList(dsName.get(s.get("ds")) + ": " + s.get("label"), dsName.get(t.get("ds")) + ": " + t.get("label"),
                    l.get("label"), levelRuF((String) l.get("level")), "negative".equals(l.get("direction")) ? "обратная" : "прямая"));
            if (rows.size() >= top) break;
        }
        Map<String, Object> entity = (Map<String, Object>) a.get("entity");
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "table");
        t.put("title", "Атлас: связи между базами");
        t.put("subtitle", "Базы сведены к одной строке на «" + entity.get("key") + "» (" + entity.get("rows") + "); проверено "
                + a.get("tested") + " пар, значимы " + a.get("significant") + " (q < 0,05, Бенджамини–Хохберг)");
        t.put("head", List.of("Признак A", "Признак B", "Мера", "Сила", "Знак"));
        t.put("formats", List.of("text", "text", "text", "text", "text"));
        t.put("keyCols", 2);
        t.put("rows", rows);
        return t;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sqlTable(String query, String title) {
        Map<String, Object> r = sql.query(query, 200);
        List<String> types = (List<String>) r.get("types");
        List<String> formats = new ArrayList<>();
        for (String type : types) formats.add("NUMERIC".equals(type) ? "num" : "text");
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "table");
        t.put("title", title == null || title.isBlank() ? "Результат SQL" : title);
        t.put("subtitle", "SQL (DuckDB): " + query.replaceAll("\\s+", " ") + (Boolean.TRUE.equals(r.get("truncated")) ? " · показаны первые 200 строк" : ""));
        t.put("head", r.get("header"));
        t.put("formats", formats);
        t.put("keyCols", 1);
        t.put("rows", r.get("rows"));
        return t;
    }

    private static ServiceJourney.Request journeyRequest(JsonNode args) {
        ServiceJourney.Request r = new ServiceJourney.Request();
        r.goal = args.path("goal").asText(null);
        r.segmentColumn = args.path("segment_column").asText(null);
        r.segmentValue = args.path("segment_value").asText(null);
        r.node = args.path("step").asText(null);
        r.from = args.path("from").asText(null);
        r.to = args.path("to").asText(null);
        r.exitFrom = args.path("exit_from").asText(null);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> journeyTable(ServiceJourney.Request r) {
        Map<String, Object> j = journey.journey(r);
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> n : (List<Map<String, Object>>) j.get("nodes")) {
            if ("start".equals(n.get("kind"))) continue;
            rows.add(Arrays.asList(n.get("label"), n.get("userShare"), n.get("exitRate"), n.get("conv"), n.get("convRest"),
                    n.get("lift") == null ? (Boolean.TRUE.equals(n.get("required")) ? "обязательный" : "—") : n.get("lift"),
                    "none".equals(n.get("level")) ? "нет" : levelRuF((String) n.get("level"))));
        }
        List<String> paths = new ArrayList<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) j.get("goalPaths")) paths.add(p.get("path") + " (" + p.get("sessions") + ")");
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "table");
        t.put("title", "Пути клиентов" + (j.get("segment") != null ? " · сегмент " + j.get("segment") : "")
                + (j.get("goal") != null ? " → цель «" + j.get("goal") + "»" : ""));
        t.put("subtitle", j.get("users") + " клиентов, " + j.get("sessions") + " визитов, до цели дошли "
                + pct((double) j.get("conversion")) + ". Влияние шага — z-тест долей, FDR. Пути к цели: "
                + String.join("; ", paths.subList(0, Math.min(4, paths.size()))));
        t.put("head", List.of("Шаг", "Доля клиентов", "Выход с шага", "Конверсия: были", "Конверсия: не были", "×", "Значимость"));
        t.put("formats", List.of("text", "pct", "pct", "pct", "pct", "num", "text"));
        t.put("keyCols", 1);
        t.put("rows", rows);
        return t;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> profileTable(ServiceJourney.Request r) {
        Map<String, Object> p = journey.profile(r);
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> tr : (List<Map<String, Object>>) p.get("traits")) {
            rows.add(Arrays.asList(tr.get("column") + " = " + tr.get("value"), tr.get("share"), tr.get("shareRest"), tr.get("ratio"),
                    "over".equals(tr.get("direction")) ? "чаще" : "реже"));
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "table");
        t.put("title", "Кто эти люди: " + p.get("title"));
        t.put("subtitle", p.get("size") + " клиентов" + (p.get("conv") != null ? "; дошли до цели " + pct((double) p.get("conv"))
                + " против " + pct((double) p.get("convRest")) + " у остальных" : "")
                + "; показаны значимые отличия (q < 0,05)");
        t.put("head", List.of("Признак", "В группе", "У остальных", "×", "Чаще/реже"));
        t.put("formats", List.of("text", "pct", "pct", "num", "text"));
        t.put("keyCols", 1);
        t.put("rows", rows);
        return t;
    }

    private static String show(Map<String, Object> result, BiConsumer<String, Object> emit) throws Exception {
        emit.accept("artifact", result);
        return forModel(result);
    }

    private static String showWithChart(Map<String, Object> result, BiConsumer<String, Object> emit) throws Exception {
        Object chart = result.get("chart");
        Map<String, Object> tableOnly = new LinkedHashMap<>(result);
        tableOnly.remove("chart");
        emit.accept("artifact", tableOnly);
        if (chart != null) emit.accept("artifact", chart);
        return forModel(result);
    }

    private Map<String, Object> correlations(FinalDataset ds, String column, int top) {
        boolean all = column == null || column.isBlank();
        List<FinalGraphLink> links = brain.overview(ds).links.stream()
                .filter(l -> "brain".equals(l.type))
                .filter(l -> all || l.source.endsWith("::" + column) || l.target.endsWith("::" + column))
                .sorted((x, y) -> Double.compare(y.strength, x.strength))
                .limit(top)
                .collect(Collectors.toList());

        List<List<Object>> rows = new ArrayList<>();
        for (FinalGraphLink l : links) {
            rows.add(Arrays.asList(l.source.substring(3), l.target.substring(3), l.label, levelRuF(l.level),
                    l.text.split("\\. ")[0]));
        }

        Map<String, Object> t = new LinkedHashMap<>();
        t.put("type", "table");
        t.put("title", all ? "Сильнейшие связи в данных" : "Связи «" + column + "»");
        t.put("subtitle", "только значимые после поправки Бенджамини–Хохберга (q < 0,05)");
        t.put("head", List.of("Переменная A", "Переменная B", "Мера", "Сила", "Пояснение"));
        t.put("formats", List.of("text", "text", "text", "text", "text"));
        t.put("keyCols", 2);
        t.put("rows", rows);
        return t;
    }

    private String generateImage(JsonNode args, BiConsumer<String, Object> emit) throws Exception {
        String id = client.generateImage(args.path("prompt").asText(), args.path("aspect").asText("landscape"), null);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "image");
        r.put("url", "/api/v2/ai/images/" + id);
        r.put("prompt", args.path("prompt").asText());
        emit.accept("artifact", r);
        return "{\"ok\":true,\"url\":\"/api/v2/ai/images/" + id + "\"}";
    }

    private String createReport(FinalDataset ds, JsonNode args, BiConsumer<String, Object> emit) throws Exception {
        Map<String, Object> report = documents.buildReport(ds, args);
        emit.accept("artifact", report);
        return "{\"ok\":true,\"sections\":" + args.path("sections").size()
                + ",\"errors\":" + JSON.writeValueAsString(report.get("errors")) + "}";
    }

    private String createPresentation(FinalDataset ds, JsonNode args, BiConsumer<String, Object> emit) throws Exception {
        Map<String, Object> deck = documents.buildDeck(ds, args);
        emit.accept("artifact", deck);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> slides = (List<Map<String, Object>>) deck.get("slides");

        List<String> errors = slides.stream()
                .filter(s -> s.containsKey("error"))
                .map(s -> s.get("title") + ": " + s.get("error"))
                .collect(Collectors.toList());
        return "{\"ok\":true,\"slides\":" + slides.size() + ",\"errors\":" + JSON.writeValueAsString(errors) + "}";
    }

    private String addColumn(FinalDataset ds, JsonNode args, BiConsumer<String, Object> emit) throws Exception {
        Map<String, Object> r;
        synchronized (ds) {
            r = FinalQueryEngine.addColumn(ds, args.path("name").asText(), args.path("formula").asText());
        }
        emit.accept("artifact", r);
        emit.accept("datasetChanged", Map.of("id", ds.id, "version", ds.version));
        return JSON.writeValueAsString(r);
    }

    private String filterDataset(FinalDataset ds, JsonNode args, BiConsumer<String, Object> emit) throws Exception {
        FinalDataset part = FinalQueryEngine.subset(ds, args.path("name").asText(null), conds(args));
        brain.store.put(part.id, part);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "dataset");
        r.put("id", part.id);
        r.put("name", part.name);
        r.put("rows", part.rows);
        emit.accept("artifact", r);
        return JSON.writeValueAsString(r);
    }

    private String graphAction(JsonNode args, BiConsumer<String, Object> emit) throws Exception {
        JsonNode targets = args.path("targets").isMissingNode() ? JSON.createArrayNode() : args.path("targets");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "graph_action");
        r.put("action", args.path("action").asText("focus"));
        r.put("targets", JSON.treeToValue(targets, List.class));
        emit.accept("artifact", r);
        return "{\"ok\":true,\"note\":\"Действие выполнено на экране пользователя\"}";
    }

    public static Map<String, Object> table(FinalDataset ds, JsonNode spec) throws Exception {
        String kind = spec.path("kind").asText("pivot");
        List<FinalCondition> filters = conds(spec);
        Map<String, Object> r;
        switch (kind) {
            case "compare":
                r = FinalQueryEngine.compare(ds, spec.path("metric").asText(), spec.path("group_by").asText(), filters);
                break;
            case "funnel":
                r = FinalQueryEngine.funnel(ds, spec.path("by").asText(null), filters);
                break;
            case "drivers":
                r = new FinalDriversAnalysis(ds, spec.path("target").asText(spec.path("metric").asText()),
                        strings(spec.path("rows")), filters).run();
                break;
            case "abc":
                r = new FinalAbcAnalysis(ds, spec.path("dim").asText(spec.path("group_by").asText()),
                        spec.path("value").asText(spec.path("values").asText(null)), filters).run();
                r.remove("chart");
                break;
            case "trend":
                r = new FinalTrendAnalysis(ds, spec.path("metric").asText(spec.path("values").asText(null)),
                        spec.path("agg").asText(null), null, null, 3, filters).run();
                r.remove("chart");
                break;
            case "finance":
                r = new FinalFinanceAnalysis(ds, null, null, null, spec.path("group_by").asText(null), filters).run();
                break;
            case "quality":
                r = new FinalQualityAnalysis(ds).run();
                break;
            case "anomalies":
                r = AbstractAnomalyAnalysis.create(ds, spec.path("metric").asText(), spec.path("by").asText(null), filters).run();
                break;
            default:
                r = FinalQueryEngine.pivot(ds, JSON.treeToValue(spec, FinalPivotSpec.class));
        }
        if (spec.hasNonNull("title")) r.put("title", spec.get("title").asText());
        return r;
    }

    public static List<FinalCondition> conds(JsonNode args) throws Exception {
        List<FinalCondition> out = new ArrayList<>();
        for (JsonNode f : args.path("filters")) out.add(JSON.treeToValue(f, FinalCondition.class));
        return out;
    }

    public static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode x : array) out.add(x.asText());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String forModel(Map<String, Object> result) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>(result);
        m.remove("spec");
        m.remove("formats");
        if (m.get("rows") instanceof List && ((List<Object>) m.get("rows")).size() > 40) {
            m.put("rows", ((List<Object>) m.get("rows")).subList(0, 40));
            m.put("truncated", true);
        }
        if (m.get("points") instanceof List) {
            m.put("points", "(" + ((List<Object>) m.get("points")).size() + " точек)");
        }
        return JSON.writeValueAsString(m);
    }

    public static String stepLabel(String name, JsonNode a) {
        switch (name) {
            case "pivot_table":
                return "Сводная: " + join(a.path("rows"))
                        + (a.hasNonNull("columns") ? " × " + a.get("columns").asText() : "")
                        + " · " + a.path("agg").asText("")
                        + (a.hasNonNull("values") ? " " + a.get("values").asText() : "");
            case "chart":
                return "График " + a.path("type").asText("") + ": " + a.path("x").asText("")
                        + (a.hasNonNull("y") ? " → " + a.get("y").asText() : "");
            case "compare_segments":
                return "Сравниваю «" + a.path("metric").asText() + "» по «" + a.path("group_by").asText() + "»";
            case "funnel":
                return "Воронка" + (a.hasNonNull("by") ? " по «" + a.get("by").asText() + "»" : "");
            case "correlations":
                return "Ищу сильнейшие связи" + (a.hasNonNull("column") ? " для «" + a.get("column").asText() + "»" : "");
            case "add_column":
                return "Добавляю колонку «" + a.path("name").asText() + "»";
            case "filter_dataset":
                return "Создаю срез данных";
            case "graph_action":
                return "Показываю на графе";
            case "data_quality":
                return "Аудит качества данных";
            case "drivers":
                return "Многофакторная модель: драйверы «" + a.path("target").asText() + "»";
            case "abc_analysis":
                return "ABC-анализ «" + a.path("dim").asText() + "»"
                        + (a.hasNonNull("value") ? " по «" + a.get("value").asText() + "»" : "");
            case "trend":
                return "Динамика и прогноз" + (a.hasNonNull("metric") ? " «" + a.get("metric").asText() + "»" : "");
            case "anomalies":
                return "Поиск аномалий «" + a.path("metric").asText() + "»";
            case "financial_summary":
                return "Финансовая сводка и юнит-экономика"
                        + (a.hasNonNull("group_by") ? " по «" + a.get("group_by").asText() + "»" : "");
            case "generate_image":
                return "Генерирую изображение";
            case "create_report":
                return "Готовлю отчёт «" + a.path("title").asText() + "» (" + a.path("sections").size() + " разделов)";
            case "create_presentation":
                return "Собираю презентацию «" + a.path("title").asText() + "» (" + a.path("slides").size() + " слайдов)";
            default:
                return name;
        }
    }

    private static String join(JsonNode array) {
        List<String> parts = strings(array);
        return parts.isEmpty() ? "всё" : String.join(" × ", parts);
    }
}
