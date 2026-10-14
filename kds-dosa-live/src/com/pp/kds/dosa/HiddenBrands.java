package com.pp.kds.dosa;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/**
 * Brands kept off the rider screen (default "Go Self Service": collected at the counter, no rider).
 * The Babai Hub sends its list with the rider feed; this copy covers the KDS-board fallback. The
 * KDS board itself is not changed. Matched ignoring case, spaces and punctuation.
 */
final class HiddenBrands {

    private static volatile String[] keys = {"goselfservice"};
    private static String saved = "";

    private HiddenBrands() {}

    static String key(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(java.util.Locale.ROOT).toCharArray()) if (Character.isLetterOrDigit(c)) b.append(c);
        return b.toString();
    }

    /** Any of the names (brand, platform, online name) is a hidden brand. */
    static boolean hidden(String... names) {
        String[] k = keys;
        if (k.length == 0) return false;
        for (String n : names) {
            String nk = key(n);
            if (nk.isEmpty()) continue;
            for (String h : k) if (!h.isEmpty() && nk.contains(h)) return true;
        }
        return false;
    }

    static void load(Context c) {
        try {
            String s = c.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE).getString("hide_brands", null);
            if (s != null) apply(s);
            saved = s == null ? "" : s;
        } catch (Throwable ignored) {
        }
    }

    /** The hub's list (comma-separated); saved so it holds while the hub is off. */
    static void fromHub(Context c, List<String> names) {
        StringBuilder b = new StringBuilder();
        for (String n : names) b.append(b.length() == 0 ? "" : ",").append(n);
        String s = b.toString();
        apply(s);
        if (c != null && !s.equals(saved)) {
            saved = s;
            try {
                c.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE).edit().putString("hide_brands", s).apply();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void apply(String s) {
        List<String> out = new ArrayList<String>();
        for (String p : s.split(",")) {
            String k = key(p);
            if (!k.isEmpty()) out.add(k);
        }
        keys = out.toArray(new String[0]);
    }
}
