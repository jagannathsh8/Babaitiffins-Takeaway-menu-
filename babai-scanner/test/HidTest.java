import com.babai.scanner.Hid;
/** java HidTest: USB keyboard reports -> scanned text. */
public class HidTest {
    static byte[] rep(int mod, int key) { return new byte[]{(byte) mod, 0, (byte) key, 0, 0, 0, 0, 0}; }
    public static void main(String[] a) {
        Hid h = new Hid(); String out = null; int fails = 0;
        // "2507ab" + Enter, every key followed by "all up"; one key repeated while held
        int[] keys = {0x1f, 0x22, 0x27, 0x24, 0x24, 0x04, 0x05};
        for (int i = 0; i < keys.length; i++) {
            if (i == 4) { out = h.feed(rep(0, keys[i]), 8); continue; } // held: same report again, no up in between
            out = h.feed(rep(0, keys[i]), 8);
            if (i != 3) h.feed(rep(0, 0), 8);   // key 3 stays held: the next report repeats it
        }
        out = h.feed(rep(0, 0x28), 8);
        if (!"2507ab".equals(out)) { fails++; System.out.println("FAIL text " + out); }
        h.feed(rep(0, 0), 8);
        h.feed(rep(0x02, 0x04), 8); h.feed(rep(0, 0), 8); h.feed(rep(0, 0x1e), 8); h.feed(rep(0, 0), 8);
        if (h.feed(rep(0, 0x28), 8) != null) { fails++; System.out.println("FAIL short code accepted"); }
        h.feed(rep(0, 0), 8);
        for (int k : new int[]{0x59, 0x5a, 0x5b, 0x62}) { h.feed(rep(0, k), 8); h.feed(rep(0, 0), 8); }
        out = h.feed(rep(0, 0x58), 8);
        if (!"1230".equals(out)) { fails++; System.out.println("FAIL keypad " + out); }
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
