package com.babai.scanner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * Babai Scanner. Opens straight on the camera: point it at a KOT barcode and the order is marked
 * Food Ready through the Babai Hub. Not paired yet -> the pairing screen. USB mode (Android box)
 * -> a status screen; the scanner keeps working with the app closed.
 */
public final class MainActivity extends Activity implements Sender.Listener, CamScan.Callback {

    private static final int GREEN = 0xFF0A8F4A, RED = 0xFFC62828, AMBER = 0xFFE0A100, BLUE = 0xFF1E6FD9, INK = 0xFF101418;
    private final Handler main = new Handler(Looper.getMainLooper());
    private float d;
    private FrameLayout root;
    private CamScan cam;
    private TextView routeChip, resultCard, camState, title;
    private LinearLayout historyBox;
    private ToneGenerator tone;
    private String lastCode = "";
    private long lastCodeAt;
    private boolean pairingScan;
    private final StringBuilder keys = new StringBuilder();
    private long keyAt;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        root = new FrameLayout(this);
        root.setBackgroundColor(INK);
        setContentView(root);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90);
        } catch (Throwable ignored) {
        }
    }

    @Override protected void onResume() {
        super.onResume();
        Sender.get(this).addListener(this);
        Sender.get(this).start();
        show();
    }

    @Override protected void onPause() {
        Sender.get(this).removeListener(this);
        stopCamera();
        super.onPause();
    }

    private void show() {
        stopCamera();
        root.removeAllViews();
        Pairing p = Pairing.load(this);
        if (p == null || pairingScan) {
            if (pairingScan) cameraScreen(true);
            else pairScreen();
        } else if (UsbScanService.enabled(this)) {
            usbScreen(p);
        } else {
            cameraScreen(false);
        }
    }

    // ---- screens --------------------------------------------------------------------------------

    private void pairScreen() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = column();
        col.addView(text("Babai Scanner", 30, Color.WHITE, true));
        col.addView(text("Pair this phone with the outlet's Babai Hub once. On the hub PC: Babai Hub page → Step 5 → Add phone → a QR code appears.", 16, 0xFFC9D1DA, false));
        Button scan = button("Scan the pairing QR code", AMBER, INK);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pairingScan = true;
                show();
            }
        });
        col.addView(scan);
        col.addView(text("or paste the pairing code:", 14, 0xFFC9D1DA, false));
        final EditText code = new EditText(this);
        code.setHint("BABAISCAN1;h=…");
        code.setTextColor(Color.WHITE);
        code.setHintTextColor(0xFF7A8794);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(code);
        Button save = button("Pair", Color.WHITE, INK);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pairWith(code.getText().toString());
            }
        });
        col.addView(save);
        root.addView(scroll(col));
    }

    private void cameraScreen(final boolean forPairing) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        TextureView tv = new TextureView(this);
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1));
        // aim band
        View band = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setStroke((int) (3 * d), forPairing ? AMBER : 0xCCFFFFFF);
        g.setCornerRadius(16 * d);
        band.setBackground(g);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams((int) (300 * d), forPairing ? (int) (300 * d) : (int) (150 * d), Gravity.CENTER);
        root.addView(band, bl);
        camState = text(forPairing ? "Point at the QR code on the Babai Hub page" : "Point at the KOT barcode", 15, Color.WHITE, true);
        camState.setGravity(Gravity.CENTER);
        camState.setShadowLayer(6, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        sl.topMargin = (int) ((forPairing ? 180 : 110) * d);
        root.addView(camState, sl);
        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding((int) (14 * d), (int) (10 * d), (int) (8 * d), (int) (10 * d));
        top.setBackgroundColor(0xCC0B0F13);
        Pairing p = Pairing.load(this);
        title = text(forPairing ? "Pair with the hub" : "Babai Scanner · " + (p == null ? "" : (p.outletName.isEmpty() ? p.outlet.toUpperCase() : p.outletName)), 17, Color.WHITE, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (!forPairing) {
            routeChip = chip("…", 0xFF455A64);
            top.addView(routeChip);
            final TextView torch = chip("🔦", 0xFF263238);
            torch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (cam != null) cam.setTorch(!cam.torch());
                    torch.setBackground(round(cam != null && cam.torch() ? AMBER : 0xFF263238));
                }
            });
            top.addView(torch);
            TextView menu = chip("⋮", 0xFF263238);
            menu.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    menu();
                }
            });
            top.addView(menu);
        } else {
            TextView cancel = chip("Cancel", 0xFF263238);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pairingScan = false;
                    show();
                }
            });
            top.addView(cancel);
        }
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        if (!forPairing) {
            LinearLayout bottom = new LinearLayout(this);
            bottom.setOrientation(LinearLayout.VERTICAL);
            bottom.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (14 * d));
            bottom.setBackgroundColor(0xCC0B0F13);
            resultCard = text("Ready", 20, Color.WHITE, true);
            resultCard.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
            resultCard.setBackground(round(0xFF263238));
            bottom.addView(resultCard);
            historyBox = new LinearLayout(this);
            historyBox.setOrientation(LinearLayout.VERTICAL);
            bottom.addView(historyBox);
            root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
            fillHistory(4);
            Sender.get(this).probe();
            onQueue(Sender.get(this).waiting(), Sender.get(this).route());
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            camState.setText("Allow the camera to scan");
            return;
        }
        cam = new CamScan(this, tv, forPairing, this);
        cam.start();
    }

    private void usbScreen(Pairing p) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        UsbScanService.start(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        }
        LinearLayout col = column();
        col.addView(text("USB scanner mode · " + (p.outletName.isEmpty() ? p.outlet.toUpperCase() : p.outletName), 24, Color.WHITE, true));
        col.addView(text("Scan KOTs with the USB scanner. This keeps working with the app closed or another app on the screen (see the notification). The scanner doesn't type into other apps while this mode is on.", 15, 0xFFC9D1DA, false));
        routeChip = chip("…", 0xFF455A64);
        col.addView(routeChip);
        resultCard = text("Waiting for a scan", 20, Color.WHITE, true);
        resultCard.setPadding((int) (14 * d), (int) (12 * d), (int) (14 * d), (int) (12 * d));
        resultCard.setBackground(round(0xFF263238));
        col.addView(resultCard);
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(historyBox);
        fillHistory(12);
        Button cam = button("Use the phone camera instead", Color.WHITE, INK);
        cam.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setUsb(false);
            }
        });
        col.addView(cam);
        Button more = button("Settings", 0xFF263238, Color.WHITE);
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                menu();
            }
        });
        col.addView(more);
        root.addView(scroll(col));
        Sender.get(this).probe();
        onQueue(Sender.get(this).waiting(), Sender.get(this).route());
    }

    private void menu() {
        final Pairing p = Pairing.load(this);
        final boolean usb = UsbScanService.enabled(this);
        String[] items = {usb ? "Use the phone camera" : "USB scanner mode (Android box, works in the background)",
                "Check the connection", "Pair again (new QR code)", "Remove the pairing"};
        new AlertDialog.Builder(this)
                .setTitle("Babai Scanner" + (p == null ? "" : " · " + p.name))
                .setItems(items, (dlg, which) -> {
                    if (which == 0) setUsb(!usb);
                    else if (which == 1) checkConnection();
                    else if (which == 2) { pairingScan = true; show(); }
                    else if (which == 3) {
                        Pairing.save(this, null);
                        setUsb(false);
                    }
                }).show();
    }

    private void setUsb(boolean on) {
        Pairing.prefs(this).edit().putBoolean("usb_mode", on).apply();
        if (on) UsbScanService.start(this);
        else UsbScanService.stop(this);
        show();
    }

    private void checkConnection() {
        Pairing p = Pairing.load(this);
        if (p == null) return;
        final StringBuilder sb = new StringBuilder();
        new Thread(() -> {
            for (String h : p.hosts) {
                try {
                    String r = Sender.http("GET", "http://" + h + "/scan?k=" + p.key, null, 2_000, 4_000);
                    JSONObject o = new JSONObject(r);
                    sb.append("✓ Shop Wi-Fi: hub at ").append(h).append(" (").append(o.optString("outlet")).append(")\n");
                } catch (Sender.HttpError e) {
                    sb.append("✕ Shop Wi-Fi: hub at ").append(h).append(e.code == 403 ? " doesn't know this phone (pair again)" : " answered " + e.code).append("\n");
                } catch (Exception e) {
                    sb.append("– Shop Wi-Fi: no answer from ").append(h).append(" (not on the shop Wi-Fi, or the hub PC is off)\n");
                }
            }
            if (p.internet()) {
                try {
                    Sender.http("GET", "https://" + p.db + "/babai/o/" + p.outlet + "/sr/" + p.key + ".json?shallow=true", null, 5_000, 8_000);
                    sb.append("✓ Internet: Firebase reachable, this phone is allowed\n");
                } catch (Sender.HttpError e) {
                    sb.append("✕ Internet: Firebase refused this phone (").append(e.code).append(") – pair again\n");
                } catch (Exception e) {
                    sb.append("– Internet: not reachable from this phone\n");
                }
            }
            main.post(() -> new AlertDialog.Builder(this).setTitle("Connection").setMessage(sb.toString().trim())
                    .setPositiveButton("OK", null).show());
        }).start();
    }

    private void pairWith(String text) {
        Pairing p = Pairing.parse(text);
        if (p == null) {
            new AlertDialog.Builder(this).setMessage("That is not a Babai Hub pairing code.").setPositiveButton("OK", null).show();
            return;
        }
        Pairing.save(this, p);
        pairingScan = false;
        beep(true);
        show();
        Sender.get(this).probe();
    }

    // ---- scans ----------------------------------------------------------------------------------

    @Override public void onCode(String text) {
        if (pairingScan) {
            if (text.startsWith("BABAISCAN1;")) pairWith(text);
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (text.equals(lastCode) && now - lastCodeAt < 4_000L) return;   // same KOT still in view
        lastCode = text;
        lastCodeAt = now;
        String digits = text.replaceAll("\\D", "");
        if (digits.length() < 4) return;
        vibrate(40);
        if (resultCard != null) {
            resultCard.setText("Sending …" + digits.substring(Math.max(0, digits.length() - 4)));
            resultCard.setBackground(round(0xFF37474F));
        }
        Sender.get(this).enqueue(text, "camera");
    }

    @Override public void onState(String s) {
        if (camState == null) return;
        if (s == null || s.isEmpty()) camState.setText(pairingScan ? "Point at the QR code on the Babai Hub page" : "Point at the KOT barcode");
        else camState.setText(s);
    }

    @Override public void onResult(Sender.Result r) {
        boolean good = r.ok;
        beep(good);
        vibrate(good ? 80 : 300);
        if (resultCard != null) {
            resultCard.setText((good ? (r.already ? "✓ " : "✓ ") : "✕ ") + History.line(r));
            resultCard.setBackground(round(good ? (r.already ? 0xFF2E7D32 : GREEN) : RED));
        }
        fillHistory(UsbScanService.enabled(this) ? 12 : 4);
    }

    @Override public void onQueue(int waiting, String route) {
        if (routeChip == null) return;
        if (waiting > 0 && !"wifi".equals(route) && !"internet".equals(route)) {
            routeChip.setText("⏳ " + waiting + " waiting");
            routeChip.setBackground(round(AMBER));
        } else if ("wifi".equals(route)) {
            routeChip.setText("📶 Wi-Fi" + (waiting > 0 ? " · " + waiting : ""));
            routeChip.setBackground(round(GREEN));
        } else if ("internet".equals(route)) {
            routeChip.setText("🌐 Internet" + (waiting > 0 ? " · " + waiting : ""));
            routeChip.setBackground(round(BLUE));
        } else {
            routeChip.setText(waiting > 0 ? "⏳ " + waiting + " waiting" : "… checking");
            routeChip.setBackground(round(0xFF455A64));
        }
    }

    private void fillHistory(int n) {
        if (historyBox == null) return;
        historyBox.removeAllViews();
        List<JSONObject> h = History.list(this);
        DateFormat tf = android.text.format.DateFormat.getTimeFormat(this);
        for (int i = 0; i < Math.min(n, h.size()); i++) {
            JSONObject o = h.get(i);
            TextView t = text(tf.format(new Date(o.optLong("at"))) + "  " + (o.optBoolean("ok") ? "✓ " : "✕ ") + o.optString("text"),
                    14, o.optBoolean("ok") ? 0xFFB9F6CA : 0xFFFFCDD2, false);
            t.setPadding(0, (int) (6 * d), 0, 0);
            t.setSingleLine(true);
            historyBox.addView(t);
        }
    }

    /** A USB / Bluetooth scanner that types (keyboard mode) while this screen is open. */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (Pairing.load(this) == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_TAB) {
                String s = keys.toString();
                keys.setLength(0);
                if (s.length() >= 4 && e.getEventTime() - keyAt < 300) {
                    Sender.get(this).enqueue(s, "keyboard");
                    if (resultCard != null) resultCard.setText("Sending …" + s.substring(Math.max(0, s.length() - 4)));
                    return true;
                }
            } else {
                int ch = e.getUnicodeChar();
                if (ch > 32 && ch < 127) {
                    if (e.getEventTime() - keyAt > 100) keys.setLength(0);
                    keys.append((char) ch);
                    keyAt = e.getEventTime();
                    if (!(getCurrentFocus() instanceof EditText)) return true;
                }
            }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 1 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) show();
    }

    private void stopCamera() {
        if (cam != null) cam.stop();
        cam = null;
    }

    // ---- small UI helpers -----------------------------------------------------------------------

    private void beep(boolean ok) {
        try {
            if (tone != null) tone.startTone(ok ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_NACK, ok ? 150 : 400);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } catch (Throwable ignored) {
        }
    }

    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (20 * d);
        col.setPadding(p, (int) (40 * d), p, p);
        return col;
    }

    private ScrollView scroll(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v);
        return s;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, (int) (8 * d), 0, (int) (4 * d));
        return t;
    }

    private Button button(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setBackground(round(bg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (54 * d));
        lp.topMargin = (int) (14 * d);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView chip(String s, int bg) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding((int) (12 * d), (int) (7 * d), (int) (12 * d), (int) (7 * d));
        t.setBackground(round(bg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = (int) (6 * d);
        lp.topMargin = (int) (4 * d);
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(12 * d);
        return g;
    }
}
