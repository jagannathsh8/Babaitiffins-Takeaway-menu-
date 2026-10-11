package com.pp.kds.scan;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java KOT barcode decoder (testable on a JVM with ZXing). Decodes only part of a camera
 * frame (Y plane).
 *
 * Speed: KOTs always carry the same kind of barcode. The first good read teaches the decoder that
 * kind ({@link #learned()}); after that every frame first gets one quick pass for just that kind,
 * and the slower passes (all kinds, the other binariser, sideways) only run every few frames.
 */
public final class KotDecoder {

    /** Scan window as a fraction of the portrait screen. */
    public static final float WIN_W = 0.86f, WIN_H = 0.24f;

    private static final BarcodeFormat[] FORMATS = {BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
            BarcodeFormat.CODE_93, BarcodeFormat.ITF, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A,
            BarcodeFormat.CODABAR};

    private static volatile BarcodeFormat learned;

    /** Kind of barcode the KOTs use, once seen ("CODE_128"), or null. */
    public static String learned() {
        BarcodeFormat f = learned;
        return f == null ? null : f.name();
    }

    /** Restores the remembered kind (saved by the app), ignoring unknown names. */
    public static void setLearned(String name) {
        if (name == null) return;
        for (BarcodeFormat f : FORMATS) if (f.name().equals(name)) learned = f;
    }

    private final MultiFormatReader reader = new MultiFormatReader();
    private final MultiFormatReader quick = new MultiFormatReader();
    private BarcodeFormat quickFor;
    private byte[] rot;
    private int frame;

    public KotDecoder() {
        reader.setHints(hints(FORMATS));
    }

    private static Map<DecodeHintType, Object> hints(BarcodeFormat... f) {
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<BarcodeFormat>();
        Collections.addAll(formats, f);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        return hints;
    }

    /**
     * Camera screen: the window is a horizontal band on the portrait screen; with the camera
     * rotated 90 degrees that is a vertical band in the sensor frame. Try it upright, then sideways.
     */
    public String decode(byte[] y, int w, int h) {
        frame++;
        int cw = Math.min(w, Math.round(w * WIN_H * 1.25f));   // sensor x  <-> screen y
        int ch = Math.min(h, Math.round(h * WIN_W * 1.08f));   // sensor y  <-> screen x
        int x0 = (w - cw) / 2, y0 = (h - ch) / 2;
        byte[] r = rotate(y, w, x0, y0, cw, ch);
        LuminanceSource upright = new PlanarYUVLuminanceSource(r, ch, cw, 0, 0, ch, cw, false);
        String t = quickPass(upright);
        if (t != null || (learned != null && frame % 3 != 0)) return t;
        t = tryDecode(reader, new BinaryBitmap(new GlobalHistogramBinarizer(upright)));
        if (t == null) t = tryDecode(reader, new BinaryBitmap(new HybridBinarizer(upright)));
        if (t == null) {
            LuminanceSource sideways = new PlanarYUVLuminanceSource(y, w, h, x0, y0, cw, ch, false);
            t = tryDecode(reader, new BinaryBitmap(new GlobalHistogramBinarizer(sideways)));
        }
        return t;
    }

    /**
     * Any camera, any way round (rider screen camera): the middle of the frame, as the sensor gives
     * it and turned a quarter, so a KOT held level or upright both read.
     */
    public String decodeAnyWay(byte[] y, int w, int h) {
        frame++;
        int cw = Math.round(w * 0.90f), ch = h;               // full height: an upright KOT is tall
        int x0 = (w - cw) / 2, y0 = (h - ch) / 2;
        LuminanceSource level = new PlanarYUVLuminanceSource(y, w, h, x0, y0, cw, ch, false);
        String t = quickPass(level);
        if (t != null) return t;
        boolean slow = learned == null || frame % 3 == 0;
        if (frame % 2 == 0 || slow) {
            byte[] r = rotate(y, w, x0, y0, cw, ch);
            LuminanceSource turned = new PlanarYUVLuminanceSource(r, ch, cw, 0, 0, ch, cw, false);
            t = quickPass(turned);
            if (t == null && slow) t = tryDecode(reader, new BinaryBitmap(new GlobalHistogramBinarizer(turned)));
            if (t != null) return t;
        }
        if (slow) {
            t = tryDecode(reader, new BinaryBitmap(new GlobalHistogramBinarizer(level)));
            if (t == null) t = tryDecode(reader, new BinaryBitmap(new HybridBinarizer(level)));
        }
        return t;
    }

    /** One fast pass for the learned kind only (nothing until a kind is learned). */
    private String quickPass(LuminanceSource src) {
        BarcodeFormat f = learned;
        if (f == null) return null;
        if (quickFor != f) {
            quick.setHints(hints(f));
            quickFor = f;
        }
        return tryDecode(quick, new BinaryBitmap(new GlobalHistogramBinarizer(src)));
    }

    /** The band x0..x0+cw, y0..y0+ch of the frame turned a quarter (sensor columns become rows). */
    private byte[] rotate(byte[] y, int w, int x0, int y0, int cw, int ch) {
        if (rot == null || rot.length != cw * ch) rot = new byte[cw * ch];
        byte[] r = rot;
        for (int px = 0; px < cw; px++) {
            int out = px * ch + (ch - 1);
            int in = y0 * w + x0 + px;
            for (int py = 0; py < ch; py++) {
                r[out - py] = y[in];
                in += w;
            }
        }
        return r;
    }

    private static String tryDecode(MultiFormatReader rd, BinaryBitmap bmp) {
        try {
            Result res = rd.decodeWithState(bmp);
            if (res == null) return null;
            if (res.getBarcodeFormat() != null && learned != res.getBarcodeFormat()) learned = res.getBarcodeFormat();
            return res.getText();
        } catch (Exception e) {
            return null;
        } finally {
            rd.reset();
        }
    }
}
