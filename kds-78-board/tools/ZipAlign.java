import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * zipalign for an unsigned APK (Android's own zipalign isn't reachable here): every stored entry's
 * data starts on a 4-byte boundary (.so: 4096), with Android's alignment extra field (0xd935), which
 * apksig keeps when it signs. Compressed entries are written compressed again.
 *
 *   java ZipAlign <in.apk> <out.apk>
 */
public class ZipAlign {
    static final class Counting extends FilterOutputStream {
        long n;

        Counting(OutputStream o) {
            super(o);
        }

        @Override public void write(int b) throws IOException {
            out.write(b);
            n++;
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            n += len;
        }
    }

    public static void main(String[] a) throws Exception {
        ZipFile in = new ZipFile(a[0]);
        Counting cnt = new Counting(new BufferedOutputStream(new FileOutputStream(a[1])));
        ZipOutputStream out = new ZipOutputStream(cnt);
        byte[] buf = new byte[1 << 16];
        for (Enumeration<? extends ZipEntry> en = in.entries(); en.hasMoreElements(); ) {
            ZipEntry src = en.nextElement();
            ZipEntry e = new ZipEntry(src.getName());
            e.setTime(src.getTime());
            byte[] data = readAll(in.getInputStream(src), buf);
            if (src.getMethod() == ZipEntry.STORED) {
                e.setMethod(ZipEntry.STORED);
                e.setSize(data.length);
                e.setCompressedSize(data.length);
                CRC32 crc = new CRC32();
                crc.update(data);
                e.setCrc(crc.getValue());
                int align = src.getName().endsWith(".so") ? 4096 : 4;
                out.flush();
                long base = cnt.n + 30 + src.getName().getBytes(StandardCharsets.UTF_8).length + 6;
                int pad = (int) ((align - base % align) % align);
                byte[] extra = new byte[6 + pad];
                extra[0] = 0x35;
                extra[1] = (byte) 0xd9;
                extra[2] = (byte) (2 + pad);
                extra[3] = (byte) ((2 + pad) >> 8);
                extra[4] = (byte) align;
                extra[5] = (byte) (align >> 8);
                e.setExtra(extra);
            } else {
                e.setMethod(ZipEntry.DEFLATED);
            }
            out.putNextEntry(e);
            out.write(data);
            out.closeEntry();
        }
        out.close();
        in.close();
    }

    static byte[] readAll(InputStream in, byte[] buf) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }
}
