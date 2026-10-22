package com.analytics.brain.service;

import com.analytics.brain.data.EnumColumnType;
import com.analytics.brain.data.FinalColumn;
import com.analytics.brain.data.FinalDataset;
import com.analytics.brain.data.FinalDatasetRoles;
import com.analytics.brain.exception.ExceptionAnalitikData;
import com.analytics.brain.mathematics.FinalMultipleComparisons;
import com.analytics.brain.mathematics.FinalTwoProportionTest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.analytics.brain.text.FinalFormatRu.*;

/**
 * Граф путей: как клиенты ходят по продукту.
 * <p>
 * Из базы событий (кто · когда · какой шаг) собираются визиты: строки сессии упорядочиваются по времени,
 * повторы подряд схлопываются. Узел — шаг, ребро — переход с долей от всех уходов с шага (вместе с выходом = 100 %).
 * Влияние шага на цель: конверсия клиентов, побывавших на шаге, против остальных (z-тест долей, h Коэна, FDR).
 * Сегмент берётся из самой базы событий или из базы сущности через общий ключ (атлас).
 */
@Service
public class ServiceJourney {

    public static final String START = "Вход";
    private static final Pattern GOAL_NAME = Pattern.compile(
            "(?iu)оплат|покупк|purchase|order|checkout|заказ|success|успе|подписк|subscribe|регистрац");
    private static final Pattern SESSION_NAME = Pattern.compile("(?iu)сесси|session|визит|visit");

    private final ServiceBrainGraph brain;

    public ServiceJourney(ServiceBrainGraph brain) {
        this.brain = brain;
    }

    public static final class Request {
        public String dataset;
        public String goal;
        public String segmentColumn;
        public String segmentValue;
        public Double minShare;
        /** Для профиля: шаг, переход (from → to) или выход с шага. */
        public String node;
        public String from;
        public String to;
        public String exitFrom;
    }

    /** Разобранная база событий: визиты, пользователи, атрибуты для сегментов и профиля. */
    private static final class Model {
        FinalDataset ds;
        String goal;
        List<String> steps;
        List<String[]> sessions = new ArrayList<>();
        List<Integer> sessionUser = new ArrayList<>();
        List<String> users = new ArrayList<>();
        Map<String, String[]> attributes = new LinkedHashMap<>();
        Map<String, String> attributeSource = new LinkedHashMap<>();
        String segmentLabel;
    }

    public FinalDataset eventsDataset(String id) {
        if (id != null && !id.isBlank()) {
            FinalDataset ds = brain.store.get(id);
            if (ds == null) throw new ExceptionAnalitikData("База не найдена: " + id);
            if (ds.stepColumn == null || ds.keyColumn == null) {
                throw new ExceptionAnalitikData("В базе «" + ds.name + "» нет колонки шага (страница, экран, событие) "
                        + "и идентификатора клиента — граф путей строится по базе событий.");
            }
            return ds;
        }
        for (FinalDataset ds : brain.workspace(null)) {
            if ("events".equals(ds.role)) return ds;
        }
        throw new ExceptionAnalitikData("В рабочем пространстве нет базы событий. Нужны колонки: ID клиента, время, "
                + "шаг (страница / экран / событие). Попробуйте «Демо-продукт».");
    }

    private Model model(Request r) {
        FinalDataset ds = eventsDataset(r.dataset);
        Model m = new Model();
        m.ds = ds;
        synchronized (ds) {
            String[] user = FinalDatasetRoles.values(ds, ds.keyColumn);
            String sessionColumn = null;
            for (String k : FinalDatasetRoles.keyColumns(ds)) {
                if (!k.equals(ds.keyColumn) && SESSION_NAME.matcher(k).find()) sessionColumn = k;
            }
            String[] session = sessionColumn == null ? user : FinalDatasetRoles.values(ds, sessionColumn);
            String[] time = ds.timeColumn == null ? new String[ds.rows] : FinalDatasetRoles.values(ds, ds.timeColumn);
            FinalColumn step = ds.col(ds.stepColumn);

            // атрибуты клиентов: категории самой базы событий + база сущности через общий ключ
            Map<String, Integer> userIndex = new HashMap<>();
            int[] rowUser = new int[ds.rows];
            for (int i = 0; i < ds.rows; i++) {
                if (user[i] == null) {
                    rowUser[i] = -1;
                    continue;
                }
                Integer u = userIndex.get(user[i]);
                if (u == null) {
                    u = m.users.size();
                    userIndex.put(user[i], u);
                    m.users.add(user[i]);
                }
                rowUser[i] = u;
            }
            int userCount = m.users.size();
            for (FinalColumn c : ds.columns) {
                if (c.type != EnumColumnType.CATEGORICAL || c.name.equals(ds.stepColumn)) continue;
                String[] perUser = new String[userCount];
                for (int i = 0; i < ds.rows; i++) {
                    if (rowUser[i] >= 0 && perUser[rowUser[i]] == null) perUser[rowUser[i]] = c.cat[i];
                }
                m.attributes.put(c.name, perUser);
                m.attributeSource.put(c.name, ds.name);
            }
            FinalWorkspaceLinks.Resolved w = FinalWorkspaceLinks.resolve(brain.workspace(null));
            if (w.entity != null && w.entity != ds && w.linkOf(ds) != null) {
                int[] owner = w.owners(ds);
                int[] userEntity = new int[userCount];
                Arrays.fill(userEntity, -1);
                for (int i = 0; i < ds.rows; i++) {
                    if (rowUser[i] >= 0 && owner[i] >= 0) userEntity[rowUser[i]] = owner[i];
                }
                for (FinalColumn c : w.entity.columns) {
                    if (c.type != EnumColumnType.CATEGORICAL || m.attributes.containsKey(c.name)) continue;
                    String[] perUser = new String[userCount];
                    for (int u = 0; u < userCount; u++) perUser[u] = userEntity[u] >= 0 ? c.cat[userEntity[u]] : null;
                    m.attributes.put(c.name, perUser);
                    m.attributeSource.put(c.name, w.entity.name);
                }
            }

            // фильтр сегмента
            boolean[] keepUser = new boolean[userCount];
            Arrays.fill(keepUser, true);
            if (r.segmentColumn != null && !r.segmentColumn.isBlank() && r.segmentValue != null) {
                String[] attr = m.attributes.get(r.segmentColumn);
                if (attr == null) throw new ExceptionAnalitikData("Нет колонки для сегмента: " + r.segmentColumn);
                for (int u = 0; u < userCount; u++) keepUser[u] = r.segmentValue.equals(attr[u]);
                m.segmentLabel = r.segmentColumn + ": " + r.segmentValue;
            }

            // визиты
            Map<String, List<Integer>> bySession = new LinkedHashMap<>();
            for (int i = 0; i < ds.rows; i++) {
                if (rowUser[i] < 0 || !keepUser[rowUser[i]] || step.cat[i] == null) continue;
                String key = session[i] == null ? user[i] : session[i];
                bySession.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
            for (List<Integer> rows : bySession.values()) {
                rows.sort(Comparator.comparing((Integer i) -> time[i] == null ? "" : time[i]).thenComparingInt(i -> i));
                List<String> seq = new ArrayList<>();
                for (int i : rows) {
                    String s = step.cat[i];
                    if (seq.isEmpty() || !seq.get(seq.size() - 1).equals(s)) seq.add(s);
                }
                m.sessions.add(seq.toArray(new String[0]));
                m.sessionUser.add(rowUser[rows.get(0)]);
            }
            m.steps = new ArrayList<>(step.levels);

            m.goal = r.goal != null && m.steps.contains(r.goal) ? r.goal : null;
            if (m.goal == null && (r.goal == null || r.goal.isBlank())) {
                for (String s : m.steps) {
                    if (GOAL_NAME.matcher(s.toLowerCase(Locale.ROOT)).find()) {
                        m.goal = s;
                        break;
                    }
                }
            }
        }
        if (m.sessions.isEmpty()) throw new ExceptionAnalitikData("В выбранном сегменте нет визитов");
        return m;
    }

    public Map<String, Object> journey(Request r) {
        Model m = model(r);
        double minShare = r.minShare == null ? 0.04 : r.minShare;

        Set<Integer> activeUsers = new HashSet<>(m.sessionUser);
        Set<Integer> goalUsers = new HashSet<>();
        Map<String, Set<Integer>> stepUsers = new LinkedHashMap<>();
        Map<String, Integer> stepSessions = new HashMap<>();
        Map<String, Integer> exits = new HashMap<>();
        Map<String, Integer> outgoing = new HashMap<>();
        Map<String, List<Integer>> firstPos = new HashMap<>();
        Map<String, Integer> transitions = new LinkedHashMap<>();
        Map<String, Set<Integer>> transitionUsers = new HashMap<>();
        Map<String, int[]> sequences = new HashMap<>();

        for (int s = 0; s < m.sessions.size(); s++) {
            String[] seq = m.sessions.get(s);
            int u = m.sessionUser.get(s);
            boolean converted = m.goal != null && Arrays.asList(seq).contains(m.goal);
            if (converted) goalUsers.add(u);

            Set<String> seen = new HashSet<>();
            String prev = START;
            outgoing.merge(START, 1, Integer::sum);
            for (int k = 0; k < seq.length; k++) {
                String st = seq[k];
                if (seen.add(st)) {
                    stepSessions.merge(st, 1, Integer::sum);
                    firstPos.computeIfAbsent(st, x -> new ArrayList<>()).add(k);
                }
                stepUsers.computeIfAbsent(st, x -> new HashSet<>()).add(u);
                String t = prev + "\u0000" + st;
                transitions.merge(t, 1, Integer::sum);
                transitionUsers.computeIfAbsent(t, x -> new HashSet<>()).add(u);
                if (!prev.equals(START)) outgoing.merge(prev, 1, Integer::sum);
                prev = st;
            }
            if (!prev.equals(m.goal)) {
                exits.merge(prev, 1, Integer::sum);
                outgoing.merge(prev, 1, Integer::sum);
            }

            String key = String.join(" → ", Arrays.asList(seq).subList(0, Math.min(seq.length, 7)))
                    + (seq.length > 7 ? " → …" : "");
            int[] agg = sequences.computeIfAbsent(key, x -> new int[2]);
            agg[0]++;
            if (converted) agg[1]++;
        }

        int users = activeUsers.size();
        int sessions = m.sessions.size();

        // влияние шагов на цель
        List<String> tested = new ArrayList<>();
        List<FinalTwoProportionTest> tests = new ArrayList<>();
        if (m.goal != null) {
            for (String st : stepUsers.keySet()) {
                if (st.equals(m.goal)) continue;
                Set<Integer> visitors = stepUsers.get(st);
                int kA = 0;
                for (int u : visitors) {
                    if (goalUsers.contains(u)) kA++;
                }
                int nA = visitors.size();
                int nB = users - nA;
                int kB = goalUsers.size() - kA;
                if (nA < 10 || nB < 10) continue;
                tested.add(st);
                tests.add(new FinalTwoProportionTest(kA, nA, kB, nB));
            }
            FinalMultipleComparisons.applyFdr(tests);
        }

        // позиция шага по горизонтали: среднее место первого появления в визите (вход = 0, цель — справа от всех)
        Map<String, Double> pos = new HashMap<>();
        double maxPos = 1;
        for (Map.Entry<String, List<Integer>> e : firstPos.entrySet()) {
            double p = e.getValue().stream().mapToInt(Integer::intValue).average().orElse(0) + 1;
            pos.put(e.getKey(), p);
            if (!e.getKey().equals(m.goal)) maxPos = Math.max(maxPos, p);
        }
        if (m.goal != null && pos.containsKey(m.goal)) pos.put(m.goal, Math.max(pos.get(m.goal), maxPos + 0.8));

        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("id", START);
        start.put("label", START);
        start.put("kind", "start");
        start.put("pos", 0.0);
        start.put("sessions", sessions);
        start.put("users", users);
        start.put("userShare", 1.0);
        nodes.add(start);

        for (String st : stepUsers.keySet()) {
            int visitors = stepUsers.get(st).size();
            int ss = stepSessions.getOrDefault(st, 0);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", st);
            n.put("label", st);
            n.put("kind", st.equals(m.goal) ? "goal" : "step");
            n.put("pos", pos.getOrDefault(st, 1.0));
            n.put("sessions", ss);
            n.put("users", visitors);
            n.put("userShare", (double) visitors / users);
            n.put("exitRate", st.equals(m.goal) ? 0.0 : (double) exits.getOrDefault(st, 0) / Math.max(1, outgoing.getOrDefault(st, 0)));
            n.put("exits", exits.getOrDefault(st, 0));
            int ti = tested.indexOf(st);
            if (ti >= 0) {
                FinalTwoProportionTest t = tests.get(ti);
                boolean sig = t.significant();
                n.put("conv", t.pA);
                n.put("convRest", t.pB);
                n.put("lift", t.pB > 0 ? t.pA / t.pB : null);
                n.put("required", t.pB == 0 && t.pA > 0);
                n.put("level", sig ? t.level : "none");
                n.put("direction", t.pA >= t.pB ? "positive" : "negative");
                n.put("q", t.q);
                n.put("effect", t.effect);
                n.put("text", "Из побывавших на «" + st + "» до цели «" + m.goal + "» дошли " + pct(t.pA)
                        + ", из остальных — " + pct(t.pB) + " (" + sgnPP(t.pA - t.pB)
                        + (t.pB > 0 ? ", ×" + fmt1(t.pA / t.pB) : "") + "). "
                        + (t.pB == 0 && t.pA > 0 ? "Без этого шага до цели не доходит никто — обязательный шаг. " : "")
                        + "z-тест долей: h Коэна = " + fmt(t.effect)
                        + ", p " + fmtPq(t.p) + ", q " + fmtPq(t.q)
                        + (sig ? "." : " — статистически не значимо."));
            } else {
                n.put("level", "none");
            }
            nodes.add(n);
        }

        List<Map<String, Object>> links = new ArrayList<>();
        for (Map.Entry<String, Integer> e : transitions.entrySet()) {
            String[] ab = e.getKey().split("\u0000");
            int out = outgoing.getOrDefault(ab[0], 0);
            double share = out == 0 ? 0 : (double) e.getValue() / out;
            if (share < minShare || e.getValue() < 5) continue;
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("id", ab[0] + "→" + ab[1]);
            l.put("source", ab[0]);
            l.put("target", ab[1]);
            l.put("count", e.getValue());
            l.put("share", share);
            l.put("users", transitionUsers.get(e.getKey()).size());
            l.put("back", pos.getOrDefault(ab[1], 0.0) < pos.getOrDefault(ab[0], 0.0));
            links.add(l);
        }

        List<Map<String, Object>> pathsAll = topPaths(sequences, sessions, false);
        List<Map<String, Object>> pathsGoal = topPaths(sequences, sessions, true);

        Map<String, Object> segments = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> a : m.attributes.entrySet()) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String v : a.getValue()) {
                if (v != null) counts.merge(v, 1, Integer::sum);
            }
            if (counts.size() < 2 || counts.size() > 15) continue;
            List<String> values = new ArrayList<>(counts.keySet());
            values.sort((x, y) -> counts.get(y) - counts.get(x));
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("source", m.attributeSource.get(a.getKey()));
            s.put("values", values);
            segments.put(a.getKey(), s);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> dsInfo = new LinkedHashMap<>();
        dsInfo.put("id", m.ds.id);
        dsInfo.put("name", m.ds.name);
        dsInfo.put("stepColumn", m.ds.stepColumn);
        dsInfo.put("keyColumn", m.ds.keyColumn);
        dsInfo.put("timeColumn", m.ds.timeColumn);
        out.put("dataset", dsInfo);
        out.put("goal", m.goal);
        out.put("steps", m.steps);
        out.put("segment", m.segmentLabel);
        out.put("segments", segments);
        out.put("users", users);
        out.put("sessions", sessions);
        out.put("goalUsers", goalUsers.size());
        out.put("conversion", users == 0 ? 0 : (double) goalUsers.size() / users);
        out.put("nodes", nodes);
        out.put("links", links);
        out.put("paths", pathsAll);
        out.put("goalPaths", pathsGoal);
        out.put("insights", journeyInsights(nodes, m.goal));
        return out;
    }

    private static String fmt1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v).replace('.', ',');
    }

    private static List<Map<String, Object>> topPaths(Map<String, int[]> sequences, int sessions, boolean converted) {
        List<Map<String, Object>> out = new ArrayList<>();
        sequences.entrySet().stream()
                .filter(e -> !converted || e.getValue()[1] > 0)
                .sorted((a, b) -> converted ? b.getValue()[1] - a.getValue()[1] : b.getValue()[0] - a.getValue()[0])
                .limit(8)
                .forEach(e -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("path", e.getKey());
                    p.put("sessions", e.getValue()[0]);
                    p.put("share", (double) e.getValue()[0] / sessions);
                    p.put("conv", (double) e.getValue()[1] / e.getValue()[0]);
                    out.add(p);
                });
        return out;
    }

    private static List<String> journeyInsights(List<Map<String, Object>> nodes, String goal) {
        List<String> out = new ArrayList<>();
        nodes.stream()
                .filter(n -> n.get("lift") != null && !"none".equals(n.get("level")))
                .sorted(Comparator.comparingDouble(n -> -Math.abs((double) n.get("effect"))))
                .limit(3)
                .forEach(n -> out.add("«" + n.get("label") + "»: дошли до «" + goal + "» " + pct((double) n.get("conv"))
                        + " против " + pct((double) n.get("convRest")) + " у остальных (×" + fmt1((double) n.get("lift")) + ")"));
        nodes.stream()
                .filter(n -> "step".equals(n.get("kind")) && (int) n.get("sessions") >= 20)
                .max(Comparator.comparingDouble(n -> (double) n.get("exitRate")))
                .ifPresent(n -> out.add("Больше всего уходят с «" + n.get("label") + "»: " + pct((double) n.get("exitRate"))
                        + " визитов заканчиваются здесь"));
        return out;
    }

    /** Кто эти люди: профиль клиентов шага / перехода / выхода против остальных клиентов в том же сегменте. */
    public Map<String, Object> profile(Request r) {
        Model m = model(r);
        Set<Integer> universe = new HashSet<>(m.sessionUser);
        Set<Integer> group = new HashSet<>();
        String title;
        for (int s = 0; s < m.sessions.size(); s++) {
            String[] seq = m.sessions.get(s);
            int u = m.sessionUser.get(s);
            if (r.node != null) {
                if (Arrays.asList(seq).contains(r.node)) group.add(u);
            } else if (r.exitFrom != null) {
                if (seq.length > 0 && seq[seq.length - 1].equals(r.exitFrom)) group.add(u);
            } else if (r.from != null && r.to != null) {
                String prev = START;
                for (String st : seq) {
                    if (prev.equals(r.from) && st.equals(r.to)) group.add(u);
                    prev = st;
                }
            }
        }
        if (r.node != null) {
            title = "Были на шаге «" + r.node + "»";
        } else if (r.exitFrom != null) {
            title = "Ушли с шага «" + r.exitFrom + "»";
        } else {
            title = "Перешли «" + r.from + "» → «" + r.to + "»";
        }

        List<Integer> rest = new ArrayList<>();
        for (int u : universe) {
            if (!group.contains(u)) rest.add(u);
        }

        List<Object[]> rows = new ArrayList<>();
        List<FinalTwoProportionTest> tests = new ArrayList<>();
        for (Map.Entry<String, String[]> a : m.attributes.entrySet()) {
            String[] v = a.getValue();
            Map<String, int[]> counts = new LinkedHashMap<>();
            int nA = 0;
            int nB = 0;
            for (int u : group) {
                if (v[u] == null) continue;
                nA++;
                counts.computeIfAbsent(v[u], x -> new int[2])[0]++;
            }
            for (int u : rest) {
                if (v[u] == null) continue;
                nB++;
                counts.computeIfAbsent(v[u], x -> new int[2])[1]++;
            }
            if (counts.size() < 2 || counts.size() > 15 || nA < 10 || nB < 10) continue;
            for (Map.Entry<String, int[]> c : counts.entrySet()) {
                FinalTwoProportionTest t = new FinalTwoProportionTest(c.getValue()[0], nA, c.getValue()[1], nB);
                tests.add(t);
                rows.add(new Object[]{a.getKey(), c.getKey(), t});
            }
        }
        FinalMultipleComparisons.applyFdr(tests);

        List<Map<String, Object>> traits = new ArrayList<>();
        rows.stream()
                .filter(x -> ((FinalTwoProportionTest) x[2]).significant())
                .sorted(Comparator.comparingDouble(x -> -Math.abs(((FinalTwoProportionTest) x[2]).effect)))
                .limit(10)
                .forEach(x -> {
                    FinalTwoProportionTest t = (FinalTwoProportionTest) x[2];
                    Map<String, Object> tr = new LinkedHashMap<>();
                    tr.put("column", x[0]);
                    tr.put("value", x[1]);
                    tr.put("source", m.attributeSource.get((String) x[0]));
                    tr.put("share", t.pA);
                    tr.put("shareRest", t.pB);
                    tr.put("ratio", t.pB > 0 ? t.pA / t.pB : null);
                    tr.put("level", t.level);
                    tr.put("direction", t.pA >= t.pB ? "over" : "under");
                    tr.put("q", t.q);
                    tr.put("text", x[0] + " = «" + x[1] + "»: " + pct(t.pA) + " в группе против " + pct(t.pB)
                            + " у остальных (" + sgnPP(t.pA - t.pB) + ", q " + fmtPq(t.q) + ")");
                    traits.add(tr);
                });

        int goalInGroup = 0;
        int goalInRest = 0;
        if (m.goal != null) {
            Set<Integer> goalUsers = new HashSet<>();
            for (int s = 0; s < m.sessions.size(); s++) {
                if (Arrays.asList(m.sessions.get(s)).contains(m.goal)) goalUsers.add(m.sessionUser.get(s));
            }
            for (int u : group) {
                if (goalUsers.contains(u)) goalInGroup++;
            }
            for (int u : rest) {
                if (goalUsers.contains(u)) goalInRest++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", title);
        out.put("segment", m.segmentLabel);
        out.put("size", group.size());
        out.put("share", universe.isEmpty() ? 0 : (double) group.size() / universe.size());
        out.put("goal", m.goal);
        out.put("conv", group.isEmpty() ? null : (double) goalInGroup / group.size());
        out.put("convRest", rest.isEmpty() ? null : (double) goalInRest / rest.size());
        out.put("traits", traits);
        out.put("tested", tests.size());
        List<String> sample = new ArrayList<>();
        group.stream().limit(12).forEach(u -> sample.add(m.users.get(u)));
        out.put("sample", sample);
        return out;
    }
}
