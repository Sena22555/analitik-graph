package com.analytics.brain.api;

import com.analytics.brain.data.FinalCsvParser;
import com.analytics.brain.data.FinalDataLoader;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalDemoData;
import com.analytics.brain.data.FinalJsonParser;
import com.analytics.brain.exception.ExceptionAnalitikData;
import com.analytics.brain.graph.FinalGraphPayload;
import com.analytics.brain.graph.FinalGraphRef;
import com.analytics.brain.service.ServiceBrainGraph;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v2")
public class ControllerBrainApi extends AbstractApiController {

    public ControllerBrainApi(ServiceBrainGraph brain) {
        super(brain);
    }

    @Override
    protected String notFoundHint() {
        return " (сервер перезапускался?)";
    }

    @Override
    protected String unknownErrorText(Exception e) {
        return e.getClass().getSimpleName();
    }

    @PostMapping("/datasets")
    public Map<String, Object> create(@RequestBody FinalCreateDatasetRequest r) throws Exception {
        FinalDataset ds = new FinalDataset();
        ds.id = UUID.randomUUID().toString().substring(0, 8);
        ds.fetchedAt = System.currentTimeMillis();

        if (r.url != null && !r.url.isBlank()) {
            ds.sourceType = "url";
            ds.sourceUrl = r.url.trim();
            if (r.headers != null) ds.headers.putAll(r.headers);
            String[] response = FinalDataLoader.fetch(ds.sourceUrl, ds.headers);
            FinalDataLoader.parseAuto(ds, response[0], response[1]);
            ds.name = r.name != null && !r.name.isBlank() ? r.name : shortName(ds.sourceUrl);
        } else if (r.data != null && !r.data.isNull()) {
            ds.sourceType = "push";
            new FinalJsonParser().parse(ds, r.data.toString());
            ds.name = r.name != null ? r.name : "API-данные";
        } else if (r.csv != null && !r.csv.isBlank()) {
            ds.sourceType = "file";
            new FinalCsvParser().parse(ds, r.csv);
            ds.name = r.name != null ? r.name : "CSV";
        } else {
            throw new ExceptionAnalitikData("Передайте csv, data или url");
        }

        FinalDataLoader.build(ds);
        brain.store.put(ds.id, ds);
        return ds.summary();
    }

    @GetMapping("/datasets")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (FinalDataset d : brain.store.values()) result.add(d.summary());
        return result;
    }

    @GetMapping("/datasets/{id}")
    public Map<String, Object> meta(@PathVariable String id) {
        return dataset(id).summary();
    }

    /** Профиль колонок: заполненность, уникальность, распределение, частые значения, проблемы данных. */
    @GetMapping("/datasets/{id}/profile")
    public Map<String, Object> profile(@PathVariable String id) {
        FinalDataset ds = dataset(id);
        synchronized (ds) {
            return Map.of("name", ds.name, "rows", ds.rows, "columns", com.analytics.brain.data.FinalColumnProfile.of(ds));
        }
    }

    /** Строки таблицы для просмотра: поиск по всем ячейкам, сортировка (числа — как числа), страницы. */
    @GetMapping("/datasets/{id}/rows")
    public Map<String, Object> rows(@PathVariable String id,
                                    @RequestParam(defaultValue = "0") int offset,
                                    @RequestParam(defaultValue = "100") int limit,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(defaultValue = "false") boolean desc) {
        FinalDataset ds = dataset(id);
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (ds) {
            List<String[]> rows = new ArrayList<>(ds.records);
            String needle = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
            if (!needle.isEmpty()) {
                rows.removeIf(r -> Arrays.stream(r).noneMatch(c -> c != null && c.toLowerCase(java.util.Locale.ROOT).contains(needle)));
            }
            int col = sort == null ? -1 : ds.header.indexOf(sort);
            if (col >= 0) {
                java.util.Comparator<String[]> cmp = (a, b) -> {
                    String x = col < a.length && a[col] != null ? a[col] : "";
                    String y = col < b.length && b[col] != null ? b[col] : "";
                    double dx = FinalDataLoader.parseNum(x);
                    double dy = FinalDataLoader.parseNum(y);
                    if (!Double.isNaN(dx) && !Double.isNaN(dy)) return Double.compare(dx, dy);
                    if (x.isEmpty() != y.isEmpty()) return x.isEmpty() ? 1 : -1;
                    return x.compareToIgnoreCase(y);
                };
                rows.sort(desc ? cmp.reversed() : cmp);
            }
            int from = Math.max(0, Math.min(offset, rows.size()));
            int to = Math.min(rows.size(), from + Math.max(1, Math.min(limit, 1000)));
            List<String> types = new ArrayList<>();
            for (String h : ds.header) {
                com.analytics.brain.data.FinalColumn c = ds.col(h);
                types.add(c == null || c.type == null ? "TEXT" : c.type.name());
            }
            out.put("header", ds.header);
            out.put("types", types);
            out.put("rows", rows.subList(from, to));
            out.put("total", ds.records.size());
            out.put("filtered", rows.size());
            out.put("offset", from);
        }
        return out;
    }

    @GetMapping("/datasets/{id}/overview")
    public FinalGraphPayload overview(@PathVariable String id) {
        return brain.overview(dataset(id));
    }

    @PostMapping("/datasets/{id}/expand")
    public FinalGraphPayload expand(@PathVariable String id, @RequestBody FinalGraphRef ref) {
        return brain.expand(dataset(id), ref);
    }

    @PostMapping("/datasets/{id}/refresh")
    public Map<String, Object> refresh(@PathVariable String id) throws Exception {
        FinalDataset ds = dataset(id);
        if (ds.sourceUrl == null) throw new ExceptionAnalitikData("У набора нет URL-источника");
        String[] response = FinalDataLoader.fetch(ds.sourceUrl, ds.headers);
        synchronized (ds) {
            FinalDataLoader.parseAuto(ds, response[0], response[1]);
            FinalDataLoader.build(ds);
            ds.version++;
            ds.fetchedAt = System.currentTimeMillis();
        }
        return ds.summary();
    }

    @PostMapping("/datasets/{id}/append")
    public Map<String, Object> append(@PathVariable String id, @RequestBody FinalCreateDatasetRequest r) throws Exception {
        FinalDataset ds = dataset(id);
        synchronized (ds) {
            List<Map<String, String>> rows = new ArrayList<>();
            if (r.data != null) {
                JsonNode array = r.data.isArray() ? r.data : JSON.createArrayNode().add(r.data);
                for (JsonNode item : array) rows.add(FinalJsonParser.flatObject(item));
            } else if (r.csv != null) {
                FinalDataset parsed = new FinalDataset();
                new FinalCsvParser().parse(parsed, r.csv);
                for (String[] record : parsed.records) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 0; i < parsed.header.size(); i++) row.put(parsed.header.get(i), record[i]);
                    rows.add(row);
                }
            }

            for (Map<String, String> row : rows) {
                for (String column : row.keySet()) {
                    if (ds.header.contains(column)) continue;
                    ds.header.add(column);
                    for (int i = 0; i < ds.records.size(); i++) {
                        ds.records.set(i, Arrays.copyOf(ds.records.get(i), ds.header.size()));
                    }
                }
            }
            for (Map<String, String> row : rows) ds.records.add(FinalDataLoader.toRecord(ds.header, row));

            FinalDataLoader.build(ds);
            ds.version++;
            ds.fetchedAt = System.currentTimeMillis();
        }
        return ds.summary();
    }

    @DeleteMapping("/datasets/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        brain.store.remove(id);
        return Map.of("deleted", id);
    }

    @GetMapping(value = "/demo/users")
    public ResponseEntity<String> demo(@RequestParam(defaultValue = "1000") int rows,
                                       @RequestParam(defaultValue = "json") String format,
                                       @RequestParam(defaultValue = "false") boolean live) throws Exception {
        rows = Math.max(20, Math.min(rows, 200000));
        List<Map<String, Object>> data = FinalDemoData.users(rows, live);
        if ("csv".equalsIgnoreCase(format)) {
            MediaType csv = new MediaType("text", "csv", StandardCharsets.UTF_8);
            return ResponseEntity.ok().contentType(csv).body(FinalDemoData.toCsv(data));
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(JSON.writeValueAsString(Map.of("data", data)));
    }

    private static String shortName(String url) {
        String s = url.replaceAll("^https?://", "");
        return s.length() > 48 ? s.substring(0, 48) + "…" : s;
    }
}
