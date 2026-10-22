package com.analytics.brain.api;

import com.analytics.brain.ai.FinalChatRequest;
import com.analytics.brain.ai.ServiceAiChat;
import com.analytics.brain.ai.ServiceAiClient;
import com.analytics.brain.ai.ServiceAiTools;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.query.FinalChartSpec;
import com.analytics.brain.query.FinalPivotSpec;
import com.analytics.brain.query.FinalQueryEngine;
import com.analytics.brain.service.ServiceBrainGraph;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api/v2")
public class ControllerAiApi extends AbstractApiController {

    private final ServiceAiChat ai;
    private final ServiceAiClient client;

    private final ExecutorService pool = Executors.newCachedThreadPool();

    public ControllerAiApi(ServiceBrainGraph brain, ServiceAiChat ai, ServiceAiClient client) {
        super(brain);
        this.ai = ai;
        this.client = client;
    }

    @Override
    protected String notFoundHint() {
        return "";
    }

    @Override
    protected String unknownErrorText(Exception e) {
        return e.toString();
    }

    @GetMapping("/ai/config")
    public Map<String, Object> config() {
        return client.config();
    }

    @PostMapping("/ai/config")
    public Map<String, Object> saveConfig(@RequestBody Map<String, String> input) throws Exception {
        return client.saveConfig(input);
    }

    @GetMapping("/ai/models")
    public Map<String, Object> models() throws Exception {
        return Map.of("models", client.models(), "config", client.config());
    }

    @GetMapping("/ai/images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable String id) {
        Object[] picture = client.image(id);
        if (picture == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType((String) picture[1]))
                .header("Cache-Control", "max-age=86400")
                .body((byte[]) picture[0]);
    }

    @PostMapping("/ai/image")
    public Map<String, Object> generateImage(@RequestBody Map<String, String> b) throws Exception {
        String id = client.generateImage(b.get("prompt"), b.getOrDefault("aspect", "landscape"), b.get("theme"));
        return Map.of("url", "/api/v2/ai/images/" + id);
    }

    @PostMapping(value = "/ai/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody FinalChatRequest request) {
        SseEmitter emitter = new SseEmitter(600_000L);
        pool.submit(() -> {
            try {
                ai.chat(request, (event, data) -> send(emitter, event, data));
                emitter.send(SseEmitter.event().name("done").data("{}"));
                emitter.complete();
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                try {
                    emitter.send(SseEmitter.event().name("error").data(JSON.writeValueAsString(Map.of("message", message))));
                    emitter.complete();
                } catch (Exception ignored) {
                    emitter.completeWithError(e);
                }
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(JSON.writeValueAsString(data), MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @PostMapping("/datasets/{id}/pivot")
    public Map<String, Object> pivot(@PathVariable String id, @RequestBody FinalPivotSpec spec) {
        return FinalQueryEngine.pivot(dataset(id), spec);
    }

    @PostMapping("/datasets/{id}/chart")
    public Map<String, Object> chart(@PathVariable String id, @RequestBody FinalChartSpec spec) {
        return FinalQueryEngine.chart(dataset(id), spec);
    }

    @PostMapping("/datasets/{id}/column")
    public Map<String, Object> column(@PathVariable String id, @RequestBody Map<String, String> b) {
        FinalDataset ds = dataset(id);
        synchronized (ds) {
            return FinalQueryEngine.addColumn(ds, b.get("name"), b.get("formula"));
        }
    }

    @PostMapping("/datasets/{id}/analysis/{kind}")
    public Map<String, Object> analysis(@PathVariable String id, @PathVariable String kind,
                                        @RequestBody Map<String, Object> body) throws Exception {
        ObjectNode spec = JSON.valueToTree(body);
        spec.put("kind", kind);
        return ServiceAiTools.table(dataset(id), spec);
    }
}
