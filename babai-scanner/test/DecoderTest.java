import com.babai.scanner.Decoder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.Random;

/** java DecoderTest: barcodes drawn into a 1280x720 "camera frame", with blur / low contrast / noise / rotation. */
public class DecoderTest {
    static int fails = 0;
    static void check(String n, boolean ok, Object info) { System.out.println((ok ? "ok   " : "FAIL ") + n + (ok ? "" : " " + info)); if (!ok) fails++; }

    static byte[] frame(BitMatrix m, int w, int h, boolean upright, int contrast, int blur, int noise, long seed) {
        byte[] y = new byte[w * h];
        int bw = upright ? m.getHeight() : m.getWidth(), bh = upright ? m.getWidth() : m.getHeight();
        int ox = (w - bw) / 2, oy = (h - bh) / 2;
        int[] img = new int[w * h];
        int light = 128 + contrast, dark = 128 - contrast;
        for (int i = 0; i < w * h; i++) img[i] = light;
        for (int yy = 0; yy < bh; yy++) for (int xx = 0; xx < bw; xx++) {
            boolean black = upright ? m.get(yy, bw - 1 - xx) : m.get(xx, yy);
            int px = ox + xx, py = oy + yy;
            if (px >= 0 && px < w && py >= 0 && py < h) img[py * w + px] = black ? dark : light;
        }
        for (int pass = 0; pass < blur; pass++) {           // box blur
            int[] out = img.clone();
            for (int yy = 1; yy < h - 1; yy++) for (int xx = 1; xx < w - 1; xx++) {
                int s = 0; for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) s += img[(yy + dy) * w + xx + dx];
                out[yy * w + xx] = s / 9;
            }
            img = out;
        }
        Random r = new Random(seed);
        for (int i = 0; i < w * h; i++) y[i] = (byte) Math.max(0, Math.min(255, img[i] + (int) (r.nextGaussian() * noise)));
        return y;
    }

    static String readOver(Decoder d, byte[] y, int w, int h, int frames) {
        for (int i = 0; i < frames; i++) { String t = d.decode(y, w, h); if (t != null) return t; }
        return null;
    }

    public static void main(String[] a) throws Exception {
        int W = 1280, H = 720;
        String id = "250787919580744";
        BitMatrix bc = new Code128Writer().encode(id, BarcodeFormat.CODE_128, 560, 120);
        check("sharp code128", id.equals(readOver(new Decoder(false), frame(bc, W, H, false, 100, 0, 3, 1), W, H, 3)), "");
        check("blurred + faded + noisy", id.equals(readOver(new Decoder(false), frame(bc, W, H, false, 45, 2, 12, 2), W, H, 12)), "");
        check("held upright (turned a quarter)", id.equals(readOver(new Decoder(false), frame(bc, W, H, true, 90, 1, 6, 3), W, H, 6)), "");
        Decoder d = new Decoder(false);
        readOver(d, frame(bc, W, H, false, 100, 0, 3, 4), W, H, 3);
        check("kind learned", "CODE_128".equals(d.learned()), d.learned());
        long t0 = System.nanoTime(); int n = 0;
        byte[] f = frame(bc, W, H, false, 80, 1, 6, 5);
        for (int i = 0; i < 60; i++) if (d.decode(f, W, H) != null) n++;
        double ms = (System.nanoTime() - t0) / 1e6 / 60;
        check("fast after learning (" + String.format("%.1f", ms) + " ms/frame on this PC, " + n + "/60 read)", n >= 55, n);
        String pair = "BABAISCAN1;h=192.168.1.20:8790;db=x.firebasedatabase.app;o=vtr;k=nBrASzG84KUoUYG2b8n26UUB;n=Counter phone;on=VTR";
        BitMatrix qr = new QRCodeWriter().encode(pair, BarcodeFormat.QR_CODE, 420, 420);
        check("pairing QR", pair.equals(readOver(new Decoder(true), frame(qr, W, H, false, 100, 0, 4, 6), W, H, 4)), "");
        byte[] empty = frame(new BitMatrix(10, 10), W, H, false, 60, 0, 10, 7);
        check("nothing in view -> nothing", readOver(new Decoder(true), empty, W, H, 6) == null, "");
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
