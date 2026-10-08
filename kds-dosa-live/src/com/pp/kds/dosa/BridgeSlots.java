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
    private static volatile java.util.List<Rec> records = Collections.emptyList();
    private static final Map<String, Long> firstSeen = new java.util.concurrent.ConcurrentHashMap<String, Long>();
    /** order digits -> last time Bridge Print listed it (to notice when it was cleared / picked up). */
    private static final Map<String, Long> lastListed = new java.util.concurrent.ConcurrentHashMap<String, Long>();
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

    /**
     * True when Bridge Print listed this order earlier but no longer does for 10 s while the
     * connection is fine: staff cleared it there (rider picked it up / Clear all).
     */
    static boolean pickedUp(String orderId) {
        if (orderId == null || !connected()) return false;
        String d = digits(orderId);
        if (d.length() < 4) return false;
        Long t = lastListed.get(d);
        if (t == null) {
            for (Map.Entry<String, Long> e : lastListed.entrySet()) {
                String k = e.getKey();
                if (Math.min(k.length(), d.length()) >= 8 && (k.endsWith(d) || d.endsWith(k))) {
                    t = e.getValue();
                    break;
                }
            }
        }
        return t != null && lastOkAt - t > 10_000L;
    }

    /** Bridge Print gives order status (ready / preparing): the board and calls can run from it alone. */
    static boolean hasStatus() {
        for (Rec r : records) if (statusKind(r.status) >= 0) return true;
        return false;
    }

    /** Bridge Print's orders as rider orders (ready / preparing / picked up from its own status). */
    static java.util.List<RiderCalls.Order> orders() {
        java.util.List<RiderCalls.Order> out = new java.util.ArrayList<RiderCalls.Order>();
        for (Rec r : records) {
            int kind = statusKind(r.status);
            if (kind < 0) continue;
            String plat = r.platform.isEmpty() ? "Online" : r.platform;
            String od = r.order.length() > 18 ? r.order.substring(r.order.length() - 18) : r.order;
            long id;
            try {
                id = Long.parseLong(od);
            } catch (Throwable t) {
                id = r.order.hashCode();
            }
            Long seen = firstSeen.get(r.order);
            long created = r.timeMs > 0 ? r.timeMs : seen == null ? System.currentTimeMillis() : seen;
            out.add(new RiderCalls.Order(id, plat, RiderCalls.last4(r.order), r.order, r.token, created,
                    kind == 1, kind == 2));
        }
        return out;
    }

    /** Read Bridge Print successfully within the last 15 s. */
    static boolean connected() {
        return lastOkAt > 0 && System.currentTimeMillis() - lastOkAt < 15_000L;
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
                java.util.List<Rec> recs = parseOrders(body);
                Map<String, String> m = new HashMap<String, String>();
                for (Rec r : recs) m.put(r.order, r.token);
                if (!m.isEmpty() || workingPath != null || body.trim().startsWith("{") || body.trim().startsWith("[")) {
                    workingPath = p;
                    slots = m;
                    records = recs;
                    long tnow = System.currentTimeMillis();
                    for (Rec r : recs) if (!firstSeen.containsKey(r.order)) firstSeen.put(r.order, tnow);
                    if (firstSeen.size() > 3000) firstSeen.clear();
                    lastOkAt = System.currentTimeMillis();
                    for (String k : m.keySet()) lastListed.put(k, lastOkAt);
                    if (lastListed.size() > 3000) lastListed.clear();
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
    /** One order as Bridge Print lists it. */
    static final class Rec {
        String order = "", token = "", status = "", platform = "";
        long timeMs;
    }

    static Map<String, String> parse(String body) throws Exception {
        Map<String, String> out = new HashMap<String, String>();
        for (Rec r : parseOrders(body)) out.put(r.order, r.token);
        return out;
    }

    /** Reads any JSON shape: every object (plus its direct children) that has an order ID and a token. */
    static java.util.List<Rec> parseOrders(String body) throws Exception {
        java.util.LinkedHashMap<String, Rec> out = new java.util.LinkedHashMap<String, Rec>();
        String t = body.trim();
        Object root = t.startsWith("[") ? new JSONArray(t) : new JSONObject(t);
        walk(root, out, 0);
        return new java.util.ArrayList<Rec>(out.values());
    }

    private static void walk(Object node, Map<String, Rec> out, int depth) {
        if (depth > 12 || node == null) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), out, depth + 1);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;
        Rec r = new Rec();
        read(o, r, false);
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            Object v = o.opt(keys.next());
            if (v instanceof JSONObject) read((JSONObject) v, r, true); // one level down
        }
        if (!r.order.isEmpty() && !r.token.isEmpty()) {
            if (!out.containsKey(r.order)) out.put(r.order, r);
            return;
        }
        keys = o.keys();
        while (keys.hasNext()) {
            Object v = o.opt(keys.next());
            if (v instanceof JSONObject || v instanceof JSONArray) walk(v, out, depth + 1);
        }
    }

    private static void read(JSONObject o, Rec r, boolean child) {
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = o.opt(k);
            if (v instanceof JSONObject || v instanceof JSONArray || v == null || v == JSONObject.NULL) continue;
            String lk = k.toLowerCase(java.util.Locale.US).replace("_", "").replace("-", "");
            String sv = String.valueOf(v).trim();
            String d = digits(sv);
            if (r.order.isEmpty() && (lk.contains("orderid") || lk.equals("order") || lk.contains("orderno")
                    || lk.contains("orderuid") || lk.contains("poid") || (child && lk.equals("id"))) && d.length() >= 6) {
                r.order = d;
            } else if (r.token.isEmpty() && (lk.contains("token") || lk.contains("slot")) && !d.isEmpty() && d.length() <= 6) {
                r.token = String.valueOf(Integer.parseInt(d));
            } else if (r.status.isEmpty() && (lk.contains("status") || lk.equals("state") || lk.equals("stage"))
                    && d.length() < sv.length()) {
                r.status = sv;
            } else if (r.platform.isEmpty() && (lk.contains("platform") || lk.contains("source") || lk.contains("channel")
                    || lk.contains("aggregator") || lk.contains("partner") || lk.contains("brand") || lk.contains("provider"))
                    && d.length() < sv.length()) {
                r.platform = sv;
            } else if (r.timeMs == 0 && (lk.contains("created") || lk.contains("received") || lk.contains("time")
                    || lk.contains("date"))) {
                r.timeMs = parseTime(sv);
            }
        }
    }

    /** Epoch seconds / millis, or "yyyy-MM-dd HH:mm(:ss)" / ISO; 0 when unknown. */
    private static long parseTime(String sv) {
        try {
            String d = digits(sv);
            if (d.length() == sv.length()) {
                long n = Long.parseLong(d);
                if (n > 1_000_000_000_000L) return n;          // millis
                if (n > 1_000_000_000L) return n * 1000L;       // seconds
                return 0;
            }
            String norm = sv.replace('T', ' ');
            if (norm.length() >= 16) {
                String fmt = norm.length() >= 19 ? "yyyy-MM-dd HH:mm:ss" : "yyyy-MM-dd HH:mm";
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(fmt, java.util.Locale.US);
                if (norm.endsWith("Z")) f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f.parse(norm.substring(0, fmt.length())).getTime();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** "Food Ready" -> 1, "Preparing" -> 0, picked up / dispatched / delivered -> 2, unknown -> -1. */
    static int statusKind(String status) {
        String l = status == null ? "" : status.toLowerCase(java.util.Locale.US);
        if (l.contains("pick") || l.contains("dispatch") || l.contains("deliver") || l.contains("handed")
                || l.contains("complete") || l.contains("cancel")) return 2;
        if (l.contains("ready")) return 1;
        if (l.contains("prepar") || l.contains("cook") || l.contains("accept") || l.contains("new")
                || l.contains("pending") || l.contains("progress") || l.contains("kitchen")) return 0;
        return -1;
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
