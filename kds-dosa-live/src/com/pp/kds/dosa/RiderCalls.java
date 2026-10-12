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
    static final long MISSING_MS = 120_000L;     // off the KDS board this long (refresh / blip) before it counts as gone
    static final long SAFETY_MS = 3 * 3600_000L;  // never dispatched in Petpooja: cleared after 3 h
    // Many ready orders off the board in the same refresh = a short / broken answer from Petpooja,
    // not riders: those orders stay this long (they come back with the next full answer).
    static final long MASS_MISSING_MS = 20 * 60_000L;
    static final int MASS_MISSING_MIN = 4;
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
        long graceMs = MISSING_MS;   // how long it may be off the board before it counts as picked up
    }

    private final Map<Long, Waiting> waiting = new LinkedHashMap<Long, Waiting>();
    private final Set<Long> skipped = new HashSet<Long>();   // stale leftovers: never called
    private long lastCycle;
    /** Hide orders already ready and very old when first seen. Off: an order is never hidden for its age. */
    boolean skipStale = false;

    /** Why a ready order left the screen (for the footer / settings). */
    static final class Removal {
        final String platform, last4, reason;
        final long at;

        Removal(Order o, String reason, long at) {
            this.platform = o == null ? "" : o.platform;
            this.last4 = o == null ? "" : o.last4;
            this.reason = reason;
            this.at = at;
        }
    }

    private final java.util.LinkedList<Removal> removals = new java.util.LinkedList<Removal>();
    private int removedCount;
    /** order key -> when it became ready (kept on the device so a restart keeps the timers). */
    private final Map<String, Long> readyStore = new LinkedHashMap<String, Long>();
    private boolean storeChanged;

    static String key(Order o) {
        return o.orderId != null && !o.orderId.isEmpty() ? o.orderId : String.valueOf(o.id);
    }

    private void removed(Waiting w, String reason, long now) {
        if (w == null || w.order == null) return;
        removals.addFirst(new Removal(w.order, reason, now));
        while (removals.size() > 20) removals.removeLast();
        removedCount++;
        if (readyStore.remove(key(w.order)) != null) storeChanged = true;
    }

    List<Removal> removals() {
        return new ArrayList<Removal>(removals);
    }

    int removedCount() {
        return removedCount;
    }

    /** Ready times to save (and whether they changed since the last call). */
    Map<String, Long> readyTimes() {
        storeChanged = false;
        return new LinkedHashMap<String, Long>(readyStore);
    }

    boolean readyTimesChanged() {
        return storeChanged;
    }

    /** Ready times saved before a restart (orders keep their "ready for" time). */
    void restoreReadyTimes(Map<String, Long> saved) {
        if (saved != null) readyStore.putAll(saved);
    }
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
    private final List<Waiting> justMissing = new ArrayList<Waiting>();

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
                Waiting was = waiting.remove(o.id);
                if (was != null) removed(was, o.gone ? "dispatched / cancelled in Petpooja" : "back to preparing", now);
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
                Long saved = readyStore.get(key(o));
                boolean restored = saved != null && saved <= now && now - saved < SAFETY_MS;
                w.readyAt = restored ? saved : now;
                if (!restored) {
                    readyStore.put(key(o), now);
                    storeChanged = true;
                }
                // Orders already ready when the app starts are not "new": they join the repeat cycle.
                w.lastSaid = initialised && !restored ? 0 : now;
                waiting.put(o.id, w);
            }
            w.order = o;
            w.missingSince = 0;
        }
        List<Long> drop = new ArrayList<Long>();
        Map<Long, String> why = new java.util.HashMap<Long, String>();
        for (Map.Entry<Long, Waiting> e : waiting.entrySet()) {
            Waiting w = e.getValue();
            if (maxMs > 0 && now - w.readyAt >= maxMs) {
                drop.add(e.getKey());
                skipped.add(e.getKey());
                why.put(e.getKey(), "time limit " + (maxMs / 60_000L) + " min");
            } else if (now - w.readyAt >= SAFETY_MS) {
                drop.add(e.getKey());
                skipped.add(e.getKey());
                why.put(e.getKey(), "ready 3 hours, never dispatched");
            } else if (!seen.contains(e.getKey())) {
                if (w.missingSince == 0) {
                    w.missingSince = now;
                    w.graceMs = MISSING_MS;
                    justMissing.add(w);
                } else if (now - w.missingSince >= w.graceMs) {
                    drop.add(e.getKey());
                    why.put(e.getKey(), w.graceMs > MISSING_MS
                            ? "off the KDS board " + (w.graceMs / 60_000L) + " min (many left at once)"
                            : "not on the KDS board for 2 min");
                }
            }
        }
        // Riders take orders one by one; many vanishing together is the feed, not pickups.
        if (justMissing.size() >= MASS_MISSING_MIN && justMissing.size() * 10 >= waiting.size() * 3) {
            for (Waiting w : justMissing) w.graceMs = MASS_MISSING_MS;
        }
        justMissing.clear();
        for (Long id : drop) removed(waiting.remove(id), why.get(id), now);
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

    /** Board order of the brands: Swiggy, then Zomato, then the rest (Ownly, ...) by name. */
    static int brandRank(String platform) {
        String l = platform == null ? "" : platform.toLowerCase(java.util.Locale.US);
        if (l.contains("swiggy")) return 0;
        if (l.contains("zomato")) return 1;
        return 2;
    }

    private static int compareBrand(String a, String b) {
        int c = Integer.compare(brandRank(a), brandRank(b));
        if (c != 0) return c;
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    /** Groups ready orders brand by brand; inside a brand the order stays as it was. */
    static List<Waiting> readyByBrand(List<Waiting> l) {
        List<Waiting> out = new ArrayList<Waiting>(l);
        Collections.sort(out, new Comparator<Waiting>() {
            @Override public int compare(Waiting a, Waiting b) { return compareBrand(a.order.platform, b.order.platform); }
        });
        return out;
    }

    /** Groups preparing orders brand by brand; inside a brand the order stays as it was (oldest first). */
    static List<Order> byBrand(List<Order> l) {
        List<Order> out = new ArrayList<Order>(l);
        Collections.sort(out, new Comparator<Order>() {
            @Override public int compare(Order a, Order b) { return compareBrand(a.platform, b.platform); }
        });
        return out;
    }

    /** One search result on the rider board: the order and whether it is ready (and since when). */
    static final class Hit {
        final Order order;
        final boolean ready;
        final long readyAt;

        Hit(Order order, boolean ready, long readyAt) {
            this.order = order;
            this.ready = ready;
            this.readyAt = readyAt;
        }
    }

    /**
     * Orders on the board whose ID matches what was typed: the last 4 digits starting with it
     * ("31" finds ...3136) or the full order ID ending with it ("103136"). Exact last-4 matches
     * first, then ready before preparing.
     */
    static List<Hit> find(String query, List<Waiting> ready, List<Order> preparing) {
        final String q = digitsOf(query);
        List<Hit> out = new ArrayList<Hit>();
        if (q.isEmpty()) return out;
        for (Waiting w : ready) if (matches(w.order, q)) out.add(new Hit(w.order, true, w.readyAt));
        for (Order o : preparing) if (matches(o, q)) out.add(new Hit(o, false, 0));
        Collections.sort(out, new Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                int c = Boolean.compare(!a.order.last4.equals(q), !b.order.last4.equals(q));
                return c != 0 ? c : Boolean.compare(!a.ready, !b.ready);
            }
        });
        return out;
    }

    private static boolean matches(Order o, String q) {
        if (o == null) return false;
        if (o.last4.startsWith(q)) return true;
        String d = digitsOf(o.orderId);
        return q.length() > 4 && d.endsWith(q);
    }

    private static String digitsOf(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
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
