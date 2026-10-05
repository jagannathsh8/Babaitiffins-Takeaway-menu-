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
import java.util.TreeSet;

/**
 * PREP LIVE engine (pure Java). Every KOT on the board (all order types) is turned into usage of
 * each prepared (OP/CP) item through the BOM. Next-hour projection (backtested on 1 Sep - 5 Oct
 * 2026 Petpooja data: ~15-17% error vs ~43% for "same as last hour"):
 *
 *   sources  = the same weekday 1..4 weeks back (past Sundays when "holiday today" is on),
 *              skipping holidays and incomplete days
 *   typical  = 60% x 4th source back ("same day last month")
 *            + 20% x average of the 4 sources + 20% x average of the latest 2 sources
 *   pace     = today's actual / typical over the last 2 hours (0.5 .. 2.0)
 *   next hr  = typical next hour x (1 + 0.5 x (pace - 1))   e.g. 40% busier -> +20%
 *
 * Dated history comes from the Petpooja seed (assets/prep_days.txt) and this tablet's own record
 * (42 days, prep items + dishes per hour), which also feeds the date-wise Excel export.
 */
public final class PrepStats {

    /** Static data from the assets (quantities only). */
    public static final class Model {
        public String[] targets = new String[0];
        public Map<String, double[]> menu = new HashMap<String, double[]>(); // dish -> qty per portion
        public String[][] recipeNames = new String[0][];
        public double[][] recipeQty = new double[0][];
        public String[][] recipeUnits = new String[0][];
        public String[] units = new String[0];
        public int[] defaults = new int[0];
        public double[] avgPerDay = new double[0];
        public double seedDosaAvgPrepMin = Double.NaN;
        public Set<String> holidays = new HashSet<String>();
        public Map<String, double[][]> seedT = new HashMap<String, double[][]>();          // date -> [t][24]
        public Map<String, Map<String, double[]>> seedD = new HashMap<String, Map<String, double[]>>();

        public double avgPerDay(int t) {
            return t < avgPerDay.length ? avgPerDay[t] : 0;
        }

        public String unit(int t) {
            return t < units.length && units[t] != null ? units[t] : "kg";
        }

        /** Parses one line of prep_days.txt: "T|date|t|h:v h:v" or "D|date|dish|h:v ...". */
        public void addSeedLine(String line) {
            String[] p = line.split("\\|", 4);
            if (p.length < 4) return;
            double[] hrs = parseCells(p[3]);
            if (p[0].equals("T")) {
                int t = Integer.parseInt(p[2]);
                if (t >= targets.length) return;
                double[][] day = seedT.get(p[1]);
                if (day == null) {
                    day = new double[targets.length][];
                    seedT.put(p[1], day);
                }
                day[t] = hrs;
            } else if (p[0].equals("D")) {
                Map<String, double[]> day = seedD.get(p[1]);
                if (day == null) {
                    day = new HashMap<String, double[]>();
                    seedD.put(p[1], day);
                }
                day.put(p[2], hrs);
            }
        }
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
        public String unit = "kg";
        public double usedToday, lastHour, nextHour, typicalNextHour;
        public double pace = Double.NaN;       // last 2 h actual / typical (1.4 = 40% busier)
        public double vsTypical = Double.NaN;  // today so far vs typical so far
        public double made, remaining = Double.NaN, minutesLeft = Double.NaN;
        public int status;                     // 0 no batch info, 1 ok, 2 prepare soon, 3 prepare now
        public double[] todayHourly = new double[24];
        public double[] typicalHourly = new double[24];
        public List<String> drivers = new ArrayList<String>();   // "Masala Dosa|0.41"
        public List<String> breakdown = new ArrayList<String>(); // "CP - Roasted Peanut|1.23|kg"
    }

    public static final class Result {
        public Item[] items = new Item[0];
        public int[] kotsByType = new int[4];
        public List<String> unmatched = new ArrayList<String>();
        public List<String> sources = new ArrayList<String>();  // dates used for today's projection
        public boolean holidayToday;
        public long updatedAt;
    }

    /** One date for the report screen / Excel export. */
    public static final class Day {
        public String date;
        public boolean today, holiday, complete;
        public String source;
        public double[][] prep;                   // [t][24], rows may be null
        public Map<String, double[]> dishes = new HashMap<String, double[]>();
        public int[] kotsByType;                  // null when unknown
    }

    static final long MINUTE = 60_000L;
    static final int BUCKETS = 288;                // 5-minute buckets per day
    static final double SOON_MIN = 30, NOW_MIN = 15;
    static final int HISTORY_DAYS = 42;
    static final double W_LAST_MONTH = 0.6, W_FOUR_WEEKS = 0.2, W_TWO_WEEKS = 0.2;
    static final double PACE_REACTION = 0.5, PACE_MIN = 0.5, PACE_MAX = 2.0;
    static final int PACE_BUCKETS = 24;            // last 2 hours
    static final int COMPLETE_FROM = 90, COMPLETE_TO = 264;  // tracked by 7:30 and until 22:00

    private final Model m;
    private final int n;
    private String dateKey;
    private double[][] buckets;
    private final Map<String, double[]> dishToday = new HashMap<String, double[]>();
    private double[] made;
    private long[] firstBatchAt;
    private final LinkedList<double[]> lastBatches = new LinkedList<double[]>();
    private final Set<Long> seen = new HashSet<Long>();
    private final int[] kotsByType = new int[4];
    private final List<Map<String, Double>> drivers = new ArrayList<Map<String, Double>>();
    private final Map<String, Double> unmatched = new HashMap<String, Double>();
    private boolean holidayToday;
    private int trackedFrom = -1, trackedTo = -1;

    private final Map<String, double[][]> ownT = new HashMap<String, double[][]>();
    private final Map<String, Map<String, double[]>> ownD = new HashMap<String, Map<String, double[]>>();
    private final Map<String, int[]> ownK = new HashMap<String, int[]>();
    private final Set<String> ownComplete = new HashSet<String>();
    private final Set<String> ownHolidays = new HashSet<String>();
    private boolean historyChanged;
    private String typicalKey;
    private double[][] typicalCache;
    private List<String> typicalSources = new ArrayList<String>();

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
        dishToday.clear();
        made = new double[n];
        firstBatchAt = new long[n];
        lastBatches.clear();
        seen.clear();
        java.util.Arrays.fill(kotsByType, 0);
        drivers.clear();
        for (int i = 0; i < n; i++) drivers.add(new HashMap<String, Double>());
        unmatched.clear();
        holidayToday = false;
        trackedFrom = -1;
        trackedTo = -1;
        typicalKey = null;
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US);
    }

    // ---- live input ----------------------------------------------------------------------

    /** Feed the board. Returns true when today's usage changed. */
    public boolean update(List<Kot> kots, long now) {
        boolean changed = rollover(now);
        touch(now);
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
                String dish = k.names[i] == null ? "" : k.names[i].trim();
                double[] dh = dishToday.get(dish);
                if (dh == null) {
                    dh = new double[24];
                    dishToday.put(dish, dh);
                }
                dh[b / 12] += q;
                double[] per = m.menu.get(norm(dish));
                if (per == null) {
                    Double old = unmatched.get(dish);
                    unmatched.put(dish, (old == null ? 0 : old) + q);
                    continue;
                }
                for (int t = 0; t < n && t < per.length; t++) {
                    if (per[t] <= 0) continue;
                    double v = per[t] * q;
                    buckets[t][b] += v;
                    Double old = drivers.get(t).get(dish);
                    drivers.get(t).put(dish, (old == null ? 0 : old) + v);
                }
            }
            changed = true;
        }
        return changed;
    }

    /** Records which part of the day this tablet was watching (for "complete day" and pace). */
    private void touch(long now) {
        int b = bucket(now);
        if (trackedFrom < 0 || b < trackedFrom) trackedFrom = b;
        if (b > trackedTo) trackedTo = b;
    }

    public void addBatch(int t, double qty, long now) {
        rollover(now);
        if (firstBatchAt[t] == 0) firstBatchAt[t] = now;
        made[t] += qty;
        lastBatches.add(new double[]{t, qty});
    }

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

    /** Staff switch: today is a holiday/festival -> project from past Sundays. */
    public void setHolidayToday(boolean on, long now) {
        rollover(now);
        holidayToday = on;
        typicalKey = null;
    }

    public boolean holidayToday() {
        return holidayToday;
    }

    private boolean rollover(long now) {
        String today = DosaStats.dayKey(now);
        if (today.equals(dateKey)) return false;
        if (dateKey != null) archiveToday();
        dateKey = today;
        reset();
        return true;
    }

    private void archiveToday() {
        double[][] day = new double[n][];
        boolean any = false;
        for (int t = 0; t < n; t++) {
            double[] h = hourly(t);
            for (double v : h) {
                if (v > 0) {
                    day[t] = h;
                    any = true;
                    break;
                }
            }
        }
        if (!any && dishToday.isEmpty()) return;
        ownT.put(dateKey, day);
        Map<String, double[]> dd = new HashMap<String, double[]>();
        for (Map.Entry<String, double[]> e : dishToday.entrySet()) dd.put(e.getKey(), e.getValue().clone());
        ownD.put(dateKey, dd);
        ownK.put(dateKey, kotsByType.clone());
        if (trackedFrom >= 0 && trackedFrom <= COMPLETE_FROM && trackedTo >= COMPLETE_TO) ownComplete.add(dateKey);
        if (holidayToday) ownHolidays.add(dateKey);
        trimHistory();
        historyChanged = true;
    }

    private void trimHistory() {
        TreeSet<String> dates = new TreeSet<String>(ownT.keySet());
        dates.addAll(ownD.keySet());
        while (dates.size() > HISTORY_DAYS) {
            String old = dates.pollFirst();
            ownT.remove(old);
            ownD.remove(old);
            ownK.remove(old);
            ownComplete.remove(old);
            ownHolidays.remove(old);
        }
    }

    // ---- projection ----------------------------------------------------------------------

    private boolean isHoliday(String date) {
        return m.holidays.contains(date) || ownHolidays.contains(date);
    }

    /** Complete prep data for a past date (own complete record first, then Petpooja seed). */
    private double[][] sourceDay(String date) {
        if (isHoliday(date)) return null;
        if (ownComplete.contains(date)) return ownT.get(date);
        return m.seedT.get(date);
    }

    /** Owner's 60/20/20 rule; returns [t][24] and remembers the source dates. */
    private double[][] typicalFor(String date, boolean holiday) {
        String key = date + (holiday ? "H" : "");
        if (key.equals(typicalKey)) return typicalCache;
        Calendar c = calendarOf(date);
        if (holiday) {
            // anchor on the coming Sunday so that "1 week back" is the most recent past Sunday
            while (c.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) c.add(Calendar.DAY_OF_MONTH, 1);
        }
        List<double[][]> src = new ArrayList<double[][]>();
        List<String> used = new ArrayList<String>();
        for (int k = 1; k <= 8 && src.size() < 4; k++) {
            c.add(Calendar.DAY_OF_MONTH, -7);
            String d = DosaStats.dayKey(c.getTimeInMillis());
            if (d.compareTo(date) >= 0) continue;
            double[][] day = sourceDay(d);
            if (day != null) {
                src.add(day);
                used.add(d);
            }
        }
        double[][] out = new double[n][24];
        for (int t = 0; t < n; t++) {
            for (int h = 0; h < 24; h++) {
                double w = 0, v = 0;
                if (src.size() >= 4) {
                    w += W_LAST_MONTH;
                    v += W_LAST_MONTH * val(src.get(3), t, h);
                }
                if (!src.isEmpty()) {
                    double b = 0, cc = 0;
                    for (int i = 0; i < src.size(); i++) b += val(src.get(i), t, h);
                    int two = Math.min(2, src.size());
                    for (int i = 0; i < two; i++) cc += val(src.get(i), t, h);
                    w += W_FOUR_WEEKS + W_TWO_WEEKS;
                    v += W_FOUR_WEEKS * b / src.size() + W_TWO_WEEKS * cc / two;
                }
                out[t][h] = w > 0 ? v / w : 0;
            }
        }
        typicalKey = key;
        typicalCache = out;
        typicalSources = used;
        return out;
    }

    private static double val(double[][] day, int t, int h) {
        return t < day.length && day[t] != null && h < day[t].length ? day[t][h] : 0;
    }

    public Result compute(long now) {
        rollover(now);
        touch(now);
        Result r = new Result();
        r.updatedAt = now;
        r.kotsByType = kotsByType.clone();
        r.holidayToday = holidayToday;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        int hour = c.get(Calendar.HOUR_OF_DAY), minute = c.get(Calendar.MINUTE);
        int nowB = bucket(now);
        double[][] typ = typicalFor(dateKey, holidayToday);
        r.sources = new ArrayList<String>(typicalSources);
        r.items = new Item[n];
        double f = minute / 60.0;
        // windows end "now": full 5-min buckets plus the part of the current bucket already elapsed
        double partial = (minute % 5) / 5.0;
        int paceFrom = Math.max(0, nowB - PACE_BUCKETS);
        boolean paceKnown = trackedFrom >= 0 && trackedFrom <= paceFrom;  // watched the whole window
        for (int t = 0; t < n; t++) {
            Item it = new Item();
            it.name = m.targets[t];
            it.unit = m.unit(t);
            it.typicalHourly = typ[t].clone();
            it.todayHourly = hourly(t);
            for (int b = 0; b <= nowB; b++) it.usedToday += buckets[t][b];
            it.lastHour = sum(t, nowB - 12, nowB);
            it.typicalNextHour = typ[t][hour] * (1 - f) + (hour < 23 ? typ[t][hour + 1] : 0) * f;

            double typWindow = 0;
            for (int b = paceFrom; b < nowB; b++) typWindow += typ[t][b / 12] / 12.0;
            typWindow += typ[t][nowB / 12] / 12.0 * partial;
            double actWindow = sum(t, paceFrom, nowB);
            double adj = 1.0;
            if (paceKnown && typWindow > 0.5) {
                it.pace = Math.max(PACE_MIN, Math.min(PACE_MAX, actWindow / typWindow));
                adj = 1 + PACE_REACTION * (it.pace - 1);
            }
            it.nextHour = it.typicalNextHour * adj;

            double typSoFar = 0;
            for (int h = 0; h < hour; h++) typSoFar += typ[t][h];
            typSoFar += typ[t][hour] * f;
            if (typSoFar > 0.5) it.vsTypical = it.usedToday / typSoFar;

            it.made = made[t];
            if (made[t] > 0) {
                double usedSince = 0;
                for (int b = bucket(firstBatchAt[t]); b <= nowB; b++) usedSince += buckets[t][b];
                it.remaining = made[t] - usedSince;
                double perMin = Math.max(it.nextHour, it.lastHour) / 60.0;
                it.minutesLeft = perMin > 0 ? Math.max(0, it.remaining) / perMin : 999;
                it.status = it.remaining <= 0 || it.minutesLeft < NOW_MIN ? 3 : it.minutesLeft < SOON_MIN ? 2 : 1;
            }
            List<Map.Entry<String, Double>> ds = sorted(drivers.get(t));
            for (int i = 0; i < ds.size() && i < 5; i++) {
                it.drivers.add(ds.get(i).getKey() + "|" + (it.usedToday > 0 ? ds.get(i).getValue() / it.usedToday : 0));
            }
            if (t < m.recipeNames.length) {
                for (int i = 0; i < m.recipeNames[t].length; i++) {
                    it.breakdown.add(m.recipeNames[t][i] + "|" + m.recipeQty[t][i] * it.nextHour + "|" + m.recipeUnits[t][i]);
                }
            }
            r.items[t] = it;
        }
        List<Map.Entry<String, Double>> um = sorted(unmatched);
        for (int i = 0; i < um.size() && i < 6; i++) {
            r.unmatched.add(um.get(i).getKey() + " ×" + Math.round(um.get(i).getValue()));
        }
        return r;
    }

    private static List<Map.Entry<String, Double>> sorted(Map<String, Double> map) {
        List<Map.Entry<String, Double>> l = new ArrayList<Map.Entry<String, Double>>(map.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Double>>() {
            @Override public int compare(Map.Entry<String, Double> a, Map.Entry<String, Double> b) {
                return Double.compare(b.getValue(), a.getValue());
            }
        });
        return l;
    }

    // ---- report / export -----------------------------------------------------------------

    /** All dates that can be viewed/exported, newest first. */
    public List<String> availableDates(long now) {
        rollover(now);
        TreeSet<String> all = new TreeSet<String>(Collections.reverseOrder());
        all.add(dateKey);
        all.addAll(ownT.keySet());
        all.addAll(ownD.keySet());
        all.addAll(m.seedT.keySet());
        all.addAll(m.seedD.keySet());
        return new ArrayList<String>(all);
    }

    public Day day(String date, long now) {
        rollover(now);
        Day d = new Day();
        d.date = date;
        d.holiday = date.equals(dateKey) ? holidayToday : isHoliday(date);
        if (date.equals(dateKey)) {
            d.today = true;
            d.source = "Live (today, so far)";
            d.prep = new double[n][];
            for (int t = 0; t < n; t++) d.prep[t] = hourly(t);
            for (Map.Entry<String, double[]> e : dishToday.entrySet()) d.dishes.put(e.getKey(), e.getValue().clone());
            d.kotsByType = kotsByType.clone();
        } else if (ownT.containsKey(date) || ownD.containsKey(date)) {
            d.source = "Tablet record" + (ownComplete.contains(date) ? "" : " (partial day)");
            d.prep = ownT.containsKey(date) ? ownT.get(date) : new double[n][];
            if (ownD.containsKey(date)) d.dishes.putAll(ownD.get(date));
            d.kotsByType = ownK.get(date);
            d.complete = ownComplete.contains(date);
        } else {
            d.source = "Petpooja report";
            d.prep = m.seedT.containsKey(date) ? m.seedT.get(date) : new double[n][];
            if (m.seedD.containsKey(date)) d.dishes.putAll(m.seedD.get(date));
            d.complete = true;
        }
        return d;
    }

    // ---- helpers -------------------------------------------------------------------------

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

    static Calendar calendarOf(String key) {
        String[] p = key.split("-");
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]), 12, 0);
        return c;
    }

    static double[] parseCells(String cells) {
        double[] h = new double[24];
        for (String cell : cells.trim().split(" ")) {
            int k = cell.indexOf(':');
            if (k > 0) {
                int hr = Integer.parseInt(cell.substring(0, k));
                if (hr >= 0 && hr < 24) h[hr] = Double.parseDouble(cell.substring(k + 1));
            }
        }
        return h;
    }

    static String cells(double[] h) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.length; i++) {
            if (h[i] != 0) sb.append(sb.length() == 0 ? "" : " ").append(i).append(':').append(Math.round(h[i] * 1000) / 1000.0);
        }
        return sb.toString();
    }

    // ---- persistence ---------------------------------------------------------------------

    public boolean historyChanged() {
        return historyChanged;
    }

    /** Own dated history (rewritten once a day). */
    public String historyBlob() {
        historyChanged = false;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, double[][]> e : ownT.entrySet()) {
            double[][] day = e.getValue();
            for (int t = 0; t < day.length; t++) {
                if (day[t] != null) sb.append("T|").append(e.getKey()).append('|').append(t).append('|').append(cells(day[t])).append('\n');
            }
        }
        for (Map.Entry<String, Map<String, double[]>> e : ownD.entrySet()) {
            for (Map.Entry<String, double[]> d : e.getValue().entrySet()) {
                sb.append("D|").append(e.getKey()).append('|').append(d.getKey().replace('|', '/').replace('\n', ' '))
                        .append('|').append(cells(d.getValue())).append('\n');
            }
        }
        for (Map.Entry<String, int[]> e : ownK.entrySet()) {
            int[] k = e.getValue();
            sb.append("K|").append(e.getKey()).append('|').append(k[0]).append(',').append(k[1]).append(',')
                    .append(k[2]).append(',').append(k[3]).append("|\n");
        }
        for (String d : ownComplete) sb.append("C|").append(d).append("||\n");
        for (String d : ownHolidays) sb.append("H|").append(d).append("||\n");
        return sb.toString();
    }

    public void loadHistory(String blob) {
        ownT.clear();
        ownD.clear();
        ownK.clear();
        ownComplete.clear();
        ownHolidays.clear();
        if (blob != null) {
            for (String line : blob.split("\n")) {
                try {
                    String[] p = line.split("\\|", 4);
                    if (p.length < 4) continue;
                    if (p[0].equals("T")) {
                        int t = Integer.parseInt(p[2]);
                        if (t >= n) continue;
                        double[][] day = ownT.get(p[1]);
                        if (day == null) {
                            day = new double[n][];
                            ownT.put(p[1], day);
                        }
                        day[t] = parseCells(p[3]);
                    } else if (p[0].equals("D")) {
                        Map<String, double[]> day = ownD.get(p[1]);
                        if (day == null) {
                            day = new HashMap<String, double[]>();
                            ownD.put(p[1], day);
                        }
                        day.put(p[2], parseCells(p[3]));
                    } else if (p[0].equals("K")) {
                        String[] k = p[2].split(",");
                        ownK.put(p[1], new int[]{Integer.parseInt(k[0]), Integer.parseInt(k[1]),
                                Integer.parseInt(k[2]), Integer.parseInt(k[3])});
                    } else if (p[0].equals("C")) {
                        ownComplete.add(p[1]);
                    } else if (p[0].equals("H")) {
                        ownHolidays.add(p[1]);
                    }
                } catch (RuntimeException ignored) {
                }
            }
        }
        trimHistory();
        typicalKey = null;
    }

    /** Today's record (saved about once a minute). */
    public Map<String, String> save() {
        Map<String, String> s = new HashMap<String, String>();
        s.put("date", dateKey == null ? "" : dateKey);
        StringBuilder b = new StringBuilder();
        for (int t = 0; t < n; t++) {
            if (t > 0) b.append(';');
            for (int i = 0; i < BUCKETS; i++) {
                if (buckets[t][i] != 0) b.append(i).append(':').append(Math.round(buckets[t][i] * 1000) / 1000.0).append(',');
            }
        }
        s.put("buckets", b.toString());
        StringBuilder dh = new StringBuilder();
        for (Map.Entry<String, double[]> e : dishToday.entrySet()) {
            dh.append(e.getKey().replace('\t', ' ').replace('\n', ' ')).append('\t').append(cells(e.getValue())).append('\n');
        }
        s.put("dishes", dh.toString());
        StringBuilder mk = new StringBuilder();
        for (int t = 0; t < n; t++) mk.append(t == 0 ? "" : ",").append(made[t]).append('@').append(firstBatchAt[t]);
        s.put("made", mk.toString());
        StringBuilder sn = new StringBuilder();
        for (Long id : seen) sn.append(sn.length() == 0 ? "" : ",").append(id);
        s.put("seen", sn.toString());
        s.put("types", kotsByType[0] + "," + kotsByType[1] + "," + kotsByType[2] + "," + kotsByType[3]);
        s.put("flags", (holidayToday ? 1 : 0) + "," + trackedFrom + "," + trackedTo);
        StringBuilder dr = new StringBuilder();
        for (int t = 0; t < n; t++) {
            for (Map.Entry<String, Double> e : drivers.get(t).entrySet()) {
                dr.append(t).append('\t').append(e.getKey().replace('\t', ' ').replace('\n', ' ')).append('\t').append(e.getValue()).append('\n');
            }
        }
        s.put("drivers", dr.toString());
        StringBuilder un = new StringBuilder();
        for (Map.Entry<String, Double> e : unmatched.entrySet()) {
            un.append(e.getKey().replace('\t', ' ').replace('\n', ' ')).append('\t').append(e.getValue()).append('\n');
        }
        s.put("unmatched", un.toString());
        return s;
    }

    public void load(Map<String, String> s, long now) {
        try {
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
            String dh = s.get("dishes");
            if (dh != null) {
                for (String line : dh.split("\n")) {
                    String[] p = line.split("\t");
                    if (p.length == 2) dishToday.put(p[0], parseCells(p[1]));
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
            String fl = s.get("flags");
            if (fl != null) {
                String[] p = fl.split(",");
                holidayToday = p[0].equals("1");
                trackedFrom = Integer.parseInt(p[1]);
                trackedTo = Integer.parseInt(p[2]);
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
            reset();
        } finally {
            rollover(now);
        }
    }
}
