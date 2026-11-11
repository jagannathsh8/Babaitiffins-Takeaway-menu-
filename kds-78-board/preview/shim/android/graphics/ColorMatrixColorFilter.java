package android.graphics;
public class ColorMatrixColorFilter extends ColorFilter { final float[] m; public ColorMatrixColorFilter(ColorMatrix c) { m = c.m; } }
