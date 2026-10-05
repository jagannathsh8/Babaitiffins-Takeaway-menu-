import com.pp.kds.dosa.DosaStats;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/** Plain-JVM checks for DosaStats. Run: see build.sh (exits non-zero on failure). */
public final class DosaStatsTest {
    static final long MIN = 60_000L;
    static int failures;

    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!ok) failures++;
    }

    static long at(int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.OCTOBER, 5, h, m, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static DosaStats.Entry prep(long id, long created) { return new DosaStats.Entry(id, created, false, false); }
    static DosaStats.Entry ready(long id, long created) { return new DosaStats.Entry(id, created, true, false); }

    public static void main(String[] a) {
        // Friendly ranges
        check("range 16.2", DosaStats.friendlyRange(16.2).equals("15–18 min"), DosaStats.friendlyRange(16.2));
        check("range 19", DosaStats.friendlyRange(19).equals("18–21 min"), DosaStats.friendlyRange(19));
        check("range 4", DosaStats.friendlyRange(4).equals("Under 5 min"), DosaStats.friendlyRange(4));
        check("range 43", DosaStats.friendlyRange(43).equals("40–45 min"), DosaStats.friendlyRange(43));
        for (double e = 5; e <= 90; e += 0.5) {
            String r = DosaStats.friendlyRange(e);
            String[] p = r.replace(" min", "").split("–");
            int lo = Integer.parseInt(p[0]), hi = Integer.parseInt(p[1]);
            if (Math.round(e) < lo || Math.round(e) > hi) check("range contains " + e, false, r);
        }

        // Rush scenario: 30 min of steady completions at 2 KOT/min with 32 active.
        DosaStats s = new DosaStats();
        long t0 = at(19, 0);
        s.update(new ArrayList<DosaStats.Entry>(), t0, false);
        long nextId = 1;
        List<long[]> board = new ArrayList<long[]>(); // {id, created}
        for (int i = 0; i < 32; i++) board.add(new long[]{nextId++, t0 - 12 * MIN});
        for (int sec = 0; sec <= 30 * 60; sec += 30) {
            long now = t0 + sec * 1000L;
            List<DosaStats.Entry> snap = new ArrayList<DosaStats.Entry>();
            // complete the oldest KOT every 30 s (2/min), add a new one every 30 s (steady queue)
            long[] done = board.remove(0);
            snap.add(ready(done[0], done[1]));
            board.add(new long[]{nextId++, now});
            for (long[] k : board) snap.add(prep(k[0], k[1]));
            s.update(snap, now, false);
        }
        DosaStats.Result r = s.compute(t0 + 30 * MIN);
        check("active 32", r.activeCount == 32, r.activeCount);
        check("throughput ~2/min", Math.abs(r.throughputPerMin - 2.0) < 0.15, r.throughputPerMin);
        check("eta ~16 min", Math.abs(r.etaMin - 16) < 1.5, r.etaMin + " " + r.etaLabel);
        System.out.println("     avg " + r.avgPrepMin + " completed " + r.completedToday);

        // Production improves: queue drains with no new orders -> ETA falls.
        double prevEta = r.etaMin;
        boolean falling = true;
        long t1 = t0 + 30 * MIN;
        for (int step = 1; step <= 10; step++) {
            long now = t1 + step * 30_000L;
            List<DosaStats.Entry> snap = new ArrayList<DosaStats.Entry>();
            for (int k = 0; k < 2 && !board.isEmpty(); k++) { long[] d = board.remove(0); snap.add(ready(d[0], d[1])); }
            for (long[] k : board) snap.add(prep(k[0], k[1]));
            s.update(snap, now, false);
            DosaStats.Result rr = s.compute(now);
            if (rr.etaMin > prevEta + 0.01) falling = false;
            prevEta = rr.etaMin;
        }
        check("eta falls as queue clears", falling, prevEta);

        // Restart: persisted daily record survives, counted KOTs not double counted.
        Map<String, String> saved = s.save();
        DosaStats s2 = new DosaStats();
        long t2 = t1 + 6 * MIN;
        s2.load(saved, t2);
        DosaStats.Result before = s2.compute(t2);
        List<DosaStats.Entry> snap = new ArrayList<DosaStats.Entry>();
        snap.add(ready(1, t0 - 12 * MIN)); // already counted earlier, still on board as ready
        s2.update(snap, t2, false);
        check("restart keeps completed", s2.compute(t2).completedToday == before.completedToday
                && before.completedToday == r.completedToday + 20, before.completedToday);

        // Ready KOTs we never saw preparing (e.g. app just opened) are not counted.
        DosaStats s3 = new DosaStats();
        s3.update(java.util.Collections.singletonList(ready(999, t0)), t0 + 5 * MIN, false);
        check("unseen ready ignored", s3.compute(t0 + 5 * MIN).completedToday == 0, "");

        // Cap pressure: vanished KOT not recorded; normal: vanished KOT recorded.
        DosaStats s4 = new DosaStats();
        s4.update(java.util.Collections.singletonList(prep(5, t0)), t0, true);
        s4.update(new ArrayList<DosaStats.Entry>(), t0 + 10 * MIN, true);
        check("cap drop ignored", s4.compute(t0 + 10 * MIN).completedToday == 0, "");
        s4.update(java.util.Collections.singletonList(prep(6, t0)), t0 + 10 * MIN, false);
        s4.update(new ArrayList<DosaStats.Entry>(), t0 + 20 * MIN, false);
        DosaStats.Result r4 = s4.compute(t0 + 20 * MIN);
        check("cleared KOT recorded", r4.completedToday == 1 && Math.abs(r4.avgPrepMin - 20) < 0.01, r4.avgPrepMin);

        // Cancelled KOT leaves the queue without a prep sample; outlier >150 min ignored.
        DosaStats s5 = new DosaStats();
        s5.update(java.util.Collections.singletonList(prep(7, t0)), t0, false);
        s5.update(java.util.Collections.singletonList(new DosaStats.Entry(7, t0, false, true)), t0 + MIN, false);
        s5.update(java.util.Collections.singletonList(prep(8, t0 - 200 * MIN)), t0 + MIN, false);
        s5.update(java.util.Collections.singletonList(ready(8, t0 - 200 * MIN)), t0 + 2 * MIN, false);
        DosaStats.Result r5 = s5.compute(t0 + 2 * MIN);
        check("cancel/outlier", r5.completedToday == 0 && r5.activeCount == 0, r5.completedToday);

        // Stalled kitchen: nothing completing, oldest waiting 25 min -> ETA >= 25.
        DosaStats s6 = new DosaStats();
        s6.update(java.util.Collections.singletonList(prep(9, t0 - 25 * MIN)), t0, false);
        DosaStats.Result r6 = s6.compute(t0 + MIN);
        check("stalled eta", r6.etaMin >= 25, r6.etaMin);

        // Day rollover: today resets, yesterday goes to history.
        DosaStats.Result r7 = s.compute(at(19, 0) + 24 * 60 * MIN);
        check("rollover", r7.completedToday == 0 && r7.history.size() == 2, r7.history);

        // Totals, running pieces, kinds and facts.
        DosaStats s8 = new DosaStats();
        long t8 = at(19, 10);
        List<DosaStats.Entry> b8 = new ArrayList<DosaStats.Entry>();
        b8.add(new DosaStats.Entry(100, t8 - 5 * MIN, false, false, new String[]{"Masala Dosa", "Ghee Roast"}, new double[]{2, 1}));
        b8.add(new DosaStats.Entry(101, t8 - 4 * MIN, false, false, new String[]{"Masala Dosa"}, new double[]{3}));
        b8.add(new DosaStats.Entry(102, t8 - 9 * MIN, true, false, new String[]{"Onion Dosa"}, new double[]{1}));
        b8.add(new DosaStats.Entry(103, t8 - 9 * MIN, false, true, new String[]{"Masala Dosa"}, new double[]{4}));
        s8.update(b8, t8, false);
        s8.update(b8, t8 + 2000, false); // same board again must not double count
        DosaStats.Result r8 = s8.compute(t8 + 2000);
        check("today total", r8.dosasToday == 7, r8.dosasToday);
        check("running pieces", r8.runningDosas == 6, r8.runningDosas);
        check("kinds", r8.kindsToday == 3, r8.kindsToday);
        check("favourite fact", r8.facts.get(0).equals("Masala Dosa is today's favourite at Babai Tiffins"), r8.facts.get(0));
        b8.set(0, new DosaStats.Entry(100, t8 - 5 * MIN, true, false, new String[]{"Masala Dosa", "Ghee Roast"}, new double[]{2, 1}));
        s8.update(b8, t8 + 60_000, false);
        DosaStats.Result r8b = s8.compute(t8 + 60_000);
        check("served pieces", r8b.servedToday == 3 && r8b.runningDosas == 3, r8b.servedToday + "/" + r8b.runningDosas);
        boolean leak = false;
        for (String f : r8b.facts) leak |= f.contains("%") || f.contains("kg");
        check("no sensitive facts", !leak, r8b.facts);
        boolean hour = false;
        for (String f : r8.facts) hour |= f.contains("7\u20138 PM");
        check("busiest hour fact", hour, r8.facts);
        DosaStats s9 = new DosaStats();
        s9.load(s8.save(), t8 + 3000);
        check("totals persist", s9.compute(t8 + 3000).dosasToday == 7 && s9.compute(t8 + 3000).kindsToday == 3, "");
        // Trend: one point per 2 min, last 3 hours only.
        DosaStats s10 = new DosaStats();
        long t10 = at(12, 0);
        s10.update(java.util.Collections.singletonList(prep(500, t10 - 5 * MIN)), t10, false);
        for (int m = 0; m <= 240; m++) s10.compute(t10 + m * MIN);
        DosaStats.Result r10 = s10.compute(t10 + 240 * MIN);
        check("trend window", r10.trendTimes.length >= 89 && r10.trendTimes.length <= 92
                && r10.trendTimes[0] >= t10 + 60 * MIN, r10.trendTimes.length);
        DosaStats s11 = new DosaStats();
        s11.load(s10.save(), t10 + 241 * MIN);
        check("trend persists", s11.compute(t10 + 241 * MIN).trendTimes.length >= 89, "");

        // No Dosa data at all: no made-up number.
        DosaStats s12 = new DosaStats();
        DosaStats.Result r12 = s12.compute(t10);
        check("no data -> calculating", Double.isNaN(r12.etaMin) && r12.etaLabel.startsWith("Calculating"), r12.etaLabel);
        // Never above 40 min.
        DosaStats s13 = new DosaStats();
        s13.update(java.util.Collections.singletonList(prep(600, t10 - 75 * MIN)), t10, false);
        check("cap 40", s13.compute(t10).etaMin == 40.0, s13.compute(t10).etaMin);
        // Next morning, before any completion: yesterday's real average is used.
        long nextDay = at(19, 0) + 24 * 60 * MIN + 1;
        DosaStats s14 = new DosaStats();
        s14.load(s.save(), nextDay);
        DosaStats.Result r14 = s14.compute(nextDay);
        check("uses last real day avg", Math.abs(r14.etaMin - 16.9) < 0.11, r14.etaMin);

        check("hour labels", DosaStats.hourLabel(11).equals("11\u201312 PM") && DosaStats.hourLabel(0).equals("12\u20131 AM"),
                DosaStats.hourLabel(11) + " " + DosaStats.hourLabel(0));

        if (failures > 0) { System.out.println(failures + " failure(s)"); System.exit(1); }
        System.out.println("All DosaStats checks passed");
    }
}
