package com.pp.kds.dosa;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rider pickup board for the TV at the delivery counter.
 *
 *  Left : READY FOR PICKUP - one card per order: platform colour, big SLOT (from Bridge Print)
 *         and the big last 4 digits of the platform order ID; cards auto-size to fit all.
 *  Right: PREPARING - online / delivery orders still in the kitchen, oldest first.
 *  Call : big pop-up while the voice calls new ready orders.
 *  Card : the camera read a rider's phone: brand, full order ID (last 4 highlighted), SLOT,
 *         READY / PREPARING, times and the KOT items; back to the board after 20 s quiet or OK.
 *  Find : the search button (or any number key) opens a keypad: type the last 4 digits of the
 *         order ID to see brand, full order ID, slot and READY / PREPARING. It stays open, and
 *         keeps updating, until it is closed.
 *
 * Plain dark background (no photos) for clarity and speed on TV boxes.
 */
final class RiderBoardView extends View {

    interface Listener {
        void onClose();

        void onVoice(boolean on);

        /** How long ready orders stay: 0 = until picked up, else minutes. */
        void onDuration(int minutes);

        /** 0 = auto (light sensor), 1 = 50 %, 2 = 75 %, 3 = 100 %. */
        void onBrightness(int mode);
    }

    private static final int[] DURATIONS = {0, 15, 30, 60};
    private int duration = 0;
    private final RectF hitDur = new RectF(), hitBright = new RectF();
    private int brightMode;
    private boolean hasSensor;

    void setBrightness(int mode, boolean sensor) {
        brightMode = mode;
        hasSensor = sensor;
        invalidate();
    }

    void setDuration(int minutes) {
        duration = minutes;
        invalidate();
    }

    private static final long CALL_MS = 6_000L;
    private static final long CONTROLS_MS = 5_000L, CONTROLS_FADE_MS = 800L;
    private static final int GREEN = 0xFF69F0AE, GOLD = 0xFFF5B21B, CREAM = 0xFFFFF4DC;

    private final float d;
    private final long start = SystemClock.uptimeMillis();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF(), dst = new RectF();
    private final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    private final Typeface condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final DateFormat clock;
    private final boolean lite;
    private Bitmap logo;
    private Listener listener;
    private boolean voiceOn = true;

    private List<RiderCalls.Waiting> ready = new ArrayList<RiderCalls.Waiting>();
    private List<RiderCalls.Order> preparing = new ArrayList<RiderCalls.Order>();
    private String bridgeStatus = "";
    private boolean bridgeOk;
    private final Map<Long, Long> firstSeen = new HashMap<Long, Long>();
    private List<RiderCalls.Waiting> call;
    private long callAt;
    private long controlsAt = SystemClock.uptimeMillis();
    private final RectF hitVoice = new RectF(), hitClose = new RectF();
    private int focus = -1; // remote: 0 search, 1 brightness, 2 duration, 3 voice, 4 close
    private final RectF hitSearch = new RectF();
    private float headCy, headH, clockLeft;

    // search: keypad + results, open until closed
    private static final String[] KEYS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "CLEAR", "0", "\u232B"};
    private static final int MAX_DIGITS = 8;
    private boolean searchOn;
    private final StringBuilder query = new StringBuilder();
    private int sFocus = -1; // remote: 0..11 keypad, 12 close
    private final RectF[] hitKeys = new RectF[KEYS.length];
    private final RectF hitSearchClose = new RectF();
    private long lastTypeAt, keyFlashAt;
    private int keyFlash = -1;
    private boolean onBeforeBurst;
    private String queryBeforeBurst = "";

    /** One rider's order as found by the camera. */
    static final class Lookup {
        RiderCalls.Order order;        // null: the number is not on the board
        boolean ready;
        long readyAt, createdMs;       // wall clock
        String unknown;                // the number read, when not on the board
        String note;                   // e.g. "Already handed over"
        final List<String> items = new ArrayList<String>();
        long shownAt, seenAt;          // uptime
    }

    private static final long LOOKUP_IDLE_MS = 20_000L;
    private Lookup lookup;
    private final RectF hitLookupOk = new RectF();
    private RadialGradient bg;
    private float bgKey;
    private String clockStr = "";
    private long clockAt;
    private float s;

    RiderBoardView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        clock = android.text.format.DateFormat.getTimeFormat(c);
        lite = DosaNeonView.isLowEnd(c);
        try {
            java.io.InputStream in = c.getAssets().open("dosa_live_logo.png");
            logo = android.graphics.BitmapFactory.decodeStream(in);
            in.close();
        } catch (Throwable ignored) {
            logo = null;
        }
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < hitKeys.length; i++) hitKeys[i] = new RectF();
        setClickable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setVoice(boolean on) {
        voiceOn = on;
        invalidate();
    }

    void setData(List<RiderCalls.Waiting> readyOrders, List<RiderCalls.Order> preparingOrders, String bridge,
                 boolean bridgeConnected) {
        long now = SystemClock.uptimeMillis();
        ready = RiderCalls.readyByBrand(readyOrders);       // Swiggy, Zomato, then the others
        preparing = RiderCalls.byBrand(preparingOrders);
        bridgeStatus = bridge;
        bridgeOk = bridgeConnected;
        List<Long> live = new ArrayList<Long>();
        for (RiderCalls.Waiting w : ready) {
            live.add(w.order.id);
            if (!firstSeen.containsKey(w.order.id)) firstSeen.put(w.order.id, now);
        }
        firstSeen.keySet().retainAll(live);
        buildGroups();
        refreshLookup();
        invalidate();
    }

    void showCall(List<RiderCalls.Waiting> orders) {
        call = new ArrayList<RiderCalls.Waiting>(orders);
        callAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Shows (or refreshes) the details card; a new order replaces the one showing. */
    void showLookup(Lookup l, boolean fresh) {
        long now = SystemClock.uptimeMillis();
        if (!fresh && lookup != null) {
            lookup.seenAt = now;          // same phone still there
            return;
        }
        l.shownAt = now;
        l.seenAt = now;
        lookup = l;
        refreshLookup();
        invalidate();
    }

    /** Keeps the card open (activity at the counter). */
    void keepLookup() {
        if (lookup != null) lookup.seenAt = SystemClock.uptimeMillis();
    }

    private void closeLookup() {
        closeLookup(true);
    }

    private void closeLookup(boolean byUser) {
        lookup = null;
        controlsAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** The order on the card follows the board: PREPARING turns READY, the slot appears. */
    private void refreshLookup() {
        Lookup l = lookup;
        if (l == null || l.order == null) return;
        String id = l.order.orderId;
        for (RiderCalls.Waiting w : ready) {
            if (w.order.id == l.order.id || (!id.isEmpty() && id.equals(w.order.orderId))) {
                l.order = w.order;
                l.ready = true;
                l.readyAt = w.readyAt;
                return;
            }
        }
        for (RiderCalls.Order o : preparing) {
            if (o.id == l.order.id || (!id.isEmpty() && id.equals(o.orderId))) {
                l.order = o;
                l.ready = false;
                return;
            }
        }
    }

    // ---- input ---------------------------------------------------------------------------

    private float downY, lastY;
    private boolean dragging;

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int act = ev.getActionMasked();
        boolean overlay = searchOn || lookup != null || (call != null && SystemClock.uptimeMillis() - callAt < CALL_MS);
        if (act == MotionEvent.ACTION_DOWN) {
            downY = lastY = ev.getY();
            dragging = false;
        } else if (act == MotionEvent.ACTION_MOVE && !overlay && maxScroll > 0 && getWidth() < getHeight()) {
            float y = ev.getY();
            if (!dragging && Math.abs(y - downY) > 12 * d) dragging = true;
            if (dragging) {
                scrollY = Math.max(0, Math.min(maxScroll, scrollY - (y - lastY)));
                controlsAt = SystemClock.uptimeMillis();
                invalidate();
            }
            lastY = y;
            return true;
        }
        if (act == MotionEvent.ACTION_UP && dragging) {
            dragging = false;
            return true;
        }
        if (ev.getAction() == MotionEvent.ACTION_UP) {
            float x = ev.getX(), y = ev.getY();
            boolean visible = controlsAlpha() > 0.3f;
            if (call != null && SystemClock.uptimeMillis() - callAt < CALL_MS) call = null;
            else if (lookup != null) {
                if (hitLookupOk.contains(x, y)) closeLookup();
                else keepLookup();
            } else if (searchOn) searchTouch(x, y);
            else if (hitSearch.contains(x, y)) activate(0);
            else if (visible && hitClose.contains(x, y)) activate(4);
            else if (visible && hitVoice.contains(x, y)) activate(3);
            else if (visible && hitDur.contains(x, y)) activate(2);
            else if (visible && hitBright.contains(x, y)) activate(1);
            controlsAt = SystemClock.uptimeMillis();
            invalidate();
        }
        return true;
    }

    private void activate(int i) {
        if (i == 0) {
            openSearch();
        } else if (i == 1) {
            brightMode = (brightMode + 1) % 4;
            if (listener != null) listener.onBrightness(brightMode);
        } else if (i == 2) {
            int j = 0;
            while (j < DURATIONS.length && DURATIONS[j] != duration) j++;
            duration = DURATIONS[(j + 1) % DURATIONS.length];
            if (listener != null) listener.onDuration(duration);
        } else if (i == 3) {
            voiceOn = !voiceOn;
            if (listener != null) listener.onVoice(voiceOn);
        } else if (listener != null) {
            listener.onClose();
        }
    }

    /** TV remote: LEFT/RIGHT between SEARCH ... close, OK presses, BACK closes. */
    boolean handleKey(KeyEvent e) {
        if (lookup != null) {
            int k = e.getKeyCode();
            if (e.getAction() == KeyEvent.ACTION_UP && (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_DPAD_CENTER
                    || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A)) {
                closeLookup();
            }
            return true;
        }
        if (searchOn) return searchKey(e);
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_BACK:
                if (!down && listener != null) listener.onClose();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (maxScroll > 0 && getWidth() < getHeight() && (focus < 0 || controlsAlpha() < 0.3f)) {
                    // upright screen: up / down scroll the orders
                    if (down) {
                        scrollY = Math.max(0, Math.min(maxScroll,
                                scrollY + (k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1) * (boardBottom - boardTop) * 0.6f));
                        invalidate();
                    }
                    return true;
                }
                // fall through: move between the buttons
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) {
                    if (focus < 0 || controlsAlpha() < 0.3f) focus = 3;
                    else {
                        int[] order = {0, 1, 3, 4};   // search, brightness, voice, close
                        int at = 0;
                        while (at < order.length - 1 && order[at] != focus) at++;
                        at = Math.max(0, Math.min(order.length - 1,
                                at + (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
                        focus = order[at];
                    }
                    controlsAt = SystemClock.uptimeMillis();
                    invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (!down) {
                    if (focus < 0 || controlsAlpha() < 0.3f) focus = 3;
                    else activate(focus);
                    controlsAt = SystemClock.uptimeMillis();
                    invalidate();
                }
                return true;
            default:
                if (down) controlsAt = SystemClock.uptimeMillis();
                return true;
        }
    }

    // ---- search ----------------------------------------------------------------------------

    void openSearch() {
        searchOn = true;
        sFocus = -1;
        invalidate();
    }

    private void closeSearch() {
        searchOn = false;
        query.setLength(0);
        sFocus = -1;
        controlsAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private static int digitOf(int k) {
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) return k - KeyEvent.KEYCODE_0;
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) return k - KeyEvent.KEYCODE_NUMPAD_0;
        return -1;
    }

    /**
     * Number keys / Delete from a keyboard, number pad or remote (they reach the board before the
     * KDS). A number opens the search. A barcode scan arrives the same way, very fast: see
     * {@link #undoScan()}.
     */
    boolean typeKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean del = k == KeyEvent.KEYCODE_DEL || k == KeyEvent.KEYCODE_FORWARD_DEL;
        int dg = digitOf(k);
        if (dg < 0 && !(del && searchOn)) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            long now = e.getEventTime();
            if (now - lastTypeAt > 80) {
                onBeforeBurst = searchOn;
                queryBeforeBurst = query.toString();
            }
            lastTypeAt = now;
            if (!searchOn) openSearch();
            press(dg >= 0 ? (dg == 0 ? 10 : dg - 1) : 11);
        }
        return true;
    }

    /** A scan was read from the same keys: put the search back as it was before the burst. */
    void undoScan() {
        query.setLength(0);
        query.append(queryBeforeBurst);
        if (!onBeforeBurst) searchOn = false;
        invalidate();
    }

    private void press(int key) {
        keyFlash = key;
        keyFlashAt = SystemClock.uptimeMillis();
        if (key == 12) {
            closeSearch();
            return;
        }
        String kv = KEYS[key];
        if (key == 9) query.setLength(0);
        else if (key == 11) {
            if (query.length() > 0) query.setLength(query.length() - 1);
        } else if (query.length() < MAX_DIGITS) query.append(kv);
        invalidate();
    }

    private void searchTouch(float x, float y) {
        if (hitSearchClose.contains(x, y)) {
            press(12);
            return;
        }
        for (int i = 0; i < hitKeys.length; i++) {
            if (hitKeys[i].contains(x, y)) {
                sFocus = -1;
                press(i);
                return;
            }
        }
    }

    /** Remote on the search screen: arrows move over the keypad (up from the top row = close). */
    private boolean searchKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_BACK:
                if (!down) closeSearch();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (sFocus < 0) sFocus = 4;
                    else if (sFocus == 12) sFocus = k == KeyEvent.KEYCODE_DPAD_DOWN ? 2 : 12;
                    else {
                        int r = sFocus / 3, col = sFocus % 3;
                        if (k == KeyEvent.KEYCODE_DPAD_LEFT) col = Math.max(0, col - 1);
                        else if (k == KeyEvent.KEYCODE_DPAD_RIGHT) col = Math.min(2, col + 1);
                        else if (k == KeyEvent.KEYCODE_DPAD_DOWN) r = Math.min(3, r + 1);
                        else r = r - 1;
                        sFocus = r < 0 ? 12 : r * 3 + col;
                    }
                    invalidate();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (!down) {
                    if (sFocus < 0) sFocus = 4;
                    else press(sFocus);
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }

    private float controlsAlpha() {
        long since = SystemClock.uptimeMillis() - controlsAt;
        if (since <= CONTROLS_MS) return 1f;
        return Math.max(0f, 1f - (since - CONTROLS_MS) / (float) CONTROLS_FADE_MS);
    }

    // ---- drawing ---------------------------------------------------------------------------

    // ---- board: sunlight design ------------------------------------------------------------
    //
    // Light screen (riders come in from the sun; light backgrounds show less glare), one section
    // per brand (most orders first) with a solid brand strip, and inside it READY cards first
    // (white, green "READY" bar, black slot box with yellow digits) then PREPARING cards (grey,
    // amber bar), each in number order (last 4 digits). Every card: brand badge, last 4, slot,
    // full order ID, and its timers. The top bar shows the totals and each brand's counts.

    private static final int SUN_BG = 0xFFE9ECF1, SUN_INK = 0xFF0B0F17, SUN_MUTE = 0xFF5A6372, SUN_LINE = 0xFFCDD3DC,
            SUN_GREEN = 0xFF0A8F4A, SUN_AMBER = 0xFFB86E00, PREP_BG = 0xFFF4F5F7, PREP_BAND = 0xFFFFE2A8,
            PREP_TEXT = 0xFF8A5200, PREP_INK = 0xFF4A5363, SLOT_BG = 0xFF111418, SLOT_FG = 0xFFFFD400;

    /** One card: a ready (Waiting) or a preparing order. */
    private static final class Item {
        final RiderCalls.Order o;
        final boolean ready;
        final long readyAt;

        Item(RiderCalls.Order o, boolean ready, long readyAt) {
            this.o = o;
            this.ready = ready;
            this.readyAt = readyAt;
        }
    }

    private static final class Group {
        final String brand;
        final int color;
        final List<Item> items = new ArrayList<Item>();
        int nReady, nPrep;

        Group(String brand, int color) {
            this.brand = brand;
            this.color = color;
        }
    }

    private List<Group> groups = new ArrayList<Group>();
    private int removedCount;
    private List<RiderCalls.Removal> removals = new ArrayList<RiderCalls.Removal>();
    private Typeface cond, condX;
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics fm = new Paint.FontMetrics();

    void setRemovals(int count, List<RiderCalls.Removal> list) {
        removedCount = count;
        removals = list;
    }

    /** Brand sections (most orders first); inside: ready then preparing, each by last 4 digits. */
    private void buildGroups() {
        Map<String, Group> m = new HashMap<String, Group>();
        for (RiderCalls.Waiting w : ready) {
            Group g = group(m, w.order.platform);
            g.items.add(new Item(w.order, true, w.readyAt));
            g.nReady++;
        }
        for (RiderCalls.Order o : preparing) {
            Group g = group(m, o.platform);
            g.items.add(new Item(o, false, 0));
            g.nPrep++;
        }
        List<Group> out = new ArrayList<Group>(m.values());
        java.util.Collections.sort(out, new java.util.Comparator<Group>() {
            @Override public int compare(Group a, Group b) {
                int c = Integer.compare(b.items.size(), a.items.size());
                if (c != 0) return c;
                c = Integer.compare(RiderCalls.brandRank(a.brand), RiderCalls.brandRank(b.brand));
                return c != 0 ? c : a.brand.compareToIgnoreCase(b.brand);
            }
        });
        for (Group g : out) {
            java.util.Collections.sort(g.items, new java.util.Comparator<Item>() {
                @Override public int compare(Item a, Item b) {
                    if (a.ready != b.ready) return a.ready ? -1 : 1;
                    return a.o.last4.compareTo(b.o.last4);
                }
            });
        }
        groups = out;
    }

    private static Group group(Map<String, Group> m, String brand) {
        String b = brand == null || brand.trim().isEmpty() ? "Online" : brand.trim();
        Group g = m.get(b);
        if (g == null) {
            g = new Group(b, platformColor(b));
            m.put(b, g);
        }
        return g;
    }

    private void fonts() {
        if (cond != null) return;
        try {
            cond = Typeface.createFromAsset(getContext().getAssets(), "rider_cond_bold.ttf");
            condX = Typeface.createFromAsset(getContext().getAssets(), "rider_cond_xbold.ttf");
        } catch (Throwable t) {
            cond = condensed;
            condX = condensed;
        }
    }

    /** Text centred vertically on cy. ls: letter spacing in px. */
    private void mid(Canvas c, String str, float x, float cy, Typeface tf, float size, int color, Paint.Align al, float ls) {
        text.setTypeface(tf);
        text.setTextSize(size);
        text.setColor(color);
        text.setTextAlign(al);
        text.setLetterSpacing(size > 0 ? ls / size : 0f);
        text.getFontMetrics(fm);
        c.drawText(str, x, cy - (fm.ascent + fm.descent) / 2f, text);
        text.setLetterSpacing(0f);
    }

    private float width(String str, Typeface tf, float size, float ls) {
        text.setTypeface(tf);
        text.setTextSize(size);
        text.setLetterSpacing(size > 0 ? ls / size : 0f);
        float wd = text.measureText(str);
        text.setLetterSpacing(0f);
        return wd;
    }

    /** "✓ 6m" style: the symbol from the system font (it has the glyph), the time in the TV font. Returns width. */
    private float iconText(Canvas c, String sym, String str, float x, float cy, float size, int color, boolean right) {
        icon.setTypeface(Typeface.DEFAULT_BOLD);
        icon.setTextSize(size * 0.92f);
        icon.setColor(color);
        icon.setTextAlign(Paint.Align.LEFT);
        float iw = icon.measureText(sym) + size * 0.22f, tw = width(str, condX, size, 0);
        float x0 = right ? x - iw - tw : x;
        icon.getFontMetrics(fm);
        c.drawText(sym, x0, cy - (fm.ascent + fm.descent) / 2f, icon);
        mid(c, str, x0 + iw, cy, condX, size, color, Paint.Align.LEFT, 0);
        return iw + tw;
    }

    private static String mins(long ms, boolean nowWord) {
        if (ms < 60_000L && nowWord) return "NOW";
        long m = Math.max(0, ms / 60_000L);
        return m < 60 ? m + "m" : (m / 60) + "h" + (m % 60 < 10 ? "0" : "") + (m % 60);
    }

    private void rounded(Canvas c, float l, float t, float r, float b, float rad, int color) {
        rect.set(l, t, r, b);
        fill.setColor(color);
        c.drawRoundRect(rect, rad, rad, fill);
    }

    @Override
    protected void onDraw(Canvas c) {
        long frameStart = SystemClock.uptimeMillis();
        float w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        long now = SystemClock.uptimeMillis();
        float t = ((now - start) % (6 * 3600_000L)) / 1000f;
        boolean land = w >= h;
        s = land ? Math.min(w / 1920f, h / 1080f) : Math.min(w / 1080f, h / 1920f);
        fonts();
        // System bars hide 10 s after the last touch / remote key (the layout stays the same).
        boolean wantFull = now - controlsAt >= FULL_AFTER_MS;
        if (wantFull != immersive) setImmersive(wantFull);

        fill.setShader(null);
        fill.setColor(SUN_BG);
        c.drawRect(0, 0, w, h, fill);
        long wall = System.currentTimeMillis();
        if (land) {
            drawSunHeader(c, w);
            drawSunBoard(c, w, h, wall);
            drawLegend(c, w, h);
        } else {
            drawPortraitBoard(c, w, h, wall);
            drawPortraitHeader(c, w);
            drawPortraitLegend(c, w, h);
        }
        drawControls(c, w);
        if (searchOn) drawSearch(c, w, h, now, land);
        if (lookup != null && now - lookup.seenAt > LOOKUP_IDLE_MS) closeLookup(false);
        if (lookup != null) drawLookup(c, w, h, now, land);
        drawCall(c, w, h, now, t);

        if (lite) postInvalidateDelayed(Math.max(1L, 33L - (SystemClock.uptimeMillis() - frameStart)));
        else postInvalidateOnAnimation();
    }

    private void drawSunHeader(Canvas c, float w) {
        float hh = 84 * s;
        fill.setColor(Color.WHITE);
        c.drawRect(0, 0, w, hh, fill);
        fill.setColor(SUN_LINE);
        c.drawRect(0, hh, w, hh + 2 * s, fill);
        headCy = 42 * s;
        headH = hh;
        // logo + title
        rounded(c, 24 * s, 18 * s, 72 * s, 66 * s, 12 * s, SUN_GREEN);
        icon.setTypeface(Typeface.DEFAULT);
        icon.setTextSize(24 * s);
        icon.setTextAlign(Paint.Align.CENTER);
        icon.getFontMetrics(fm);
        c.drawText("🛵", 48 * s, 42 * s - (fm.ascent + fm.descent) / 2f, icon);
        mid(c, "RIDER PICKUP", 86 * s, 36 * s, condX, 36 * s, SUN_INK, Paint.Align.LEFT, 3 * s);
        mid(c, "FIND YOUR NUMBER  ·  GO TO THE SLOT", 86 * s, 64 * s, bold, 13 * s, SUN_MUTE, Paint.Align.LEFT, 2 * s);
        // clock (right)
        long nowMs = System.currentTimeMillis();
        if (nowMs - clockAt >= 1000) {
            clockAt = nowMs;
            clockStr = clock.format(new Date(nowMs));
        }
        String main = clockStr, suf = "";
        int sp = clockStr.lastIndexOf(' ');
        if (sp > 0 && clockStr.length() - sp <= 4) {
            main = clockStr.substring(0, sp);
            suf = clockStr.substring(sp + 1);
        }
        float sw = suf.isEmpty() ? 0 : width(suf, condX, 18 * s, 1 * s) + 6 * s;
        float cr = w - 24 * s;
        if (sw > 0) mid(c, suf, cr, 48 * s, condX, 18 * s, SUN_MUTE, Paint.Align.RIGHT, 1 * s);
        mid(c, main, cr - sw, 42 * s, condX, 44 * s, SUN_INK, Paint.Align.RIGHT, 0);
        clockLeft = cr - sw - width(main, condX, 44 * s, 0);
        // totals box
        int nr = ready.size(), np = preparing.size();
        float x = Math.max(86 * s + width("RIDER PICKUP", condX, 36 * s, 3 * s) + 40 * s, w * 0.255f);
        float tx = x + 14 * s;
        String[] lab = {"TOTAL", "READY", "PREPARING"};
        String[] val = {String.valueOf(nr + np), String.valueOf(nr), String.valueOf(np)};
        int[] col = {SUN_INK, SUN_GREEN, SUN_AMBER};
        float bx = x, bxEnd = tx;
        for (int i = 0; i < 3; i++) bxEnd += Math.max(width(lab[i], condX, 13 * s, 2 * s), width(val[i], condX, 30 * s, 0)) + 22 * s;
        rect.set(bx, 14 * s, bxEnd - 8 * s, 70 * s);
        fill.setColor(0xFFF1F3F6);
        c.drawRoundRect(rect, 12 * s, 12 * s, fill);
        stroke.setColor(SUN_LINE);
        stroke.setStrokeWidth(1.2f * s);
        c.drawRoundRect(rect, 12 * s, 12 * s, stroke);
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                fill.setColor(SUN_LINE);
                c.drawRect(tx - 11 * s, 24 * s, tx - 9.5f * s, 60 * s, fill);
            }
            mid(c, lab[i], tx, 30 * s, condX, 13 * s, i == 0 ? SUN_MUTE : col[i], Paint.Align.LEFT, 2 * s);
            mid(c, val[i], tx, 54 * s, condX, 30 * s, col[i], Paint.Align.LEFT, 0);
            tx += Math.max(width(lab[i], condX, 13 * s, 2 * s), width(val[i], condX, 30 * s, 0)) + 22 * s;
        }
        // each brand: badge, ready count, prep count
        x = bxEnd + 22 * s;
        for (Group g : groups) {
            String r = String.valueOf(g.nReady), pr = g.nPrep + " PREP";
            float need = 52 * s + width(r, condX, 38 * s, 0) + 5 * s + width(pr, condX, 13 * s, 1.5f * s) + 30 * s;
            if (x + need > clockLeft - 96 * s) break;   // room for the search button
            rounded(c, x, 22 * s, x + 40 * s, 62 * s, 9 * s, g.color);
            mid(c, badge(g.brand), x + 20 * s, 42 * s, condX, 30 * s, Color.WHITE, Paint.Align.CENTER, 0);
            mid(c, r, x + 52 * s, 42 * s, condX, 38 * s, SUN_GREEN, Paint.Align.LEFT, 0);
            float wx = x + 52 * s + width(r, condX, 38 * s, 0) + 5 * s;
            mid(c, "READY", wx, 35 * s, condX, 13 * s, SUN_GREEN, Paint.Align.LEFT, 1.5f * s);
            mid(c, pr, wx, 53 * s, condX, 13 * s, SUN_AMBER, Paint.Align.LEFT, 1.5f * s);
            x += need;
        }
    }

    private static String badge(String brand) {
        String b = brand == null ? "" : brand.trim();
        return b.isEmpty() ? "?" : b.substring(0, 1).toUpperCase(Locale.US);
    }

    /**
     * TV (landscape): every brand is one block in its own colour (Swiggy orange, Zomato red ...),
     * name and counts on top, its cards inside. Columns are chosen for the biggest cards that fit.
     */
    private void drawSunBoard(Canvas c, float w, float h, long wall) {
        float left = 16 * s, right = w - 16 * s, top = 94 * s, bottom = h - 40 * s, gap = 7 * s, stripH = 36 * s,
                secGap = 8 * s, pad = 9 * s;
        if (groups.isEmpty()) {
            mid(c, "No online orders right now", w / 2f, (top + bottom) / 2f, condX, 40 * s, SUN_MUTE, Paint.Align.CENTER, 2 * s);
            return;
        }
        float cl = left + pad, cr = right - pad;
        int bestCols = 4;
        float bestCw = 0, bestRh = 0, bestSc = -1;
        for (int cols = 4; cols <= 14; cols++) {
            int rows = 0;
            for (Group g : groups) rows += (g.items.size() + cols - 1) / cols;
            float cw = (cr - cl - (cols - 1) * gap) / cols;
            float rh = (bottom - top - groups.size() * (stripH + secGap + pad) - (rows - groups.size()) * gap) / rows;
            float sc = Math.min(rh, cw / 2.7f);
            if (sc > bestSc) {
                bestSc = sc;
                bestCols = cols;
                bestCw = cw;
                bestRh = rh;
            }
        }
        int cols = bestCols;
        float cw = bestCw, ch = Math.min(bestRh, cw / 2.3f), y = top;
        for (Group g : groups) {
            y = drawZone(c, g, left, right, y, cols, cw, ch, gap, stripH, pad, 24 * s, 19 * s, wall, -1, -1) + secGap;
        }
    }

    /** One brand block; returns its bottom. Cards outside clipTop..clipBottom are skipped (phone scrolling). */
    private float drawZone(Canvas c, Group g, float left, float right, float y, int cols, float cw, float ch, float gap,
                           float stripH, float pad, float nameSize, float countSize, long wall, float clipTop, float clipBottom) {
        int rows = (g.items.size() + cols - 1) / cols;
        float zoneH = stripH + rows * (ch + gap) - gap + pad;
        if (clipTop >= 0 && (y > clipBottom || y + zoneH < clipTop)) return y + zoneH;
        rounded(c, left, y, right, y + zoneH, 12 * s, g.color);
        float sy = y + stripH / 2f;
        mid(c, g.brand.toUpperCase(Locale.US), left + 16 * s, sy, condX, nameSize, Color.WHITE, Paint.Align.LEFT, 5 * s);
        mid(c, g.nReady + " READY   ·   " + g.nPrep + " PREPARING", right - 16 * s, sy, condX, countSize, Color.WHITE,
                Paint.Align.RIGHT, 2 * s);
        float y0 = y + stripH, cl = left + pad;
        for (int i = 0; i < g.items.size(); i++) {
            Item it = g.items.get(i);
            float cx = cl + (i % cols) * (cw + gap), cy = y0 + (i / cols) * (ch + gap);
            if (clipTop >= 0 && (cy > clipBottom || cy + ch < clipTop)) continue;
            if (it.ready) readyCard(c, it, g, cx, cy, cw, ch, wall);
            else prepCard(c, it, g, cx, cy, cw, ch, wall);
        }
        return y + zoneH;
    }

    // ---- phone (portrait): header in two rows, brands stacked, 3 cards a row, scrolls with a finger

    private float scrollY, maxScroll, boardTop, boardBottom;

    private void drawPortraitHeader(Canvas c, float w) {
        float row1 = 96 * s, hh = 252 * s;
        fill.setColor(Color.WHITE);
        c.drawRect(0, 0, w, hh, fill);
        fill.setColor(SUN_LINE);
        c.drawRect(0, hh, w, hh + 2 * s, fill);
        headCy = 50 * s;
        headH = row1;
        rounded(c, 24 * s, 20 * s, 84 * s, 80 * s, 14 * s, SUN_GREEN);
        icon.setTypeface(Typeface.DEFAULT);
        icon.setTextSize(30 * s);
        icon.setTextAlign(Paint.Align.CENTER);
        icon.getFontMetrics(fm);
        c.drawText("🛵", 54 * s, 50 * s - (fm.ascent + fm.descent) / 2f, icon);
        mid(c, "RIDER PICKUP", 100 * s, 42 * s, condX, 44 * s, SUN_INK, Paint.Align.LEFT, 3 * s);
        mid(c, "FIND YOUR NUMBER · GO TO THE SLOT", 100 * s, 74 * s, bold, 16 * s, SUN_MUTE, Paint.Align.LEFT, 1.5f * s);
        long nowMs = System.currentTimeMillis();
        if (nowMs - clockAt >= 1000) {
            clockAt = nowMs;
            clockStr = clock.format(new Date(nowMs));
        }
        String main = clockStr, suf = "";
        int sp = clockStr.lastIndexOf(' ');
        if (sp > 0 && clockStr.length() - sp <= 4) {
            main = clockStr.substring(0, sp);
            suf = clockStr.substring(sp + 1);
        }
        float sw = suf.isEmpty() ? 0 : width(suf, condX, 20 * s, 1 * s) + 6 * s;
        float cr = w - 24 * s;
        if (sw > 0) mid(c, suf, cr, 56 * s, condX, 20 * s, SUN_MUTE, Paint.Align.RIGHT, 1 * s);
        mid(c, main, cr - sw, 50 * s, condX, 48 * s, SUN_INK, Paint.Align.RIGHT, 0);
        clockLeft = cr - sw - width(main, condX, 48 * s, 0);
        // totals
        float left = 14 * s, right = w - 14 * s, y0 = 108 * s, h0 = 128 * s;
        rect.set(left, y0, right, y0 + h0);
        fill.setColor(0xFFF1F3F6);
        c.drawRoundRect(rect, 14 * s, 14 * s, fill);
        int nr = ready.size(), np = preparing.size();
        String[] lab = {"TOTAL", "READY", "PREPARING"};
        String[] val = {String.valueOf(nr + np), String.valueOf(nr), String.valueOf(np)};
        int[] col = {SUN_INK, SUN_GREEN, SUN_AMBER};
        float cw0 = (right - left) / 3f;
        for (int i = 0; i < 3; i++) {
            float cx = left + cw0 * i + cw0 / 2f;
            mid(c, lab[i], cx, y0 + 30 * s, condX, 18 * s, i == 0 ? SUN_MUTE : col[i], Paint.Align.CENTER, 2 * s);
            mid(c, val[i], cx, y0 + 80 * s, condX, 56 * s, col[i], Paint.Align.CENTER, 0);
            if (i > 0) {
                fill.setColor(SUN_LINE);
                c.drawRect(left + cw0 * i, y0 + 18 * s, left + cw0 * i + 2 * s, y0 + h0 - 18 * s, fill);
            }
        }
    }

    /** Upright screen: everything fits, no scrolling; columns chosen for the biggest cards that fit. */
    private void drawPortraitBoard(Canvas c, float w, float h, long wall) {
        float left = 14 * s, right = w - 14 * s, gap = 7 * s, pad = 8 * s, secGap = 10 * s;
        boardTop = 254 * s;
        boardBottom = h - 64 * s;
        scrollY = maxScroll = 0;
        if (groups.isEmpty()) {
            mid(c, "No online orders right now", w / 2f, (boardTop + boardBottom) / 2f, condX, 40 * s, SUN_MUTE,
                    Paint.Align.CENTER, 2 * s);
            return;
        }
        // fewer orders -> taller brand headers; many -> slimmer, so the cards keep the room
        int total = 0;
        for (Group g : groups) total += g.items.size();
        float stripH = (total > 60 ? 40 : 56) * s;
        float avail = boardBottom - boardTop - 10 * s;
        int bestCols = 2;
        float bestCw = 0, bestCh = 0, bestSc = -1;
        for (int cols = 2; cols <= 12; cols++) {
            int rows = 0;
            for (Group g : groups) rows += (g.items.size() + cols - 1) / cols;
            float cw = (right - left - 2 * pad - (cols - 1) * gap) / cols;
            float rh = (avail - groups.size() * (stripH + pad + secGap) - (rows - groups.size()) * gap) / rows;
            float ch = Math.min(rh, cw / 2.25f);
            float sc = Math.min(ch, cw / 2.7f);
            if (rh > 0 && sc > bestSc) {
                bestSc = sc;
                bestCols = cols;
                bestCw = cw;
                bestCh = ch;
            }
        }
        float y = boardTop + 10 * s, nameSize = Math.min(30 * s, stripH * 0.55f), countSize = Math.min(24 * s, stripH * 0.45f);
        for (Group g : groups) {
            y = drawZone(c, g, left, right, y, bestCols, bestCw, bestCh, gap, stripH, pad, nameSize, countSize, wall, -1, -1)
                    + secGap;
        }
    }

    private void drawPortraitLegend(Canvas c, float w, float h) {
        fill.setColor(SUN_BG);
        c.drawRect(0, h - 62 * s, w, h, fill);
        float ly = h - 44 * s, lx = 16 * s;
        rect.set(lx, ly - 11 * s, lx + 34 * s, ly + 11 * s);
        fill.setColor(Color.WHITE);
        c.drawRoundRect(rect, 5 * s, 5 * s, fill);
        fill.setColor(SUN_GREEN);
        c.drawRect(lx, ly - 11 * s, lx + 34 * s, ly - 5 * s, fill);
        mid(c, "READY — collect from the slot", lx + 44 * s, ly, bold, 17 * s, SUN_INK, Paint.Align.LEFT, 0);
        lx = w / 2f + 20 * s;
        rect.set(lx, ly - 11 * s, lx + 34 * s, ly + 11 * s);
        fill.setColor(PREP_BG);
        c.drawRoundRect(rect, 5 * s, 5 * s, fill);
        fill.setColor(0xFFFFB020);
        c.drawRect(lx, ly - 11 * s, lx + 34 * s, ly - 5 * s, fill);
        mid(c, "PREPARING — please wait", lx + 44 * s, ly, bold, 17 * s, SUN_INK, Paint.Align.LEFT, 0);
        String rs = bridgeStatus == null ? "" : bridgeStatus;
        float size = fitSize(rs, medium, 14 * s, w - 50 * s);
        fill.setColor(bridgeOk ? SUN_GREEN : 0xFFE06A00);
        c.drawCircle(22 * s, h - 16 * s, 5 * s, fill);
        mid(c, rs, 34 * s, h - 16 * s, medium, size, SUN_MUTE, Paint.Align.LEFT, 0);
    }

    private void cardBadge(Canvas c, Group g, float x, float cy, float size) {
        rounded(c, x, cy - size / 2f, x + size, cy + size / 2f, size * 0.22f, g.color);
        mid(c, badge(g.brand), x + size / 2f, cy, condX, size * 0.8f, Color.WHITE, Paint.Align.CENTER, 0);
    }

    private void readyCard(Canvas c, Item it, Group g, float x, float y, float w, float h, long wall) {
        RiderCalls.Order o = it.o;
        rect.set(x, y, x + w, y + h);
        fill.setColor(Color.WHITE);
        c.drawRoundRect(rect, 9 * s, 9 * s, fill);
        // green status bar (rounded top)
        float bh = Math.max(16 * s, h * 0.23f);
        c.save();
        c.clipRect(x, y, x + w, y + bh);
        fill.setColor(SUN_GREEN);
        c.drawRoundRect(rect, 9 * s, 9 * s, fill);
        c.restore();
        stroke.setColor(SUN_LINE);
        stroke.setStrokeWidth(1.2f * s);
        c.drawRoundRect(rect, 9 * s, 9 * s, stroke);
        float fz = Math.min(bh * 0.68f, 16 * s), by = y + bh / 2f;
        mid(c, "READY", x + 9 * s, by, condX, fz, Color.WHITE, Paint.Align.LEFT, 2 * s);
        iconText(c, "✓", mins(wall - it.readyAt, true), x + w - 9 * s, by, fz, Color.WHITE, true);
        float body = h - bh, line1 = y + bh + body * 0.43f, big = Math.min(body * 0.6f, 52 * s);
        cardBadge(c, g, x + 8 * s, line1, big * 0.78f);
        float sw = Math.min(w * 0.32f, big * 1.8f), sh = big * 1.04f, sx = x + w - sw - 7 * s;
        rounded(c, sx, line1 - sh / 2f, sx + sw, line1 + sh / 2f, 7 * s, SLOT_BG);
        String slot = o.slot == null || o.slot.isEmpty() ? "—" : o.slot;
        mid(c, slot, sx + sw / 2f, line1, condX, Math.min(big * 0.92f, fitSize(slot, condX, big * 0.92f, sw - 8 * s)),
                SLOT_FG, Paint.Align.CENTER, 0);
        float nx = x + 8 * s + big * 0.78f + 8 * s;
        mid(c, o.last4, nx, line1, condX, fitSize(o.last4, condX, big, sx - nx - 6 * s), SUN_INK, Paint.Align.LEFT, 0.5f * s);
        bottomLine(c, o, x, y, w, h, body, wall, SUN_MUTE, 0xFF3C4452);
    }

    private void prepCard(Canvas c, Item it, Group g, float x, float y, float w, float h, long wall) {
        RiderCalls.Order o = it.o;
        rect.set(x, y, x + w, y + h);
        fill.setColor(PREP_BG);
        c.drawRoundRect(rect, 9 * s, 9 * s, fill);
        float bh = Math.max(16 * s, h * 0.23f);
        c.save();
        c.clipRect(x, y, x + w, y + bh);
        fill.setColor(PREP_BAND);
        c.drawRoundRect(rect, 9 * s, 9 * s, fill);
        c.restore();
        float fz = Math.min(bh * 0.68f, 16 * s), by = y + bh / 2f;
        mid(c, "PREPARING", x + 9 * s, by, condX, fz, PREP_TEXT, Paint.Align.LEFT, 2 * s);
        iconText(c, "🔥", o.createdMs > 0 ? mins(wall - o.createdMs, false) : "—", x + w - 9 * s, by, fz, PREP_TEXT, true);
        float body = h - bh, line1 = y + bh + body * 0.43f, big = Math.min(body * 0.56f, 46 * s);
        cardBadge(c, g, x + 8 * s, line1, big * 0.74f);
        float sw = Math.min(w * 0.32f, big * 1.8f), sh = big, sx = x + w - sw - 7 * s;
        rounded(c, sx, line1 - sh / 2f, sx + sw, line1 + sh / 2f, 7 * s, 0xB3FFFFFF);
        rect.set(sx, line1 - sh / 2f, sx + sw, line1 + sh / 2f);
        stroke.setColor(0xFFAEB6C2);
        stroke.setStrokeWidth(1.5f * s);
        c.drawRoundRect(rect, 7 * s, 7 * s, stroke);
        boolean hasSlot = o.slot != null && !o.slot.isEmpty();
        String slot = hasSlot ? o.slot : "—";
        mid(c, slot, sx + sw / 2f, line1, condX, Math.min(big * 0.86f, fitSize(slot, condX, big * 0.86f, sw - 8 * s)),
                hasSlot ? PREP_INK : 0xFF9AA3B0, Paint.Align.CENTER, 0);
        float nx = x + 8 * s + big * 0.74f + 8 * s;
        mid(c, o.last4, nx, line1, condX, fitSize(o.last4, condX, big, sx - nx - 6 * s), PREP_INK, Paint.Align.LEFT, 0.5f * s);
        bottomLine(c, o, x, y, w, h, body, wall, 0xFF6B7383, 0xFF6B7383);
    }

    /** Full order ID (shortened only when the card is narrow) and ⏱ time since the order came in. */
    private void bottomLine(Canvas c, RiderCalls.Order o, float x, float y, float w, float h, float body, long wall,
                            int timeColor, int idColor) {
        float l2 = y + h - body * 0.15f, fz2 = Math.max(9 * s, Math.min(body * 0.2f, 14 * s));
        float tw = o.createdMs > 0 ? iconText(c, "⏱", mins(wall - o.createdMs, false), x + w - 8 * s, l2, fz2 * 1.08f,
                timeColor, true) : 0;
        String id = o.orderId == null || o.orderId.isEmpty() ? o.last4 : o.orderId;
        float room = w - 26 * s - tw;
        if (width(id, bold, fz2 * 0.92f, 0) > room) {
            id = "…" + id.substring(Math.max(0, id.length() - 10));
            if (width(id, bold, fz2 * 0.92f, 0) > room) id = "…" + o.orderId.substring(Math.max(0, o.orderId.length() - 7));
        }
        mid(c, id, x + 9 * s, l2, bold, fz2 * 0.92f, idColor, Paint.Align.LEFT, 0);
    }

    private float fitSize(String str, Typeface tf, float size, float maxW) {
        float wd = width(str, tf, size, 0);
        return wd > maxW && wd > 0 ? size * maxW / wd : size;
    }

    /** Bottom line: what the colours mean, slot source, and why orders left (for checking). */
    private void drawLegend(Canvas c, float w, float h) {
        float ly = h - 20 * s, lx = 20 * s;
        rect.set(lx, ly - 11 * s, lx + 34 * s, ly + 11 * s);
        fill.setColor(Color.WHITE);
        c.drawRoundRect(rect, 5 * s, 5 * s, fill);
        fill.setColor(SUN_GREEN);
        c.drawRect(lx, ly - 11 * s, lx + 34 * s, ly - 5 * s, fill);
        mid(c, "READY — collect from the slot", lx + 44 * s, ly, bold, 14 * s, SUN_INK, Paint.Align.LEFT, 0);
        lx += 300 * s;
        rect.set(lx, ly - 11 * s, lx + 34 * s, ly + 11 * s);
        fill.setColor(PREP_BG);
        c.drawRoundRect(rect, 5 * s, 5 * s, fill);
        fill.setColor(0xFFFFB020);
        c.drawRect(lx, ly - 11 * s, lx + 34 * s, ly - 5 * s, fill);
        mid(c, "PREPARING — please wait", lx + 44 * s, ly, bold, 14 * s, SUN_INK, Paint.Align.LEFT, 0);
        lx += 270 * s;
        mid(c, "✓ since ready  ·  🔥 in kitchen  ·  ⏱ since order", lx, ly, medium, 13 * s, SUN_MUTE,
                Paint.Align.LEFT, 0);
        // right: slot source + removals
        StringBuilder r = new StringBuilder(bridgeStatus == null ? "" : bridgeStatus);
        if (removedCount > 0 && !removals.isEmpty()) {
            RiderCalls.Removal last = removals.get(0);
            r.append("   ·   Left the screen: ").append(removedCount).append(" (last ").append(badge(last.platform))
                    .append(' ').append(last.last4).append(": ").append(last.reason).append(')');
        }
        float maxW = w - 20 * s - (lx + width("✓ since ready  ·  🔥 in kitchen  ·  ⏱ since order", medium,
                13 * s, 0) + 30 * s);
        String rs = r.toString();
        float size = fitSize(rs, medium, 13 * s, Math.max(10 * s, maxW));
        fill.setColor(bridgeOk ? SUN_GREEN : 0xFFE06A00);
        float rw = width(rs, medium, size, 0);
        c.drawCircle(w - 20 * s - rw - 12 * s, ly, 5 * s, fill);
        mid(c, rs, w - 20 * s, ly, medium, size, SUN_MUTE, Paint.Align.RIGHT, 0);
    }

    private void drawControls(Canvas c, float w) {
        float a = controlsAlpha();
        hitVoice.setEmpty();
        hitClose.setEmpty();
        hitDur.setEmpty();   // "show N min" is gone: ready orders stay until dispatched
        hitBright.setEmpty();
        hitSearch.setEmpty();
        // Search: a round button left of the clock while the controls are hidden; a SEARCH ORDER
        // pill at the start of the controls row while they show.
        if (a < 1f && !searchOn) {
            float r = Math.min(24 * s, headH * 0.32f), bx = clockLeft - 40 * s - r;
            int ia = (int) (255 * (1f - a));
            fill.setColor(alpha(0xFFF5B21B, ia));
            c.drawCircle(bx, headCy, r, fill);
            drawGlass(c, bx, headCy, r * 0.42f, alpha(0xFF1B1203, ia));
            if (a <= 0.3f) {
                hitSearch.set(bx - r, headCy - r, bx + r, headCy + r);
                hitSearch.inset(-12 * s, -12 * s);
            }
        }
        if (a <= 0f) return;
        float h = 44 * s, y = headCy - h / 2f, right = clockLeft - 24 * s;
        String v = voiceOn ? "🔊  CALLS ON" : "🔇  CALLS OFF";
        String bl = brightMode == 0 ? (hasSensor ? "☀  AUTO" : "☀  AUTO (MAX)")
                : "☀  " + (brightMode == 1 ? 50 : brightMode == 2 ? 75 : 100) + "%";
        String sl = "SEARCH ORDER";
        text.setTypeface(condensed);
        text.setLetterSpacing(0.1f);
        text.setTextSize(18 * s);
        float bw = text.measureText(v) + 36 * s, blw = text.measureText(bl) + 36 * s, sw = text.measureText(sl) + 72 * s;
        float leftEdge = right - h - 12 * s - bw - 12 * s - blw - 12 * s - sw;
        // white backing over the header counts while the controls show
        fill.setColor(alpha(Color.WHITE, (int) (250 * a)));
        c.drawRect(leftEdge - 16 * s, 0, right + 4 * s, headH, fill);
        // close
        rect.set(right - h, y, right, y + h);
        hitClose.set(rect);
        hitClose.inset(-10 * s, -10 * s);
        fill.setColor(alpha(0xFF263238, (int) (230 * a)));
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        stroke.setColor(alpha(Color.WHITE, (int) (240 * a)));
        stroke.setStrokeWidth(2.5f * s);
        float k = h * 0.2f, cx = rect.centerX(), cy = rect.centerY();
        c.drawLine(cx - k, cy - k, cx + k, cy + k, stroke);
        c.drawLine(cx - k, cy + k, cx + k, cy - k, stroke);
        // voice
        float bx = right - h - 12 * s;
        rect.set(bx - bw, y, bx, y + h);
        hitVoice.set(rect);
        hitVoice.inset(-6 * s, -10 * s);
        fill.setColor(alpha(voiceOn ? 0xFF1E88E5 : 0xFF546E7A, (int) (245 * a)));
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(alpha(Color.WHITE, (int) (255 * a)));
        c.drawText(v, rect.centerX(), rect.centerY() + 6 * s, text);
        // brightness
        float blx = bx - bw - 12 * s;
        rect.set(blx - blw, y, blx, y + h);
        hitBright.set(rect);
        hitBright.inset(-6 * s, -10 * s);
        fill.setColor(alpha(0xFF8D6E00, (int) (245 * a)));
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        text.setColor(alpha(Color.WHITE, (int) (255 * a)));
        c.drawText(bl, rect.centerX(), rect.centerY() + 6 * s, text);
        // search
        float sx = blx - blw - 12 * s;
        rect.set(sx - sw, y, sx, y + h);
        if (a > 0.3f) {
            hitSearch.set(rect);
            hitSearch.inset(-6 * s, -10 * s);
        }
        fill.setColor(alpha(0xFFF5B21B, (int) (250 * a)));
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        drawGlass(c, rect.left + 26 * s, rect.centerY(), 8 * s, alpha(0xFF1B1203, (int) (255 * a)));
        text.setColor(alpha(0xFF1B1203, (int) (255 * a)));
        c.drawText(sl, rect.centerX() + 14 * s, rect.centerY() + 6 * s, text);
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0f);
        if (focus >= 0) {
            RectF fr = focus == 0 ? hitSearch : focus == 1 ? hitBright : focus == 3 ? hitVoice : hitClose;
            rect.set(fr);
            rect.inset(focus == 4 ? 6 * s : 2 * s, 6 * s);
            stroke.setColor(alpha(0xFF0B0F17, (int) (255 * a)));
            stroke.setStrokeWidth(3 * s);
            c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, stroke);
        }
    }

    /** Magnifying-glass icon (drawn, so it looks the same on every TV box). */
    private void drawGlass(Canvas c, float cx, float cy, float r, int color) {
        stroke.setColor(color);
        stroke.setStrokeWidth(Math.max(2f, r * 0.38f));
        c.drawCircle(cx - r * 0.2f, cy - r * 0.2f, r, stroke);
        c.drawLine(cx + r * 0.52f, cy + r * 0.52f, cx + r * 1.25f, cy + r * 1.25f, stroke);
    }

    /** Search screen: keypad + typed digits on one side, matching orders (live) on the other. */
    private void drawSearch(Canvas c, float w, float h, long now, boolean land) {
        fill.setColor(0xEE05080D);
        c.drawRect(0, 0, w, h, fill);
        float cw = Math.min(w - 32 * s, 1800 * s), ch = land ? Math.min(h - 32 * s, 1040 * s) : h - 32 * s;
        float x = (w - cw) / 2f, y = (h - ch) / 2f;
        panel(c, x, y, cw, ch, 28 * s, 0xFF101722, 0x88F5B21B);
        float pad = 28 * s;

        // title + close
        drawGlass(c, x + pad + 14 * s, y + pad + 22 * s, 13 * s, GOLD);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setLetterSpacing(0.12f);
        text.setTextSize(36 * s);
        text.setColor(Color.WHITE);
        c.drawText("FIND ORDER", x + pad + 46 * s, y + pad + 36 * s, text);
        text.setLetterSpacing(0f);
        float cr = 30 * s, ccx = x + cw - pad - cr, ccy = y + pad + 22 * s;
        hitSearchClose.set(ccx - cr - 12 * s, ccy - cr - 12 * s, ccx + cr + 12 * s, ccy + cr + 12 * s);
        boolean flashClose = keyFlash == 12 && now - keyFlashAt < 150;
        fill.setColor(flashClose ? 0x66FFFFFF : 0x33FFFFFF);
        c.drawCircle(ccx, ccy, cr, fill);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(3 * s);
        float k = cr * 0.4f;
        c.drawLine(ccx - k, ccy - k, ccx + k, ccy + k, stroke);
        c.drawLine(ccx - k, ccy + k, ccx + k, ccy - k, stroke);
        if (sFocus == 12) {
            stroke.setStrokeWidth(4 * s);
            stroke.setColor(GOLD);
            c.drawCircle(ccx, ccy, cr + 6 * s, stroke);
        }

        float top = y + pad + 70 * s, bottom = y + ch - pad;
        float kx, ky, kw, kh, rx, ry, rw, rh;
        if (land) {
            kw = Math.min(cw * 0.36f, 560 * s);
            kx = x + pad;
            ky = top;
            kh = bottom - top;
            rx = kx + kw + 32 * s;
            ry = top;
            rw = x + cw - pad - rx;
            rh = kh;
        } else {
            kw = cw - 2 * pad;
            kx = x + pad;
            ky = top;
            kh = (bottom - top) * 0.5f;
            rx = kx;
            ry = ky + kh + 24 * s;
            rw = kw;
            rh = bottom - ry;
        }

        // typed digits
        float boxH = Math.min(120 * s, kh * 0.2f);
        rect.set(kx, ky, kx + kw, ky + boxH);
        fill.setColor(0xFF060A10);
        c.drawRoundRect(rect, 18 * s, 18 * s, fill);
        stroke.setColor(GOLD);
        stroke.setStrokeWidth(2.5f * s);
        c.drawRoundRect(rect, 18 * s, 18 * s, stroke);
        boolean blink = (now / 530) % 2 == 0;
        text.setTextAlign(Paint.Align.CENTER);
        if (query.length() == 0) {
            text.setTypeface(medium);
            text.setTextSize(fit("Last 4 digits of the order ID", boxH * 0.3f, kw - 40 * s));
            text.setColor(0x88FFFFFF);
            c.drawText("Last 4 digits of the order ID", rect.centerX(), rect.centerY() + text.getTextSize() * 0.36f, text);
        } else {
            text.setTypeface(bold);
            text.setLetterSpacing(0.25f);
            String q = query.toString() + (blink ? "_" : " ");
            text.setTextSize(fit(q, boxH * 0.62f, kw - 40 * s));
            text.setColor(Color.WHITE);
            c.drawText(q, rect.centerX(), rect.centerY() + text.getTextSize() * 0.36f, text);
            text.setLetterSpacing(0f);
        }

        // keypad 3 x 4
        float kgap = 14 * s, kTop = ky + boxH + 20 * s;
        float keyW = (kw - 2 * kgap) / 3f, keyH = Math.min(keyW * 0.7f, (ky + kh - kTop - 3 * kgap) / 4f);
        for (int i = 0; i < KEYS.length; i++) {
            int r = i / 3, col = i % 3;
            RectF kr = hitKeys[i];
            kr.set(kx + col * (keyW + kgap), kTop + r * (keyH + kgap), kx + col * (keyW + kgap) + keyW,
                    kTop + r * (keyH + kgap) + keyH);
            boolean flash = keyFlash == i && now - keyFlashAt < 150;
            boolean special = i == 9 || i == 11;
            fill.setColor(flash ? 0xFFF5B21B : special ? 0xFF2A3442 : 0xFF1F2A38);
            c.drawRoundRect(kr, 16 * s, 16 * s, fill);
            if (sFocus == i) {
                stroke.setColor(GOLD);
                stroke.setStrokeWidth(4 * s);
                c.drawRoundRect(kr, 16 * s, 16 * s, stroke);
            }
            text.setTypeface(special ? condensed : bold);
            text.setLetterSpacing(special && i == 9 ? 0.15f : 0f);
            text.setTextSize(fit(KEYS[i], keyH * (special ? 0.32f : 0.5f), keyW - 20 * s));
            text.setColor(flash ? 0xFF1B1203 : special ? 0xFFFFE082 : Color.WHITE);
            c.drawText(KEYS[i], kr.centerX(), kr.centerY() + text.getTextSize() * 0.36f, text);
        }
        text.setLetterSpacing(0f);

        drawResults(c, rx, ry, rw, rh, System.currentTimeMillis());
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawResults(Canvas c, float x, float y, float w, float h, long wall) {
        String q = query.toString();
        text.setTextAlign(Paint.Align.CENTER);
        if (q.isEmpty()) {
            text.setTypeface(medium);
            text.setColor(0xAAFFFFFF);
            String l1 = "Type the last 4 digits of the order ID";
            String l2 = "to see brand, order ID, slot and READY / PREPARING";
            text.setTextSize(fit(l1, 32 * s, w - 40 * s));
            c.drawText(l1, x + w / 2f, y + h / 2f - 10 * s, text);
            text.setTextSize(fit(l2, 22 * s, w - 40 * s));
            text.setColor(0x77FFFFFF);
            c.drawText(l2, x + w / 2f, y + h / 2f + 30 * s, text);
            return;
        }
        List<RiderCalls.Hit> hits = RiderCalls.find(q, ready, preparing);
        if (hits.isEmpty()) {
            text.setTypeface(bold);
            text.setColor(0xFFFF8A65);
            String l1 = "No order ending " + q + " on the board";
            text.setTextSize(fit(l1, 38 * s, w - 40 * s));
            c.drawText(l1, x + w / 2f, y + h / 2f - 12 * s, text);
            text.setTypeface(medium);
            text.setColor(0x99FFFFFF);
            String l2 = q.length() < 4 ? "Keep typing the last 4 digits" : "Already picked up, or not sent to the kitchen yet";
            text.setTextSize(fit(l2, 24 * s, w - 40 * s));
            c.drawText(l2, x + w / 2f, y + h / 2f + 30 * s, text);
            return;
        }
        // count line
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.15f);
        text.setTextSize(20 * s);
        text.setColor(0xCCF5B21B);
        c.drawText(hits.size() == 1 ? "1 ORDER FOUND" : hits.size() + " ORDERS FOUND", x, y + 20 * s, text);
        text.setLetterSpacing(0f);
        float top = y + 36 * s;
        float minRow = 110 * s, rowH = Math.min(230 * s, (y + h - top) / Math.max(1, hits.size()));
        int fit = hits.size();
        if (rowH < minRow) {
            rowH = minRow;
            fit = Math.max(1, (int) ((y + h - top - 40 * s) / rowH));
        }
        for (int i = 0; i < Math.min(fit, hits.size()); i++) {
            drawHit(c, hits.get(i), x, top + i * rowH, w, rowH - 12 * s, wall);
        }
        if (fit < hits.size()) {
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(medium);
            text.setTextSize(22 * s);
            text.setColor(0xAAFFFFFF);
            c.drawText("+" + (hits.size() - fit) + " more \u2022 type more digits", x + w / 2f, y + h - 8 * s, text);
        }
    }

    /** One result: [BRAND | ORDER ID 3136 + full ID | SLOT 47 | READY / PREPARING]. */
    private void drawHit(Canvas c, RiderCalls.Hit hit, float x, float y, float w, float h, long wall) {
        RiderCalls.Order o = hit.order;
        int pc = platformColor(o.platform);
        rect.set(x, y, x + w, y + h);
        fill.setColor(hit.ready ? 0xF2FFFFFF : 0x26FFFFFF);
        c.drawRoundRect(rect, 22 * s, 22 * s, fill);
        if (!hit.ready) {
            stroke.setColor(0x66F5B21B);
            stroke.setStrokeWidth(2 * s);
            c.drawRoundRect(rect, 22 * s, 22 * s, stroke);
        }
        fill.setColor(pc);
        rect.set(x, y, x + 12 * s, y + h);
        c.drawRoundRect(rect, 6 * s, 6 * s, fill);

        int fg = hit.ready ? 0xFF111111 : Color.WHITE;
        int lab = hit.ready ? 0x99000000 : 0x99FFFFFF;
        float lx = x + 30 * s, labY = y + h * 0.27f, valY = y + h * 0.66f;
        float c1 = w * 0.28f, c2 = w * 0.30f, c3 = w * 0.14f, c4 = w - c1 - c2 - c3 - 30 * s;
        float labS = Math.min(16 * s, h * 0.13f), valS = h * 0.42f;
        // labels
        text.setTypeface(condensed);
        text.setLetterSpacing(0.18f);
        text.setTextSize(labS);
        text.setColor(lab);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText("BRAND", lx, labY, text);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("ORDER ID", lx + c1 + c2 / 2f, labY, text);
        c.drawText("SLOT", lx + c1 + c2 + c3 / 2f, labY, text);
        text.setLetterSpacing(0f);
        // brand
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setColor(hit.ready ? pc : Color.WHITE);
        text.setTextSize(fit(o.platform, valS * 0.62f, c1 - 16 * s));
        c.drawText(o.platform, lx, valY, text);
        // last 4 big + full ID small
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(fg);
        text.setTextSize(fit(o.last4, valS, c2 - 16 * s));
        c.drawText(o.last4, lx + c1 + c2 / 2f, valY, text);
        if (o.orderId.length() > 4) {
            text.setTextSize(fit(o.orderId, Math.min(18 * s, h * 0.14f), c2 - 8 * s));
            text.setColor(hit.ready ? 0xDD222222 : 0xDDFFFFFF);
            c.drawText(o.orderId, lx + c1 + c2 / 2f, y + h * 0.88f, text);
        }
        // slot
        boolean hasSlot = o.slot != null && !o.slot.isEmpty();
        String slot = hasSlot ? o.slot : "\u2014";
        text.setColor(hit.ready ? 0xFF1B5E20 : GOLD);
        text.setTextSize(fit(slot, valS, c3 - 8 * s));
        c.drawText(slot, lx + c1 + c2 + c3 / 2f, valY, text);
        // status chip
        float chx = lx + c1 + c2 + c3 + 10 * s, chw = c4 - 16 * s, chh = Math.min(h * 0.46f, 74 * s);
        rect.set(chx, y + h * 0.42f - chh / 2f, chx + chw, y + h * 0.42f + chh / 2f);
        fill.setColor(hit.ready ? 0xFF2E7D32 : 0xFFF5B21B);
        c.drawRoundRect(rect, chh / 2f, chh / 2f, fill);
        String st = hit.ready ? "\u2713 READY" : "PREPARING";
        text.setTypeface(bold);
        text.setLetterSpacing(0.08f);
        text.setTextSize(fit(st, chh * 0.5f, chw - 24 * s));
        text.setColor(hit.ready ? Color.WHITE : 0xFF1B1203);
        c.drawText(st, rect.centerX(), rect.centerY() + text.getTextSize() * 0.36f, text);
        text.setLetterSpacing(0f);
        long since = hit.ready ? hit.readyAt : o.createdMs;
        if (since > 0 && wall - since >= 0 && wall - since < 24 * 3600_000L) {
            long min = (wall - since) / 60_000L;
            String sub = hit.ready ? (min < 1 ? "ready just now" : "ready " + min + " min")
                    : (min < 1 ? "ordered just now" : "ordered " + min + " min ago");
            text.setTypeface(medium);
            text.setTextSize(fit(sub, Math.min(18 * s, h * 0.14f), chw));
            text.setColor(hit.ready ? 0xAA000000 : 0xAAFFFFFF);
            c.drawText(sub, rect.centerX(), y + h * 0.88f, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * Rider details card: brand strip with status, full order ID with the last 4 highlighted,
     * a big SLOT, order / ready times, the KOT items and a big OK.
     */
    private void drawLookup(Canvas c, float w, float h, long now, boolean land) {
        Lookup l = lookup;
        long wall = System.currentTimeMillis();
        float age = Math.min(1f, (now - l.shownAt) / 220f);
        fill.setColor(alpha(0xFF04070B, (int) (225 * age)));
        c.drawRect(0, 0, w, h, fill);
        float cw = Math.min(w - 40 * s, land ? 1640 * s : w), ch = Math.min(h - 40 * s, land ? 1000 * s : h - 40 * s);
        float x = (w - cw) / 2f, y = (h - ch) / 2f;
        c.save();
        float sc = 0.94f + 0.06f * age;
        c.scale(sc, sc, w / 2f, h / 2f);
        RiderCalls.Order o = l.order;
        int pc = o != null ? platformColor(o.platform) : 0xFF8E2424;
        rect.set(x, y, x + cw, y + ch);
        fill.setColor(0xFFF7F8FA);
        c.drawRoundRect(rect, 30 * s, 30 * s, fill);

        // brand strip + status chip
        float stripH = Math.min(150 * s, ch * 0.17f);
        c.save();
        c.clipRect(x, y, x + cw, y + stripH);
        rect.set(x, y, x + cw, y + stripH + 40 * s);
        fill.setColor(pc);
        c.drawRoundRect(rect, 30 * s, 30 * s, fill);
        c.restore();
        float pad = 40 * s;
        String brand = o != null ? o.platform.toUpperCase(Locale.US) : "ORDER";
        String st = o == null ? "NOT ON BOARD" : l.ready ? "\u2713 READY" : "PREPARING";
        int stBg = o == null ? 0xFFFFFFFF : l.ready ? 0xFF1B5E20 : 0xFFF5B21B;
        int stFg = o == null ? 0xFFB71C1C : l.ready ? Color.WHITE : 0xFF1B1203;
        text.setTypeface(bold);
        text.setLetterSpacing(0.06f);
        text.setTextSize(stripH * 0.38f);
        float stW = Math.min(cw * 0.42f, text.measureText(st) + 70 * s), stH = stripH * 0.62f;
        rect.set(x + cw - pad - stW, y + (stripH - stH) / 2f, x + cw - pad, y + (stripH + stH) / 2f);
        fill.setColor(stBg);
        c.drawRoundRect(rect, stH / 2f, stH / 2f, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(stFg);
        text.setTextSize(fit(st, stripH * 0.38f, stW - 40 * s));
        c.drawText(st, rect.centerX(), rect.centerY() + text.getTextSize() * 0.36f, text);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Color.WHITE);
        text.setTextSize(fit(brand, stripH * 0.5f, cw - 3 * pad - stW));
        c.drawText(brand, x + pad, y + stripH / 2f + text.getTextSize() * 0.36f, text);
        text.setLetterSpacing(0f);

        // order ID (last 4 highlighted) and SLOT
        float top = y + stripH + 30 * s;
        float slotW = o == null ? 0 : land ? cw * 0.28f : cw * 0.34f;   // no slot box for "not on board"
        float idW = cw - 2 * pad - slotW - 30 * s;
        float blockH = Math.min(ch * 0.30f, 300 * s);
        String full = o != null ? (o.orderId.isEmpty() ? o.last4 : o.orderId) : (l.unknown == null ? "" : l.unknown);
        String d = full.replaceAll("\\s", "");
        int cut = Math.max(0, d.length() - 4);
        String head = d.substring(0, cut), tail = d.substring(cut);
        label(c, "ORDER ID", x + pad, top + 22 * s);
        // full ID on one line: head small-ish, tail big and highlighted
        float tailSize = Math.min(blockH * 0.62f, 190 * s);
        text.setTypeface(bold);
        text.setTextSize(tailSize);
        float tailW = text.measureText(tail);
        if (tailW > idW * 0.62f) {
            tailSize *= idW * 0.62f / tailW;
            text.setTextSize(tailSize);
            tailW = text.measureText(tail);
        }
        text.setTypeface(medium);
        float headSize = fit(head, tailSize * 0.42f, idW - tailW - 50 * s);
        text.setTextSize(headSize);
        float headW = head.isEmpty() ? 0 : text.measureText(head) + 14 * s;
        float base = top + 40 * s + blockH * 0.62f;
        text.setColor(0xFF4A4F57);
        if (!head.isEmpty()) c.drawText(head, x + pad, base, text);
        float tx = x + pad + headW;
        rect.set(tx - 12 * s, base - tailSize * 0.86f, tx + tailW + 12 * s, base + tailSize * 0.16f);
        fill.setColor(0xFFFFE082);
        c.drawRoundRect(rect, 14 * s, 14 * s, fill);
        text.setTypeface(bold);
        text.setTextSize(tailSize);
        text.setColor(0xFF111111);
        c.drawText(tail, tx, base, text);
        // slot
        float sx = x + cw - pad - slotW, sy = top;
        if (o != null) {
        rect.set(sx, sy, sx + slotW, sy + blockH + 40 * s);
        boolean hasSlot = o != null && o.slot != null && !o.slot.isEmpty();
        fill.setColor(hasSlot ? 0xFFF5B21B : 0xFFE3E6EA);
        c.drawRoundRect(rect, 26 * s, 26 * s, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.25f);
        text.setTextSize(24 * s);
        text.setColor(hasSlot ? 0xFF1B1203 : 0xFF6B7280);
        c.drawText("SLOT", rect.centerX(), sy + 40 * s, text);
        text.setLetterSpacing(0f);
        String slot = hasSlot ? o.slot : "\u2014";
        text.setTypeface(bold);
        text.setTextSize(fit(slot, blockH * 0.78f, slotW - 30 * s));
        c.drawText(slot, rect.centerX(), sy + 40 * s + (blockH + 40 * s - 40 * s) / 2f + text.getTextSize() * 0.36f, text);
        }
        text.setTextAlign(Paint.Align.LEFT);

        // times / note
        float ty = top + blockH + 40 * s + 46 * s;
        String times;
        if (o == null) {
            times = l.note != null ? l.note : "Picked up already, cancelled, or not in the kitchen yet";
        } else {
            StringBuilder sb = new StringBuilder();
            if (l.createdMs > 0 && wall >= l.createdMs) sb.append("Ordered ").append(mins(wall - l.createdMs)).append(" ago");
            if (l.ready && l.readyAt > 0 && wall >= l.readyAt) {
                if (sb.length() > 0) sb.append("   \u2022   ");
                sb.append("Ready for ").append(mins(wall - l.readyAt));
            } else if (!l.ready) {
                if (sb.length() > 0) sb.append("   \u2022   ");
                sb.append("Still being prepared");
            }
            times = sb.toString();
        }
        text.setTypeface(bold);
        text.setTextSize(fit(times, 34 * s, cw - 2 * pad));
        text.setColor(o == null ? 0xFFB71C1C : l.ready ? 0xFF1B5E20 : 0xFF8D5A00);
        c.drawText(times, x + pad, ty, text);

        // items
        float okH = Math.min(96 * s, ch * 0.1f);
        float itTop = ty + 30 * s, itBottom = y + ch - okH - 50 * s;
        if (!l.items.isEmpty() && itBottom - itTop > 60 * s) {
            label(c, "ITEMS", x + pad, itTop + 24 * s);
            int cols = land && l.items.size() > 4 ? 2 : 1;
            float lineH = Math.min(48 * s, (itBottom - itTop - 40 * s) / Math.max(1, (l.items.size() + cols - 1) / cols));
            lineH = Math.max(lineH, 30 * s);
            int perCol = Math.max(1, (int) ((itBottom - itTop - 40 * s) / lineH));
            int shown = Math.min(l.items.size(), perCol * cols);
            if (shown < l.items.size()) shown = Math.max(0, shown - 1);
            float colW = (cw - 2 * pad) / cols;
            text.setTypeface(medium);
            for (int i = 0; i < shown; i++) {
                int col = i / perCol, row = i % perCol;
                String it = l.items.get(i);
                text.setColor(0xFF1F2933);
                text.setTextSize(fit(it, lineH * 0.66f, colW - 24 * s));
                c.drawText(it, x + pad + col * colW, itTop + 40 * s + (row + 0.75f) * lineH, text);
            }
            if (shown < l.items.size()) {
                text.setColor(0xFF6B7280);
                text.setTextSize(lineH * 0.6f);
                int col = shown / perCol, row = shown % perCol;
                c.drawText("+" + (l.items.size() - shown) + " more", x + pad + col * colW, itTop + 40 * s + (row + 0.75f) * lineH, text);
            }
        }

        // OK + auto-close hint
        float okW = Math.min(420 * s, cw * 0.4f);
        rect.set(x + cw / 2f - okW / 2f, y + ch - okH - 26 * s, x + cw / 2f + okW / 2f, y + ch - 26 * s);
        hitLookupOk.set(rect);
        hitLookupOk.inset(-20 * s, -20 * s);
        fill.setColor(0xFF263238);
        c.drawRoundRect(rect, okH / 2f, okH / 2f, fill);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(bold);
        text.setLetterSpacing(0.12f);
        text.setTextSize(okH * 0.42f);
        text.setColor(Color.WHITE);
        c.drawText("OK", rect.centerX(), rect.centerY() + text.getTextSize() * 0.36f, text);
        text.setLetterSpacing(0f);
        long left = Math.max(0, (LOOKUP_IDLE_MS - (now - l.seenAt) + 999) / 1000);
        text.setTypeface(medium);
        text.setTextSize(18 * s);
        text.setColor(0xFF6B7280);
        text.setTextAlign(Paint.Align.RIGHT);
        c.drawText("Back to the board in " + left + " s", x + cw - pad, rect.centerY() + 6 * s, text);
        text.setTextAlign(Paint.Align.LEFT);
        c.restore();
    }

    private void label(Canvas c, String s0, float x, float y) {
        text.setTypeface(condensed);
        text.setLetterSpacing(0.22f);
        text.setTextSize(22 * s);
        text.setColor(0xFF6B7280);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(s0, x, y, text);
        text.setLetterSpacing(0f);
    }

    private static String mins(long ms) {
        long m = ms / 60_000L;
        return m < 1 ? "under 1 min" : m + " min";
    }

    private void drawCall(Canvas c, float w, float h, long now, float t) {
        if (call == null || call.isEmpty()) return;
        long age = now - callAt;
        if (age > CALL_MS) {
            call = null;
            return;
        }
        float vis = Math.min(Math.min(1f, age / 300f), Math.min(1f, (CALL_MS - age) / 400f));
        fill.setColor(alpha(0xFF04080C, (int) (215 * vis)));
        c.drawRect(0, 0, w, h, fill);
        int n = Math.min(call.size(), 4);
        float cw = Math.min(w * 0.86f, 1500 * s), rowH = Math.min(200 * s, (h * 0.7f) / n);
        float x = (w - cw) / 2f, y = (h - rowH * n) / 2f;
        float sc = popScale(age);
        c.save();
        c.scale(sc, sc, w / 2f, h / 2f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(condensed);
        text.setLetterSpacing(0.4f);
        text.setTextSize(30 * s);
        text.setColor(alpha(GREEN, (int) (255 * vis)));
        c.drawText("READY FOR PICKUP", w / 2f, y - 26 * s, text);
        text.setLetterSpacing(0f);
        for (int i = 0; i < n; i++) {
            RiderCalls.Order o = call.get(i).order;
            float ry = y + i * rowH;
            rect.set(x, ry + 8 * s, x + cw, ry + rowH - 8 * s);
            fill.setColor(alpha(platformColor(o.platform), (int) (255 * vis)));
            c.drawRoundRect(rect, 26 * s, 26 * s, fill);
            boolean hasSlot = o.slot != null && !o.slot.isEmpty();
            String line = o.platform.toUpperCase(Locale.US) + "   \u2026" + o.last4 + (hasSlot ? "   \u2192   SLOT " + o.slot : "");
            text.setTypeface(bold);
            text.setTextSize(fit(line, rowH * 0.5f, cw - 60 * s));
            text.setColor(alpha(Color.WHITE, (int) (255 * vis)));
            c.drawText(line, w / 2f, rect.centerY() + text.getTextSize() * 0.36f, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
        c.restore();
    }

    // ---- helpers -----------------------------------------------------------------------------

    static int platformColor(String p) {
        String l = p == null ? "" : p.toLowerCase(Locale.US);
        if (l.contains("swiggy")) return 0xFFFC8019;
        if (l.contains("zomato")) return 0xFFE23744;
        if (l.contains("ownly")) return 0xFF6A4CD6;
        if (l.contains("toing")) return 0xFF0097A7;
        if (l.contains("magicpin")) return 0xFF8E24AA;
        return 0xFF2E7D32;
    }

    private void title(Canvas c, float x, float baseY, float w, String title, int color, String count) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(bold);
        text.setLetterSpacing(0.2f);
        text.setTextSize(28 * s);
        text.setColor(color);
        c.drawText(title, x, baseY, text);
        text.setLetterSpacing(0f);
        if (count != null) {
            text.setTextSize(20 * s);
            float cw = text.measureText(count) + 30 * s;
            rect.set(x + w - cw, baseY - 26 * s, x + w, baseY + 8 * s);
            fill.setColor(0x22FFFFFF);
            c.drawRoundRect(rect, 17 * s, 17 * s, fill);
            text.setTextAlign(Paint.Align.CENTER);
            text.setColor(color);
            c.drawText(count, rect.centerX(), baseY - 2 * s, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    private void panel(Canvas c, float x, float y, float w, float h, float r, int bgc, int border) {
        rect.set(x, y, x + w, y + h);
        fill.setShader(null);
        fill.setColor(bgc);
        c.drawRoundRect(rect, r, r, fill);
        stroke.setColor(border);
        stroke.setStrokeWidth(1.5f * s);
        c.drawRoundRect(rect, r, r, stroke);
    }

    private float fit(String str, float size, float maxW) {
        text.setTextSize(size);
        float tw = text.measureText(str);
        return tw > maxW ? size * maxW / tw : size;
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private static final long FULL_AFTER_MS = 10_000L;
    private float full;
    private long lastFrame;
    private boolean immersive;

    /** Hides / shows the Android status and navigation bars (swipe from the edge shows them briefly). */
    @SuppressWarnings("deprecation")
    private void setImmersive(boolean on) {
        immersive = on;
        try {
            setSystemUiVisibility(on ? (SYSTEM_UI_FLAG_IMMERSIVE_STICKY | SYSTEM_UI_FLAG_FULLSCREEN
                    | SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_LAYOUT_STABLE) : 0);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (immersive) setImmersive(false);
        super.onDetachedFromWindow();
    }

    private static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, a)) << 24);
    }

    private static float popScale(long age) {
        if (age >= 600) return 1f;
        float p = age / 600f;
        return (float) (1 + (-0.4 * Math.exp(-6 * p) * Math.cos(9 * p)));
    }
}
