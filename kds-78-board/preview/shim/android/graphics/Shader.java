package android.graphics;
public abstract class Shader {
    public enum TileMode { CLAMP, REPEAT, MIRROR }
    protected final Matrix local = new Matrix();
    public void setLocalMatrix(Matrix m) { local.t.setTransform(m.t); }
    abstract java.awt.Paint awt();
    static java.awt.Color[] cols(int[] c) { java.awt.Color[] o = new java.awt.Color[c.length]; for (int i = 0; i < c.length; i++) o[i] = Color.awt(c[i]); return o; }
    static float[] fr(float[] p, int n) {
        float[] o = new float[n];
        for (int i = 0; i < n; i++) o[i] = p == null ? i / (float) (n - 1) : p[i];
        for (int i = 1; i < n; i++) if (o[i] <= o[i - 1]) o[i] = Math.min(1f, o[i - 1] + 1e-4f);
        return o;
    }
}
