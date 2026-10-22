package com.analytics.brain.structure;

import com.analytics.brain.data.FinalDataLoader;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.exception.ExceptionAnalitikData;
import com.analytics.brain.service.ServiceBrainGraph;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Сортировка сырых таблиц: план (с кэшем по версии), применение и пересчёт при изменении источника. */
@Service
public class ServiceStructure {

    private final ServiceBrainGraph brain;
    private final Map<String, FinalAutoStructure.Plan> cache = new HashMap<>();

    public ServiceStructure(ServiceBrainGraph brain) {
        this.brain = brain;
    }

    public synchronized FinalAutoStructure.Plan plan(FinalDataset ds) {
        FinalAutoStructure.Plan p = cache.get(ds.id);
        if (p != null && p.sourceVersion == ds.version) return p;
        synchronized (ds) {
            p = FinalAutoStructure.plan(ds);
        }
        cache.put(ds.id, p);
        return p;
    }

    /** Можно ли разложить таблицу (для подсказки на графе) — без тяжёлых расчётов для производных таблиц. */
    public boolean splittable(FinalDataset ds) {
        if (ds.source || ds.recipe != null || ds.rows < 30) return false;
        try {
            return plan(ds).splittable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Разложить: создать таблицы по плану; исходная становится сырым источником (в анализ больше не идёт). */
    public synchronized List<FinalDataset> apply(FinalDataset raw) {
        if (raw.source) throw new ExceptionAnalitikData("Таблица «" + raw.name + "» уже разложена");
        FinalAutoStructure.Plan p = plan(raw);
        // источник помечается сырым до создания детей: версия меняется, данные — нет, план остаётся тем же
        raw.source = true;
        raw.version++;
        p.sourceVersion = raw.version;
        List<FinalDataset> created = new ArrayList<>();
        long now = System.currentTimeMillis();
        int order = 0;
        for (FinalAutoStructure.Table t : p.tables) {
            FinalDataset ds = new FinalDataset();
            ds.id = UUID.randomUUID().toString().substring(0, 8);
            ds.name = uniqueName(t.name, raw.name);
            ds.sourceType = "derived";
            ds.fetchedAt = now + order++;
            ds.recipe = "structure";
            ds.recipePart = t.part;
            ds.parents.add(raw.id);
            ds.parentVersions.put(raw.id, raw.version);
            fill(ds, p, t);
            brain.store.put(ds.id, ds);
            created.add(ds);
        }
        return created;
    }

    /** Источник изменился (дописали строки, обновили по API) — пересобрать все его разложенные таблицы. */
    public synchronized void refresh(FinalDataset raw) {
        FinalAutoStructure.Plan p = plan(raw);
        for (FinalDataset child : brain.store.values()) {
            if (!"structure".equals(child.recipe) || !child.parents.contains(raw.id)) continue;
            for (FinalAutoStructure.Table t : p.tables) {
                if (!t.part.equals(child.recipePart)) continue;
                synchronized (child) {
                    fill(child, p, t);
                    child.parentVersions.put(raw.id, raw.version);
                    child.version++;
                    child.fetchedAt = System.currentTimeMillis();
                }
            }
        }
    }

    private static void fill(FinalDataset ds, FinalAutoStructure.Plan p, FinalAutoStructure.Table t) {
        ds.header = new ArrayList<>(t.columns);
        ds.records = FinalAutoStructure.records(p, t);
        FinalDataLoader.build(ds);
    }

    private String uniqueName(String wanted, String rawName) {
        if (!exists(wanted)) return wanted;
        String alt = wanted + " (из «" + rawName + "»)";
        String name = alt;
        for (int k = 2; exists(name); k++) name = alt + " " + k;
        return name;
    }

    private boolean exists(String name) {
        return brain.store.values().stream().anyMatch(d -> name.equalsIgnoreCase(d.name));
    }
}
