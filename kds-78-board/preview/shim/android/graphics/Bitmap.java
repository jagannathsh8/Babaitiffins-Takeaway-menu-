package android.graphics;
public class Bitmap {
    public final java.awt.image.BufferedImage img;
    public Bitmap(java.awt.image.BufferedImage i) { img = i; }
    public int getWidth() { return img.getWidth(); }
    public int getHeight() { return img.getHeight(); }
}
