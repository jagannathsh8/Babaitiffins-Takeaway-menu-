package com.babai.screens;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Today's dosa numbers for the "Did you know?" strip (Babai Dosa Board app), counted from the
 * kitchen lists read from Petpooja: every KOT once, its dosas by name and by the hour it was
 * punched. Kept in a small file, so a restart keeps the day; a new day starts over and keeps
 * yesterday's favourite.
 */
final class DosaStats {
    private final File file;
    private String day = "";
    private final Set<Long> seen = new HashSet<Long>();
    private final Map<String, Double> dish = new HashMap<String, Double>();
    private final double[] hour = new double[24];
    private String yesterdayFav = "";
    private boolean dirty;

    DosaStats(File file) {
        this.file = file;
        load();
    }

    static String today(long ms) { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(ms); }

    private void newDay(String d) {
        if (!day.isEmpty() && !d.equals(day)) {
            String f = favourite();
            if (!f.isEmpty()) yesterdayFav = f;
        }
        day = d;
        seen.clear();
        dish.clear();
        for (int i = 0; i < 24; i++) hour[i] = 0;
        dirty = true;
    }

    /** One kitchen list (the cards as DosaBoard reads them). */
    synchronized void add(JSONObject j) {
        long now = System.currentTimeMillis();
        String d = today(now);
        if (!d.equals(day)) newDay(d);
        JSONArray cards = j.optJSONArray("cards");
        if (cards != null) {
            for (int i = 0; i < cards.length(); i++) {
                JSONObject c = cards.optJSONObject(i);
                if (c == null) continue;
                long id = c.optLong("k"), created = c.optLong("c", 0);
                if (id == 0 || seen.contains(id)) continue;
                if (created > 0 && !today(created).equals(d)) continue; // a KOT from another day
                JSONArray its = c.optJSONArray("i");
                double q = 0;
                if (its != null) {
                    for (int k = 0; k < its.length(); k++) {
                        JSONObject it = its.optJSONObject(k);
                        if (it == null || !DosaBoard.isDosa(it.optString("c"))) continue;
                        double n = Math.max(1, it.optDouble("q", 1));
                        String name = it.optString("n").trim();
                        if (name.isEmpty()) continue;
                        Double was = dish.get(name);
                        dish.put(name, (was == null ? 0 : was) + n);
                        q += n;
                    }
                }
                seen.add(id);
                dirty = true;
                if (q > 0) {
                    Calendar cal = Calendar.getInstance();
                    cal.setTimeInMillis(created > 0 ? created : now);
                    hour[cal.get(Calendar.HOUR_OF_DAY)] += q;
                }
            }
        }
        if (dirty) save();
    }

    synchronized int total() {
        double t = 0;
        for (double v : dish.values()) t += v;
        return (int) Math.round(t);
    }

    private List<String> top() {
        List<String> names = new ArrayList<String>(dish.keySet());
        Collections.sort(names, new Comparator<String>() {
            @Override public int compare(String a, String b) { return Double.compare(dish.get(b), dish.get(a)); }
        });
        return names;
    }

    private String favourite() {
        List<String> t = top();
        return t.isEmpty() ? "" : t.get(0);
    }

    static String hourName(int h) {
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + (h < 12 ? " AM" : " PM");
    }

    /** The facts made from today's numbers (only once there are enough dosas to say something). */
    synchronized List<String> facts() {
        List<String> out = new ArrayList<String>();
        int n = total();
        List<String> t = top();
        if (n >= 5 && !t.isEmpty()) out.add(t.get(0) + " is today's favourite at Babai Tiffins.");
        if (n >= 10) out.add(n + " dosas made fresh at Babai Tiffins today.");
        if (n >= 20) {
            int best = 0;
            for (int i = 1; i < 24; i++) if (hour[i] > hour[best]) best = i;
            out.add("Dosa rush hour today: " + hourName(best) + " – " + hourName((best + 1) % 24) + ".");
        }
        if (n >= 15 && t.size() >= 3) out.add("Top 3 dosas today: " + t.get(0) + ", " + t.get(1) + " and " + t.get(2) + ".");
        if (!yesterdayFav.isEmpty()) out.add("Yesterday's favourite: " + yesterdayFav + ".");
        return out;
    }

    private void load() {
        try {
            if (!file.exists()) return;
            byte[] b = new byte[(int) Math.min(file.length(), 1 << 20)];
            FileInputStream in = new FileInputStream(file);
            try { int r = 0; while (r < b.length) { int x = in.read(b, r, b.length - r); if (x < 0) break; r += x; } } finally { in.close(); }
            JSONObject j = new JSONObject(new String(b, "UTF-8"));
            day = j.optString("day");
            yesterdayFav = j.optString("yfav");
            JSONArray s = j.optJSONArray("seen");
            if (s != null) for (int i = 0; i < s.length(); i++) seen.add(s.optLong(i));
            JSONObject d = j.optJSONObject("dish");
            if (d != null) for (Iterator<String> it = d.keys(); it.hasNext(); ) { String k = it.next(); dish.put(k, d.optDouble(k)); }
            JSONArray h = j.optJSONArray("hour");
            if (h != null) for (int i = 0; i < 24 && i < h.length(); i++) hour[i] = h.optDouble(i);
        } catch (Throwable ignored) {
            // a damaged file: start the day again
        }
    }

    private void save() {
        try {
            JSONObject j = new JSONObject();
            j.put("day", day);
            j.put("yfav", yesterdayFav);
            JSONArray s = new JSONArray();
            for (Long id : seen) s.put(id);
            j.put("seen", s);
            JSONObject d = new JSONObject();
            for (Map.Entry<String, Double> e : dish.entrySet()) d.put(e.getKey(), e.getValue());
            j.put("dish", d);
            JSONArray h = new JSONArray();
            for (double v : hour) h.put(v);
            j.put("hour", h);
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try { o.write(j.toString().getBytes("UTF-8")); } finally { o.close(); }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
            dirty = false;
        } catch (Throwable ignored) {
        }
    }
}
