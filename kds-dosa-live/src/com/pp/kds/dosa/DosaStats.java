package com.pp.kds.dosa;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java Dosa wait-time engine (no Android dependencies, so it can be unit tested on a JVM).
 *
 * Only the tiny daily record is persisted: completed count, total prep time, recent completion
 * timestamps (last 15 min), the KOT ids already counted today and a 7-day history line per day.
 * Active KOTs are rebuilt from the live board on every snapshot, never stored.
 */
public final class DosaStats {

    /** One Dine-In KOT on the board that contains at least one Dosa item. */
    public static final class Entry {
        final long id;
        final long createdMs;   // <= 0 when the created time could not be parsed
        final boolean ready;    // card has become Food Ready / dispatched
        final boolean cancelled;
        final String[] names;   // Dosa item names on this KOT
        final double[] qty;     // matching quantities

        public Entry(long id, long createdMs, boolean ready, boolean cancelled) {
            this(id, createdMs, ready, cancelled, new String[0], new double[0]);
        }

        public Entry(long id, long createdMs, boolean ready, boolean cancelled, String[] names, double[] qty) {
            this.id = id;
            this.createdMs = createdMs;
            this.ready = ready;
            this.cancelled = cancelled;
            this.names = names;
            this.qty = qty;
        }

        double pieces() {
            double p = 0;
            for (double q : qty) p += q;
            return p;
        }
    }

    public static final class Result {
        public int activeCount;
        public int completedToday;
        public double avgPrepMin;       // NaN when nothing completed yet today
        public double throughputPerMin; // completed Dosa KOTs per minute, recent window
        public int recentCompletions;
        public double windowMin;
        public double queueMin;         // active / throughput, NaN when throughput is 0
        public double etaMin;           // precise internal value
        public String etaLabel;         // customer-facing range, e.g. "15–18 min"
        public double oldestActiveMin;
        public double peakThroughputPerMin;
        public int dosasToday;           // Dine-In Dosa pieces ordered today
        public int runningDosas;         // Dosa pieces on KOTs still preparing
        public int kindsToday;
        public int servedToday;          // Dosa pieces on completed KOTs today
        public long[] trendTimes = new long[0];
        public float[] trendEta = new float[0];
        public double fastestPrepMin;    // NaN when none
        public List<String> facts = new ArrayList<String>();
        public long updatedAt;
        public List<String> history = new ArrayList<String>();
    }

    static final long MINUTE = 60_000L;
    static final long WINDOW_MS = 15 * MINUTE;      // recent-throughput window
    static final long MIN_WINDOW_MS = 3 * MINUTE;   // avoid huge rates right after start
    static final long MAX_PREP_MS = 150 * MINUTE;   // ignore forgotten tickets as outliers
    static final double DEFAULT_AVG_MIN = 12.0;     // until the first Dosa KOT completes today
    static final double MAX_ETA_MIN = 90.0;
    static final int CONFIDENT_RECENT = 6;          // recent completions for full queue weighting
    static final int HISTORY_DAYS = 7;
    static final long TREND_STEP_MS = 2 * MINUTE;   // one trend point every 2 min
    static final long TREND_SPAN_MS = 180 * MINUTE; // chart shows the last 3 hours

    private String dateKey;
    private int completed;
    private long totalPrepMs;
    private long trackingSince;
    private double peakRate;
    private final Set<Long> countedIds = new HashSet<Long>();
    private final LinkedList<Long> recent = new LinkedList<Long>();
    private final LinkedList<String> history = new LinkedList<String>();
    private final Map<Long, Long> active = new HashMap<Long, Long>(); // id -> createdMs
    private final Set<Long> seenToday = new HashSet<Long>();          // KOTs already in the totals
    private final Map<String, Integer> kinds = new HashMap<String, Integer>(); // dosa name -> pieces
    private final int[] hourly = new int[24];
    private final Map<Long, Integer> activePieces = new HashMap<Long, Integer>();
    private long fastestMs;
    private int runningPieces;
    private int servedPieces;
    private final LinkedList<long[]> trend = new LinkedList<long[]>(); // {time, eta*10}

    // ---- snapshot processing -------------------------------------------------------------

    /**
     * Feed the current board. {@code capPressure} is true when the board is at the 300-order cap,
     * in which case a KOT vanishing from the board is not treated as completed.
     * Returns true when anything visible changed.
     */
    public boolean update(List<Entry> snapshot, long now, boolean capPressure) {
        boolean changed = rollover(now);
        if (trackingSince <= 0) {
            trackingSince = now;
            changed = true;
        }
        Set<Long> seen = new HashSet<Long>();
        int running = 0;
        long dayStart = startOfDay(now);
        for (Entry e : snapshot) {
            seen.add(e.id);
            if (!e.cancelled && !e.ready) running += (int) Math.round(e.pieces());
            if (!e.cancelled && (e.createdMs <= 0 || e.createdMs >= dayStart) && seenToday.add(e.id)) {
                for (int i = 0; i < e.names.length; i++) {
                    int q = (int) Math.max(1, Math.round(e.qty[i]));
                    Integer old = kinds.get(e.names[i]);
                    kinds.put(e.names[i], (old == null ? 0 : old) + q);
                    Calendar cal = Calendar.getInstance();
                    cal.setTimeInMillis(e.createdMs > 0 ? e.createdMs : now);
                    hourly[cal.get(Calendar.HOUR_OF_DAY)] += q;
                }
                changed = true;
            }
            if (e.cancelled) {
                if (active.remove(e.id) != null) changed = true;
            } else if (e.ready) {
                Long created = active.remove(e.id);
                if (created != null) {
                    record(e.id, e.createdMs > 0 ? e.createdMs : created, now, pieces(e));
                    changed = true;
                }
            } else {
                activePieces.put(e.id, pieces(e));
                Long prev = active.put(e.id, e.createdMs);
                if (prev == null) changed = true;
            }
        }
        if (running != runningPieces) {
            runningPieces = running;
            changed = true;
        }
        Iterator<Map.Entry<Long, Long>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> a = it.next();
            if (!seen.contains(a.getKey())) {
                // Left the board while preparing: normally it was readied & cleared in one step.
                Integer pc = activePieces.get(a.getKey());
                if (!capPressure) record(a.getKey(), a.getValue(), now, pc == null ? 1 : pc);
                it.remove();
                changed = true;
            }
        }
        activePieces.keySet().retainAll(active.keySet());
        return changed;
    }

    private static int pieces(Entry e) {
        return e.names.length == 0 ? 1 : (int) Math.max(1, Math.round(e.pieces()));
    }

    private void record(long id, long createdMs, long now, int pieces) {
        if (!countedIds.add(id)) return;
        servedPieces += pieces;
        long prep = now - createdMs;
        if (createdMs <= 0 || prep < 0 || prep > MAX_PREP_MS) return; // outlier / unknown
        completed++;
        totalPrepMs += prep;
        if (fastestMs <= 0 || prep < fastestMs) fastestMs = prep;
        recent.add(now);
    }

    private boolean rollover(long now) {
        String today = dayKey(now);
        if (today.equals(dateKey)) return false;
        if (dateKey != null && completed > 0) {
            history.addFirst(historyLine(dateKey));
            while (history.size() > HISTORY_DAYS) history.removeLast();
        }
        dateKey = today;
        completed = 0;
        totalPrepMs = 0;
        peakRate = 0;
        trackingSince = now;
        countedIds.clear();
        recent.clear();
        seenToday.clear();
        kinds.clear();
        java.util.Arrays.fill(hourly, 0);
        fastestMs = 0;
        servedPieces = 0;
        trend.clear();
        return true;
    }

    // ---- estimate ------------------------------------------------------------------------

    public Result compute(long now) {
        rollover(now);
        while (!recent.isEmpty() && recent.getFirst() < now - WINDOW_MS) recent.removeFirst();

        Result r = new Result();
        r.updatedAt = now;
        r.activeCount = active.size();
        r.completedToday = completed;
        r.avgPrepMin = completed > 0 ? (totalPrepMs / (double) completed) / MINUTE : Double.NaN;

        long observed = now - Math.max(trackingSince, startOfDay(now));
        long window = Math.max(MIN_WINDOW_MS, Math.min(WINDOW_MS, observed));
        r.windowMin = window / (double) MINUTE;
        r.recentCompletions = recent.size();
        r.throughputPerMin = recent.size() / r.windowMin;
        if (observed >= 10 * MINUTE && r.throughputPerMin > peakRate) peakRate = r.throughputPerMin;
        r.peakThroughputPerMin = peakRate;

        double oldest = 0;
        for (Long c : active.values()) {
            if (c != null && c > 0) oldest = Math.max(oldest, (now - c) / (double) MINUTE);
        }
        r.oldestActiveMin = oldest;

        double avg = Double.isNaN(r.avgPrepMin) ? DEFAULT_AVG_MIN : r.avgPrepMin;
        double eta;
        r.queueMin = r.throughputPerMin > 0 ? r.activeCount / r.throughputPerMin : Double.NaN;
        if (r.activeCount == 0) {
            eta = avg;                                     // empty queue: just the prep time
        } else if (r.throughputPerMin <= 0) {
            eta = Math.max(avg, Math.min(oldest, MAX_ETA_MIN)); // nothing finishing: stalled
        } else {
            // Queue component (Little's law) can't beat the physical cook time.
            double core = Math.max(r.queueMin, 0.6 * avg);
            double w = Math.min(1.0, r.recentCompletions / (double) CONFIDENT_RECENT);
            eta = (1 - w) * avg + w * core;
        }
        r.etaMin = Math.min(eta, MAX_ETA_MIN);
        r.etaLabel = friendlyRange(r.etaMin);

        int total = 0;
        for (int q : kinds.values()) total += q;
        r.dosasToday = total;
        r.runningDosas = runningPieces;
        r.kindsToday = kinds.size();
        r.fastestPrepMin = fastestMs > 0 ? fastestMs / (double) MINUTE : Double.NaN;
        r.servedToday = servedPieces;
        if (trend.isEmpty() || now - trend.getLast()[0] >= TREND_STEP_MS) {
            trend.add(new long[]{now, Math.round(r.etaMin * 10)});
        } else {
            trend.getLast()[1] = Math.round(r.etaMin * 10); // keep the latest point live
        }
        while (!trend.isEmpty() && trend.getFirst()[0] < now - TREND_SPAN_MS) trend.removeFirst();
        r.trendTimes = new long[trend.size()];
        r.trendEta = new float[trend.size()];
        for (int i = 0; i < trend.size(); i++) {
            r.trendTimes[i] = trend.get(i)[0];
            r.trendEta[i] = trend.get(i)[1] / 10f;
        }
        r.facts = facts(r);

        r.history.add(historyLine(dateKey));
        r.history.addAll(history);
        return r;
    }

    static final String[] TRIVIA = {
            // Babai Tiffins menu stories (owner-provided, trimmed for the screen; no recipe specifics)
            "Our Ghee Idli melts in your mouth thanks to a light, slow-fermented urad dal batter",
            "Our signature idli is layered three ways: warm ghee, Karam Podi and a dollop of white butter",
            "Ghee Pesarattu Upma is two dishes in one \u2014 a crisp green-gram crepe filled with soft rava upma",
            "The red layer in our Ghee Karam Dosa is roasted chilli-garlic karam, balanced by ghee on a hot tawa",
            "Our Karam Podi is a house blend of dry-roasted dals, sesame and spices \u2014 not just chilli powder",
            "Set Dosa is thick and porous \u2014 tiny air pockets keep it soft and spongy",
            "Pesarattu, the green-gram dosa, is a beloved Andhra breakfast classic",
            "Andhra food is famous for bold, spicy flavours \u2014 Guntur chillies are known worldwide",
            "Team Babai Tiffins is on the tawa right now, making your dosa",
            "The thinner the spread on a hot tawa, the crispier the dosa",
            "Babai Tiffins \u2014 taste the Andhra style",
    };

    /**
     * Customer-facing facts. Deliberately avoids business-sensitive figures (no shares,
     * ingredient usage, sales or staff data) - only light, fun, guest-friendly numbers.
     */
    List<String> facts(Result r) {
        List<String> f = new ArrayList<String>();
        List<Map.Entry<String, Integer>> top = new ArrayList<Map.Entry<String, Integer>>(kinds.entrySet());
        java.util.Collections.sort(top, new java.util.Comparator<Map.Entry<String, Integer>>() {
            @Override public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return b.getValue() - a.getValue();
            }
        });
        if (!top.isEmpty()) f.add(top.get(0).getKey() + " is today's favourite at Babai Tiffins");
        if (top.size() >= 2) f.add(top.get(1).getKey() + " is today's runner-up \u2014 a close race!");
        if (r.servedToday > 0) {
            f.add(r.servedToday + (r.servedToday == 1 ? " happy plate" : " happy plates") + " of dosa served so far today");
        }
        int bestHour = -1;
        for (int h = 0; h < 24; h++) if (hourly[h] > 0 && (bestHour < 0 || hourly[h] > hourly[bestHour])) bestHour = h;
        if (bestHour >= 0 && r.dosasToday >= 5) f.add("Dosa rush hour today: " + hourLabel(bestHour));
        if (!Double.isNaN(r.fastestPrepMin)) {
            f.add(String.format(Locale.US, "Today's fastest dosa order was ready in just %d min",
                    Math.max(1, Math.round(r.fastestPrepMin))));
        }
        if (r.throughputPerMin > 0) {
            long sec = Math.round(60 / r.throughputPerMin);
            f.add(sec < 100
                    ? "Right now a fresh dosa order leaves our tawa every " + sec + " seconds"
                    : "Right now a fresh dosa order leaves our tawa every " + Math.round(sec / 60.0) + " min");
        }
        if (r.runningDosas > 0) {
            f.add(r.runningDosas + (r.runningDosas == 1 ? " dosa is" : " dosas are") + " sizzling on the tawa right now");
        }
        if (r.servedToday >= 10) {
            f.add(String.format(Locale.US, "Laid end to end, today's dosas would stretch about %d metres",
                    Math.round(r.servedToday * 0.35)));
        }
        // Interleave: a live (today's orders) fact between every menu story, so live ones recur.
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < TRIVIA.length; i++) {
            if (!f.isEmpty()) out.add(f.get(i % f.size()));
            out.add(TRIVIA[i]);
        }
        return out;
    }

    public static String hourLabel(int h) {
        int a = h % 12 == 0 ? 12 : h % 12, b = (h + 1) % 12 == 0 ? 12 : (h + 1) % 12;
        String ap = (h + 1) % 24 < 12 ? "AM" : "PM";
        return a + "\u2013" + b + " " + ap;
    }

    /** 16.2 -> "15–18 min", 19 -> "18–21 min", 43 -> "40–45 min". */
    public static String friendlyRange(double eta) {
        if (eta < 5) return "Under 5 min";
        int e = (int) Math.round(eta);
        int lo, hi;
        if (e <= 30) {
            lo = 5 * (e / 5);
            if (e - lo >= 3) lo += 3;
            hi = lo + 3;
        } else {
            lo = 5 * (e / 5);
            hi = lo + 5;
        }
        if (hi < e) hi = e;
        return lo + "–" + hi + " min";
    }

    private String historyLine(String day) {
        double avg = completed > 0 ? (totalPrepMs / (double) completed) / MINUTE : 0;
        return String.format(Locale.US, "%s|%d|%.1f|%.1f|%.2f",
                day, completed, totalPrepMs / (double) MINUTE, avg, peakRate);
    }

    // ---- persistence (flat strings for SharedPreferences) --------------------------------

    public Map<String, String> save() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("date", dateKey == null ? "" : dateKey);
        m.put("completed", String.valueOf(completed));
        m.put("totalPrepMs", String.valueOf(totalPrepMs));
        m.put("since", String.valueOf(trackingSince));
        m.put("peak", String.valueOf(peakRate));
        m.put("counted", join(countedIds, ","));
        m.put("recent", join(recent, ","));
        m.put("history", join(history, "\n"));
        m.put("seen", join(seenToday, ","));
        m.put("fastest", String.valueOf(fastestMs));
        m.put("served", String.valueOf(servedPieces));
        StringBuilder ts = new StringBuilder();
        for (long[] p : trend) ts.append(ts.length() == 0 ? "" : ",").append(p[0]).append(':').append(p[1]);
        m.put("trend", ts.toString());
        StringBuilder hs = new StringBuilder();
        for (int h : hourly) hs.append(hs.length() == 0 ? "" : ",").append(h);
        m.put("hourly", hs.toString());
        StringBuilder ks = new StringBuilder();
        for (Map.Entry<String, Integer> e : kinds.entrySet()) {
            if (ks.length() > 0) ks.append('\n');
            ks.append(e.getValue()).append('\t').append(e.getKey().replace('\n', ' ').replace('\t', ' '));
        }
        m.put("kinds", ks.toString());
        return m;
    }

    public void load(Map<String, String> m, long now) {
        String d = m.get("date");
        if (d != null && !d.isEmpty()) {
            dateKey = d;
            completed = parseInt(m.get("completed"));
            totalPrepMs = parseLong(m.get("totalPrepMs"));
            trackingSince = parseLong(m.get("since"));
            peakRate = parseDouble(m.get("peak"));
            for (String s : split(m.get("counted"), ",")) countedIds.add(parseLong(s));
            for (String s : split(m.get("recent"), ",")) recent.add(parseLong(s));
            for (String s : split(m.get("seen"), ",")) seenToday.add(parseLong(s));
            fastestMs = parseLong(m.get("fastest"));
            servedPieces = parseInt(m.get("served"));
            for (String s : split(m.get("trend"), ",")) {
                int k = s.indexOf(':');
                if (k > 0) trend.add(new long[]{parseLong(s.substring(0, k)), parseLong(s.substring(k + 1))});
            }
            List<String> hs = split(m.get("hourly"), ",");
            for (int i = 0; i < hs.size() && i < 24; i++) hourly[i] = parseInt(hs.get(i));
            for (String s : split(m.get("kinds"), "\n")) {
                int tab = s.indexOf('\t');
                if (tab > 0) kinds.put(s.substring(tab + 1), parseInt(s.substring(0, tab)));
            }
        }
        for (String s : split(m.get("history"), "\n")) history.add(s);
        rollover(now);
    }

    // ---- helpers -------------------------------------------------------------------------

    static String dayKey(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    static long startOfDay(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String join(Iterable<?> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (Object o : items) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(o);
        }
        return sb.toString();
    }

    private static List<String> split(String s, String sep) {
        List<String> out = new ArrayList<String>();
        if (s == null || s.isEmpty()) return out;
        for (String p : s.split(sep)) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0L; }
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s); } catch (Exception e) { return 0.0; }
    }
}
