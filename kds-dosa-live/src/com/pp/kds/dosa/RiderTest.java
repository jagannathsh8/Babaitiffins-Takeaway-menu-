package com.pp.kds.dosa;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Test run for the rider board and calls (pure Java): 50 sample online orders, 30 ready and
 * 20 preparing. Every 10 s the oldest preparing order turns ready; every 20 s a rider picks up the
 * oldest ready one. Ends by itself after {@link #DURATION_MS}.
 */
final class RiderTest {

    static final long DURATION_MS = 5 * 60_000L;
    private static final String[] PLATFORMS = {"Swiggy", "Zomato", "Ownly", "Toing / Swiggy", "Swiggy", "Zomato"};

    private static final class Sim {
        long id, created;
        String platform, orderId, slot;
        boolean ready, gone;
        long goneAt;
    }

    private final List<Sim> sims = new ArrayList<Sim>();
    final long start, until;

    RiderTest(long now) {
        start = now;
        until = now + DURATION_MS;
        Random r = new Random(now);
        for (int i = 0; i < 50; i++) {
            Sim s = new Sim();
            s.id = 9_000_000_000L + i;
            s.platform = PLATFORMS[i % PLATFORMS.length];
            s.orderId = sampleId(s.platform, r, i);
            s.slot = String.valueOf(i + 1);
            s.ready = i < 30;
            s.created = now - (50 - i) * 45_000L; // oldest about 37 min ago
            sims.add(s);
        }
    }

    /** Platform-like order IDs; every 5th one ends with zeros so "zero" is heard. */
    private static String sampleId(String platform, Random r, int i) {
        StringBuilder b = new StringBuilder();
        if (platform.startsWith("Zomato")) b.append("85");
        else if (platform.startsWith("Ownly")) b.append("1791447658581");
        else b.append("25041");
        int len = platform.startsWith("Zomato") ? 10 : platform.startsWith("Ownly") ? 19 : 15;
        while (b.length() < len - 4) b.append(r.nextInt(10));
        String last = i % 5 == 0 ? "0" + r.nextInt(10) + "0" + r.nextInt(10) : String.format("%04d", r.nextInt(10000));
        return b.append(last).toString();
    }

    boolean running(long now) {
        return now < until;
    }

    long secondsLeft(long now) {
        return Math.max(0, (until - now) / 1000);
    }

    /** Advances the simulation and returns the orders as the board / calls see them. */
    List<RiderCalls.Order> step(long now) {
        long elapsed = now - start;
        int turned = (int) (elapsed / 10_000L);   // one more ready every 10 s
        int picked = (int) (elapsed / 20_000L);   // one picked up every 20 s
        int readyTarget = Math.min(50, 30 + turned);
        int countReady = 0, countPicked = 0;
        for (Sim s : sims) {
            if (s.ready) countReady++;
            if (s.gone) countPicked++;
        }
        for (Sim s : sims) { // oldest preparing first
            if (countReady >= readyTarget) break;
            if (!s.ready) {
                s.ready = true;
                countReady++;
            }
        }
        for (Sim s : sims) { // oldest ready first
            if (countPicked >= picked) break;
            if (s.ready && !s.gone) {
                s.gone = true;
                s.goneAt = now;
                countPicked++;
            }
        }
        List<RiderCalls.Order> out = new ArrayList<RiderCalls.Order>();
        for (Sim s : sims) {
            if (s.gone && now - s.goneAt > 3_000L) continue; // off the board once picked up
            out.add(new RiderCalls.Order(s.id, s.platform, RiderCalls.last4(s.orderId), s.orderId, s.slot,
                    s.created, s.ready, s.gone));
        }
        return out;
    }
}
