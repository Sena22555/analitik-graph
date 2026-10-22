package com.analytics.controller;

import com.analytics.model.AnalyticsGraph;
import com.analytics.service.AnalyticsGraphEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API для работы с графами аналитики
 */
@RestController
@RequestMapping("/api/v1/graphs")
public class GraphController {
    
    @Autowired
    private AnalyticsGraphEngine analyticsEngine;
    
    // Хранилище графов (в продакшене - БД)
    private final Map<String, AnalyticsGraph> graphs = new HashMap<>();
    
    /**
     * POST /api/v1/graphs - создать новый граф из CSV данных
     * 
     * Body:
     * {
     *   "csvData": "var1,var2,var3\n1,2,3\n4,5,6\n...",
     *   "name": "My Analytics Graph"
     * }
     */
    @PostMapping
    public ResponseEntity<?> createGraph(@RequestBody GraphRequest request) {
        try {
            System.out.println("=== CREATE GRAPH REQUEST ===");
            System.out.println("Name: " + request.getName());
            System.out.println("CSV Data length: " + (request.getCsvData() != null ? request.getCsvData().length() : "null"));
            
            if (request.getCsvData() == null || request.getCsvData().isEmpty()) {
                System.out.println("ERROR: CSV data is null or empty");
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("CSV data is required"));
            }
            
            String graphName = request.getName() != null ? request.getName() : "Unnamed Graph";
            
            System.out.println("Building graph...");
            AnalyticsGraph graph = analyticsEngine.buildGraph(request.getCsvData(), graphName);
            System.out.println("Graph built successfully: " + graph.getId());
            
            graphs.put(graph.getId(), graph);
            
            return ResponseEntity.status(HttpStatus.CREATED).body(graph);
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse("Error building graph: " + e.getMessage()));
        }
    }
    
    /**
     * GET /api/v1/graphs/{id} - получить граф по ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getGraph(@PathVariable String id) {
        AnalyticsGraph graph = graphs.get(id);
        
        if (graph == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Graph not found: " + id));
        }
        
        return ResponseEntity.ok(graph);
    }

    @GetMapping("/{id}/insights")
    public ResponseEntity<?> getInsights(@PathVariable String id) {
        AnalyticsGraph graph = graphs.get(id);
        if (graph == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Граф не найден: " + id));
        }
        return ResponseEntity.ok(graph.getInsights());
    }
    
    /**
     * GET /api/v1/graphs - получить все графы
     */
    @GetMapping
    public ResponseEntity<?> getAllGraphs() {
        return ResponseEntity.ok(new GraphListResponse(graphs.values()));
    }
    
    /**
     * DELETE /api/v1/graphs/{id} - удалить граф
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteGraph(@PathVariable String id) {
        if (!graphs.containsKey(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Graph not found: " + id));
        }
        
        graphs.remove(id);
        return ResponseEntity.ok(new SuccessResponse("Graph deleted: " + id));
    }
    
    /**
     * GET /api/v1/graphs/{id}/statistics - получить статистику графа
     */
    @GetMapping("/{id}/statistics")
    public ResponseEntity<?> getGraphStatistics(@PathVariable String id) {
        AnalyticsGraph graph = graphs.get(id);
        
        if (graph == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Graph not found: " + id));
        }
        
        return ResponseEntity.ok(graph.getStatistics());
    }
    
    /**
     * GET /api/v1/graphs/{id}/variables - получить список переменных
     */
    @GetMapping("/{id}/variables")
    public ResponseEntity<?> getVariables(@PathVariable String id) {
        AnalyticsGraph graph = graphs.get(id);
        
        if (graph == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Graph not found: " + id));
        }
        
        return ResponseEntity.ok(new VariablesResponse(graph.getVariables().values()));
    }
    
    /**
     * GET /api/v1/graphs/{id}/variables/{name} - получить информацию о переменной
     */
    @GetMapping("/{id}/variables/{name}")
    public ResponseEntity<?> getVariable(@PathVariable String id, @PathVariable String name) {
        AnalyticsGraph graph = graphs.get(id);
        
        if (graph == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Graph not found: " + id));
        }
        
        var variable = graph.getVariable(name);
        if (variable == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Variable not found: " + name));
        }
        
        return ResponseEntity.ok(variable);
    }
    
    /**
     * GET /api/v1/graphs/{id}/correlations - получить матрицу корреляций
     */
    @GetMapping("/{id}/correlations")
    public ResponseEntity<?> getCorrelations(@PathVariable String id) {
        AnalyticsGraph graph = graphs.get(id);
        
        if (graph == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Graph not found: " + id));
        }
        
        return ResponseEntity.ok(new CorrelationsResponse(
                graph.getAdjacencyMatrix(),
                graph.getStatistics().getStrongestCorrelations()
        ));
    }
    
    /**
     * GET /api/v1/graphs/{id}/timeseries - получить временной ряд событий
     */
    @GetMapping("/{id}/timeseries")
    public ResponseEntity<?> getTimeSeries(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "100") int limit
    ) {
        AnalyticsGraph graph = graphs.get(id);
        
        if (graph == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Graph not found: " + id));
        }
        
        var events = graph.getTimeSeries()
                .stream()
                .skip(skip)
                .limit(limit)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(new TimeSeriesResponse(events, graph.getEventCount()));
    }
    
    /**
     * GET /api/v1/graphs/{id}/adjacency - получить матрицу смежности
     */
    @GetMapping("/{id}/adjacency")
    public ResponseEntity<?> getAdjacencyMatrix(@PathVariable String id) {
        AnalyticsGraph graph = graphs.get(id);
        
        if (graph == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("Graph not found: " + id));
        }
        
        return ResponseEntity.ok(graph.getAdjacencyMatrix());
    }
    
    // ========== DTO Response Classes ==========
    
    public static class GraphRequest {
        private String csvData;
        private String name;
        
        public String getCsvData() { return csvData; }
        public void setCsvData(String csvData) { this.csvData = csvData; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }
    
    public static class ErrorResponse {
        private String error;
        private long timestamp;
        
        public ErrorResponse(String error) {
            this.error = error;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getError() { return error; }
        public long getTimestamp() { return timestamp; }
    }
    
    public static class SuccessResponse {
        private String message;
        private long timestamp;
        
        public SuccessResponse(String message) {
            this.message = message;
            this.timestamp = System.currentTimeMillis();
        }
        
        public String getMessage() { return message; }
        public long getTimestamp() { return timestamp; }
    }
    
    public static class GraphListResponse {
        private Collection<?> graphs;
        private int count;
        
        public GraphListResponse(Collection<?> graphs) {
            this.graphs = graphs;
            this.count = graphs.size();
        }
        
        public Collection<?> getGraphs() { return graphs; }
        public int getCount() { return count; }
    }
    
    public static class VariablesResponse {
        private Collection<?> variables;
        private int count;
        
        public VariablesResponse(Collection<?> variables) {
            this.variables = variables;
            this.count = variables.size();
        }
        
        public Collection<?> getVariables() { return variables; }
        public int getCount() { return count; }
    }
    
    public static class CorrelationsResponse {
        private Map<String, Map<String, Double>> matrix;
        private List<?> strongestCorrelations;
        
        public CorrelationsResponse(Map<String, Map<String, Double>> matrix, List<?> strongestCorrelations) {
            this.matrix = matrix;
            this.strongestCorrelations = strongestCorrelations;
        }
        
        public Map<String, Map<String, Double>> getMatrix() { return matrix; }
        public List<?> getStrongestCorrelations() { return strongestCorrelations; }
    }
    
    public static class TimeSeriesResponse {
        private List<?> events;
        private int totalCount;
        
        public TimeSeriesResponse(List<?> events, int totalCount) {
            this.events = events;
            this.totalCount = totalCount;
        }
        
        public List<?> getEvents() { return events; }
        public int getTotalCount() { return totalCount; }
    }
}
