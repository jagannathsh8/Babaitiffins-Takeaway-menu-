package com.babai.scanner;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The pairing code from the Babai Hub page (its QR code):
 *   BABAISCAN1;h=192.168.1.20:8790,10.0.0.5:8790;db=xxx.firebasedatabase.app;o=vtr;k=<24-char key>;n=Counter phone;on=VTR
 * h = the hub on the shop Wi-Fi, db/o/k = the internet route through Firebase.
 */
final class Pairing {
    final List<String> hosts = new ArrayList<String>();
    String db = "", outlet = "", key = "", name = "", outletName = "";

    static Pairing parse(String text) {
        if (text == null) return null;
        text = text.trim();
        if (!text.startsWith("BABAISCAN1;")) return null;
        Pairing p = new Pairing();
        for (String part : text.substring(11).split(";")) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            String k = part.substring(0, i).trim(), v = part.substring(i + 1).trim();
            if (k.equals("h")) {
                for (String h : v.split(",")) if (!h.trim().isEmpty()) p.hosts.add(h.trim());
            } else if (k.equals("db")) p.db = v.replaceFirst("^https?://", "").replaceAll("/+$", "");
            else if (k.equals("o")) p.outlet = v;
            else if (k.equals("k")) p.key = v;
            else if (k.equals("n")) p.name = v;
            else if (k.equals("on")) p.outletName = v;
        }
        if (p.key.length() < 16 || !p.key.matches("[A-Za-z0-9]+")) return null;
        if (p.hosts.isEmpty() && (p.db.isEmpty() || p.outlet.isEmpty())) return null;
        return p;
    }

    String text() {
        StringBuilder h = new StringBuilder();
        for (String s : hosts) h.append(h.length() > 0 ? "," : "").append(s);
        return "BABAISCAN1;h=" + h + ";db=" + db + ";o=" + outlet + ";k=" + key + ";n=" + name + ";on=" + outletName;
    }

    boolean internet() {
        return !db.isEmpty() && !outlet.isEmpty();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("babai_scanner", Context.MODE_PRIVATE);
    }

    static Pairing load(Context c) {
        return parse(prefs(c).getString("pairing", ""));
    }

    static void save(Context c, Pairing p) {
        prefs(c).edit().putString("pairing", p == null ? "" : p.text()).apply();
    }
}
