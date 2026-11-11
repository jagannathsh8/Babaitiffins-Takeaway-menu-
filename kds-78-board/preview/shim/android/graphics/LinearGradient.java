package android.graphics;
import java.awt.MultipleGradientPaint;
public class LinearGradient extends Shader {
    final float x0, y0, x1, y1; final int[] c; final float[] p;
    public LinearGradient(float x0, float y0, float x1, float y1, int[] c, float[] p, TileMode m) { this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.c = c; this.p = p; }
    java.awt.Paint awt() { return new java.awt.LinearGradientPaint(new java.awt.geom.Point2D.Float(x0, y0), new java.awt.geom.Point2D.Float(x1, y1), fr(p, c.length), cols(c),
            MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, local.t); }
}
