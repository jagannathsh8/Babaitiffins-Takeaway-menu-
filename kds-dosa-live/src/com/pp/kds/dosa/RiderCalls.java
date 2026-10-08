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
    static final long MAX_MS = 15 * 60_000L;     // e.g. 15 min (when a limit is chosen)
    static final long MISSING_MS = 5_000L;       // board blip protection
    static final long STALE_MS = 45 * 60_000L;   // already ready + this old when first seen: skip
    static final int MAX_PER_CALL = 6;

    static final class Order {
        final long id;
        final String platform, last4, orderId;
        String slot;   // Bridge Print pickup slot, filled in when known
        final long createdMs;
        final boolean ready, gone;   // gone = dispatched / cancelled

        Order(long id, String platform, String last4, String slot, long createdMs, boolean ready, boolean gone) {
            this(id, platform, last4, "", slot, createdMs, ready, gone);
        }

        Order(long id, String platform, String last4, String orderId, String slot, long createdMs, boolean ready,
              boolean gone) {
            this.id = id;
            this.platform = platform;
            this.last4 = last4;
            this.orderId = orderId == null ? "" : orderId;
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
    /** Hide orders already ready and very old when first seen (KDS source). Bridge Print clears its own. */
    boolean skipStale = true;
    static final long SECONDS_PER_ORDER = 2_600L;
    private long nextGapMs = REPEAT_MS;
    /** Stop showing / calling an order this long after ready; 0 = until picked up. */
    private long maxMs = 0;

    void setMaxMinutes(int minutes) {
        maxMs = Math.max(0, minutes) * 60_000L;
    }

    private boolean initialised;
    /** True when the last non-empty due() result was new orders (play the chime). */
    boolean lastWasNew;

    private List<Order> preparingList = new ArrayList<Order>();

    void update(List<Order> board, long now) {
        List<Order> prep = new ArrayList<Order>();
        for (Order o : board) if (!o.ready && !o.gone) prep.add(o);
        Collections.sort(prep, new Comparator<Order>() {
            @Override public int compare(Order a, Order b) { return Long.compare(a.createdMs, b.createdMs); }
        });
        preparingList = prep;
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
                if (skipStale && o.createdMs > 0 && now - o.createdMs > STALE_MS) {
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
            if (maxMs > 0 && now - w.readyAt >= maxMs) {
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
        if (now - lastCycle < nextGapMs) return Collections.emptyList();
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
        // Quiet: one round every 30 s. Busy (more waiting than one call holds): keep going round,
        // next group as soon as this one is spoken (~2.6 s per order) + a 4 s pause.
        nextGapMs = all.size() > MAX_PER_CALL ? out.size() * SECONDS_PER_ORDER + 4_000L : REPEAT_MS;
        return out;
    }

    int waitingCount() {
        return waiting.size();
    }

    /** Ready orders waiting for a rider, most recently ready first. */
    List<Waiting> readyList() {
        List<Waiting> out = new ArrayList<Waiting>(waiting.values());
        Collections.sort(out, new Comparator<Waiting>() {
            @Override public int compare(Waiting a, Waiting b) { return Long.compare(b.readyAt, a.readyAt); }
        });
        return out;
    }

    /** Online / delivery orders still being prepared, oldest first. */
    List<Order> preparing() {
        return preparingList;
    }

    private static List<Waiting> oldestFirst(List<Waiting> l) {
        List<Waiting> out = new ArrayList<Waiting>(l);
        Collections.sort(out, new Comparator<Waiting>() {
            @Override public int compare(Waiting a, Waiting b) { return Long.compare(a.readyAt, b.readyAt); }
        });
        if (out.size() > MAX_PER_CALL) out = new ArrayList<Waiting>(out.subList(0, MAX_PER_CALL));
        return out;
    }

    /**
     * "Swiggy order 3 1 3 6, slot 47. ... Ready for pickup." The slot is only said when it is
     * known: pickup slots are assigned by Bridge Print, not by Petpooja, so until slots are read
     * from Bridge Print the call is "Swiggy order 3 1 3 6. Ready for pickup."
     */
    static String phrase(List<Waiting> call) {
        // Short and quick: "Zomato zero 3 9 1, slot 64. Swiggy 9 1 9 zero, slot 25."
        // Digits one by one (riders match them); 0 is said as "zero", never "oh".
        StringBuilder sb = new StringBuilder();
        for (Waiting w : call) {
            Order o = w.order;
            if (sb.length() > 0) sb.append(' ');
            sb.append(o.platform.replace('/', ' ').replaceAll("\\s+", " ").trim()).append(' ');
            for (int i = 0; i < o.last4.length(); i++) {
                if (i > 0) sb.append(' ');
                char ch = o.last4.charAt(i);
                sb.append(ch == '0' ? "zero" : String.valueOf(ch));
            }
            if (o.slot != null && !o.slot.isEmpty()) sb.append(", slot ").append(o.slot);
            sb.append('.');
        }
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
