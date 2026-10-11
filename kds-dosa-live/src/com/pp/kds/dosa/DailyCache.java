package com.pp.kds.dosa;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Once a day, at the first start of the KDS app: empties the app's cache folders (temporary
 * files only: images, WebView cache, temp downloads). Settings, login and KDS data live in other
 * folders and are never touched. Runs in the background; a busy file is simply skipped.
 */
final class DailyCache {

    private DailyCache() {}

    static void clearOncePerDay(final Context c) {
        try {
            final SharedPreferences p = c.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE);
            final String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (today.equals(p.getString("cache_cleared", ""))) return;
            p.edit().putString("cache_cleared", today).apply();
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    long freed = 0;
                    try {
                        freed += emptyDir(c.getCacheDir(), 0);
                        File ext = c.getExternalCacheDir();
                        if (ext != null) freed += emptyDir(ext, 0);
                    } catch (Throwable ignored) {
                    }
                    p.edit().putLong("cache_freed", freed).apply();
                }
            }, "babai-cache");
            t.setPriority(Thread.MIN_PRIORITY);
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    /** Deletes what is inside the folder (not the folder itself); returns bytes freed. */
    private static long emptyDir(File dir, int depth) {
        long freed = 0;
        if (dir == null || depth > 12) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            try {
                if (f.isDirectory()) {
                    freed += emptyDir(f, depth + 1);
                    f.delete();
                } else {
                    long n = f.length();
                    if (f.delete()) freed += n;
                }
            } catch (Throwable ignored) {
            }
        }
        return freed;
    }
}
