package com.pp.kds.scan;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Fast KOT barcode scanner (replaces the earlier camera screen; same class name so the KDS camera
 * button opens it). Marks orders with the existing ScannerBridge.markFromBarcode().
 *
 * Speed: 1D formats only, decodes just the scan window (not the whole frame), HD preview,
 * every frame on a background camera thread, both barcode orientations, no fixed throttle.
 * Also accepts USB / Bluetooth barcode scanners (keyboard style: digits + Enter).
 */
public final class ScannerActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    // Last scan survives closing/reopening this screen (and is shared with the board's HID scanner).
    static String lastCode;
    static int lastCount = -1;
    static long lastAt;
    static String lastSource = "";

    private static final float WIN_W = KotDecoder.WIN_W, WIN_H = KotDecoder.WIN_H;
    private static final long SAME_CODE_MS = 2500;
    private static final int REQ_CAMERA = 7001;

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread camThread;
    private Handler camHandler;
    private volatile Camera camera;
    private SurfaceHolder holder;
    private boolean surfaceReady, resumed, torch;
    private final KotDecoder decoder = new KotDecoder();
    private int pw, ph;
    private String debounceCode;
    private long debounceAt;
    private ScanOverlay overlay;
    private TextView lastTitle, lastDetail, flashBtn;
    private ToneGenerator tone;
    private final StringBuilder keyBuf = new StringBuilder();
    private long keyAt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        } catch (RuntimeException ignored) {
            tone = null;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission("android.permission.CAMERA") != 0) {
            requestPermissions(new String[]{"android.permission.CAMERA"}, REQ_CAMERA);
        }
    }

    // ---- UI ------------------------------------------------------------------------------

    private void buildUi() {
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        SurfaceView sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        overlay = new ScanOverlay(this);
        overlay.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) refocus();
                return true;
            }
        });
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding((int) (16 * d), (int) (14 * d), (int) (12 * d), (int) (8 * d));
        TextView title = text("SCAN KOT", 20, Color.WHITE, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        flashBtn = text("FLASH OFF", 13, Color.WHITE, true);
        flashBtn.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        flashBtn.setBackground(round(0x55000000, 0x88FFFFFF, 10 * d, d));
        flashBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                setTorch(!torch);
            }
        });
        top.addView(flashBtn);
        TextView close = text("   \u2715", 22, Color.WHITE, true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        });
        top.addView(close);
        root.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding((int) (18 * d), (int) (14 * d), (int) (18 * d), (int) (18 * d));
        panel.setBackground(round(0xE6121212, 0x33FFFFFF, 18 * d, d));
        panel.addView(text("LAST SCANNED", 12, 0xFFB0BEC5, true));
        lastTitle = text("\u2014", 24, Color.WHITE, true);
        panel.addView(lastTitle);
        lastDetail = text("Hold the KOT barcode inside the box. USB / Bluetooth scanners work too.", 14, 0xFFCFD8DC, false);
        panel.addView(lastDetail);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        plp.setMargins((int) (12 * d), 0, (int) (12 * d), (int) (16 * d));
        root.addView(panel, plp);
        setContentView(root);
        showLast();
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private static GradientDrawable round(int fill, int stroke, float r, float sw) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(r);
        if (sw > 0) g.setStroke((int) sw, stroke);
        return g;
    }

    private void showLast() {
        if (lastCode == null) return;
        String tail = lastCode.length() > 4 ? lastCode.substring(lastCode.length() - 4) : lastCode;
        String time = new SimpleDateFormat("hh:mm:ss a", Locale.US).format(new Date(lastAt));
        if (lastCount > 0) {
            lastTitle.setText("\u2713 FOOD READY \u2022 " + lastCount + (lastCount == 1 ? " order" : " orders"));
            lastTitle.setTextColor(0xFF69F0AE);
        } else {
            lastTitle.setText("\u2715 No preparing order ending " + tail);
            lastTitle.setTextColor(0xFFFFB300);
        }
        lastDetail.setText("Barcode \u2026" + tail + "  (" + lastCode + ")  \u2022  " + time + "  \u2022  " + lastSource);
    }

    // ---- camera (all on the camera thread) -------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        startCamera();
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (tone != null) tone.release();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        super.onRequestPermissionsResult(req, p, r);
        if (req == REQ_CAMERA) {
            if (r.length > 0 && r[0] == 0) startCamera();
            else Toast.makeText(this, "Camera permission is required for barcode scanning", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        holder = h;
        surfaceReady = true;
        startCamera();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
    }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        stopCamera();
    }

    private void startCamera() {
        if (!resumed || !surfaceReady || camera != null) return;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission("android.permission.CAMERA") != 0) return;
        if (camThread == null) {
            camThread = new HandlerThread("kot-scan-camera");
            camThread.start();
            camHandler = new Handler(camThread.getLooper());
        }
        final SurfaceHolder h = holder;
        camHandler.post(new Runnable() {
            @Override public void run() {
                openCamera(h);
            }
        });
    }

    private void openCamera(SurfaceHolder h) {
        try {
            Camera c = Camera.open();
            Camera.Parameters p = c.getParameters();
            Camera.Size best = null;
            long bestScore = Long.MAX_VALUE;
            List<Camera.Size> sizes = p.getSupportedPreviewSizes();
            if (sizes != null) {
                for (Camera.Size s : sizes) {           // HD preview: thin KOT bars need pixels
                    long score = Math.abs((long) s.width * s.height - 1280L * 720L);
                    if (s.width < 960) score += 10_000_000L;
                    if (score < bestScore) {
                        bestScore = score;
                        best = s;
                    }
                }
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            List<String> fm = p.getSupportedFocusModes();
            if (fm != null) {
                if (fm.contains("continuous-picture")) p.setFocusMode("continuous-picture");
                else if (fm.contains("continuous-video")) p.setFocusMode("continuous-video");
                else if (fm.contains("auto")) p.setFocusMode("auto");
            }
            List<String> scenes = p.getSupportedSceneModes();
            if (scenes != null && scenes.contains("barcode")) p.setSceneMode("barcode");
            c.setParameters(p);
            Camera.Size ps = c.getParameters().getPreviewSize();
            pw = ps.width;
            ph = ps.height;
            c.setDisplayOrientation(90);
            c.setPreviewDisplay(h);
            int len = pw * ph * 3 / 2;
            c.addCallbackBuffer(new byte[len]);
            c.addCallbackBuffer(new byte[len]);
            c.setPreviewCallbackWithBuffer(this);
            c.startPreview();
            camera = c;
            if (torch) applyTorch(true);
        } catch (Exception e) {
            main.post(new Runnable() {
                @Override public void run() {
                    Toast.makeText(ScannerActivity.this, "Unable to start camera", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void stopCamera() {
        final Camera c = camera;
        camera = null;
        if (c == null || camHandler == null) return;
        camHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    c.setPreviewCallbackWithBuffer(null);
                    c.stopPreview();
                    c.release();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void refocus() {
        final Camera c = camera;
        if (c == null || camHandler == null) return;
        camHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    c.cancelAutoFocus();
                    c.autoFocus(null);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void setTorch(final boolean on) {
        torch = on;
        flashBtn.setText(on ? "FLASH ON" : "FLASH OFF");
        flashBtn.setTextColor(on ? Color.BLACK : Color.WHITE);
        float d = getResources().getDisplayMetrics().density;
        flashBtn.setBackground(round(on ? 0xFFFFD54F : 0x55000000, 0x88FFFFFF, 10 * d, on ? 0 : d));
        if (camHandler != null) {
            camHandler.post(new Runnable() {
                @Override public void run() {
                    applyTorch(on);
                }
            });
        }
    }

    private void applyTorch(boolean on) {
        Camera c = camera;
        if (c == null) return;
        try {
            Camera.Parameters p = c.getParameters();
            List<String> modes = p.getSupportedFlashModes();
            if (modes != null && modes.contains("torch")) {
                p.setFlashMode(on ? "torch" : "off");
                c.setParameters(p);
            }
        } catch (Exception ignored) {
        }
    }

    // ---- decoding --------------------------------------------------------------------------

    @Override
    public void onPreviewFrame(byte[] data, Camera c) {
        String text = null;
        try {
            text = decoder.decode(data, pw, ph);
        } catch (Throwable ignored) {
        }
        if (camera != null) {
            try {
                c.addCallbackBuffer(data);
            } catch (Exception ignored) {
            }
        }
        if (text != null) {
            long now = SystemClock.uptimeMillis();
            if (text.equals(debounceCode) && now - debounceAt < SAME_CODE_MS) return;
            debounceCode = text;
            debounceAt = now;
            final String code = text;
            main.post(new Runnable() {
                @Override public void run() {
                    onCode(code, "camera");
                }
            });
        }
    }

    // ---- result ----------------------------------------------------------------------------

    private void onCode(String code, String source) {
        int n = record(this, code, source);
        showLast();
        overlay.flash(n > 0 ? 0xFF69F0AE : 0xFFFFB300);
        if (tone != null) {
            try {
                tone.startTone(n > 0 ? ToneGenerator.TONE_PROP_BEEP : ToneGenerator.TONE_PROP_NACK, 150);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Marks the order(s) and remembers the scan. Used by the camera, HID scanners and the board. */
    public static int record(Context ctx, String code, String source) {
        int n = ScannerBridge.markFromBarcode(ctx, code);
        lastCode = code;
        lastCount = n;
        lastAt = System.currentTimeMillis();
        lastSource = source;
        return n;
    }

    public static String lastSummary() {
        if (lastCode == null) return null;
        String tail = lastCode.length() > 4 ? lastCode.substring(lastCode.length() - 4) : lastCode;
        return lastCount > 0 ? "\u2713 FOOD READY \u2022 " + lastCount + (lastCount == 1 ? " order" : " orders") + " \u2022 \u2026" + tail
                : "\u2715 No preparing order ending " + tail;
    }

    public static boolean lastOk() {
        return lastCount > 0;
    }

    /** USB / Bluetooth scanners type the code + Enter. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_TAB) {
                if (keyBuf.length() >= 4) onCode(keyBuf.toString(), "USB/BT scanner");
                keyBuf.setLength(0);
                return true;
            }
            int ch = e.getUnicodeChar();
            if (ch > 32 && ch < 127) {
                long now = SystemClock.uptimeMillis();
                if (now - keyAt > 1000) keyBuf.setLength(0);
                keyAt = now;
                keyBuf.append((char) ch);
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    /** Dark mask, scan window corners and an animated red laser. */
    private static final class ScanOverlay extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF win = new RectF();
        private final float d;
        private int flashColor;
        private long flashAt = -10_000;

        ScanOverlay(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
        }

        void flash(int color) {
            flashColor = color;
            flashAt = SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float ww = w * WIN_W, wh = h * WIN_H;
            win.set((w - ww) / 2, (h - wh) / 2, (w + ww) / 2, (h + wh) / 2);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x99000000);
            c.drawRect(0, 0, w, win.top, p);
            c.drawRect(0, win.bottom, w, h, p);
            c.drawRect(0, win.top, win.left, win.bottom, p);
            c.drawRect(win.right, win.top, w, win.bottom, p);

            long since = SystemClock.uptimeMillis() - flashAt;
            boolean flashing = since < 700;
            int frame = flashing ? flashColor : Color.WHITE;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            if (flashing) {
                p.setColor(flashColor);
                p.setAlpha((int) (255 * (1 - since / 700f)));
                p.setStrokeWidth(10 * d);
                c.drawRoundRect(win, 12 * d, 12 * d, p);
                p.setAlpha(255);
            }
            p.setColor(frame);
            p.setStrokeWidth(4 * d);
            float L = 28 * d;
            c.drawLine(win.left, win.top, win.left + L, win.top, p);
            c.drawLine(win.left, win.top, win.left, win.top + L, p);
            c.drawLine(win.right, win.top, win.right - L, win.top, p);
            c.drawLine(win.right, win.top, win.right, win.top + L, p);
            c.drawLine(win.left, win.bottom, win.left + L, win.bottom, p);
            c.drawLine(win.left, win.bottom, win.left, win.bottom - L, p);
            c.drawLine(win.right, win.bottom, win.right - L, win.bottom, p);
            c.drawLine(win.right, win.bottom, win.right, win.bottom - L, p);

            // red laser sweeping slowly up and down inside the window
            float t = (SystemClock.uptimeMillis() % 1800) / 1800f;
            float k = t < 0.5f ? t * 2 : (1 - t) * 2;
            float y = win.top + 10 * d + (win.height() - 20 * d) * (0.15f + 0.7f * k);
            int[] widths = {14, 7, 2};
            int[] alphas = {40, 90, 255};
            for (int i = 0; i < widths.length; i++) {
                p.setColor(0xFFFF1744);
                p.setAlpha(alphas[i]);
                p.setStrokeWidth(widths[i] * d);
                c.drawLine(win.left + 8 * d, y, win.right - 8 * d, y, p);
            }
            p.setAlpha(255);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setTextSize(14 * d);
            p.setTextAlign(Paint.Align.CENTER);
            c.drawText("Align the KOT barcode with the red line", w / 2, win.bottom + 28 * d, p);
            postInvalidateOnAnimation();
        }
    }
}
