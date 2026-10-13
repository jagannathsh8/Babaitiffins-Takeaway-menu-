package com.babai.scanner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart: USB mode starts again by itself; queued scans are sent. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        try {
            if (UsbScanService.enabled(c)) UsbScanService.start(c);
            Sender.get(c).start();
        } catch (Throwable ignored) {
        }
    }
}
