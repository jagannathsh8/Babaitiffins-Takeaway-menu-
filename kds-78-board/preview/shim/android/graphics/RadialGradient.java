package android.graphics;
import java.awt.MultipleGradientPaint;
public class RadialGradient extends Shader {
    final float cx, cy, r; final int[] c; final float[] p;
    public RadialGradient(float cx, float cy, float r, int[] c, float[] p, TileMode m) { this.cx = cx; this.cy = cy; this.r = r; this.c = c; this.p = p; }
    java.awt.Paint awt() { return new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Float(cx, cy), r, new java.awt.geom.Point2D.Float(cx, cy), fr(p, c.length), cols(c),
            MultipleGradientPaint.CycleMethod.NO_CYCLE, MultipleGradientPaint.ColorSpaceType.SRGB, local.t); }
}
