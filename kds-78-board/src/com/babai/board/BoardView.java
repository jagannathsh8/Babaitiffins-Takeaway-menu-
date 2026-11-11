package com.babai.board;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Babai Dosa Board, drawn natively: the same design as the approved page (Babai Dosa Board),
 * on the same 1600 x 900 stage scaled to the screen. Header (mascot, DOSA PICKUP STATUS, clock),
 * READY FOR PICKUP (green running border, cards, newest in the spotlight) and PREPARING (yellow
 * running border, rows with progress rings), footer (est. prep time, dosas in the making, did you
 * know ticker). Colours, sizes and fonts are the page's (Montserrat 800/900 for "Nexa", Figtree).
 */
public final class BoardView extends View {

    // ---- the page's colours
    static final int BG = 0xFFFCFBDA, HEAD = 0xFF000000, PANEL = 0xFFFFFFFF, LINE = 0xFFE6DFC8, MUTED = 0xFF7A6C5A,
            GREEN = 0xFF087C45, FRESH = 0xFF00A85A, YELLOW = 0xFFFAAC23, PREP_INK = 0xFF000000, MAGENTA = 0xFFCF126D,
            PURPLE = 0xFF4F267A, RING = 0xFF171717;

    static final String[] FACTS = {
            "Pesarattu’s protein partner: green gram, with 22.5 g protein per 100 g dry grain.",
            "Pesarattu and upma: Andhra’s traditional breakfast buddies.",
            "“Kaaram” means spicy in Telugu. Your dosa comes with a delicious warning!"};

    /** The page's fonts and pictures. */
    public static final class Look {
        public Typeface mont800, mont900, fig600, fig700, fig800;
        public Bitmap mascot, spinner;
    }

    private final Look look;
    private BoardModel model = new BoardModel();
    private long now = System.currentTimeMillis();
    private final long start = SystemClock.uptimeMillis();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG), bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Matrix mx = new Matrix();
    private final Map<String, Long> firstSeen = new HashMap<String, Long>();
    private final Paint darkSpin = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    /** Frozen time for screenshots (ms since the view started); <0: live. */
    public long frozenT = -1;
    private boolean firstFrame = true; // cards already there when the board opens don't pop in

    public BoardView(Context c, Look look) {
        super(c);
        this.look = look;
        stroke.setStyle(Paint.Style.STROKE);
        ColorMatrix cm = new ColorMatrix(new float[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, .92f, 0});
        darkSpin.setColorFilter(new ColorMatrixColorFilter(cm)); // filter: brightness(0); opacity .92
        setLayerType(LAYER_TYPE_HARDWARE, null);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setKeepScreenOn(true);
    }

    public void show(BoardModel m, long now) {
        this.model = m;
        this.now = now;
        invalidate();
    }

    private float t() { // seconds of animation
        return (frozenT >= 0 ? frozenT : SystemClock.uptimeMillis() - start) / 1000f;
    }

    // ---- drawing helpers ----------------------------------------------------------------------

    private void font(Typeface tf, float size, int color, float letterPx) {
        text.setTypeface(tf);
        text.setTextSize(size);
        text.setColor(color);
        text.setLetterSpacing(size > 0 ? letterPx / size : 0);
        text.setShader(null);
    }

    private float width(String s) {
        return text.measureText(s);
    }

    /** Text with its middle at cy (the way the page centres its lines). */
    private void mid(Canvas c, String s, float x, float cy, Paint.Align al) {
        text.setTextAlign(al);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2f, text);
    }

    private float fit(String s, Typeface tf, float max, float room, float letterPx) {
        float f = max;
        font(tf, f, 0, letterPx * f / max);
        while (f > 18 && width(s) > room) {
            f -= 2;
            font(tf, f, 0, letterPx * f / max);
        }
        return f;
    }

    private void round(Canvas c, float l, float tp, float rt, float b, float rad, int color) {
        r.set(l, tp, rt, b);
        fill.setShader(null);
        fill.setColor(color);
        c.drawRoundRect(r, rad, rad, fill);
    }

    /** CSS conic-gradient(from a): starts at 12 o'clock, clockwise. */
    private Shader conic(float cx, float cy, int[] cols, float[] pos, float deg) {
        SweepGradient g = new SweepGradient(cx, cy, cols, pos);
        mx.reset();
        mx.setRotate(-90 + deg, cx, cy);
        g.setLocalMatrix(mx);
        return g;
    }

    private static int alpha(int c, float a) {
        return (Math.round(Color.alpha(c) * a) << 24) | (c & 0xFFFFFF);
    }

    // ---- the board -----------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        float W = getWidth(), H = getHeight();
        if (W <= 0 || H <= 0) return;
        if (frozenT < 0) now = System.currentTimeMillis();
        float s = Math.min(W / 1600f, H / 900f), SW = W / s, SH = H / s;
        c.save();
        c.scale(s, s);
        background(c, SW, SH);
        header(c, SW);
        float mainTop = 96, mainBot = SH - 108, MW = SW - 56;
        float rw = (MW - 8) * 3 / 5f;
        readyZone(c, 28, mainTop, 28 + rw, mainBot);
        prepZone(c, 28 + rw + 8, mainTop, SW - 28, mainBot);
        footer(c, 28, SH - 92, SW - 28, SH - 18);
        c.restore();
        firstFrame = false;
        if (frozenT < 0) postInvalidateDelayed(33); // ~30 frames a second: smooth on TV boxes too
    }

    private void background(Canvas c, float SW, float SH) {
        c.drawColor(BG);
        glow(c, SW * .25f, -SH * .10f, 1200, 500, 0x1A00A85A);
        glow(c, SW * .85f, -SH * .10f, 1000, 500, 0x14FFD43B);
    }

    private void glow(Canvas c, float cx, float cy, float rx, float ry, int col) {
        RadialGradient g = new RadialGradient(0, 0, 1, new int[]{col, col & 0xFFFFFF}, new float[]{0, .6f}, Shader.TileMode.CLAMP);
        mx.reset();
        mx.setScale(rx, ry);
        mx.postTranslate(cx, cy);
        g.setLocalMatrix(mx);
        fill.setShader(g);
        c.drawRect(0, 0, cx + rx * 2, cy + ry * 2, fill);
        fill.setShader(null);
    }

    private void header(Canvas c, float SW) {
        float cy = 45;
        float mh = 68, mw = look.mascot == null ? 62 : mh * look.mascot.getWidth() / look.mascot.getHeight();
        if (look.mascot != null) {
            r.set(28, cy - mh / 2, 28 + mw, cy + mh / 2);
            c.drawBitmap(look.mascot, null, r, bmp);
        }
        float lx = 28 + mw + 22 + 6;
        fill.setColor(0x2E000000);
        c.drawRect(lx, 10, lx + 2, 80, fill);
        font(look.mont800, 56, HEAD, 0);
        mid(c, "DOSA PICKUP STATUS", lx + 24, cy + 56 * .04f, Paint.Align.LEFT);
        font(look.mont800, 44, HEAD, 0);
        mid(c, clock(), SW - 28, cy + 44 * .04f, Paint.Align.RIGHT);
    }

    private String clock() {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(now);
        int h = k.get(Calendar.HOUR_OF_DAY), m = k.get(Calendar.MINUTE);
        String ap = h >= 12 ? "PM" : "AM";
        h = h % 12 == 0 ? 12 : h % 12;
        return h + ":" + (m < 10 ? "0" : "") + m + " " + ap;
    }

    /** A zone: 7 px running border (conic), white inside, coloured title bar with the logo. */
    private void zone(Canvas c, float l, float tp, float rt, float b, int[] cols, float[] pos, float deg, int bar, int ink,
                      String title, boolean dark) {
        r.set(l, tp, rt, b);
        fill.setShader(conic((l + rt) / 2, (tp + b) / 2, cols, pos, deg));
        c.drawRoundRect(r, 26, 26, fill);
        fill.setShader(null);
        round(c, l + 7, tp + 7, rt - 7, b - 7, 19, PANEL);
        // title bar: rounded on top only
        c.save();
        r.set(l + 7, tp + 7, rt - 7, b - 7);
        path.reset();
        path.addRoundRect(r, 19, 19, Path.Direction.CW);
        c.clipPath(path);
        fill.setColor(bar);
        c.drawRect(l + 7, tp + 7, rt - 7, tp + 7 + 56, fill);
        c.restore();
        font(look.fig800, 22, ink, 2.5f);
        mid(c, title, l + 7 + 22, tp + 7 + 28, Paint.Align.LEFT);
        if (look.spinner != null) {
            float sz = 42, sx = rt - 7 - 22 - sz, sy = tp + 7 + 7;
            c.save();
            c.rotate((t() / 9f * 360f) % 360f, sx + sz / 2, sy + sz / 2);
            r.set(sx, sy, sx + sz, sy + sz);
            c.drawBitmap(look.spinner, null, r, dark ? darkSpin : bmp);
            c.restore();
        }
    }

    // ---- READY ----------------------------------------------------------------------------------

    private void readyZone(Canvas c, float l, float tp, float rt, float b) {
        zone(c, l, tp, rt, b, new int[]{GREEN, GREEN, FRESH, 0xFF5FD39A, FRESH, GREEN},
                new float[]{0, 220 / 360f, 270 / 360f, 300 / 360f, 325 / 360f, 1}, (t() / 6f * 360f) % 360f, GREEN, Color.WHITE,
                "✓ READY FOR PICKUP", false);
        float gx = l + 7 + 16, gy = tp + 7 + 56 + 16, GW = rt - 7 - 16 - gx, GH = b - 7 - 16 - gy;
        List<BoardModel.Ready> list = model.ready;
        int shown = Math.min(50, list.size()), n = Math.max(9, shown);
        // the page's layoutGrid: the column count that gives the biggest cards (cards 1.5 : 1)
        float g = n > 20 ? 8 : 12;
        int bc = 3, br = 3;
        float bw = 0, bh = 0, best = -1;
        for (int cols = 3; cols <= 12; cols++) {
            int rows = (n + cols - 1) / cols;
            if (rows < 3 && n <= 9) continue;
            float w = (GW - g * (cols - 1)) / cols, h = (GH - g * (rows - 1)) / rows, sc = Math.min(w / 1.5f, h);
            if (sc > best) {
                best = sc;
                bc = cols;
                br = rows;
                bw = w;
                bh = h;
            }
        }
        float cs = Math.min(1f, Math.min(bh / 165f, bw / 250f));
        boolean dense = cs < .6f, tiny = cs < .42f;
        for (int i = 0; i < bc * br && i < Math.max(9, shown); i++) {
            float x = gx + (i % bc) * (bw + g), y = gy + (i / bc) * (bh + g);
            if (i >= shown) {
                if (i < 9) emptyCard(c, x, y, bw, bh);
                continue;
            }
            BoardModel.Ready rd = list.get(i);
            long age = now - rd.readyAt;
            float pop = pop(rd.o.token + "r", i == 0 && age < BoardModel.SPOT_MS ? 800 : 600);
            c.save();
            c.scale(pop, pop, x + bw / 2, y + bh / 2);
            if (i == 0 && age < BoardModel.SPOT_MS) spotCard(c, rd, x, y, bw, bh, cs, dense, tiny, age);
            else readyCard(c, rd, x, y, bw, bh, cs, dense, tiny, age);
            c.restore();
        }
    }

    /** The page's "pop" for a card that just came: scale .6 -> 1 with a small overshoot. */
    private float pop(String key, long dur) {
        Long f = firstSeen.get(key);
        long ms = frozenT >= 0 ? frozenT : SystemClock.uptimeMillis() - start;
        if (f == null) {
            firstSeen.put(key, ms);
            if (firstSeen.size() > 400) firstSeen.clear();
            return firstFrame ? 1f : .6f;
        }
        float p = Math.min(1f, (ms - f) / (float) dur);
        if (p >= 1f) return 1f;
        float k = 1 - p;
        return 1f - .4f * k * k * k + .12f * (float) Math.sin(p * Math.PI) * k;
    }

    private void emptyCard(Canvas c, float x, float y, float w, float h) {
        stroke.setColor(LINE);
        stroke.setStrokeWidth(3);
        stroke.setPathEffect(new DashPathEffect(new float[]{9, 6}, 0));
        r.set(x + 1.5f, y + 1.5f, x + w - 1.5f, y + h - 1.5f);
        c.drawRoundRect(r, 18, 18, stroke);
        stroke.setPathEffect(null);
    }

    private String ago(long age) {
        long m = age / 60000L;
        return m < 1 ? "Just now" : m + " min ago";
    }

    private void readyCard(Canvas c, BoardModel.Ready rd, float x, float y, float w, float h, float cs, boolean dense,
                           boolean tiny, long age) {
        float rad = dense ? 12 : 18;
        boolean fresh = age < BoardModel.FRESH_MS;
        if (fresh) { // spinning light border
            r.set(x, y, x + w, y + h);
            fill.setShader(conic(x + w / 2, y + h / 2, new int[]{GREEN, 0xFFD9FFE9, GREEN, 0xFF04512D, GREEN},
                    new float[]{0, .25f, .5f, .75f, 1}, (t() / 2f * 360f) % 360f));
            c.drawRoundRect(r, rad, rad, fill);
            fill.setShader(null);
            round(c, x + 3, y + 3, x + w - 3, y + h - 3, rad - 3, FRESH);
        } else {
            round(c, x, y, x + w, y + h, rad, GREEN);
        }
        // the lines, centred as a block
        float pad = dense ? 4 : 10, room = w - 2 * pad - 6;
        float tf = fit(rd.o.token, look.mont900, 60 * cs, room, -1 * cs);
        List<String> items = new ArrayList<String>();
        float isz = 16 * cs;
        if (!tiny) {
            font(look.fig700, isz, Color.WHITE, 0);
            for (BoardModel.Item it : rd.o.items) items.addAll(wrap(it.label(), room));
        }
        float asz = 13 * cs;
        boolean showAge = !dense;
        float hTok = tf * 1.15f, hItems = items.size() * isz * 1.2f, hAge = showAge ? 5 * cs + asz * 1.25f + (fresh ? 8 * cs : 0) : 0;
        int maxLines = (int) Math.max(0, (h - 12 - hTok - hAge) / (isz * 1.2f));
        if (items.size() > maxLines) {
            items = new ArrayList<String>(items.subList(0, maxLines));
            hItems = items.size() * isz * 1.2f;
        }
        float y0 = y + (h - (hTok + hItems + hAge)) / 2, cx = x + w / 2;
        font(look.mont900, tf, Color.WHITE, -1 * cs);
        mid(c, rd.o.token, cx, y0 + hTok / 2 + tf * .03f, Paint.Align.CENTER);
        float yy = y0 + hTok;
        font(look.fig700, isz, Color.WHITE, 0);
        for (String ln : items) {
            mid(c, ln, cx, yy + isz * .6f, Paint.Align.CENTER);
            yy += isz * 1.2f;
        }
        if (showAge) {
            yy += 5 * cs;
            if (fresh) {
                font(look.fig700, asz, GREEN, 1);
                String a = "JUST NOW";
                float pw = width(a) + 24 * cs, ph = asz * 1.25f + 8 * cs;
                round(c, cx - pw / 2, yy, cx + pw / 2, yy + ph, ph / 2, Color.WHITE);
                mid(c, a, cx, yy + ph / 2, Paint.Align.CENTER);
            } else {
                font(look.fig700, asz, 0xCCFFFFFF, 0);
                mid(c, ago(age), cx, yy + asz * .65f, Paint.Align.CENTER);
            }
        }
    }

    private void spotCard(Canvas c, BoardModel.Ready rd, float x, float y, float w, float h, float cs, boolean dense,
                          boolean tiny, long age) {
        float rad = dense ? 12 : 18;
        // glow: 0 -> 6 px of rgba(250,172,35,.35) every 1.6 s
        float gp = (float) (0.5 - 0.5 * Math.cos(t() / 1.6f * 2 * Math.PI));
        if (gp > .02f) {
            stroke.setColor(alpha(0xFFFAAC23, .35f));
            stroke.setStrokeWidth(6 * gp);
            r.set(x - 3 * gp, y - 3 * gp, x + w + 3 * gp, y + h + 3 * gp);
            c.drawRoundRect(r, rad + 3 * gp, rad + 3 * gp, stroke);
        }
        round(c, x, y, x + w, y + h, rad, YELLOW); // 4 px border
        r.set(x + 4, y + 4, x + w - 4, y + h - 4);
        float gx = x + w / 2, gy = y + h * .4f, far = (float) Math.hypot(Math.max(gx - x, x + w - gx), Math.max(gy - y, y + h - gy));
        fill.setShader(new RadialGradient(gx, gy, far, new int[]{FRESH, GREEN}, new float[]{0, .8f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad - 4, rad - 4, fill);
        fill.setShader(null);
        float room = w - 16 - 8;
        float ssz = 12 * cs, tf = fit(rd.o.token, look.mont900, 54 * cs, room, -1 * cs), psz = 13 * cs, isz = 16 * cs;
        List<String> items = new ArrayList<String>();
        if (!tiny) {
            StringBuilder sb = new StringBuilder();
            for (BoardModel.Item it : rd.o.items) sb.append(sb.length() > 0 ? " · " : "").append(it.label());
            font(look.fig700, isz, Color.WHITE, 0);
            items = wrap(sb.toString(), room);
            if (items.size() > 2) items = items.subList(0, 2);
        }
        float hSmall = dense ? 0 : ssz * 1.3f, hTok = tf * 1.1f, ph = tiny ? 0 : psz * 1.2f + 8 * cs,
                hItems = items.isEmpty() ? 0 : 6 * cs + items.size() * isz * 1.2f;
        float y0 = y + (h - 10 - (hSmall + hTok + ph + hItems)) / 2 + 2, cx = x + w / 2;
        if (!dense) {
            font(look.fig800, ssz, alpha(Color.WHITE, .9f), 3 * cs);
            mid(c, "BILL NO.", cx, y0 + hSmall / 2, Paint.Align.CENTER);
        }
        float yy = y0 + hSmall;
        font(look.mont900, tf, Color.WHITE, -1 * cs);
        text.setShadowLayer(0.1f, 0, 4, 0x2E000000);
        mid(c, rd.o.token, cx, yy + hTok / 2 + tf * .03f, Paint.Align.CENTER);
        text.clearShadowLayer();
        yy += hTok;
        if (!tiny) { // READY FOR PICKUP pill (pops in)
            float k = Math.min(1f, Math.max(0f, (age - 500) / 900f));
            float sc = k < .4f ? k / .4f * 1.15f : k < .6f ? 1.15f - (k - .4f) / .2f * .2f : .95f + (k - .6f) / .4f * .05f;
            font(look.fig800, psz, GREEN, 2 * cs);
            String p = "READY FOR PICKUP";
            float pw = width(p) + 24 * cs;
            c.save();
            c.scale(sc, sc, cx, yy + ph / 2);
            round(c, cx - pw / 2, yy + 3, cx + pw / 2, yy + ph + 3, ph / 2, 0x26000000);
            round(c, cx - pw / 2, yy, cx + pw / 2, yy + ph, ph / 2, Color.WHITE);
            mid(c, p, cx, yy + ph / 2, Paint.Align.CENTER);
            c.restore();
            yy += ph;
        }
        if (!items.isEmpty()) {
            yy += 6 * cs;
            font(look.fig700, isz, Color.WHITE, 0);
            for (String ln : items) {
                mid(c, ln, cx, yy + isz * .6f, Paint.Align.CENTER);
                yy += isz * 1.2f;
            }
        }
        // the bar runs out with the spotlight
        float left = Math.max(0f, 1f - age / (float) BoardModel.SPOT_MS);
        c.save();
        r.set(x + 4, y + 4, x + w - 4, y + h - 4);
        path.reset();
        path.addRoundRect(r, rad - 4, rad - 4, Path.Direction.CW);
        c.clipPath(path);
        fill.setColor(YELLOW);
        c.drawRect(x + 4, y + h - 4 - 6, x + 4 + (w - 8) * left, y + h - 4, fill);
        c.restore();
    }

    private List<String> wrap(String s, float room) {
        List<String> out = new ArrayList<String>();
        String line = "";
        for (String w : s.split(" ")) {
            String t = line.isEmpty() ? w : line + " " + w;
            if (!line.isEmpty() && width(t) > room) {
                out.add(line);
                line = w;
            } else {
                line = t;
            }
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    // ---- PREPARING ------------------------------------------------------------------------------

    private void prepZone(Canvas c, float l, float tp, float rt, float b) {
        zone(c, l, tp, rt, b, new int[]{YELLOW, YELLOW, 0xFFFFC964, 0xFFFFE2A8, 0xFFFFC964, YELLOW},
                new float[]{0, 220 / 360f, 270 / 360f, 300 / 360f, 325 / 360f, 1}, 360f - (t() / 7f * 360f) % 360f, YELLOW, PREP_INK,
                "PREPARING", true);
        List<BoardModel.Prep> list = model.prep;
        int n = Math.min(7, list.size());
        float px = l + 7 + 16, py = tp + 7 + 56 + 16, PW = rt - 7 - 16 - px, moreH = 18 + 14;
        float PH = b - 7 - moreH - 16 - py;
        if (n > 0) {
            float rh = Math.min((PH - 8 * (n - 1)) / n, (PH - 8 * 3) / 4.5f);
            for (int i = 0; i < n; i++) prepRow(c, list.get(i), i, px, py + i * (rh + 8), PW, rh);
        }
        int extra = list.size() - 7;
        if (extra > 0) {
            font(look.fig700, 15, MUTED, 0);
            mid(c, "+" + extra + " more order" + (extra > 1 ? "s" : "") + " waiting", (l + rt) / 2, b - 7 - 14 - 9, Paint.Align.CENTER);
        }
    }

    private void prepRow(Canvas c, BoardModel.Prep p, int i, float x, float y, float w, float h) {
        if (i == 0) { // "next up": a magenta ring pulses out of the row every second
            float k = (t() % 1f);
            stroke.setColor(alpha(MAGENTA, .55f * (1 - k)));
            stroke.setStrokeWidth(12 * k);
            r.set(x - 6 * k, y - 6 * k, x + w + 6 * k, y + h + 6 * k);
            c.drawRoundRect(r, 16 + 6 * k, 16 + 6 * k, stroke);
        }
        round(c, x, y, x + w, y + h, 16, YELLOW);
        float cy = y + h / 2;
        // token: 122 px column
        float tf = fit(p.o.token, look.mont900, 42, 122, -.5f);
        font(look.mont900, tf, PREP_INK, -.5f);
        mid(c, p.o.token, x + 16, cy + tf * .03f, Paint.Align.LEFT);
        // the dosas as chips (two lines at most, then "+N more")
        float cx0 = x + 16 + 122 + 16, cw = w - 32 - 122 - 74 - 32;
        chips(c, p, cx0, cy, cw);
        // ring: minutes left
        float rx = x + w - 16 - 37, rr = 24.86f;
        stroke.setStrokeWidth(5.52f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(0x26171717);
        c.drawCircle(rx, cy, rr, stroke);
        if (p.progress > 0) {
            stroke.setColor(RING);
            r.set(rx - rr, cy - rr, rx + rr, cy + rr);
            c.drawArc(r, -90, 360 * p.progress, false, stroke);
        }
        stroke.setStrokeCap(Paint.Cap.BUTT);
        font(look.mont900, 22, PREP_INK, 0);
        mid(c, "~" + p.minutes, rx, cy - 5, Paint.Align.CENTER);
        font(look.fig800, 11, PREP_INK, 1);
        mid(c, "MIN", rx, cy + 11, Paint.Align.CENTER);
    }

    private void chips(Canvas c, BoardModel.Prep p, float x0, float cy, float room) {
        font(look.fig700, 16, PREP_INK, 0);
        float ch = 16 * 1.2f + 6, gap = 6;
        List<String> labels = new ArrayList<String>();
        for (BoardModel.Item it : p.o.items) labels.add((it.ready ? "✓ " : "") + it.label());
        // lay out in up to two lines; what doesn't fit becomes "+N more"
        List<float[]> pos = new ArrayList<float[]>();
        List<String> shown = new ArrayList<String>();
        float x = 0;
        int line = 0;
        int i = 0;
        for (; i < labels.size(); i++) {
            float cw = Math.min(room, width(labels.get(i)) + 20);
            if (x > 0 && x + cw > room) {
                line++;
                x = 0;
            }
            if (line > 1) break;
            pos.add(new float[]{x, line, cw});
            shown.add(labels.get(i));
            x += cw + gap;
        }
        int hidden = labels.size() - shown.size();
        if (hidden > 0) {
            String more = "+" + hidden + " more";
            float mw = width(more) + 20;
            float[] last = pos.get(pos.size() - 1);
            while (last[0] + last[2] + gap + mw > room && pos.size() > 1 && last[1] >= 1) {
                pos.remove(pos.size() - 1);
                shown.remove(shown.size() - 1);
                hidden++;
                more = "+" + hidden + " more";
                mw = width(more) + 20;
                last = pos.get(pos.size() - 1);
            }
            float mx0 = last[0] + last[2] + gap;
            if (mx0 + mw > room) { // the only chip shares its line: shorten it
                last[2] = Math.max(40, room - mw - gap);
                mx0 = last[0] + last[2] + gap;
            }
            pos.add(new float[]{mx0, last[1], mw});
            shown.add(more);
        }
        int lines = 1;
        for (float[] q : pos) lines = Math.max(lines, (int) q[1] + 1);
        float top = cy - (lines * ch + (lines - 1) * gap) / 2;
        for (int k = 0; k < shown.size(); k++) {
            float[] q = pos.get(k);
            float l = x0 + q[0], tp = top + q[1] * (ch + gap);
            round(c, l, tp, l + q[2], tp + ch, 8, 0x8CFFFFFF);
            String s = shown.get(k);
            c.save();
            c.clipRect(l + 10, tp, l + q[2] - 4, tp + ch);
            font(look.fig700, 16, PREP_INK, 0);
            if (width(s) > q[2] - 20) s = ellipsize(s, q[2] - 20);
            mid(c, s, l + 10, tp + ch / 2, Paint.Align.LEFT);
            c.restore();
        }
    }

    private String ellipsize(String s, float room) {
        while (s.length() > 1 && width(s + "…") > room) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    // ---- footer ---------------------------------------------------------------------------------

    private void footer(Canvas c, float l, float tp, float rt, float b) {
        float u = (rt - l - 28) / 3.6f, h = b - tp, cy = tp + h / 2;
        float x1 = l, x2 = l + u + 14, x3 = x2 + u + 14;
        // Est. prep time
        round(c, x1, tp, x1 + u, b, 16, MAGENTA);
        font(look.fig800, 19, Color.WHITE, 1);
        mid(c, "EST. PREP TIME", x1 + 18, cy, Paint.Align.LEFT);
        font(look.mont800, 32, Color.WHITE, 0);
        mid(c, "~" + model.estMinutes + " min", x1 + u - 18, cy + 1, Paint.Align.RIGHT);
        // Dosas in the making
        round(c, x2, tp, x2 + u, b, 16, PURPLE);
        font(look.fig800, 19, Color.WHITE, 1);
        mid(c, "DOSAS IN THE MAKING", x2 + 18, cy - 10, Paint.Align.LEFT);
        font(look.fig600, 14, Color.WHITE, .2f);
        mid(c, "Freshly made, just for you!", x2 + 18, cy + 13, Paint.Align.LEFT);
        font(look.mont800, 32, Color.WHITE, 0);
        mid(c, String.valueOf(model.dosasInMaking), x2 + u - 18, cy + 1, Paint.Align.RIGHT);
        // Did you know?
        float w3 = rt - x3;
        round(c, x3, tp, rt, b, 16, MAGENTA);
        font(look.fig800, 19, Color.WHITE, 1);
        String dl = "DID YOU KNOW?";
        mid(c, dl, x3 + 18, cy, Paint.Align.LEFT);
        float sep = x3 + 18 + width(dl) + 14;
        fill.setColor(0x59FFFFFF);
        c.drawRect(sep, cy - 13, sep + 2, cy + 13, fill);
        ticker(c, sep + 2 + 14, tp, rt, b, cy);
    }

    private void ticker(Canvas c, float l, float tp, float rt, float b, float cy) {
        // each fact: 28 px | fact | 56 px | ✦ | 28 px (the page's <i> with its ::after star)
        font(look.fig700, 20, Color.WHITE, 0);
        float star = width("✦"), one = 0;
        for (String f : FACTS) one += 28 + width(f) + 56 + star + 28;
        int chars = 0;
        for (String f : FACTS) chars += f.length();
        float dur = Math.max(18f, chars * .28f), off = (t() % dur) / dur * one;
        int layer = c.saveLayer(l, tp, rt, b, null);
        c.clipRect(l, tp, rt, b);
        float x = l - off;
        while (x < rt) {
            for (String f : FACTS) {
                font(look.fig700, 20, Color.WHITE, 0);
                float fw = width(f);
                mid(c, f, x + 28, cy, Paint.Align.LEFT);
                text.setColor(0x99FFFFFF);
                mid(c, "✦", x + 28 + fw + 56, cy, Paint.Align.LEFT);
                x += 28 + fw + 56 + star + 28;
            }
        }
        // fade at both ends (the page's mask: 4 % and 6 %)
        float w = rt - l;
        Paint m = new Paint();
        m.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        m.setShader(new LinearGradient(l, 0, rt, 0, new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000},
                new float[]{0, .04f, .94f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(l, tp, l + w, b, m);
        c.restoreToCount(layer);
    }
}
