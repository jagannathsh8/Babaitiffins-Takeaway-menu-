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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java KOT barcode decoder (testable on a JVM with ZXing). Decodes only the scan window of a
 * camera frame (NV21 / Y plane, sensor in landscape, shown rotated 90 degrees on a portrait screen).
 */
public final class KotDecoder {

    /** Scan window as a fraction of the portrait screen. */
    public static final float WIN_W = 0.86f, WIN_H = 0.24f;

    private final MultiFormatReader reader = new MultiFormatReader();
    private byte[] rot;

    public KotDecoder() {
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<BarcodeFormat>();
        formats.add(BarcodeFormat.CODE_128);
        formats.add(BarcodeFormat.CODE_39);
        formats.add(BarcodeFormat.CODE_93);
        formats.add(BarcodeFormat.ITF);
        formats.add(BarcodeFormat.EAN_13);
        formats.add(BarcodeFormat.EAN_8);
        formats.add(BarcodeFormat.UPC_A);
        formats.add(BarcodeFormat.CODABAR);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        reader.setHints(hints);
    }

    /**
     * The window is a horizontal band on the portrait screen; with the camera rotated 90 degrees
     * that is a vertical band in the sensor frame. Try it upright (rotated), then sideways.
     */
    public String decode(byte[] y, int w, int h) {
        int cw = Math.min(w, Math.round(w * WIN_H * 1.25f));   // sensor x  <-> screen y
        int ch = Math.min(h, Math.round(h * WIN_W * 1.08f));   // sensor y  <-> screen x
        int x0 = (w - cw) / 2, y0 = (h - ch) / 2;
        if (rot == null || rot.length != cw * ch) rot = new byte[cw * ch];
        byte[] r = rot;
        for (int px = 0; px < cw; px++) {                      // rotate the band to screen orientation
            int out = px * ch + (ch - 1);
            int in = y0 * w + x0 + px;
            for (int py = 0; py < ch; py++) {
                r[out - py] = y[in];
                in += w;
            }
        }
        LuminanceSource upright = new PlanarYUVLuminanceSource(r, ch, cw, 0, 0, ch, cw, false);
        String t = tryDecode(new BinaryBitmap(new GlobalHistogramBinarizer(upright)));
        if (t == null) t = tryDecode(new BinaryBitmap(new HybridBinarizer(upright)));
        if (t == null) {
            LuminanceSource sideways = new PlanarYUVLuminanceSource(y, w, h, x0, y0, cw, ch, false);
            t = tryDecode(new BinaryBitmap(new GlobalHistogramBinarizer(sideways)));
        }
        return t;
    }

    private String tryDecode(BinaryBitmap bmp) {
        try {
            Result res = reader.decodeWithState(bmp);
            return res == null ? null : res.getText();
        } catch (Exception e) {
            return null;
        } finally {
            reader.reset();
        }
    }
}
