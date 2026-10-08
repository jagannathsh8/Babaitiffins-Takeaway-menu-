package com.pp.kds.dosa;

import java.util.List;

/** Plain-JVM checks for the rider test run. */
public final class RiderTestTest {
    public static void main(String[] a) {
        int failures = 0;
        long T = 10_000_000_000L;
        RiderTest t = new RiderTest(T);
        List<RiderCalls.Order> o = t.step(T);
        int ready = 0, prep = 0;
        for (RiderCalls.Order x : o) if (x.ready) ready++; else prep++;
        boolean ok = o.size() == 50 && ready == 30 && prep == 20;
        System.out.println((ok ? "PASS" : "FAIL") + " start 50/30/20 " + o.size() + "/" + ready + "/" + prep);
        if (!ok) failures++;
        RiderCalls rc = new RiderCalls();
        rc.update(o, T);
        List<RiderCalls.Order> o2 = t.step(T + 65_000L);
        rc.update(o2, T + 65_000L);
        ready = 0;
        for (RiderCalls.Order x : o2) if (x.ready && !x.gone) ready++;
        ok = ready == 30 + 6 - 3; // +6 turned ready, -3 picked up
        System.out.println((ok ? "PASS" : "FAIL") + " after 65 s ready=" + ready);
        if (!ok) failures++;
        boolean zero = false;
        for (RiderCalls.Order x : o) if (x.last4.contains("0")) zero = true;
        System.out.println((zero ? "PASS" : "FAIL") + " some ids contain zeros, e.g. " + o.get(0).platform + " " + o.get(0).last4);
        if (!zero) failures++;
        System.out.println(RiderCalls.phrase(rc.due(T + 70_000L)));
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
