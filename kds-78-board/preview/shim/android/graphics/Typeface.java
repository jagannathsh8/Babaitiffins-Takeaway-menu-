package android.graphics;
public class Typeface {
    final java.awt.Font font;
    Typeface(java.awt.Font f) { font = f; }
    public static final Typeface DEFAULT = new Typeface(new java.awt.Font("DejaVu Sans", java.awt.Font.PLAIN, 1));
    public static final Typeface DEFAULT_BOLD = new Typeface(new java.awt.Font("DejaVu Sans", java.awt.Font.BOLD, 1));
    public static Typeface createFromFile(String path) {
        try { return new Typeface(java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, new java.io.File(path))); } catch (Exception e) { throw new RuntimeException(e); }
    }
}
