package android.view;
import android.content.Context;
import android.graphics.Canvas;
/** Preview shim: just enough of View for BoardView. */
public class View {
    public static final int LAYER_TYPE_HARDWARE = 2;
    private int w, h;
    public View(Context c) {}
    public void setSize(int w, int h) { this.w = w; this.h = h; }
    public final int getWidth() { return w; }
    public final int getHeight() { return h; }
    public void invalidate() {}
    public void postInvalidateDelayed(long ms) {}
    public void setLayerType(int t, Object p) {}
    public void setFocusable(boolean b) {}
    public void setFocusableInTouchMode(boolean b) {}
    public void setKeepScreenOn(boolean b) {}
    protected void onDraw(Canvas c) {}
    public void draw(Canvas c) { onDraw(c); }
}
