import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;

import com.babai.board.BoardModel;
import com.babai.board.BoardView;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Renders the Dosa Board's own drawing code (BoardView.java, unchanged) to PNG on a PC, through the
 * Java 2D stand-ins in preview/shim. A timeline like the approved design: tokens turn ready at
 * different times, 1622 has just turned ready (spotlight), nine are preparing.
 *
 *   java Preview <assets dir> <out dir>
 */
public class Preview {
    static final long M = 60_000L;

    static BoardModel.Order o(long id, long created, boolean ready, Object... items) {
        List<BoardModel.Item> l = new ArrayList<BoardModel.Item>();
        for (int i = 0; i < items.length; i += 2) l.add(new BoardModel.Item((String) items[i + 1], ((Number) items[i]).doubleValue(), ready));
        return new BoardModel.Order(id, String.valueOf(id), created, ready, false, l);
    }

    public static void main(String[] a) throws Exception {
        String as = a[0];
        BoardView.Look look = new BoardView.Look();
        look.mont800 = Typeface.createFromFile(as + "/montserrat-800.ttf");
        look.mont900 = Typeface.createFromFile(as + "/montserrat-900.ttf");
        look.fig600 = Typeface.createFromFile(as + "/figtree-600.ttf");
        look.fig700 = Typeface.createFromFile(as + "/figtree-700.ttf");
        look.fig800 = Typeface.createFromFile(as + "/figtree-800.ttf");
        look.mascot = new Bitmap(ImageIO.read(new File(as + "/mascot.png")));
        look.spinner = new Bitmap(ImageIO.read(new File(as + "/spinner.png")));

        long now = java.util.Calendar.getInstance().getTimeInMillis();
        // when each ready token turned ready (minutes before now; 1622: 2 s ago)
        long[][] readyAt = {{1607, 6 * M}, {1610, 330_000}, {1612, 5 * M}, {1616, 3 * M}, {1618, 2 * M}, {1622, 2_000}};
        BoardModel m = new BoardModel();
        List<long[]> steps = new ArrayList<long[]>();
        for (long t = now - 10 * M; t <= now; t += 1000) {
            List<BoardModel.Order> board = new ArrayList<BoardModel.Order>();
            for (long[] r : readyAt) board.add(order((int) r[0], now - r[1] - 4 * M, t >= now - r[1]));
            board.add(o(1623, now - 8 * M, false, 1, "Onion Uttapam", 1, "Cheese Masala Dosa", 1, "Ghee Masala Dosa"));
            board.add(o(1624, now - 7 * M, false, 1, "Ghee Podi Masala Dosa", 1, "Onion Dosa"));
            board.add(o(1625, now - 6 * M, false, 1, "Ghee Podi Masala Dosa"));
            board.add(o(1627, now - 5 * M, false, 1, "Ghee Podi Masala Dosa"));
            board.add(o(1630, now - 4 * M, false, 1, "Upma Pesarattu"));
            board.add(o(1635, now - 3 * M, false, 1, "Ghee Karam Dosa"));
            board.add(o(1633, now - 3 * M + 1000, false, 2, "Plain Dosa"));
            board.add(o(1636, now - 2 * M, false, 1, "Masala Dosa", 1, "Onion Dosa"));
            board.add(o(1637, now - M, false, 1, "Rava Dosa"));
            m.update(board, t);
        }
        // the first one on the tawa: its first dosa is done
        render(look, m, now, 1920, 1080, 1500, a[1] + "/board-1920x1080.png");
        render(look, m, now, 1280, 720, 1500, a[1] + "/board-1280x720.png");
        render(look, m, now, 1366, 768, 4200, a[1] + "/board-1366x768.png");
        // a busy rush: 22 ready, 14 preparing
        BoardModel busy = new BoardModel();
        String[] menu = {"Masala Dosa", "Plain Dosa", "Onion Dosa", "Ghee Podi Masala Dosa", "Cheese Masala Dosa", "Ghee Karam Dosa", "Rava Dosa"};
        for (long t = now - 20 * M; t <= now; t += 1000) {
            List<BoardModel.Order> board = new ArrayList<BoardModel.Order>();
            for (int i = 0; i < 22; i++) {
                long ra = now - (22 - i) * 40_000L;
                board.add(o(1700 + i, ra - 5 * M, t >= ra, 1 + i % 2, menu[i % menu.length], 1, menu[(i + 3) % menu.length]));
            }
            for (int i = 0; i < 14; i++) board.add(o(1730 + i, now - (14 - i) * 50_000L, false, 1 + i % 3, menu[(i * 2) % menu.length]));
            busy.update(board, t);
        }
        render(look, busy, now, 1920, 1080, 2500, a[1] + "/board-busy-1920x1080.png");
        System.out.println("ready " + m.ready.size() + ", preparing " + m.prep.size() + ", est ~" + m.estMinutes + " min, dosas " + m.dosasInMaking);
    }

    static BoardModel.Order order(int tok, long created, boolean ready) {
        switch (tok) {
            case 1607: return o(tok, created, ready, 2, "Ghee Karam Dosa");
            case 1610: return o(tok, created, ready, 1, "Onion Dosa", 1, "Rava Dosa");
            case 1612: return o(tok, created, ready, 1, "Plain Dosa");
            case 1616: return o(tok, created, ready, 2, "Ghee Masala Dosa", 1, "Ghee Podi Dosa");
            case 1618: return o(tok, created, ready, 1, "Ghee Podi Masala Dosa", 1, "Masala Dosa");
            default: return o(tok, created, ready, 2, "Masala Dosa", 1, "Cheese Masala Dosa");
        }
    }

    static void render(BoardView.Look look, BoardModel m, long now, int w, int h, long animMs, String out) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        BoardView v = new BoardView(null, look);
        v.setSize(w, h);
        v.frozenT = animMs;
        v.show(m, now);
        v.draw(new Canvas(img));
        ImageIO.write(img, "png", new File(out));
    }
}
