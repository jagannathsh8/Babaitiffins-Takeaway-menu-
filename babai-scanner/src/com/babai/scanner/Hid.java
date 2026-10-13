package com.babai.scanner;

/**
 * USB barcode scanners act as a keyboard: every character arrives as an 8-byte "boot keyboard"
 * report [modifiers, 0, key1..key6]. This turns those reports back into the scanned text.
 * Plain Java (tested on a PC).
 */
public final class Hid {

    private final StringBuilder buf = new StringBuilder();
    private int lastKey;

    /** Feeds one report; returns the finished code when Enter / Tab arrives, else null. */
    public String feed(byte[] r, int len) {
        if (len < 3) return null;
        int mod = r[0] & 0xff;
        boolean shift = (mod & 0x22) != 0;
        int key = 0;
        for (int i = 2; i < Math.min(len, 8); i++) if ((r[i] & 0xff) != 0) { key = r[i] & 0xff; break; }
        if (key == 0) { lastKey = 0; return null; }       // all keys up
        if (key == lastKey) return null;                   // key still held (same report repeated)
        lastKey = key;
        if (key == 0x28 || key == 0x58 || key == 0x2b) {   // Enter, keypad Enter, Tab
            String s = buf.toString().trim();
            buf.setLength(0);
            return s.length() >= 4 ? s : null;
        }
        char c = map(key, shift);
        if (c != 0 && buf.length() < 80) buf.append(c);
        return null;
    }

    static char map(int k, boolean shift) {
        if (k >= 0x04 && k <= 0x1d) return (char) ((shift ? 'A' : 'a') + (k - 0x04));
        if (k >= 0x1e && k <= 0x26) return shift ? "!@#$%^&*(".charAt(k - 0x1e) : (char) ('1' + (k - 0x1e));
        if (k == 0x27) return shift ? ')' : '0';
        if (k >= 0x59 && k <= 0x61) return (char) ('1' + (k - 0x59));   // keypad 1-9
        if (k == 0x62) return '0';
        switch (k) {
            case 0x2c: return ' ';
            case 0x2d: return shift ? '_' : '-';
            case 0x2e: return shift ? '+' : '=';
            case 0x2f: return shift ? '{' : '[';
            case 0x30: return shift ? '}' : ']';
            case 0x31: return shift ? '|' : '\\';
            case 0x33: return shift ? ':' : ';';
            case 0x34: return shift ? '"' : '\'';
            case 0x36: return shift ? '<' : ',';
            case 0x37: return shift ? '>' : '.';
            case 0x38: return shift ? '?' : '/';
            case 0x54: return '/';
            case 0x55: return '*';
            case 0x56: return '-';
            case 0x57: return '+';
            case 0x63: return '.';
            default: return 0;
        }
    }
}
