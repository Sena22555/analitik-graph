package com.analytics.brain.service;

import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalDatasetRoles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Связи между базами рабочего пространства через общий ключ.
 * <p>
 * Ключ сущности — колонка-идентификатор, уникальная в своей базе (например «ID пользователя» в «Пользователях»).
 * Другая база с ней связана, если её колонка-идентификатор хотя бы на 30 % состоит из значений этого ключа.
 * Сущность рабочего пространства — база, на которую ссылается больше всего других баз.
 */
public final class FinalWorkspaceLinks {

    static final double MIN_OVERLAP = 0.3;

    private FinalWorkspaceLinks() {
    }

    public static final class Link {
        public FinalDataset entity;
        public String entityKey;
        public FinalDataset other;
        public String otherKey;
        public double overlap;
    }

    public static final class Resolved {
        public FinalDataset entity;
        public String entityKey;
        public List<Link> links = new ArrayList<>();
        public List<FinalDataset> unlinked = new ArrayList<>();
        /** Значение ключа → номер строки в базе сущности. */
        public Map<String, Integer> index = new HashMap<>();

        public Link linkOf(FinalDataset ds) {
            for (Link l : links) {
                if (l.other == ds) return l;
            }
            return null;
        }

        /** Для каждой строки связанной базы — строка сущности (или −1). */
        public int[] owners(FinalDataset ds) {
            int[] owner = new int[ds.rows];
            if (ds == entity) {
                for (int i = 0; i < owner.length; i++) owner[i] = i;
                return owner;
            }
            Link l = linkOf(ds);
            String[] keys = l == null ? new String[ds.rows] : FinalDatasetRoles.values(ds, l.otherKey);
            for (int i = 0; i < owner.length; i++) {
                Integer row = keys[i] == null ? null : index.get(keys[i]);
                owner[i] = row == null ? -1 : row;
            }
            return owner;
        }
    }

    public static Resolved resolve(List<FinalDataset> all) {
        Map<FinalDataset, Map<String, Set<String>>> distinct = new LinkedHashMap<>();
        Map<FinalDataset, Set<String>> uniqueKeys = new HashMap<>();
        for (FinalDataset ds : all) {
            Map<String, Set<String>> perKey = new LinkedHashMap<>();
            Set<String> unique = new HashSet<>();
            for (String k : FinalDatasetRoles.keyColumns(ds)) {
                String[] v = FinalDatasetRoles.values(ds, k);
                Set<String> set = new HashSet<>();
                for (String x : v) {
                    if (x != null) set.add(x);
                }
                perKey.put(k, set);
                if (FinalDatasetRoles.unique(v)) unique.add(k);
            }
            distinct.put(ds, perKey);
            uniqueKeys.put(ds, unique);
        }

        Map<FinalDataset, List<Link>> byEntity = new LinkedHashMap<>();
        for (FinalDataset x : all) {
            for (String kx : uniqueKeys.get(x)) {
                Set<String> entityValues = distinct.get(x).get(kx);
                for (FinalDataset y : all) {
                    if (y == x) continue;
                    Link best = null;
                    double bestScore = 0;
                    for (Map.Entry<String, Set<String>> ky : distinct.get(y).entrySet()) {
                        Set<String> values = ky.getValue();
                        if (values.isEmpty()) continue;
                        int hit = 0;
                        for (String v : values) {
                            if (entityValues.contains(v)) hit++;
                        }
                        double overlap = (double) hit / values.size();
                        if (overlap < MIN_OVERLAP) continue;
                        double score = overlap + (normalize(ky.getKey()).equals(normalize(kx)) ? 0.2 : 0);
                        if (score > bestScore) {
                            bestScore = score;
                            best = new Link();
                            best.entity = x;
                            best.entityKey = kx;
                            best.other = y;
                            best.otherKey = ky.getKey();
                            best.overlap = overlap;
                        }
                    }
                    if (best != null) {
                        List<Link> list = byEntity.computeIfAbsent(x, k -> new ArrayList<>());
                        list.removeIf(l -> l.other == y && l.entityKey.equals(kx));
                        list.add(best);
                    }
                }
            }
        }

        Resolved r = new Resolved();
        FinalDataset bestEntity = null;
        String bestKey = null;
        double bestScore = -1;
        for (Map.Entry<FinalDataset, List<Link>> e : byEntity.entrySet()) {
            Map<String, List<Link>> perKey = new LinkedHashMap<>();
            for (Link l : e.getValue()) perKey.computeIfAbsent(l.entityKey, k -> new ArrayList<>()).add(l);
            for (Map.Entry<String, List<Link>> k : perKey.entrySet()) {
                double overlapSum = k.getValue().stream().mapToDouble(l -> l.overlap).sum();
                double score = k.getValue().size() * 10 + overlapSum - e.getKey().rows / 1e9;
                if (score > bestScore) {
                    bestScore = score;
                    bestEntity = e.getKey();
                    bestKey = k.getKey();
                }
            }
        }
        if (bestEntity == null) {
            r.unlinked.addAll(all);
            return r;
        }

        r.entity = bestEntity;
        r.entityKey = bestKey;
        for (Link l : byEntity.get(bestEntity)) {
            if (Objects.equals(l.entityKey, bestKey)) r.links.add(l);
        }
        for (FinalDataset ds : all) {
            if (ds != bestEntity && r.linkOf(ds) == null) r.unlinked.add(ds);
        }
        String[] keys = FinalDatasetRoles.values(bestEntity, bestKey);
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != null) r.index.putIfAbsent(keys[i], i);
        }
        return r;
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
