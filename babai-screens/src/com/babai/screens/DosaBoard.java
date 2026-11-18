package com.babai.screens;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;

import com.babai.board.BoardModel;
import com.babai.board.BoardView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Dosa screen (1.6): the Babai Dosa Board design (the same drawing code as the Petpooja KDS 7.8
 * patch, kds-78-board/), drawn by the app, fed from the Console's kitchen feed. Which orders' dosas
 * it shows is chosen on this TV (settings): dine in, pick up, self service, online (all channels or
 * some). Nothing changes on the Console.
 */
final class DosaBoard {
    interface Host {
        void boardOk();
        void boardFailed(String why);
        void say(String text, String wav);
        boolean speaking();
    }

    static final long LEFTOVER_MS = 2 * 3600_000L; // left open in Petpooja this long: not shown

    private final Handler h = new Handler(Looper.getMainLooper());
    private final SharedPreferences P;
    private final Host host;
    private final String url, base;
    final BoardView view;
    private final BoardModel model = new BoardModel();
    private final Set<Long> called = new HashSet<Long>();
    /** The Babai Dosa Board app: read every order type from Petpooja (today's numbers count every dosa;
     *  the board still shows only the chosen ones), and hand each list to its stats. */
    boolean fetchAll;
    DosaStats stats;
    volatile JSONObject last; // the last kitchen list read (the TV's settings page shows it)
    static String defaultKinds = "IP";
    static boolean roundsMode = false; // the Dosa Board app: wait by tawa rounds // Babai Screens: dine in + pick up; the Dosa Board app: dine in
    private boolean running, first = true, busy;

    DosaBoard(Context c, SharedPreferences p, Host host, String base, String query) {
        this.P = p;
        this.host = host;
        this.base = base;
        this.url = base + "/screens/api/kitchen?" + query + "&scr=dosa";
        view = new BoardView(c, look(c));
        view.show(model, System.currentTimeMillis());
    }

    void start() {
        if (running) return;
        running = true;
        if (P.getString("ppHost", "").trim().isEmpty()) {
            // like the Petpooja KDS: find the billing PC on the Wi-Fi and read it directly (the
            // Console's list is the backup; it can't tell a dispatched dish)
            new Thread(new Runnable() {
                @Override public void run() {
                    List<String> f = Petpooja.find();
                    if (!f.isEmpty() && P.getString("ppHost", "").trim().isEmpty()) P.edit().putString("ppHost", f.get(0)).apply();
                }
            }).start();
        }
        h.post(tick);
    }

    void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!busy) {
                busy = true;
                new Thread(new Runnable() { @Override public void run() { fetch(); } }).start();
            }
            h.postDelayed(this, 1500);
        }
    };

    /** Petpooja's billing PC first (as the KDS app, works without the Console), else the Console. */
    private void fetch() {
        JSONObject got = null;
        String err = null, pp = P.getString("ppHost", "").trim();
        if (!pp.isEmpty()) {
            try {
                got = Petpooja.kitchen(pp, fetchAll ? allKinds() : kinds(P), 20000); // a busy billing PC can be slow to answer
            } catch (Throwable t) {
                err = "Petpooja at " + pp + ": " + why(t);
            }
        }
        if (got == null && !base.isEmpty()) {
            String body = null;
            try {
                body = Net.get(url, 8000, 16 * 1024 * 1024); // every open KOT: can be large at a busy outlet
                got = new JSONObject(body);
            } catch (Throwable t) {
                String e2;
                if (body != null) {
                    String head = body.trim().replaceAll("\\s+", " ");
                    e2 = "the Console's reply isn't the kitchen list (" + body.length() + " bytes, starts: " + (head.length() > 70 ? head.substring(0, 70) + "…" : head) + ")";
                } else {
                    e2 = "Console: " + why(t);
                }
                err = err == null ? e2 : err + "\n" + e2;
            }
        }
        if (got == null && err == null) err = "set the Petpooja billing PC in settings";
        final JSONObject j = got;
        final String e = err;
        h.post(new Runnable() {
            @Override public void run() {
                busy = false;
                if (!running) return;
                if (j == null) { host.boardFailed(e); return; }
                try {
                    long now = System.currentTimeMillis();
                    List<BoardModel.Order> orders = fresh(orders(j, P, now), now);
                    model.clearMs = P.getInt("dosaClearMin", 10) * 60_000L;
                    model.tawaOrders = P.getInt("tawaOrders", 4); // orders the tawa works on at once (laptop page)
                    model.rounds = roundsMode; // the Dosa Board app: wait by tawa rounds (as the KDS V11 Dosa Live)
                    model.roundSize = P.getInt("roundSize", 8);
                    model.roundMin = P.getFloat("roundMin", 3f);
                    model.update(orders, now);
                    view.show(model, now);
                    last = j;
                    if (stats != null) stats.add(j, kinds(P), chans(P));
                    host.boardOk();
                    calls();
                } catch (Throwable t) {
                    host.boardFailed("the kitchen list couldn't be read: " + why(t));
                }
            }
        });
    }

    // already ready and older than this when the board first sees it: collected long ago, not shown
    static final long STALE_MS = 30 * 60_000L;
    private final Set<Long> known = new HashSet<Long>(), stale = new HashSet<Long>();

    /** Drops the KOTs that were ready long ago when the board first saw them (as the Console's screen). */
    List<BoardModel.Order> fresh(List<BoardModel.Order> in, long now) {
        Set<Long> live = new HashSet<Long>();
        List<BoardModel.Order> out = new ArrayList<BoardModel.Order>();
        for (BoardModel.Order o : in) {
            live.add(o.id);
            if (known.add(o.id) && o.ready && o.created > 0 && now - o.created > STALE_MS) stale.add(o.id);
            if (!o.ready) stale.remove(o.id); // un-marked: it is cooking again
            if (!stale.contains(o.id)) out.add(o);
        }
        known.retainAll(live);
        stale.retainAll(live);
        return out;
    }

    private static String why(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : " " + m);
    }

    /** Calls: a token as soon as it turns ready; then reminders at remindMins after that (the Dosa Board
     *  app: 1, 2, 4, 5) while it is still ready; new tokens first. Babai Screens: no reminders. */
    static int[] remindMins = {};
    static boolean defaultDigits = false; // the Dosa Board app: "two four zero one"
    private final CallPlan plan = new CallPlan();

    private void calls() {
        List<CallPlan.Token> ready = new ArrayList<CallPlan.Token>();
        for (BoardModel.Ready r : model.ready) ready.add(new CallPlan.Token(r.o.id, r.o.token));
        CallPlan.Due d = plan.next(ready, System.currentTimeMillis(), remindMins, host.speaking());
        if (!P.getBoolean("dosaVoice", true)) return;
        boolean dg = P.getBoolean("voiceDigits", defaultDigits);
        for (List<String> c : CallPlan.chunks(d.fresh, 3)) say(announcement(c, dg)); // at most 3 tokens a sentence
        for (List<String> c : CallPlan.chunks(d.remind, 3)) say(reminder(c, dg));
    }

    private void say(String text) {
        String wav;
        try {
            wav = base.isEmpty() ? null : base + "/screens/voice.wav?t=" + URLEncoder.encode(text, "UTF-8");
        } catch (Exception x) {
            wav = null;
        }
        host.say(text, wav);
    }

    static String tokens(List<String> t, boolean digits) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < t.size(); i++) s.append(i == 0 ? "" : i == t.size() - 1 ? " and " : ", ").append(spoken(t.get(i), digits));
        return s.toString();
    }

    /** "Reminder for token number 2 4 0 1. Your dosa is waiting at the counter." */
    static String reminder(List<String> t, boolean digits) {
        if (shortCalls) return announcement(t, digits); // the same words as the first call
        return "Reminder for token number" + (t.size() > 1 ? "s " : " ") + tokens(t, digits) + ". Your dosa" + (t.size() > 1 ? "s are" : " is") + " waiting at the counter.";
    }

    /** How a token is said: "2401", or digit by digit ("2, 4, 0, 1") when chosen. */
    static String spoken(String t, boolean digits) {
        if (t.matches("T\\d+")) return "table " + t.substring(1);
        t = t.replace("#", "");
        if (!digits) return t;
        StringBuilder b = new StringBuilder();
        for (char ch : t.toCharArray()) {
            if (Character.isDigit(ch)) b.append(b.length() > 0 ? " " : "").append(ch);
            else if (ch != ' ') b.append(ch);
        }
        return b.toString();
    }

    /** The announcement for these tokens, as the board says it. */
    static String announcement(List<String> t, boolean digits) {
        if (shortCalls) return "Token " + tokens(t, digits) + ", dosa ready.";
        return "Token number" + (t.size() > 1 ? "s " : " ") + tokens(t, digits) + ". Your dosa" + (t.size() > 1 ? "s are" : " is") + " ready. Kindly collect.";
    }

    /** The Dosa Board app: the short call the outlet chose ("Token 2 4 0 1, dosa ready."). */
    static boolean shortCalls = false;

    // ---- what this screen shows -------------------------------------------------------------------

    /** The setting: "I" dine in, "P" pick up, "S" self service, "D" online; channels: none = all. */
    static Set<String> kinds(SharedPreferences p) {
        Set<String> s = new HashSet<String>();
        for (String x : p.getString("dosaKinds", defaultKinds).split("")) if (!x.isEmpty()) s.add(x);
        if (s.isEmpty()) s.add("I");
        return s;
    }

    static Set<String> allKinds() {
        Set<String> s = new HashSet<String>();
        for (String x : new String[]{"I", "P", "S", "D"}) s.add(x);
        return s;
    }

    static Set<String> chans(SharedPreferences p) {
        Set<String> s = new HashSet<String>();
        for (String x : p.getString("dosaChans", "").split("\n")) if (!x.trim().isEmpty()) s.add(x.trim());
        return s;
    }

    /** The online channels seen so far (for the settings), newest last. */
    static List<String> seenChans(SharedPreferences p) {
        List<String> s = new ArrayList<String>();
        for (String x : p.getString("dosaSeen", "").split("\n")) if (!x.trim().isEmpty() && !s.contains(x.trim())) s.add(x.trim());
        return s;
    }

    static boolean isDosa(String cat) { return cat != null && cat.toLowerCase(Locale.US).contains("dosa"); }

    /** The kitchen feed's cards -> the board's orders, as this screen is set. */
    static List<BoardModel.Order> orders(JSONObject j, SharedPreferences p, long localNow) throws Exception {
        Set<String> kinds = kinds(p), chans = chans(p);
        List<String> seen = seenChans(p);
        boolean newChan = false;
        long srvNow = j.optLong("now", localNow), skew = j.optLong("skew", 0);
        JSONArray cards = j.optJSONArray("cards");
        List<BoardModel.Order> out = new ArrayList<BoardModel.Order>();
        if (cards == null) return out;
        for (int i = 0; i < cards.length(); i++) {
            JSONObject c = cards.optJSONObject(i);
            if (c == null) continue;
            try {
            String ch = c.optString("ch", "I"), app = c.optString("app", "");
            if ("D".equals(ch) && !app.isEmpty() && !seen.contains(app)) { seen.add(app); newChan = true; }
            if (!kinds.contains(ch)) continue;
            if ("D".equals(ch) && !chans.isEmpty() && !chans.contains(app)) continue;
            JSONArray its = c.optJSONArray("i");
            if (its == null) continue;
            List<BoardModel.Item> items = new ArrayList<BoardModel.Item>();
            boolean allReady = true;
            for (int k = 0; k < its.length(); k++) {
                JSONObject it = its.optJSONObject(k);
                if (it == null) continue;
                if (!isDosa(it.optString("c"))) continue;
                boolean r = it.optBoolean("r");
                allReady &= r;
                items.add(new BoardModel.Item(it.optString("n"), it.optDouble("q", 1), r));
            }
            if (items.isEmpty()) continue;
            long created = c.optLong("c", 0);
            if (created > 0) created = created + skew - (srvNow - localNow); // Petpooja's clock -> this TV's
            if (!allReady && created > 0 && localNow - created > LEFTOVER_MS) continue; // left open in Petpooja
            String tok = c.optString("kn", "");
            if ("D".equals(ch)) {
                String l4 = c.optString("l4", "");
                tok = (app.isEmpty() ? "" : app.substring(0, 1).toUpperCase(Locale.US) + " ") + (l4.isEmpty() ? tok : l4);
            }
            out.add(new BoardModel.Order(c.optLong("k"), tok, created, allReady, false, items));
            } catch (Throwable t) {
                // one unreadable order never stops the board
            }
        }
        if (newChan) {
            StringBuilder s = new StringBuilder();
            for (String x : seen) s.append(x).append('\n');
            p.edit().putString("dosaSeen", s.toString()).apply();
        }
        return out;
    }

    // ---- the design's fonts and pictures (assets/babai_board, as in the KDS patch) ----------------

    private static BoardView.Look look;

    private static BoardView.Look look(Context c) {
        if (look != null) return look;
        BoardView.Look l = new BoardView.Look();
        l.mont800 = tf(c, "montserrat-800.ttf", Typeface.DEFAULT_BOLD);
        l.mont900 = tf(c, "montserrat-900.ttf", Typeface.DEFAULT_BOLD);
        l.fig600 = tf(c, "figtree-600.ttf", Typeface.DEFAULT);
        l.fig700 = tf(c, "figtree-700.ttf", Typeface.DEFAULT_BOLD);
        l.fig800 = tf(c, "figtree-800.ttf", Typeface.DEFAULT_BOLD);
        l.mascot = png(c, "mascot.png");
        l.spinner = png(c, "spinner.png");
        look = l;
        return l;
    }

    private static Typeface tf(Context c, String name, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), "babai_board/" + name);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static Bitmap png(Context c, String name) {
        try {
            InputStream in = c.getAssets().open("babai_board/" + name);
            try {
                return BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
