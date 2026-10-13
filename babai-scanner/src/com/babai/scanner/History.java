package com.babai.scanner;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The last 30 scans and their results (shown on the screen, kept when the app closes). */
final class History {
    private static final int MAX = 30;

    private History() {}

    static synchronized void add(Context c, Sender.Result r) {
        try {
            JSONArray a = new JSONArray(Pairing.prefs(c).getString("history", "[]")), out = new JSONArray();
            JSONObject o = new JSONObject();
            o.put("at", r.at);
            o.put("ok", r.ok);
            o.put("already", r.already);
            o.put("text", line(r));
            o.put("route", r.route);
            out.put(o);
            for (int i = 0; i < a.length() && out.length() < MAX; i++) out.put(a.get(i));
            Pairing.prefs(c).edit().putString("history", out.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static synchronized List<JSONObject> list(Context c) {
        List<JSONObject> l = new ArrayList<JSONObject>();
        try {
            JSONArray a = new JSONArray(Pairing.prefs(c).getString("history", "[]"));
            for (int i = 0; i < a.length(); i++) l.add(a.getJSONObject(i));
        } catch (Exception ignored) {
        }
        return l;
    }

    /** "FOOD READY · Swiggy …0744 · slot 40" or the hub's message. */
    static String line(Sender.Result r) {
        StringBuilder b = new StringBuilder(r.msg == null || r.msg.isEmpty() ? (r.ok ? "FOOD READY" : "Not marked") : r.msg);
        if (r.ok || r.already) {
            if (!r.brand.isEmpty()) b.append(" · ").append(r.brand);
            if (!r.last4.isEmpty()) b.append(" …").append(r.last4);
            if (!r.slot.isEmpty()) b.append(" · slot ").append(r.slot);
        }
        return b.toString();
    }
}
