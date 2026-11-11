package com.babai.board;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the Dosa Board shows, from the KDS board (pure Java: unit tested on a PC).
 *
 * <ul>
 *   <li>Ready: dosa tokens whose dosas are all ready, newest first; the newest is the spotlight for
 *       5 s, "Just now" for a minute, then "N min ago". A token leaves when it is dispatched, gone
 *       from the KDS, or ready for {@link #CLEAR_MS}.</li>
 *   <li>Preparing: the rest, oldest first, each with about how many minutes are left: the same rule
 *       as the Dosa screen (today's average dosa time, and how fast tokens come off the tawa).</li>
 *   <li>Est. prep time: the same rule for an order placed now. Dosas in the making: the dosas still
 *       being made.</li>
 * </ul>
 */
public final class BoardModel {

    public static final long SPOT_MS = 5_000L, FRESH_MS = 60_000L, CLEAR_MS = 30 * 60_000L;
    static final double DEFAULT_AVG_MIN = 6.0, START_KOTS = 4.0, MAX_MIN = 40.0;
    static final long WIN_MS = 15 * 60_000L, MIN_WIN_MS = 3 * 60_000L, MAX_PREP_MS = 150 * 60_000L;

    /** A dosa line of an order: name, quantity, ready. */
    public static final class Item {
        public final String name;
        public final double qty;
        public final boolean ready;

        public Item(String name, double qty, boolean ready) {
            this.name = name;
            this.qty = qty;
            this.ready = ready;
        }

        public String label() {
            return fmtQty(qty) + "× " + name;
        }
    }

    /** One KOT with dosas, as the KDS has it now. */
    public static final class Order {
        public final long id;
        public final String token;
        public final long created; // ms, 0 unknown
        public final boolean ready, gone;
        public final List<Item> items;

        public Order(long id, String token, long created, boolean ready, boolean gone, List<Item> items) {
            this.id = id;
            this.token = token;
            this.created = created;
            this.ready = ready;
            this.gone = gone;
            this.items = items;
        }

        public int dosas() {
            double q = 0;
            for (Item i : items) q += i.qty;
            return Math.max(1, (int) Math.round(q));
        }
    }

    public static final class Ready {
        public final Order o;
        public final long readyAt;

        Ready(Order o, long readyAt) {
            this.o = o;
            this.readyAt = readyAt;
        }
    }

    public static final class Prep {
        public final Order o;
        public final int minutes; // about this many left (1+)
        public final float progress; // 0..1 for the ring

        Prep(Order o, int minutes, float progress) {
            this.o = o;
            this.minutes = minutes;
            this.progress = progress;
        }
    }

    private final Map<Long, Long> readyAt = new HashMap<Long, Long>();
    private final Set<Long> wasPreparing = new HashSet<Long>();
    private final List<Long> recent = new ArrayList<Long>(); // tokens off the tawa (ms)
    private long since, totalPrepMs;
    private int completed;
    private boolean first = true;

    public List<Ready> ready = new ArrayList<Ready>();
    public List<Prep> prep = new ArrayList<Prep>();
    public int estMinutes, dosasInMaking;

    public void update(List<Order> board, long now) {
        if (since == 0) since = now;
        Set<Long> seen = new HashSet<Long>();
        List<Ready> rd = new ArrayList<Ready>();
        List<Order> pr = new ArrayList<Order>();
        for (Order o : board) {
            seen.add(o.id);
            if (o.gone) continue;
            if (o.ready) {
                Long at = readyAt.get(o.id);
                if (at == null) {
                    // seen turning ready now; already ready at start: an older one (no spotlight)
                    at = first || !wasPreparing.contains(o.id) ? now - FRESH_MS - 1 : now;
                    readyAt.put(o.id, at);
                    if (wasPreparing.contains(o.id)) offTheTawa(o, now);
                }
                wasPreparing.remove(o.id);
                if (now - at < CLEAR_MS) rd.add(new Ready(o, at));
            } else {
                readyAt.remove(o.id); // back to preparing (an item was un-marked)
                wasPreparing.add(o.id);
                pr.add(o);
            }
        }
        readyAt.keySet().retainAll(seen);
        wasPreparing.retainAll(seen);
        first = false;
        Collections.sort(rd, new Comparator<Ready>() {
            @Override public int compare(Ready a, Ready b) {
                return a.readyAt != b.readyAt ? Long.compare(b.readyAt, a.readyAt) : Long.compare(b.o.created, a.o.created);
            }
        });
        Collections.sort(pr, new Comparator<Order>() {
            @Override public int compare(Order a, Order b) {
                return a.created != b.created ? Long.compare(a.created, b.created) : Long.compare(a.id, b.id);
            }
        });
        while (!recent.isEmpty() && recent.get(0) < now - WIN_MS) recent.remove(0);
        double avg = avgMin(), perMin = perMin(now, avg);
        List<Prep> out = new ArrayList<Prep>();
        int dosas = 0;
        for (int i = 0; i < pr.size(); i++) {
            Order o = pr.get(i);
            dosas += o.dosas();
            double el = o.created > 0 ? Math.max(0, (now - o.created) / 60000.0) : 0;
            double left = minutesLeft(i, el, avg, perMin);
            float p = (float) (el / (el + left));
            out.add(new Prep(o, Math.max(1, (int) Math.ceil(left)), Math.max(0f, Math.min(.97f, p))));
        }
        ready = rd;
        prep = out;
        dosasInMaking = dosas;
        estMinutes = Math.max(2, (int) Math.ceil(minutesLeft(pr.size(), 0, avg, perMin)));
    }

    private void offTheTawa(Order o, long now) {
        recent.add(now);
        long d = o.created > 0 ? now - o.created : 0;
        if (d > 0 && d < MAX_PREP_MS) {
            completed++;
            totalPrepMs += d;
        }
    }

    double avgMin() {
        return completed > 0 ? totalPrepMs / (double) completed / 60000.0 : DEFAULT_AVG_MIN;
    }

    double perMin(long now, double avg) {
        if (recent.isEmpty()) return START_KOTS / avg; // not measured yet: ~4 tokens on the tawas at once
        double window = Math.max(MIN_WIN_MS, Math.min(WIN_MS, now - since)) / 60000.0;
        return recent.size() / window;
    }

    /** The Dosa screen's rule: today's average minus time waited, and the queue at the tawa's pace. */
    static double minutesLeft(int pos, double elapsedMin, double avgMin, double perMin) {
        double left = avgMin - elapsedMin;
        if (perMin > 0) left = Math.max(left, (pos + 1) / perMin);
        return Math.max(0.5, Math.min(MAX_MIN, left));
    }

    public static String fmtQty(double q) {
        if (q <= 0) q = 1;
        return q == Math.rint(q) ? String.valueOf((long) q) : String.valueOf(q);
    }
}
