package com.babai.screens;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Reads the kitchen orders straight from the Petpooja billing PC on the shop Wi-Fi, the same way the
 * Petpooja KDS app does (1.7), so the Dosa board works without the Console. Read only: nothing is
 * changed in Petpooja. The same two calls as the Console (babai-hub/petpooja.go):
 *   POST http://<billing PC>:3000/petpooja_server/kiosk_get_default_data   -> sync_code
 *   POST .../petpooja_server/get_kot_data_for_kitchen_view                   -> one row per KOT item
 * The rows become the same list the Console's kitchen feed sends (cards), for DosaBoard.orders.
 */
final class Petpooja {
    static final int PORT = 3000;
    private static final String CLIENT = "android_kds", USER = "{\"site_user_language_profile\":0}";
    private static String code = "", codeHost = "";

    private Petpooja() {}

    static String base(String host) {
        String h = host == null ? "" : host.trim();
        if (h.startsWith("http://")) h = h.substring(7);
        int s = h.indexOf('/');
        if (s >= 0) h = h.substring(0, s);
        if (h.isEmpty()) return "";
        if (h.indexOf(':') < 0) h = h + ":" + PORT;
        return "http://" + h + "/";
    }

    private static String hostOnly(String host) {
        String b = base(host);
        b = b.substring(7, b.length() - 1);
        int c = b.lastIndexOf(':');
        return c > 0 ? b.substring(0, c) : b;
    }

    static String post(String url, String json, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        c.setRequestProperty("x-app-client", "android-kds");
        try {
            OutputStream o = c.getOutputStream();
            o.write(json.getBytes("UTF-8"));
            o.close();
            if (c.getResponseCode() != 200) throw new java.io.IOException("Petpooja answered HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            return Net.read(in, 16 * 1024 * 1024);
        } finally {
            c.disconnect();
        }
    }

    /** A sync code from the billing PC (as the KDS app asks for one). */
    static String syncCode(String host, int timeoutMs) throws Exception {
        JSONObject req = new JSONObject();
        req.put("intra_server_version", 7);
        req.put("kds_details_obj", new JSONObject().put("ip", hostOnly(host)).put("version", "5.0.1").put("language_id", "0"));
        req.put("user", USER);
        req.put("clientIdentifier", CLIENT);
        JSONObject r = new JSONObject(post(base(host) + "petpooja_server/kiosk_get_default_data", req.toString(), timeoutMs));
        String s = r.optString("sync_code", "");
        if (s.isEmpty() && r.optJSONObject("data") != null) s = r.optJSONObject("data").optString("sync_code", "");
        if (s.isEmpty()) {
            String m = r.optString("message", "");
            throw new java.io.IOException("Petpooja didn't accept the connection" + (m.isEmpty() ? "" : ": " + m));
        }
        return s;
    }

    static boolean isPetpooja(String host, int timeoutMs) {
        try {
            return !syncCode(host, timeoutMs).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Every Petpooja billing PC answering on this device's networks (a few seconds). */
    static List<String> find() {
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        ExecutorService ex = Executors.newFixedThreadPool(48);
        for (String me : Net.myAddresses()) {
            for (final String ip : Net.neighbours(me)) {
                ex.execute(new Runnable() { @Override public void run() { if (isPetpooja(ip, 900)) found.add(ip); } });
            }
        }
        ex.shutdown();
        try { ex.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        ex.shutdownNow();
        List<String> out = new ArrayList<String>(found);
        Collections.sort(out);
        return out;
    }

    /** Petpooja's order types for the board's choice: 1 delivery, 2 pick up, 3 dine in. */
    static String orderTypes(java.util.Set<String> kinds) {
        StringBuilder s = new StringBuilder();
        if (kinds.contains("D")) s.append("1,");
        if (kinds.contains("P") || kinds.contains("S")) s.append("2,");
        if (kinds.contains("I") || kinds.contains("S")) s.append("3,");
        return s.length() == 0 ? "3" : s.substring(0, s.length() - 1);
    }

    private static JSONArray rows(String host, String types, int timeoutMs) throws Exception {
        synchronized (Petpooja.class) {
            if (code.isEmpty() || !host.equals(codeHost)) { code = syncCode(host, timeoutMs); codeHost = host; }
        }
        JSONObject req = new JSONObject();
        req.put("intra_server_version", 7);
        req.put("is_addon_combo", 0);
        req.put("sync_code", code);
        req.put("category_ids", "-1");
        req.put("kot_ids", new JSONArray());
        req.put("order_type", types);
        req.put("release_kot_on", "10");
        req.put("user", USER);
        req.put("clientIdentifier", CLIENT);
        req.put("sub_order_type_ids", "");
        req.put("item_ids", "-1");
        req.put("table_ids", "-1");
        try {
            JSONObject r = new JSONObject(post(base(host) + "petpooja_server/get_kot_data_for_kitchen_view", req.toString(), timeoutMs));
            JSONArray d = r.optJSONArray("data");
            return d == null ? new JSONArray() : d;
        } catch (Exception e) {
            synchronized (Petpooja.class) { code = ""; } // a fresh sync code next time
            throw e;
        }
    }

    // the KDS app's names for the aggregators (o_u_id, live and staging)
    private static final String[][] AGG = {
            {"5409", "3481", "Zomato"}, {"14347", "3554", "Ubereats"}, {"6736", "3495", "Swiggy"},
            {"13554", "3566", "Dunzo"}, {"38913", "3658", "Google pay"}, {"39481", "3663", "Amazon"},
            {"23098", "3693", "Uengage"}, {"40177", "3685", "Dotpe"}, {"4117", "3458", "Own Website"},
            {"307753", "307753", "Toing By Swiggy"},
    };

    private static String str(JSONObject r, String k) {
        Object v = r.opt(k);
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v).trim();
    }

    private static double num(JSONObject r, String k, double def) {
        try { String s = str(r, k); return s.isEmpty() ? def : Double.parseDouble(s); } catch (Throwable t) { return def; }
    }

    static long created(String s) {
        String[] fs = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm", "dd-MM-yyyy HH:mm:ss"};
        for (String f : fs) {
            try {
                SimpleDateFormat d = new SimpleDateFormat(f, Locale.US);
                d.setLenient(false);
                return d.parse(s.trim()).getTime();
            } catch (Throwable ignored) {
            }
        }
        return 0;
    }

    /**
     * The open KOTs, as the Console's kitchen feed has them: {now, skew 0, cards: [{k, kn, ch, app,
     * l4, c, i: [{n, c, q, r}]}]}. ch: D online, P pick up, I dine in, S self service.
     */
    static JSONObject kitchen(String host, java.util.Set<String> kinds, int timeoutMs) throws Exception {
        String types = orderTypes(kinds);
        JSONArray rows = rows(host, types, timeoutMs);
        if (rows.length() >= 295 && types.contains("1")) { // the billing PC sends at most 300 rows, oldest first
            JSONArray more = rows(host, "1", timeoutMs);
            for (int i = 0; i < more.length(); i++) rows.put(more.get(i));
        }
        Map<Long, JSONObject> cards = new LinkedHashMap<Long, JSONObject>();
        Map<Long, String> status = new LinkedHashMap<Long, String>();
        Map<Long, Boolean> allOut = new LinkedHashMap<Long, Boolean>(); // every dish dispatched
        for (int i = 0; i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null) continue;
            long id = Math.round(num(r, "id", 0));
            if (id == 0) continue;
            JSONObject c = cards.get(id);
            if (c == null) {
                String ouid = str(r, "o_u_id"), brand = str(r, "tp_virtual_brand_name"), plat = "";
                for (String[] a : AGG) if (!ouid.isEmpty() && (ouid.equals(a[0]) || ouid.equals(a[1]))) plat = a[2];
                if (plat.isEmpty()) plat = str(r, "online_order_user_name");
                if (plat.isEmpty()) plat = "Online";
                String chName = !brand.isEmpty() ? brand : !ouid.isEmpty() ? "Online" : "";
                boolean online = !ouid.isEmpty();
                int ot = (int) Math.round(num(r, "order_type", 0));
                String app = online && !"Online".equals(plat) ? plat : !chName.isEmpty() ? chName : ot == 1 ? "Delivery" : "Online";
                String b = (app + " " + chName).toLowerCase(Locale.US);
                String ch = b.contains("self service") || b.contains("self-service") ? "S" : online || ot == 1 ? "D" : ot == 2 ? "P" : "I";
                long tok = Math.round(num(r, "kot_unique_id", 0));
                String table = str(r, "table_no");
                String kn = tok > 0 ? String.valueOf(tok) : !table.isEmpty() ? "T" + table : "#" + (id % 1000);
                String digits = str(r, "p_o_id").replaceAll("\\D", "");
                c = new JSONObject();
                c.put("k", id);
                c.put("kn", kn);
                c.put("ch", ch);
                if ("D".equals(ch)) {
                    c.put("app", app);
                    c.put("l4", digits.length() > 4 ? digits.substring(digits.length() - 4) : digits);
                }
                c.put("c", created(str(r, "created")));
                c.put("i", new JSONArray());
                cards.put(id, c);
            }
            String ks = str(r, "kot_status");
            if (!ks.isEmpty()) status.put(id, ks);
            String name = str(r, "name");
            if (name.isEmpty()) continue;
            int s = (int) Math.round(num(r, "status", 0));
            if (s < 0) continue; // cancelled dish
            allOut.put(id, (allOut.containsKey(id) ? allOut.get(id) : true) && s == 10);
            // as the Petpooja KDS (BuildKotBoardUseCase.isValidForCategory): a dispatched dish (10) and
            // a dish replaced in an edited KOT (changed 1) are not shown; a KOT with none left is gone
            if (s == 10 || Math.round(num(r, "changed", 0)) == 1) continue;
            c.getJSONArray("i").put(new JSONObject().put("n", name).put("c", str(r, "category"))
                    .put("q", Math.max(num(r, "quantity", 1), 0) > 0 ? num(r, "quantity", 1) : 1).put("r", s == 9 || s == 10));
        }
        JSONArray out = new JSONArray();
        for (Map.Entry<Long, JSONObject> e : cards.entrySet()) {
            String ks = status.get(e.getKey());
            if ("0".equals(ks) || "10".equals(ks)) continue; // cancelled, dispatched
            if (Boolean.TRUE.equals(allOut.get(e.getKey()))) continue; // every dish dispatched
            if ("9".equals(ks)) { // the whole KOT marked Food ready
                JSONArray its = e.getValue().getJSONArray("i");
                for (int i = 0; i < its.length(); i++) its.getJSONObject(i).put("r", true);
            }
            out.put(e.getValue());
        }
        return new JSONObject().put("now", System.currentTimeMillis()).put("skew", 0).put("cards", out).put("src", "petpooja");
    }
}
