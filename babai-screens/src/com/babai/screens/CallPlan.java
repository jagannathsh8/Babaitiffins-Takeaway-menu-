package com.babai.screens;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * When the board calls a ready token: as soon as it turns ready, then reminders at the set minutes
 * after that (e.g. 1, 2, 4, 5) while it is still ready and not dispatched. Tokens that just turned
 * ready always come first: reminders wait until the voice is free. Tokens already ready when the
 * board starts are not called.
 */
final class CallPlan {
    static final class Token {
        final long id;
        final String label;
        Token(long id, String label) { this.id = id; this.label = label; }
    }

    static final class Due {
        final List<String> fresh = new ArrayList<String>(), remind = new ArrayList<String>();
    }

    private final Map<Long, Long> firstCall = new HashMap<Long, Long>();
    private final Map<Long, Integer> done = new HashMap<Long, Integer>(); // reminders made
    private boolean first = true;

    /** The calls due now. busy: the voice is still speaking (reminders wait for it). */
    Due next(List<Token> ready, long now, int[] remindMins, boolean busy) {
        Due d = new Due();
        Set<Long> live = new HashSet<Long>();
        for (Token t : ready) {
            live.add(t.id);
            if (!firstCall.containsKey(t.id)) {
                firstCall.put(t.id, now);
                done.put(t.id, first ? remindMins.length : 0); // already ready at start: no calls
                if (!first) d.fresh.add(t.label);
            }
        }
        firstCall.keySet().retainAll(live);
        done.keySet().retainAll(live);
        first = false;
        if (busy || !d.fresh.isEmpty()) return d; // the new tokens first; reminders when the voice is free
        for (Token t : ready) {
            int k = done.get(t.id);
            long since = now - firstCall.get(t.id);
            int reach = k;
            while (reach < remindMins.length && since >= remindMins[reach] * 60_000L) reach++;
            if (reach > k) { // one call even if several reminder times have passed
                done.put(t.id, reach);
                d.remind.add(t.label);
            }
        }
        return d;
    }
}
