package com.analytics.brain.service;

import com.analytics.brain.data.FinalDataLoader;
import com.analytics.brain.data.FinalDataset;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Рабочее пространство на диске: каждая база — файл {@code workspace/<id>.json.gz} (метаданные + сырые строки).
 * Сохранение фоновое: раз в несколько секунд записываются базы, у которых сменилась версия, —
 * поэтому точки изменения данных (создание, дозапись, новые колонки, срезы ИИ) трогать не нужно.
 */
@Service
public class ServiceWorkspaceStorage {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceWorkspaceStorage.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ServiceBrainGraph brain;
    private final Path dir;
    private final Map<String, Integer> savedVersion = new ConcurrentHashMap<>();

    public ServiceWorkspaceStorage(ServiceBrainGraph brain) {
        this.brain = brain;
        this.dir = Paths.get(System.getProperty("analitik.workspace",
                System.getenv().getOrDefault("ANALITIK_WORKSPACE", "workspace"))).toAbsolutePath();
    }

    @PostConstruct
    public void load() {
        if (!Files.isDirectory(dir)) return;
        int loaded = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json.gz")) {
            for (Path file : files) {
                try {
                    FinalDataset ds = read(file);
                    brain.store.put(ds.id, ds);
                    savedVersion.put(ds.id, ds.version);
                    loaded++;
                } catch (Exception e) {
                    LOG.warn("Не удалось прочитать {}: {}", file.getFileName(), e.toString());
                }
            }
        } catch (Exception e) {
            LOG.warn("Рабочее пространство недоступно: {}", e.toString());
        }
        if (loaded > 0) LOG.info("Рабочее пространство: загружено баз — {} ({})", loaded, dir);
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void flush() {
        for (FinalDataset ds : brain.store.values()) {
            Integer saved = savedVersion.get(ds.id);
            if (saved != null && saved == ds.version) continue;
            try {
                write(ds);
            } catch (Exception e) {
                LOG.warn("Не удалось сохранить базу {}: {}", ds.id, e.toString());
            }
        }
        Set<String> gone = new HashSet<>(savedVersion.keySet());
        gone.removeAll(brain.store.keySet());
        for (String id : gone) {
            savedVersion.remove(id);
            try {
                Files.deleteIfExists(file(id));
            } catch (Exception e) {
                LOG.warn("Не удалось удалить файл базы {}: {}", id, e.toString());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        flush();
    }

    private Path file(String id) {
        return dir.resolve(id.replaceAll("[^A-Za-z0-9_-]", "_") + ".json.gz");
    }

    private void write(FinalDataset ds) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        int version;
        synchronized (ds) {
            version = ds.version;
            m.put("id", ds.id);
            m.put("name", ds.name);
            m.put("sourceType", ds.sourceType);
            m.put("sourceUrl", ds.sourceUrl);
            m.put("query", ds.query);
            m.put("parents", new ArrayList<>(ds.parents));
            m.put("parentVersions", new LinkedHashMap<>(ds.parentVersions));
            m.put("recipe", ds.recipe);
            m.put("recipePart", ds.recipePart);
            m.put("source", ds.source);
            m.put("headers", new LinkedHashMap<>(ds.headers));
            m.put("fetchedAt", ds.fetchedAt);
            m.put("version", ds.version);
            m.put("header", new ArrayList<>(ds.header));
            m.put("records", new ArrayList<>(ds.records));
            m.put("derivedFrom", new LinkedHashMap<>(ds.derivedFrom));
        }
        Files.createDirectories(dir);
        Path tmp = dir.resolve(file(ds.id).getFileName() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
            JSON.writeValue(out, m);
        }
        Files.move(tmp, file(ds.id), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        savedVersion.put(ds.id, version);
    }

    private static FinalDataset read(Path file) throws Exception {
        Map<String, Object> m;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            m = JSON.readValue(in, new TypeReference<Map<String, Object>>() {
            });
        }
        FinalDataset ds = new FinalDataset();
        ds.id = (String) m.get("id");
        ds.name = (String) m.get("name");
        ds.sourceType = (String) m.get("sourceType");
        ds.sourceUrl = (String) m.get("sourceUrl");
        ds.query = (String) m.get("query");
        ds.recipe = (String) m.get("recipe");
        ds.recipePart = (String) m.get("recipePart");
        ds.source = Boolean.TRUE.equals(m.get("source"));
        @SuppressWarnings("unchecked")
        List<String> parents = (List<String>) m.getOrDefault("parents", List.of());
        ds.parents = new ArrayList<>(parents);
        @SuppressWarnings("unchecked")
        Map<String, Number> pv = (Map<String, Number>) m.getOrDefault("parentVersions", Map.of());
        pv.forEach((k, v) -> ds.parentVersions.put(k, v.intValue()));
        ds.fetchedAt = ((Number) m.getOrDefault("fetchedAt", 0L)).longValue();
        ds.version = ((Number) m.getOrDefault("version", 1)).intValue();

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) m.getOrDefault("headers", Map.of());
        ds.headers.putAll(headers);
        @SuppressWarnings("unchecked")
        List<String> header = (List<String>) m.get("header");
        ds.header = new ArrayList<>(header);
        @SuppressWarnings("unchecked")
        List<List<String>> records = (List<List<String>>) m.get("records");
        ds.records = new ArrayList<>(records.size());
        for (List<String> r : records) ds.records.add(r.toArray(new String[0]));
        @SuppressWarnings("unchecked")
        Map<String, List<String>> derived = (Map<String, List<String>>) m.getOrDefault("derivedFrom", Map.of());
        derived.forEach((k, v) -> ds.derivedFrom.put(k, new HashSet<>(v)));

        FinalDataLoader.build(ds);
        return ds;
    }
}
