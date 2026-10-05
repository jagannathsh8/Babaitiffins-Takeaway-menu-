package com.pp.kds.dosa;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Hand-drawn (vector, code-only) Andhra tiffin illustrations used as floating background art:
 * dosa cone, idli, banana leaf, red chilli, chutney bowls (coconut / tomato / mint), podi with
 * ghee and a curry-leaf sprig. Each is rendered once into a small bitmap.
 */
final class DosaFoodArt {

    static final int DOSA = 0, IDLI = 1, LEAF = 2, CHILLI = 3, COCONUT_CHUTNEY = 4,
            TOMATO_CHUTNEY = 5, MINT_CHUTNEY = 6, PODI = 7, CURRY_LEAVES = 8;
    static final int KINDS = 9;
    private static final int S = 160; // bitmap size; drawing space is 0..128 scaled up

    private DosaFoodArt() {}

    static Bitmap[] renderAll() {
        Bitmap[] out = new Bitmap[KINDS];
        for (int k = 0; k < KINDS; k++) {
            Bitmap b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.scale(S / 128f, S / 128f);
            draw(c, k);
            out[k] = b;
        }
        return out;
    }

    private static Paint fill() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    private static Paint line(int color, float w) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        p.setStrokeWidth(w);
        return p;
    }

    private static void draw(Canvas c, int kind) {
        switch (kind) {
            case DOSA: dosa(c); break;
            case IDLI: idli(c); break;
            case LEAF: leaf(c); break;
            case CHILLI: chilli(c); break;
            case COCONUT_CHUTNEY: bowl(c, 0xFFF4EEDC, 0xFF6A9F3A, false); break;
            case TOMATO_CHUTNEY: bowl(c, 0xFFD84315, 0xFFFFCCBC, false); break;
            case MINT_CHUTNEY: bowl(c, 0xFF7CB342, 0xFFDCEDC8, false); break;
            case PODI: bowl(c, 0xFFC1440E, 0xFF8D2A06, true); break;
            default: curryLeaves(c); break;
        }
    }

    /** Rolled masala dosa: crisp golden tube, lacy roast spots, potato masala at the open end. */
    private static void dosa(Canvas c) {
        c.rotate(-18, 64, 64);
        RectF tube = new RectF(8, 46, 124, 84);
        Paint body = fill();
        body.setShader(new LinearGradient(0, 46, 0, 84, new int[]{0xFFF8D27E, 0xFFD98E36, 0xFF9C5419},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(tube, 19, 19, body);
        Paint spot = fill();
        spot.setColor(0x669C5419);
        float[][] sp = {{40, 56, 3}, {58, 62, 2.2f}, {74, 54, 2.8f}, {92, 64, 2.4f}, {106, 56, 2f},
                {50, 72, 2.6f}, {84, 74, 2.2f}, {66, 70, 1.8f}, {98, 76, 2f}};
        for (float[] q : sp) c.drawCircle(q[0], q[1], q[2], spot);
        c.drawLine(30, 51, 116, 51, line(0x99FFF3D6, 2.2f));
        c.drawRoundRect(tube, 19, 19, line(0xFF8D4A12, 2.5f));
        // open end with spiral and masala
        RectF end = new RectF(2, 46, 30, 84);
        Paint edge = fill();
        edge.setColor(0xFFE9A94F);
        c.drawOval(end, edge);
        Paint masala = fill();
        masala.setColor(0xFFFFC928);
        c.drawOval(new RectF(9, 55, 23, 75), masala);
        Paint dot = fill();
        dot.setColor(0xFF7CB342);
        c.drawCircle(14, 62, 1.6f, dot);
        dot.setColor(0xFFE65100);
        c.drawCircle(18, 68, 1.4f, dot);
        c.drawArc(new RectF(5, 50, 27, 80), 120, 220, false, line(0xFFB5651D, 1.8f));
        c.drawOval(end, line(0xFF8D4A12, 2.2f));
    }

    /** Two soft steamed idlis. */
    private static void idli(Canvas c) {
        idliOne(c, 44, 58, 0.8f);
        idliOne(c, 70, 76, 1f);
    }

    private static void idliOne(Canvas c, float cx, float cy, float s) {
        RectF body = new RectF(cx - 42 * s, cy - 20 * s, cx + 42 * s, cy + 24 * s);
        Paint side = fill();
        side.setColor(0xFFE3DCC8);
        c.drawOval(new RectF(body.left, body.top + 10 * s, body.right, body.bottom), side);
        Paint top = fill();
        top.setShader(new RadialGradient(cx - 10 * s, cy - 12 * s, 46 * s, 0xFFFFFFFF, 0xFFF1EBDB, Shader.TileMode.CLAMP));
        c.drawOval(new RectF(body.left, body.top, body.right, body.bottom - 10 * s), top);
        Paint dimple = fill();
        dimple.setColor(0x33A1887F);
        c.drawCircle(cx - 14 * s, cy - 4 * s, 2.2f * s, dimple);
        c.drawCircle(cx + 8 * s, cy - 8 * s, 1.8f * s, dimple);
        c.drawCircle(cx + 18 * s, cy + 2 * s, 2f * s, dimple);
        c.drawOval(body, line(0x55A1887F, 1.5f));
    }

    /** Banana leaf with midrib and veins. */
    private static void leaf(Canvas c) {
        Path p = new Path();
        p.moveTo(6, 96);
        p.cubicTo(30, 30, 90, 8, 124, 20);
        p.cubicTo(110, 62, 60, 118, 6, 96);
        p.close();
        Paint f = fill();
        f.setShader(new LinearGradient(10, 100, 120, 20, 0xFF2E7D32, 0xFF66BB6A, Shader.TileMode.CLAMP));
        c.drawPath(p, f);
        Paint rib = line(0xCCC5E1A5, 2.2f);
        Path mid = new Path();
        mid.moveTo(8, 96);
        mid.quadTo(60, 50, 122, 21);
        c.drawPath(mid, rib);
        Paint vein = line(0x5520531F, 1.2f);
        for (int i = 1; i < 9; i++) {
            float t = i / 9f;
            float x = 8 + (122 - 8) * t, y = 96 + (21 - 96) * t - (float) Math.sin(Math.PI * t) * 12;
            c.drawLine(x, y, x - 4, y - 22 + t * 6, vein);
            c.drawLine(x, y, x + 10, y + 18 - t * 8, vein);
        }
        c.drawPath(p, line(0x991B5E20, 1.5f));
    }

    /** Guntur red chilli with green stem. */
    private static void chilli(Canvas c) {
        Path p = new Path();
        p.moveTo(24, 34);
        p.cubicTo(52, 22, 104, 54, 114, 112);
        p.cubicTo(90, 82, 50, 62, 22, 52);
        p.close();
        Paint f = fill();
        f.setShader(new LinearGradient(24, 30, 110, 110, 0xFFEF5350, 0xFFB71C1C, Shader.TileMode.CLAMP));
        c.drawPath(p, f);
        Path shine = new Path();
        shine.moveTo(34, 38);
        shine.cubicTo(58, 34, 90, 58, 102, 92);
        c.drawPath(shine, line(0x88FFCDD2, 2.5f));
        Paint stem = line(0xFF388E3C, 6);
        Path st = new Path();
        st.moveTo(24, 43);
        st.quadTo(12, 40, 10, 24);
        c.drawPath(st, stem);
        Paint cap = fill();
        cap.setColor(0xFF2E7D32);
        c.drawOval(new RectF(16, 34, 32, 52), cap);
    }

    /** Small steel katori with chutney or podi (+ ghee). */
    private static void bowl(Canvas c, int top, int speck, boolean podi) {
        RectF rim = new RectF(14, 46, 114, 70);
        Path body = new Path();
        body.moveTo(14, 58);
        body.cubicTo(18, 108, 110, 108, 114, 58);
        body.close();
        Paint steel = fill();
        steel.setShader(new LinearGradient(14, 58, 114, 100, 0xFFCFD8DC, 0xFF546E7A, Shader.TileMode.CLAMP));
        c.drawPath(body, steel);
        Paint rimP = fill();
        rimP.setColor(0xFFECEFF1);
        c.drawOval(rim, rimP);
        Paint food = fill();
        food.setColor(top);
        if (podi) {
            Path mound = new Path();
            mound.moveTo(20, 60);
            mound.quadTo(64, 18, 108, 60);
            mound.quadTo(64, 70, 20, 60);
            c.drawPath(mound, food);
            Paint ghee = fill();
            ghee.setShader(new RadialGradient(62, 44, 12, 0xFFFFF59D, 0xFFFFB300, Shader.TileMode.CLAMP));
            c.drawOval(new RectF(50, 38, 76, 50), ghee);
        } else {
            c.drawOval(new RectF(20, 49, 108, 67), food);
        }
        Paint sp = fill();
        sp.setColor(speck);
        float[][] pts = {{40, 57}, {58, 54}, {76, 59}, {90, 55}, {50, 61}, {68, 52}};
        for (float[] q : pts) c.drawCircle(q[0], podi ? q[1] - 2 : q[1], podi ? 1.4f : 2f, sp);
        c.drawOval(rim, line(0xFF90A4AE, 1.5f));
    }

    /** Curry-leaf sprig for tadka. */
    private static void curryLeaves(Canvas c) {
        Path stem = new Path();
        stem.moveTo(20, 112);
        stem.quadTo(60, 70, 110, 18);
        c.drawPath(stem, line(0xFF558B2F, 3));
        Paint f = fill();
        for (int i = 0; i < 7; i++) {
            float t = 0.15f + i * 0.12f;
            float x = 20 + 90 * t, y = 112 - 94 * t + (float) Math.sin(Math.PI * t) * 8;
            for (int side = -1; side <= 1; side += 2) {
                c.save();
                c.translate(x, y);
                c.rotate(-45 + side * 60);
                f.setColor(side < 0 ? 0xFF388E3C : 0xFF43A047);
                c.drawOval(new RectF(0, -5, 24, 5), f);
                c.restore();
            }
        }
    }
}
