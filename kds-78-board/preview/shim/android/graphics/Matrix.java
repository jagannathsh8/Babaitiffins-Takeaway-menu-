package android.graphics;
import java.awt.geom.AffineTransform;
public class Matrix {
    final AffineTransform t = new AffineTransform();
    public void reset() { t.setToIdentity(); }
    public void setRotate(float deg, float px, float py) { t.setToRotation(Math.toRadians(deg), px, py); }
    public void setScale(float sx, float sy) { t.setToScale(sx, sy); }
    public boolean postTranslate(float dx, float dy) { t.preConcatenate(AffineTransform.getTranslateInstance(dx, dy)); return true; }
}
