package com.analytics.brain.service;

import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.structure.ServiceStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * Живой конвейер: производные таблицы (из SQL, из сортировки сырых данных) пересчитываются сами,
 * когда меняется любая из таблиц, из которых они получены. Каскадом — изменения доходят до самого низа графа.
 */
@Service
public class ServiceLineage {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceLineage.class);

    private final ServiceBrainGraph brain;
    private final ServiceSql sql;
    private final ServiceStructure structure;

    public ServiceLineage(ServiceBrainGraph brain, ServiceSql sql, ServiceStructure structure) {
        this.brain = brain;
        this.sql = sql;
        this.structure = structure;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 4000)
    public void tick() {
        // несколько проходов: пересчёт родителя может сделать устаревшим ребёнка (каскад)
        for (int pass = 0; pass < 4; pass++) {
            if (!propagate()) break;
        }
    }

    /** Один проход: пересчитать всё, что устарело. Возвращает true, если что-то пересчитано. */
    public boolean propagate() {
        boolean changed = false;
        Set<String> refreshedSources = new HashSet<>();
        for (FinalDataset ds : brain.allTables()) {
            if (ds.recipe == null || ds.parents.isEmpty() || !stale(ds)) continue;
            try {
                if ("sql".equals(ds.recipe) && ds.query != null) {
                    synchronized (ds) {
                        sql.refill(ds);
                        ds.version++;
                        ds.fetchedAt = System.currentTimeMillis();
                    }
                    changed = true;
                } else if ("structure".equals(ds.recipe)) {
                    String rawId = ds.parents.get(0);
                    FinalDataset raw = brain.store.get(rawId);
                    if (raw != null && refreshedSources.add(rawId)) {
                        structure.refresh(raw);
                        changed = true;
                    }
                }
            } catch (RuntimeException e) {
                // запрос сломался (например, удалили колонку) — запоминаем версии, чтобы не повторять ошибку каждые 2 секунды
                LOG.warn("Не удалось пересчитать «{}»: {}", ds.name, e.getMessage());
                for (String p : ds.parents) {
                    FinalDataset parent = brain.store.get(p);
                    if (parent != null) ds.parentVersions.put(p, parent.version);
                }
            }
        }
        return changed;
    }

    private boolean stale(FinalDataset ds) {
        for (String p : ds.parents) {
            FinalDataset parent = brain.store.get(p);
            if (parent == null) continue;
            Integer seen = ds.parentVersions.get(p);
            if (seen == null || seen != parent.version) return true;
        }
        return false;
    }
}
