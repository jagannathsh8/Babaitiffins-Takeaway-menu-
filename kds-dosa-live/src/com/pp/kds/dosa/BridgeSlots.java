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
    private static volatile String lastSample = "";

    /** Start of Bridge Print's data (for the setup window, so field names can be checked). */
    static String sample() {
        return lastSample;
    }
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

    /** Bridge Print is reachable: the board and calls run from its orders (no Petpooja needed). */
    static boolean hasStatus() {
        return true;
    }

    /** Ready (1) / preparing (0) / picked up (2) per order ID as the KDS sees it (Petpooja). */
    private static volatile Map<String, Integer> kds = Collections.emptyMap();

    static void setKdsKinds(Map<String, Integer> m) {
        kds = m;
    }

    private static int kdsKind(String order) {
        Map<String, Integer> m = kds;
        Integer k = m.get(order);
        if (k != null) return k;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            String key = e.getKey();
            if (Math.min(key.length(), order.length()) >= 8 && (key.endsWith(order) || order.endsWith(key))) return e.getValue();
        }
        return -1;
    }

    /** Bridge Print's orders as rider orders (ready / preparing / picked up from its own status). */
    static java.util.List<RiderCalls.Order> orders() {
        java.util.List<RiderCalls.Order> out = new java.util.ArrayList<RiderCalls.Order>();
        for (Rec r : records) {
            int kind = kindOf(r);
            if (kind < 0) kind = kdsKind(r.order);   // Petpooja status via the KDS, if this device has it
            if (kind < 0) kind = 0;                  // Bridge Print shows "Preparing" until Food Ready
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
        return lastOkAt > 0 && System.currentTimeMillis() - lastOkAt < 30_000L;
    }

    /** One-line status for the settings dialog. */
    static String status() {
        if (address == null || address.isEmpty()) return "Not set";
        if (lastOkAt == 0) return lastError.isEmpty() ? "Connecting\u2026" : "Not reachable: " + lastError;
        long ago = (System.currentTimeMillis() - lastOkAt) / 1000;
        int withStatus = 0;
        for (Rec r : records) if (kindOf(r) >= 0) withStatus++;
        String st = (ago < 30 ? "Connected" : "Last read " + ago + " s ago") + " \u2022 " + slots.size()
                + " orders \u2022 " + withStatus + " with ready/preparing";
        return lastError.isEmpty() ? st : st + " \u2022 last error: " + lastError;
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
                lastSample = p + "  \u2192  " + (body.length() > 900 ? body.substring(0, 900) + "\u2026" : body);
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
                String m = t.getMessage();
                lastError = t.getClass().getSimpleName() + (m == null ? "" : ": " + (m.length() > 60 ? m.substring(0, 60) : m));
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
        c.setRequestProperty("Connection", "close"); // small local servers can stall on reused connections
        c.setUseCaches(false);
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
        Boolean readyFlag;   // from fields like isReady / foodReady / readyAt
        String hint = "";    // any text value mentioning ready / preparing
        String group = "";   // name of the list / section the order sits in ("ready": [...], "preparing": [...])
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
        walk(root, out, 0, "");
        return new java.util.ArrayList<Rec>(out.values());
    }

    /** A list / section name that tells the status, e.g. "readyOrders", "preparing", "foodReady". */
    private static boolean statusWord(String key) {
        String l = key.toLowerCase(java.util.Locale.US);
        return l.contains("ready") || l.contains("prepar") || l.contains("pending") || l.contains("done")
                || l.contains("complete") || l.contains("progress") || l.contains("cook") || l.contains("kitchen")
                || l.contains("picked") || l.contains("dispatch") || l.equals("new") || l.startsWith("new");
    }

    private static void walk(Object node, Map<String, Rec> out, int depth, String group) {
        if (depth > 12 || node == null) return;
        if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) walk(a.opt(i), out, depth + 1, group);
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
            r.group = group;
            if (!out.containsKey(r.order)) out.put(r.order, r);
            return;
        }
        keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = o.opt(k);
            if (v instanceof JSONObject || v instanceof JSONArray) walk(v, out, depth + 1, statusWord(k) ? k : group);
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
                    && !sv.isEmpty()) {
                r.status = sv; // text ("Food Ready") or a code ("9")
            } else if (r.readyFlag == null && (lk.contains("ready") || lk.equals("done") || lk.equals("isdone")
                    || lk.equals("completed") || lk.equals("iscompleted") || lk.equals("served"))) {
                // isReady:true / foodReady:1 / readyAt:"2026-..." -> ready; false / 0 / "" -> not yet
                String lv = sv.toLowerCase(java.util.Locale.US);
                r.readyFlag = !(lv.isEmpty() || lv.equals("false") || lv.equals("0") || lv.equals("null") || lv.equals("no"));
            } else if (r.platform.isEmpty() && (lk.contains("platform") || lk.contains("source") || lk.contains("channel")
                    || lk.contains("aggregator") || lk.contains("partner") || lk.contains("brand") || lk.contains("provider"))
                    && d.length() < sv.length()) {
                r.platform = sv;
            } else if (r.timeMs == 0 && (lk.contains("created") || lk.contains("received") || lk.contains("time")
                    || lk.contains("date"))) {
                r.timeMs = parseTime(sv);
            }
            if (r.readyFlag == null && (lk.contains("color") || lk.contains("colour") || lk.equals("bg")
                    || lk.contains("background") || lk.contains("theme"))) {
                int c = colourKind(sv);            // Bridge Print cards: green = ready, yellow = preparing
                if (c >= 0) r.readyFlag = c == 1;
            }
            if (r.platform.isEmpty() && sv.length() <= 30) {
                String lv = sv.toLowerCase(java.util.Locale.US);
                if (lv.contains("swiggy") || lv.contains("zomato") || lv.contains("ownly") || lv.contains("toing")
                        || lv.contains("magicpin")) r.platform = sv;
            }
            if (r.hint.isEmpty() && d.length() < sv.length()) {
                String lv = sv.toLowerCase(java.util.Locale.US);
                if (lv.contains("ready") || lv.contains("prepar")) r.hint = sv;
            }
        }
    }

    /** 1 = green-ish (ready), 0 = yellow / amber (preparing), -1 = not a status colour. */
    static int colourKind(String v) {
        String l = v.trim().toLowerCase(java.util.Locale.US);
        if (l.contains("green") || l.contains("success")) return 1;
        if (l.contains("yellow") || l.contains("amber") || l.contains("warn") || l.contains("olive")) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#([0-9a-f]{6})").matcher(l);
        if (!m.find()) return -1;
        int rgb = Integer.parseInt(m.group(1), 16);
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        if (g > r + 25 && g > b + 25) return 1;
        if (r > 140 && g > 120 && b < 110 && Math.abs(r - g) < 70) return 0;
        return -1;
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

    /** Ready (1) / preparing (0) / picked up (2) / unknown (-1) from everything the record carries. */
    static int kindOf(Rec r) {
        int k = statusKind(r.status);
        if (k >= 0) return k;
        if (r.readyFlag != null) return r.readyFlag ? 1 : 0;
        k = statusKind(r.hint);
        if (k >= 0) return k;
        String g = r.group.toLowerCase(java.util.Locale.US);
        if (g.contains("done") || g.contains("complete")) return 1;
        return statusKind(r.group);
    }

    /** "Food Ready" / 9 -> 1, "Preparing" / 1..8 -> 0, picked up / dispatched / 10 -> 2, unknown -> -1. */
    static int statusKind(String status) {
        String l = status == null ? "" : status.trim().toLowerCase(java.util.Locale.US);
        if (!l.isEmpty() && digits(l).length() == l.replace("-", "").length()) { // numeric code (Petpooja style)
            try {
                int c = Integer.parseInt(l);
                if (c == 9) return 1;
                if (c == 10 || c <= 0) return 2;
                return 0;
            } catch (Throwable t) {
                return -1;
            }
        }
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
