package com.analytics.brain.ai;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalDatasetRoles;
import com.analytics.brain.graph.FinalGraphPayload;
import com.analytics.brain.mathematics.FinalAnalitikMathematics;
import com.analytics.brain.service.ServiceBrainGraph;
import com.analytics.brain.service.ServiceSql;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static com.analytics.brain.ai.ServiceAiClient.JSON;
import static com.analytics.brain.text.FinalFormatRu.fmt;

@Service
public class ServiceAiChat {

    private static final int MAX_STEPS = 10;

    private final ServiceBrainGraph brain;
    private final ServiceAiClient client;
    private final ServiceAiTools tools;
    private final ServiceSql sql;
    private final String toolDefinitions;
    private final String systemPrompt;

    public ServiceAiChat(ServiceBrainGraph brain, ServiceAiClient client, ServiceAiTools tools, ServiceSql sql) throws IOException {
        this.sql = sql;
        this.brain = brain;
        this.client = client;
        this.tools = tools;

        String filterSchema = "{\"type\":\"array\",\"description\":\"Фильтры строк\",\"items\":{\"type\":\"object\","
                + "\"properties\":{\"column\":{\"type\":\"string\"},"
                + "\"op\":{\"type\":\"string\",\"enum\":[\"=\",\"!=\",\">\",\">=\",\"<\",\"<=\",\"in\",\"not in\",\"contains\"]},"
                + "\"value\":{\"type\":\"string\"},\"values\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                + "\"required\":[\"column\"]}}";
        this.toolDefinitions = resource("ai/tools.json")
                .replaceAll("\\{\\s*\"\\$ref\"\\s*:\\s*\"#/filters\"\\s*\\}", Matcher.quoteReplacement(filterSchema));
        this.systemPrompt = resource("ai/system.md");
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = ServiceAiChat.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void chat(FinalChatRequest request, BiConsumer<String, Object> emit) throws Exception {
        FinalDataset ds = request.datasetId == null ? null : brain.store.get(request.datasetId);
        String wanted = request.model != null && !request.model.isBlank() ? request.model : client.setting("model", null);
        String model = client.resolve(wanted, false);
        emit.accept("meta", Map.of("model", model));

        ArrayNode messages = buildMessages(request, ds, emit);
        JsonNode toolDefs = JSON.readTree(toolDefinitions);
        boolean useTools = ds != null;

        for (int step = 0; step < MAX_STEPS; step++) {
            ObjectNode body = JSON.createObjectNode();
            body.put("model", model);
            body.set("messages", messages);
            if (useTools) {
                body.set("tools", toolDefs);
                body.put("tool_choice", "auto");
            }
            body.put("temperature", Double.parseDouble(client.setting("temperature", "0.4")));
            body.put("max_tokens", Integer.parseInt(client.setting("maxTokens", "8000")));

            JsonNode response = client.chatCompletion(body);
            JsonNode message = response.path("choices").path(0).path("message");
            if (response.has("usage")) emit.accept("usage", response.get("usage"));

            JsonNode calls = message.path("tool_calls");
            if (!calls.isArray() || calls.size() == 0) {
                String text = message.path("content").asText("");
                if (text.isBlank() && message.has("reasoning_content")) {
                    text = message.path("reasoning_content").asText("");
                }
                emit.accept("answer", Map.of("text", text));
                return;
            }

            ObjectNode assistant = message.deepCopy();
            if (!assistant.has("content") || assistant.get("content").isNull()) assistant.put("content", "");
            messages.add(assistant);

            for (JsonNode call : calls) {
                String result = runCall(ds, call, emit);
                messages.addObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.path("id").asText())
                        .put("name", call.path("function").path("name").asText())
                        .put("content", result);
            }
        }
        emit.accept("answer", Map.of("text", "Остановился после " + MAX_STEPS + " шагов анализа — уточните вопрос."));
    }

    private ArrayNode buildMessages(FinalChatRequest request, FinalDataset ds, BiConsumer<String, Object> emit) throws Exception {
        ArrayNode messages = JSON.createArrayNode();
        String dataPart = ds == null
                ? "Набор данных не загружен — можешь отвечать общими советами и предлагать загрузить данные."
                : context(ds);
        messages.addObject().put("role", "system").put("content", systemPrompt + "\n\n" + dataPart);

        List<Map<String, Object>> history = request.messages;
        if (history.size() > 14) history = history.subList(history.size() - 14, history.size());

        for (int k = 0; k < history.size(); k++) {
            Map<String, Object> m = history.get(k);
            String role = String.valueOf(m.get("role"));
            String text = String.valueOf(m.getOrDefault("content", ""));
            boolean lastUserMessage = k == history.size() - 1 && role.equals("user");
            if (lastUserMessage) {
                @SuppressWarnings("unchecked")
                List<String> pictures = (List<String>) m.get("images");
                if (pictures != null && !pictures.isEmpty()) {
                    text = describeImages(text, pictures, emit) + "\n\n" + text;
                }
                if (request.context != null && !request.context.isEmpty()) {
                    text = "[Контекст интерфейса: " + JSON.writeValueAsString(request.context) + "]\n" + text;
                }
            }
            messages.addObject().put("role", role.equals("assistant") ? "assistant" : "user").put("content", text);
        }
        return messages;
    }

    private String runCall(FinalDataset ds, JsonNode call, BiConsumer<String, Object> emit) throws Exception {
        String name = call.path("function").path("name").asText();
        JsonNode args;
        try {
            args = JSON.readTree(call.path("function").path("arguments").asText("{}"));
        } catch (Exception e) {
            args = JSON.createObjectNode();
        }
        emit.accept("step", Map.of("tool", name, "label", ServiceAiTools.stepLabel(name, args), "args", args));

        try {
            return tools.run(ds, name, args, emit);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            emit.accept("stepError", Map.of("tool", name, "message", String.valueOf(e.getMessage())));
            return "{\"error\":" + JSON.writeValueAsString(message) + "}";
        }
    }

    private String describeImages(String question, List<String> pictures, BiConsumer<String, Object> emit) throws Exception {
        String visionModel = client.resolve(client.setting("visionModel", ""), true);
        if (visionModel == null || visionModel.isBlank()) visionModel = client.resolve(client.setting("model", null), false);
        emit.accept("step", Map.of("tool", "vision", "label", "Смотрю на изображение (" + visionModel + ")",
                "args", Map.of("images", pictures.size())));

        ObjectNode body = JSON.createObjectNode();
        body.put("model", visionModel);
        body.put("max_tokens", 3000);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content",
                "Ты аналитик. Подробно опиши изображение: тип (график, таблица, дашборд, документ, фото), все подписи, "
                        + "оси, легенду, ВСЕ числа и значения (таблицы переписывай в markdown), тренды и аномалии. "
                        + "Отвечай по-русски, без домыслов.");
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        ArrayNode parts = user.putArray("content");
        parts.addObject().put("type", "text").put("text", "Вопрос пользователя: " + question);
        for (String picture : pictures) {
            parts.addObject().put("type", "image_url").putObject("image_url").put("url", picture);
        }

        JsonNode response = client.chatCompletion(body);
        String description = response.path("choices").path(0).path("message").path("content").asText("");
        emit.accept("vision", Map.of("model", visionModel, "text", description));
        return "[Изображение от пользователя — описание vision-модели " + visionModel + "]:\n" + description;
    }

    String context(FinalDataset ds) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Набор данных «").append(ds.name).append("»: ").append(ds.rows)
                .append(" строк (").append(ds.entity).append(")\n");
        if (!ds.kpis.isEmpty()) sb.append("KPI: ").append(String.join(", ", ds.kpis)).append("\n");
        if (ds.funnelColumn != null) {
            sb.append("Воронка: колонка «").append(ds.funnelColumn).append("», этапы: ")
                    .append(String.join(" → ", ds.col(ds.funnelColumn).levels)).append("\n");
        }

        sb.append("\n### Колонки\n");
        for (FinalColumn c : ds.columns) {
            sb.append("- ").append(c.name).append(" [").append(c.typeRu()).append("]");
            if (c.type == EnumColumnType.NUMERIC) {
                double[] x = FinalAnalitikMathematics.clean(c.num);
                if (x.length > 0) {
                    double[] s = FinalAnalitikMathematics.sorted(x);
                    sb.append(": среднее ").append(fmt(FinalAnalitikMathematics.mean(x)))
                            .append(", медиана ").append(fmt(FinalAnalitikMathematics.quantile(s, .5)))
                            .append(", мин ").append(fmt(s[0]))
                            .append(", макс ").append(fmt(s[s.length - 1]));
                }
            } else if (c.levels != null) {
                sb.append(": ").append(c.levels.stream().limit(12).collect(Collectors.joining(", ")))
                        .append(c.levels.size() > 12 ? " …" : "");
            }
            if (c.missing > 0) sb.append(" (пропусков ").append(c.missing).append(")");
            sb.append("\n");
        }

        String imageModel = client.imageModel();
        sb.append("\nГенерация изображений: ")
                .append(imageModel == null
                        ? "НЕ настроена (не используй image_prompt и generate_image)"
                        : "доступна, модель " + imageModel)
                .append("\n");

        List<FinalDataset> workspace = brain.workspace(null);
        if (workspace.size() > 1) {
            sb.append("\n### Рабочее пространство (").append(workspace.size()).append(" баз)\n");
            for (FinalDataset w : workspace) {
                sb.append("- «").append(w.name).append("»: ").append(w.rows).append(" строк, роль — ")
                        .append(FinalDatasetRoles.roleRu(w.role))
                        .append(w.keyColumn == null ? "" : ", ключ «" + w.keyColumn + "»")
                        .append(w.stepColumn == null ? "" : ", шаг «" + w.stepColumn + "»")
                        .append(w == ds ? " (открыта сейчас)" : "").append("\n");
            }
            sb.append("Связи между базами — workspace_atlas; пути клиентов по базе событий — journey / journey_profile.\n");
        }

        String schema = sql.schemaText();
        if (!schema.isEmpty()) {
            sb.append("\n### Таблицы для run_sql (DuckDB; имена таблиц и колонок — в двойных кавычках)\n").append(schema);
        }

        FinalGraphPayload overview = brain.overview(ds);
        sb.append("\n### Главные статистические находки (уже посчитаны)\n");
        overview.insights.stream().limit(10).forEach(i -> sb.append("- ").append(i.text).append("\n"));
        return sb.toString();
    }
}
