package com.pp.kds.dosa;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Prep Live data kept on the Babai Hub PC (http://PC:8790/prep/...), shared by every Prep Live tablet.
 *
 * Once the hub is found (same address as the rider screen uses):
 *   1. the hub has no recipes yet -> this tablet sends what the APK came with (recipes + Petpooja history);
 *   2. this tablet sends its recorded days; the hub keeps, per date, the best copy from all tablets;
 *   3. it reads back everything (all tablets' days, holidays) and uses that; new recipes on the hub
 *      replace the APK's own (kept in files, so they are used even when the hub is off);
 *   4. after that, every finished day / holiday change is sent within a minute.
 * The projection itself stays on the tablet. Without the hub nothing changes.
 */
final class PrepSync {

    private static final long STEP_MS = 60_000L;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Thread worker;
    private static Context app;
    private static volatile boolean synced, changed;
    private static volatile String lastSentHash = "";
    private static volatile String status = "";

    private PrepSync() {}

    static String status() {
        return status;
    }

    static synchronized void start(Context c) {
        app = c.getApplicationContext();
        if (worker != null) return;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(20_000L);   // let the KDS settle and the hub be found
                } catch (InterruptedException e) {
                    return;
                }
                while (true) {
                    try {
                        step();
                    } catch (Throwable t) {
                        status = "hub: " + t.getClass().getSimpleName();
                    }
                    try {
                        Thread.sleep(STEP_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "babai-prep-sync");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    static void historyChanged() {
        changed = true;
    }

    // ---- recipes / history last read from the hub (files) ---------------------------------

    private static File file(Context c, String name) {
        return new File(c.getFilesDir(), name);
    }

    /** The hub's recipes + Petpooja history saved earlier, or null (then the APK's own are used). */
    static PrepStats.Model cachedModel(Context c) {
        try {
            String p = read(file(c, "prep_hub_profile.json")), s = read(file(c, "prep_hub_seed.txt"));
            return p.isEmpty() ? null : PrepLive.modelFrom(p, s);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Hash of the recipes + history this tablet uses now (same as the hub's "base"). */
    private static String usedBaseHash() {
        String p = read(file(app, "prep_hub_profile.json")), s = read(file(app, "prep_hub_seed.txt"));
        if (p.isEmpty()) {
            p = PrepLive.asset(app, "prep_profile.json");
            s = PrepLive.asset(app, "prep_days.txt");
        }
        return hash(p + "\u0000" + s);
    }

    // ---- sync ---------------------------------------------------------------------------------

    private static void step() throws Exception {
        String addr = HubFeed.address();
        if (addr.isEmpty() || !PrepLive.available()) {
            status = "waiting for the Babai Hub";
            return;
        }
        String base = "http://" + addr + "/prep/";
        if (!synced) {
            String have = usedBaseHash();
            JSONObject b = new JSONObject(get(base + "base?have=" + have));
            if (b.optString("base", "").isEmpty()) {
                // the hub has nothing yet: send what this APK came with
                JSONObject seed = new JSONObject();
                seed.put("profile", PrepLive.asset(app, "prep_profile.json"));
                seed.put("seed", PrepLive.asset(app, "prep_days.txt"));
                post(base + "seed", "application/json", seed.toString());
            }
            String mine = onMain();
            if (mine != null && !mine.isEmpty()) post(base + "history", "text/plain", mine);
            b = new JSONObject(get(base + "base?have=" + have));
            final String merged = withHolidays(b.optString("history", ""), b.optJSONArray("holidays"));
            String profile = b.optString("profile", ""), seed = b.optString("seed", "");
            if (!profile.isEmpty() && !hash(profile + "\u0000" + seed).equals(have)) {
                final PrepStats.Model m = PrepLive.modelFrom(profile, seed);
                if (m != null) {
                    write(file(app, "prep_hub_profile.json"), profile);
                    write(file(app, "prep_hub_seed.txt"), seed);
                    main.post(new Runnable() {
                        @Override public void run() {
                            PrepLive.applyModel(m, merged);
                        }
                    });
                }
            } else {
                main.post(new Runnable() {
                    @Override public void run() {
                        PrepLive.applyHistory(merged);
                    }
                });
            }
            synced = true;
            changed = false;
            lastSentHash = mine == null ? "" : hash(mine);
            status = "synced with the hub (" + b.optInt("days") + " recorded days)";
            return;
        }
        if (changed) {
            String mine = onMain();
            if (mine != null && !hash(mine).equals(lastSentHash)) {
                post(base + "history", "text/plain", mine);
                lastSentHash = hash(mine);
            }
            changed = false;
            status = "synced with the hub";
        }
    }

    private static String withHolidays(String history, JSONArray hol) {
        StringBuilder sb = new StringBuilder(history);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        for (int i = 0; hol != null && i < hol.length(); i++) sb.append("H|").append(hol.optString(i)).append("||\n");
        return sb.toString();
    }

    /** This tablet's recorded days, read on the main thread (where Prep Live changes them). */
    private static String onMain() throws InterruptedException {
        final AtomicReference<String> out = new AtomicReference<String>();
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override public void run() {
                try {
                    out.set(PrepLive.historyText());
                } finally {
                    done.countDown();
                }
            }
        });
        done.await(10, TimeUnit.SECONDS);
        return out.get();
    }

    // ---- helpers ------------------------------------------------------------------------------

    static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(String.format("%02x", d[i] & 0xff));
            return b.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String read(File f) {
        if (!f.exists()) return "";
        try {
            InputStream in = new java.io.FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static void write(File f, String s) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        out.write(s.getBytes("UTF-8"));
        out.close();
        if (!tmp.renameTo(f)) throw new IllegalStateException("rename");
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4_000);
        c.setReadTimeout(30_000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void post(String url, String type, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(4_000);
        c.setReadTimeout(30_000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", type + "; charset=utf-8");
        c.setRequestProperty("X-Babai-KDS", "1");
        try {
            OutputStream out = c.getOutputStream();
            out.write(body.getBytes("UTF-8"));
            out.close();
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
        } finally {
            c.disconnect();
        }
    }
}
