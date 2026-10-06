package com.pp.kds.dosa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Order Ready" token board for the Dosa TV (pure Java, no Android).
 *
 * Input: the Dine-In KOTs on the board that carry Dosa items. A token is READY when every Dosa
 * KOT under that token is ready (Food Ready, or all its Dosa items marked ready) and stays on
 * screen until the kitchen presses Dispatch (KOT status 10 / card leaves the board).
 *
 * Tokens that turn ready within a few seconds of each other are announced together:
 * "Token number 1, 2, 6, 4, 5, dosa ready, kindly collect."
 */
final class TokenBoard {

    /** Wait this long after the first newly-ready token so close-together ones are called together. */
    static final long GATHER_MS = 3_000L;
    /** A KOT missing from the board this long is treated as gone (protects against reload blips). */
    static final long MISSING_MS = 5_000L;
    /** A KOT that is already ready when first seen and older than this is a leftover: not shown. */
    static final long STALE_MS = 30 * 60_000L;
    static final int MAX_PER_CALL = 8;

    /** One Dine-In Dosa KOT as read from the board. */
    static final class Kot {
        final long id;
        final String label;   // what the screen shows: "680", or "T4" when there is no token
        final String spoken;  // what the voice says: "680", "table 4"
        final long createdMs;
        /** ready = Dosa part done; dispatched = KOT dispatched OR every Dosa item item-dispatched. */
        final boolean ready, dispatched, cancelled;
        final int dosas;

        Kot(long id, String label, String spoken, long createdMs, boolean ready, boolean dispatched,
            boolean cancelled, int dosas) {
            this.id = id;
            this.label = label;
            this.spoken = spoken;
            this.createdMs = createdMs;
            this.ready = ready;
            this.dispatched = dispatched;
            this.cancelled = cancelled;
            this.dosas = dosas;
        }
    }

    /** A token on screen (one or more KOTs with the same token number). */
    static final class Token {
        final String label;
        String spoken;
        long createdMs;
        long readyAt;
        boolean ready;
        int dosas;
        /** Still preparing although a newer order is already ready (e.g. a bigger order). */
        boolean overtaken;
        final List<Rec> recs = new ArrayList<Rec>();

        Token(String label) {
            this.label = label;
        }
    }

    static final class Rec {
        Kot kot;
        long missingSince;
        long readyAt;       // when this tracker saw the KOT's Dosa part become ready (0 = not ready)
        boolean released;   // collected / auto-cleared / leftover: hidden while it stays on the board
    }

    private final Map<Long, Rec> kots = new LinkedHashMap<Long, Rec>();
    private final Map<String, Token> tokens = new LinkedHashMap<String, Token>();
    private final List<Token> pending = new ArrayList<Token>();
    private long pendingSince;
    private boolean initialised;
    private boolean lastAnnounce;
    private long autoClearMs = 10 * 60_000L;
    private List<Token> readyList = new ArrayList<Token>();
    private List<Token> preparingList = new ArrayList<Token>();

    /** Ready tokens leave the screen after this long even if nobody presses Dispatch (0 = never). */
    void setAutoClearMinutes(int minutes) {
        autoClearMs = Math.max(0, minutes) * 60_000L;
    }

    /**
     * @param announce false while nobody is watching the Order Ready screen: tokens that become
     *                 ready then are marked as already called, so opening the screen never
     *                 replays a backlog.
     */
    void update(List<Kot> board, long now, boolean announce) {
        Map<Long, Kot> seen = new HashMap<Long, Kot>();
        for (Kot k : board) seen.put(k.id, k);
        for (Kot k : board) {
            Rec r = kots.get(k.id);
            if (r == null) {
                r = new Rec();
                kots.put(k.id, r);
                if (k.ready) {
                    r.readyAt = now;
                    // Already ready long after it was ordered: a leftover that was collected
                    // without Dispatch being pressed. Don't put it on the TV.
                    if (k.createdMs > 0 && now - k.createdMs > STALE_MS) r.released = true;
                }
            } else if (k.ready && r.readyAt == 0) {
                r.readyAt = now;
            } else if (!k.ready) {
                r.readyAt = 0;
                r.released = false; // something was added to it: it is cooking again
            }
            r.kot = k;
            r.missingSince = 0;
        }
        List<Long> drop = new ArrayList<Long>();
        for (Map.Entry<Long, Rec> e : kots.entrySet()) {
            Rec r = e.getValue();
            if (r.kot.dispatched || r.kot.cancelled) {
                drop.add(e.getKey());
            } else if (!seen.containsKey(e.getKey())) {
                if (r.missingSince == 0) r.missingSince = now;
                else if (now - r.missingSince >= MISSING_MS) drop.add(e.getKey());
            }
        }
        for (Long id : drop) kots.remove(id);
        rebuild(now, announce);
    }

    /** Re-evaluates auto-clear without a new board (call every tick). */
    void refresh(long now) {
        if (initialised) rebuild(now, lastAnnounce);
    }

    /** Staff marked a token as collected (long-press on the TV). */
    void collect(String label, long now) {
        Token t = tokens.get(label);
        if (t == null || !t.ready) return;
        for (Rec r : t.recs) r.released = true;
        rebuild(now, lastAnnounce);
    }

    private void rebuild(long now, boolean announce) {
        lastAnnounce = announce;
        // Group live, unreleased KOTs by token.
        Map<String, Token> next = new LinkedHashMap<String, Token>();
        for (Rec r : kots.values()) {
            if (r.released) continue;
            Kot k = r.kot;
            Token t = next.get(k.label);
            if (t == null) {
                t = new Token(k.label);
                t.spoken = k.spoken;
                t.createdMs = k.createdMs;
                t.ready = true;
                next.put(k.label, t);
            }
            t.recs.add(r);
            if (k.createdMs > 0 && (t.createdMs <= 0 || k.createdMs < t.createdMs)) t.createdMs = k.createdMs;
            t.ready &= k.ready;
            t.readyAt = Math.max(t.readyAt, r.readyAt);
            t.dosas += Math.max(1, k.dosas);
        }
        // Auto-clear tokens that have been ready for too long.
        if (autoClearMs > 0) {
            List<String> expired = new ArrayList<String>();
            for (Token t : next.values()) {
                if (t.ready && t.readyAt > 0 && now - t.readyAt >= autoClearMs) {
                    for (Rec r : t.recs) r.released = true;
                    expired.add(t.label);
                }
            }
            for (String l : expired) next.remove(l);
        }

        for (Token t : next.values()) {
            Token old = tokens.get(t.label);
            boolean wasReady = old != null && old.ready;
            if (t.ready && !wasReady && announce && initialised) {
                if (pending.isEmpty()) pendingSince = now;
                pending.add(t);
            }
        }
        // Drop calls for tokens that are gone or went back to preparing (e.g. a dosa was added).
        for (int i = pending.size() - 1; i >= 0; i--) {
            Token p = pending.get(i);
            Token now2 = next.get(p.label);
            if (now2 == null || !now2.ready) pending.remove(i);
            else pending.set(i, now2);
        }
        tokens.clear();
        tokens.putAll(next);
        initialised = true;

        List<Token> ready = new ArrayList<Token>();
        List<Token> preparing = new ArrayList<Token>();
        for (Token t : tokens.values()) (t.ready ? ready : preparing).add(t);
        Collections.sort(ready, new Comparator<Token>() {
            @Override public int compare(Token a, Token b) { return Long.compare(b.readyAt, a.readyAt); }
        });
        Collections.sort(preparing, new Comparator<Token>() {
            @Override public int compare(Token a, Token b) { return Long.compare(a.createdMs, b.createdMs); }
        });
        long newestReady = 0;
        for (Token t : ready) newestReady = Math.max(newestReady, t.createdMs);
        for (Token t : preparing) t.overtaken = t.createdMs > 0 && t.createdMs < newestReady;
        readyList = ready;
        preparingList = preparing;
    }

    /** Newly-ready tokens to call out, once the gather window has passed; empty otherwise. */
    List<Token> takeCall(long now) {
        if (pending.isEmpty() || now - pendingSince < GATHER_MS) return Collections.emptyList();
        int n = Math.min(MAX_PER_CALL, pending.size());
        List<Token> out = new ArrayList<Token>(pending.subList(0, n));
        pending.subList(0, n).clear();
        pendingSince = now - GATHER_MS; // any remainder goes out on the next call
        return out;
    }

    /** Forget anything queued (voice switched off / screen closed). */
    void clearPending() {
        pending.clear();
    }

    /** Ready tokens, most recently ready first. */
    List<Token> ready() {
        return readyList;
    }

    /** Preparing tokens, oldest order first (= next to come out). */
    List<Token> preparing() {
        return preparingList;
    }

    /** "Token number 1, 2, 6, 4, 5, dosa ready, kindly collect." */
    static String phrase(List<Token> call) {
        StringBuilder tokensPart = new StringBuilder();
        StringBuilder others = new StringBuilder();
        for (Token t : call) {
            boolean isToken = t.spoken != null && !t.spoken.isEmpty() && Character.isDigit(t.spoken.charAt(0));
            StringBuilder sb = isToken ? tokensPart : others;
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.spoken == null ? t.label : t.spoken);
        }
        StringBuilder s = new StringBuilder();
        if (tokensPart.length() > 0) s.append("Token number ").append(tokensPart);
        if (others.length() > 0) {
            if (s.length() > 0) s.append(", ");
            s.append(others);
        }
        s.append(", dosa ready, kindly collect.");
        return s.toString();
    }

    /**
     * Estimated minutes left for the preparing token at queue position {@code pos} (0 = oldest),
     * from today's real average prep time and the live completion rate. NaN when there is no
     * real figure yet (the screen then shows no minutes rather than a guess).
     */
    static double minutesLeft(int pos, double elapsedMin, double avgPrepMin, double perMin) {
        boolean haveAvg = !Double.isNaN(avgPrepMin) && avgPrepMin > 0;
        boolean haveRate = perMin > 0;
        if (!haveAvg && !haveRate) return Double.NaN;
        double left = haveAvg ? avgPrepMin - elapsedMin : 0;
        if (haveRate) left = Math.max(left, (pos + 1) / perMin);
        return Math.max(0.5, Math.min(DosaStats.MAX_ETA_MIN, left));
    }
}
