package com.pp.kds.dosa;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Calendar;

/**
 * Updates this KDS app by itself. Looks for kds/version.json {"b":build,"url":"…apk","sha256":"…"}
 * on the Babai Hub (shop Wi-Fi, which keeps a copy) and else on the Netlify site; downloads a newer
 * build in the background, checks its SHA-256, and asks Android to install it between 3 and 6 am
 * (or at once from Rider screen settings → Update now). Android shows its "Install" screen once;
 * the app's data stays. Same signing key on every build, so it installs over this one.
 */
public final class AppUpdate {

    /** This build. Raise by one for every KDS release (make-netlify-site.sh reads it). */
    static final int BUILD = 4;
    static final String SITE = "https://babaidosa.netlify.app/";
    private static final String EXTRA = "babai_update";
    private static final long FIRST_CHECK_MS = 3 * 60_000L, EVERY_MS = 4 * 3600_000L;

    private static volatile String status = "Not checked yet";
    private static volatile File ready;
    private static volatile int readyBuild;
    private static volatile boolean installing, checking;
    private static volatile long lastAskAt;
    private static Context app;
    private static Thread worker;

    private AppUpdate() {}

    static synchronized void start(Context c) {
        app = c.getApplicationContext();
        if (worker != null) return;
        worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(FIRST_CHECK_MS);
                } catch (InterruptedException e) {
                    return;
                }
                while (true) {
                    check();
                    try {
                        Thread.sleep(EVERY_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "babai-update");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    static String status() {
        return "This app: build " + BUILD + " • " + status;
    }

    /** Called every tick with the visible activity: installs a downloaded update in the quiet hours. */
    static void tick(Activity a, long now) {
        if (a == null || ready == null || installing) return;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 3 || h >= 6 || now - lastAskAt < 30 * 60_000L) return;
        lastAskAt = now;
        install(a);
    }

    /** Settings → Update now: check, download and install at once. */
    static void now(final Activity a) {
        new Thread(new Runnable() {
            @Override public void run() {
                if (ready == null) check();
                if (ready != null) {
                    a.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            install(a);
                        }
                    });
                }
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        android.widget.Toast.makeText(a, status(), android.widget.Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "babai-update-now").start();
    }

    private static synchronized void check() {
        if (checking || app == null) return;
        checking = true;
        try {
            JSONObject v = null;
            String base = null;
            String hub = HubFeed.address();
            if (!hub.isEmpty()) {
                try {
                    v = new JSONObject(text("http://" + hub + "/kds/version.json", 4_000));
                    base = "http://" + hub + "/kds/";
                } catch (Throwable ignored) {
                    v = null;
                }
            }
            if (v == null || v.optInt("b", 0) <= 0) {
                try {
                    v = new JSONObject(text(SITE + "kds/version.json", 10_000));
                    base = SITE + "kds/";
                } catch (Throwable t) {
                    status = "Couldn't check for updates (no hub or internet)";
                    return;
                }
            }
            int b = v.optInt("b", 0);
            String sha = v.optString("sha256", "").toLowerCase(java.util.Locale.ROOT);
            String name = v.optString("url", "");
            if (b <= BUILD) {
                status = "up to date";
                return;
            }
            if (sha.length() != 64 || name.isEmpty() || name.contains("/") || name.contains("..")) {
                status = "Update file list is not valid";
                return;
            }
            if (ready != null && readyBuild == b && ready.exists()) return;
            File dir = new File(app.getFilesDir(), "update");
            dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            File f = new File(dir, "kds-" + b + ".apk");
            status = "Downloading build " + b + "…";
            download(base + name, f);
            if (!sha.equals(sha256(f))) {
                f.delete();
                status = "Download of build " + b + " was damaged; will try again";
                return;
            }
            ready = f;
            readyBuild = b;
            status = "build " + b + " downloaded • installs between 3 and 6 am, or tap Update now";
        } catch (Throwable t) {
            status = "Update check failed: " + t.getClass().getSimpleName();
        } finally {
            checking = false;
        }
    }

    private static void install(Context c) {
        File f = ready;
        if (f == null || installing) return;
        installing = true;
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(c.getPackageName());
            sp.setSize(f.length());
            int id = pi.createSession(sp);
            PackageInstaller.Session s = pi.openSession(id);
            InputStream in = new FileInputStream(f);
            OutputStream out = s.openWrite("kds", 0, f.length());
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.fsync(out);
            out.close();
            in.close();
            // Petpooja's own install-result receiver is in the manifest; the build lets it hand our
            // results to onInstallResult() (so the updated app gets the "done" message too).
            Intent i = new Intent().setClassName(c.getPackageName(), "com.pp.kds.core.update.InstallResultReceiver")
                    .putExtra(EXTRA, readyBuild);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* MUTABLE */ : 0);
            s.commit(PendingIntent.getBroadcast(c, 7701, i, fl).getIntentSender());
            s.close();
            status = "Installing build " + readyBuild + "… tap Install on the screen";
        } catch (Throwable t) {
            status = "Couldn't start the install: " + t.getClass().getSimpleName();
            installing = false;
        }
    }

    /**
     * From InstallResultReceiver (patched in by build.sh). Returns true when it was our update, so
     * Petpooja's own updater doesn't handle it.
     */
    public static boolean onInstallResult(Context c, Intent i) {
        try {
            if (i == null || !i.hasExtra(EXTRA)) return false;
            int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (st == PackageInstaller.STATUS_SUCCESS) {
                status = "updated";
                Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
                if (open != null) {
                    try {
                        c.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); // may be blocked by Android
                    } catch (Throwable ignored) {
                    }
                }
            } else {
                installing = false;
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                status = "Install not done (" + st + (m == null ? "" : ": " + m) + ")";
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private static String text(String url, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && b.size() < 64 * 1024) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File to) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            InputStream in = c.getInputStream();
            OutputStream out = new FileOutputStream(to);
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total > 300L * 1024 * 1024) throw new java.io.IOException("too big");
            }
            out.close();
        } finally {
            c.disconnect();
        }
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new FileInputStream(f);
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        in.close();
        StringBuilder b = new StringBuilder();
        for (byte x : md.digest()) b.append(String.format("%02x", x));
        return b.toString();
    }
}
