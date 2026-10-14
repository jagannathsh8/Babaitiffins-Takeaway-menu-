package com.pp.kds.dosa;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Rider screen orders from the Babai Hub PC on the shop Wi-Fi (http://PC:8790/rider.json): every
 * online order straight from Petpooja (not limited to what this KDS board shows), its Bridge Print
 * slot and its "ready since" time kept on the PC. Read every 2 s on a background thread.
 *
 * When the hub can't be reached for a few seconds, {@link #fresh()} turns false and the rider
 * screen goes back to the KDS board by itself; nothing goes blank.
 *
 * Address: set in the rider settings, or found by itself (the Petpooja PC first, then the Wi-Fi).
 */
final class HubFeed {

    static final int PORT = 8790;
    private static final long POLL_MS = 1_000L;
    private static final long FRESH_MS = 10_000L;
    private static final long FIND_EVERY_MS = 5 * 60_000L;

    private static volatile String manual = "";      // typed in the settings
    private static volatile String found = "";       // found by itself
    private static volatile List<RiderCalls.Order> orders = Collections.emptyList();
    private static volatile Map<String, DosaLive.RiderInfo> info = Collections.emptyMap();
    private static volatile long okAt;
    private static volatile boolean feedOk, bridgeOk;
    private static volatile String lastError = "", note = "";
    private static volatile String outlet = "";
    private static volatile long lastFindAt;
    private static Thread worker;
    private static Context app;
    private static int failures;

    private HubFeed() {}

    static synchronized void start(Context c) {
        app = c.getApplicationContext();
        SharedPreferences p = prefs();
        manual = p.getString("hub_addr", "");
        found = p.getString("hub_found", "");
        if (worker != null) return;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    long t0 = System.currentTimeMillis();
                    try {
                        step();
                    } catch (Throwable ignored) {
                    }
                    long wait = POLL_MS - (System.currentTimeMillis() - t0);
                    try {
                        Thread.sleep(Math.max(300L, wait));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "babai-hub-feed");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE);
    }

    /** Settings: "" = find the hub by itself. */
    static void setManual(String addr) {
        manual = norm(addr);
        prefs().edit().putString("hub_addr", manual).apply();
        failures = 0;
        okAt = 0;
        lastFindAt = 0;
    }

    static String manual() {
        return manual;
    }

    static String address() {
        return manual.isEmpty() ? found : manual;
    }

    /** The hub answered within the last 10 s with good Petpooja data. */
    static boolean fresh() {
        return feedOk && System.currentTimeMillis() - okAt < FRESH_MS;
    }

    static boolean bridgeOk() {
        return bridgeOk;
    }

    static List<RiderCalls.Order> orders() {
        // copies: the rider board fills in slots on its own copies
        List<RiderCalls.Order> src = orders, out = new ArrayList<RiderCalls.Order>(src.size());
        for (RiderCalls.Order o : src) {
            RiderCalls.Order c = new RiderCalls.Order(o.id, o.platform, o.last4, o.orderId, o.slot, o.createdMs, o.ready, o.gone);
            c.readyAtHint = o.readyAtHint;
            out.add(c);
        }
        return out;
    }

    static Map<String, DosaLive.RiderInfo> info() {
        return info;
    }

    static String status() {
        String a = address();
        if (a.isEmpty()) return note.isEmpty() ? "looking for the Babai Hub on the Wi-Fi…" : note;
        if (fresh()) return "connected to " + a + (outlet.isEmpty() ? "" : " (" + outlet + ")") + " • "
                + orders.size() + " online orders";
        if (okAt > 0 && !feedOk) return a + ": the hub can't read Petpooja right now";
        return a + (lastError.isEmpty() ? ": connecting…" : ": " + lastError);
    }

    // ---- background ---------------------------------------------------------------------------

    private static void step() {
        String a = address();
        if (a.isEmpty()) {
            find();
            return;
        }
        try {
            read(a);
            failures = 0;
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName().replace("Exception", "") + (t.getMessage() == null ? ""
                    : ": " + (t.getMessage().length() > 60 ? t.getMessage().substring(0, 60) : t.getMessage()));
            if (++failures >= 15 && manual.isEmpty()) {   // ~30 s: the hub PC may have a new address
                found = "";
                prefs().edit().putString("hub_found", "").apply();
                failures = 0;
                lastFindAt = 0;
            }
        }
    }

    private static void read(String addr) throws Exception {
        long sent = System.currentTimeMillis();
        String body = get("http://" + addr + "/rider.json", 3_000);
        long got = System.currentTimeMillis();
        JSONObject j = new JSONObject(body);
        // The PC's clock and this device's clock may differ: move the hub's times onto ours.
        long hubNow = j.optLong("now", 0);
        long offset = hubNow > 0 ? (sent + got) / 2 - hubNow : 0;
        JSONArray a = j.optJSONArray("orders");
        List<RiderCalls.Order> out = new ArrayList<RiderCalls.Order>();
        Map<String, DosaLive.RiderInfo> inf = new HashMap<String, DosaLive.RiderInfo>();
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || !o.has("id")) continue;
                String oid = o.optString("o", "");
                String platform = o.optString("p", "Online");
                long created = o.optLong("c", 0);
                if (created > 0) created += offset;
                long ra = o.optLong("ra", 0);
                if (ra > 0) ra += offset;
                String last4 = RiderCalls.last4(oid);
                if (last4.isEmpty()) last4 = RiderCalls.last4(String.valueOf(o.optLong("id")));
                RiderCalls.Order ord = new RiderCalls.Order(o.optLong("id"), platform, last4, oid, o.optString("s", ""),
                        created, o.optInt("r", 0) == 1, false);
                ord.readyAtHint = ra;
                out.add(ord);
                String key = RiderOcr.digits(oid);
                if (key.length() >= 4) {
                    DosaLive.RiderInfo ri = new DosaLive.RiderInfo();
                    ri.createdMs = created;
                    JSONArray items = o.optJSONArray("i");
                    if (items != null) for (int k = 0; k < items.length(); k++) ri.items.add(items.optString(k));
                    inf.put(key, ri);
                }
            }
        }
        JSONArray hide = j.optJSONArray("hide");
        if (hide != null) {
            List<String> names = new ArrayList<String>();
            for (int i = 0; i < hide.length(); i++) names.add(hide.optString(i));
            HiddenBrands.fromHub(app, names);
        }
        orders = out;
        info = inf;
        feedOk = j.optBoolean("ok", false);
        bridgeOk = j.optBoolean("bridge", false);
        outlet = j.optString("outlet", "");
        okAt = got;
        lastError = "";
    }

    /** The Petpooja PC first (the hub often runs there), then every address on this Wi-Fi. */
    private static void find() {
        long now = System.currentTimeMillis();
        if (now - lastFindAt < FIND_EVERY_MS) return;
        lastFindAt = now;
        note = "looking for the Babai Hub on the Wi-Fi…";
        List<String> cands = new ArrayList<String>();
        String pp = PetpoojaServer.ip(app);
        if (pp != null && !pp.isEmpty()) cands.add(pp);
        String mine = myIp();
        if (mine != null) {
            String pre = mine.substring(0, mine.lastIndexOf('.') + 1);
            for (int i = 1; i < 255; i++) {
                String c = pre + i;
                if (!c.equals(mine) && !cands.contains(c)) cands.add(c);
            }
        }
        if (cands.isEmpty()) {
            note = "Not on a Wi-Fi network";
            return;
        }
        final AtomicReference<String> hit = new AtomicReference<String>();
        if (isHub(cands.get(0) + ":" + PORT, 800)) hit.set(cands.get(0) + ":" + PORT);
        if (hit.get() == null) {
            ExecutorService pool = Executors.newFixedThreadPool(24);
            for (final String c : cands) {
                pool.execute(new Runnable() {
                    @Override public void run() {
                        if (hit.get() == null && isHub(c + ":" + PORT, 600)) hit.compareAndSet(null, c + ":" + PORT);
                    }
                });
            }
            pool.shutdown();
            try {
                pool.awaitTermination(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            pool.shutdownNow();
        }
        String h = hit.get();
        if (h != null) {
            found = h;
            prefs().edit().putString("hub_found", h).apply();
            note = "found by itself at " + h;
        } else {
            note = "Babai Hub not found on this Wi-Fi • checking every 5 min (or type its address)";
        }
    }

    private static boolean isHub(String addr, int timeout) {
        try {
            return get("http://" + addr + "/ping", timeout).startsWith("babai-hub");
        } catch (Throwable t) {
            return false;
        }
    }

    private static String myIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface n = ifs.nextElement();
                if (!n.isUp() || n.isLoopback()) continue;
                Enumeration<InetAddress> as = n.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress ad = as.nextElement();
                    if (ad instanceof Inet4Address && ad.isSiteLocalAddress()) return ad.getHostAddress();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static String norm(String s) {
        if (s == null) return "";
        s = s.trim().replaceFirst("^https?://", "").replaceAll("[/?#].*$", "");
        if (s.isEmpty()) return "";
        if (!s.contains(":")) s = s + ":" + PORT;
        return s;
    }

    private static String get(String url, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(Math.max(timeout, 3_000));
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 4_000_000) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
