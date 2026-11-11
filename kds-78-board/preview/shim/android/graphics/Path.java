package android.graphics;
public class Path {
    public enum Direction { CW, CCW }
    final java.awt.geom.Path2D.Float p = new java.awt.geom.Path2D.Float();
    public void reset() { p.reset(); }
    public void addRoundRect(RectF r, float rx, float ry, Direction d) { p.append(new java.awt.geom.RoundRectangle2D.Float(r.left, r.top, r.width(), r.height(), 2 * rx, 2 * ry), false); }
}
