package com.pp.kds.dosa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * "Order Ready" token board for the Dosa TV (Dine-In Dosa orders only).
 *
 *  Left : READY - PLEASE COLLECT. Newest ready token in a big glowing spotlight card, the other
 *         ready tokens as cards that pop in and soften with age. They stay until Dispatch.
 *  Right: PREPARING queue (oldest first) with live progress bars and real-figure minutes.
 *  Foot : Dosa wait, dosas on the tawa, rotating "Did you know".
 *  Call : full-screen token pop-up while the voice calls the numbers.
 *
 * Layout is designed on a 1920 x 1080 grid and scaled; portrait stacks the two sections.
 */
final class TokenBoardView extends View {

    interface Listener {
        void onClose();

        void onDosaLive();

        void onVoice(boolean on);
    }

    private static final int GREEN = 0xFF69F0AE, GREEN_DEEP = 0xFF1B5E20, GREEN_DARK = 0xFF0F3D16, MINT = 0xFFC8FFE0;
    private static final long CALL_MS = 7_000L;
    private static final long CONTROLS_MS = 5_000L, CONTROLS_FADE_MS = 800L;
    private static final float FACT_SECONDS = 9f;

    private final float d;
    private final long start = SystemClock.uptimeMillis();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final RectF dst = new RectF();
    private final Path path = new Path();
    private final android.graphics.Matrix borderMatrix = new android.graphics.Matrix();
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final DateFormat clock;
    private Bitmap logo;
    private Bitmap[] photos;

    private DosaNeonView.Theme theme = DosaNeonView.THEMES[0];
    private Listener listener;
    private boolean voiceOn = true;

    // data
    private List<TokenBoard.Token> ready = new ArrayList<TokenBoard.Token>();
    private List<TokenBoard.Token> preparing = new ArrayList<TokenBoard.Token>();
    private double avgPrep = Double.NaN, perMin;
    private String waitLabel = "\u2014";
    private int onTawa;
    private List<String> facts = new ArrayList<String>();

    // animation state
    private final Map<String, Long> firstSeen = new HashMap<String, Long>();
    private final Map<String, Float> rowY = new HashMap<String, Float>();
    private final Map<String, float[]> cardPos = new HashMap<String, float[]>();
    private static final float READY_PAGE_SECONDS = 7f;
    private int readyPage;
    private long readyPageAt;
    private float[] flowX = new float[0], flowY = new float[0], flowW = new float[0], flowH = new float[0];
    private String spotLabel;
    private long spotAt;
    private List<TokenBoard.Token> call;
    private long callAt;
    private long controlsAt = SystemClock.uptimeMillis();
    private final RectF hitDosa = new RectF(), hitVoice = new RectF(), hitClose = new RectF();

    private static final int SPARKS = 26;
    private final float[] sx = new float[SPARKS], sy = new float[SPARKS], ss = new float[SPARKS], sr = new float[SPARKS];

    TokenBoardView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        clock = android.text.format.DateFormat.getTimeFormat(c);
        try {
            java.io.InputStream in = c.getAssets().open("dosa_live_logo.png");
            logo = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
        } catch (Throwable ignored) {
            logo = null;
        }
        try {
            photos = DosaNeonView.photos(c);
        } catch (Throwable ignored) {
            photos = null;
        }
        Random rnd = new Random(11);
        for (int i = 0; i < SPARKS; i++) {
            sx[i] = rnd.nextFloat();
            sy[i] = rnd.nextFloat();
            ss[i] = 0.02f + rnd.nextFloat() * 0.04f;
            sr[i] = 1.5f + rnd.nextFloat() * 3f;
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setTheme(int index) {
        theme = DosaNeonView.THEMES[Math.max(0, Math.min(DosaNeonView.THEMES.length - 1, index))];
        invalidate();
    }

    void setVoice(boolean on) {
        voiceOn = on;
        invalidate();
    }

    void setData(List<TokenBoard.Token> readyTokens, List<TokenBoard.Token> preparingTokens, DosaStats.Result r) {
        long now = SystemClock.uptimeMillis();
        ready = new ArrayList<TokenBoard.Token>(readyTokens);
        preparing = new ArrayList<TokenBoard.Token>(preparingTokens);
        for (TokenBoard.Token t : ready) if (!firstSeen.containsKey("R" + t.label)) firstSeen.put("R" + t.label, now);
        for (TokenBoard.Token t : preparing) if (!firstSeen.containsKey("P" + t.label)) firstSeen.put("P" + t.label, now);
        // forget tokens that left the screen
        List<String> live = new ArrayList<String>();
        for (TokenBoard.Token t : ready) live.add("R" + t.label);
        for (TokenBoard.Token t : preparing) live.add("P" + t.label);
        firstSeen.keySet().retainAll(live);
        rowY.keySet().retainAll(live);
        List<String> liveCards = new ArrayList<String>();
        for (TokenBoard.Token t : ready) liveCards.add("R" + t.label);
        for (TokenBoard.Token t : preparing) liveCards.add("C" + t.label);
        cardPos.keySet().retainAll(liveCards);
        String top = ready.isEmpty() ? null : ready.get(0).label;
        if (top != null && !top.equals(spotLabel)) spotAt = now;
        spotLabel = top;
        if (r != null) {
            avgPrep = r.avgPrepMin;
            perMin = r.throughputPerMin;
            waitLabel = Double.isNaN(r.etaMin) ? "Calculating\u2026" : r.etaLabel;
            onTawa = r.runningDosas;
            if (r.facts != null && !r.facts.isEmpty()) facts = new ArrayList<String>(r.facts);
        }
        invalidate();
    }

    /** Big pop-up for the tokens being called. */
    void showCall(List<TokenBoard.Token> tokens) {
        call = new ArrayList<TokenBoard.Token>(tokens);
        callAt = SystemClock.uptimeMillis();
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_UP) {
            float x = ev.getX(), y = ev.getY();
            boolean visible = controlsAlpha() > 0.3f;
            if (visible && hitClose.contains(x, y)) {
                if (listener != null) listener.onClose();
            } else if (visible && hitDosa.contains(x, y)) {
                if (listener != null) listener.onDosaLive();
            } else if (visible && hitVoice.contains(x, y)) {
                voiceOn = !voiceOn;
                if (listener != null) listener.onVoice(voiceOn);
            } else if (call != null) {
                call = null; // tap dismisses the pop-up early
            }
            controlsAt = SystemClock.uptimeMillis();
            invalidate();
        }
        return true; // never let taps fall through to the board underneath
    }

    private float controlsAlpha() {
        long since = SystemClock.uptimeMillis() - controlsAt;
        if (since <= CONTROLS_MS) return 1f;
        return Math.max(0f, 1f - (since - CONTROLS_MS) / (float) CONTROLS_FADE_MS);
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    // ---- drawing --------------------------------------------------------------------------

    private float s; // design unit -> px

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        float t = (now - start) / 1000f;
        boolean land = w >= h;
        s = land ? Math.min(w / 1920f, h / 1080f) : Math.min(w / 1080f, h / 1920f);

        drawBackground(c, w, h, t);
        drawBorder(c, w, h, t);
        float pad = 44 * s;
        drawHeader(c, pad, 34 * s, w - 2 * pad, 104 * s, t);

        float footH = 96 * s;
        float footY = h - 30 * s - footH;
        float top = 160 * s;
        if (land) {
            float gap = 26 * s;
            float leftW = (w - 2 * pad - gap) * 0.618f;
            drawReady(c, pad, top, leftW, footY - 24 * s - top, now, t, 4);
            drawPreparing(c, pad + leftW + gap, top, w - 2 * pad - leftW - gap, footY - 24 * s - top, now, t);
        } else {
            float readyH = (footY - top) * 0.52f;
            drawReady(c, pad, top, w - 2 * pad, readyH, now, t, 3);
            drawPreparing(c, pad, top + readyH + 22 * s, w - 2 * pad, footY - 24 * s - top - readyH - 22 * s, now, t);
        }
        drawFooter(c, pad, footY, w - 2 * pad, footH, t);
        drawControls(c, w, t);
        drawCall(c, w, h, now, t);
        postInvalidateOnAnimation();
    }

    private void drawBackground(Canvas c, float w, float h, float t) {
        c.drawColor(theme.bg2);
        int n = photos == null ? 0 : photos.length;
        float tint = 1f;
        if (n > 0) {
            float slide = 12f;
            int k = (int) (t / slide);
            drawPhoto(c, w, h, t, k, n, slide, 255);
            float local = t - k * slide;
            float mix = local > slide - 2f ? (local - slide + 2f) / 2f : 0f;
            if (mix > 0) drawPhoto(c, w, h, t, k + 1, n, slide, (int) (255 * mix));
            tint = DosaNeonView.tintScale(k, k + 1, mix); // same brightness on every photo
        }
        fill.setShader(new RadialGradient(w * 0.3f, h * 0.4f, Math.max(w, h),
                new int[]{alpha(theme.bg0, 0xB0), alpha(theme.bg1, 0xCC), alpha(theme.bg2, 0xEA)},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        fill.setAlpha((int) (255 * tint));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        fill.setAlpha(255);
        // drifting embers
        for (int i = 0; i < SPARKS; i++) {
            float life = (sy[i] + t * ss[i]) % 1f;
            float x = (sx[i] + 0.02f * (float) Math.sin(t * 0.5f + i)) * w;
            float y = h * (1.05f - life * 1.1f);
            float f = (float) Math.sin(Math.PI * life);
            fill.setColor(i % 3 == 0 ? theme.S : theme.P);
            fill.setAlpha((int) (26 * f));
            c.drawCircle(x, y, sr[i] * d * 3f, fill);
            fill.setAlpha((int) (150 * f));
            c.drawCircle(x, y, sr[i] * d, fill);
        }
        fill.setAlpha(255);
    }

    private void drawPhoto(Canvas c, float w, float h, float t, int k, int n, float slide, int a) {
        Bitmap b = photos[k % n];
        float p = Math.max(0f, Math.min(1f, (t - k * slide) / (slide + 2f)));
        float sc = Math.max(w / b.getWidth(), h / b.getHeight()) * (1.05f + 0.1f * p);
        float dw = b.getWidth() * sc, dh = b.getHeight() * sc;
        float x = (w - dw) / 2f + (dw - w) / 2f * (k % 2 == 0 ? 1 : -1) * (p - 0.5f) * 0.8f;
        float y = (h - dh) / 2f;
        dst.set(x, y, x + dw, y + dh);
        bmp.setAlpha(a);
        c.drawBitmap(b, null, dst, bmp);
    }

    private void drawBorder(Canvas c, float w, float h, float t) {
        SweepGradient g = new SweepGradient(w / 2f, h / 2f,
                new int[]{theme.P, theme.L, theme.T, theme.M, theme.R, theme.P}, null);
        borderMatrix.setRotate(t * 12f, w / 2f, h / 2f);
        g.setLocalMatrix(borderMatrix);
        stroke.setShader(g);
        stroke.setStrokeWidth(3 * s);
        rect.set(14 * s, 14 * s, w - 14 * s, h - 14 * s);
        stroke.setAlpha(230);
        c.drawRoundRect(rect, 30 * s, 30 * s, stroke);
        stroke.setShader(null);
        stroke.setAlpha(255);
    }

    private void drawHeader(Canvas c, float x, float y, float w, float h, float t) {
        float cy = y + h / 2f;
        float breathe = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 4.5);
        fill.setColor(theme.P);
        fill.setAlpha((int) (50 + 40 * breathe));
        c.drawCircle(x + h / 2f, cy, h * 0.54f, fill);
        fill.setAlpha(255);
        if (logo != null) {
            dst.set(x + h * 0.03f, y + h * 0.03f, x + h * 0.97f, y + h * 0.97f);
            bmp.setAlpha(255);
            c.drawBitmap(logo, null, dst, bmp);
        }
        float tx = x + h + 22 * s;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setLetterSpacing(0.06f);
        text.setTextSize(44 * s);
        text.setColor(theme.L);
        c.drawText("BABAI TIFFINS", tx, cy + 4 * s, text);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.45f);
        text.setTextSize(17 * s);
        text.setColor(theme.P);
        c.drawText("TASTE THE ANDHRA STYLE", tx, cy + 34 * s, text);

        // title
        boolean land = getWidth() >= getHeight();
        if (land) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(bold);
            text.setLetterSpacing(0.3f);
            text.setTextSize(36 * s);
            text.setColor(Color.WHITE);
            text.setShadowLayer(18 * s, 0, 0, theme.P);
            c.drawText("ORDER READY", x + w * 0.52f, cy + 12 * s, text);
            text.clearShadowLayer();
        }

        // clock + live
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTypeface(bold);
        text.setLetterSpacing(0f);
        text.setTextSize(44 * s);
        text.setColor(Color.WHITE);
        c.drawText(clock.format(new Date()), x + w, cy + 4 * s, text);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.3f);
        text.setTextSize(16 * s);
        text.setColor(GREEN);
        c.drawText("LIVE", x + w, cy + 32 * s, text);
        float lw = text.measureText("LIVE");
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 1.6);
        fill.setColor(GREEN);
        fill.setAlpha((int) (120 + 135 * pulse));
        c.drawCircle(x + w - lw - 14 * s, cy + 26 * s, 6 * s, fill);
        fill.setAlpha(255);
        text.setLetterSpacing(0f);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ---- READY ---------------------------------------------------------------------------

    private void drawReady(Canvas c, float x, float y, float w, float h, long now, float t, int maxCols) {
        int others = Math.max(0, ready.size() - 1);
        float top = y + 52 * s;
        float spotFull = Math.min(330 * s, h * 0.46f);
        float spotH = others <= 8 ? spotFull : others <= 16 ? Math.min(250 * s, h * 0.36f) : Math.min(200 * s, h * 0.28f);
        float gy = top + spotH + 18 * s, gh = y + h - gy;
        float gap = 14 * s;

        // Auto-size: the column count that gives the biggest cards while fitting every token.
        int cols = 1;
        float ch = 0;
        for (int k = 1; k <= Math.max(1, Math.min(others, 12)); k++) {
            int rows = (others + k - 1) / k;
            float cw = (w - (k - 1) * gap) / k;
            float hh = Math.min(Math.min((gh - (rows - 1) * gap) / Math.max(rows, 1), 150 * s), cw * 0.72f);
            if (hh > ch) {
                ch = hh;
                cols = k;
            }
        }
        int perPage = Math.max(others, 1), pages = 1;
        float minH = 58 * s;
        if (others > 0 && ch < minH) { // too many even for small cards: page through them
            ch = minH;
            cols = Math.max(1, (int) ((w + gap) / (ch * 1.45f + gap)));
            int rows = Math.max(1, (int) ((gh + gap) / (ch + gap)));
            perPage = cols * rows;
            pages = (others + perPage - 1) / perPage;
        }
        int page = pages > 1 ? (int) (t / READY_PAGE_SECONDS) % pages : 0;
        if (page != readyPage) {
            readyPage = page;
            readyPageAt = now;
            cardPos.clear();
        }
        String count = ready.isEmpty() ? null : (ready.size() == 1 ? "1 ready" : ready.size() + " ready");
        if (count != null && pages > 1) count += "  \u00B7  " + (page + 1) + "/" + pages;
        sectionTitle(c, x, y + 26 * s, w, "\u2713  READY \u2014 PLEASE COLLECT", GREEN, count);
        drawSpotlight(c, x, top, w, spotH, now, t);
        if (others == 0) return;

        float cw = (w - (cols - 1) * gap) / cols;
        int from = page * perPage, to = Math.min(others, from + perPage);
        for (int i = from; i < to; i++) {
            int j = i - from;
            TokenBoard.Token tk = ready.get(i + 1);
            float tx = x + (j % cols) * (cw + gap);
            float ty = gy + (j / cols) * (ch + gap);
            String key = "R" + tk.label;
            float[] p = cardPos.get(key);
            if (p == null) {
                p = new float[]{tx, ty + ch * 0.6f};
                cardPos.put(key, p);
            }
            p[0] += (tx - p[0]) * 0.14f;
            p[1] += (ty - p[1]) * 0.14f;
            // staggered fade-in when a page (or the layout) changes
            float in = Math.max(0f, Math.min(1f, (now - readyPageAt - j * 45) / 400f));
            drawReadyCard(c, p[0], p[1], cw, ch, tk, now, t, in);
        }
    }

    private void drawSpotlight(Canvas c, float x, float y, float w, float h, long now, float t) {
        float r = 30 * s;
        if (ready.isEmpty()) {
            panel(c, x, y, w, h, r, alpha(theme.surface, 0xCC), alpha(theme.P, 0x66));
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(medium);
            text.setTextSize(44 * s);
            text.setColor(theme.L);
            c.drawText("Fresh dosas on the tawa", x + w / 2f, y + h * 0.45f, text);
            text.setTypeface(light);
            text.setTextSize(26 * s);
            text.setColor(alpha(theme.L, 0xCC));
            c.drawText("Your token number will appear here when it\u2019s ready", x + w / 2f, y + h * 0.66f, text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        TokenBoard.Token tk = ready.get(0);
        float pop = popScale(now - spotAt);
        c.save();
        c.scale(pop, pop, x + w / 2f, y + h / 2f);
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 2.2);
        // outer glow rings
        for (int i = 3; i >= 1; i--) {
            rect.set(x - i * 7 * s, y - i * 7 * s, x + w + i * 7 * s, y + h + i * 7 * s);
            fill.setColor(GREEN);
            fill.setAlpha((int) ((14 + 16 * pulse) / i));
            c.drawRoundRect(rect, r + i * 7 * s, r + i * 7 * s, fill);
        }
        rect.set(x, y, x + w, y + h);
        fill.setShader(new LinearGradient(x, y, x + w, y + h, 0xEE1B5E20, 0xF0103416, Shader.TileMode.CLAMP));
        fill.setAlpha(255);
        c.drawRoundRect(rect, r, r, fill);
        fill.setShader(null);
        // shimmer sweep
        c.save();
        path.reset();
        path.addRoundRect(rect, r, r, Path.Direction.CW);
        c.clipPath(path);
        float sweep = ((t % 3.2f) / 3.2f) * (w + h) * 1.6f - h;
        fill.setShader(new LinearGradient(x + sweep, y, x + sweep + h * 0.6f, y + h,
                new int[]{0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawRect(rect, fill);
        fill.setShader(null);
        c.restore();
        stroke.setColor(GREEN);
        stroke.setStrokeWidth(3 * s);
        stroke.setAlpha(200 + (int) (55 * pulse));
        c.drawRoundRect(rect, r, r, stroke);
        stroke.setAlpha(255);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.3f);
        text.setTextSize(20 * s);
        text.setColor(MINT);
        c.drawText("JUST READY", x + 48 * s, y + 46 * s, text);
        text.setLetterSpacing(0f);

        // number
        float bellR = Math.min(75 * s, h * 0.23f);
        text.setTypeface(bold);
        float numSize = Math.min(220 * s, h * 0.62f);
        text.setTextSize(numSize);
        float maxNum = w * 0.42f;
        float nw = text.measureText(tk.label);
        if (nw > maxNum) {
            text.setTextSize(numSize * maxNum / nw);
            nw = maxNum;
        }
        text.setColor(Color.WHITE);
        text.setShadowLayer(30 * s, 0, 0, GREEN);
        float base = y + h * 0.5f + text.getTextSize() * 0.36f + 12 * s;
        c.drawText(tk.label, x + 48 * s, base, text);
        text.clearShadowLayer();

        // info column
        float ix = x + 48 * s + nw + 46 * s;
        float iw = x + w - 2 * bellR - 60 * s - ix;
        float iy = y + h * 0.36f;
        pill(c, ix, iy - 22 * s, "DINE-IN", 22 * s, 0x24FFFFFF, Color.WHITE);
        text.setTypeface(bold);
        text.setTextSize(fit("Your dosa is ready!", 46 * s, iw));
        text.setColor(Color.WHITE);
        c.drawText("Your dosa is ready!", ix, iy + 70 * s, text);
        text.setTypeface(medium);
        text.setTextSize(fit("Please collect at the counter", 24 * s, iw));
        text.setColor(MINT);
        c.drawText("Please collect at the counter", ix, iy + 108 * s, text);
        text.setTextSize(20 * s);
        text.setColor(alpha(MINT, 0xBB));
        c.drawText(ago(tk.readyAt), ix, iy + 140 * s, text);

        // bell with ripples
        float bx = x + w - bellR - 40 * s, by = y + h / 2f;
        for (int i = 0; i < 3; i++) {
            float ph = ((t / 2.4f) + i / 3f) % 1f;
            stroke.setColor(GREEN);
            stroke.setStrokeWidth(3 * s);
            stroke.setAlpha((int) (170 * (1 - ph)));
            c.drawCircle(bx, by, bellR * (1f + ph * 0.7f), stroke);
        }
        fill.setColor(0xFF0E3A16);
        c.drawCircle(bx, by, bellR, fill);
        stroke.setAlpha(255);
        stroke.setStrokeWidth(5 * s);
        c.drawCircle(bx, by, bellR, stroke);
        c.save();
        c.rotate(14f * (float) Math.sin(t * 2 * Math.PI / 1.2) * (float) Math.max(0, Math.sin(t * 2 * Math.PI / 4.8)), bx, by - bellR * 0.4f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(bellR * 0.9f);
        c.drawText("\uD83D\uDD14", bx, by + bellR * 0.32f, text);
        c.restore();
        text.setTextAlign(Paint.Align.LEFT);
        c.restore();
    }

    private void drawReadyCard(Canvas c, float x, float y, float w, float h, TokenBoard.Token tk, long now, float t,
                               float in) {
        Long seen = firstSeen.get("R" + tk.label);
        long age = seen == null ? 10_000 : now - seen;
        float pop = popScale(age);
        float mins = (System.currentTimeMillis() - tk.readyAt) / 60_000f;
        int a = (int) ((mins < 3 ? 255 : mins < 6 ? 215 : 170) * in);
        if (a <= 0) return;
        c.save();
        c.scale(pop, pop, x + w / 2f, y + h / 2f);
        float r = Math.min(22 * s, h * 0.2f);
        rect.set(x, y, x + w, y + h);
        fill.setColor(0xD8142816);
        fill.setAlpha(Math.min(255, a));
        c.drawRoundRect(rect, r, r, fill);
        fill.setAlpha(255);
        float flash = age < 1500 ? 1f - age / 1500f : 0f;
        stroke.setColor(GREEN);
        stroke.setStrokeWidth((2 + 4 * flash) * s);
        stroke.setAlpha((int) (a * 0.6f + 100 * flash * in));
        c.drawRoundRect(rect, r, r, stroke);
        stroke.setAlpha(255);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        String agoText = ago(tk.readyAt);
        if (h >= 120 * s) {
            // roomy card: number on top, DINE-IN badge + time below
            text.setTextSize(fit(tk.label, Math.min(76 * s, h * 0.5f), w - 40 * s));
            text.setColor(alpha(Color.WHITE, a));
            c.drawText(tk.label, x + 20 * s, y + h * 0.5f + 16 * s, text);
            float bw = pill(c, x + 18 * s, y + h - 46 * s, "DINE-IN", 15 * s, alpha(0xFFFFFFFF, (int) (0x1F * in)), alpha(MINT, a));
            text.setTypeface(medium);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTextSize(18 * s);
            text.setColor(alpha(MINT, a));
            if (x + 18 * s + bw + 10 * s + text.measureText(agoText) < x + w - 18 * s) {
                c.drawText(agoText, x + w - 18 * s, y + h - 26 * s, text);
            }
        } else {
            // compact card: big centred number, minutes underneath when there is room
            boolean showAgo = h >= 78 * s;
            float numSize = fit(tk.label, h * (showAgo ? 0.5f : 0.62f), w - 20 * s);
            text.setTextSize(numSize);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(alpha(Color.WHITE, a));
            float base = showAgo ? y + h * 0.56f : y + h / 2f + numSize * 0.36f;
            c.drawText(tk.label, x + w / 2f, base, text);
            if (showAgo) {
                text.setTypeface(medium);
                text.setTextSize(fit(agoText, Math.min(16 * s, h * 0.18f), w - 16 * s));
                text.setColor(alpha(MINT, a));
                c.drawText(agoText, x + w / 2f, y + h * 0.84f, text);
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
        c.restore();
    }

    // ---- PREPARING -----------------------------------------------------------------------

    private static final int ROWS = 10;
    private static final String[] KIND_WORDS = {
            "\u2764 Made fresh just for you \u2014 almost there!",
            "\u2764 Extra crisp on its way \u2014 thank you for waiting",
            "\u2764 Worth the wait \u2014 coming right up",
            "\u2764 Your dosa is getting a little extra love",
    };
    private static final String BIG_ORDER = "\u2764 Bigger order, more dosas on the tawa \u2014 nearly done!";

    private void drawPreparing(Canvas c, float x, float y, float w, float h, long now, float t) {
        panel(c, x, y, w, h, 26 * s, alpha(0xFF140F08, 0xC0), alpha(Color.WHITE, 0x22));
        float ix = x + 24 * s, iw = w - 48 * s;
        sectionTitle(c, ix, y + 50 * s, iw, "\uD83D\uDD25  PREPARING", theme.P,
                preparing.isEmpty() ? null : preparing.size() + " in queue");
        float top = y + 76 * s, bottom = y + h - 16 * s;
        if (preparing.isEmpty()) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(light);
            text.setTextSize(28 * s);
            text.setColor(alpha(theme.L, 0xDD));
            c.drawText("All caught up \u2014 every dosa is made fresh to order", x + w / 2f, (top + bottom) / 2f, text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }
        int n = preparing.size();
        boolean flow = n > ROWS;
        int shown = Math.min(n, ROWS);
        float rowsArea = flow ? (bottom - top) * 0.68f : bottom - top;
        float rowH = Math.min(86 * s, rowsArea / (flow ? ROWS : Math.max(shown, 4)));
        long wall = System.currentTimeMillis();
        for (int i = 0; i < shown; i++) {
            TokenBoard.Token tk = preparing.get(i);
            String key = "P" + tk.label;
            float target = top + i * rowH;
            Float cur = rowY.get(key);
            float yy = cur == null ? bottom : cur + (target - cur) * 0.12f;
            if (Math.abs(yy - target) < 0.5f) yy = target;
            rowY.put(key, yy);
            Long seen = firstSeen.get(key);
            float appear = seen == null ? 1f : Math.min(1f, (now - seen) / 600f);
            double elapsed = tk.createdMs > 0 ? (wall - tk.createdMs) / 60_000.0 : 0;
            double left = TokenBoard.minutesLeft(i, elapsed, avgPrep, perMin);
            drawRow(c, ix, yy, iw, rowH, tk, elapsed, left, i == 0, appear, t);
        }
        if (flow) drawQueueFlow(c, ix, top + ROWS * rowH + 12 * s, iw, bottom - (top + ROWS * rowH + 12 * s), now, t);
    }

    /**
     * Tokens after the first 10: cards flowing left-to-right, large to small, one after another.
     * When they don't all fit the area scrolls up slowly in a loop, so 50+ tokens stay visible.
     */
    private void drawQueueFlow(Canvas c, float x, float y, float w, float h, long now, float t) {
        int rest = preparing.size() - ROWS;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.2f);
        text.setTextSize(15 * s);
        text.setColor(alpha(theme.L, 0xBB));
        c.drawText("NEXT IN LINE  \u00B7  " + rest + (rest == 1 ? " MORE" : " MORE"), x, y + 16 * s, text);
        text.setLetterSpacing(0f);
        float areaTop = y + 26 * s, areaH = h - 26 * s;
        if (areaH <= 10 * s) return;

        // layout in content coordinates
        float gap = 8 * s;
        int m = rest;
        if (flowX.length < m) {
            flowX = new float[m * 2];
            flowY = new float[m * 2];
            flowW = new float[m * 2];
            flowH = new float[m * 2];
        }
        float cx = 0, cy = 0, lineH = 0;
        for (int j = 0; j < m; j++) {
            float chh = (58 - 26 * Math.min(1f, j / 30f)) * s; // large -> small
            TokenBoard.Token tk = preparing.get(ROWS + j);
            text.setTypeface(bold);
            text.setTextSize(chh * 0.55f);
            float cww = Math.max(chh * 1.5f, text.measureText(tk.label) + chh * 0.8f);
            if (cx > 0 && cx + cww > w) {
                cx = 0;
                cy += lineH + gap;
                lineH = 0;
            }
            flowX[j] = cx;
            flowY[j] = cy;
            flowW[j] = cww;
            flowH[j] = chh;
            cx += cww + gap;
            lineH = Math.max(lineH, chh);
        }
        float contentH = cy + lineH;
        boolean scroll = contentH > areaH;
        float loop = contentH + 40 * s;
        float off = scroll ? (t * 16 * s) % loop : 0;

        c.save();
        c.clipRect(x - 4 * s, areaTop, x + w + 4 * s, areaTop + areaH);
        for (int copy = 0; copy < (scroll ? 2 : 1); copy++) {
            float base = areaTop - off + copy * loop;
            for (int j = 0; j < m; j++) {
                float yy = base + flowY[j];
                if (yy > areaTop + areaH || yy + flowH[j] < areaTop) continue;
                TokenBoard.Token tk = preparing.get(ROWS + j);
                String key = "C" + tk.label;
                float[] p = cardPos.get(key);
                if (p == null) {
                    p = new float[]{flowX[j], flowY[j] + 30 * s};
                    cardPos.put(key, p);
                }
                if (copy == 0) {
                    p[0] += (flowX[j] - p[0]) * 0.12f;
                    p[1] += (flowY[j] - p[1]) * 0.12f;
                }
                Long seen = firstSeen.get("P" + tk.label);
                float appear = seen == null ? 1f : Math.min(1f, (now - seen) / 600f);
                float fade = 1f - 0.45f * Math.min(1f, j / 40f);
                drawChip(c, x + p[0], base + p[1], flowW[j], flowH[j], tk.label, appear * fade, popScale(seen == null ? 10_000 : now - seen));
            }
        }
        c.restore();
    }

    private void drawChip(Canvas c, float x, float y, float w, float h, String label, float a, float pop) {
        int ia = (int) (255 * a);
        c.save();
        c.scale(pop, pop, x + w / 2f, y + h / 2f);
        rect.set(x, y, x + w, y + h);
        fill.setShader(null);
        fill.setColor(alpha(theme.surface, (int) (0xE0 * a)));
        c.drawRoundRect(rect, h * 0.3f, h * 0.3f, fill);
        stroke.setShader(null);
        stroke.setColor(alpha(theme.P, (int) (0xAA * a)));
        stroke.setStrokeWidth(1.5f * s);
        c.drawRoundRect(rect, h * 0.3f, h * 0.3f, stroke);
        stroke.setAlpha(255);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(bold);
        text.setTextSize(h * 0.55f);
        text.setColor(alpha(Color.WHITE, ia));
        c.drawText(label, x + w / 2f, y + h / 2f + h * 0.2f, text);
        text.setTextAlign(Paint.Align.LEFT);
        fill.setAlpha(255);
        c.restore();
    }

    private void drawRow(Canvas c, float x, float y, float w, float h, TokenBoard.Token tk, double elapsed,
                         double left, boolean next, float appear, float t) {
        int a = (int) (255 * appear);
        if (next) {
            rect.set(x - 10 * s, y + 3 * s, x + w + 10 * s, y + h - 3 * s);
            fill.setColor(alpha(GREEN, (int) (22 + 14 * Math.sin(t * 2 * Math.PI / 1.8))));
            c.drawRoundRect(rect, 14 * s, 14 * s, fill);
        }
        float numW = 130 * s;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setTextSize(fit(tk.label, Math.min(46 * s, h * 0.62f), numW - 10 * s));
        text.setColor(alpha(Color.WHITE, a));
        c.drawText(tk.label, x, y + h * 0.5f + text.getTextSize() * 0.36f, text);

        float etaW = 110 * s;
        float bx = x + numW, bw = w - numW - etaW - 12 * s;
        text.setTypeface(condensed);
        text.setLetterSpacing(0.15f);
        text.setTextSize(Math.min(15 * s, h * 0.22f));
        text.setColor(alpha(0xFFCBBF9F, a));
        String sub = "DINE-IN \u00B7 " + (tk.dosas == 1 ? "1 DOSA" : tk.dosas + " DOSAS") + (next ? "  \u00B7  NEXT UP" : "");
        if (tk.overtaken) {
            // A newer order came out first: a warm word instead of the plain details.
            int k = (int) (t / 6f) + Math.abs(tk.label.hashCode());
            sub = tk.dosas > 1 && k % 3 == 0 ? BIG_ORDER : KIND_WORDS[k % KIND_WORDS.length];
            text.setLetterSpacing(0.02f);
            text.setTypeface(medium);
            text.setTextSize(fit(sub, Math.min(17 * s, h * 0.26f), bw));
            text.setColor(alpha(theme.T, a));
        }
        c.drawText(sub, bx, y + h * 0.42f, text);
        text.setLetterSpacing(0f);

        float barY = y + h * 0.56f, barH = Math.max(6 * s, Math.min(14 * s, h * 0.17f));
        rect.set(bx, barY, bx + bw, barY + barH);
        fill.setColor(alpha(Color.WHITE, (int) (20 * appear)));
        c.drawRoundRect(rect, barH / 2f, barH / 2f, fill);
        float p;
        if (Double.isNaN(left)) {
            p = 0.5f + 0.5f * (float) Math.sin(t * 2.0); // no real figure yet: gentle pulse
        } else {
            p = (float) Math.max(0.04, Math.min(0.98, elapsed / (elapsed + left)));
        }
        float fx = bx + bw * p;
        boolean hot = p > 0.7f;
        int c0 = hot ? theme.S : 0xFFFF8A00, c1 = hot ? GREEN : 0xFFFFE082;
        rect.set(bx, barY, Math.max(bx + barH, fx), barY + barH);
        fill.setShader(new LinearGradient(bx, 0, fx, 0, alpha(c0, a), alpha(c1, a), Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, barH / 2f, barH / 2f, fill);
        fill.setShader(null);
        // travelling sparkle on the bar
        float sp = (t * 0.6f + tk.label.hashCode() * 0.013f) % 1f;
        float sxp = bx + (fx - bx) * sp;
        fill.setColor(alpha(Color.WHITE, (int) (150 * appear)));
        c.drawCircle(sxp, barY + barH / 2f, barH * 0.45f, fill);

        text.setTextAlign(Paint.Align.RIGHT);
        text.setTypeface(bold);
        text.setTextSize(Math.min(24 * s, h * 0.34f));
        String eta;
        if (Double.isNaN(left)) {
            eta = "on tawa";
            text.setColor(alpha(theme.S, a));
        } else {
            long m = Math.max(1, Math.round(left));
            eta = "~" + m + " min";
            text.setColor(alpha(m <= 2 ? GREEN : theme.S, a));
        }
        c.drawText(eta, x + w, y + h * 0.5f + text.getTextSize() * 0.36f, text);
        text.setTextAlign(Paint.Align.LEFT);
        stroke.setColor(alpha(Color.WHITE, (int) (16 * appear)));
        stroke.setStrokeWidth(1 * s);
        c.drawLine(x, y + h, x + w, y + h, stroke);
        stroke.setAlpha(255);
    }

    // ---- footer, controls, call ----------------------------------------------------------

    private void drawFooter(Canvas c, float x, float y, float w, float h, float t) {
        float gap = 18 * s;
        float w1 = 300 * s, w2 = 240 * s;
        panel(c, x, y, w1, h, 20 * s, alpha(0xFF140F08, 0xC8), alpha(theme.P, 0x77));
        tile(c, x + 22 * s, y, h, "DOSA WAIT", waitLabel, theme.P);
        panel(c, x + w1 + gap, y, w2, h, 20 * s, alpha(0xFF140F08, 0xC8), alpha(theme.P, 0x77));
        tile(c, x + w1 + gap + 22 * s, y, h, "ON THE TAWA", onTawa == 1 ? "1 dosa" : onTawa + " dosas", theme.P);
        float fx = x + w1 + w2 + 2 * gap, fw = w - w1 - w2 - 2 * gap;
        panel(c, fx, y, fw, h, 20 * s, alpha(0xFF140F08, 0xC8), alpha(theme.T, 0x88));
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.2f);
        text.setTextSize(16 * s);
        text.setColor(theme.T);
        c.drawText("\u2726 DID YOU KNOW", fx + 22 * s, y + 34 * s, text);
        text.setLetterSpacing(0f);
        if (!facts.isEmpty()) {
            int k = (int) (t / FACT_SECONDS);
            float ph = t - k * FACT_SECONDS;
            float a = Math.max(0f, Math.min(1f, Math.min(ph / 0.8f, (FACT_SECONDS - ph) / 0.8f)));
            String f = facts.get(k % facts.size());
            text.setTypeface(medium);
            text.setTextSize(fit(f, 26 * s, fw - 44 * s));
            text.setColor(alpha(Color.WHITE, (int) (255 * a)));
            c.drawText(f, fx + 22 * s, y + 72 * s, text);
        }
    }

    private void tile(Canvas c, float x, float y, float h, String label, String value, int color) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.2f);
        text.setTextSize(16 * s);
        text.setColor(color);
        c.drawText(label, x, y + 34 * s, text);
        text.setLetterSpacing(0f);
        text.setTypeface(bold);
        text.setTextSize(fit(value, 38 * s, 256 * s));
        text.setColor(Color.WHITE);
        c.drawText(value, x, y + 76 * s, text);
    }

    private void drawControls(Canvas c, float w, float t) {
        float a = controlsAlpha();
        hitDosa.setEmpty();
        hitVoice.setEmpty();
        hitClose.setEmpty();
        if (a <= 0f) return;
        float h = 40 * s, y = 86 * s - h / 2f;
        text.setTypeface(bold);
        text.setTextSize(44 * s);
        float right = w - 44 * s - text.measureText(clock.format(new Date())) - 28 * s;
        float bx = right - h;
        rect.set(bx, y, right, y + h);
        hitClose.set(rect);
        hitClose.inset(-10 * s, -10 * s);
        fill.setColor(alpha(Color.WHITE, (int) (0x33 * a)));
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        stroke.setColor(alpha(Color.WHITE, (int) (220 * a)));
        stroke.setStrokeWidth(2.5f * s);
        float k = h * 0.2f, cx = bx + h / 2f, cy = y + h / 2f;
        c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke);
        c.drawLine(cx - k, cy + k, cx + k, cy - k, stroke);
        stroke.setAlpha(255);
        String v = voiceOn ? "\uD83D\uDD0A  VOICE ON" : "\uD83D\uDD07  VOICE OFF";
        float vw = button(c, bx - 12 * s, y, h, v, voiceOn ? 0xFF2E7D32 : 0xFF5D4037, a, hitVoice);
        button(c, bx - 12 * s - vw - 12 * s, y, h, "\u2726  DOSA LIVE", 0xFFE65100, a, hitDosa);
    }

    /** Right-aligned pill button ending at {@code right}; returns its width. */
    private float button(Canvas c, float right, float y, float h, String label, int color, float a, RectF hit) {
        text.setTypeface(condensed);
        text.setLetterSpacing(0.1f);
        text.setTextSize(17 * s);
        float bw = text.measureText(label) + 36 * s;
        rect.set(right - bw, y, right, y + h);
        hit.set(rect);
        hit.inset(-6 * s, -10 * s);
        fill.setColor(alpha(color, (int) (235 * a)));
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        stroke.setColor(alpha(Color.WHITE, (int) (170 * a)));
        stroke.setStrokeWidth(1.5f * s);
        c.drawRoundRect(rect, h / 2f, h / 2f, stroke);
        stroke.setAlpha(255);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(alpha(Color.WHITE, (int) (255 * a)));
        c.drawText(label, rect.centerX(), y + h / 2f + 6 * s, text);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0f);
        return bw;
    }

    private void drawCall(Canvas c, float w, float h, long now, float t) {
        if (call == null) return;
        long age = now - callAt;
        if (age > CALL_MS) {
            call = null;
            return;
        }
        float in = Math.min(1f, age / 350f), out = Math.min(1f, (CALL_MS - age) / 450f);
        float vis = Math.min(in, out);
        fill.setShader(new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.7f,
                new int[]{0xB8081E0C, 0xDD000000}, null, Shader.TileMode.CLAMP));
        fill.setAlpha((int) (255 * vis));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        fill.setAlpha(255);

        boolean land = w >= h;
        float cw = land ? Math.min(1240 * s, w * 0.72f) : w * 0.88f;
        float ch = land ? Math.min(660 * s, h * 0.66f) : Math.min(820 * s, h * 0.5f);
        float x = (w - cw) / 2f, y = (h - ch) / 2f;
        float sc = popScale(age) * (0.9f + 0.1f * out);
        c.save();
        c.scale(sc, sc, w / 2f, h / 2f);
        float r = 44 * s;
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 1.1);
        for (int i = 0; i < 3; i++) {
            float ph = ((age / 1600f) + i / 3f) % 1f;
            float grow = ph * 70 * s;
            rect.set(x - grow, y - grow, x + cw + grow, y + ch + grow);
            stroke.setColor(GREEN);
            stroke.setStrokeWidth(4 * s);
            stroke.setAlpha((int) (150 * (1 - ph) * vis));
            c.drawRoundRect(rect, r + grow, r + grow, stroke);
        }
        rect.set(x, y, x + cw, y + ch);
        fill.setShader(new LinearGradient(x, y, x + cw, y + ch, GREEN_DEEP, GREEN_DARK, Shader.TileMode.CLAMP));
        fill.setAlpha((int) (255 * vis));
        c.drawRoundRect(rect, r, r, fill);
        fill.setShader(null);
        stroke.setColor(GREEN);
        stroke.setStrokeWidth(5 * s);
        stroke.setAlpha((int) ((200 + 55 * pulse) * vis));
        c.drawRoundRect(rect, r, r, stroke);
        stroke.setAlpha(255);
        // orbiting sparkles
        for (int i = 0; i < 10; i++) {
            double ang = t * 0.9 + i * Math.PI / 5;
            float px = (float) (w / 2f + Math.cos(ang) * cw * 0.47f);
            float py = (float) (h / 2f + Math.sin(ang) * ch * 0.44f);
            fill.setColor(0xFFFFE082);
            fill.setAlpha((int) (200 * vis * (0.5f + 0.5f * Math.sin(t * 5 + i))));
            c.drawCircle(px, py, 5 * s, fill);
        }
        fill.setAlpha(255);

        int ta = (int) (255 * vis);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.5f);
        text.setTextSize(34 * s);
        text.setColor(alpha(MINT, ta));
        c.drawText(call.size() == 1 ? "TOKEN NUMBER" : "TOKEN NUMBERS", w / 2f, y + ch * 0.16f, text);
        text.setLetterSpacing(0f);

        StringBuilder sb = new StringBuilder();
        for (TokenBoard.Token tk : call) sb.append(sb.length() == 0 ? "" : "  \u00B7  ").append(tk.label);
        String nums = sb.toString();
        text.setTypeface(bold);
        float size = fit(nums, 300 * s, cw - 100 * s);
        size = Math.min(size, ch * 0.42f);
        text.setTextSize(size);
        text.setColor(alpha(Color.WHITE, ta));
        text.setShadowLayer(40 * s, 0, 0, GREEN);
        c.drawText(nums, w / 2f, y + ch * 0.36f + size * 0.36f, text);
        text.clearShadowLayer();

        text.setTextSize(fit("DOSA READY \u2014 KINDLY COLLECT", 58 * s, cw - 100 * s));
        text.setColor(alpha(Color.WHITE, ta));
        c.drawText("DOSA READY \u2014 KINDLY COLLECT", w / 2f, y + ch * 0.80f, text);
        text.setTypeface(medium);
        text.setTextSize(fit("Dine-in \u00B7 Thank you for choosing Babai Tiffins", 28 * s, cw - 100 * s));
        text.setColor(alpha(MINT, ta));
        c.drawText("Dine-in \u00B7 Thank you for choosing Babai Tiffins", w / 2f, y + ch * 0.91f, text);

        if (voiceOn) {
            String v = "\uD83D\uDD0A  Calling now\u2026";
            text.setTypeface(medium);
            text.setTextSize(26 * s);
            float vw = text.measureText(v) + 44 * s;
            rect.set(w / 2f - vw / 2f, y - 26 * s, w / 2f + vw / 2f, y + 26 * s);
            fill.setColor(alpha(GREEN_DARK, ta));
            c.drawRoundRect(rect, 26 * s, 26 * s, fill);
            stroke.setColor(alpha(GREEN, ta));
            stroke.setStrokeWidth(2 * s);
            c.drawRoundRect(rect, 26 * s, 26 * s, stroke);
            stroke.setAlpha(255);
            text.setColor(alpha(Color.WHITE, ta));
            c.drawText(v, w / 2f, y + 9 * s, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        c.restore();
    }

    // ---- helpers -------------------------------------------------------------------------

    private void sectionTitle(Canvas c, float x, float baseY, float w, String title, int color, String count) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setLetterSpacing(0.22f);
        text.setTextSize(26 * s);
        text.setColor(color);
        text.setShadowLayer(14 * s, 0, 0, alpha(color, 0x99));
        c.drawText(title, x, baseY, text);
        text.clearShadowLayer();
        text.setLetterSpacing(0f);
        if (count != null) {
            text.setTypeface(bold);
            text.setLetterSpacing(0.08f);
            text.setTextSize(20 * s);
            float cw = text.measureText(count) + 30 * s;
            rect.set(x + w - cw, baseY - 26 * s, x + w, baseY + 8 * s);
            fill.setColor(alpha(Color.WHITE, 0x18));
            c.drawRoundRect(rect, 17 * s, 17 * s, fill);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(color);
            c.drawText(count, rect.centerX(), baseY - 2 * s, text);
            text.setTextAlign(Paint.Align.LEFT);
            text.setLetterSpacing(0f);
        }
    }

    private void panel(Canvas c, float x, float y, float w, float h, float r, int bg, int border) {
        rect.set(x, y, x + w, y + h);
        fill.setShader(null);
        fill.setColor(bg);
        c.drawRoundRect(rect, r, r, fill);
        stroke.setShader(null);
        stroke.setColor(border);
        stroke.setStrokeWidth(1.5f * s);
        c.drawRoundRect(rect, r, r, stroke);
        stroke.setAlpha(255);
        fill.setAlpha(255);
    }

    /** Small rounded label; returns its width. */
    private float pill(Canvas c, float x, float y, String label, float size, int bg, int fg) {
        text.setTypeface(bold);
        text.setLetterSpacing(0.15f);
        text.setTextSize(size);
        float pw = text.measureText(label) + size * 1.4f;
        float ph = size * 1.7f;
        rect.set(x, y, x + pw, y + ph);
        fill.setColor(bg);
        c.drawRoundRect(rect, ph / 2f, ph / 2f, fill);
        fill.setAlpha(255);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(fg);
        c.drawText(label, rect.centerX(), y + ph / 2f + size * 0.36f, text);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0f);
        return pw;
    }

    private float fit(String s0, float size, float maxW) {
        text.setTextSize(size);
        float tw = text.measureText(s0);
        return tw > maxW ? size * maxW / tw : size;
    }

    /** Springy pop-in: 0.6 -> 1.06 -> 1.0 over ~600 ms. */
    private static float popScale(long age) {
        if (age >= 600) return 1f;
        float p = age / 600f;
        return (float) (1 + (-0.4 * Math.exp(-6 * p) * Math.cos(9 * p)));
    }

    private static String ago(long readyAt) {
        long m = (System.currentTimeMillis() - readyAt) / 60_000L;
        if (m < 1) return "ready just now";
        return "ready " + m + " min ago";
    }
}
