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
        {   // brand order: Swiggy, Zomato, others by name; order inside a brand kept
            List<RiderCalls.Order> l = new ArrayList<RiderCalls.Order>();
            l.add(o(1, "Ownly", "1111", "", 1, false, false));
            l.add(o(2, "Zomato", "2222", "", 2, false, false));
            l.add(o(3, "Swiggy", "3333", "", 3, false, false));
            l.add(o(4, "Magicpin", "4444", "", 4, false, false));
            l.add(o(5, "Swiggy", "5555", "", 5, false, false));
            l.add(o(6, "Zomato", "6666", "", 6, false, false));
            StringBuilder sb = new StringBuilder();
            for (RiderCalls.Order x : RiderCalls.byBrand(l)) sb.append(x.id);
            check("brand order", sb.toString().equals("352641"), sb);
        }
        {   // search on the rider board
            RiderCalls.Order r1 = new RiderCalls.Order(1, "Swiggy", "3136", "250412237103136", "47", 0, true, false);
            RiderCalls.Order p1 = new RiderCalls.Order(2, "Zomato", "3190", "88233190", "", 0, false, false);
            RiderCalls.Order p2 = new RiderCalls.Order(3, "Ownly", "7964", "1791447658581597964", "20", 0, false, false);
            RiderCalls.Waiting w = new RiderCalls.Waiting();
            w.order = r1;
            List<RiderCalls.Waiting> rl = new ArrayList<RiderCalls.Waiting>();
            rl.add(w);
            List<RiderCalls.Order> pl = new ArrayList<RiderCalls.Order>();
            pl.add(p1);
            pl.add(p2);
            List<RiderCalls.Hit> h = RiderCalls.find("3136", rl, pl);
            check("search last 4", h.size() == 1 && h.get(0).ready && h.get(0).order.slot.equals("47"), h.size());
            h = RiderCalls.find("31", rl, pl);
            check("search prefix: both, ready first", h.size() == 2 && h.get(0).ready, h.size());
            h = RiderCalls.find("3190", rl, pl);
            check("search preparing", h.size() == 1 && !h.get(0).ready && h.get(0).order.platform.equals("Zomato"), h.size());
            check("search more digits", RiderCalls.find("597964", rl, pl).size() == 1, "");
            check("search none", RiderCalls.find("5555", rl, pl).isEmpty() && RiderCalls.find("", rl, pl).isEmpty(), "");
        }
        long T = 10_000_000_000L;
        check("last4", RiderCalls.last4("1791447658581597964").equals("7964"), RiderCalls.last4("1791447658581597964"));
        check("last4 short", RiderCalls.last4("A12").equals("12"), RiderCalls.last4("A12"));

        RiderCalls rc = new RiderCalls();
        rc.skipStale = true;   // the old leftover rule (off on the TV now), still tested here
        rc.setMaxMinutes(15);
        List<RiderCalls.Order> board = new ArrayList<RiderCalls.Order>();
        board.add(o(1, "Swiggy", "250412237103136", "47", T - 5 * 60_000L, true, false));    // ready at start
        board.add(o(2, "Zomato", "88231182", "12", T - 120 * 60_000L, true, false));         // stale leftover
        board.add(o(3, "Ownly", "1791447658581597964", "20", T - 3 * 60_000L, false, false));
        rc.update(board, T);
        check("start: no 'new' call", rc.due(T + 4_000).size() <= 1, ids(rc.due(T + 4_000)));
        // (first repeat cycle may include the already-ready Swiggy order)
        rc = new RiderCalls();
        rc.skipStale = true;
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
        check("phrase", RiderCalls.phrase(fresh).equals("Ownly 7 9 6 4, slot 20."),
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

        RiderCalls.Waiting zw = new RiderCalls.Waiting();
        zw.order = o(77, "Zomato", "0391", "64", T, true, false);
        check("zero said as zero", RiderCalls.phrase(java.util.Arrays.asList(zw)).equals("Zomato zero 3 9 1, slot 64."),
                RiderCalls.phrase(java.util.Arrays.asList(zw)));

        // Busy: 40 ready -> groups of 6 back to back (~19.6 s apart), every order heard within ~2.5 min.
        RiderCalls busy = new RiderCalls();
        List<RiderCalls.Order> b40 = new ArrayList<RiderCalls.Order>();
        for (int i = 0; i < 40; i++) b40.add(o(1000 + i, "Swiggy", "1" + (1000 + i), String.valueOf(i + 1), T, true, false));
        busy.update(b40, T);
        java.util.Set<String> heard = new java.util.HashSet<String>();
        long tt = T;
        int calls = 0;
        while (tt < T + 150_000L) {
            List<RiderCalls.Waiting> got = busy.due(tt);
            if (!got.isEmpty()) {
                calls++;
                for (RiderCalls.Waiting w : got) heard.add(w.order.slot);
            }
            tt += 1_000L;
        }
        check("busy: all 40 heard within 150 s", heard.size() == 40, heard.size() + " in " + calls + " calls");
        check("busy: groups of 6", calls >= 7, calls);

        // Default: until picked up (no time limit).
        RiderCalls u = new RiderCalls();
        List<RiderCalls.Order> b3 = new ArrayList<RiderCalls.Order>();
        b3.add(o(5, "Zomato", "1182", "12", T, true, false));
        u.update(b3, T);
        u.update(b3, T + 170 * 60_000L);
        check("until picked up", u.waitingCount() == 1, u.waitingCount());
        b3.set(0, o(5, "Zomato", "1182", "12", T, true, true));
        u.update(b3, T + 170 * 60_000L + 1000);
        check("picked up removes", u.waitingCount() == 0, u.waitingCount());
        check("removal reason", u.removals().get(0).reason.contains("dispatched"), u.removals().get(0).reason);

        // Off the KDS board: kept through a refresh / blip, removed after 2 minutes.
        RiderCalls g = new RiderCalls();
        List<RiderCalls.Order> b2 = new ArrayList<RiderCalls.Order>();
        b2.add(o(9, "Swiggy", "5343", "40", T, true, false));
        g.update(b2, T);
        b2.clear();
        g.update(b2, T + 1_000);
        g.update(b2, T + 90_000);
        check("blip kept (90 s)", g.waitingCount() == 1, g.waitingCount());
        g.update(b2, T + 122_000);
        check("gone after 2 min", g.waitingCount() == 0 && g.removals().get(0).reason.contains("KDS board"), g.waitingCount());

        // 20 ready orders; Petpooja's answer suddenly holds only 6 of them (short / cut answer):
        // the other 14 stay on the rider screen, and come back normally with the next full answer.
        RiderCalls m = new RiderCalls();
        List<RiderCalls.Order> full = new ArrayList<RiderCalls.Order>();
        for (int i = 0; i < 20; i++) full.add(o(100 + i, "Swiggy", "77" + (10 + i), String.valueOf(i), T, true, false));
        m.update(full, T);
        List<RiderCalls.Order> cut = new ArrayList<RiderCalls.Order>(full.subList(14, 20));
        m.update(cut, T + 2_000);
        m.update(cut, T + 5 * 60_000L);
        check("many vanish at once: all kept after 5 min", m.waitingCount() == 20, m.waitingCount());
        m.update(full, T + 6 * 60_000L);
        check("back with the full answer", m.waitingCount() == 20 && m.removedCount() == 0, m.waitingCount());
        // ...and if they truly never come back, they go after 20 min, not 2.
        m.update(cut, T + 7 * 60_000L);
        m.update(cut, T + 7 * 60_000L + 19 * 60_000L);
        check("held 19 min", m.waitingCount() == 20, m.waitingCount());
        m.update(cut, T + 7 * 60_000L + 21 * 60_000L);
        check("cleared after 20 min", m.waitingCount() == 6, m.waitingCount());
        // One rider pickup among many still clears in 2 min.
        RiderCalls one = new RiderCalls();
        one.update(full, T);
        one.update(new ArrayList<RiderCalls.Order>(full.subList(1, 20)), T + 1_000);
        one.update(new ArrayList<RiderCalls.Order>(full.subList(1, 20)), T + 122_000);
        check("single pickup: 2 min", one.waitingCount() == 19, one.waitingCount());

        // Old orders are never hidden for their age (busy mornings: placed > 45 min ago, ready now).
        RiderCalls old = new RiderCalls();
        List<RiderCalls.Order> bo = new ArrayList<RiderCalls.Order>();
        bo.add(o(11, "Swiggy", "7001", "3", T - 90 * 60_000L, true, false));
        old.update(bo, T);
        check("old order shown", old.waitingCount() == 1, old.waitingCount());

        // Safety: ready 3 hours without dispatch -> cleared.
        old.update(bo, T + RiderCalls.SAFETY_MS);
        check("3 h safety", old.waitingCount() == 0 && old.removals().get(0).reason.contains("3 hours"), old.waitingCount());

        // Ready times survive a restart.
        RiderCalls a1 = new RiderCalls();
        List<RiderCalls.Order> br = new ArrayList<RiderCalls.Order>();
        br.add(new RiderCalls.Order(21, "Zomato", "1182", "8723451182", "12", T, true, false));
        a1.update(br, T);
        java.util.Map<String, Long> saved = a1.readyTimes();
        RiderCalls a2 = new RiderCalls();
        a2.restoreReadyTimes(saved);
        a2.update(br, T + 7 * 60_000L);
        check("ready time kept after restart", a2.readyList().get(0).readyAt == T, a2.readyList().get(0).readyAt - T);
        check("restored order is not called as new", a2.due(T + 7 * 60_000L + 4_000).isEmpty() || !a2.lastWasNew, "");

        // Babai Hub knows when an order turned ready (TV restarted / just switched on): timer kept, no "new" call.
        RiderCalls h = new RiderCalls();
        h.update(new ArrayList<RiderCalls.Order>(), T);
        RiderCalls.Order ho = new RiderCalls.Order(31, "Swiggy", "0744", "250787919580744", "40", T - 20 * 60_000L, true, false);
        ho.readyAtHint = T - 9 * 60_000L;
        List<RiderCalls.Order> hb = new ArrayList<RiderCalls.Order>();
        hb.add(ho);
        h.update(hb, T + 1_000);
        check("hub ready time used", h.readyList().get(0).readyAt == T - 9 * 60_000L, h.readyList().get(0).readyAt - T);
        check("hub-ready order not called as new", h.due(T + 5_000).isEmpty() || !h.lastWasNew, "");
        // just turned ready on the hub: still a new call
        RiderCalls h2 = new RiderCalls();
        h2.update(new ArrayList<RiderCalls.Order>(), T);
        RiderCalls.Order hn = new RiderCalls.Order(32, "Zomato", "1046", "8496851046", "7", T - 60_000L, true, false);
        hn.readyAtHint = T + 1_000 - 3_000;
        List<RiderCalls.Order> hb2 = new ArrayList<RiderCalls.Order>();
        hb2.add(hn);
        h2.update(hb2, T + 1_000);
        check("fresh hub-ready order is new", h2.due(T + 5_000).size() == 1 && h2.lastWasNew, "");

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
