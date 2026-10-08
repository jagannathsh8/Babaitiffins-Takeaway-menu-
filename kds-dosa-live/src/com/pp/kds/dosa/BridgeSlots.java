package com.pp.kds.dosa;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Pickup slot numbers from Bridge Print (the PC that prints the KOT slips and runs the rider
 * screen). Bridge Print assigns its own token / slot per order; its rider screen refreshes from
 * a small JSON "state" endpoint on the shop Wi-Fi (e.g. http://192.168.1.3:8787/state).
 *
 * A background thread reads it every few seconds and keeps a map "order ID digits -> slot".
 * The JSON is read generically: any object with an order-ID-like field and a token/slot field.
 */
final class BridgeSlots {

    private static final String[] PATHS = {"/state", "/api/state", "/display/state", "/api/display/state"};
    private static final long POLL_MS = 3_000L;

    private static volatile Map<String, String> slots = Collections.emptyMap();
    private static volatile String address;      // "192.168.1.3:8787"
    private static volatile String workingPath;
    private static volatile long lastOkAt;
    private static volatile String lastError = "";
    private static Thread worker;

    private BridgeSlots() {}

    static synchronized void setAddress(String addr) {
        address = addr == null ? null : addr.trim().replaceFirst("^https?://", "").replaceAll("/.*$", "");
        workingPath = null;
        slots = Collections.emptyMap();
        if (worker == null && address != null && !address.isEmpty()) {
            worker = new Thread(new Runnable() {
                @Override public void run() {
                    while (true) {
                        try {
                            if (DosaLive.riderCallsOn() && address != null && !address.isEmpty()) pollOnce();
                        } catch (Throwable ignored) {
                        }
                        try {
                            Thread.sleep(POLL_MS);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            }, "bridge-slots");
            worker.setDaemon(true);
            worker.start();
        }
    }

    static String address() {
        return address;
    }

    /** Slot for a platform order ID (as the KDS has it), or "" when unknown. */
    static String slotFor(String orderId) {
        if (orderId == null) return "";
        String d = digits(orderId);
        if (d.length() < 4) return "";
        Map<String, String> m = slots;
        String s = m.get(d);
        if (s != null) return s;
        // Same order written slightly differently: accept a match on the last 8+ digits.
        for (Map.Entry<String, String> e : m.entrySet()) {
            String k = e.getKey();
            int n = Math.min(k.length(), d.length());
            if (n >= 8 && (k.endsWith(d) || d.endsWith(k))) return e.getValue();
        }
        return "";
    }

    /** One-line status for the settings dialog. */
    static String status() {
        if (address == null || address.isEmpty()) return "Not set";
        if (lastOkAt == 0) return lastError.isEmpty() ? "Connecting\u2026" : "Not reachable: " + lastError;
        long ago = (System.currentTimeMillis() - lastOkAt) / 1000;
        return "Connected \u2022 " + slots.size() + " orders with slots \u2022 " + ago + " s ago";
    }

    private static final Object POLL_LOCK = new Object();

    /** Fetches once (poll thread / "test" button; never call on the main thread). */
    static boolean pollOnce() {
        synchronized (POLL_LOCK) {
            return pollLocked();
        }
    }

    private static boolean pollLocked() {
        String addr = address;
        if (addr == null || addr.isEmpty()) return false;
        String[] tryPaths = workingPath != null ? new String[]{workingPath} : PATHS;
        for (String p : tryPaths) {
            try {
                String body = get("http://" + addr + p);
                Map<String, String> m = parse(body);
                if (!m.isEmpty() || workingPath != null || body.trim().startsWith("{") || body.trim().startsWith("[")) {
                    workingPath = p;
                    slots = m;
                    lastOkAt = System.currentTimeMillis();
                    lastError = "";
                    return true;
                }
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName();
            }
        }
        workingPath = null;
        return false;
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2500);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 2_000_000) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Reads any JSON shape: collects objects that carry both an order ID and a token / slot. */
    static Map<String, String> parse(String body) throws Exception {
        Map<String, String> out = new HashMap<String, String>();
        String t = body.trim();
        Object root = t.startsWith("[") ? new JSONArray(t) : new JSONObject(t);
        walk(root, out, 0);
        return out;
    }

    private static void walk(Object node, Map<String, String> out, int depth) {
        if (depth > 12 || node == null) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), out, depth + 1);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        String order = null, token = null;
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = o.opt(k);
            String lk = k.toLowerCase(java.util.Locale.US).replace("_", "");
            if (v instanceof JSONObject || v instanceof JSONArray) {
                walk(v, out, depth + 1);
                continue;
            }
            String sv = v == null ? "" : String.valueOf(v);
            if (order == null && (lk.contains("orderid") || lk.equals("order") || lk.contains("orderno")
                    || lk.contains("orderuid") || lk.contains("poid"))) {
                String d = digits(sv);
                if (d.length() >= 6) order = d;
            } else if (token == null && (lk.contains("token") || lk.contains("slot"))) {
                String d = digits(sv);
                if (!d.isEmpty() && d.length() <= 6) token = String.valueOf(Integer.parseInt(d));
            }
        }
        // Order ID or token one level down (e.g. {"token":9,"order":{"orderId":"..."}}).
        if (order == null || token == null) {
            Iterator<String> ks = o.keys();
            while (ks.hasNext() && (order == null || token == null)) {
                Object v = o.opt(ks.next());
                if (!(v instanceof JSONObject)) continue;
                JSONObject c = (JSONObject) v;
                Iterator<String> ck = c.keys();
                while (ck.hasNext()) {
                    String k = ck.next();
                    String lk = k.toLowerCase(java.util.Locale.US).replace("_", "");
                    String d = digits(String.valueOf(c.opt(k)));
                    if (order == null && (lk.contains("orderid") || lk.equals("id") || lk.contains("orderno"))
                            && d.length() >= 6) order = d;
                    else if (token == null && (lk.contains("token") || lk.contains("slot"))
                            && !d.isEmpty() && d.length() <= 6) token = String.valueOf(Integer.parseInt(d));
                }
            }
        }
        if (order != null && token != null) out.put(order, token);
    }

    static String digits(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') b.append(c);
        }
        return b.toString();
    }
}
