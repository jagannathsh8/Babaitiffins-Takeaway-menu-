package com.babai.scanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Android box mode: reads a USB barcode scanner directly (USB host), in the background, while any
 * other app is on the screen. The scanner stops typing into other apps while this runs. Started
 * from the app, after a restart (boot) and when a scanner is plugged in.
 */
public final class UsbScanService extends Service implements Sender.Listener {

    static final String ACTION_PERMISSION = "com.babai.scanner.USB_PERMISSION";
    private static final String CHANNEL = "babai_usb";
    private static final int NOTE_ID = 7;
    private volatile boolean running;
    private Thread reader;
    private String status = "Waiting for the USB scanner…";
    private PowerManager.WakeLock wake;

    static void start(Context c) {
        Intent i = new Intent(c, UsbScanService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, UsbScanService.class));
    }

    static boolean enabled(Context c) {
        return Pairing.prefs(c).getBoolean("usb_mode", false);
    }

    @Override public IBinder onBind(Intent i) {
        return null;
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "USB scanner", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        startForeground(NOTE_ID, note());
        Sender.get(this).addListener(this);
        Sender.get(this).start();
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(ACTION_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usb, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usb, f);
        try {
            wake = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "babai:usb");
            wake.acquire();
        } catch (Throwable ignored) {
        }
        running = true;
        connect();
    }

    @Override public int onStartCommand(Intent i, int flags, int id) {
        connect();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        Sender.get(this).removeListener(this);
        try {
            unregisterReceiver(usb);
        } catch (Throwable ignored) {
        }
        if (reader != null) reader.interrupt();
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private final BroadcastReceiver usb = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            connect();
        }
    };

    /** Finds a keyboard-type USB device (the scanner) and starts reading it. */
    private synchronized void connect() {
        if (!running || (reader != null && reader.isAlive())) return;
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        UsbDevice found = null;
        UsbInterface intf = null;
        for (UsbDevice d : um.getDeviceList().values()) {
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface it = d.getInterface(i);
                if (it.getInterfaceClass() == UsbConstants.USB_CLASS_HID && inEndpoint(it) != null) {
                    // boot keyboard first; other HID (many scanners) next
                    if (found == null || it.getInterfaceProtocol() == 1) { found = d; intf = it; }
                }
            }
        }
        if (found == null) {
            setStatus("Plug in the USB scanner");
            return;
        }
        if (!um.hasPermission(found)) {
            setStatus("Allow Babai Scanner to use the USB scanner (tap OK on the screen)");
            int fl = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            Intent pi = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
            um.requestPermission(found, PendingIntent.getBroadcast(this, 0, pi, fl));
            return;
        }
        final UsbDeviceConnection conn = um.openDevice(found);
        if (conn == null) {
            setStatus("The USB scanner couldn't be opened – plug it in again");
            return;
        }
        final UsbInterface itf = intf;
        if (!conn.claimInterface(itf, true)) {
            conn.close();
            setStatus("The USB scanner is used by something else");
            return;
        }
        final UsbEndpoint ep = inEndpoint(itf);
        final String name = found.getProductName() == null ? "USB scanner" : found.getProductName();
        setStatus("Ready – " + name + " (scan a KOT)");
        reader = new Thread(new Runnable() {
            @Override public void run() {
                Hid hid = new Hid();
                byte[] b = new byte[Math.max(8, ep.getMaxPacketSize())];
                try {
                    while (running && !Thread.currentThread().isInterrupted()) {
                        int n = conn.bulkTransfer(ep, b, b.length, 1_000);
                        if (n < 0) {
                            if (!stillThere(conn)) break;
                            continue;
                        }
                        String code = hid.feed(b, n);
                        if (code != null) {
                            setStatus("Sending " + code + "…");
                            Sender.get(UsbScanService.this).enqueue(code, "usb");
                        }
                    }
                } finally {
                    try {
                        conn.releaseInterface(itf);
                    } catch (Throwable ignored) {
                    }
                    conn.close();
                    setStatus("USB scanner unplugged");
                }
            }
        }, "babai-usb");
        reader.start();
    }

    private boolean stillThere(UsbDeviceConnection c) {
        return c.getFileDescriptor() >= 0 && !((UsbManager) getSystemService(USB_SERVICE)).getDeviceList().isEmpty();
    }

    private static UsbEndpoint inEndpoint(UsbInterface it) {
        for (int i = 0; i < it.getEndpointCount(); i++) {
            UsbEndpoint e = it.getEndpoint(i);
            if (e.getDirection() == UsbConstants.USB_DIR_IN && e.getType() == UsbConstants.USB_ENDPOINT_XFER_INT) return e;
        }
        return null;
    }

    @Override public void onResult(Sender.Result r) {
        setStatus((r.ok ? "✓ " : "✕ ") + History.line(r));
    }

    @Override public void onQueue(int waiting, String route) {
        if (waiting > 0) setStatus(waiting + " waiting to send");
    }

    private void setStatus(String s) {
        status = s;
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTE_ID, note());
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private Notification note() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("Babai Scanner – USB")
                .setContentText(status).setContentIntent(pi).setOngoing(true).build();
    }
}
