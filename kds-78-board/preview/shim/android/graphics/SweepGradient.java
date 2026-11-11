package android.graphics;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
/** Android's sweep (conic) gradient: from 3 o'clock, clockwise; the local matrix rotates it. */
public class SweepGradient extends Shader {
    final float cx, cy; final int[] c; final float[] p;
    public SweepGradient(float cx, float cy, int[] c, float[] p) { this.cx = cx; this.cy = cy; this.c = c; this.p = fr(p, c.length); }
    java.awt.Paint awt() {
        final AffineTransform loc = local.t;
        return new java.awt.Paint() {
            public int getTransparency() { return TRANSLUCENT; }
            public PaintContext createContext(ColorModel cm, Rectangle db, Rectangle2D ub, AffineTransform xf, RenderingHints h) {
                final AffineTransform inv;
                try { AffineTransform all = new AffineTransform(xf); all.concatenate(loc); inv = all.createInverse(); } catch (Exception e) { throw new RuntimeException(e); }
                return new PaintContext() {
                    public void dispose() {}
                    public ColorModel getColorModel() { return ColorModel.getRGBdefault(); }
                    public Raster getRaster(int x, int y, int w, int hh) {
                        WritableRaster r = getColorModel().createCompatibleWritableRaster(w, hh);
                        int[] px = new int[w * hh];
                        Point2D.Double pt = new Point2D.Double();
                        for (int j = 0; j < hh; j++) for (int i = 0; i < w; i++) {
                            pt.setLocation(x + i + .5, y + j + .5); inv.transform(pt, pt);
                            double a = Math.atan2(pt.y - cy, pt.x - cx); if (a < 0) a += 2 * Math.PI;
                            px[j * w + i] = at((float) (a / (2 * Math.PI)));
                        }
                        r.setDataElements(0, 0, w, hh, px);
                        return r;
                    }
                };
            }
        };
    }
    int at(float f) {
        for (int i = 1; i < c.length; i++) if (f <= p[i]) {
            float k = (f - p[i - 1]) / Math.max(1e-6f, p[i] - p[i - 1]);
            return mix(c[i - 1], c[i], k);
        }
        return c[c.length - 1];
    }
    static int mix(int a, int b, float k) {
        int o = 0;
        for (int s = 0; s < 32; s += 8) { int x = (a >>> s) & 255, y = (b >>> s) & 255; o |= (Math.round(x + (y - x) * k) & 255) << s; }
        return o;
    }
}
