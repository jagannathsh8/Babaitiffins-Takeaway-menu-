package com.pp.kds.dosa;

import android.app.Activity;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Window;

import androidx.appcompat.view.WindowCallbackWrapper;

import com.pp.kds.scan.ScannerActivity;

/**
 * Wraps the KDS window callback:
 *  - USB / Bluetooth barcode scanners (keyboard style) work directly on the board: a burst of
 *    fast keystrokes (< 80 ms apart, 4+ chars) ended by Enter/Tab is treated as a scan. Normal,
 *    slower typing is passed through untouched.
 *  - Any touch brings the auto-hidden buttons back.
 */
final class KeyScanCallback extends WindowCallbackWrapper {

    private static final long MAX_GAP_MS = 80;
    private final Activity activity;
    private final StringBuilder buf = new StringBuilder();
    private long lastKeyAt;
    private int swallowUp = -1;

    KeyScanCallback(Window.Callback wrapped, Activity a) {
        super(wrapped);
        activity = a;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        try {
            int k = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_UP && k == swallowUp) {
                swallowUp = -1;
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                long now = SystemClock.uptimeMillis();
                if (k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_TAB) {
                    boolean scan = buf.length() >= 4 && now - lastKeyAt < 300;
                    String code = buf.toString();
                    buf.setLength(0);
                    if (scan) {
                        swallowUp = k;
                        int n = ScannerActivity.record(activity, code, "USB/BT scanner");
                        DosaLive.showScanBanner(activity, ScannerActivity.lastSummary(), n > 0);
                        return true;
                    }
                } else {
                    int ch = e.getUnicodeChar();
                    if (ch > 32 && ch < 127) {
                        if (buf.length() == 0 || now - lastKeyAt > MAX_GAP_MS) {
                            buf.setLength(0);
                        }
                        buf.append((char) ch);
                        lastKeyAt = now;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            try {
                DosaLive.onUserTouch();
            } catch (Throwable ignored) {
            }
        }
        return super.dispatchTouchEvent(e);
    }
}
