package android.graphics;
public class Color {
    public static final int WHITE = 0xFFFFFFFF, BLACK = 0xFF000000;
    public static int alpha(int c) { return c >>> 24; }
    public static int red(int c) { return (c >> 16) & 0xFF; }
    public static int green(int c) { return (c >> 8) & 0xFF; }
    public static int blue(int c) { return c & 0xFF; }
    static java.awt.Color awt(int c) { return new java.awt.Color(red(c), green(c), blue(c), alpha(c)); }
}
