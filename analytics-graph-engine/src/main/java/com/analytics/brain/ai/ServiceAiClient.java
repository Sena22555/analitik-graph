package com.analytics.brain.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Service
public class ServiceAiClient {

    public static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<String, String> IMAGE_STYLE = Map.of(
            "executive", "corporate editorial photography, muted navy and warm grey palette, soft daylight, shallow depth of field",
            "ledger", "clean architectural photography, deep green and ivory tones, precise geometry, calm",
            "editorial", "fine-art editorial photograph, warm film grain, terracotta and cream palette, natural light",
            "midnight", "cinematic night photography, deep teal and amber accents, dark moody atmosphere",
            "bold", "vivid contemporary 3D illustration, coral and ink colors, bold simple shapes, studio lighting",
            "minimal", "minimalist Swiss-style abstract composition, black white and a single red accent, lots of negative space");

    private final Properties cfg = new Properties();
    private final Path cfgPath;
    private List<String> modelsCache;
    private long modelsLoadedAt;

    private final Map<String, Object[]> images = Collections.synchronizedMap(
            new LinkedHashMap<String, Object[]>(16, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
                    return size() > 150;
                }
            });

    public ServiceAiClient() throws IOException {
        String file = System.getProperty("analitik.ai.config",
                System.getenv().getOrDefault("ANALITIK_AI_CONFIG", "analitik-ai.properties"));
        cfgPath = Paths.get(file).toAbsolutePath();
        if (Files.exists(cfgPath)) {
            try (Reader r = Files.newBufferedReader(cfgPath, StandardCharsets.UTF_8)) {
                cfg.load(r);
            }
        }

        fromEnv("AI_BASE_URL", "baseUrl");
        fromEnv("AI_API_KEY", "apiKey");
        fromEnv("AI_MODEL", "model");
        fromEnv("AI_VISION_MODEL", "visionModel");
        fromEnv("AI_IMAGE_MODEL", "imageModel");
        cfg.putIfAbsent("baseUrl", "https://api.openai.com/v1");
        cfg.putIfAbsent("model", "gpt-4o-mini");
    }

    private void fromEnv(String variable, String property) {
        String v = System.getenv(variable);
        if (v != null && !v.isBlank()) cfg.setProperty(property, v);
    }

    String setting(String name, String fallback) {
        return cfg.getProperty(name, fallback);
    }

    public Map<String, Object> config() {
        String key = cfg.getProperty("apiKey", "");
        String masked;
        if (key.length() > 10) {
            masked = key.substring(0, 5) + "…" + key.substring(key.length() - 4);
        } else {
            masked = key.isBlank() ? "" : "•••";
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", !key.isBlank());
        m.put("baseUrl", cfg.getProperty("baseUrl"));
        m.put("model", cfg.getProperty("model"));
        m.put("visionModel", cfg.getProperty("visionModel", ""));
        m.put("imageModel", cfg.getProperty("imageModel", ""));
        m.put("key", masked);
        m.put("configFile", cfgPath.toString());
        return m;
    }

    public synchronized Map<String, Object> saveConfig(Map<String, String> input) throws IOException {
        // ключ привязан к адресу провайдера: при смене адреса без нового ключа старый не уходит на новый сервер
        String newBase = input.get("baseUrl") == null ? null : input.get("baseUrl").trim().replaceAll("/+$", "");
        String newKey = input.getOrDefault("apiKey", "").trim();
        boolean baseChanged = newBase != null && !newBase.equals(cfg.getProperty("baseUrl", ""));
        if (baseChanged && (newKey.isEmpty() || newKey.contains("…"))) cfg.setProperty("apiKey", "");

        for (String name : List.of("baseUrl", "apiKey", "model", "visionModel", "imageModel")) {
            String v = input.get(name);
            if (v == null) continue;
            v = v.trim();

            if (name.equals("apiKey") && (v.isEmpty() || v.contains("…"))) continue;
            if (name.equals("baseUrl")) v = v.replaceAll("/+$", "");
            cfg.setProperty(name, v);
        }
        try (Writer w = Files.newBufferedWriter(cfgPath, StandardCharsets.UTF_8)) {
            cfg.store(w, "Analitik Graph — настройки ИИ (не публикуйте этот файл: в нём API-ключ)");
        }
        modelsCache = null;
        return config();
    }

    public List<String> models() throws Exception {
        if (modelsCache != null && System.currentTimeMillis() - modelsLoadedAt < 600_000) return modelsCache;

        HttpResponse<String> r = HTTP.send(request("/models").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) {
            throw new IllegalStateException("Провайдер ответил " + r.statusCode() + ": " + snippet(r.body()));
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode m : JSON.readTree(r.body()).path("data")) ids.add(m.path("id").asText());
        Collections.sort(ids);
        modelsCache = ids;
        modelsLoadedAt = System.currentTimeMillis();
        return ids;
    }

    String resolve(String wanted, boolean vision) {
        List<String> ids;
        try {
            ids = models();
        } catch (Exception e) {
            return wanted;
        }
        if (wanted != null && !wanted.isBlank()) {
            if (ids.contains(wanted)) return wanted;
            String w = normalize(wanted);
            for (String id : ids) if (normalize(id).equals(w)) return id;
            for (String id : ids) if (normalize(id).endsWith(w)) return id;
            for (String id : ids) if (normalize(id).contains(w)) return id;
        }
        if (vision) {
            for (String pattern : List.of("vl", "vision", "gpt4o", "gpt41", "gemini", "claude", "gpt5")) {
                for (String id : ids) if (normalize(id).contains(pattern)) return id;
            }
        }
        return wanted;
    }

    String imageModel() {
        String wanted = cfg.getProperty("imageModel", "");
        List<String> ids;
        try {
            ids = models();
        } catch (Exception e) {
            return wanted.isBlank() ? null : wanted;
        }
        if (!wanted.isBlank()) return resolve(wanted, false);

        List<String> patterns = List.of("gptimage", "dalle3", "dalle", "flux", "imagen", "seedream", "recraft",
                "ideogram", "kandinsky", "sdxl", "stablediffusion", "midjourney", "image");
        for (String pattern : patterns) {
            for (String id : ids) {
                if (normalize(id).contains(pattern) && !normalize(id).contains("vision")) return id;
            }
        }
        return null;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private HttpRequest.Builder request(String path) {
        String key = cfg.getProperty("apiKey", "");
        if (key.isBlank()) throw new IllegalStateException("ИИ не настроен: укажите API-ключ в настройках (⚙)");
        String base = cfg.getProperty("baseUrl").replaceAll("/+$", "");
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(240))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json");
    }

    JsonNode chatCompletion(ObjectNode body) throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpRequest httpRequest = request("/chat/completions")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> r = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = r.statusCode();

            if (status < 400) {
                JsonNode json = JSON.readTree(r.body());
                if (json.has("error") && !json.has("choices")) {
                    throw new IllegalStateException("Ошибка провайдера: " + snippet(json.get("error").toString()));
                }
                return json;
            }

            String error = r.body() == null ? "" : r.body().toLowerCase(Locale.ROOT);
            if (status == 400 || status == 422) {
                if (error.contains("temperature") && body.has("temperature")) {
                    body.remove("temperature");
                    continue;
                }
                if ((error.contains("max_tokens") || error.contains("max tokens")) && body.has("max_tokens")) {
                    body.remove("max_tokens");
                    continue;
                }
                if (error.contains("tool") && body.has("tools")) {
                    body.remove("tools");
                    body.remove("tool_choice");
                    continue;
                }
            }
            if (status == 401 || status == 403) {
                throw new IllegalStateException("Ключ не принят провайдером (" + status + "). Проверьте API-ключ в настройках.");
            }
            if (status == 404 && error.contains("model")) {
                throw new IllegalStateException("Модель «" + body.path("model").asText()
                        + "» не найдена у провайдера. Выберите другую в настройках ⚙.");
            }
            if (status == 429 && attempt < 2) {
                Thread.sleep(2500L * (attempt + 1));
                continue;
            }
            throw new IllegalStateException("Провайдер ответил " + status + ": " + snippet(r.body()));
        }
        throw new IllegalStateException("Не удалось получить ответ модели");
    }

    public Object[] image(String id) {
        return images.get(id);
    }

    public String generateImage(String prompt, String aspect, String theme) throws Exception {
        String model = imageModel();
        if (model == null) throw new IllegalStateException("Модель изображений не настроена (⚙ → «Модель изображений»)");

        String size;
        if ("portrait".equals(aspect)) {
            size = "1024x1536";
        } else if ("square".equals(aspect)) {
            size = "1024x1024";
        } else {
            size = "1536x1024";
        }
        String style = IMAGE_STYLE.getOrDefault(theme == null ? "" : theme,
                "high-quality professional photography, natural light");

        String fullPrompt = prompt + ". " + style + ". No text, no letters, no numbers, no logos, no watermark.";

        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        body.put("prompt", fullPrompt);
        body.put("n", 1);
        body.put("size", size);

        for (int attempt = 0; attempt < 3; attempt++) {
            HttpRequest httpRequest = request("/images/generations")
                    .timeout(Duration.ofSeconds(180))
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> r = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 400) {
                String error = r.body() == null ? "" : r.body().toLowerCase(Locale.ROOT);
                if (error.contains("size") && !"1024x1024".equals(body.path("size").asText())) {
                    body.put("size", "1024x1024");
                    continue;
                }
                if (attempt == 0 && (error.contains("dall-e-3") || error.contains("1792"))) {
                    body.put("size", "1792x1024");
                    continue;
                }
                throw new IllegalStateException("Генерация изображения: провайдер ответил " + r.statusCode() + ": " + snippet(r.body()));
            }

            JsonNode data = JSON.readTree(r.body()).path("data").path(0);
            Object[] picture = readPicture(data);
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            images.put(id, picture);
            return id;
        }
        throw new IllegalStateException("Не удалось сгенерировать изображение");
    }

    private Object[] readPicture(JsonNode data) throws Exception {
        byte[] bytes;
        String mime = "image/png";
        if (data.hasNonNull("b64_json")) {
            bytes = Base64.getDecoder().decode(data.get("b64_json").asText());
        } else if (data.hasNonNull("url")) {
            String url = data.get("url").asText();
            if (url.startsWith("data:")) {
                mime = url.substring(5, url.indexOf(';'));
                bytes = Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1));
            } else {
                HttpRequest download = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build();
                HttpResponse<byte[]> r = HTTP.send(download, HttpResponse.BodyHandlers.ofByteArray());
                bytes = r.body();
                mime = r.headers().firstValue("content-type").orElse("image/png");
            }
        } else {
            throw new IllegalStateException("Провайдер не вернул изображение");
        }
        boolean jpeg = bytes.length > 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8;
        if (jpeg) mime = "image/jpeg";
        return new Object[]{bytes, mime};
    }

    public static String snippet(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
