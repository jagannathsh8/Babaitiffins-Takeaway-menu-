package com.pp.kds.dosa;

import java.util.ArrayList;
import java.util.List;

/** Plain-JVM checks for RiderCalls. Run: see build.sh (exits non-zero on failure). */
public final class RiderCallsTest {
    static int failures;

    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!ok) failures++;
    }

    static RiderCalls.Order o(long id, String p, String oid, String slot, long created, boolean ready, boolean gone) {
        return new RiderCalls.Order(id, p, RiderCalls.last4(oid), slot, created, ready, gone);
    }

    static String ids(List<RiderCalls.Waiting> l) {
        StringBuilder sb = new StringBuilder();
        for (RiderCalls.Waiting w : l) sb.append(sb.length() == 0 ? "" : ",").append(w.order.slot);
        return sb.toString();
    }

    public static void main(String[] a) {
        long T = 10_000_000_000L;
        check("last4", RiderCalls.last4("1791447658581597964").equals("7964"), RiderCalls.last4("1791447658581597964"));
        check("last4 short", RiderCalls.last4("A12").equals("12"), RiderCalls.last4("A12"));

        RiderCalls rc = new RiderCalls();
        rc.setMaxMinutes(15);
        List<RiderCalls.Order> board = new ArrayList<RiderCalls.Order>();
        board.add(o(1, "Swiggy", "250412237103136", "47", T - 5 * 60_000L, true, false));    // ready at start
        board.add(o(2, "Zomato", "88231182", "12", T - 120 * 60_000L, true, false));         // stale leftover
        board.add(o(3, "Ownly", "1791447658581597964", "20", T - 3 * 60_000L, false, false));
        rc.update(board, T);
        check("start: no 'new' call", rc.due(T + 4_000).size() <= 1, ids(rc.due(T + 4_000)));
        // (first repeat cycle may include the already-ready Swiggy order)
        rc = new RiderCalls();
        rc.setMaxMinutes(15);
        rc.update(board, T);
        List<RiderCalls.Waiting> first = rc.due(T + 1);
        check("start: repeat cycle has ready order only", ids(first).equals("47"), ids(first));

        // Ownly turns ready -> new call after gather window, with chime-worthy phrase.
        board.set(2, o(3, "Ownly", "1791447658581597964", "20", T - 3 * 60_000L, true, false));
        rc.update(board, T + 10_000);
        check("gather", rc.due(T + 11_000).isEmpty(), "");
        List<RiderCalls.Waiting> fresh = rc.due(T + 13_100);
        check("new call", ids(fresh).equals("20"), ids(fresh));
        check("phrase", RiderCalls.phrase(fresh).equals("Ownly order 7 9 6 4, slot 20. Ready for pickup."),
                RiderCalls.phrase(fresh));

        // Repeat cycle every 30 s with both waiting orders, oldest ready first.
        List<RiderCalls.Waiting> rep = rc.due(T + 31_000);
        check("repeat", ids(rep).equals("47,20"), ids(rep));
        check("no repeat before 30 s", rc.due(T + 40_000).isEmpty(), "");

        // Dispatched -> stops being called.
        board.set(0, o(1, "Swiggy", "250412237103136", "47", T - 5 * 60_000L, true, true));
        rc.update(board, T + 50_000);
        List<RiderCalls.Waiting> rep2 = rc.due(T + 62_000);
        check("dispatched removed", ids(rep2).equals("20"), ids(rep2));

        // Stops after 15 minutes even without dispatch.
        rc.update(board, T + 10_000 + RiderCalls.MAX_MS);
        check("max 15 min", rc.waitingCount() == 0, rc.waitingCount());

        // Default: until picked up (no time limit).
        RiderCalls u = new RiderCalls();
        List<RiderCalls.Order> b3 = new ArrayList<RiderCalls.Order>();
        b3.add(o(5, "Zomato", "1182", "12", T, true, false));
        u.update(b3, T);
        u.update(b3, T + 3 * 3600_000L);
        check("until picked up", u.waitingCount() == 1, u.waitingCount());
        b3.set(0, o(5, "Zomato", "1182", "12", T, true, true));
        u.update(b3, T + 3 * 3600_000L + 1000);
        check("picked up removes", u.waitingCount() == 0, u.waitingCount());

        // Gone from the board (picked up / released) after the 5 s grace.
        RiderCalls g = new RiderCalls();
        List<RiderCalls.Order> b2 = new ArrayList<RiderCalls.Order>();
        b2.add(o(9, "Swiggy", "5343", "40", T, true, false));
        g.update(b2, T);
        b2.clear();
        g.update(b2, T + 1_000);
        check("blip kept", g.waitingCount() == 1, g.waitingCount());
        g.update(b2, T + 7_000);
        check("gone removed", g.waitingCount() == 0, g.waitingCount());

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
