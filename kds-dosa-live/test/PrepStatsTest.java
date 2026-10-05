import com.pp.kds.dosa.PrepStats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

/** Plain-JVM checks for PrepStats. */
public final class PrepStatsTest {
    static final long MIN = 60_000L;
    static int failures;

    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!ok) failures++;
    }

    static long at(int d, int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.OCTOBER, d, h, m, 0); // Oct 5 2026 = Monday
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    static PrepStats.Model model() {
        PrepStats.Model m = new PrepStats.Model();
        m.targets = new String[]{"Peanut Chutney", "Dosa Batter"};
        m.menu.put("masala dosa", new double[]{0.1, 0.11});
        m.menu.put("idli (2 pcs)", new double[]{0.1, 0});
        m.recipeNames = new String[][]{{"Roasted Peanut", "Green chilli"}, {}};
        m.recipeQty = new double[][]{{0.3, 0.03}, {}};
        m.recipeUnits = new String[][]{{"kg", "kg"}, {}};
        m.profile = new double[2][7][24];
        for (int wd = 0; wd < 7; wd++) Arrays.fill(m.profile[0][wd], 6.0); // 6 kg/h typical
        return m;
    }

    static PrepStats.Kot kot(long id, long t, int type, String item, double q) {
        return new PrepStats.Kot(id, t, type, false, new String[]{item}, new double[]{q});
    }

    public static void main(String[] a) {
        PrepStats s = new PrepStats(model());
        long t0 = at(5, 9, 0);
        List<PrepStats.Kot> board = new ArrayList<PrepStats.Kot>();
        // 120 masala dosas in the last hour (12 kg peanut chutney), 10 idli, 1 unknown, 1 cancelled
        for (int i = 0; i < 60; i++) board.add(kot(i, t0 - 60 * MIN + i * MIN, i % 3, "Masala Dosa", 2));
        board.add(kot(100, t0 - 10 * MIN, 0, "Idli (2 Pcs)", 10));
        board.add(kot(101, t0 - 5 * MIN, 0, "Gobi Manchurian", 1));
        board.add(new PrepStats.Kot(102, t0 - 5 * MIN, 0, true, new String[]{"Masala Dosa"}, new double[]{50}));
        s.update(board, t0);
        s.update(board, t0 + 2000); // same board again: no double count
        PrepStats.Result r = s.compute(t0 + 2000);
        PrepStats.Item pc = r.items[0];
        check("used today", Math.abs(pc.usedToday - 13.0) < 1e-6, pc.usedToday);
        check("last hour (12 x 5-min buckets)", Math.abs(pc.lastHour - 12.0) < 1e-6, pc.lastHour);
        check("all order types counted", r.kotsByType[0] + r.kotsByType[1] + r.kotsByType[2] == 62, Arrays.toString(r.kotsByType));
        check("unmatched listed", r.unmatched.size() == 1 && r.unmatched.get(0).startsWith("Gobi Manchurian"), r.unmatched);
        check("drivers", pc.drivers.get(0).startsWith("Masala Dosa|"), pc.drivers);
        // busier than typical (13 vs 6) -> projection scaled up, above typical, below 2x+trend
        check("projection scales with rush", pc.nextHour > 9 && pc.nextHour < 16, pc.nextHour);
        check("breakdown uses projection", pc.breakdown.get(0).startsWith("Roasted Peanut|"), pc.breakdown.get(0));
        double bd = Double.parseDouble(pc.breakdown.get(0).split("\\|")[1]);
        check("breakdown qty", Math.abs(bd - 0.3 * pc.nextHour) < 1e-6, bd);
        check("no batch -> status 0", pc.status == 0, pc.status);

        // Batches: 10 kg made now, then 40 more masala dosas (4 kg) in 20 min
        s.addBatch(0, 10, t0 + 2000);
        List<PrepStats.Kot> more = new ArrayList<PrepStats.Kot>(board);
        for (int i = 0; i < 20; i++) more.add(kot(200 + i, t0 + i * MIN, 1, "Masala Dosa", 2));
        s.update(more, t0 + 20 * MIN);
        PrepStats.Item p2 = s.compute(t0 + 20 * MIN).items[0];
        check("remaining = made - used since batch", Math.abs(p2.remaining - 6.0) < 1e-6, p2.remaining);
        check("minutes left = remaining / pace", Math.abs(p2.minutesLeft - 30.0) < 0.5 && p2.status == 1, p2.status + " " + p2.minutesLeft);
        List<PrepStats.Kot> rush = new ArrayList<PrepStats.Kot>(more);
        for (int i = 0; i < 25; i++) rush.add(kot(300 + i, t0 + 20 * MIN, 2, "Masala Dosa", 2)); // +5 kg
        s.update(rush, t0 + 21 * MIN);
        PrepStats.Item p4 = s.compute(t0 + 21 * MIN).items[0];
        check("prepare now when nearly out", p4.status == 3 && p4.remaining < 1.01, p4.status + " " + p4.remaining);
        check("undo", s.undoBatch(0) && s.compute(t0 + 20 * MIN).items[0].made == 0, "");

        // Persistence round trip
        s.addBatch(0, 5, t0 + 21 * MIN);
        Map<String, String> saved = s.save();
        PrepStats s2 = new PrepStats(model());
        s2.load(saved, t0 + 22 * MIN);
        PrepStats.Item p3 = s2.compute(t0 + 22 * MIN).items[0];
        check("persist usage", Math.abs(p3.usedToday - 22.0) < 1e-6, p3.usedToday);
        check("persist batch", p3.made == 5, p3.made);
        s2.update(more, t0 + 23 * MIN);
        check("persist seen ids", Math.abs(s2.compute(t0 + 23 * MIN).items[0].usedToday - 22.0) < 1e-6, "");

        // Next day: today resets, Monday's real usage blends into Monday's typical profile next week
        PrepStats.Result tue = s2.compute(at(6, 9, 0));
        check("rollover resets", tue.items[0].usedToday == 0 && tue.items[0].made == 0, "");
        PrepStats.Result mon = s2.compute(at(12, 8, 0)); // next Monday
        double typ8 = mon.items[0].typicalHourly[8];
        check("own history blends into typical", Math.abs(typ8 - (0.5 * 6 + 0.5 * 13.0)) < 1e-6, typ8);
        check("idle -> projection = typical next hour", Math.abs(mon.items[0].nextHour - mon.items[0].typicalNextHour) < 1e-6,
                mon.items[0].nextHour);

        // Yesterday's KOTs still on the board are not counted today
        PrepStats s3 = new PrepStats(model());
        s3.update(java.util.Collections.singletonList(kot(1, at(4, 22, 0), 0, "Masala Dosa", 1)), at(5, 7, 0));
        check("old KOTs ignored", s3.compute(at(5, 7, 0)).items[0].usedToday == 0, "");

        if (failures > 0) { System.out.println(failures + " failure(s)"); System.exit(1); }
        System.out.println("All PrepStats checks passed");
    }
}
