package android.graphics;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.util.HashMap;
import java.util.Map;

/** Preview shim (Java 2D): the parts of Paint the Dosa Board uses. */
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1, FILTER_BITMAP_FLAG = 2, SUBPIXEL_TEXT_FLAG = 128;

    public enum Style { FILL, STROKE, FILL_AND_STROKE }

    public enum Align { LEFT, CENTER, RIGHT }

    public enum Cap { BUTT, ROUND, SQUARE }

    public static class FontMetrics {
        public float ascent, descent, top, bottom, leading;
    }

    int color = 0xFF000000;
    Style style = Style.FILL;
    Align align = Align.LEFT;
    Cap cap = Cap.BUTT;
    float strokeWidth = 0, textSize = 12, letter = 0;
    Typeface tf = Typeface.DEFAULT;
    Shader shader;
    PathEffect effect;
    ColorFilter filter;
    Xfermode xfer;
    float shR, shDx, shDy;
    int shColor;

    static final FontRenderContext FRC = new FontRenderContext(null, true, true);
    static final Font FALLBACK = new Font("DejaVu Sans", Font.BOLD, 1);

    public Paint() {
    }

    public Paint(int flags) {
    }

    public void setColor(int c) { color = c; }
    public int getColor() { return color; }
    public void setStyle(Style s) { style = s; }
    public void setStrokeWidth(float w) { strokeWidth = w; }
    public void setStrokeCap(Cap c) { cap = c; }
    public void setTextAlign(Align a) { align = a; }
    public void setTextSize(float s) { textSize = s; }
    public void setTypeface(Typeface t) { tf = t == null ? Typeface.DEFAULT : t; }
    public void setLetterSpacing(float em) { letter = em; }
    public Shader setShader(Shader s) { shader = s; return s; }
    public PathEffect setPathEffect(PathEffect e) { effect = e; return e; }
    public ColorFilter setColorFilter(ColorFilter f) { filter = f; return f; }
    public Xfermode setXfermode(Xfermode x) { xfer = x; return x; }
    public void setShadowLayer(float r, float dx, float dy, int c) { shR = r; shDx = dx; shDy = dy; shColor = c; }
    public void clearShadowLayer() { shR = 0; shColor = 0; }

    Font font() {
        Map<TextAttribute, Object> a = new HashMap<TextAttribute, Object>();
        a.put(TextAttribute.TRACKING, letter);
        a.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        return tf.font.deriveFont(textSize).deriveFont(a);
    }

    Font fallback() {
        Map<TextAttribute, Object> a = new HashMap<TextAttribute, Object>();
        a.put(TextAttribute.TRACKING, letter);
        return FALLBACK.deriveFont(textSize).deriveFont(a);
    }

    /** Runs of text with the font that has the glyphs (Android falls back to system fonts too). */
    java.util.List<Object[]> runs(String s) {
        java.util.List<Object[]> out = new java.util.ArrayList<Object[]>();
        Font f = font(), fb = fallback();
        StringBuilder sb = new StringBuilder();
        Font cur = null;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            Font use = f.canDisplay(cp) ? f : fb;
            if (cur != null && use != cur) {
                out.add(new Object[]{sb.toString(), cur});
                sb.setLength(0);
            }
            cur = use;
            sb.appendCodePoint(cp);
            i += Character.charCount(cp);
        }
        if (sb.length() > 0) out.add(new Object[]{sb.toString(), cur});
        return out;
    }

    public float measureText(String s) {
        float w = 0;
        for (Object[] r : runs(s)) w += (float) ((Font) r[1]).getStringBounds((String) r[0], FRC).getWidth();
        return w;
    }

    public FontMetrics getFontMetrics() {
        FontMetrics m = new FontMetrics();
        getFontMetrics(m);
        return m;
    }

    public float getFontMetrics(FontMetrics m) {
        java.awt.font.LineMetrics lm = font().getLineMetrics("Hg", FRC);
        m.ascent = -lm.getAscent();
        m.descent = lm.getDescent();
        m.top = m.ascent;
        m.bottom = m.descent;
        m.leading = lm.getLeading();
        return m.descent - m.ascent;
    }
}
