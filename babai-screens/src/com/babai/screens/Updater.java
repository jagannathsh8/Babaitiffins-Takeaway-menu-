package com.babai.screens;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Self-update from the Console (/bsapp/version.json + /bsapp/app.apk, which the Console keeps from
 * the Netlify site). Downloaded at any time, installed at app start or between 3 and 6 am (Android
 * asks once on the TV: press Install). The screen and its settings are kept.
 */
final class Updater {
    static volatile String status = "";
    static volatile int readyBuild;
    private static volatile File ready;
    private static volatile boolean busy;

    private Updater() {}

    /** Background thread. */
    static void check(Context c, String base) {
        if (busy || base.isEmpty()) return;
        busy = true;
        try {
            String v = Net.get(base + "/bsapp/version.json", 8000);
            int b = (int) Net.jsonNum(v, "b");
            String sum = Net.jsonStr(v, "sha256");
            if (b <= Version.CODE || sum.length() != 64) { status = "up to date"; return; }
            if (ready != null && readyBuild == b && ready.exists()) return;
            File f = new File(c.getCacheDir(), "update-" + b + ".apk");
            HttpURLConnection h = (HttpURLConnection) new URL(base + "/bsapp/app.apk").openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(30000);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            InputStream in = h.getInputStream();
            OutputStream out = new FileOutputStream(f);
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                total += n;
                if (total > 40L << 20) throw new java.io.IOException("too big");
            }
            out.close();
            in.close();
            if (!hex(md.digest()).equalsIgnoreCase(sum)) {
                f.delete();
                status = "update " + b + " was damaged; will try again";
                return;
            }
            ready = f;
            readyBuild = b;
            status = "build " + b + " ready to install";
        } catch (Throwable t) {
            if (status.isEmpty()) status = "no update on the Console yet";
        } finally {
            busy = false;
        }
    }

    static boolean hasUpdate() { return ready != null && ready.exists(); }

    static void install(Context c) {
        File f = ready;
        if (f == null) return;
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setAppPackageName(c.getPackageName());
            sp.setSize(f.length());
            int id = pi.createSession(sp);
            PackageInstaller.Session s = pi.openSession(id);
            InputStream in = new FileInputStream(f);
            OutputStream out = s.openWrite("app", 0, f.length());
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.fsync(out);
            out.close();
            in.close();
            Intent i = new Intent(c, InstallReceiver.class);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* MUTABLE */ : 0);
            s.commit(PendingIntent.getBroadcast(c, 7702, i, fl).getIntentSender());
            s.close();
            status = "installing build " + readyBuild + " (press Install)";
        } catch (Throwable t) {
            status = "couldn't start the install: " + t.getClass().getSimpleName();
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
