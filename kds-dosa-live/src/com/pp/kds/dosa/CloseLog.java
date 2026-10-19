package com.pp.kds.dosa;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Why the app closed, for TVs that "close after some time":
 * - a crash is written down (time + first lines of the error) and the app is opened again a few
 *   seconds later, on the screen it was showing (at most 3 times in 10 minutes, so a crash at start
 *   can't loop);
 * - on Android 11+ Android's own record of recent exits is read too (low memory, crash, freeze...).
 * Both are listed in the rider settings ("Why the app closed").
 */
final class CloseLog {
    private static final String PREFS = "dosa_close_log";
    private static final long RELAUNCH_MS = 3000L;
    private static final long LOOP_WINDOW_MS = 10 * 60_000L;
    private static final int LOOP_MAX = 3;
    private static boolean installed;

    private CloseLog() {
    }

    static synchronized void install(final Activity activity) {
        if (installed) return;
        installed = true;
        final Context app = activity.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    record(app, t, e);
                    relaunch(app);
                } catch (Throwable ignored) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private static void record(Context c, Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        if (trace.length() > 1500) trace = trace.substring(0, 1500);
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20, maxMb = rt.maxMemory() >> 20;
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putLong("at", System.currentTimeMillis())
                .putString("what", "Crash in " + t.getName() + " (memory " + usedMb + " of " + maxMb + " MB)\n" + trace)
                .commit(); // commit: the process is about to die
    }

    /** Open the app again shortly, unless it already crashed LOOP_MAX times in LOOP_WINDOW_MS. */
    private static void relaunch(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis(), since = p.getLong("loop_since", 0);
        int n = now - since < LOOP_WINDOW_MS ? p.getInt("loop_n", 0) : 0;
        if (n == 0) since = now;
        p.edit().putLong("loop_since", since).putInt("loop_n", n + 1).commit();
        if (n + 1 > LOOP_MAX) return;
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int flags = PendingIntent.FLAG_CANCEL_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(c, 7101, launch, flags);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RELAUNCH_MS, pi);
    }

    /** Text for the settings: our last crash, then Android's own list of recent exits. */
    static String report(Context c) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat f = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault());
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long at = p.getLong("at", 0);
        if (at > 0) sb.append("Last crash ").append(f.format(new Date(at))).append(": ").append(p.getString("what", ""));
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
                List<android.app.ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(c.getPackageName(), 0, 5);
                for (android.app.ApplicationExitInfo x : list) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(f.format(new Date(x.getTimestamp()))).append("  ").append(reason(x.getReason()))
                            .append("  (memory ").append(x.getPss() >> 10).append(" MB)");
                    if (x.getDescription() != null) sb.append("  ").append(x.getDescription());
                }
            } catch (Throwable ignored) {
            }
        } else if (sb.length() == 0) {
            sb.append("No crash recorded. (Android ").append(Build.VERSION.RELEASE)
                    .append(" doesn't report low-memory closes to apps.)");
        }
        Runtime rt = Runtime.getRuntime();
        sb.append("\nNow: ").append((rt.totalMemory() - rt.freeMemory()) >> 20).append(" of ")
                .append(rt.maxMemory() >> 20).append(" MB used");
        return sb.length() == 0 ? "No closes recorded." : sb.toString();
    }

    private static String reason(int r) {
        switch (r) {
            case 1: return "Closed by itself";
            case 2: return "Killed by Android";
            case 3: return "LOW MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH (native)";
            case 6: return "FROZE (not responding)";
            case 7: return "Start-up problem";
            case 8: return "Permission changed";
            case 9: return "Too much battery/CPU";
            case 10: return "Closed by user";
            case 11: return "Closed by user (stopped)";
            case 12: return "Updated / app data changed";
            case 13: return "Other";
            case 14: return "Frozen app";
            default: return "Unknown (" + r + ")";
        }
    }
}
