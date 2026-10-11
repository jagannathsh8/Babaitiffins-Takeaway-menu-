package com.pp.kds.dosa;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the rider's order on the board from the text read off the rider app screen (pure Java).
 *
 * Rider apps show the platform order ID in different ways:
 *   Swiggy        "#25070425817 0997"                      (last 4 set apart, bold)
 *   Zomato        "ORDER ID" / "8713250896"                (10 digits)
 *   Collect Food  "Order ID : FlvQ1j_1791700811809339883"  (code + "_" + digits; may wrap
 *                 onto a second line: "...180933" / "9883"; Hindi label on some phones)
 * Long digit runs are taken from the text and compared from the end with the board's order IDs;
 * only an order on the board can match, so prices, times or addresses never show a result.
 */
final class RiderOcr {

    /** Digits matched from the end: this many make a match certain (shown at once). */
    static final int SURE = 8;
    /** Fewer (but at least this many, or the whole ID) need the same answer twice in a row. */
    static final int MIN = 6;

    private RiderOcr() {}

    static final class Match {
        final RiderCalls.Order order;
        final int digits;      // matched from the end
        final boolean sure;

        Match(RiderCalls.Order order, int digits, boolean sure) {
            this.order = order;
            this.digits = digits;
            this.sure = sure;
        }
    }

    /** Long digit runs that may be an order ID (OCR slips like O->0 inside a number fixed). */
    static List<String> candidates(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = clean(lines[i]);
            // "#25070425817 0997": a short group right after a long one belongs to it.
            line = line.replaceAll("(\\d{6,}) (\\d{3,4})(?!\\d)", "$1$2");
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{6,22}").matcher(line);
            String lastRun = null;
            int lastEnd = -1;
            while (m.find()) {
                add(out, m.group());
                lastRun = m.group();
                lastEnd = m.end();
            }
            // Wrapped ID: the line ends with the long number and the next line is only digits.
            int j = i + 1;
            while (j < lines.length && lines[j].trim().isEmpty()) j++;    // OCR may leave blank lines
            if (lastRun != null && j < lines.length && line.substring(lastEnd).trim().isEmpty()) {
                String next = clean(lines[j]).replace(" ", "");
                if (next.matches("\\d{1,8}")) add(out, lastRun + next);
            }
        }
        return out;
    }

    /** True when the text has an order-ID label (so an unknown number is worth "not on board"). */
    static boolean labelled(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.US);
        return t.contains("order id") || t.contains("order 1d") || t.contains("rder id") || t.contains(" id :")
                || t.contains("id:") || t.contains("ऑर्डर") || t.matches("(?s).*#\\s*\\d{6,}.*");
    }

    /**
     * Best board order for the text: most digits matched from the end of its ID. Null when nothing
     * matches well enough, or when two orders match equally well.
     */
    static Match match(List<String> candidates, List<RiderCalls.Order> orders) {
        RiderCalls.Order best = null;
        int bestN = 0;
        boolean tie = false;
        for (RiderCalls.Order o : orders) {
            String id = digits(o.orderId);
            if (id.length() < 4) id = digits(o.last4);
            if (id.length() < 4) continue;
            int n = 0;
            for (String c : candidates) n = Math.max(n, commonTail(c, id));
            boolean whole = n == id.length();
            if (n < MIN && !(whole && n >= 4 && candidates.contains(id))) continue;
            if (n > bestN) {
                best = o;
                bestN = n;
                tie = false;
            } else if (n == bestN && best != null && best.id != o.id) {
                tie = true;
            }
        }
        if (best == null || tie) return null;
        String id = digits(best.orderId);
        boolean sure = bestN >= SURE || (bestN == id.length() && bestN >= 10);
        return new Match(best, bestN, sure);
    }

    /** The longest candidate when nothing on the board matches (for "not on board"). */
    static String unknownId(List<String> candidates) {
        String best = null;
        for (String c : candidates) if (c.length() >= 10 && (best == null || c.length() > best.length())) best = c;
        return best;
    }

    static int commonTail(String a, String b) {
        int n = 0, i = a.length() - 1, j = b.length() - 1;
        while (i >= 0 && j >= 0 && a.charAt(i) == b.charAt(j)) {
            n++;
            i--;
            j--;
        }
        return n;
    }

    static String digits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Keeps a line's digits readable: in a word that is mostly digits, letters OCR often reads in
     * place of digits (O, o -> 0; I, l, | -> 1) are fixed, and the "code_" before an ID goes.
     */
    private static String clean(String s) {
        String t = s.replaceAll("[A-Za-z0-9]+_", " ");   // "FlvQ1j_1791..." -> " 1791..."
        StringBuilder out = new StringBuilder();
        for (String w : t.split(" ", -1)) {
            int dg = 0;
            for (int k = 0; k < w.length(); k++) if (Character.isDigit(w.charAt(k))) dg++;
            if (w.length() >= 3 && dg * 10 >= w.length() * 6) {
                w = w.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1').replace('|', '1');
            }
            if (out.length() > 0) out.append(' ');
            out.append(w);
        }
        return out.toString();
    }

    private static void add(List<String> out, String s) {
        if (!out.contains(s)) out.add(s);
    }
}
