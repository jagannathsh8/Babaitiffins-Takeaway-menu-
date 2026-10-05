import com.pp.kds.dosa.PrepStats;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Plain-JVM checks for PrepStats (owner's 60/20/20 rule, pace, holidays, history, export data). */
public final class PrepStatsTest {
    static final long MIN = 60_000L;
    static int failures;

    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!ok) failures++;
    }

    static long at(int month, int d, int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(2026, month - 1, d, h, m, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Target 0 = Peanut chutney (0.1 kg per Masala Dosa), target 1 = Dosa batter. */
    static PrepStats.Model model() {
        PrepStats.Model m = new PrepStats.Model();
        m.targets = new String[]{"OP - Peanut chutney", "OP - Dosa batter"};
        m.units = new String[]{"kg", "kg"};
        m.menu.put("masala dosa", new double[]{0.1, 0.11});
        m.menu.put("idli (2 pcs)", new double[]{0.1, 0});
        m.recipeNames = new String[][]{{"CP - Roasted Peanut"}, {}};
        m.recipeQty = new double[][]{{0.3}, {}};
        m.recipeUnits = new String[][]{{"kg"}, {}};
        return m;
    }

    /** Seed: peanut chutney flat `v` kg every hour 6..22 on `date`. */
    static void seed(PrepStats.Model m, String date, double v) {
        StringBuilder sb = new StringBuilder("T|" + date + "|0|");
        for (int h = 6; h <= 22; h++) sb.append(h == 6 ? "" : " ").append(h).append(':').append(v);
        m.addSeedLine(sb.toString());
    }

    static PrepStats.Kot kot(long id, long t, int type, String item, double q) {
        return new PrepStats.Kot(id, t, type, false, new String[]{item}, new double[]{q});
    }

    public static void main(String[] a) {
        // Monday 5 Oct 2026. Same weekday back: 28 Sep (1w), 21 Sep (2w), 14 Sep (3w), 7 Sep (4w), 31 Aug (5w)
        PrepStats.Model m = model();
        seed(m, "2026-09-28", 10);
        seed(m, "2026-09-21", 20);
        seed(m, "2026-09-14", 30);
        seed(m, "2026-09-07", 40);
        PrepStats s = new PrepStats(m);
        PrepStats.Result r = s.compute(at(10, 5, 9, 0));
        double expect = 0.6 * 40 + 0.2 * (10 + 20 + 30 + 40) / 4 + 0.2 * (10 + 20) / 2;   // 32
        check("60/20/20 rule", Math.abs(r.items[0].typicalNextHour - expect) < 1e-9, r.items[0].typicalNextHour);
        check("sources = same weekday 1-4 weeks back", r.sources.toString().equals("[2026-09-28, 2026-09-21, 2026-09-14, 2026-09-07]"), r.sources);

        // Missing "last month" -> weights renormalised over the 20/20 parts
        PrepStats.Model m2 = model();
        seed(m2, "2026-09-28", 10);
        seed(m2, "2026-09-21", 20);
        seed(m2, "2026-09-14", 30);
        double typ3 = new PrepStats(m2).compute(at(10, 5, 9, 0)).items[0].typicalNextHour;
        check("renormalised with 3 sources", Math.abs(typ3 - (0.2 * 20 + 0.2 * 15) / 0.4) < 1e-9, typ3);

        // Holiday in history is skipped -> next older same weekday is used
        PrepStats.Model m3 = model();
        seed(m3, "2026-09-28", 10);
        seed(m3, "2026-09-21", 20);
        seed(m3, "2026-09-14", 300);   // Ganesh Chaturthi
        seed(m3, "2026-09-07", 40);
        seed(m3, "2026-08-31", 50);
        m3.holidays.add("2026-09-14");
        PrepStats.Result r3 = new PrepStats(m3).compute(at(10, 5, 9, 0));
        check("holiday source skipped", r3.sources.toString().equals("[2026-09-28, 2026-09-21, 2026-09-07, 2026-08-31]"), r3.sources);

        // Holiday today -> past Sundays (Fri 2 Oct: 27 Sep, 20 Sep, 13 Sep, 6 Sep)
        PrepStats.Model m4 = model();
        for (String d : new String[]{"2026-09-27", "2026-09-20", "2026-09-13", "2026-09-06"}) seed(m4, d, 70);
        seed(m4, "2026-09-25", 30);
        PrepStats s4 = new PrepStats(m4);
        s4.setHolidayToday(true, at(10, 2, 6, 0));
        PrepStats.Result r4 = s4.compute(at(10, 2, 9, 0));
        check("holiday today uses Sundays", r4.sources.toString().equals("[2026-09-27, 2026-09-20, 2026-09-13, 2026-09-06]")
                && Math.abs(r4.items[0].typicalNextHour - 70) < 1e-9, r4.sources);

        // Pace: last 2 hours 40% above typical -> next hour +20%
        PrepStats sp = new PrepStats(m);
        sp.compute(at(10, 5, 6, 0));                       // tablet watching since 6 AM
        List<PrepStats.Kot> board = new ArrayList<PrepStats.Kot>();
        long id = 1;
        for (int h = 7; h < 9; h++) {
            for (int k = 0; k < 12; k++) {                 // typical 32 kg/h -> 44.8 kg/h actual
                board.add(kot(id++, at(10, 5, h, k * 5), id % 3 == 0 ? 2 : 0, "Masala Dosa", 32 * 1.4 / 12 / 0.1));
            }
        }
        sp.update(board, at(10, 5, 9, 0));
        PrepStats.Item ip = sp.compute(at(10, 5, 9, 0)).items[0];
        check("pace 1.4", Math.abs(ip.pace - 1.4) < 1e-6, ip.pace);
        check("next hour +20%", Math.abs(ip.nextHour - 32 * 1.2) < 1e-6, ip.nextHour);
        check("used today / last hour", Math.abs(ip.usedToday - 89.6) < 1e-6 && Math.abs(ip.lastHour - 44.8) < 1e-6,
                ip.usedToday + " / " + ip.lastHour);
        check("vs typical so far (typical from 6 AM)", Math.abs(ip.vsTypical - 89.6 / 96) < 1e-6, ip.vsTypical);

        // Pace not applied when the tablet only started watching recently
        PrepStats late = new PrepStats(m);
        late.update(board, at(10, 5, 8, 50));
        PrepStats.Item il = late.compute(at(10, 5, 9, 0)).items[0];
        check("no pace without 2 h of watching", Double.isNaN(il.pace) && Math.abs(il.nextHour - 32) < 1e-9, il.nextHour);

        // Counting rules
        PrepStats sc = new PrepStats(m);
        List<PrepStats.Kot> b2 = new ArrayList<PrepStats.Kot>();
        b2.add(kot(1, at(10, 5, 8, 0), 0, "Masala Dosa", 10));
        b2.add(kot(2, at(10, 5, 8, 5), 1, "Idli (2 Pcs)", 5));
        b2.add(kot(3, at(10, 5, 8, 10), 2, "Gobi Manchurian", 2));
        b2.add(new PrepStats.Kot(4, at(10, 5, 8, 10), 0, true, new String[]{"Masala Dosa"}, new double[]{99}));
        b2.add(kot(5, at(10, 4, 22, 0), 0, "Masala Dosa", 50));   // yesterday
        sc.update(b2, at(10, 5, 8, 30));
        sc.update(b2, at(10, 5, 8, 31));                          // same board again
        PrepStats.Result rc = sc.compute(at(10, 5, 8, 31));
        check("counts all order types once", rc.kotsByType[0] == 1 && rc.kotsByType[1] == 1 && rc.kotsByType[2] == 1,
                java.util.Arrays.toString(rc.kotsByType));
        check("usage via BOM", Math.abs(rc.items[0].usedToday - 1.5) < 1e-9, rc.items[0].usedToday);
        check("unmatched listed", rc.unmatched.size() == 1 && rc.unmatched.get(0).startsWith("Gobi Manchurian"), rc.unmatched);
        check("drivers", rc.items[0].drivers.get(0).startsWith("Masala Dosa|"), rc.items[0].drivers);
        check("breakdown", rc.items[0].breakdown.get(0).startsWith("CP - Roasted Peanut|"), rc.items[0].breakdown);

        // Batches
        sc.addBatch(0, 2, at(10, 5, 8, 31));
        sc.update(Collections.singletonList(kot(6, at(10, 5, 8, 40), 0, "Masala Dosa", 5)), at(10, 5, 8, 41));
        PrepStats.Item ib = sc.compute(at(10, 5, 8, 41)).items[0];
        check("remaining after batch", Math.abs(ib.remaining - 1.5) < 1e-9, ib.remaining);
        check("prepare now (typical 32 kg/h)", ib.status == 3, ib.status + " " + ib.minutesLeft);
        check("undo", sc.undoBatch(0) && sc.compute(at(10, 5, 8, 42)).items[0].made == 0, "");

        // Export data for today
        PrepStats.Day today = sc.day("2026-10-05", at(10, 5, 8, 42));
        check("today export: dishes", today.today && today.dishes.get("Masala Dosa")[8] == 15 && today.dishes.get("Gobi Manchurian")[8] == 2,
                today.dishes.keySet());
        check("today export: prep", Math.abs(today.prep[0][8] - 2.0) < 1e-9, today.prep[0][8]);

        // Persistence of today
        Map<String, String> saved = sc.save();
        PrepStats sr = new PrepStats(m);
        sr.load(saved, at(10, 5, 8, 43));
        check("today survives restart", Math.abs(sr.compute(at(10, 5, 8, 43)).items[0].usedToday - 2.0) < 1e-9
                && sr.day("2026-10-05", at(10, 5, 8, 43)).dishes.get("Masala Dosa")[8] == 15, "");
        sr.update(b2, at(10, 5, 8, 44));
        check("seen KOTs survive restart", Math.abs(sr.compute(at(10, 5, 8, 44)).items[0].usedToday - 2.0) < 1e-9, "");

        // Rollover archives a complete own day; it is used as a source ahead of the seed
        PrepStats so = new PrepStats(m);
        so.compute(at(10, 5, 6, 0));
        List<PrepStats.Kot> full = new ArrayList<PrepStats.Kot>();
        for (int h = 7; h <= 22; h++) full.add(kot(1000 + h, at(10, 5, h, 0), 0, "Masala Dosa", 100)); // 10 kg/h
        so.update(full, at(10, 5, 22, 30));
        so.compute(at(10, 5, 22, 55));
        so.compute(at(10, 6, 0, 1));                       // midnight -> archive
        String blob = so.historyBlob();
        check("history has complete day", blob.contains("C|2026-10-05") && blob.contains("D|2026-10-05|Masala Dosa|"), blob.length());
        PrepStats sn = new PrepStats(m);
        sn.loadHistory(blob);
        PrepStats.Result rn = sn.compute(at(10, 12, 9, 0));  // next Monday
        check("own complete day is a source", rn.sources.get(0).equals("2026-10-05"), rn.sources);
        PrepStats.Day past = sn.day("2026-10-05", at(10, 12, 9, 0));
        check("past day export from tablet", past.source.startsWith("Tablet") && past.complete && past.dishes.get("Masala Dosa")[9] == 100,
                past.source);
        List<String> dates = sn.availableDates(at(10, 12, 9, 0));
        check("available dates newest first", dates.get(0).equals("2026-10-12") && dates.contains("2026-10-05")
                && dates.contains("2026-09-07"), dates);
        PrepStats.Day seedDay = sn.day("2026-09-07", at(10, 12, 9, 0));
        check("seed day export", seedDay.source.startsWith("Petpooja") && Math.abs(seedDay.prep[0][9] - 40) < 1e-9, seedDay.source);

        // Own history keeps 42 dates
        PrepStats sh = new PrepStats(m);
        for (int d = 0; d < 50; d++) {
            long t = at(8, 1, 9, 0) + d * 24L * 60 * MIN;
            sh.update(Collections.singletonList(kot(5000 + d, t - MIN, 0, "Masala Dosa", 1)), t);
            sh.compute(t);
        }
        sh.compute(at(8, 1, 9, 0) + 51L * 24 * 60 * MIN);
        java.util.Set<String> ds = new java.util.HashSet<String>();
        for (String line : sh.historyBlob().split("\n")) if (line.startsWith("T|")) ds.add(line.split("\\|")[1]);
        check("history keeps 42 days", ds.size() == 42, ds.size());

        if (failures > 0) { System.out.println(failures + " failure(s)"); System.exit(1); }
        System.out.println("All PrepStats checks passed");
    }
}
