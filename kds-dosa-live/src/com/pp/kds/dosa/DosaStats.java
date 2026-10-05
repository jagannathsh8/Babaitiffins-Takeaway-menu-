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

        public Entry(long id, long createdMs, boolean ready, boolean cancelled) {
            this.id = id;
            this.createdMs = createdMs;
            this.ready = ready;
            this.cancelled = cancelled;
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

    private String dateKey;
    private int completed;
    private long totalPrepMs;
    private long trackingSince;
    private double peakRate;
    private final Set<Long> countedIds = new HashSet<Long>();
    private final LinkedList<Long> recent = new LinkedList<Long>();
    private final LinkedList<String> history = new LinkedList<String>();
    private final Map<Long, Long> active = new HashMap<Long, Long>(); // id -> createdMs

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
        for (Entry e : snapshot) {
            seen.add(e.id);
            if (e.cancelled) {
                if (active.remove(e.id) != null) changed = true;
            } else if (e.ready) {
                Long created = active.remove(e.id);
                if (created != null) {
                    record(e.id, e.createdMs > 0 ? e.createdMs : created, now);
                    changed = true;
                }
            } else {
                Long prev = active.put(e.id, e.createdMs);
                if (prev == null) changed = true;
            }
        }
        Iterator<Map.Entry<Long, Long>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> a = it.next();
            if (!seen.contains(a.getKey())) {
                // Left the board while preparing: normally it was readied & cleared in one step.
                if (!capPressure) record(a.getKey(), a.getValue(), now);
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    private void record(long id, long createdMs, long now) {
        if (!countedIds.add(id)) return;
        long prep = now - createdMs;
        if (createdMs <= 0 || prep < 0 || prep > MAX_PREP_MS) return; // outlier / unknown
        completed++;
        totalPrepMs += prep;
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

        r.history.add(historyLine(dateKey));
        r.history.addAll(history);
        return r;
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
