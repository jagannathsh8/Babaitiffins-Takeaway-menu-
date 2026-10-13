package com.babai.scanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sends scans to the Babai Hub, one at a time, in order, and never loses one:
 *   1. shop Wi-Fi: POST http://<hub>/scan (fast, works without internet);
 *   2. internet: Firebase write .../sq/<key>/<id>, then read the hub's answer at .../sr/<key>/<id>;
 *   3. neither works: the scan waits in the queue (kept when the app closes) and is sent later.
 * The hub marks a scan id once, so a scan that reaches it both ways is still marked once.
 */
final class Sender {

    interface Listener {
        void onResult(Result r);
        void onQueue(int waiting, String route);
    }

    static final class Result {
        String id, code, msg = "", brand = "", order = "", last4 = "", slot = "", route = "";
        boolean ok, already, failed;
        long at;
    }

    private static final long MAX_AGE_MS = 9 * 60_000L;   // the hub drops older queued scans
    private static Sender instance;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final Object lock = new Object();
    private Thread worker;
    private volatile String route = "";
    private volatile String goodHost = "";
    private final SecureRandom rnd = new SecureRandom();

    static synchronized Sender get(Context c) {
        if (instance == null) instance = new Sender(c.getApplicationContext());
        return instance;
    }

    private Sender(Context c) {
        app = c;
    }

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    String route() {
        return route;
    }

    // ---- queue (kept in prefs) -------------------------------------------------------------------

    private SharedPreferences prefs() {
        return Pairing.prefs(app);
    }

    private JSONArray queue() {
        try {
            return new JSONArray(prefs().getString("queue", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    int waiting() {
        synchronized (lock) {
            return queue().length();
        }
    }

    /** A scanned code: queued and sent as soon as possible. */
    void enqueue(String code, String source) {
        String c = code == null ? "" : code.trim();
        if (c.isEmpty() || c.length() > 80) return;
        synchronized (lock) {
            try {
                JSONArray q = queue();
                JSONObject o = new JSONObject();
                o.put("id", newId());
                o.put("c", c);
                o.put("t", System.currentTimeMillis());
                o.put("src", source);
                q.put(o);
                prefs().edit().putString("queue", q.toString()).commit();
            } catch (Exception ignored) {
            }
            lock.notifyAll();
        }
        start();
        notifyQueue();
    }

    private String newId() {
        String abc = "abcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder b = new StringBuilder(Long.toString(System.currentTimeMillis(), 36));
        b.append('-');
        for (int i = 0; i < 6; i++) b.append(abc.charAt(rnd.nextInt(abc.length())));
        return b.toString();
    }

    private void removeHead(String id) {
        synchronized (lock) {
            JSONArray q = queue(), out = new JSONArray();
            for (int i = 0; i < q.length(); i++) {
                JSONObject o = q.optJSONObject(i);
                if (o != null && !id.equals(o.optString("id"))) out.put(o);
            }
            prefs().edit().putString("queue", out.toString()).commit();
        }
    }

    synchronized void start() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                loop();
            }
        }, "babai-sender");
        worker.setDaemon(true);
        worker.start();
    }

    private void loop() {
        while (true) {
            JSONObject head;
            synchronized (lock) {
                JSONArray q = queue();
                head = q.length() > 0 ? q.optJSONObject(0) : null;
                if (head == null) {
                    try {
                        lock.wait(30_000L);
                    } catch (InterruptedException e) {
                        return;
                    }
                    continue;
                }
            }
            Pairing p = Pairing.load(app);
            if (p == null) {
                sleep(5_000);
                continue;
            }
            String id = head.optString("id"), code = head.optString("c");
            long t = head.optLong("t");
            if (System.currentTimeMillis() - t > MAX_AGE_MS) {
                Result r = new Result();
                r.id = id;
                r.code = code;
                r.failed = true;
                r.msg = "Not sent in time (no Wi-Fi or internet) – scan again";
                r.at = System.currentTimeMillis();
                removeHead(id);
                deliver(r);
                continue;
            }
            Result r = viaWifi(p, id, code);
            if (r == null && p.internet()) r = viaInternet(p, id, code, t);
            if (r == null) {
                route = "queued";
                notifyQueue();
                sleep(4_000);
                continue;
            }
            r.id = id;
            r.code = code;
            r.at = System.currentTimeMillis();
            removeHead(id);
            deliver(r);
        }
    }

    private Result viaWifi(Pairing p, String id, String code) {
        List<String> hosts = new ArrayList<String>();
        if (!goodHost.isEmpty() && p.hosts.contains(goodHost)) hosts.add(goodHost);
        for (String h : p.hosts) if (!hosts.contains(h)) hosts.add(h);
        for (String h : hosts) {
            try {
                JSONObject body = new JSONObject();
                body.put("k", p.key);
                body.put("id", id);
                body.put("c", code);
                body.put("d", p.name);
                String res = http("POST", "http://" + h + "/scan", body.toString(), 2_500, 8_000);
                goodHost = h;
                route = "wifi";
                return parse(res, "wifi");
            } catch (HttpError e) {
                if (e.code == 403) {
                    Result r = new Result();
                    r.failed = true;
                    r.msg = "This phone was removed on the hub – pair it again";
                    r.route = "wifi";
                    return r;
                }
                if (e.code == 429) {
                    sleep(1_500);
                }
            } catch (Exception ignored) {
                // not on the shop Wi-Fi / hub off: next address, then the internet
            }
        }
        return null;
    }

    private Result viaInternet(Pairing p, String id, String code, long t) {
        String base = "https://" + p.db + "/babai/o/" + p.outlet;
        try {
            JSONObject body = new JSONObject();
            body.put("c", code);
            body.put("t", t);
            body.put("d", p.name);
            http("PUT", base + "/sq/" + p.key + "/" + id + ".json", body.toString(), 6_000, 10_000);
        } catch (HttpError e) {
            if (e.code == 401 || e.code == 403) {
                Result r = new Result();
                r.failed = true;
                r.msg = "The hub doesn't know this phone any more – pair it again";
                r.route = "internet";
                return r;
            }
            return null;
        } catch (Exception e) {
            return null;   // no internet either
        }
        route = "internet";
        notifyQueue();
        // the hub answers within a second or two when it is on
        long until = System.currentTimeMillis() + 25_000L;
        while (System.currentTimeMillis() < until) {
            sleep(700);
            try {
                String res = http("GET", base + "/sr/" + p.key + "/" + id + ".json", null, 6_000, 10_000);
                if (res != null && !res.trim().equals("null") && !res.trim().isEmpty()) return parse(res, "internet");
            } catch (Exception ignored) {
            }
        }
        // sent, but the hub hasn't answered (PC off?): it marks it when it comes back
        Result r = new Result();
        r.msg = "Sent – waiting for the hub (is the shop PC on?)";
        r.route = "internet";
        r.failed = true;
        return r;
    }

    private static Result parse(String json, String route) {
        Result r = new Result();
        r.route = route;
        try {
            JSONObject o = new JSONObject(json);
            r.ok = o.optBoolean("ok");
            r.already = o.optBoolean("already");
            r.msg = o.optString("m", "");
            r.brand = o.optString("b", "");
            r.order = o.optString("o", "");
            r.last4 = o.optString("l", "");
            r.slot = o.optString("s", "");
            r.failed = !r.ok;
        } catch (Exception e) {
            r.failed = true;
            r.msg = "The hub's answer couldn't be read";
        }
        return r;
    }

    private void deliver(final Result r) {
        main.post(new Runnable() {
            @Override public void run() {
                for (Listener l : listeners) l.onResult(r);
            }
        });
        History.add(app, r);
        notifyQueue();
    }

    private void notifyQueue() {
        final int n = waiting();
        final String rt = route;
        main.post(new Runnable() {
            @Override public void run() {
                for (Listener l : listeners) l.onQueue(n, rt);
            }
        });
    }

    /** Checks the Wi-Fi route right away (for the screen). */
    void probe() {
        new Thread(new Runnable() {
            @Override public void run() {
                Pairing p = Pairing.load(app);
                if (p == null) return;
                for (String h : p.hosts) {
                    try {
                        http("GET", "http://" + h + "/scan?k=" + p.key, null, 2_000, 4_000);
                        goodHost = h;
                        route = "wifi";
                        notifyQueue();
                        return;
                    } catch (Exception ignored) {
                    }
                }
                route = p.internet() ? "internet" : "queued";
                notifyQueue();
            }
        }).start();
    }

    // ---- http -------------------------------------------------------------------------------------

    static final class HttpError extends Exception {
        final int code;

        HttpError(int c) {
            super("HTTP " + c);
            code = c;
        }
    }

    static String http(String method, String url, String body, int connectMs, int readMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setUseCaches(false);
        c.setRequestMethod(method);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream out = c.getOutputStream();
                out.write(body.getBytes("UTF-8"));
                out.close();
            }
            int code = c.getResponseCode();
            if (code != 200) throw new HttpError(code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 200_000) b.write(buf, 0, n);
            in.close();
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
