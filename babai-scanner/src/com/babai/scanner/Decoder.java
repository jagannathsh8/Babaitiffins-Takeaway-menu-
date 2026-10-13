package com.babai.scanner;

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
 * Reads KOT barcodes (and the pairing QR code) from camera frames (Y plane), plain Java.
 *
 * Fast path: the kind of barcode the KOTs use is learned from the first read; every frame then
 * gets one quick pass for that kind, level and turned a quarter. Slower passes (every kind, the
 * other binariser, "try harder" for blurred / faded prints) run on every second / third frame,
 * so the camera keeps its full frame rate.
 */
public final class Decoder {

    private static final BarcodeFormat[] ONE_D = {BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
            BarcodeFormat.ITF, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.CODABAR};

    private final MultiFormatReader all = new MultiFormatReader();
    private final MultiFormatReader hard = new MultiFormatReader();
    private final MultiFormatReader quick = new MultiFormatReader();
    private BarcodeFormat learned, quickFor;
    private byte[] rot;
    private int frame;
    private final boolean qr;

    /** qr: also read QR codes (pairing screen). */
    public Decoder(boolean qr) {
        this.qr = qr;
        List<BarcodeFormat> f = new ArrayList<BarcodeFormat>();
        Collections.addAll(f, ONE_D);
        if (qr) f.add(BarcodeFormat.QR_CODE);
        all.setHints(hints(f, false));
        hard.setHints(hints(f, true));
    }

    private static Map<DecodeHintType, Object> hints(List<BarcodeFormat> f, boolean tryHarder) {
        Map<DecodeHintType, Object> h = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        h.put(DecodeHintType.POSSIBLE_FORMATS, f);
        if (tryHarder) h.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return h;
    }

    public String learned() {
        return learned == null ? null : learned.name();
    }

    public void setLearned(String name) {
        if (name == null) return;
        for (BarcodeFormat f : ONE_D) if (f.name().equals(name)) learned = f;
    }

    private byte[] avg;

    /**
     * Bars run across the rows, so averaging every 6 rows (step 3) takes out the camera's grain in
     * low light without blurring the bars. The result is a third of the height (also faster).
     */
    private byte[] smoothRows(byte[] y, int w, int h, int x0, int cw) {
        int oh = Math.max(1, (h - 6) / 3 + 1);
        if (avg == null || avg.length != cw * oh) avg = new byte[cw * oh];
        for (int r = 0; r < oh; r++) {
            int top = Math.min(r * 3, h - 6 < 0 ? 0 : h - 6), rows = Math.min(6, h - top);
            int o = r * cw;
            for (int x = 0; x < cw; x++) {
                int s = 0, i = top * w + x0 + x;
                for (int k = 0; k < rows; k++, i += w) s += y[i] & 0xff;
                avg[o + x] = (byte) (s / rows);
            }
        }
        return avg;
    }

    /** One frame: y = luminance, w x h. Returns the text or null. */
    public String decode(byte[] y, int w, int h) {
        frame++;
        // middle 90% of the width, full height: a KOT held level or upright both fit
        int cw = Math.max(16, Math.round(w * 0.9f)), ch = h, x0 = (w - cw) / 2, y0 = 0;
        byte[] sm = smoothRows(y, w, h, x0, cw);
        int sh = sm.length / cw;
        LuminanceSource level = new PlanarYUVLuminanceSource(sm, cw, sh, 0, 0, cw, sh, false);
        String t = quickPass(level);
        if (t != null) return t;
        boolean slow = learned == null || frame % 3 == 0;
        if (frame % 2 == 0 || slow) {
            byte[] r = rotate(y, w, x0, y0, cw, ch);
            byte[] rs = smoothTurned(r, ch, cw);
            int rh = rs.length / ch;
            LuminanceSource turned = new PlanarYUVLuminanceSource(rs, ch, rh, 0, 0, ch, rh, false);
            t = quickPass(turned);
            if (t == null && slow) t = read(all, new BinaryBitmap(new HybridBinarizer(turned)));
            if (t != null) return t;
        }
        if (slow) {
            t = read(all, new BinaryBitmap(new HybridBinarizer(level)));
            if (t == null) t = read(all, new BinaryBitmap(new GlobalHistogramBinarizer(level)));
        }
        if (t == null && frame % 6 == 0) {
            // blurred / faded / crumpled print: the slow, thorough pass, now and then
            t = read(hard, new BinaryBitmap(new HybridBinarizer(level)));
        }
        if (t == null && qr && frame % 2 == 1) {
            // the pairing QR code needs the full picture, not the row averages
            t = read(all, new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(y, w, h, 0, 0, w, h, false))));
        }
        return t;
    }

    private byte[] avgT;

    private byte[] smoothTurned(byte[] r, int w, int h) {
        int oh = Math.max(1, (h - 6) / 3 + 1);
        if (avgT == null || avgT.length != w * oh) avgT = new byte[w * oh];
        for (int row = 0; row < oh; row++) {
            int top = Math.min(row * 3, Math.max(0, h - 6)), rows = Math.min(6, h - top);
            for (int x = 0; x < w; x++) {
                int s = 0, i = top * w + x;
                for (int k = 0; k < rows; k++, i += w) s += r[i] & 0xff;
                avgT[row * w + x] = (byte) (s / rows);
            }
        }
        return avgT;
    }

    private String quickPass(LuminanceSource src) {
        BarcodeFormat f = learned;
        if (f == null) return null;
        if (quickFor != f) {
            List<BarcodeFormat> l = new ArrayList<BarcodeFormat>();
            l.add(f);
            if (qr) l.add(BarcodeFormat.QR_CODE);
            quick.setHints(hints(l, false));
            quickFor = f;
        }
        String t = read(quick, new BinaryBitmap(new GlobalHistogramBinarizer(src)));
        return t != null ? t : read(quick, new BinaryBitmap(new HybridBinarizer(src)));
    }

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

    private String read(MultiFormatReader rd, BinaryBitmap bmp) {
        try {
            Result res = rd.decodeWithState(bmp);
            if (res == null) return null;
            BarcodeFormat f = res.getBarcodeFormat();
            if (f != null && f != BarcodeFormat.QR_CODE) learned = f;
            return res.getText();
        } catch (Exception e) {
            return null;
        } finally {
            rd.reset();
        }
    }
}
