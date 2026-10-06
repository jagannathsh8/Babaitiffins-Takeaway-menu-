package com.pp.kds.dosa;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plain-JVM checks for TokenBoard. Run: see build.sh (exits non-zero on failure). */
public final class TokenBoardTest {
    static int failures;

    static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  " + detail);
        if (!ok) failures++;
    }

    static TokenBoard.Kot k(long id, String tok, long created, boolean ready) {
        return new TokenBoard.Kot(id, tok, tok, created, ready, false, false, 1);
    }

    static String labels(List<TokenBoard.Token> l) {
        StringBuilder sb = new StringBuilder();
        for (TokenBoard.Token t : l) sb.append(sb.length() == 0 ? "" : ",").append(t.label);
        return sb.toString();
    }

    public static void main(String[] a) {
        TokenBoard b = new TokenBoard();
        long t0 = 1_000_000L;
        // Startup snapshot: an already-ready token is NOT announced.
        b.update(Arrays.asList(k(1, "5", t0, true), k(2, "6", t0 + 1, false)), t0, true);
        check("no backlog call", b.takeCall(t0 + 10_000).isEmpty(), "");
        check("lists", labels(b.ready()).equals("5") && labels(b.preparing()).equals("6"),
                labels(b.ready()) + " | " + labels(b.preparing()));

        // Five tokens turn ready within a few seconds -> one call, in the order they became ready.
        List<TokenBoard.Kot> board = new ArrayList<TokenBoard.Kot>();
        board.add(k(1, "5", t0, true));
        board.add(k(2, "6", t0, false));
        board.add(k(11, "1", t0, true));
        board.add(k(12, "2", t0, true));
        b.update(board, t0 + 20_000, true);
        check("gather wait", b.takeCall(t0 + 21_000).isEmpty(), "");
        board.set(1, k(2, "6", t0, true));
        board.add(k(14, "4", t0, true));
        b.update(board, t0 + 22_000, true);
        List<TokenBoard.Token> call = b.takeCall(t0 + 23_100);
        check("batched call", labels(call).equals("1,2,6,4"), labels(call));
        check("phrase", TokenBoard.phrase(call).equals("Token number 1, 2, 6, 4, dosa ready, kindly collect."),
                TokenBoard.phrase(call));
        check("called once", b.takeCall(t0 + 40_000).isEmpty(), "");
        check("ready newest first", labels(b.ready()).startsWith("6,4") || labels(b.ready()).startsWith("4,6"),
                labels(b.ready()));

        // Dispatch removes the token immediately; a vanished card goes after MISSING_MS.
        board.set(0, new TokenBoard.Kot(1, "5", "5", t0, true, true, false, 1));
        b.update(board, t0 + 50_000, true);
        check("dispatched removed", !labels(b.ready()).contains("5"), labels(b.ready()));
        board.remove(0);
        board.remove(0); // KOT 2 (token 6) disappears from the board
        b.update(board, t0 + 51_000, true);
        check("blip kept", labels(b.ready()).contains("6"), labels(b.ready()));
        b.update(board, t0 + 57_000, true);
        check("gone removed", !labels(b.ready()).contains("6"), labels(b.ready()));

        // Not watching: ready tokens are silently marked, nothing replayed later.
        board.add(k(20, "9", t0, false));
        b.update(board, t0 + 60_000, true);
        board.set(board.size() - 1, k(20, "9", t0, true));
        b.update(board, t0 + 62_000, false);
        b.update(board, t0 + 64_000, true);
        check("silent when hidden", b.takeCall(t0 + 70_000).isEmpty(), "");

        // Two KOTs on one token: ready only when both are.
        board.add(k(30, "12", t0, true));
        board.add(k(31, "12", t0, false));
        b.update(board, t0 + 80_000, true);
        check("token waits for all KOTs", labels(b.preparing()).contains("12"), labels(b.preparing()));
        board.set(board.size() - 1, k(31, "12", t0, true));
        b.update(board, t0 + 82_000, true);
        check("token ready", labels(b.takeCall(t0 + 86_000)).equals("12"), "");

        // Older order still cooking while a newer one is ready -> flagged for a friendly note.
        TokenBoard ob = new TokenBoard();
        ob.update(Arrays.asList(k(1, "40", t0, false), k(2, "41", t0 + 60_000, true), k(3, "42", t0 + 120_000, false)), t0, true);
        check("overtaken", ob.preparing().get(0).overtaken && !ob.preparing().get(1).overtaken,
                ob.preparing().get(0).label + "/" + ob.preparing().get(1).label);

        // --- release rules ---
        long T = 10_000_000_000L;
        TokenBoard rb = new TokenBoard();
        // Leftover at start: ready and ordered 2 h ago -> never shown. Fresh ready one is shown.
        List<TokenBoard.Kot> rbBoard = new ArrayList<TokenBoard.Kot>();
        rbBoard.add(k(100, "300", T - 120 * 60_000L, true));
        rbBoard.add(k(101, "301", T - 6 * 60_000L, true));
        rb.update(rbBoard, T, true);
        check("stale leftover hidden", labels(rb.ready()).equals("301"), labels(rb.ready()));
        // Dosa items item-dispatched (KOT still open for other items) -> released.
        rbBoard.set(1, new TokenBoard.Kot(101, "301", "301", T - 6 * 60_000L, true, true, false, 1));
        rb.update(rbBoard, T + 2_000, true);
        check("dosa-dispatched released", rb.ready().isEmpty(), labels(rb.ready()));
        // Auto-clear after 10 min ready.
        rbBoard.add(k(102, "302", T, false));
        rb.update(rbBoard, T + 4_000, true);
        rbBoard.set(2, k(102, "302", T, true));
        rb.update(rbBoard, T + 6_000, true);
        rb.refresh(T + 6_000 + 9 * 60_000L);
        check("still ready at 9 min", labels(rb.ready()).equals("302"), labels(rb.ready()));
        rb.refresh(T + 6_000 + 10 * 60_000L);
        check("auto-cleared at 10 min", rb.ready().isEmpty(), labels(rb.ready()));
        // Manual collect + later reuse of the same token number by a new KOT.
        rbBoard.add(k(103, "303", T + 700_000, true));
        rb.update(rbBoard, T + 700_000, true);
        rb.collect("303", T + 701_000);
        check("collected", rb.ready().isEmpty(), labels(rb.ready()));
        rbBoard.add(k(104, "303", T + 800_000, false)); // same number, new order (old KOT still on board)
        rb.update(rbBoard, T + 800_000, true);
        check("reused number = new order only", labels(rb.preparing()).equals("303") && rb.preparing().get(0).dosas == 1,
                labels(rb.preparing()));
        rbBoard.set(rbBoard.size() - 1, k(104, "303", T + 800_000, true));
        rb.update(rbBoard, T + 802_000, true);
        check("reused number called", labels(rb.takeCall(T + 806_000)).equals("303"), "");
        // Auto-clear off.
        TokenBoard off = new TokenBoard();
        off.setAutoClearMinutes(0);
        off.update(Arrays.asList(k(1, "9", T, true)), T, true);
        off.refresh(T + 60 * 60_000L);
        check("auto-clear off keeps", labels(off.ready()).equals("9"), labels(off.ready()));

        // Table fallback in the phrase.
        TokenBoard.Token tt = new TokenBoard.Token("T4");
        tt.spoken = "table 4";
        TokenBoard.Token t7 = new TokenBoard.Token("7");
        t7.spoken = "7";
        check("table phrase", TokenBoard.phrase(Arrays.asList(t7, tt)).equals("Token number 7, table 4, dosa ready, kindly collect."),
                TokenBoard.phrase(Arrays.asList(t7, tt)));

        // Minutes left: real figures only.
        check("no data -> NaN", Double.isNaN(TokenBoard.minutesLeft(0, 2, Double.NaN, 0)), "");
        check("avg based", Math.abs(TokenBoard.minutesLeft(0, 2, 5.5, 0) - 3.5) < 1e-9, TokenBoard.minutesLeft(0, 2, 5.5, 0));
        check("queue based", Math.abs(TokenBoard.minutesLeft(9, 2, 5.5, 1.0) - 10) < 1e-9, TokenBoard.minutesLeft(9, 2, 5.5, 1.0));
        check("cap 40", TokenBoard.minutesLeft(99, 0, 5, 0.5) == 40, TokenBoard.minutesLeft(99, 0, 5, 0.5));

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
