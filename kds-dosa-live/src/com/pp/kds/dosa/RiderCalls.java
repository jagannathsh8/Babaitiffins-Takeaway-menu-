package com.pp.kds.dosa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rider announcements for online / delivery orders (pure Java, no Android).
 *
 * Every KOT that is Food Ready is called out with its platform, the last 4 digits of the
 * platform order ID and the pickup slot: "Swiggy order 3 1 3 6, slot 47." New ones are called
 * at once; all waiting ones are repeated every {@link #REPEAT_MS} until the order is dispatched,
 * leaves the board, or {@link #MAX_MS} passes.
 */
final class RiderCalls {

    static final long GATHER_MS = 3_000L;        // ready within 3 s of each other -> one call
    static final long REPEAT_MS = 30_000L;       // repeat cycle
    static final long MAX_MS = 15 * 60_000L;     // stop calling an order after 15 min
    static final long MISSING_MS = 5_000L;       // board blip protection
    static final long STALE_MS = 45 * 60_000L;   // already ready + this old when first seen: skip
    static final int MAX_PER_CALL = 6;

    static final class Order {
        final long id;
        final String platform, last4, slot;
        final long createdMs;
        final boolean ready, gone;   // gone = dispatched / cancelled

        Order(long id, String platform, String last4, String slot, long createdMs, boolean ready, boolean gone) {
            this.id = id;
            this.platform = platform;
            this.last4 = last4;
            this.slot = slot;
            this.createdMs = createdMs;
            this.ready = ready;
            this.gone = gone;
        }
    }

    static final class Waiting {
        Order order;
        long readyAt, lastSaid, missingSince;
    }

    private final Map<Long, Waiting> waiting = new LinkedHashMap<Long, Waiting>();
    private final Set<Long> skipped = new HashSet<Long>();   // stale leftovers: never called
    private long lastCycle;
    private boolean initialised;
    /** True when the last non-empty due() result was new orders (play the chime). */
    boolean lastWasNew;

    void update(List<Order> board, long now) {
        Set<Long> seen = new HashSet<Long>();
        for (Order o : board) {
            seen.add(o.id);
            Waiting w = waiting.get(o.id);
            if (o.gone || !o.ready) {
                waiting.remove(o.id);
                if (!o.ready) skipped.remove(o.id);
                continue;
            }
            if (skipped.contains(o.id)) continue;
            if (w == null) {
                if (o.createdMs > 0 && now - o.createdMs > STALE_MS) {
                    skipped.add(o.id); // leftover from long ago, collected without Dispatch
                    continue;
                }
                w = new Waiting();
                w.readyAt = now;
                // Orders already ready when the app starts are not "new": they join the repeat cycle.
                w.lastSaid = initialised ? 0 : now;
                waiting.put(o.id, w);
            }
            w.order = o;
            w.missingSince = 0;
        }
        List<Long> drop = new ArrayList<Long>();
        for (Map.Entry<Long, Waiting> e : waiting.entrySet()) {
            Waiting w = e.getValue();
            if (now - w.readyAt >= MAX_MS) {
                drop.add(e.getKey());
                skipped.add(e.getKey());
            } else if (!seen.contains(e.getKey())) {
                if (w.missingSince == 0) w.missingSince = now;
                else if (now - w.missingSince >= MISSING_MS) drop.add(e.getKey());
            }
        }
        for (Long id : drop) waiting.remove(id);
        skipped.retainAll(seen);
        initialised = true;
    }

    /** Orders to call now: new ones (after the gather window), else the repeat cycle. */
    List<Waiting> due(long now) {
        List<Waiting> fresh = new ArrayList<Waiting>();
        long firstNew = Long.MAX_VALUE;
        for (Waiting w : waiting.values()) {
            if (w.lastSaid == 0) {
                fresh.add(w);
                firstNew = Math.min(firstNew, w.readyAt);
            }
        }
        if (!fresh.isEmpty() && now - firstNew >= GATHER_MS) {
            List<Waiting> out = oldestFirst(fresh);
            for (Waiting w : out) w.lastSaid = now;
            lastWasNew = true;
            return out;
        }
        if (now - lastCycle < REPEAT_MS) return Collections.emptyList();
        List<Waiting> all = new ArrayList<Waiting>();
        for (Waiting w : waiting.values()) if (w.lastSaid != 0) all.add(w);
        if (all.isEmpty()) return Collections.emptyList();
        // Least recently called first, so long queues rotate fairly.
        Collections.sort(all, new Comparator<Waiting>() {
            @Override public int compare(Waiting a, Waiting b) {
                int c = Long.compare(a.lastSaid, b.lastSaid);
                return c != 0 ? c : Long.compare(a.readyAt, b.readyAt);
            }
        });
        List<Waiting> out = new ArrayList<Waiting>(all.subList(0, Math.min(MAX_PER_CALL, all.size())));
        out = oldestFirst(out);
        for (Waiting w : out) w.lastSaid = now;
        lastCycle = now;
        lastWasNew = false;
        return out;
    }

    int waitingCount() {
        return waiting.size();
    }

    private static List<Waiting> oldestFirst(List<Waiting> l) {
        List<Waiting> out = new ArrayList<Waiting>(l);
        Collections.sort(out, new Comparator<Waiting>() {
            @Override public int compare(Waiting a, Waiting b) { return Long.compare(a.readyAt, b.readyAt); }
        });
        if (out.size() > MAX_PER_CALL) out = new ArrayList<Waiting>(out.subList(0, MAX_PER_CALL));
        return out;
    }

    /** "Swiggy order 3 1 3 6, slot 47. Ownly order 7 9 6 4, slot 20. Ready for pickup." */
    static String phrase(List<Waiting> call) {
        StringBuilder sb = new StringBuilder();
        for (Waiting w : call) {
            Order o = w.order;
            sb.append(o.platform).append(" order ");
            for (int i = 0; i < o.last4.length(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(o.last4.charAt(i));
            }
            if (o.slot != null && !o.slot.isEmpty()) sb.append(", slot ").append(o.slot);
            sb.append(". ");
        }
        sb.append("Ready for pickup.");
        return sb.toString();
    }

    /** Last 4 digits of the platform order ID ("1791447658581597964" -> "7964"). */
    static String last4(String orderId) {
        if (orderId == null) return "";
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < orderId.length(); i++) {
            char c = orderId.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        String d = digits.toString();
        return d.length() <= 4 ? d : d.substring(d.length() - 4);
    }
}
