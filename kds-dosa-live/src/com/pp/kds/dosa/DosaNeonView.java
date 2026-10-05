package com.pp.kds.dosa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
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
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Customer-facing "Dosa Live" screen in Babai Tiffins colours: rotating neon border, breathing
 * wait ring, rising tawa embers, comfort messages, live stat cards, rotating facts / guest love
 * and a wait-time trend chart. Drawn on one Canvas, animated while visible.
 *
 * Staff details (exact numbers, 7-day table) open only with a 1.5 s long-press on the title.
 */
final class DosaNeonView extends View {

    /** Selectable looks. Order = dots left to right. */
    static final class Theme {
        final String name;
        final int P, S, T, L, M, R;     // primary, secondary, third, light, mid, review accent
        final int bg0, bg1, bg2, surface;

        Theme(String name, int p, int s, int t, int l, int m, int r, int bg0, int bg1, int bg2, int surface) {
            this.name = name;
            P = p; S = s; T = t; L = l; M = m; R = r;
            this.bg0 = bg0; this.bg1 = bg1; this.bg2 = bg2; this.surface = surface;
        }
    }

    static final Theme[] THEMES = {
            // From the Babai Tiffins logo: turmeric-mustard circle, green angavastram, charcoal
            new Theme("Babai Classic", 0xFFF5B21B, 0xFF8BCB8E, 0xFFF2C98A, 0xFFFFF4DC, 0xFFFFD54F, 0xFFFF6E40,
                    0xFF2E2408, 0xFF16110A, 0xFF080604, 0xFF1C170B),
            // Guntur chilli red-orange
            new Theme("Guntur Chilli", 0xFFFF5200, 0xFFFFC107, 0xFF66BB6A, 0xFFFFE8C7, 0xFFFF8A00, 0xFFFF3D00,
                    0xFF3A1606, 0xFF1E0B04, 0xFF0A0402, 0xFF1F0E06),
            // Banana leaf green with turmeric
            new Theme("Banana Leaf", 0xFF7CB342, 0xFFFFD54F, 0xFFFF8A65, 0xFFE8F5E9, 0xFFAED581, 0xFFFF7043,
                    0xFF12301A, 0xFF0A1A0E, 0xFF030805, 0xFF0F2214),
            // Temple-festival night neon
            new Theme("Festival Neon", 0xFF00E5FF, 0xFFFF2BD6, 0xFFFFB300, 0xFFE0F7FA, 0xFF7C4DFF, 0xFFFF2BD6,
                    0xFF241046, 0xFF120A2C, 0xFF05040D, 0xFF15112A),
    };

    private static final int LIVE_GREEN = 0xFF69F0AE;
    private int ORANGE, SAFFRON, GOLD, CREAM, LEAF, CHILI;
    private Theme theme;
    private int themeIndex;
    private long themeChangedAt = -10_000;
    private final float[] dotX = new float[THEMES.length];
    private float dotY;
    private android.graphics.Bitmap logo;
    private final RectF logoRect = new RectF();

    private static final String[] MESSAGES = {
            "Your dosa is being crafted fresh on the tawa",
            "Golden. Crispy. Worth every minute.",
            "Good food takes a little time — thank you for waiting",
            "Batter, heat and a lot of love in progress",
            "Sit back and relax — we'll serve it piping hot",
    };
    private static final String EMPTY_MESSAGE = "The tawa is hot and ready — orders are flying out!";
    private static final float MESSAGE_SECONDS = 6f;
    private static final float PANEL_SECONDS = 7f;
    private static final long STAFF_HOLD_MS = 1500;

    interface Listener {
        void onClose();

        void onTheme(int index);
    }

    private final float d;
    private final long start = SystemClock.uptimeMillis();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF titleHit = new RectF();
    private final Matrix matrix = new Matrix();
    private final Path path = new Path();
    private final Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final DateFormat timeFormat;

    private static final int EMBERS = 20;
    private final float[] ex = new float[EMBERS], ey = new float[EMBERS], es = new float[EMBERS],
            er = new float[EMBERS], ep = new float[EMBERS];
    private final int[] ec = new int[EMBERS];

    // floating Andhra tiffin art
    private static final int FOODS = 16;
    private android.graphics.Bitmap[] food;
    private final int[] fk = new int[FOODS];
    private final float[] fx = new float[FOODS], fy = new float[FOODS], fs = new float[FOODS],
            fsize = new float[FOODS], frot = new float[FOODS], fspin = new float[FOODS], fph = new float[FOODS];
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dst = new RectF();

    private Shader background, blobA, blobB, halo;
    private float haloKey;
    private SweepGradient borderShader, cometShader;
    private int shaderW, shaderH;

    private Listener listener;
    private String eta = "--";
    private int count, running, served;
    private float shownRunning, shownServed;
    private String avg = "—";
    private String fastest = "";
    private String updated = "";
    private String staff = "";
    private List<String> facts = new ArrayList<String>();
    private long[] trendTimes = new long[0];
    private float[] trendEta = new float[0];
    private boolean showStaff;
    private float closeX, closeY, closeR;
    private long downAt;
    private boolean downOnTitle;

    DosaNeonView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        timeFormat = android.text.format.DateFormat.getTimeFormat(c);
        try {
            java.io.InputStream in = c.getAssets().open("dosa_live_logo.png");
            logo = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
        } catch (Exception ignored) {
            logo = null;
        }
        setTheme(0);
        try {
            food = DosaFoodArt.renderAll();
        } catch (Throwable ignored) {
            food = null;
        }
        Random rnd = new Random(7);
        for (int i = 0; i < FOODS; i++) {
            fk[i] = i % DosaFoodArt.KINDS;
            fx[i] = (i + 0.5f) / FOODS + (rnd.nextFloat() - 0.5f) * 0.05f; // spread across width
            fy[i] = rnd.nextFloat();
            fs[i] = 0.010f + rnd.nextFloat() * 0.012f;                  // 45-100 s per climb
            fsize[i] = 34 + rnd.nextFloat() * 30;                         // dp
            frot[i] = rnd.nextFloat() * 360;
            fspin[i] = (rnd.nextFloat() - 0.5f) * 14;                     // deg / s
            fph[i] = rnd.nextFloat() * 6.28f;
        }
        for (int i = 0; i < EMBERS; i++) {
            ex[i] = rnd.nextFloat();
            ey[i] = rnd.nextFloat();
            es[i] = 0.025f + rnd.nextFloat() * 0.05f;
            er[i] = 1.2f + rnd.nextFloat() * 2.6f;
            ep[i] = rnd.nextFloat() * 6.28f;
            ec[i] = rnd.nextInt(4);
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
    }

    void setTheme(int index) {
        themeIndex = Math.max(0, Math.min(THEMES.length - 1, index));
        theme = THEMES[themeIndex];
        ORANGE = theme.P;
        GOLD = theme.S;
        LEAF = theme.T;
        CREAM = theme.L;
        SAFFRON = theme.M;
        CHILI = theme.R;
        background = null;  // rebuild shaders in the new colours
        halo = null;
        invalidate();
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setData(DosaStats.Result r, String updatedText, String staffText) {
        String e = r.etaLabel == null ? "--" : r.etaLabel;
        eta = e.startsWith("Under") ? "< 5" : e.replace(" min", "");
        count = r.activeCount;
        running = r.runningDosas;
        served = r.servedToday;
        avg = Double.isNaN(r.avgPrepMin) ? "—" : String.format(Locale.US, "%.1f", r.avgPrepMin);
        fastest = Double.isNaN(r.fastestPrepMin) ? "fastest —"
                : String.format(Locale.US, "fastest %.1f min", r.fastestPrepMin);
        updated = updatedText;
        staff = staffText;
        trendTimes = r.trendTimes;
        trendEta = r.trendEta;
        List<String> panel = new ArrayList<String>();
        List<String> f = r.facts == null ? new ArrayList<String>() : r.facts;
        int gi = 0;
        for (int i = 0; i < f.size(); i++) {
            panel.add("F" + f.get(i));
            // interleave a guest review after every second fact
            if (i % 2 == 1 && DosaLive.GUEST_LOVE.length > 0) {
                panel.add("R" + DosaLive.GUEST_LOVE[gi++ % DosaLive.GUEST_LOVE.length]);
            }
        }
        if (!panel.isEmpty()) facts = panel;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            downAt = SystemClock.uptimeMillis();
            downOnTitle = titleHit.contains(x, y);
        } else if (ev.getAction() == MotionEvent.ACTION_UP) {
            int hit = -1;
            for (int i = 0; i < THEMES.length; i++) {
                if (Math.hypot(x - dotX[i], y - dotY) < 13 * d) hit = i;
            }
            if (hit >= 0) {
                setTheme(hit);
                themeChangedAt = SystemClock.uptimeMillis();
                if (listener != null) listener.onTheme(hit);
            } else if (Math.hypot(x - closeX, y - closeY) < closeR + 14 * d) {
                if (listener != null) listener.onClose();
            } else if (downOnTitle && titleHit.contains(x, y)
                    && SystemClock.uptimeMillis() - downAt >= STAFF_HOLD_MS) {
                showStaff = !showStaff;
                invalidate();
            } else if (showStaff) {
                showStaff = false;
            }
        }
        return true; // never let taps fall through to the board underneath
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        ensureShaders((int) w, (int) h);
        shownRunning = ease(shownRunning, running);
        shownServed = ease(shownServed, served);

        drawBackground(c, w, h, t);
        drawBorder(c, w, h, t);

        float u = Math.min(w, h);
        float ringCx, ringCy, ringR, infoX, infoW, titleY, chartTop, chartBottom, chartX, chartW;
        if (w >= h) {
            ringCx = w * 0.245f;
            ringCy = h * 0.40f;
            ringR = Math.min(w * 0.15f, h * 0.23f);
            infoX = w * 0.465f;
            infoW = w * 0.935f - infoX;
            titleY = h * 0.20f;
            chartX = w * 0.06f;
            chartW = w * 0.875f;
            chartTop = h * 0.755f;
            chartBottom = h * 0.925f;
        } else {
            ringCx = w * 0.5f;
            ringCy = h * 0.21f;
            ringR = Math.min(w * 0.27f, h * 0.13f);
            infoX = w * 0.07f;
            infoW = w * 0.86f;
            titleY = h * 0.43f;
            chartX = w * 0.07f;
            chartW = w * 0.86f;
            chartTop = h * 0.80f;
            chartBottom = h * 0.95f;
        }

        drawRing(c, ringCx, ringCy, ringR, t);
        drawInfo(c, infoX, infoW, titleY, u, t);
        drawTrend(c, chartX, chartTop, chartW, chartBottom - chartTop, u, t);
        drawClose(c, w);
        drawThemeDots(c, t);
        if (showStaff) drawStaff(c, w, h);

        postInvalidateOnAnimation();
    }

    private static float ease(float shown, int target) {
        float v = shown + (target - shown) * 0.08f;
        return Math.abs(target - v) < 0.02f ? target : v;
    }

    private static float fade(float ph, float period) {
        return Math.max(0f, Math.min(1f, Math.min(ph / 0.9f, (period - ph) / 0.9f)));
    }

    // ---- background & border -------------------------------------------------------------

    private void ensureShaders(int w, int h) {
        if (w == shaderW && h == shaderH && background != null) return;
        shaderW = w;
        shaderH = h;
        float big = Math.max(w, h);
        background = new RadialGradient(w * 0.28f, h * 0.42f, big * 0.95f,
                new int[]{theme.bg0, theme.bg1, theme.bg2}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP);
        blobA = new RadialGradient(w * 0.85f, h * 0.12f, big * 0.38f,
                new int[]{alpha(ORANGE, 0x33), alpha(ORANGE, 0)}, null, Shader.TileMode.CLAMP);
        blobB = new RadialGradient(w * 0.12f, h * 0.95f, big * 0.35f,
                new int[]{alpha(GOLD, 0x2A), alpha(GOLD, 0)}, null, Shader.TileMode.CLAMP);
        borderShader = new SweepGradient(w / 2f, h / 2f,
                new int[]{ORANGE, GOLD, CREAM, SAFFRON, CHILI, ORANGE}, null);
        cometShader = new SweepGradient(0, 0,
                new int[]{alpha(ORANGE, 0), alpha(ORANGE, 0), ORANGE, GOLD, CREAM},
                new float[]{0f, 0.15f, 0.55f, 0.85f, 1f});
    }

    private void drawBackground(Canvas c, float w, float h, float t) {
        c.drawColor(theme.bg2);
        fill.setColor(Color.BLACK);
        fill.setAlpha(255);
        fill.setShader(background);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(blobA);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(blobB);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        if (food != null) {
            drawCornerLeaves(c, w, h, t);
            for (int i = 0; i < FOODS; i++) {
                float life = (fy[i] + t * fs[i]) % 1f;
                float x = (fx[i] + 0.025f * (float) Math.sin(t * 0.35f + fph[i])) * w;
                float y = h * (1.12f - life * 1.24f);
                float a = (float) Math.sin(Math.PI * life);
                float size = fsize[i] * d;
                c.save();
                c.translate(x, y);
                c.rotate(frot[i] + t * fspin[i]);
                dst.set(-size / 2, -size / 2, size / 2, size / 2);
                bmp.setAlpha((int) (95 * a));
                c.drawBitmap(food[fk[i]], null, dst, bmp);
                c.restore();
            }
        }

        for (int i = 0; i < EMBERS; i++) {
            float life = (ey[i] + t * es[i]) % 1f;
            float x = (ex[i] + 0.02f * (float) Math.sin(t * 0.6f + ep[i])) * w;
            float y = h * (1.05f - life * 1.1f);
            float f = (float) Math.sin(Math.PI * life);
            float r = er[i] * d;
            int[] pal = {ORANGE, SAFFRON, GOLD, CREAM};
            fill.setColor(pal[ec[i]]);
            fill.setAlpha((int) (30 * f));
            c.drawCircle(x, y, r * 3.2f, fill);
            fill.setAlpha((int) (190 * f));
            c.drawCircle(x, y, r, fill);
        }
        fill.setAlpha(255);
    }

    /** Big banana leaves peeking in from the top-left and bottom-right corners, gently swaying. */
    private void drawCornerLeaves(Canvas c, float w, float h, float t) {
        float size = Math.min(w, h) * 0.55f;
        float sway = 3f * (float) Math.sin(t * 2 * Math.PI / 7);
        bmp.setAlpha(80);
        c.save();
        c.translate(-size * 0.12f, -size * 0.05f);
        c.rotate(-20 + sway, size / 2, size / 2);
        dst.set(0, 0, size, size);
        c.drawBitmap(food[DosaFoodArt.LEAF], null, dst, bmp);
        c.restore();
        c.save();
        c.translate(w - size * 0.88f, h - size * 0.92f);
        c.rotate(160 - sway, size / 2, size / 2);
        dst.set(0, 0, size, size);
        c.drawBitmap(food[DosaFoodArt.LEAF], null, dst, bmp);
        c.restore();
        // small chilli & curry-leaf garnish near the leaves
        bmp.setAlpha(110);
        float g = size * 0.32f;
        c.save();
        c.translate(size * 0.42f, size * 0.30f);
        c.rotate(25 + sway * 2);
        dst.set(-g / 2, -g / 2, g / 2, g / 2);
        c.drawBitmap(food[DosaFoodArt.CHILLI], null, dst, bmp);
        c.restore();
        c.save();
        c.translate(w - size * 0.42f, h - size * 0.36f);
        c.rotate(-150 - sway * 2);
        dst.set(-g / 2, -g / 2, g / 2, g / 2);
        c.drawBitmap(food[DosaFoodArt.CURRY_LEAVES], null, dst, bmp);
        c.restore();
    }

    private void drawBorder(Canvas c, float w, float h, float t) {
        float inset = 12 * d, radius = 26 * d;
        rect.set(inset, inset, w - inset, h - inset);
        matrix.setRotate(t * 24f, w / 2f, h / 2f);
        borderShader.setLocalMatrix(matrix);
        stroke.setShader(borderShader);
        float[] widths = {16 * d, 8 * d, 2.5f * d};
        int[] alphas = {28, 70, 255};
        for (int i = 0; i < widths.length; i++) {
            stroke.setStrokeWidth(widths[i]);
            stroke.setAlpha(alphas[i]);
            c.drawRoundRect(rect, radius, radius, stroke);
        }
        stroke.setShader(null);
        stroke.setAlpha(255);
    }

    // ---- wait ring -----------------------------------------------------------------------

    private void drawRing(Canvas c, float cx, float cy, float r, float t) {
        float breath = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 4.5);
        drawKolam(c, cx, cy, r, t);

        if (halo == null || haloKey != cx * 31 + cy * 17 + r) {
            haloKey = cx * 31 + cy * 17 + r;
            halo = new RadialGradient(cx, cy, r * 1.6f,
                    new int[]{alpha(ORANGE, 0x55), alpha(GOLD, 0x18), 0x00000000}, new float[]{0.45f, 0.75f, 1f},
                    Shader.TileMode.CLAMP);
        }
        fill.setShader(halo);
        fill.setAlpha((int) (150 + 105 * breath));
        c.drawCircle(cx, cy, r * 1.6f, fill);
        fill.setShader(null);
        fill.setAlpha(255);

        stroke.setStrokeWidth(1.5f * d);
        stroke.setColor(GOLD);
        stroke.setAlpha((int) (40 + 60 * breath));
        c.drawCircle(cx, cy, r + (14 + 8 * breath) * d, stroke);
        stroke.setColor(ORANGE);
        stroke.setAlpha((int) (25 + 45 * (1 - breath)));
        c.drawCircle(cx, cy, r + (28 - 6 * breath) * d, stroke);

        stroke.setColor(Color.WHITE);
        stroke.setAlpha(22);
        stroke.setStrokeWidth(9 * d);
        c.drawCircle(cx, cy, r, stroke);

        c.save();
        c.translate(cx, cy);
        c.rotate(t * 50f);
        rect.set(-r, -r, r, r);
        stroke.setShader(cometShader);
        float[] widths = {24 * d, 13 * d, 6.5f * d};
        int[] alphas = {40, 110, 255};
        for (int i = 0; i < widths.length; i++) {
            stroke.setStrokeWidth(widths[i]);
            stroke.setAlpha(alphas[i]);
            c.drawArc(rect, 20, 330, false, stroke);
        }
        stroke.setShader(null);
        fill.setColor(Color.WHITE);
        fill.setShadowLayer(14 * d, 0, 0, GOLD);
        c.drawCircle(r, 0, 5 * d, fill);
        fill.clearShadowLayer();
        c.restore();
        stroke.setAlpha(255);

        text.setTypeface(condensed);
        text.setTextAlign(Paint.Align.CENTER);
        text.setLetterSpacing(0.3f);
        text.setTextSize(r * 0.13f);
        text.setColor(alpha(CREAM, 0xDD));
        c.drawText("ESTIMATED WAIT", cx, cy - r * 0.42f, text);

        text.setTypeface(thin);
        text.setLetterSpacing(0f);
        float size = r * 0.62f;
        text.setTextSize(size);
        float tw = text.measureText(eta);
        if (tw > r * 1.5f) {
            size *= r * 1.5f / tw;
            text.setTextSize(size);
        }
        text.setColor(Color.WHITE);
        text.setShadowLayer(18 * d, 0, 0, ORANGE);
        c.drawText(eta, cx, cy + size * 0.33f, text);
        text.clearShadowLayer();

        text.setTypeface(condensed);
        text.setLetterSpacing(0.4f);
        text.setTextSize(r * 0.12f);
        text.setColor(GOLD);
        c.drawText("MINUTES", cx, cy + r * 0.52f, text);
        text.setLetterSpacing(0f);
    }

    /** Faint muggulu (kolam) motif: dot grid and interlaced loops, slowly turning. */
    private void drawKolam(Canvas c, float cx, float cy, float r, float t) {
        c.save();
        c.rotate(t * 3f, cx, cy);
        stroke.setShader(null);
        stroke.setColor(CREAM);
        stroke.setStrokeWidth(1.2f * d);
        stroke.setAlpha(26);
        for (int k = 0; k < 8; k++) {
            double a = Math.toRadians(k * 45);
            c.drawCircle(cx + (float) Math.cos(a) * r * 1.32f, cy + (float) Math.sin(a) * r * 1.32f,
                    r * 0.42f, stroke);
        }
        fill.setShader(null);
        fill.setColor(CREAM);
        fill.setAlpha(40);
        for (int k = 0; k < 16; k++) {
            double a = Math.toRadians(k * 22.5);
            c.drawCircle(cx + (float) Math.cos(a) * r * 1.75f, cy + (float) Math.sin(a) * r * 1.75f,
                    2.2f * d, fill);
        }
        c.restore();
        fill.setAlpha(255);
        stroke.setAlpha(255);
    }

    // ---- title, message, cards, facts ----------------------------------------------------

    private void drawInfo(Canvas c, float x, float width, float titleY, float u, float t) {
        text.setTextAlign(Paint.Align.LEFT);

        // logo badge
        float badge = u * 0.19f;
        float bcx = x + badge / 2f, bcy = titleY - u * 0.03f;
        float breathe = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 4.5);
        fill.setShader(null);
        fill.setColor(ORANGE);
        fill.setAlpha((int) (40 + 40 * breathe));
        c.drawCircle(bcx, bcy, badge * 0.56f, fill);
        fill.setAlpha(255);
        if (logo != null) {
            logoRect.set(bcx - badge / 2f, bcy - badge / 2f, bcx + badge / 2f, bcy + badge / 2f);
            c.drawBitmap(logo, null, logoRect, fill);
        }
        x += badge + 14 * d;
        width -= badge + 14 * d;

        // brand line
        text.setTypeface(condensed);
        text.setLetterSpacing(0.3f);
        text.setTextSize(u * 0.026f);
        String brand = "BABAI TIFFINS  \u2022  TASTE THE ANDHRA STYLE";
        float bw = text.measureText(brand);
        if (bw > width) text.setTextSize(text.getTextSize() * width / bw);
        text.setColor(alpha(CREAM, 0xCC));
        c.drawText(brand, x, titleY - u * 0.085f, text);

        float ts = u * 0.085f;
        text.setTypeface(medium);
        text.setLetterSpacing(0.08f);
        text.setTextSize(ts);
        String a = "DOSA ", b = "LIVE";
        float aw = text.measureText(a);
        text.setColor(CREAM);
        text.setShadowLayer(16 * d, 0, 0, ORANGE);
        c.drawText(a, x, titleY, text);
        text.setColor(Color.WHITE);
        text.setShadowLayer(16 * d, 0, 0, GOLD);
        c.drawText(b, x + aw, titleY, text);
        text.clearShadowLayer();
        float titleW = aw + text.measureText(b);
        titleHit.set(x - 10 * d, titleY - ts, x + titleW + 10 * d, titleY + 10 * d);

        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 1.6);
        float dx = x + titleW + ts * 0.45f, dy = titleY - ts * 0.36f;
        fill.setColor(LIVE_GREEN);
        fill.setAlpha((int) (60 * pulse));
        c.drawCircle(dx, dy, ts * (0.16f + 0.12f * pulse), fill);
        fill.setAlpha(255);
        fill.setShadowLayer(10 * d, 0, 0, LIVE_GREEN);
        c.drawCircle(dx, dy, ts * 0.11f, fill);
        fill.clearShadowLayer();

        String msg;
        float alpha;
        if (count == 0) {
            msg = EMPTY_MESSAGE;
            alpha = 1f;
        } else {
            msg = MESSAGES[(int) (t / MESSAGE_SECONDS) % MESSAGES.length];
            alpha = fade(t % MESSAGE_SECONDS, MESSAGE_SECONDS);
        }
        text.setTypeface(light);
        text.setLetterSpacing(0.02f);
        text.setTextSize(u * 0.036f);
        text.setColor(alpha(CREAM, 0xF0));
        text.setAlpha((int) (255 * alpha));
        drawWrapped(c, msg, x, titleY + u * 0.06f, width, u * 0.045f, 1);
        text.setAlpha(255);
        x -= badge + 14 * d;
        width += badge + 14 * d;

        float gap = 10 * d;
        float cardTop = titleY + u * 0.115f;
        float cardH = u * 0.21f;
        float cw = (width - 2 * gap) / 3f;
        drawCard(c, x, cardTop, cw, cardH, String.valueOf(Math.round(shownRunning)), null,
                count == 1 ? "in 1 order" : "in " + count + " orders", "ON THE TAWA", ORANGE);
        drawCard(c, x + cw + gap, cardTop, cw, cardH, String.valueOf(Math.round(shownServed)), null,
                "fresh & hot", "SERVED TODAY", GOLD);
        drawCard(c, x + 2 * (cw + gap), cardTop, cw, cardH, avg, "min", fastest, "AVG PREP", LEAF);

        drawPanel(c, x, cardTop + cardH + u * 0.03f, width, u * 0.15f, u, t);
    }

    private void drawCard(Canvas c, float x, float y, float w, float h, String value, String unit,
                          String sub, String label, int color) {
        float r = 16 * d;
        rect.set(x, y, x + w, y + h);
        fill.setShader(null);
        fill.setColor(theme.surface);
        c.drawRoundRect(rect, r, r, fill);
        stroke.setShader(null);
        stroke.setColor(color);
        stroke.setStrokeWidth(5 * d);
        stroke.setAlpha(35);
        c.drawRoundRect(rect, r, r, stroke);
        stroke.setStrokeWidth(1.3f * d);
        stroke.setAlpha(170);
        c.drawRoundRect(rect, r, r, stroke);
        stroke.setAlpha(255);

        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(light);
        text.setLetterSpacing(0f);
        float vs = h * 0.34f;
        text.setTextSize(vs);
        float unitW = 0;
        Paint up = null;
        if (unit != null && !"—".equals(value)) {
            up = new Paint(text);
            up.setTextSize(vs * 0.4f);
            up.setTypeface(condensed);
            unitW = up.measureText(" " + unit);
        }
        float vw = text.measureText(value);
        float maxW = w * 0.86f;
        if (vw + unitW > maxW) {
            float k = maxW / (vw + unitW);
            text.setTextSize(vs * k);
            vw *= k;
            if (up != null) {
                up.setTextSize(up.getTextSize() * k);
                unitW *= k;
            }
        }
        float cx = x + w / 2f - unitW / 2f;
        float vy = y + h * 0.45f;
        text.setColor(Color.WHITE);
        text.setShadowLayer(12 * d, 0, 0, color);
        c.drawText(value, cx, vy, text);
        text.clearShadowLayer();
        if (up != null) {
            up.setTextAlign(Paint.Align.LEFT);
            up.setColor(color);
            up.clearShadowLayer();
            c.drawText(" " + unit, cx + vw / 2f, vy, up);
        }

        if (sub != null) {
            text.setTypeface(light);
            text.setLetterSpacing(0.02f);
            text.setTextSize(h * 0.1f);
            float sw = text.measureText(sub);
            if (sw > w * 0.9f) text.setTextSize(text.getTextSize() * w * 0.9f / sw);
            text.setColor(0xCCFFFFFF);
            c.drawText(sub, x + w / 2f, y + h * 0.66f, text);
        }

        text.setTypeface(condensed);
        text.setLetterSpacing(0.18f);
        text.setTextSize(h * 0.1f);
        float lw = text.measureText(label);
        if (lw > w * 0.9f) text.setTextSize(text.getTextSize() * w * 0.9f / lw);
        text.setColor(color);
        text.setAlpha(220);
        c.drawText(label, x + w / 2f, y + h * 0.87f, text);
        text.setAlpha(255);
        text.setLetterSpacing(0f);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** Rotating "Did you know" facts, interleaved with guest reviews when provided. */
    private void drawPanel(Canvas c, float x, float y, float w, float h, float u, float t) {
        int n = facts.size();
        String item = n == 0 ? "F" : facts.get((int) (t / PANEL_SECONDS) % n);
        boolean review = item.startsWith("R");
        String body = item.substring(1);
        int accent = review ? CHILI : GOLD;

        float r = 14 * d;
        rect.set(x, y, x + w, y + h);
        fill.setShader(null);
        fill.setColor(theme.surface);
        c.drawRoundRect(rect, r, r, fill);
        stroke.setShader(null);
        stroke.setColor(accent);
        stroke.setStrokeWidth(1.2f * d);
        stroke.setAlpha(140);
        c.drawRoundRect(rect, r, r, stroke);
        stroke.setAlpha(255);
        fill.setColor(accent);
        fill.setShadowLayer(8 * d, 0, 0, accent);
        c.drawRoundRect(x + 10 * d, y + h * 0.2f, x + 13 * d, y + h * 0.8f, 2 * d, 2 * d, fill);
        fill.clearShadowLayer();

        float alpha = fade(t % PANEL_SECONDS, PANEL_SECONDS);
        float tx = x + 24 * d;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.25f);
        text.setTextSize(u * 0.025f);
        text.setColor(accent);
        text.setAlpha((int) (255 * alpha));
        c.drawText(review ? "♥ GUEST LOVE" : "✦ DID YOU KNOW", tx, y + h * 0.3f, text);

        text.setTypeface(review ? Typeface.create("serif", Typeface.ITALIC) : light);
        text.setLetterSpacing(0.01f);
        text.setTextSize(u * 0.032f);
        text.setColor(Color.WHITE);
        text.setAlpha((int) (255 * alpha));
        drawWrapped(c, review ? "“" + body + "”" : body, tx, y + h * 0.58f, w - 36 * d,
                u * 0.04f, 2);
        text.setAlpha(255);
        text.setLetterSpacing(0f);
    }

    // ---- wait trend chart ----------------------------------------------------------------

    private void drawTrend(Canvas c, float x, float y, float w, float h, float u, float t) {
        float r = 14 * d;
        rect.set(x, y, x + w, y + h);
        fill.setShader(null);
        fill.setColor(alpha(theme.surface, 0xCC));
        c.drawRoundRect(rect, r, r, fill);
        stroke.setShader(null);
        stroke.setColor(ORANGE);
        stroke.setStrokeWidth(1f * d);
        stroke.setAlpha(90);
        c.drawRoundRect(rect, r, r, stroke);
        stroke.setAlpha(255);

        // header
        float pad = 14 * d;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.25f);
        text.setTextSize(u * 0.024f);
        text.setColor(GOLD);
        c.drawText("WAIT TREND  •  LAST 3 HOURS", x + pad, y + pad + u * 0.018f, text);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTypeface(light);
        text.setLetterSpacing(0.12f);
        text.setColor(0x99FFFFFF);
        c.drawText("UPDATED " + updated + "  •  MADE FRESH TO ORDER ♥", x + w - pad,
                y + pad + u * 0.018f, text);
        text.setLetterSpacing(0f);

        float px = x + pad + u * 0.06f, pw = w - 2 * pad - u * 0.06f;
        float py = y + pad + u * 0.04f, ph = h - (py - y) - pad * 0.8f;
        int n = trendTimes.length;
        if (n < 2) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(light);
            text.setTextSize(u * 0.03f);
            text.setColor(0xAAFFFFFF);
            c.drawText("The trend draws itself as dosa orders flow in…", x + w / 2f, py + ph * 0.6f, text);
            text.setTextAlign(Paint.Align.LEFT);
            return;
        }

        long t0 = trendTimes[0], t1 = trendTimes[n - 1];
        long span = Math.max(30 * 60_000L, t1 - t0);
        float maxEta = 10;
        for (float v : trendEta) maxEta = Math.max(maxEta, v);
        maxEta = (float) Math.ceil(maxEta / 5f) * 5f;

        // grid + y labels
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTypeface(light);
        text.setTextSize(u * 0.022f);
        text.setColor(0x88FFFFFF);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(1);
        for (int g = 0; g <= 2; g++) {
            float gy = py + ph * g / 2f;
            stroke.setAlpha(g == 2 ? 50 : 22);
            c.drawLine(px, gy, px + pw, gy, stroke);
            c.drawText(Math.round(maxEta * (2 - g) / 2f) + "m", px - 6 * d, gy + u * 0.008f, text);
        }
        stroke.setAlpha(255);

        // line + area
        path.reset();
        float lastX = 0, lastY = 0;
        for (int i = 0; i < n; i++) {
            float fx = px + pw * (float) (trendTimes[i] - (t1 - span)) / span;
            float fy = py + ph * (1 - Math.min(1f, trendEta[i] / maxEta));
            if (i == 0) path.moveTo(fx, fy); else path.lineTo(fx, fy);
            lastX = fx;
            lastY = fy;
        }
        float firstX = px + pw * (float) (t0 - (t1 - span)) / span;
        Path area = new Path(path);
        area.lineTo(lastX, py + ph);
        area.lineTo(firstX, py + ph);
        area.close();
        fill.setShader(new LinearGradient(0, py, 0, py + ph, alpha(ORANGE, 0x66), alpha(ORANGE, 0), Shader.TileMode.CLAMP));
        c.drawPath(area, fill);
        fill.setShader(null);

        stroke.setShader(new LinearGradient(px, 0, px + pw, 0, SAFFRON, GOLD, Shader.TileMode.CLAMP));
        stroke.setStrokeWidth(7 * d);
        stroke.setAlpha(45);
        c.drawPath(path, stroke);
        stroke.setStrokeWidth(2.4f * d);
        stroke.setAlpha(255);
        c.drawPath(path, stroke);
        stroke.setShader(null);

        // live point
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 1.6);
        fill.setColor(GOLD);
        fill.setAlpha((int) (70 * pulse));
        c.drawCircle(lastX, lastY, (6 + 6 * pulse) * d, fill);
        fill.setAlpha(255);
        fill.setColor(Color.WHITE);
        c.drawCircle(lastX, lastY, 3.5f * d, fill);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTypeface(condensed);
        text.setTextSize(u * 0.026f);
        text.setColor(CREAM);
        c.drawText(String.format(Locale.US, "now ~%.0fm", trendEta[n - 1]), lastX - 10 * d,
                Math.max(py + u * 0.03f, lastY - 8 * d), text);

        // x labels
        text.setTypeface(light);
        text.setTextSize(u * 0.021f);
        text.setColor(0x88FFFFFF);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(timeFormat.format(new Date(t1 - span)), px, y + h - pad * 0.25f, text);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText(timeFormat.format(new Date(t1)), px + pw, y + h - pad * 0.25f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ---- close & staff -------------------------------------------------------------------

    private void drawThemeDots(Canvas c, float t) {
        dotY = closeY;
        for (int i = 0; i < THEMES.length; i++) {
            dotX[i] = closeX - closeR - 24 * d - (THEMES.length - 1 - i) * 24 * d;
            boolean sel = i == themeIndex;
            fill.setShader(null);
            fill.setColor(THEMES[i].P);
            c.drawCircle(dotX[i], dotY, (sel ? 7 : 5.5f) * d, fill);
            if (sel) {
                stroke.setShader(null);
                stroke.setColor(Color.WHITE);
                stroke.setStrokeWidth(1.6f * d);
                stroke.setAlpha(220);
                c.drawCircle(dotX[i], dotY, 11 * d, stroke);
                stroke.setAlpha(255);
            }
        }
        long since = SystemClock.uptimeMillis() - themeChangedAt;
        if (since < 2500) {
            text.setTypeface(condensed);
            text.setTextAlign(Paint.Align.CENTER);
            text.setLetterSpacing(0.2f);
            text.setTextSize(11 * d);
            text.setColor(Color.WHITE);
            text.setAlpha((int) (255 * Math.min(1f, (2500 - since) / 600f)));
            c.drawText(theme.name.toUpperCase(Locale.US), (dotX[0] + dotX[THEMES.length - 1]) / 2f,
                    dotY + 28 * d, text);
            text.setAlpha(255);
            text.setLetterSpacing(0f);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void drawClose(Canvas c, float w) {
        closeR = 18 * d;
        closeX = w - 46 * d;
        closeY = 46 * d;
        fill.setShader(null);
        fill.setColor(0x33FFFFFF);
        c.drawCircle(closeX, closeY, closeR, fill);
        stroke.setShader(null);
        stroke.setColor(Color.WHITE);
        stroke.setAlpha(200);
        stroke.setStrokeWidth(2 * d);
        float k = closeR * 0.38f;
        c.drawLine(closeX - k, closeY - k, closeX + k, closeY + k, stroke);
        c.drawLine(closeX - k, closeY + k, closeX + k, closeY - k, stroke);
        stroke.setAlpha(255);
    }

    private void drawStaff(Canvas c, float w, float h) {
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0f);
        text.setTextSize(11 * d);
        String[] lines = ("STAFF ONLY (tap anywhere to hide)\n" + staff).split("\n");
        float lh = 14 * d;
        float bh = lines.length * lh + 16 * d;
        rect.set(28 * d, h - 28 * d - bh, w - 28 * d, h - 28 * d);
        fill.setShader(null);
        fill.setColor(alpha(theme.bg2, 0xF0));
        c.drawRoundRect(rect, 10 * d, 10 * d, fill);
        text.setColor(CREAM);
        float y = rect.top + 8 * d + lh * 0.8f;
        for (String line : lines) {
            c.drawText(line, rect.left + 12 * d, y, text);
            y += lh;
        }
    }

    private float drawWrapped(Canvas c, String s, float x, float y, float maxW, float lineH, int maxLines) {
        List<String> lines = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            String trial = cur.length() == 0 ? word : cur + " " + word;
            if (text.measureText(trial) > maxW && cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(trial);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        // shrink to fit instead of cutting words off
        if (lines.size() > maxLines && text.getTextSize() > 8 * d) {
            float k = 0.9f;
            text.setTextSize(text.getTextSize() * k);
            return drawWrapped(c, s, x, y, maxW, lineH * k, maxLines);
        }
        float baseline = y;
        for (int i = 0; i < lines.size(); i++) {
            baseline = y + i * lineH;
            c.drawText(lines.get(i), x, baseline, text);
        }
        return baseline;
    }
}
