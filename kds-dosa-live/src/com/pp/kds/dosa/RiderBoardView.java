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
    private int focus = -1; // remote: 0 brightness, 1 duration, 2 voice, 3 close
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
            if (visible && hitClose.contains(x, y)) activate(3);
            else if (visible && hitVoice.contains(x, y)) activate(2);
            else if (visible && hitDur.contains(x, y)) activate(1);
            else if (visible && hitBright.contains(x, y)) activate(0);
            else if (call != null) call = null;
            controlsAt = SystemClock.uptimeMillis();
            invalidate();
        }
        return true;
    }

    private void activate(int i) {
        if (i == 0) {
            brightMode = (brightMode + 1) % 4;
            if (listener != null) listener.onBrightness(brightMode);
        } else if (i == 1) {
            int j = 0;
            while (j < DURATIONS.length && DURATIONS[j] != duration) j++;
            duration = DURATIONS[(j + 1) % DURATIONS.length];
            if (listener != null) listener.onDuration(duration);
        } else if (i == 2) {
            voiceOn = !voiceOn;
            if (listener != null) listener.onVoice(voiceOn);
        } else if (listener != null) {
            listener.onClose();
        }
    }

    /** TV remote: LEFT/RIGHT between VOICE and close, OK presses, BACK closes. */
    boolean handleKey(KeyEvent e) {
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
                    if (focus < 0 || controlsAlpha() < 0.3f) focus = 2;
                    else focus = Math.max(0, Math.min(3, focus
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
                    if (focus < 0 || controlsAlpha() < 0.3f) focus = 2;
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

        float pad = 40 * s;
        drawHeader(c, pad, 26 * s, w - 2 * pad, 96 * s, t);
        float top = 140 * s, bottom = h - 70 * s;
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
        drawFooter(c, pad, h - 52 * s, w - 2 * pad);
        drawControls(c, w);
        drawCall(c, w, h, now, t);

        if (lite) postInvalidateDelayed(Math.max(1L, 33L - (SystemClock.uptimeMillis() - frameStart)));
        else postInvalidateOnAnimation();
    }

    private void drawHeader(Canvas c, float x, float y, float w, float h, float t) {
        float cy = y + h / 2f;
        if (logo != null) {
            dst.set(x, y + h * 0.05f, x + h * 0.9f, y + h * 0.95f);
            bmp.setAlpha(255);
            c.drawBitmap(logo, null, dst, bmp);
        }
        float tx = x + h + 14 * s;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setLetterSpacing(0.12f);
        text.setTextSize(46 * s);
        text.setColor(Color.WHITE);
        c.drawText("\uD83D\uDEF5 RIDER PICKUP", tx, cy + 6 * s, text);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.3f);
        text.setTextSize(17 * s);
        text.setColor(GOLD);
        c.drawText("BABAI TIFFINS \u2022 FIND YOUR ORDER NUMBER, GO TO THE SLOT", tx, cy + 34 * s, text);
        text.setLetterSpacing(0f);

        long nowMs = System.currentTimeMillis();
        if (nowMs - clockAt >= 1000) {
            clockAt = nowMs;
            clockStr = clock.format(new Date(nowMs));
        }
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTypeface(bold);
        text.setTextSize(44 * s);
        text.setColor(Color.WHITE);
        c.drawText(clockStr, x + w, cy + 4 * s, text);
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 1.6);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.3f);
        text.setTextSize(16 * s);
        text.setColor(GREEN);
        c.drawText("LIVE", x + w, cy + 32 * s, text);
        fill.setColor(GREEN);
        fill.setAlpha((int) (120 + 135 * pulse));
        c.drawCircle(x + w - text.measureText("LIVE") - 14 * s, cy + 26 * s, 6 * s, fill);
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
        float fs = Math.min(isReady ? 40 * s : 26 * s, rowH * 0.62f);
        // headings
        text.setTypeface(condensed);
        text.setLetterSpacing(0.18f);
        text.setTextSize(Math.min(15 * s, headH * 0.55f));
        text.setColor(isReady ? 0xCC69F0AE : 0xCCF5B21B);
        for (int col = 0; col < cols; col++) {
            float cx = ix + col * (colW + gap);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("BRAND", cx + 16 * s, top + headH * 0.62f, text);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("ORDER ID", cx + colW * 0.64f, top + headH * 0.62f, text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("SLOT", cx + colW - 6 * s, top + headH * 0.62f, text);
        }
        text.setLetterSpacing(0f);
        int from = page * perPage, to = Math.min(n, from + perPage);
        long wall = System.currentTimeMillis();
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
            float base = ry + rowH * 0.5f + fs * 0.36f;
            int fg = isReady ? 0xFF111111 : Color.WHITE;
            // brand
            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(bold);
            text.setColor(isReady ? pc : fg);
            String brand = o.platform;
            text.setTextSize(fit(brand, fs * 0.8f, colW * 0.36f));
            c.drawText(brand, cx + 16 * s, base, text);
            // dash - order id - dash - slot
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(isReady ? 0x88111111 : 0x88FFFFFF);
            text.setTextSize(fs * 0.8f);
            c.drawText("\u2013", cx + colW * 0.43f, base, text);
            c.drawText("\u2013", cx + colW * 0.84f, base, text);
            text.setColor(fg);
            text.setTextSize(fs);
            // Full order ID in small text under the big last 4 (for verification), when the row has room.
            boolean full = o.orderId != null && o.orderId.length() > 4 && rowH >= 44 * s;
            float numBase = full ? ry + rowH * 0.44f + fs * 0.3f : base;
            c.drawText(o.last4, cx + colW * 0.64f, numBase, text);
            if (full) {
                text.setTypeface(medium);
                text.setColor(isReady ? 0xAA111111 : 0xAAFFFFFF);
                text.setTextSize(fit(o.orderId, Math.min(14 * s, rowH * 0.19f), colW * 0.40f));
                c.drawText(o.orderId, cx + colW * 0.64f, ry + rowH * 0.84f, text);
                text.setTypeface(bold);
                text.setTextSize(fs);
            }
            text.setTextAlign(Paint.Align.RIGHT);
            boolean hasSlot = o.slot != null && !o.slot.isEmpty();
            text.setColor(isReady ? 0xFF1B5E20 : GOLD);
            c.drawText(hasSlot ? o.slot : "\u2014", cx + colW - 8 * s, base, text);
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

    private void drawFooter(Canvas c, float x, float y, float w) {
        fill.setColor(bridgeOk ? GREEN : 0xFFFF8A65);
        c.drawCircle(x + 8 * s, y + 16 * s, 6 * s, fill);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(medium);
        text.setTextSize(17 * s);
        text.setColor(0xAAFFFFFF);
        c.drawText(bridgeStatus, x + 22 * s, y + 22 * s, text);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(0x88FFFFFF);
        c.drawText(voiceOn ? "\uD83D\uDD0A Calls on" : "\uD83D\uDD07 Calls off", x + w, y + 22 * s, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawControls(Canvas c, float w) {
        float a = controlsAlpha();
        hitVoice.setEmpty();
        hitClose.setEmpty();
        hitDur.setEmpty();
        hitBright.setEmpty();
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
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0f);
        if (focus >= 0) {
            RectF fr = focus == 0 ? hitBright : focus == 1 ? hitDur : focus == 2 ? hitVoice : hitClose;
            rect.set(fr);
            rect.inset(focus == 3 ? 6 * s : 2 * s, 6 * s);
            stroke.setColor(alpha(Color.WHITE, (int) (255 * a)));
            stroke.setStrokeWidth(3 * s);
            c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, stroke);
        }
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

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    private static float popScale(long age) {
        if (age >= 600) return 1f;
        float p = age / 600f;
        return (float) (1 + (-0.4 * Math.exp(-6 * p) * Math.cos(9 * p)));
    }
}
