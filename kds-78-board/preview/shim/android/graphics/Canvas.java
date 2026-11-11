package android.graphics;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Preview shim: android.graphics.Canvas drawn with Java 2D (layers for saveLayer). */
public class Canvas {
    private static final class State {
        AffineTransform tx;
        Shape clip;
        Graphics2D layerOwner; // set when this save is a saveLayer
        BufferedImage layer;
    }

    private final BufferedImage base;
    private Graphics2D g;
    private final ArrayDeque<State> stack = new ArrayDeque<State>();

    public Canvas(BufferedImage img) {
        base = img;
        g = setup(img.createGraphics());
    }

    private static Graphics2D setup(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    public int save() {
        State s = new State();
        s.tx = g.getTransform();
        s.clip = g.getClip();
        stack.push(s);
        return stack.size();
    }

    public int saveLayer(float l, float t, float r, float b, Paint p) {
        State s = new State();
        s.tx = g.getTransform();
        s.clip = g.getClip();
        s.layerOwner = g;
        s.layer = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        stack.push(s);
        Graphics2D lg = setup(s.layer.createGraphics());
        lg.setTransform(s.tx);
        lg.setClip(s.clip);
        g = lg;
        return stack.size();
    }

    public void restore() {
        State s = stack.pop();
        if (s.layerOwner != null) {
            g.dispose();
            g = s.layerOwner;
            AffineTransform keep = g.getTransform();
            Shape kc = g.getClip();
            g.setTransform(new AffineTransform());
            g.setClip(null);
            g.drawImage(s.layer, 0, 0, null);
            g.setTransform(keep);
            g.setClip(kc);
        }
        g.setTransform(s.tx);
        g.setClip(s.clip);
    }

    public void restoreToCount(int n) {
        while (stack.size() >= n && !stack.isEmpty()) restore();
    }

    public void scale(float sx, float sy) { g.scale(sx, sy); }

    public void scale(float sx, float sy, float px, float py) {
        g.translate(px, py);
        g.scale(sx, sy);
        g.translate(-px, -py);
    }

    public void rotate(float deg, float px, float py) { g.rotate(Math.toRadians(deg), px, py); }

    public boolean clipRect(float l, float t, float r, float b) {
        g.clip(new Rectangle2D.Float(l, t, r - l, b - t));
        return true;
    }

    public boolean clipPath(Path p) {
        g.clip(p.p);
        return true;
    }

    public void drawColor(int c) {
        AffineTransform k = g.getTransform();
        g.setTransform(new AffineTransform());
        g.setComposite(AlphaComposite.SrcOver);
        g.setColor(Color.awt(c));
        g.fillRect(0, 0, base.getWidth(), base.getHeight());
        g.setTransform(k);
    }

    private void apply(Paint p) {
        if (p.xfer instanceof PorterDuffXfermode && ((PorterDuffXfermode) p.xfer).mode == PorterDuff.Mode.DST_IN)
            g.setComposite(AlphaComposite.DstIn);
        else g.setComposite(AlphaComposite.SrcOver);
        if (p.shader != null) g.setPaint(p.shader.awt());
        else g.setColor(Color.awt(p.color));
        int cap = p.cap == Paint.Cap.ROUND ? BasicStroke.CAP_ROUND : p.cap == Paint.Cap.SQUARE ? BasicStroke.CAP_SQUARE : BasicStroke.CAP_BUTT;
        float[] dash = p.effect instanceof DashPathEffect ? ((DashPathEffect) p.effect).d : null;
        g.setStroke(new BasicStroke(Math.max(0.01f, p.strokeWidth), cap, BasicStroke.JOIN_ROUND, 10f, dash, 0f));
    }

    private void shape(Shape s, Paint p) {
        apply(p);
        if (p.style == Paint.Style.STROKE) g.draw(s);
        else g.fill(s);
        g.setComposite(AlphaComposite.SrcOver);
    }

    public void drawRect(float l, float t, float r, float b, Paint p) { shape(new Rectangle2D.Float(l, t, r - l, b - t), p); }

    public void drawRoundRect(RectF r, float rx, float ry, Paint p) {
        shape(new RoundRectangle2D.Float(r.left, r.top, r.width(), r.height(), 2 * rx, 2 * ry), p);
    }

    public void drawCircle(float cx, float cy, float rad, Paint p) { shape(new Ellipse2D.Float(cx - rad, cy - rad, 2 * rad, 2 * rad), p); }

    public void drawArc(RectF o, float start, float sweep, boolean useCenter, Paint p) {
        shape(new Arc2D.Float(o.left, o.top, o.width(), o.height(), -start, -sweep, useCenter ? Arc2D.PIE : Arc2D.OPEN), p);
    }

    public void drawBitmap(Bitmap b, Object src, RectF dst, Paint p) {
        BufferedImage img = b.img;
        if (p != null && p.filter instanceof ColorMatrixColorFilter) img = filtered(img, ((ColorMatrixColorFilter) p.filter).m);
        g.setComposite(AlphaComposite.SrcOver);
        AffineTransform at = new AffineTransform();
        at.translate(dst.left, dst.top);
        at.scale(dst.width() / img.getWidth(), dst.height() / img.getHeight());
        g.drawImage(img, at, null);
    }

    private static BufferedImage filtered(BufferedImage in, float[] m) {
        BufferedImage o = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < in.getHeight(); y++)
            for (int x = 0; x < in.getWidth(); x++) {
                int c = in.getRGB(x, y), a = c >>> 24, r = (c >> 16) & 255, gg = (c >> 8) & 255, b = c & 255;
                int[] v = new int[4];
                for (int k = 0; k < 4; k++)
                    v[k] = Math.max(0, Math.min(255, Math.round(m[k * 5] * r + m[k * 5 + 1] * gg + m[k * 5 + 2] * b + m[k * 5 + 3] * a + m[k * 5 + 4])));
                o.setRGB(x, y, (v[3] << 24) | (v[0] << 16) | (v[1] << 8) | v[2]);
            }
        return o;
    }

    public void drawText(String s, float x, float y, Paint p) {
        float w = p.measureText(s);
        if (p.align == Paint.Align.CENTER) x -= w / 2;
        else if (p.align == Paint.Align.RIGHT) x -= w;
        if (p.shColor != 0) {
            Paint sh = new Paint();
            sh.color = p.shColor;
            text(s, x + p.shDx, y + p.shDy, p, sh);
        }
        text(s, x, y, p, p);
    }

    private void text(String s, float x, float y, Paint fontP, Paint colorP) {
        apply(colorP);
        List<Object[]> runs = fontP.runs(s);
        for (Object[] r : runs) {
            Font f = (Font) r[1];
            g.setFont(f);
            g.drawString((String) r[0], x, y);
            x += (float) f.getStringBounds((String) r[0], Paint.FRC).getWidth();
        }
    }
}
