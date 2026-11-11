import com.babai.board.BoardModel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BoardModelTest {
    static int fails;

    static void check(boolean ok, String what) {
        if (!ok) {
            fails++;
            System.out.println("FAIL " + what);
        }
    }

    static BoardModel.Order o(long id, String tok, long created, boolean ready, boolean gone, double... qty) {
        List<BoardModel.Item> items = new ArrayList<BoardModel.Item>();
        for (double q : qty) items.add(new BoardModel.Item("Masala Dosa", q, ready));
        return new BoardModel.Order(id, tok, created, ready, gone, items);
    }

    public static void main(String[] a) {
        long t0 = 1_800_000_000_000L, M = 60_000L;
        BoardModel m = new BoardModel();
        // start: one already ready (no spotlight), three preparing
        m.update(Arrays.asList(o(1, "1601", t0 - 9 * M, true, false, 1), o(2, "1602", t0 - 6 * M, false, false, 2),
                o(3, "1603", t0 - 2 * M, false, false, 1), o(4, "1604", t0 - M, false, false, 1, 1)), t0);
        check(m.ready.size() == 1 && t0 - m.ready.get(0).readyAt > BoardModel.FRESH_MS, "ready at start: not spotlit");
        check(m.prep.size() == 3 && m.prep.get(0).o.token.equals("1602"), "preparing oldest first");
        check(m.dosasInMaking == 5, "dosas in the making " + m.dosasInMaking);
        check(m.prep.get(0).minutes >= 1 && m.prep.get(2).minutes >= m.prep.get(0).minutes, "later tokens wait as long or longer");
        check(m.estMinutes >= m.prep.get(2).minutes, "a new order waits behind the queue: " + m.estMinutes);
        // 1602 turns ready: spotlight, first in Ready, learns its 7 min
        long t1 = t0 + M;
        m.update(Arrays.asList(o(1, "1601", t0 - 9 * M, true, false, 1), o(2, "1602", t0 - 6 * M, true, false, 2),
                o(3, "1603", t0 - 2 * M, false, false, 1), o(4, "1604", t0 - M, false, false, 1, 1)), t1);
        check(m.ready.size() == 2 && m.ready.get(0).o.token.equals("1602") && m.ready.get(0).readyAt == t1, "newest ready first, now");
        check(m.prep.size() == 2 && m.dosasInMaking == 3, "left preparing");
        // dispatched / gone leave; 30 min old ready leave
        m.update(Arrays.asList(o(1, "1601", t0 - 9 * M, true, true, 1), o(2, "1602", t0 - 6 * M, true, false, 2),
                o(3, "1603", t0 - 2 * M, false, false, 1)), t1 + 31 * M);
        check(m.ready.isEmpty(), "dispatched and 30-min-old ready leave: " + m.ready.size());
        check(m.prep.size() == 1, "gone from the KDS: off the board");
        // a token made ready then un-marked goes back to preparing
        m.update(Arrays.asList(o(3, "1603", t0 - 2 * M, true, false, 1)), t1 + 32 * M);
        m.update(Arrays.asList(o(3, "1603", t0 - 2 * M, false, false, 1)), t1 + 33 * M);
        check(m.ready.isEmpty() && m.prep.size() == 1, "un-marked: back to preparing");
        check(BoardModel.fmtQty(2) .equals("2") && BoardModel.fmtQty(1.5).equals("1.5"), "quantities");
        System.out.println(fails == 0 ? "BoardModelTest: all passed" : "BoardModelTest: " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
