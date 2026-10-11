package com.pp.kds.dosa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rider pickup board for the TV at the delivery counter.
 *
 *  Left : READY FOR PICKUP - one card per order: platform colour, big SLOT (from Bridge Print)
 *         and the big last 4 digits of the platform order ID; cards auto-size to fit all.
 *  Right: PREPARING - online / delivery orders still in the kitchen, oldest first.
 *  Call : big pop-up while the voice calls new ready orders.
 *  Find : the search button (or any number key) opens a keypad: type the last 4 digits of the
 *         order ID to see brand, full order ID, slot and READY / PREPARING. It stays open, and
 *         keeps updating, until it is closed.
 *
 * Plain dark background (no photos) for clarity and speed on TV boxes.
 */
final class RiderBoardView extends View {

    interface Listener {
        void onClose();

        void onVoice(boolean on);

        /** How long ready orders stay: 0 = until picked up, else minutes. */
        void onDuration(int minutes);

        /** 0 = auto (light sensor), 1 = 50 %, 2 = 75 %, 3 = 100 %. */
        void onBrightness(int mode);
    }

    private static final int[] DURATIONS = {0, 15, 30, 60};
    private int duration = 0;
    private final RectF hitDur = new RectF(), hitBright = new RectF();
    private int brightMode;
    private boolean hasSensor;

    void setBrightness(int mode, boolean sensor) {
        brightMode = mode;
        hasSensor = sensor;
        invalidate();
    }

    void setDuration(int minutes) {
        duration = minutes;
        invalidate();
    }

    private static final long CALL_MS = 6_000L;
    private static final long CONTROLS_MS = 5_000L, CONTROLS_FADE_MS = 800L;
    private static final int GREEN = 0xFF69F0AE, GOLD = 0xFFF5B21B, CREAM = 0xFFFFF4DC;

    private final float d;
    private final long start = SystemClock.uptimeMillis();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF(), dst = new RectF();
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final DateFormat clock;
    private final boolean lite;
    private Bitmap logo;
    private Listener listener;
    private boolean voiceOn = true;

    private List<RiderCalls.Waiting> ready = new ArrayList<RiderCalls.Waiting>();
    private List<RiderCalls.Order> preparing = new ArrayList<RiderCalls.Order>();
    private String bridgeStatus = "";
    private boolean bridgeOk;
    private final Map<Long, Long> firstSeen = new HashMap<Long, Long>();
    private List<RiderCalls.Waiting> call;
    private long callAt;
    private long controlsAt = SystemClock.uptimeMillis();
    private final RectF hitVoice = new RectF(), hitClose = new RectF();
    private int focus = -1; // remote: 0 search, 1 brightness, 2 duration, 3 voice, 4 close
    private final RectF hitSearch = new RectF();
    private float headCy, headH, clockLeft;

    // search: keypad + results, open until closed
    private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "CLEAR", "0", "\u232B"};
    private static final int MAX_DIGITS = 8;
    private boolean searchOn;
    private final StringBuilder query = new StringBuilder();
    private int sFocus = -1; // remote: 0..11 keypad, 12 close
    private final RectF[] hitKeys = new RectF[KEYS.length];
    private final RectF hitSearchClose = new RectF();
    private long lastTypeAt, keyFlashAt;
    private int keyFlash = -1;
    private boolean onBeforeBurst;
    private String queryBeforeBurst = "";
    private RadialGradient bg;
    private float bgKey;
    private String clockStr = "";
    private long clockAt;
    private float s;

    RiderBoardView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        clock = android.text.format.DateFormat.getTimeFormat(c);
        lite = DosaNeonView.isLowEnd(c);
        try {
            java.io.InputStream in = c.getAssets().open("dosa_live_logo.png");
            logo = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
        } catch (Throwable ignored) {
            logo = null;
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < hitKeys.length; i++) hitKeys[i] = new RectF();
        setClickable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setVoice(boolean on) {
        voiceOn = on;
        invalidate();
    }

    void setData(List<RiderCalls.Waiting> readyOrders, List<RiderCalls.Order> preparingOrders, String bridge,
                 boolean bridgeConnected) {
        long now = SystemClock.uptimeMillis();
        ready = new ArrayList<RiderCalls.Waiting>(readyOrders);
        preparing = new ArrayList<RiderCalls.Order>(preparingOrders);
        bridgeStatus = bridge;
        bridgeOk = bridgeConnected;
        List<Long> live = new ArrayList<Long>();
        for (RiderCalls.Waiting w : ready) {
            live.add(w.order.id);
            if (!firstSeen.containsKey(w.order.id)) firstSeen.put(w.order.id, now);
        }
        firstSeen.keySet().retainAll(live);
        invalidate();
    }

    void showCall(List<RiderCalls.Waiting> orders) {
        call = new ArrayList<RiderCalls.Waiting>(orders);
        callAt = SystemClock.uptimeMillis();
        invalidate();
    }

    // ---- input ---------------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_UP) {
            float x = ev.getX(), y = ev.getY();
            boolean visible = controlsAlpha() > 0.3f;
            if (call != null && SystemClock.uptimeMillis() - callAt < CALL_MS) call = null;
            else if (searchOn) searchTouch(x, y);
            else if (hitSearch.contains(x, y)) activate(0);
            else if (visible && hitClose.contains(x, y)) activate(4);
            else if (visible && hitVoice.contains(x, y)) activate(3);
            else if (visible && hitDur.contains(x, y)) activate(2);
            else if (visible && hitBright.contains(x, y)) activate(1);
            controlsAt = SystemClock.uptimeMillis();
            invalidate();
        }
        return true;
    }

    private void activate(int i) {
        if (i == 0) {
            openSearch();
        } else if (i == 1) {
            brightMode = (brightMode + 1) % 4;
            if (listener != null) listener.onBrightness(brightMode);
        } else if (i == 2) {
            int j = 0;
            while (j < DURATIONS.length && DURATIONS[j] != duration) j++;
            duration = DURATIONS[(j + 1) % DURATIONS.length];
            if (listener != null) listener.onDuration(duration);
        } else if (i == 3) {
            voiceOn = !voiceOn;
            if (listener != null) listener.onVoice(voiceOn);
        } else if (listener != null) {
            listener.onClose();
        }
    }

    /** TV remote: LEFT/RIGHT between SEARCH ... close, OK presses, BACK closes. */
    boolean handleKey(KeyEvent e) {
        if (searchOn) return searchKey(e);
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_BACK:
                if (!down && listener != null) listener.onClose();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (focus < 0 || controlsAlpha() < 0.3f) focus = 3;
                    else focus = Math.max(0, Math.min(4, focus
                            + (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                    controlsAt = SystemClock.uptimeMillis();
                    invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (!down) {
                    if (focus < 0 || controlsAlpha() < 0.3f) focus = 3;
                    else activate(focus);
                    controlsAt = SystemClock.uptimeMillis();
                    invalidate();
                }
                return true;
            default:
                if (down) controlsAt = SystemClock.uptimeMillis();
                return true;
        }
    }

    // ---- search ----------------------------------------------------------------------------

    void openSearch() {
        searchOn = true;
        sFocus = -1;
        invalidate();
    }

    private void closeSearch() {
        searchOn = false;
        query.setLength(0);
        sFocus = -1;
        controlsAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private static int digitOf(int k) {
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) return k - KeyEvent.KEYCODE_0;
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) return k - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    /**
     * Number keys / Delete from a keyboard, number pad or remote (they reach the board before the
     * KDS). A number opens the search. A barcode scan arrives the same way, very fast: see
     * {@link #undoScan()}.
     */
    boolean typeKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean del = k == KeyEvent.KEYCODE_DEL || k == KeyEvent.KEYCODE_FORWARD_DEL;
        int dg = digitOf(k);
        if (dg < 0 && !(del && searchOn)) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            long now = SystemClock.uptimeMillis();
            if (now - lastTypeAt > 80) {
                onBeforeBurst = searchOn;
                queryBeforeBurst = query.toString();
            }
            lastTypeAt = now;
            if (!searchOn) openSearch();
            press(dg >= 0 ? (dg == 0 ? 10 : dg - 1) : 11);
        }
        return true;
    }

    /** A scan was read from the same keys: put the search back as it was before the burst. */
    void undoScan() {
        query.setLength(0);
        query.append(queryBeforeBurst);
        if (!onBeforeBurst) searchOn = false;
        invalidate();
    }

    private void press(int key) {
        keyFlash = key;
        keyFlashAt = SystemClock.uptimeMillis();
        if (key == 12) {
            closeSearch();
            return;
        }
        String kv = KEYS[key];
        if (key == 9) query.setLength(0);
        else if (key == 11) {
            if (query.length() > 0) query.setLength(query.length() - 1);
        } else if (query.length() < MAX_DIGITS) query.append(kv);
        invalidate();
    }

    private void searchTouch(float x, float y) {
        if (hitSearchClose.contains(x, y)) {
            press(12);
            return;
        }
        for (int i = 0; i < hitKeys.length; i++) {
            if (hitKeys[i].contains(x, y)) {
                sFocus = -1;
                press(i);
                return;
            }
        }
    }

    /** Remote on the search screen: arrows move over the keypad (up from the top row = close). */
    private boolean searchKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_BACK:
                if (!down) closeSearch();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (sFocus < 0) sFocus = 4;
                    else if (sFocus == 12) sFocus = k == KeyEvent.KEYCODE_DPAD_DOWN ? 2 : 12;
                    else {
                        int r = sFocus / 3, col = sFocus % 3;
                        if (k == KeyEvent.KEYCODE_DPAD_LEFT) col = Math.max(0, col - 1);
                        else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) col = Math.min(2, col + 1);
                        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) r = Math.min(3, r + 1);
                        else r = r - 1;
                        sFocus = r < 0 ? 12 : r * 3 + col;
                    }
                    invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (!down) {
                    if (sFocus < 0) sFocus = 4;
                    else press(sFocus);
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }

    private float controlsAlpha() {
        long since = SystemClock.uptimeMillis() - controlsAt;
        if (since <= CONTROLS_MS) return 1f;
        return Math.max(0f, 1f - (since - CONTROLS_MS) / (float) CONTROLS_FADE_MS);
    }

    // ---- drawing ---------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        long frameStart = SystemClock.uptimeMillis();
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        float t = ((now - start) % (6 * 3600_000L)) / 1000f;
        boolean land = w >= h;
        s = land ? Math.min(w / 1920f, h / 1080f) : Math.min(w / 1080f, h / 1920f);

        if (bg == null || bgKey != w * 7 + h) {
            bgKey = w * 7 + h;
            bg = new RadialGradient(w * 0.3f, h * 0.35f, Math.max(w, h),
                    new int[]{0xFF1E2A3A, 0xFF111823, 0xFF080B10}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        }
        fill.setShader(bg);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        // Full screen: 10 s after the last touch / remote key the header slims down, the footer
        // and margins go and the system bars hide, so the order lists get nearly the whole screen.
        boolean wantFull = now - controlsAt >= FULL_AFTER_MS;
        long dt = lastFrame == 0 ? 0 : Math.min(200L, now - lastFrame);
        lastFrame = now;
        full += ((wantFull ? 1f : 0f) - full) * Math.min(1f, dt / 180f);
        if (Math.abs(full - (wantFull ? 1f : 0f)) < 0.01f) full = wantFull ? 1f : 0f;
        if (wantFull != immersive) setImmersive(wantFull);
        float f = full;
        float pad = lerp(40, 14, f) * s;
        float headY = lerp(26, 6, f) * s, headH = lerp(96, 50, f) * s;
        drawHeader(c, pad, headY, w - 2 * pad, headH, t, f);
        float top = headY + headH + lerp(18, 4, f) * s, bottom = h - lerp(70, 10, f) * s;
        if (land) {
            // Food Ready gets more room as it fills up (it must show every order).
            float share = Math.max(0.58f, Math.min(0.74f, 0.5f + ready.size() / 120f));
            float gap = 24 * s, leftW = (w - 2 * pad - gap) * share;
            drawReady(c, pad, top, leftW, bottom - top, now, t);
            drawPreparing(c, pad + leftW + gap, top, w - 2 * pad - leftW - gap, bottom - top);
        } else {
            float rh = (bottom - top) * 0.62f;
            drawReady(c, pad, top, w - 2 * pad, rh, now, t);
            drawPreparing(c, pad, top + rh + 20 * s, w - 2 * pad, bottom - top - rh - 20 * s);
        }
        if (f < 1f) drawFooter(c, pad, h - 52 * s, w - 2 * pad, 1f - f);
        drawControls(c, w);
        if (searchOn) drawSearch(c, w, h, now, land);
        drawCall(c, w, h, now, t);

        if (lite) postInvalidateDelayed(Math.max(1L, 33L - (SystemClock.uptimeMillis() - frameStart)));
        else postInvalidateOnAnimation();
    }

    private void drawHeader(Canvas c, float x, float y, float w, float h, float t, float f) {
        float cy = y + h / 2f, k = h / (96 * s); // everything scales with the header height
        int sub = (int) (255 * (1f - f));
        if (logo != null) {
            dst.set(x, y + h * 0.05f, x + h * 0.9f, y + h * 0.95f);
            bmp.setAlpha(255);
            c.drawBitmap(logo, null, dst, bmp);
        }
        float tx = x + h + 14 * s * k;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setLetterSpacing(0.12f);
        text.setTextSize(46 * s * k);
        text.setColor(Color.WHITE);
        c.drawText("\uD83D\uDEF5 RIDER PICKUP", tx, cy + lerp(6, 16, f) * s * k, text);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.3f);
        text.setTextSize(17 * s);
        text.setColor(alpha(GOLD, sub));
        if (sub > 0) c.drawText("BABAI TIFFINS \u2022 FIND YOUR ORDER NUMBER, GO TO THE SLOT", tx, cy + 34 * s, text);
        text.setLetterSpacing(0f);

        long nowMs = System.currentTimeMillis();
        if (nowMs - clockAt >= 1000) {
            clockAt = nowMs;
            clockStr = clock.format(new Date(nowMs));
        }
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTypeface(bold);
        text.setTextSize(44 * s * k);
        text.setColor(Color.WHITE);
        float clockBase = cy + lerp(4, 16, f) * s * k;
        c.drawText(clockStr, x + w, clockBase, text);
        float clockW = text.measureText(clockStr);
        headCy = cy;
        headH = h;
        clockLeft = x + w - clockW;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 1.6);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.3f);
        text.setTextSize(16 * s);
        text.setColor(alpha(GREEN, sub));
        if (sub > 0) c.drawText("LIVE", x + w, cy + 32 * s, text);
        // live dot: under the clock normally, left of the clock in full screen
        fill.setColor(GREEN);
        fill.setAlpha((int) (120 + 135 * pulse));
        float dx0 = x + w - text.measureText("LIVE") - 14 * s, dy0 = cy + 26 * s;
        float dx1 = x + w - clockW - 18 * s, dy1 = clockBase - 13 * s * k;
        c.drawCircle(lerp(dx0, dx1, f), lerp(dy0, dy1, f), 6 * s, fill);
        fill.setAlpha(255);
        text.setLetterSpacing(0f);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawReady(Canvas c, float x, float y, float w, float h, long now, float t) {
        List<RiderCalls.Order> l = new ArrayList<RiderCalls.Order>();
        for (RiderCalls.Waiting rw : ready) l.add(rw.order);
        title(c, x, y + 30 * s, w, "\u2713 FOOD READY \u2014 PICK UP", GREEN, l.isEmpty() ? null : l.size() + " ready");
        drawTable(c, x, y + 46 * s, w, h - 46 * s, l, true, t, "No orders waiting for pickup");
    }

    private void drawPreparing(Canvas c, float x, float y, float w, float h) {
        title(c, x, y + 30 * s, w, "\uD83D\uDD25 PREPARING", GOLD, preparing.isEmpty() ? null : preparing.size() + " orders");
        drawTable(c, x, y + 46 * s, w, h - 46 * s, preparing, false, (SystemClock.uptimeMillis() - start) / 1000f,
                "No orders being prepared");
    }

    /**
     * Every order as one line "Swiggy - 9090 - 20" under BRAND / ORDER ID / SLOT headings, in as
     * many columns as needed so all orders fit; pages only when there are very many.
     */
    private void drawTable(Canvas c, float x, float y, float w, float h, List<RiderCalls.Order> items, boolean isReady,
                           float t, String empty) {
        panel(c, x, y, w, h, 20 * s, isReady ? 0x5514301F : 0x66141C27, isReady ? 0x6669F0AE : 0x33FFFFFF);
        float pad = 14 * s, ix = x + pad, iw = w - 2 * pad, top = y + pad, bottom = y + h - pad;
        int n = items.size();
        if (n == 0) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(medium);
            text.setTextSize(26 * s);
            text.setColor(0xAAFFFFFF);
            c.drawText(empty, x + w / 2f, y + h / 2f, text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        float headH = 30 * s, gap = 14 * s;
        // Food Ready: always every order on one screen (more columns, smaller lines; never pages).
        // Preparing: lines stay readable and the list pages when it doesn't fit.
        float minColW = (isReady ? (n > 24 ? 230 : 300) : 280) * s, maxRow = (isReady ? 84 : 58) * s;
        float minRow = (isReady ? 1 : 32) * s;
        int maxCols = Math.max(1, (int) ((iw + gap) / (minColW + gap)));
        int cols = 1;
        float rowH = 0;
        for (int k = 1; k <= maxCols; k++) {
            int rows = (n + k - 1) / k;
            float rh = Math.min(maxRow, (bottom - top - headH) / rows);
            if (rh > rowH + 0.5f) {
                rowH = rh;
                cols = k;
            }
            if (rh >= maxRow) break; // biggest rows already reached: fewer columns read easier
        }
        int perPage = n, pages = 1;
        if (!isReady && rowH < minRow) {
            rowH = minRow;
            cols = maxCols;
            int rows = Math.max(1, (int) ((bottom - top - headH) / rowH));
            perPage = rows * cols;
            pages = (n + perPage - 1) / perPage;
        }
        int page = pages > 1 ? (int) (t / 8f) % pages : 0;
        int rowsPerCol = (Math.min(perPage, n) + cols - 1) / cols;
        float colW = (iw - (cols - 1) * gap) / cols;
        // Full order ID (small, bold) under each line when the rows are tall enough.
        boolean fullRows = rowH >= 44 * s;
        float fs = Math.min(isReady ? 40 * s : 26 * s, rowH * (fullRows ? 0.50f : 0.62f));
        // Measured layout, no separators: [brand ......  8888  88]. The number and slot keep at
        // most ~62% of the line; the brand gets the rest, so nothing overlaps at any column width.
        float left = 16 * s, right = 8 * s, sp = 10 * s, inner = colW - left - right;
        text.setTypeface(bold);
        text.setTextSize(100f);
        float w4 = text.measureText("8888") / 100f, w2 = text.measureText("88") / 100f;
        fs = Math.min(fs, (inner * 0.62f - sp) / (w4 + w2));
        float slotW = w2 * fs, numW = w4 * fs;
        float slotRight = colW - right;                    // offsets from the column's left edge
        // Order ID in the middle of the card (pushed left only if the slot needs the room).
        float numCenter = Math.min(colW * 0.56f, slotRight - slotW - sp - numW / 2f);
        float brandMax = numCenter - numW / 2f - sp - left;
        // headings, fitted to the same positions
        text.setTypeface(condensed);
        text.setLetterSpacing(0.18f);
        float hs = Math.min(15 * s, headH * 0.55f);
        text.setTextSize(100f);
        float hOrder = text.measureText("ORDER ID") / 100f, hSlot = text.measureText("SLOT") / 100f,
                hBrand = text.measureText("BRAND") / 100f;
        hs = Math.min(hs, (slotRight - numCenter - 6 * s) / (hOrder / 2f + hSlot));
        hs = Math.min(hs, (numCenter - left - 6 * s) / (hOrder / 2f + hBrand));
        text.setTextSize(hs);
        text.setColor(isReady ? 0xCC69F0AE : 0xCCF5B21B);
        for (int col = 0; col < cols; col++) {
            float cx = ix + col * (colW + gap);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("BRAND", cx + left, top + headH * 0.62f, text);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("ORDER ID", cx + numCenter, top + headH * 0.62f, text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("SLOT", cx + slotRight, top + headH * 0.62f, text);
        }
        text.setLetterSpacing(0f);
        int from = page * perPage, to = Math.min(n, from + perPage);
        for (int i = from; i < to; i++) {
            int j = i - from, col = j / rowsPerCol, row = j % rowsPerCol;
            float cx = ix + col * (colW + gap), ry = top + headH + row * rowH;
            RiderCalls.Order o = items.get(i);
            int pc = platformColor(o.platform);
            rect.set(cx, ry + rowH * 0.08f, cx + colW, ry + rowH * 0.92f);
            fill.setColor(isReady ? 0xF2FFFFFF : 0x1FFFFFFF);
            c.drawRoundRect(rect, rowH * 0.18f, rowH * 0.18f, fill);
            fill.setColor(pc);
            rect.set(cx, ry + rowH * 0.08f, cx + 8 * s, ry + rowH * 0.92f);
            c.drawRoundRect(rect, 4 * s, 4 * s, fill);
            boolean full = fullRows && o.orderId != null && o.orderId.length() > 4;
            float base = full ? ry + rowH * 0.56f : ry + rowH * 0.5f + fs * 0.36f;
            int fg = isReady ? 0xFF111111 : Color.WHITE;
            // brand
            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(bold);
            text.setColor(isReady ? pc : fg);
            text.setTextSize(fit(o.platform, fs * 0.8f, brandMax));
            c.drawText(o.platform, cx + left, base, text);
            // last 4 of the order ID
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(fg);
            text.setTextSize(fit(o.last4, fs, numW));
            c.drawText(o.last4, cx + numCenter, base, text);
            // slot
            text.setTextAlign(Paint.Align.RIGHT);
            boolean hasSlot = o.slot != null && !o.slot.isEmpty();
            String slot = hasSlot ? o.slot : "\u2014";
            text.setColor(isReady ? 0xFF1B5E20 : GOLD);
            text.setTextSize(fit(slot, fs, slotW + sp * 0.6f));
            c.drawText(slot, cx + slotRight, base, text);
            // full order ID: small but bold, centred under the last 4
            if (full) {
                text.setTextAlign(Paint.Align.CENTER);
                text.setTypeface(bold);
                text.setColor(isReady ? 0xDD222222 : 0xDDFFFFFF);
                float idMax = 2f * Math.min(numCenter - left, slotRight - numCenter);
                text.setTextSize(fit(o.orderId, Math.min(16 * s, rowH * 0.2f), idMax));
                c.drawText(o.orderId, cx + numCenter, ry + rowH * 0.85f, text);
            }
        }
        if (pages > 1) {
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTypeface(condensed);
            text.setTextSize(16 * s);
            text.setColor(0xAAFFFFFF);
            c.drawText("page " + (page + 1) + " / " + pages, x + w - pad, y - 4 * s, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawFooter(Canvas c, float x, float y, float w, float a) {
        int ia = (int) (255 * a);
        fill.setColor(alpha(bridgeOk ? GREEN : 0xFFFF8A65, ia));
        c.drawCircle(x + 8 * s, y + 16 * s, 6 * s, fill);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(medium);
        text.setTextSize(17 * s);
        text.setColor(alpha(Color.WHITE, (int) (0xAA * a)));
        c.drawText(bridgeStatus, x + 22 * s, y + 22 * s, text);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(alpha(Color.WHITE, (int) (0x88 * a)));
        c.drawText(voiceOn ? "\uD83D\uDD0A Calls on" : "\uD83D\uDD07 Calls off", x + w, y + 22 * s, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawControls(Canvas c, float w) {
        float a = controlsAlpha();
        hitVoice.setEmpty();
        hitClose.setEmpty();
        hitDur.setEmpty();
        hitBright.setEmpty();
        hitSearch.setEmpty();
        // Search: a small round button left of the clock while the controls are hidden (also in
        // full screen); a SEARCH pill at the start of the controls row while they show.
        if (a < 1f && !searchOn) {
            float r = Math.min(24 * s, headH * 0.4f), bx = clockLeft - 40 * s - r;
            int ia = (int) (255 * (1f - a));
            fill.setColor(alpha(0xFFF5B21B, (int) (0.9f * ia)));
            c.drawCircle(bx, headCy, r, fill);
            drawGlass(c, bx, headCy, r * 0.42f, alpha(0xFF1B1203, ia));
            if (a <= 0.3f) {
                hitSearch.set(bx - r, headCy - r, bx + r, headCy + r);
                hitSearch.inset(-12 * s, -12 * s);
            }
        }
        if (a <= 0f) return;
        float h = 44 * s, y = 74 * s - h / 2f;
        text.setTypeface(bold);
        text.setTextSize(44 * s);
        float right = w - 40 * s - text.measureText(clockStr) - 28 * s;
        rect.set(right - h, y, right, y + h);
        hitClose.set(rect);
        hitClose.inset(-10 * s, -10 * s);
        fill.setColor(alpha(Color.WHITE, (int) (0x33 * a)));
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        stroke.setColor(alpha(Color.WHITE, (int) (220 * a)));
        stroke.setStrokeWidth(2.5f * s);
        float k = h * 0.2f, cx = rect.centerX(), cy = rect.centerY();
        c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke);
        c.drawLine(cx - k, cy + k, cx + k, cy - k, stroke);
        String v = voiceOn ? "\uD83D\uDD0A  CALLS ON" : "\uD83D\uDD07  CALLS OFF";
        text.setTypeface(condensed);
        text.setLetterSpacing(0.1f);
        text.setTextSize(18 * s);
        float bw = text.measureText(v) + 36 * s;
        float bx = right - h - 12 * s;
        rect.set(bx - bw, y, bx, y + h);
        hitVoice.set(rect);
        hitVoice.inset(-6 * s, -10 * s);
        fill.setColor(alpha(voiceOn ? 0xFF1E88E5 : 0xFF546E7A, (int) (235 * a)));
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(alpha(Color.WHITE, (int) (255 * a)));
        c.drawText(v, rect.centerX(), rect.centerY() + 6 * s, text);
        // duration pill, left of the voice pill
        String dl = duration == 0 ? "\u23F1  SHOW UNTIL PICKED UP" : "\u23F1  SHOW " + duration + " MIN";
        float dw = text.measureText(dl) + 36 * s;
        float dx = bx - bw - 12 * s;
        rect.set(dx - dw, y, dx, y + h);
        hitDur.set(rect);
        hitDur.inset(-6 * s, -10 * s);
        fill.setColor(alpha(0xFF37474F, (int) (235 * a)));
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        text.setColor(alpha(Color.WHITE, (int) (255 * a)));
        c.drawText(dl, rect.centerX(), rect.centerY() + 6 * s, text);
        // brightness pill, left of the duration pill
        String bl = brightMode == 0 ? (hasSensor ? "\u2600  AUTO" : "\u2600  AUTO (MAX)")
                : "\u2600  " + (brightMode == 1 ? 50 : brightMode == 2 ? 75 : 100) + "%";
        float blw = text.measureText(bl) + 36 * s;
        float blx = dx - dw - 12 * s;
        rect.set(blx - blw, y, blx, y + h);
        hitBright.set(rect);
        hitBright.inset(-6 * s, -10 * s);
        fill.setColor(alpha(0xFF8D6E00, (int) (235 * a)));
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        text.setColor(alpha(Color.WHITE, (int) (255 * a)));
        c.drawText(bl, rect.centerX(), rect.centerY() + 6 * s, text);
        // search pill, first in the row
        String sl = "SEARCH ORDER";
        float sw = text.measureText(sl) + 72 * s;
        float sx = blx - blw - 12 * s;
        rect.set(sx - sw, y, sx, y + h);
        if (a > 0.3f) {
            hitSearch.set(rect);
            hitSearch.inset(-6 * s, -10 * s);
        }
        fill.setColor(alpha(0xFFF5B21B, (int) (240 * a)));
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        drawGlass(c, rect.left + 26 * s, rect.centerY(), 8 * s, alpha(0xFF1B1203, (int) (255 * a)));
        text.setColor(alpha(0xFF1B1203, (int) (255 * a)));
        c.drawText(sl, rect.centerX() + 14 * s, rect.centerY() + 6 * s, text);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0f);
        if (focus >= 0) {
            RectF fr = focus == 0 ? hitSearch : focus == 1 ? hitBright : focus == 2 ? hitDur : focus == 3 ? hitVoice : hitClose;
            rect.set(fr);
            rect.inset(focus == 4 ? 6 * s : 2 * s, 6 * s);
            stroke.setColor(alpha(Color.WHITE, (int) (255 * a)));
            stroke.setStrokeWidth(3 * s);
            c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, stroke);
        }
    }

    /** Magnifying-glass icon (drawn, so it looks the same on every TV box). */
    private void drawGlass(Canvas c, float cx, float cy, float r, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(Math.max(2f, r * 0.38f));
        c.drawCircle(cx - r * 0.2f, cy - r * 0.2f, r, stroke);
        c.drawLine(cx + r * 0.52f, cy + r * 0.52f, cx + r * 1.25f, cy + r * 1.25f, stroke);
    }

    /** Search screen: keypad + typed digits on one side, matching orders (live) on the other. */
    private void drawSearch(Canvas c, float w, float h, long now, boolean land) {
        fill.setColor(0xEE05080D);
        c.drawRect(0, 0, w, h, fill);
        float cw = Math.min(w - 32 * s, 1800 * s), ch = land ? Math.min(h - 32 * s, 1040 * s) : h - 32 * s;
        float x = (w - cw) / 2f, y = (h - ch) / 2f;
        panel(c, x, y, cw, ch, 28 * s, 0xFF101722, 0x88F5B21B);
        float pad = 28 * s;

        // title + close
        drawGlass(c, x + pad + 14 * s, y + pad + 22 * s, 13 * s, GOLD);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setLetterSpacing(0.12f);
        text.setTextSize(36 * s);
        text.setColor(Color.WHITE);
        c.drawText("FIND ORDER", x + pad + 46 * s, y + pad + 36 * s, text);
        text.setLetterSpacing(0f);
        float cr = 30 * s, ccx = x + cw - pad - cr, ccy = y + pad + 22 * s;
        hitSearchClose.set(ccx - cr - 12 * s, ccy - cr - 12 * s, ccx + cr + 12 * s, ccy + cr + 12 * s);
        boolean flashClose = keyFlash == 12 && now - keyFlashAt < 150;
        fill.setColor(flashClose ? 0x66FFFFFF : 0x33FFFFFF);
        c.drawCircle(ccx, ccy, cr, fill);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(3 * s);
        float k = cr * 0.4f;
        c.drawLine(ccx - k, ccy - k, ccx + k, ccy + k, stroke);
        c.drawLine(ccx - k, ccy + k, ccx + k, ccy - k, stroke);
        if (sFocus == 12) {
            stroke.setStrokeWidth(4 * s);
            stroke.setColor(GOLD);
            c.drawCircle(ccx, ccy, cr + 6 * s, stroke);
        }

        float top = y + pad + 70 * s, bottom = y + ch - pad;
        float kx, ky, kw, kh, rx, ry, rw, rh;
        if (land) {
            kw = Math.min(cw * 0.36f, 560 * s);
            kx = x + pad;
            ky = top;
            kh = bottom - top;
            rx = kx + kw + 32 * s;
            ry = top;
            rw = x + cw - pad - rx;
            rh = kh;
        } else {
            kw = cw - 2 * pad;
            kx = x + pad;
            ky = top;
            kh = (bottom - top) * 0.5f;
            rx = kx;
            ry = ky + kh + 24 * s;
            rw = kw;
            rh = bottom - ry;
        }

        // typed digits
        float boxH = Math.min(120 * s, kh * 0.2f);
        rect.set(kx, ky, kx + kw, ky + boxH);
        fill.setColor(0xFF060A10);
        c.drawRoundRect(rect, 18 * s, 18 * s, fill);
        stroke.setColor(GOLD);
        stroke.setStrokeWidth(2.5f * s);
        c.drawRoundRect(rect, 18 * s, 18 * s, stroke);
        boolean blink = (now / 530) % 2 == 0;
        text.setTextAlign(Paint.Align.CENTER);
        if (query.length() == 0) {
            text.setTypeface(medium);
            text.setTextSize(fit("Last 4 digits of the order ID", boxH * 0.3f, kw - 40 * s));
            text.setColor(0x88FFFFFF);
            c.drawText("Last 4 digits of the order ID", rect.centerX(), rect.centerY() + text.getTextSize() * 0.36f, text);
        } else {
            text.setTypeface(bold);
            text.setLetterSpacing(0.25f);
            String q = query.toString() + (blink ? "_" : " ");
            text.setTextSize(fit(q, boxH * 0.62f, kw - 40 * s));
            text.setColor(Color.WHITE);
            c.drawText(q, rect.centerX(), rect.centerY() + text.getTextSize() * 0.36f, text);
            text.setLetterSpacing(0f);
        }

        // keypad 3 x 4
        float kgap = 14 * s, kTop = ky + boxH + 20 * s;
        float keyW = (kw - 2 * kgap) / 3f, keyH = Math.min(keyW * 0.7f, (ky + kh - kTop - 3 * kgap) / 4f);
        for (int i = 0; i < KEYS.length; i++) {
            int r = i / 3, col = i % 3;
            RectF kr = hitKeys[i];
            kr.set(kx + col * (keyW + kgap), kTop + r * (keyH + kgap), kx + col * (keyW + kgap) + keyW,
                    kTop + r * (keyH + kgap) + keyH);
            boolean flash = keyFlash == i && now - keyFlashAt < 150;
            boolean special = i == 9 || i == 11;
            fill.setColor(flash ? 0xFFF5B21B : special ? 0xFF2A3442 : 0xFF1F2A38);
            c.drawRoundRect(kr, 16 * s, 16 * s, fill);
            if (sFocus == i) {
                stroke.setColor(GOLD);
                stroke.setStrokeWidth(4 * s);
                c.drawRoundRect(kr, 16 * s, 16 * s, stroke);
            }
            text.setTypeface(special ? condensed : bold);
            text.setLetterSpacing(special && i == 9 ? 0.15f : 0f);
            text.setTextSize(fit(KEYS[i], keyH * (special ? 0.32f : 0.5f), keyW - 20 * s));
            text.setColor(flash ? 0xFF1B1203 : special ? 0xFFFFE082 : Color.WHITE);
            c.drawText(KEYS[i], kr.centerX(), kr.centerY() + text.getTextSize() * 0.36f, text);
        }
        text.setLetterSpacing(0f);

        drawResults(c, rx, ry, rw, rh, System.currentTimeMillis());
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawResults(Canvas c, float x, float y, float w, float h, long wall) {
        String q = query.toString();
        text.setTextAlign(Paint.Align.CENTER);
        if (q.isEmpty()) {
            text.setTypeface(medium);
            text.setColor(0xAAFFFFFF);
            String l1 = "Type the last 4 digits of the order ID";
            String l2 = "to see brand, order ID, slot and READY / PREPARING";
            text.setTextSize(fit(l1, 32 * s, w - 40 * s));
            c.drawText(l1, x + w / 2f, y + h / 2f - 10 * s, text);
            text.setTextSize(fit(l2, 22 * s, w - 40 * s));
            text.setColor(0x77FFFFFF);
            c.drawText(l2, x + w / 2f, y + h / 2f + 30 * s, text);
            return;
        }
        List<RiderCalls.Hit> hits = RiderCalls.find(q, ready, preparing);
        if (hits.isEmpty()) {
            text.setTypeface(bold);
            text.setColor(0xFFFF8A65);
            String l1 = "No order ending " + q + " on the board";
            text.setTextSize(fit(l1, 38 * s, w - 40 * s));
            c.drawText(l1, x + w / 2f, y + h / 2f - 12 * s, text);
            text.setTypeface(medium);
            text.setColor(0x99FFFFFF);
            String l2 = q.length() < 4 ? "Keep typing the last 4 digits" : "Already picked up, or not sent to the kitchen yet";
            text.setTextSize(fit(l2, 24 * s, w - 40 * s));
            c.drawText(l2, x + w / 2f, y + h / 2f + 30 * s, text);
            return;
        }
        // count line
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.15f);
        text.setTextSize(20 * s);
        text.setColor(0xCCF5B21B);
        c.drawText(hits.size() == 1 ? "1 ORDER FOUND" : hits.size() + " ORDERS FOUND", x, y + 20 * s, text);
        text.setLetterSpacing(0f);
        float top = y + 36 * s;
        float minRow = 110 * s, rowH = Math.min(230 * s, (y + h - top) / Math.max(1, hits.size()));
        int fit = hits.size();
        if (rowH < minRow) {
            rowH = minRow;
            fit = Math.max(1, (int) ((y + h - top - 40 * s) / rowH));
        }
        for (int i = 0; i < Math.min(fit, hits.size()); i++) {
            drawHit(c, hits.get(i), x, top + i * rowH, w, rowH - 12 * s, wall);
        }
        if (fit < hits.size()) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(medium);
            text.setTextSize(22 * s);
            text.setColor(0xAAFFFFFF);
            c.drawText("+" + (hits.size() - fit) + " more \u2022 type more digits", x + w / 2f, y + h - 8 * s, text);
        }
    }

    /** One result: [BRAND | ORDER ID 3136 + full ID | SLOT 47 | READY / PREPARING]. */
    private void drawHit(Canvas c, RiderCalls.Hit hit, float x, float y, float w, float h, long wall) {
        RiderCalls.Order o = hit.order;
        int pc = platformColor(o.platform);
        rect.set(x, y, x + w, y + h);
        fill.setColor(hit.ready ? 0xF2FFFFFF : 0x26FFFFFF);
        c.drawRoundRect(rect, 22 * s, 22 * s, fill);
        if (!hit.ready) {
            stroke.setColor(0x66F5B21B);
            stroke.setStrokeWidth(2 * s);
            c.drawRoundRect(rect, 22 * s, 22 * s, stroke);
        }
        fill.setColor(pc);
        rect.set(x, y, x + 12 * s, y + h);
        c.drawRoundRect(rect, 6 * s, 6 * s, fill);

        int fg = hit.ready ? 0xFF111111 : Color.WHITE;
        int lab = hit.ready ? 0x99000000 : 0x99FFFFFF;
        float lx = x + 30 * s, labY = y + h * 0.27f, valY = y + h * 0.66f;
        float c1 = w * 0.28f, c2 = w * 0.30f, c3 = w * 0.14f, c4 = w - c1 - c2 - c3 - 30 * s;
        float labS = Math.min(16 * s, h * 0.13f), valS = h * 0.42f;
        // labels
        text.setTypeface(condensed);
        text.setLetterSpacing(0.18f);
        text.setTextSize(labS);
        text.setColor(lab);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("BRAND", lx, labY, text);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("ORDER ID", lx + c1 + c2 / 2f, labY, text);
        c.drawText("SLOT", lx + c1 + c2 + c3 / 2f, labY, text);
        text.setLetterSpacing(0f);
        // brand
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setColor(hit.ready ? pc : Color.WHITE);
        text.setTextSize(fit(o.platform, valS * 0.62f, c1 - 16 * s));
        c.drawText(o.platform, lx, valY, text);
        // last 4 big + full ID small
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(fg);
        text.setTextSize(fit(o.last4, valS, c2 - 16 * s));
        c.drawText(o.last4, lx + c1 + c2 / 2f, valY, text);
        if (o.orderId.length() > 4) {
            text.setTextSize(fit(o.orderId, Math.min(18 * s, h * 0.14f), c2 - 8 * s));
            text.setColor(hit.ready ? 0xDD222222 : 0xDDFFFFFF);
            c.drawText(o.orderId, lx + c1 + c2 / 2f, y + h * 0.88f, text);
        }
        // slot
        boolean hasSlot = o.slot != null && !o.slot.isEmpty();
        String slot = hasSlot ? o.slot : "\u2014";
        text.setColor(hit.ready ? 0xFF1B5E20 : GOLD);
        text.setTextSize(fit(slot, valS, c3 - 8 * s));
        c.drawText(slot, lx + c1 + c2 + c3 / 2f, valY, text);
        // status chip
        float chx = lx + c1 + c2 + c3 + 10 * s, chw = c4 - 16 * s, chh = Math.min(h * 0.46f, 74 * s);
        rect.set(chx, y + h * 0.42f - chh / 2f, chx + chw, y + h * 0.42f + chh / 2f);
        fill.setColor(hit.ready ? 0xFF2E7D32 : 0xFFF5B21B);
        c.drawRoundRect(rect, chh / 2f, chh / 2f, fill);
        String st = hit.ready ? "\u2713 READY" : "PREPARING";
        text.setTypeface(bold);
        text.setLetterSpacing(0.08f);
        text.setTextSize(fit(st, chh * 0.5f, chw - 24 * s));
        text.setColor(hit.ready ? Color.WHITE : 0xFF1B1203);
        c.drawText(st, rect.centerX(), rect.centerY() + text.getTextSize() * 0.36f, text);
        text.setLetterSpacing(0f);
        long since = hit.ready ? hit.readyAt : o.createdMs;
        if (since > 0 && wall - since >= 0 && wall - since < 24 * 3600_000L) {
            long min = (wall - since) / 60_000L;
            String sub = hit.ready ? (min < 1 ? "ready just now" : "ready " + min + " min")
                    : (min < 1 ? "ordered just now" : "ordered " + min + " min ago");
            text.setTypeface(medium);
            text.setTextSize(fit(sub, Math.min(18 * s, h * 0.14f), chw));
            text.setColor(hit.ready ? 0xAA000000 : 0xAAFFFFFF);
            c.drawText(sub, rect.centerX(), y + h * 0.88f, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawCall(Canvas c, float w, float h, long now, float t) {
        if (call == null || call.isEmpty()) return;
        long age = now - callAt;
        if (age > CALL_MS) {
            call = null;
            return;
        }
        float vis = Math.min(Math.min(1f, age / 300f), Math.min(1f, (CALL_MS - age) / 400f));
        fill.setColor(alpha(0xFF04080C, (int) (215 * vis)));
        c.drawRect(0, 0, w, h, fill);
        int n = Math.min(call.size(), 4);
        float cw = Math.min(w * 0.86f, 1500 * s), rowH = Math.min(200 * s, (h * 0.7f) / n);
        float x = (w - cw) / 2f, y = (h - rowH * n) / 2f;
        float sc = popScale(age);
        c.save();
        c.scale(sc, sc, w / 2f, h / 2f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.4f);
        text.setTextSize(30 * s);
        text.setColor(alpha(GREEN, (int) (255 * vis)));
        c.drawText("READY FOR PICKUP", w / 2f, y - 26 * s, text);
        text.setLetterSpacing(0f);
        for (int i = 0; i < n; i++) {
            RiderCalls.Order o = call.get(i).order;
            float ry = y + i * rowH;
            rect.set(x, ry + 8 * s, x + cw, ry + rowH - 8 * s);
            fill.setColor(alpha(platformColor(o.platform), (int) (255 * vis)));
            c.drawRoundRect(rect, 26 * s, 26 * s, fill);
            boolean hasSlot = o.slot != null && !o.slot.isEmpty();
            String line = o.platform.toUpperCase(Locale.US) + "   \u2026" + o.last4 + (hasSlot ? "   \u2192   SLOT " + o.slot : "");
            text.setTypeface(bold);
            text.setTextSize(fit(line, rowH * 0.5f, cw - 60 * s));
            text.setColor(alpha(Color.WHITE, (int) (255 * vis)));
            c.drawText(line, w / 2f, rect.centerY() + text.getTextSize() * 0.36f, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        c.restore();
    }

    // ---- helpers -----------------------------------------------------------------------------

    static int platformColor(String p) {
        String l = p == null ? "" : p.toLowerCase(Locale.US);
        if (l.contains("swiggy")) return 0xFFFC8019;
        if (l.contains("zomato")) return 0xFFE23744;
        if (l.contains("ownly")) return 0xFF6A4CD6;
        if (l.contains("toing")) return 0xFF0097A7;
        if (l.contains("magicpin")) return 0xFF8E24AA;
        return 0xFF2E7D32;
    }

    private void title(Canvas c, float x, float baseY, float w, String title, int color, String count) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setLetterSpacing(0.2f);
        text.setTextSize(28 * s);
        text.setColor(color);
        c.drawText(title, x, baseY, text);
        text.setLetterSpacing(0f);
        if (count != null) {
            text.setTextSize(20 * s);
            float cw = text.measureText(count) + 30 * s;
            rect.set(x + w - cw, baseY - 26 * s, x + w, baseY + 8 * s);
            fill.setColor(0x22FFFFFF);
            c.drawRoundRect(rect, 17 * s, 17 * s, fill);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(color);
            c.drawText(count, rect.centerX(), baseY - 2 * s, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void panel(Canvas c, float x, float y, float w, float h, float r, int bgc, int border) {
        rect.set(x, y, x + w, y + h);
        fill.setShader(null);
        fill.setColor(bgc);
        c.drawRoundRect(rect, r, r, fill);
        stroke.setColor(border);
        stroke.setStrokeWidth(1.5f * s);
        c.drawRoundRect(rect, r, r, stroke);
    }

    private float fit(String str, float size, float maxW) {
        text.setTextSize(size);
        float tw = text.measureText(str);
        return tw > maxW ? size * maxW / tw : size;
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private static final long FULL_AFTER_MS = 10_000L;
    private float full;
    private long lastFrame;
    private boolean immersive;

    /** Hides / shows the Android status and navigation bars (swipe from the edge shows them briefly). */
    @SuppressWarnings("deprecation")
    private void setImmersive(boolean on) {
        immersive = on;
        try {
            setSystemUiVisibility(on ? (SYSTEM_UI_FLAG_IMMERSIVE_STICKY | SYSTEM_UI_FLAG_FULLSCREEN
                    | SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_LAYOUT_STABLE) : 0);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (immersive) setImmersive(false);
        super.onDetachedFromWindow();
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    private static float popScale(long age) {
        if (age >= 600) return 1f;
        float p = age / 600f;
        return (float) (1 + (-0.4 * Math.exp(-6 * p) * Math.cos(9 * p)));
    }
}
