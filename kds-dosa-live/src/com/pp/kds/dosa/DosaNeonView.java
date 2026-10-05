package com.pp.kds.dosa;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Customer-facing "Dosa Live" screen: calm neon look with a rotating neon border, a breathing
 * wait ring, rising tawa embers and rotating comfort messages. Everything is drawn on one Canvas
 * (no resources needed), animated with postInvalidateOnAnimation while visible.
 */
final class DosaNeonView extends View {

    private static final int CYAN = 0xFF00E5FF;
    private static final int MAGENTA = 0xFFFF2BD6;
    private static final int AMBER = 0xFFFFB300;
    private static final int VIOLET = 0xFF7C4DFF;

    private static final String[] MESSAGES = {
            "Your dosa is being crafted fresh on the tawa",
            "Golden. Crispy. Worth every minute.",
            "Good food takes a little time — thank you for waiting",
            "Batter, heat and a lot of love in progress",
            "Sit back and relax — we'll serve it piping hot",
    };
    private static final String EMPTY_MESSAGE = "The tawa is hot and ready — orders are flying out!";
    private static final float MESSAGE_SECONDS = 6f;

    interface Listener {
        void onClose();
    }

    private final float d;
    private final long start = SystemClock.uptimeMillis();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF titleHit = new RectF();
    private final Matrix matrix = new Matrix();
    private final Typeface thin = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    private final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    // embers rising from the tawa
    private static final int EMBERS = 34;
    private final float[] ex = new float[EMBERS], ey = new float[EMBERS], es = new float[EMBERS],
            er = new float[EMBERS], ep = new float[EMBERS];
    private final int[] ec = new int[EMBERS];

    private Shader background, blobA, blobB, halo;
    private float haloKey;
    private SweepGradient borderShader, cometShader;
    private int shaderW, shaderH;

    private Listener listener;
    private String eta = "--";
    private String etaUnit = "MINUTES";
    private int count;
    private float shownCount;
    private String avg = "—";
    private String updated = "";
    private String staff = "";
    private boolean showStaff;
    private float closeX, closeY, closeR;

    DosaNeonView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        Random rnd = new Random(7);
        int[] palette = {AMBER, 0xFFFF7043, 0xFFFFD180, MAGENTA};
        for (int i = 0; i < EMBERS; i++) {
            ex[i] = rnd.nextFloat();
            ey[i] = rnd.nextFloat();
            es[i] = 0.025f + rnd.nextFloat() * 0.05f;
            er[i] = 1.2f + rnd.nextFloat() * 2.6f;
            ep[i] = rnd.nextFloat() * 6.28f;
            ec[i] = palette[rnd.nextInt(palette.length)];
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setData(String etaLabel, int activeCount, double avgPrepMin, String updatedText, String staffText) {
        String e = etaLabel == null ? "--" : etaLabel;
        if (e.startsWith("Under")) {
            eta = "< 5";
        } else {
            eta = e.replace(" min", "");
        }
        count = activeCount;
        avg = Double.isNaN(avgPrepMin) ? "—" : String.format(java.util.Locale.US, "%.1f", avgPrepMin);
        updated = updatedText;
        staff = staffText;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_UP) {
            float x = ev.getX(), y = ev.getY();
            if (Math.hypot(x - closeX, y - closeY) < closeR + 20 * d) {
                if (listener != null) listener.onClose();
            } else if (titleHit.contains(x, y)) {
                showStaff = !showStaff; // hidden staff details: tap the title
                invalidate();
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
        shownCount += (count - shownCount) * 0.08f;
        if (Math.abs(count - shownCount) < 0.02f) shownCount = count;

        drawBackground(c, w, h, t);
        drawBorder(c, w, h, t);

        boolean landscape = w >= h;
        float u = Math.min(w, h);
        float ringCx, ringCy, ringR, infoX, infoW, titleY;
        if (landscape) {
            ringCx = w * 0.29f;
            ringCy = h * 0.54f;
            ringR = Math.min(w * 0.18f, h * 0.29f);
            infoX = w * 0.53f;
            infoW = w * 0.93f - infoX;
            titleY = h * 0.22f;
        } else {
            ringCx = w * 0.5f;
            ringCy = h * 0.30f;
            ringR = Math.min(w * 0.30f, h * 0.17f);
            infoX = w * 0.08f;
            infoW = w * 0.84f;
            titleY = h * 0.58f;
        }

        drawRing(c, ringCx, ringCy, ringR, t);
        drawInfo(c, infoX, infoW, titleY, u, h, t, landscape);
        drawClose(c, w);
        if (showStaff) drawStaff(c, w, h);

        postInvalidateOnAnimation();
    }

    // ---- pieces --------------------------------------------------------------------------

    private void ensureShaders(int w, int h) {
        if (w == shaderW && h == shaderH && background != null) return;
        shaderW = w;
        shaderH = h;
        float big = Math.max(w, h);
        background = new RadialGradient(w * 0.3f, h * 0.55f, big * 0.95f,
                new int[]{0xFF241046, 0xFF120A2C, 0xFF05040D}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP);
        blobA = new RadialGradient(w * 0.82f, h * 0.15f, big * 0.35f,
                new int[]{0x3300E5FF, 0x0000E5FF}, null, Shader.TileMode.CLAMP);
        blobB = new RadialGradient(w * 0.15f, h * 0.95f, big * 0.35f,
                new int[]{0x33FF2BD6, 0x00FF2BD6}, null, Shader.TileMode.CLAMP);
        borderShader = new SweepGradient(w / 2f, h / 2f,
                new int[]{CYAN, VIOLET, MAGENTA, AMBER, CYAN}, null);
        cometShader = new SweepGradient(0, 0,
                new int[]{0x0000E5FF, 0x0000E5FF, CYAN, MAGENTA, AMBER},
                new float[]{0f, 0.15f, 0.55f, 0.85f, 1f});
    }

    private void drawBackground(Canvas c, float w, float h, float t) {
        fill.setShader(background);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(blobA);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(blobB);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        for (int i = 0; i < EMBERS; i++) {
            float life = (ey[i] + t * es[i]) % 1f;                 // 0 bottom -> 1 top
            float x = (ex[i] + 0.02f * (float) Math.sin(t * 0.6f + ep[i])) * w;
            float y = h * (1.05f - life * 1.1f);
            float fade = (float) Math.sin(Math.PI * life);         // fade in & out
            float r = er[i] * d;
            fill.setColor(ec[i]);
            fill.setAlpha((int) (35 * fade));
            c.drawCircle(x, y, r * 3.2f, fill);
            fill.setAlpha((int) (200 * fade));
            c.drawCircle(x, y, r, fill);
        }
        fill.setAlpha(255);
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

    private void drawRing(Canvas c, float cx, float cy, float r, float t) {
        float breath = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 4.5);   // 4.5 s breathing

        // soft halo
        if (halo == null || haloKey != cx * 31 + cy * 17 + r) {
            haloKey = cx * 31 + cy * 17 + r;
            halo = new RadialGradient(cx, cy, r * 1.6f,
                    new int[]{0x4400E5FF, 0x14FF2BD6, 0x00000000}, new float[]{0.45f, 0.75f, 1f},
                    Shader.TileMode.CLAMP);
        }
        fill.setShader(halo);
        fill.setAlpha((int) (150 + 105 * breath));
        c.drawCircle(cx, cy, r * 1.6f, fill);
        fill.setShader(null);
        fill.setAlpha(255);

        // breathing outer rings
        stroke.setStrokeWidth(1.5f * d);
        stroke.setColor(CYAN);
        stroke.setAlpha((int) (40 + 60 * breath));
        c.drawCircle(cx, cy, r + (14 + 8 * breath) * d, stroke);
        stroke.setColor(MAGENTA);
        stroke.setAlpha((int) (25 + 40 * (1 - breath)));
        c.drawCircle(cx, cy, r + (30 - 6 * breath) * d, stroke);

        // track
        stroke.setColor(Color.WHITE);
        stroke.setAlpha(22);
        stroke.setStrokeWidth(10 * d);
        c.drawCircle(cx, cy, r, stroke);

        // rotating comet arc with glow
        c.save();
        c.translate(cx, cy);
        c.rotate(t * 50f);
        rect.set(-r, -r, r, r);
        stroke.setShader(cometShader);
        float[] widths = {26 * d, 14 * d, 7 * d};
        int[] alphas = {40, 110, 255};
        for (int i = 0; i < widths.length; i++) {
            stroke.setStrokeWidth(widths[i]);
            stroke.setAlpha(alphas[i]);
            c.drawArc(rect, 20, 330, false, stroke);
        }
        stroke.setShader(null);
        // bright head
        fill.setColor(Color.WHITE);
        fill.setShadowLayer(14 * d, 0, 0, AMBER);
        c.drawCircle(r, 0, 5 * d, fill);
        fill.clearShadowLayer();
        c.restore();
        stroke.setAlpha(255);

        // label above
        text.setTypeface(condensed);
        text.setTextAlign(Paint.Align.CENTER);
        text.setLetterSpacing(0.35f);
        text.setTextSize(r * 0.13f);
        text.setColor(0xCCB2EBF2);
        text.clearShadowLayer();
        c.drawText("ESTIMATED WAIT", cx, cy - r * 0.42f, text);

        // big number
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
        text.setShadowLayer(18 * d, 0, 0, CYAN);
        c.drawText(eta, cx, cy + size * 0.33f, text);
        text.clearShadowLayer();

        // unit
        text.setTypeface(condensed);
        text.setLetterSpacing(0.4f);
        text.setTextSize(r * 0.12f);
        text.setColor(0xFFFFD180);
        c.drawText(etaUnit, cx, cy + r * 0.52f, text);
        text.setLetterSpacing(0f);
    }

    private void drawInfo(Canvas c, float x, float width, float titleY, float u, float h, float t,
                          boolean landscape) {
        text.setTextAlign(Paint.Align.LEFT);

        // neon title
        float ts = u * 0.095f;
        text.setTypeface(medium);
        text.setLetterSpacing(0.08f);
        text.setTextSize(ts);
        String a = "DOSA ", b = "LIVE";
        float aw = text.measureText(a);
        text.setColor(0xFFFFE0B2);
        text.setShadowLayer(16 * d, 0, 0, AMBER);
        c.drawText(a, x, titleY, text);
        text.setColor(0xFFE0F7FA);
        text.setShadowLayer(16 * d, 0, 0, MAGENTA);
        c.drawText(b, x + aw, titleY, text);
        text.clearShadowLayer();
        float titleW = aw + text.measureText(b);
        titleHit.set(x - 10 * d, titleY - ts, x + titleW + 10 * d, titleY + 10 * d);

        // pulsing live dot
        float pulse = 0.5f + 0.5f * (float) Math.sin(t * 2 * Math.PI / 1.6);
        float dx = x + titleW + ts * 0.45f, dy = titleY - ts * 0.36f;
        fill.setColor(0xFF69F0AE);
        fill.setAlpha((int) (60 * pulse));
        c.drawCircle(dx, dy, ts * (0.16f + 0.12f * pulse), fill);
        fill.setAlpha(255);
        fill.setShadowLayer(10 * d, 0, 0, 0xFF69F0AE);
        c.drawCircle(dx, dy, ts * 0.11f, fill);
        fill.clearShadowLayer();

        // rotating comfort message (cross-fade)
        String msg;
        float alpha;
        if (count == 0) {
            msg = EMPTY_MESSAGE;
            alpha = 1f;
        } else {
            int idx = (int) (t / MESSAGE_SECONDS) % MESSAGES.length;
            float ph = t % MESSAGE_SECONDS;
            alpha = Math.min(1f, Math.min(ph / 0.9f, (MESSAGE_SECONDS - ph) / 0.9f));
            msg = MESSAGES[idx];
        }
        text.setTypeface(light);
        text.setLetterSpacing(0.02f);
        text.setTextSize(u * 0.043f);
        text.setColor(0xFFE1E6F0);
        text.setAlpha((int) (255 * Math.max(0f, alpha)));
        float msgBottom = drawWrapped(c, msg, x, titleY + u * 0.1f, width, u * 0.058f, 2);
        text.setAlpha(255);

        // glass stat cards
        float gap = 12 * d;
        float cardTop = Math.max(msgBottom + u * 0.06f, titleY + u * 0.26f);
        float cardH = u * 0.25f;
        float cw = (width - 2 * gap) / 3f;
        drawCard(c, x, cardTop, cw, cardH, String.valueOf(Math.round(shownCount)), null,
                "ON THE TAWA", CYAN);
        drawCard(c, x + cw + gap, cardTop, cw, cardH, avg, "min", "AVG PREP TODAY", MAGENTA);
        drawCard(c, x + 2 * (cw + gap), cardTop, cw, cardH, updated, null, "UPDATED", AMBER);

        // footer
        text.setTypeface(light);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0.12f);
        text.setTextSize(u * 0.03f);
        text.setColor(0x99FFFFFF);
        float fy = cardTop + cardH + u * 0.08f;
        if (fy < h - 24 * d) c.drawText("MADE FRESH TO ORDER  •  THANK YOU FOR YOUR PATIENCE ♥", x, fy, text);
        text.setLetterSpacing(0f);
    }

    private void drawCard(Canvas c, float x, float y, float w, float h, String value, String unit,
                          String label, int color) {
        float r = 16 * d;
        rect.set(x, y, x + w, y + h);
        fill.setColor(0x16FFFFFF);
        c.drawRoundRect(rect, r, r, fill);
        stroke.setColor(color);
        stroke.setStrokeWidth(5 * d);
        stroke.setAlpha(35);
        c.drawRoundRect(rect, r, r, stroke);
        stroke.setStrokeWidth(1.3f * d);
        stroke.setAlpha(170);
        c.drawRoundRect(rect, r, r, stroke);
        stroke.setAlpha(255);

        // value
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(light);
        text.setLetterSpacing(0f);
        float vs = h * 0.36f;
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
        float vy = y + h * 0.55f;
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

        // label
        text.setTypeface(condensed);
        text.setLetterSpacing(0.18f);
        text.setTextSize(h * 0.11f);
        float lw = text.measureText(label);
        if (lw > w * 0.9f) text.setTextSize(text.getTextSize() * w * 0.9f / lw);
        text.setColor(color);
        text.setAlpha(210);
        c.drawText(label, x + w / 2f, y + h * 0.82f, text);
        text.setAlpha(255);
        text.setLetterSpacing(0f);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawClose(Canvas c, float w) {
        closeR = 18 * d;
        closeX = w - 46 * d;
        closeY = 46 * d;
        fill.setColor(0x33FFFFFF);
        c.drawCircle(closeX, closeY, closeR, fill);
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
        String[] lines = staff.split("\n");
        float lh = 14 * d;
        float bh = lines.length * lh + 16 * d;
        rect.set(28 * d, h - 28 * d - bh, w - 28 * d, h - 28 * d);
        fill.setColor(0xDD0A0A18);
        c.drawRoundRect(rect, 10 * d, 10 * d, fill);
        text.setColor(0xFFB0BEC5);
        float y = rect.top + 8 * d + lh * 0.8f;
        for (String line : lines) {
            c.drawText(line, rect.left + 12 * d, y, text);
            y += lh;
        }
    }

    /** Word-wraps {@code s} into at most {@code maxLines}; returns the baseline of the last line. */
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
        float baseline = y;
        for (int i = 0; i < lines.size() && i < maxLines; i++) {
            baseline = y + i * lineH;
            c.drawText(lines.get(i), x, baseline, text);
        }
        return baseline;
    }
}
