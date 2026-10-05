package com.pp.kds.dosa;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PREP LIVE engine (pure Java). Turns every KOT on the board (all order types) into usage of the
 * tracked prepared items (sambar, chutneys, batters, ...) through the BOM, and projects the next
 * hour from the Petpooja-seeded weekday/hour profile, the last hour and the short-term trend.
 *
 * Only a small daily record is kept: 5-minute usage buckets per item for today, the KOT ids
 * already counted, batches made, and 7 days of hourly usage per item.
 */
public final class PrepStats {

    /** Data from assets/prep_profile.json (quantities only). */
    public static final class Model {
        public String[] targets = new String[0];
        public Map<String, double[]> menu = new HashMap<String, double[]>(); // item -> kg per portion
        public String[][] recipeNames = new String[0][];                    // per target: ingredients
        public double[][] recipeQty = new double[0][];                       // per kg of target
        public String[][] recipeUnits = new String[0][];
        public double[][][] profile = new double[0][][];                    // [target][weekday Mon=0][hour]
        public double seedDosaAvgPrepMin = Double.NaN;
    }

    public static final class Kot {
        final long id;
        final long createdMs;
        final int orderType;    // 0 dine-in, 1 pick-up, 2 delivery, 3 other
        final boolean cancelled;
        final String[] names;
        final double[] qty;

        public Kot(long id, long createdMs, int orderType, boolean cancelled, String[] names, double[] qty) {
            this.id = id;
            this.createdMs = createdMs;
            this.orderType = orderType;
            this.cancelled = cancelled;
            this.names = names;
            this.qty = qty;
        }
    }

    public static final class Item {
        public String name;
        public double usedToday, lastHour, nextHour, typicalNextHour;
        public double made, remaining = Double.NaN, minutesLeft = Double.NaN;
        public int status;                  // 0 no batch info, 1 ok, 2 prepare soon, 3 prepare now
        public double[] todayHourly = new double[24];
        public double[] typicalHourly = new double[24];
        public List<String> drivers = new ArrayList<String>();   // "Masala Dosa|0.41"
        public List<String> breakdown = new ArrayList<String>(); // "Roasted Peanut|1.23|kg"
    }

    public static final class Result {
        public Item[] items = new Item[0];
        public int[] kotsByType = new int[4];
        public List<String> unmatched = new ArrayList<String>();
        public long updatedAt;
    }

    static final long MINUTE = 60_000L;
    static final int BUCKETS = 288;                // 5-minute buckets per day
    static final double SOON_MIN = 30, NOW_MIN = 15;

    private final Model m;
    private final int n;
    private String dateKey;
    private double[][] buckets;                    // [target][288]
    private double[] made;
    private long[] firstBatchAt;
    private final LinkedList<double[]> lastBatches = new LinkedList<double[]>(); // {target, kg} for undo
    private final Set<Long> seen = new HashSet<Long>();
    private final int[] kotsByType = new int[4];
    private final List<Map<String, Double>> drivers = new ArrayList<Map<String, Double>>();
    private final Map<String, Double> unmatched = new HashMap<String, Double>();
    private final LinkedList<String> history = new LinkedList<String>(); // "date|wd|t|h0,h1,..."

    public PrepStats(Model model) {
        m = model;
        n = model.targets.length;
        reset();
    }

    public Model model() {
        return m;
    }

    private void reset() {
        buckets = new double[n][BUCKETS];
        made = new double[n];
        firstBatchAt = new long[n];
        lastBatches.clear();
        seen.clear();
        java.util.Arrays.fill(kotsByType, 0);
        drivers.clear();
        for (int i = 0; i < n; i++) drivers.add(new HashMap<String, Double>());
        unmatched.clear();
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US);
    }

    // ---- live input ----------------------------------------------------------------------

    /** Feed the board. Returns true when today's usage changed. */
    public boolean update(List<Kot> kots, long now) {
        boolean changed = rollover(now);
        long dayStart = DosaStats.startOfDay(now);
        for (Kot k : kots) {
            if (k.cancelled) continue;
            long at = k.createdMs > 0 ? k.createdMs : now;
            if (at < dayStart || at > now + 5 * MINUTE) continue;
            if (!seen.add(k.id)) continue;
            kotsByType[Math.max(0, Math.min(3, k.orderType))]++;
            int b = bucket(at);
            for (int i = 0; i < k.names.length; i++) {
                double q = k.qty[i] <= 0 ? 1 : k.qty[i];
                double[] per = m.menu.get(norm(k.names[i]));
                if (per == null) {
                    Double old = unmatched.get(k.names[i]);
                    unmatched.put(k.names[i], (old == null ? 0 : old) + q);
                    continue;
                }
                for (int t = 0; t < n && t < per.length; t++) {
                    if (per[t] <= 0) continue;
                    double kg = per[t] * q;
                    buckets[t][b] += kg;
                    Double old = drivers.get(t).get(k.names[i]);
                    drivers.get(t).put(k.names[i], (old == null ? 0 : old) + kg);
                }
            }
            changed = true;
        }
        return changed;
    }

    /** Staff tapped "batch made" for item t (kg). */
    public void addBatch(int t, double kg, long now) {
        rollover(now);
        if (firstBatchAt[t] == 0) firstBatchAt[t] = now;
        made[t] += kg;
        lastBatches.add(new double[]{t, kg});
    }

    /** Undo the most recent batch of item t. */
    public boolean undoBatch(int t) {
        for (int i = lastBatches.size() - 1; i >= 0; i--) {
            double[] b = lastBatches.get(i);
            if ((int) b[0] == t) {
                lastBatches.remove(i);
                made[t] = Math.max(0, made[t] - b[1]);
                if (made[t] == 0) firstBatchAt[t] = 0;
                return true;
            }
        }
        return false;
    }

    private boolean rollover(long now) {
        String today = DosaStats.dayKey(now);
        if (today.equals(dateKey)) return false;
        if (dateKey != null) {
            int wd = weekdayOfKey(dateKey);
            for (int t = 0; t < n; t++) {
                double[] h = hourly(t);
                double sum = 0;
                for (double v : h) sum += v;
                if (sum <= 0) continue;
                StringBuilder sb = new StringBuilder(dateKey).append('|').append(wd).append('|').append(t).append('|');
                for (int i = 0; i < 24; i++) sb.append(i == 0 ? "" : ",").append(String.format(Locale.US, "%.2f", h[i]));
                history.addFirst(sb.toString());
            }
            while (history.size() > 7 * n) history.removeLast();
        }
        dateKey = today;
        reset();
        return true;
    }

    // ---- projection ----------------------------------------------------------------------

    public Result compute(long now) {
        rollover(now);
        Result r = new Result();
        r.updatedAt = now;
        r.kotsByType = kotsByType.clone();
        int wd = weekday(now);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int hour = c.get(Calendar.HOUR_OF_DAY), minute = c.get(Calendar.MINUTE);
        int nowB = bucket(now);
        r.items = new Item[n];
        for (int t = 0; t < n; t++) {
            Item it = new Item();
            it.name = m.targets[t];
            double[] typ = typical(t, wd);
            it.typicalHourly = typ;
            it.todayHourly = hourly(t);
            for (int b = 0; b <= nowB; b++) it.usedToday += buckets[t][b];
            it.lastHour = sum(t, nowB - 11, nowB);
            double last30 = sum(t, nowB - 5, nowB), prev30 = sum(t, nowB - 11, nowB - 6);

            // typical usage for the coming 60 min and the past 60 min (minute-weighted)
            double f = minute / 60.0;
            double nextTyp = typ[hour] * (1 - f) + (hour < 23 ? typ[hour + 1] : 0) * f;
            double lastTyp = typ[hour] * f + (hour > 0 ? typ[hour - 1] : 0) * (1 - f);
            it.typicalNextHour = nextTyp;

            double trend = prev30 > 0.05 ? clamp(last30 / prev30, 0.7, 1.4) : 1.0;
            double trendProj = it.lastHour * trend;
            if (nextTyp > 0.05) {
                double scale = lastTyp > 0.2 && it.lastHour > 0 ? clamp(it.lastHour / lastTyp, 0.5, 2.0) : 1.0;
                it.nextHour = 0.6 * nextTyp * scale + 0.4 * (it.lastHour > 0 ? trendProj : nextTyp * scale);
            } else {
                it.nextHour = trendProj;
            }

            it.made = made[t];
            if (made[t] > 0) {
                double usedSince = 0;
                int fb = bucket(firstBatchAt[t]);
                for (int b = fb; b <= nowB; b++) usedSince += buckets[t][b];
                it.remaining = made[t] - usedSince;
                double perMin = Math.max(it.nextHour, it.lastHour) / 60.0;
                it.minutesLeft = perMin > 0 ? Math.max(0, it.remaining) / perMin : 999;
                it.status = it.remaining <= 0 || it.minutesLeft < NOW_MIN ? 3 : it.minutesLeft < SOON_MIN ? 2 : 1;
            }

            List<Map.Entry<String, Double>> ds = new ArrayList<Map.Entry<String, Double>>(drivers.get(t).entrySet());
            Collections.sort(ds, new Comparator<Map.Entry<String, Double>>() {
                @Override public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                    return Double.compare(b.getValue(), a.getValue());
                }
            });
            for (int i = 0; i < ds.size() && i < 5; i++) {
                it.drivers.add(ds.get(i).getKey() + "|" + (it.usedToday > 0 ? ds.get(i).getValue() / it.usedToday : 0));
            }
            if (t < m.recipeNames.length) {
                for (int i = 0; i < m.recipeNames[t].length; i++) {
                    it.breakdown.add(m.recipeNames[t][i] + "|" + m.recipeQty[t][i] * it.nextHour + "|"
                            + m.recipeUnits[t][i]);
                }
            }
            r.items[t] = it;
        }
        List<Map.Entry<String, Double>> um = new ArrayList<Map.Entry<String, Double>>(unmatched.entrySet());
        Collections.sort(um, new Comparator<Map.Entry<String, Double>>() {
            @Override public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(b.getValue(), a.getValue());
            }
        });
        for (int i = 0; i < um.size() && i < 6; i++) {
            r.unmatched.add(um.get(i).getKey() + " ×" + Math.round(um.get(i).getValue()));
        }
        return r;
    }

    /** Typical hourly usage: Petpooja seed, averaged with this tablet's own same-weekday days. */
    private double[] typical(int t, int wd) {
        double[] seed = t < m.profile.length ? m.profile[t][wd] : new double[24];
        double[] own = new double[24];
        int days = 0;
        for (String line : history) {
            String[] p = line.split("\\|");
            if (p.length < 4 || Integer.parseInt(p[1]) != wd || Integer.parseInt(p[2]) != t) continue;
            String[] v = p[3].split(",");
            for (int h = 0; h < 24 && h < v.length; h++) own[h] += Double.parseDouble(v[h]);
            days++;
        }
        if (days == 0) return seed.clone();
        double[] out = new double[24];
        for (int h = 0; h < 24; h++) out[h] = 0.5 * seed[h] + 0.5 * own[h] / days;
        return out;
    }

    private double[] hourly(int t) {
        double[] h = new double[24];
        for (int b = 0; b < BUCKETS; b++) h[b / 12] += buckets[t][b];
        return h;
    }

    private double sum(int t, int from, int to) {
        double s = 0;
        for (int b = Math.max(0, from); b <= to && b < BUCKETS; b++) s += buckets[t][b];
        return s;
    }

    static int bucket(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c.get(Calendar.HOUR_OF_DAY) * 12 + c.get(Calendar.MINUTE) / 5;
    }

    /** Monday = 0 ... Sunday = 6 (matches the Python profile builder). */
    static int weekday(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    static int weekdayOfKey(String key) {
        String[] p = key.split("-");
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), 12, 0);
        return weekday(c.getTimeInMillis());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- persistence ---------------------------------------------------------------------

    public Map<String, String> save() {
        Map<String, String> s = new HashMap<String, String>();
        s.put("date", dateKey == null ? "" : dateKey);
        StringBuilder b = new StringBuilder();
        for (int t = 0; t < n; t++) {
            if (t > 0) b.append(';');
            for (int i = 0; i < BUCKETS; i++) {
                if (buckets[t][i] != 0) b.append(i).append(':').append(String.format(Locale.US, "%.3f", buckets[t][i])).append(',');
            }
        }
        s.put("buckets", b.toString());
        StringBuilder mk = new StringBuilder();
        for (int t = 0; t < n; t++) mk.append(t == 0 ? "" : ",").append(made[t]).append('@').append(firstBatchAt[t]);
        s.put("made", mk.toString());
        StringBuilder sn = new StringBuilder();
        for (Long id : seen) sn.append(sn.length() == 0 ? "" : ",").append(id);
        s.put("seen", sn.toString());
        s.put("types", kotsByType[0] + "," + kotsByType[1] + "," + kotsByType[2] + "," + kotsByType[3]);
        StringBuilder dr = new StringBuilder();
        for (int t = 0; t < n; t++) {
            for (Map.Entry<String, Double> e : drivers.get(t).entrySet()) {
                dr.append(t).append('\t').append(e.getKey().replace('\t', ' ').replace('\n', ' '))
                        .append('\t').append(e.getValue()).append('\n');
            }
        }
        s.put("drivers", dr.toString());
        StringBuilder un = new StringBuilder();
        for (Map.Entry<String, Double> e : unmatched.entrySet()) {
            un.append(e.getKey().replace('\t', ' ').replace('\n', ' ')).append('\t').append(e.getValue()).append('\n');
        }
        s.put("unmatched", un.toString());
        StringBuilder hi = new StringBuilder();
        for (String h : history) hi.append(hi.length() == 0 ? "" : "\n").append(h);
        s.put("history", hi.toString());
        return s;
    }

    public void load(Map<String, String> s, long now) {
        try {
            String hist = s.get("history");
            if (hist != null) for (String h : hist.split("\n")) if (!h.isEmpty()) history.add(h);
            String d = s.get("date");
            if (d == null || d.isEmpty()) return;
            dateKey = d;
            String[] per = (s.get("buckets") == null ? "" : s.get("buckets")).split(";", -1);
            for (int t = 0; t < n && t < per.length; t++) {
                for (String cell : per[t].split(",")) {
                    int k = cell.indexOf(':');
                    if (k > 0) buckets[t][Integer.parseInt(cell.substring(0, k))] = Double.parseDouble(cell.substring(k + 1));
                }
            }
            String[] mk = (s.get("made") == null ? "" : s.get("made")).split(",");
            for (int t = 0; t < n && t < mk.length; t++) {
                int k = mk[t].indexOf('@');
                if (k > 0) {
                    made[t] = Double.parseDouble(mk[t].substring(0, k));
                    firstBatchAt[t] = Long.parseLong(mk[t].substring(k + 1));
                }
            }
            String sn = s.get("seen");
            if (sn != null) for (String id : sn.split(",")) if (!id.isEmpty()) seen.add(Long.parseLong(id));
            String ty = s.get("types");
            if (ty != null) {
                String[] p = ty.split(",");
                for (int i = 0; i < 4 && i < p.length; i++) kotsByType[i] = Integer.parseInt(p[i]);
            }
            String dr = s.get("drivers");
            if (dr != null) {
                for (String line : dr.split("\n")) {
                    String[] p = line.split("\t");
                    if (p.length == 3) {
                        int t = Integer.parseInt(p[0]);
                        if (t < n) drivers.get(t).put(p[1], Double.parseDouble(p[2]));
                    }
                }
            }
            String un = s.get("unmatched");
            if (un != null) {
                for (String line : un.split("\n")) {
                    String[] p = line.split("\t");
                    if (p.length == 2) unmatched.put(p[0], Double.parseDouble(p[1]));
                }
            }
        } catch (RuntimeException e) {
            reset(); // corrupt record: start the day clean rather than crash the KDS
        } finally {
            rollover(now);
        }
    }
}
