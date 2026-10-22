package com.analytics.brain.api;

import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.exception.ExceptionDatasetNotFound;
import com.analytics.brain.service.ServiceBrainGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;
import java.util.NoSuchElementException;

public abstract class AbstractApiController {

    protected static final ObjectMapper JSON = new ObjectMapper();

    protected final ServiceBrainGraph brain;

    protected AbstractApiController(ServiceBrainGraph brain) {
        this.brain = brain;
    }

    protected final FinalDataset dataset(String id) {
        FinalDataset d = brain.store.get(id);
        if (d == null) throw new ExceptionDatasetNotFound("Набор данных не найден: " + id + notFoundHint());
        return d;
    }

    protected abstract String notFoundHint();

    protected abstract String unknownErrorText(Exception e);

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, Object>> notFound(Exception e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> error(Exception e) {
        String message = e.getMessage() == null ? unknownErrorText(e) : e.getMessage();
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
