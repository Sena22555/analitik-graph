package com.analytics.brain.api;

import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.exception.ExceptionAnalitikData;
import com.analytics.brain.service.ServiceBrainGraph;
import com.analytics.brain.service.ServiceCompose;
import com.analytics.brain.service.ServiceSql;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** SQL-консоль: запросы по всем таблицам (DuckDB) и сохранение результата как новой таблицы графа. */
@RestController
@RequestMapping("/api/v2/sql")
public class ControllerSqlApi extends AbstractApiController {

    private final ServiceSql sql;
    private final ServiceCompose compose;

    public ControllerSqlApi(ServiceBrainGraph brain, ServiceSql sql, ServiceCompose compose) {
        super(brain);
        this.sql = sql;
        this.compose = compose;
    }

    /** «Собрать из графа»: разрезы и показатели из разных таблиц → честный отчёт без раздувания сумм. */
    @PostMapping("/compose")
    public Map<String, Object> compose(@RequestBody ServiceCompose.Request r) throws Exception {
        return compose.compose(r);
    }

    @Override
    protected String notFoundHint() {
        return "";
    }

    @Override
    protected String unknownErrorText(Exception e) {
        return e.getClass().getSimpleName();
    }

    @GetMapping("/schema")
    public Map<String, Object> schema() throws Exception {
        return sql.schema();
    }

    @PostMapping("/query")
    public Map<String, Object> query(@RequestBody Map<String, Object> body) {
        int limit = body.get("limit") instanceof Number ? ((Number) body.get("limit")).intValue() : 1000;
        return sql.query((String) body.get("sql"), Math.max(1, Math.min(limit, ServiceSql.MAX_ROWS)));
    }

    /** Результат запроса → новая таблица рабочего пространства (появляется на графе, обновляется тем же запросом). */
    @PostMapping("/save")
    public Map<String, Object> save(@RequestBody Map<String, String> body) {
        String name = body.get("name") == null || body.get("name").isBlank() ? "Результат SQL" : body.get("name").trim();
        FinalDataset ds = new FinalDataset();
        ds.id = UUID.randomUUID().toString().substring(0, 8);
        ds.name = name;
        ds.sourceType = "sql";
        ds.query = body.get("sql");
        ds.fetchedAt = System.currentTimeMillis();
        fill(ds);
        brain.store.put(ds.id, ds);
        return ds.summary();
    }

    /** Пересчитать таблицу из SQL по её запросу (исходные таблицы могли измениться). */
    @PostMapping("/refresh/{id}")
    public Map<String, Object> refresh(@PathVariable String id) {
        FinalDataset ds = dataset(id);
        if (ds.query == null) throw new ExceptionAnalitikData("Таблица построена не из SQL");
        synchronized (ds) {
            fill(ds);
            ds.version++;
            ds.fetchedAt = System.currentTimeMillis();
        }
        return ds.summary();
    }

    private void fill(FinalDataset ds) {
        sql.refill(ds);
    }
}
