package com.pp.kds.dosa;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.pp.kds.core.common.model.OrderType;
import com.pp.kds.domain.model.Kot;
import com.pp.kds.domain.model.KotItem;
import com.pp.kds.feature.dashboard.DashboardUiState;
import com.pp.kds.feature.dashboard.DashboardViewModel;
import com.pp.kds.feature.dashboard.KotCard;
import com.pp.kds.feature.dashboard.ui.BoardVisualsKt;
import com.pp.kds.scan.ScannerBridge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "Dosa Live Wait" add-on for the KDS. Installed from MainActivity.onCreate with one call:
 * {@code DosaLive.install(this)}. Reads the live board read-only (the same DashboardViewModel the
 * scanner uses) and never calls any board action, so the scanner/Food Ready logic is untouched.
 */
public final class DosaLive {

    private static final String PREFS = "dosa_live_stats";
    private static final long TICK_MS = 2_000L;          // board poll, mirrors KDS 2 s poll
    private static final long RECOMPUTE_MS = 15_000L;    // sliding throughput window refresh
    private static final int BOARD_CAP = 300;            // KotRepositoryImpl caps the board at 300
    private static final String DOSA = "dosa";

    /**
     * Real guest reviews shown as "Guest love" on the customer screen, e.g.
     * "Crispiest ghee roast ever! \u2014 Priya, Google review".
     * Only add genuine reviews (with permission/public text); leave empty to show facts only.
     */
    static final String[] GUEST_LOVE = {};

    private static DosaStats stats;
    private static DosaStats.Result last;
    private static Handler handler;
    private static Object lastCards;
    /** When the board list last changed / when we last asked the KDS to re-fetch it. */
    private static long lastBoardChangeAt, lastReloadAt;
    /**
     * The KDS refreshes its board only when the server pushes a change (MQTT). On TV Wi-Fi that
     * link can go quiet without an error, and the board (and our screens) then show old orders until
     * the KDS screen is reopened. So when nothing has changed for this long, ask it to re-fetch.
     */
    private static final long QUIET_RELOAD_MS = 20_000L;
    private static long lastComputeAt;
    private static Field vmField;
    private static Panel panel;
    private static SharedPreferences prefs;
    private static View buttonRow;
    private static boolean seedApplied;
    private static boolean persistPending;
    private static java.lang.ref.WeakReference<Activity> current;
    private static final long PROCESS_START = System.currentTimeMillis();
    private static long lastUserAt = System.currentTimeMillis();
    private static final TokenBoard tokens = new TokenBoard();
    private static RiderCalls riders = new RiderCalls();
    private static int riderSource = -1;          // 0 Petpooja via KDS, 1 Bridge Print, 2 test

    /** Rider board + calls run from Bridge Print when it is reachable and gives order status. */
    static boolean bridgeMode() {
        return BridgeSlots.connected() && BridgeSlots.hasStatus();
    }

    /** Switching data source: start fresh, so orders already waiting are not called as "new". */
    private static void useBridgeSource(boolean bridge) {
        useSource(bridge ? 1 : 0);
    }

    private static void useSource(int src) {
        if (src == riderSource) return;
        riderSource = src;
        RiderCalls fresh = new RiderCalls();
        fresh.setMaxMinutes(riderMaxMin);
        fresh.skipStale = src == 0; // only for Petpooja-via-KDS; Bridge Print shows everything they list
        riders = fresh;
    }

    private static int riderMaxMin;
    private static boolean riderOn;

    static boolean riderCallsOn() {
        return riderOn;
    }
    private static Context appCtx;
    private static TokenVoice voiceInstance;

    /** One text-to-speech voice for the whole app, so token calls and rider calls queue up. */
    static TokenVoice sharedVoice(Context c) {
        if (voiceInstance == null) voiceInstance = new TokenVoice(c);
        return voiceInstance;
    }

    private DosaLive() {}

    public static void install(final Activity activity) {
        current = new java.lang.ref.WeakReference<Activity>(activity);
        try {
            addButton(activity);
            start(activity.getApplicationContext());
        } catch (Throwable ignored) {
            // Never let the add-on break the KDS.
        }
        try {
            restoreLastScreen(activity);
        } catch (Throwable ignored) {
        }
        try {
            setupAutoHide(activity);
            android.view.Window w = activity.getWindow();
            w.setCallback(new KeyScanCallback(w.getCallback(), activity)); // USB/BT scanner + touch
        } catch (Throwable ignored) {
        }
    }

    // ---- auto-hiding buttons + scan banner ---------------------------------------------------

    private static final long CONTROLS_VISIBLE_MS = 6000;
    private static final List<View> controls = new ArrayList<View>();
    private static Handler uiHandler;
    private static final Runnable hideControls = new Runnable() {
        @Override public void run() {
            for (final View v : controls) {
                v.animate().alpha(0f).setDuration(600).withEndAction(new Runnable() {
                    @Override public void run() {
                        if (v.getAlpha() < 0.05f) v.setVisibility(View.INVISIBLE);
                    }
                }).start();
            }
        }
    };

    /** Camera button + DOSA LIVE / PREP LIVE fade out after a few seconds; any tap brings them back. */
    private static void setupAutoHide(Activity a) {
        uiHandler = new Handler(Looper.getMainLooper());
        controls.clear();
        if (buttonRow != null) controls.add(buttonRow);
        ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
        for (int i = 0; i < content.getChildCount(); i++) {
            View v = content.getChildAt(i);
            CharSequence cd = v.getContentDescription();
            if (v instanceof android.widget.ImageButton && cd != null && cd.toString().startsWith("Scan order")) controls.add(v);
        }
        onUserTouch();
    }

    /** The board buttons (camera, DOSA LIVE, PREP LIVE) for remote navigation. */
    static List<View> controlViews() {
        return new ArrayList<View>(controls);
    }

    /** Remote keys go straight to the open Dosa Live / Order Ready screen. */
    static boolean panelKey(android.view.KeyEvent e) {
        if (panel == null) return false;
        if (panel.mode == Panel.RIDER && panel.riderView != null) return panel.riderView.handleKey(e);
        return panel.mode == Panel.ORDER_READY && panel.board != null ? panel.board.handleKey(e) : panel.view.handleKey(e);
    }

    static void onUserTouch() {
        lastUserAt = System.currentTimeMillis();
        if (uiHandler == null) return;
        uiHandler.removeCallbacks(hideControls);
        for (View v : controls) {
            v.animate().cancel();
            v.setVisibility(View.VISIBLE);
            v.setAlpha(1f);
        }
        uiHandler.postDelayed(hideControls, CONTROLS_VISIBLE_MS);
    }

    private static TextView banner;

    /** Short result banner on the board after a USB/Bluetooth scanner scan. */
    static void showScanBanner(Activity a, String msg, boolean ok) {
        if (msg == null) return;
        float d = a.getResources().getDisplayMetrics().density;
        ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
        if (banner == null || banner.getParent() == null) {
            banner = new TextView(a);
            banner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            banner.setTypeface(Typeface.DEFAULT_BOLD);
            banner.setPadding((int) (20 * d), (int) (10 * d), (int) (20 * d), (int) (10 * d));
            banner.setElevation(40 * d);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.topMargin = (int) (10 * d);
            content.addView(banner, lp);
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(14 * d);
        bg.setColor(ok ? 0xF01B5E20 : 0xF0E65100);
        banner.setBackground(bg);
        banner.setTextColor(Color.WHITE);
        banner.setText(msg);
        banner.bringToFront();
        banner.animate().cancel();
        banner.setAlpha(1f);
        banner.setVisibility(View.VISIBLE);
        banner.animate().alpha(0f).setStartDelay(4000).setDuration(700).start();
    }

    // ---- live tracking -------------------------------------------------------------------

    private static void start(Context ctx) {
        if (handler != null) return; // already running (activity recreated)
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        appCtx = ctx.getApplicationContext();
        riderOn = ctx.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE).getBoolean("rider_calls", false);
        BridgeSlots.setAddress(ctx.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE).getString("bridge_addr", ""));
        riderMaxMin = ctx.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE).getInt("rider_max_min", 0);
        riders.setMaxMinutes(riderMaxMin);
        tokens.setAutoClearMinutes(ctx.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE).getInt("autoclear_min", 10));
        SharedPreferences tp = ctx.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE);
        PhoneTrack.configure(tp.getString("track_db", ""), tp.getString("track_secret", ""), tp.getString("track_page", ""));
        stats = new DosaStats();
        Map<String, String> saved = new HashMap<String, String>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            saved.put(e.getKey(), String.valueOf(e.getValue()));
        }
        stats.load(saved, System.currentTimeMillis());
        try {
            PrepLive.init(ctx); // loads in the background; the dosa seed is applied in tick()
        } catch (Throwable ignored) {
        }
        last = stats.compute(System.currentTimeMillis());
        handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override public void run() {
                try {
                    tick();
                } catch (Throwable ignored) {
                }
                handler.postDelayed(this, TICK_MS);
            }
        });
    }

    private static void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        DashboardUiState state = currentState();
        if (state != null) {
            List<KotCard> cards = state.getCards();
            // Use every fresh board list, also while the KDS says "reconnecting"/"loading" (weak TV
            // Wi-Fi flags that often and ready tokens used to freeze). Only an EMPTY list during a
            // connection problem is ignored, so a network blip never wipes the screen.
            boolean shaky = state.getError() != null || state.isLoading() || state.getReconnecting();
            if (cards != null && cards != lastCards && !(shaky && cards.isEmpty())) { // new list on every change
                lastCards = cards;
                lastBoardChangeAt = now;
                List<TokenBoard.Kot> tk = new ArrayList<TokenBoard.Kot>();
                changed = stats.update(dosaEntries(cards, tk), now, cards.size() >= BOARD_CAP - 5);
                try {
                    tokens.update(tk, now, panel != null && panel.mode == Panel.ORDER_READY);
                    if (panel != null) panel.bindTokens();
                } catch (Throwable ignored) {
                }
                try {
                    List<RiderCalls.Order> kdsOrders = riderOrders(cards);
                    Map<String, Integer> kinds = new HashMap<String, Integer>();
                    for (RiderCalls.Order o : kdsOrders) {
                        String dg = BridgeSlots.digits(o.orderId);
                        if (dg.length() >= 6) kinds.put(dg, o.gone ? 2 : o.ready ? 1 : 0);
                    }
                    BridgeSlots.setKdsKinds(kinds); // helps Bridge Print orders show Food Ready
                    if (!bridgeMode()) {
                        useBridgeSource(false);
                        riders.update(kdsOrders, now); // fallback: Petpooja via the KDS
                    }
                } catch (Throwable ignored) {
                }
                try {
                    PrepLive.onBoard(cards, now);
                } catch (Throwable ignored) {
                }
            }
        }
        reloadIfQuiet(now);
        try {
            if (!seedApplied && PrepLive.available()) {
                stats.setSeedAvg(PrepLive.seedDosaAvg()); // real Petpooja Dine-In dosa average
                seedApplied = true;
                changed = true;
            }
            PrepLive.tick(now, false);
        } catch (Throwable ignored) {
        }
        try {
            tokens.refresh(now); // auto-clear long-ready tokens even when the board is quiet
            if (panel != null) panel.bindTokens();
            if (PhoneTrack.enabled()) {
                // customers' phones: token status to the cloud (only when it changes, + 30 s heartbeat)
                PhoneTrack.publish(PhoneTrack.payload(tokens.ready(), tokens.preparing(), last, now), now);
            }
            List<TokenBoard.Token> call = tokens.takeCall(now);
            if (!call.isEmpty() && panel != null) panel.announce(call);
        } catch (Throwable ignored) {
        }
        if (persistPending && now - lastPersistAt >= 30_000L) persist();
        try {
            if (bridgeMode()) {
                useBridgeSource(true);
                riders.update(BridgeSlots.orders(), now); // same orders, status and slots as Bridge Print
            }
            if (panel != null) panel.bindRider();
        } catch (Throwable ignored) {
        }
        try {
            if (riderOn && appCtx != null) {
                TokenVoice rv = sharedVoice(appCtx);
                // Next call only after the voice has finished (+3 s): no backlog that keeps
                // talking after orders are cleared. Nothing waiting -> stop at once.
                List<RiderCalls.Waiting> call = java.util.Collections.emptyList();
                if (riders.waitingCount() == 0) rv.stopRiderCalls();
                else if (!rv.busy() && rv.idleMs() >= 3_000L) call = riders.due(now);
                for (RiderCalls.Waiting w : call) {
                    String s = BridgeSlots.slotFor(w.order.orderId); // Bridge Print slot, if connected
                    if (!s.isEmpty()) w.order.slot = s;
                }
                if (!call.isEmpty()) sharedVoice(appCtx).sayAlternating(RiderCalls.phrase(call), riders.lastWasNew);
            }
        } catch (Throwable ignored) {
        }
        nightlyRefresh(now);
        if (changed || now - lastComputeAt >= RECOMPUTE_MS) {
            lastComputeAt = now;
            last = stats.compute(now);
            if (changed) persist();
            render();
        }
    }

    private static List<DosaStats.Entry> dosaEntries(List<KotCard> cards, List<TokenBoard.Kot> tokenOut) {
        List<DosaStats.Entry> out = new ArrayList<DosaStats.Entry>();
        for (KotCard card : cards) {
            Kot kot = card.getKot();
            if (kot == null || kot.getId() == null) continue;
            if (OrderType.Companion.fromId(kot.getOrderType()) != OrderType.DINE_IN) continue;
            List<String> names = new ArrayList<String>();
            List<Double> qty = new ArrayList<Double>();
            boolean dosaItemsReady = collectDosa(kot, names, qty);
            if (names.isEmpty()) continue;
            String status = kot.getKotStatus();
            boolean cancelled = "0".equals(status);
            // Dosa part is done when the KOT is Food Ready, or when every Dosa item on it is
            // marked ready (so slower non-dosa items on the same KOT don't stretch dosa times).
            boolean ready = card.getState().isDispatch() || "9".equals(status) || "10".equals(status)
                    || dosaItemsReady;
            Long created = BoardVisualsKt.parseCreatedMillis(kot.getCreatedTime());
            double[] q = new double[qty.size()];
            for (int i = 0; i < q.length; i++) q[i] = qty.get(i);
            out.add(new DosaStats.Entry(kot.getId(), created == null ? 0L : created, ready, cancelled,
                    names.toArray(new String[0]), q));
            int dosas = 0;
            for (double v : q) dosas += (int) Math.max(1, Math.round(v));
            String[] tok = tokenLabel(kot);
            // Released when the KOT is dispatched, or when every Dosa item on it was dispatched
            // (the KOT can stay open on the board for its other items).
            tokenOut.add(new TokenBoard.Kot(kot.getId(), tok[0], tok[1], created == null ? 0L : created, ready,
                    "10".equals(status) || dosaDispatched(kot), cancelled, dosas));
        }
        return out;
    }

    /**
     * Online / delivery KOTs for rider calls: platform (Swiggy, Zomato, Ownly ...), last 4 digits
     * of the platform order ID and the pickup slot (the KOT token number, e.g. "Ownly 20").
     */
    private static List<RiderCalls.Order> riderOrders(List<KotCard> cards) {
        List<RiderCalls.Order> out = new ArrayList<RiderCalls.Order>();
        for (KotCard card : cards) {
            Kot kot = card.getKot();
            if (kot == null || kot.getId() == null) continue;
            String user = null;
            try {
                user = kot.getOnlineOrderUserId();
            } catch (Throwable ignored) {
            }
            boolean online = user != null && !user.trim().isEmpty();
            boolean delivery = OrderType.Companion.fromId(kot.getOrderType()) == OrderType.DELIVERY;
            if (!online && !delivery) continue;
            String platform = null;
            try {
                platform = BoardVisualsKt.channelName(kot);
            } catch (Throwable ignored) {
            }
            if (platform == null || platform.trim().isEmpty() || "Online".equals(platform)) {
                platform = online ? "Online" : "Delivery";
            }
            String last4 = RiderCalls.last4(kot.getPOId());
            if (last4.isEmpty()) last4 = RiderCalls.last4(String.valueOf(kot.getId()));
            // Pickup slots are assigned by Bridge Print (not the Petpooja token): read from it.
            String slot = BridgeSlots.slotFor(kot.getPOId());
            String status = kot.getKotStatus();
            boolean ready = card.getState().isDispatch() || "9".equals(status);
            // Picked up: dispatched / cancelled in Petpooja, or cleared on Bridge Print.
            boolean gone = "10".equals(status) || "0".equals(status) || BridgeSlots.pickedUp(kot.getPOId());
            Long created = BoardVisualsKt.parseCreatedMillis(kot.getCreatedTime());
            out.add(new RiderCalls.Order(kot.getId(), platform.trim(), last4, kot.getPOId(), slot,
                    created == null ? 0L : created, ready, gone));
        }
        return out;
    }

    /** {screen label, spoken form}: the KOT token number, else the table, else the KOT number. */
    private static String[] tokenLabel(Kot kot) {
        Long tn = null;
        try {
            tn = kot.getTokenNo();
        } catch (Throwable ignored) {
        }
        if (tn != null && tn > 0) return new String[]{String.valueOf(tn), String.valueOf(tn)};
        String table = null;
        try {
            table = kot.getTableNo();
        } catch (Throwable ignored) {
        }
        if (table != null && !table.trim().isEmpty()) {
            table = table.trim();
            return new String[]{"T" + table, "table " + table};
        }
        String id = String.valueOf(kot.getId());
        if (id.length() > 3) id = id.substring(id.length() - 3);
        return new String[]{"#" + id, "order " + id};
    }

    /** True when every (non-cancelled) Dosa item on the KOT has item status 10 = dispatched. */
    private static boolean dosaDispatched(Kot kot) {
        List<KotItem> items = kot.getItems();
        if (items == null) return false;
        boolean any = false;
        for (KotItem item : items) {
            String cat = item.getCategory();
            if (cat == null || !cat.toLowerCase(Locale.US).contains(DOSA)) continue;
            Integer st = item.getStatus();
            if (BoardVisualsKt.isItemCancelled(st)) continue;
            if (st == null || st != 10) return false;
            any = true;
        }
        return any;
    }

    /** Collects the Dosa items; returns true when every one of them is marked ready. */
    private static boolean collectDosa(Kot kot, List<String> names, List<Double> qty) {
        List<KotItem> items = kot.getItems();
        if (items == null) return false;
        boolean allReady = true;
        for (KotItem item : items) {
            String cat = item.getCategory();
            if (cat != null && cat.toLowerCase(Locale.US).contains(DOSA)
                    && !BoardVisualsKt.isItemCancelled(item.getStatus())) {
                String n = item.getName();
                names.add(n == null || n.trim().isEmpty() ? "Dosa" : n.trim());
                Double q = item.getQuantity();
                qty.add(q == null || q <= 0 ? 1.0 : q);
                if (!BoardVisualsKt.isItemReady(item.getStatus())) allReady = false;
            }
        }
        return allReady && !names.isEmpty();
    }

    /** Board quiet for a while: make the KDS fetch the latest KOTs from the server (same as reopening it). */
    private static void reloadIfQuiet(long now) {
        if (now - lastBoardChangeAt < QUIET_RELOAD_MS || now - lastReloadAt < QUIET_RELOAD_MS) return;
        lastReloadAt = now;
        try {
            if (vmField == null) {
                vmField = ScannerBridge.class.getDeclaredField("dashboardViewModel");
                vmField.setAccessible(true);
            }
            DashboardViewModel vm = (DashboardViewModel) vmField.get(null);
            if (vm != null) vm.reload();
        } catch (Throwable ignored) {
        }
    }

    private static DashboardUiState currentState() {
        try {
            if (vmField == null) {
                vmField = ScannerBridge.class.getDeclaredField("dashboardViewModel");
                vmField.setAccessible(true);
            }
            DashboardViewModel vm = (DashboardViewModel) vmField.get(null);
            return vm == null ? null : vm.getState().getValue();
        } catch (Throwable t) {
            return null;
        }
    }

    private static long lastPersistAt;
    private static String lastPersistDay;

    /** Saves at most every 30 s (and at once on a new day): no constant writes to TV storage. */
    private static void persist() {
        long now = System.currentTimeMillis();
        String day = DosaStats.dayKey(now);
        if (now - lastPersistAt < 30_000L && day.equals(lastPersistDay)) {
            persistPending = true;
            return;
        }
        lastPersistAt = now;
        lastPersistDay = day;
        persistPending = false;
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, String> e : stats.save().entrySet()) ed.putString(e.getKey(), e.getValue());
        ed.apply();
    }

    // ---- UI ------------------------------------------------------------------------------

    /** Phones get a compact row; tablets / TVs the full size. Independent of the font-size setting. */
    private static boolean compact(Activity a) {
        return a.getResources().getConfiguration().smallestScreenWidthDp < 600;
    }

    private static void addButton(final Activity activity) {
        final float d = activity.getResources().getDisplayMetrics().density;
        final boolean small = compact(activity);
        final int h = (int) ((small ? 28 : 36) * d);
        final LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setElevation(8 * d);

        // Grip: shows the group can be dragged anywhere on the screen.
        TextView grip = new TextView(activity);
        grip.setText("\u2807\u2807");
        grip.setTextColor(Color.WHITE);
        grip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, small ? 12 : 14);
        grip.setGravity(Gravity.CENTER);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(0xCC37474F);
        gb.setCornerRadius(h / 2f);
        grip.setBackground(gb);
        grip.setContentDescription("Drag to move the Dosa Live buttons");
        row.addView(grip, new LinearLayout.LayoutParams((int) ((small ? 22 : 28) * d), h));

        TextView dosa = pill(activity, small ? "\u2726 DOSA" : "\u2726 DOSA LIVE",
                new int[]{0xFFFF5200, 0xFFFF8A00, 0xFFFFC107}, 0xFFFF5200);
        dosa.setContentDescription("Dosa Live Wait");
        dosaButton = dosa;
        dosa.setOnLongClickListener(new View.OnLongClickListener() { // TV remote: hold OK
            @Override public boolean onLongClick(View v) {
                phoneDialog(activity);
                return true;
            }
        });
        dosa.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showPanel(activity, -1);
            }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h);
        dlp.leftMargin = (int) (5 * d);
        row.addView(dosa, dlp);
        TextView prep = pill(activity, small ? "\u25C9 PREP" : "\u25C9 PREP LIVE",
                new int[]{0xFF2E7D32, 0xFF43A047, 0xFF8BC34A}, 0xFF43A047);
        prep.setContentDescription("Prep Live production");
        prep.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PrepLive.open(activity);
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h);
        plp.leftMargin = (int) (5 * d);
        row.addView(prep, plp);

        // Rider calls on/off: only the device near the pickup area should speak them.
        final TextView rider = pill(activity, "", RIDER_OFF, 0xFF546E7A);
        rider.setContentDescription("Rider calls on or off");
        styleRider(rider, small);
        riderSmall = small;
        rider.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Opens the full-screen rider pickup board; calls are switched on with it.
                setRiderCalls(activity, true);
                showPanel(activity, Panel.RIDER);
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h);
        rlp.leftMargin = (int) (5 * d);
        row.addView(rider, rlp);

        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // Default: just right of the scanner camera button (which sits at 14% width, 44dp wide).
        lp.leftMargin = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.14f + 52 * d);
        lp.topMargin = (int) (7 * d);
        final ViewGroup content = (ViewGroup) activity.findViewById(android.R.id.content);
        content.addView(row, lp);
        buttonRow = row;

        // Restore the spot the user dragged it to (saved per orientation, as screen fractions).
        final SharedPreferences ui = activity.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE);
        final String key = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? "btn_land" : "btn_port";
        row.post(new Runnable() {
            @Override public void run() {
                String saved = ui.getString(key, null);
                if (saved == null) return;
                try {
                    String[] p = saved.split(",");
                    place(row, content, Float.parseFloat(p[0]) * content.getWidth(),
                            Float.parseFloat(p[1]) * content.getHeight());
                } catch (Throwable ignored) {
                }
            }
        });

        // Drag from anywhere on the group; a short tap still clicks the button under the finger.
        final int slop = android.view.ViewConfiguration.get(activity).getScaledTouchSlop();
        View.OnTouchListener drag = new View.OnTouchListener() {
            float downX, downY, startL, startT;
            boolean dragging;

            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        FrameLayout.LayoutParams cur = (FrameLayout.LayoutParams) row.getLayoutParams();
                        startL = cur.leftMargin;
                        startT = cur.topMargin;
                        dragging = false;
                        v.setPressed(true);
                        onUserTouch();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop) {
                            dragging = true;
                            v.setPressed(false);
                            row.animate().scaleX(1.06f).scaleY(1.06f).setDuration(100).start();
                        }
                        if (dragging) {
                            place(row, content, startL + dx, startT + dy);
                            onUserTouch();
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (dragging) {
                            row.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                            FrameLayout.LayoutParams fin = (FrameLayout.LayoutParams) row.getLayoutParams();
                            if (content.getWidth() > 0 && content.getHeight() > 0) {
                                ui.edit().putString(key, (fin.leftMargin / (float) content.getWidth()) + ","
                                        + (fin.topMargin / (float) content.getHeight())).apply();
                            }
                        } else if (v == dosaButton && e.getEventTime() - e.getDownTime() >= 700) {
                            phoneDialog(activity); // long-press DOSA LIVE: customer phone tracking setup
                        } else if (v == riderButton && e.getEventTime() - e.getDownTime() >= 700) {
                            bridgeDialog(activity); // long-press the rider button: Bridge Print setup
                        } else if (v.hasOnClickListeners()) {
                            v.performClick();
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        row.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
                        return true;
                    default:
                        return false;
                }
            }
        };
        grip.setOnTouchListener(drag);
        dosa.setOnTouchListener(drag);
        prep.setOnTouchListener(drag);
        rider.setOnTouchListener(drag);
        riderButton = rider;
        rider.setOnLongClickListener(new View.OnLongClickListener() { // TV remote: hold OK
            @Override public boolean onLongClick(View v) {
                bridgeDialog(activity);
                return true;
            }
        });
    }

    private static View riderButton, dosaButton;

    /** Long-press DOSA LIVE: "Track your dosa" on customers' phones (Firebase database + QR page). */
    private static void phoneDialog(final Activity a) {
        final SharedPreferences ui = a.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE);
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        TextView info = new TextView(a);
        info.setText("Customers scan the QR code on the Order Ready screen, type their token number and "
                + "see Preparing / Ready live on their phone. Only token numbers and wait times are sent.\n\n"
                + "Status: " + PhoneTrack.status() + "\n\nFirebase Realtime Database address:");
        box.addView(info);
        final android.widget.EditText db = field(a, "babai-dosa-default-rtdb.asia-southeast1.firebasedatabase.app",
                ui.getString("track_db", ""));
        box.addView(db);
        TextView l2 = new TextView(a);
        l2.setText("\nDatabase secret (Project settings \u2192 Service accounts \u2192 Database secrets):");
        box.addView(l2);
        final android.widget.EditText key = field(a, "secret", ui.getString("track_secret", ""));
        box.addView(key);
        TextView l3 = new TextView(a);
        l3.setText("\nStatus page address (leave empty for the Babai Tiffins page):");
        box.addView(l3);
        final android.widget.EditText pg = field(a, PhoneTrack.DEFAULT_PAGE, ui.getString("track_page", ""));
        box.addView(pg);
        new android.app.AlertDialog.Builder(a)
                .setTitle("\uD83D\uDCF1 Track your dosa \u2022 customer phones")
                .setView(scrollable(a, box))
                .setPositiveButton("Save & test", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dlg, int which) {
                        ui.edit().putString("track_db", db.getText().toString().trim())
                                .putString("track_secret", key.getText().toString().trim())
                                .putString("track_page", pg.getText().toString().trim()).apply();
                        PhoneTrack.configure(db.getText().toString(), key.getText().toString(), pg.getText().toString());
                        if (panel != null) panel.bindTokens();
                        if (!PhoneTrack.enabled()) {
                            android.widget.Toast.makeText(a, "Phone tracking off (no database set)",
                                    android.widget.Toast.LENGTH_LONG).show();
                            return;
                        }
                        final String body = PhoneTrack.payload(tokens.ready(), tokens.preparing(), last,
                                System.currentTimeMillis());
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final String err = PhoneTrack.test(body);
                                a.runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        android.widget.Toast.makeText(a, err == null
                                                ? "\u2713 Connected. The QR code now shows on the Order Ready screen."
                                                : "\u26A0 " + err, android.widget.Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static android.widget.EditText field(Activity a, String hint, String value) {
        android.widget.EditText e = new android.widget.EditText(a);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(value);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        return e;
    }
    private static boolean riderSmall;

    static void setRiderCalls(Context c, boolean on) {
        riderOn = on;
        c.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE).edit().putBoolean("rider_calls", on).apply();
        if (riderButton instanceof TextView) styleRider((TextView) riderButton, riderSmall);
        if (on) sharedVoice(c); // warm up the voice engine
    }

    private static View scrollable(Activity a, View v) {
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(v);
        return sv;
    }

    /** Bridge Print address (for pickup slot numbers) + connection test. */
    private static void bridgeDialog(final Activity a) {
        final SharedPreferences ui = a.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE);
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (8 * d), (int) (20 * d), 0);
        final TextView info = new TextView(a);
        String sample = BridgeSlots.sample();
        info.setText("Bridge Print PC address on the shop Wi-Fi (the one that opens the rider screen "
                + "on a phone), e.g. 192.168.1.3:8787\n\nStatus: " + BridgeSlots.status()
                + (sample.isEmpty() ? "" : "\n\nData received (send a photo of this if orders don't show):\n" + sample));
        info.setTextIsSelectable(true);
        box.addView(info);
        final android.widget.EditText addr = new android.widget.EditText(a);
        addr.setSingleLine(true);
        addr.setHint("192.168.1.3:8787");
        addr.setText(ui.getString("bridge_addr", ""));
        addr.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(addr);
        new android.app.AlertDialog.Builder(a)
                .setTitle("\uD83D\uDEF5 Rider calls \u2022 Bridge Print slots")
                .setView(scrollable(a, box))
                .setPositiveButton("Save & test", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dlg, int which) {
                        final String v = addr.getText().toString().trim();
                        ui.edit().putString("bridge_addr", v).apply();
                        BridgeSlots.setAddress(v);
                        if (v.isEmpty()) return;
                        new Thread(new Runnable() {
                            @Override public void run() {
                                final boolean ok = BridgeSlots.pollOnce();
                                a.runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        android.widget.Toast.makeText(a, (ok ? "\u2713 " : "\u26A0 ") + "Bridge Print: "
                                                + BridgeSlots.status(), android.widget.Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        }).start();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static final int[] RIDER_OFF = {0xFF455A64, 0xFF546E7A, 0xFF607D8B};
    private static final int[] RIDER_ON = {0xFF0D47A1, 0xFF1E88E5, 0xFF42A5F5};

    private static void styleRider(TextView b, boolean small) {
        // Tap = open the rider pickup board; blue = rider calls on, grey = off.
        b.setText(small ? "\uD83D\uDEF5 RIDER" : "\uD83D\uDEF5 RIDER PICKUP");
        float d = b.getResources().getDisplayMetrics().density;
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, riderOn ? RIDER_ON : RIDER_OFF);
        bg.setCornerRadius(18 * d);
        bg.setStroke((int) (1.5f * d), 0xCCFFFFFF);
        b.setBackground(bg);
    }

    /** Moves the button group, kept fully on screen. */
    private static void place(View row, ViewGroup content, float left, float top) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) row.getLayoutParams();
        int maxL = Math.max(0, content.getWidth() - row.getWidth());
        int maxT = Math.max(0, content.getHeight() - row.getHeight());
        lp.leftMargin = (int) Math.max(0, Math.min(maxL, left));
        lp.topMargin = (int) Math.max(0, Math.min(maxT, top));
        row.setLayoutParams(lp);
    }

    private static TextView pill(Activity a, String label, int[] colors, int glow) {
        float d = a.getResources().getDisplayMetrics().density;
        boolean small = compact(a);
        TextView b = new TextView(a);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, small ? 11 : 13); // ignores the phone's font-size setting
        b.setIncludeFontPadding(false);
        b.setGravity(Gravity.CENTER);
        b.setPadding((int) ((small ? 9 : 12) * d), 0, (int) ((small ? 9 : 12) * d), 0);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
        bg.setCornerRadius(18 * d);
        bg.setStroke((int) (1.5f * d), 0xCCFFFFFF);
        b.setBackground(bg);
        b.setShadowLayer((small ? 5 : 8) * d, 0, 0, glow);
        return b;
    }

    // ---- nightly refresh: the TV can run for weeks without a restart ---------------------------

    /**
     * Around 4:30 AM, if the app has been running 20+ hours and nobody touched it for 30 minutes,
     * rebuild the screen (like a rotation): clears anything that piled up during the day. The
     * KDS data and connection are kept, and the add-on screen that was showing comes back.
     */
    private static void nightlyRefresh(long now) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(now);
        int minuteOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE);
        if (minuteOfDay < 4 * 60 + 30 || minuteOfDay > 5 * 60 + 30) return;
        if (now - PROCESS_START < 20 * 3600_000L || now - lastUserAt < 30 * 60_000L) return;
        final Activity a = current == null ? null : current.get();
        if (a == null || a.isFinishing()) return;
        SharedPreferences ui = a.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE);
        String today = DosaStats.dayKey(now);
        if (today.equals(ui.getString("refreshed_day", ""))) return;
        ui.edit().putString("refreshed_day", today).commit();
        lastPersistAt = 0; // save now, not throttled
        persist();
        try {
            PrepLive.tick(now, true);
        } catch (Throwable ignored) {
        }
        a.recreate();
    }

    // ---- reopen the screen that was showing when the app was closed ---------------------------

    private static final String LAST = "last_screen";

    /** "dosa" (Dosa Live / Order Ready, mode kept separately), "prep", or "" for the KDS board. */
    static void remember(Context c, String screen) {
        c.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE).edit().putString(LAST, screen).apply();
    }

    private static void restoreLastScreen(final Activity a) {
        final String last = a.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE).getString(LAST, "");
        if (last.isEmpty()) return;
        final Handler h = new Handler(Looper.getMainLooper());
        final long until = System.currentTimeMillis() + 30_000L;
        // Dark cover at once, so the white KDS board never flashes while the screen is rebuilt.
        final View cover = new View(a);
        cover.setBackgroundColor(0xFF080B10);
        cover.setElevation(40 * a.getResources().getDisplayMetrics().density);
        cover.setClickable(true);
        final ViewGroup root = (ViewGroup) a.findViewById(android.R.id.content);
        root.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        final Runnable uncover = new Runnable() {
            @Override public void run() {
                if (cover.getParent() != null) root.removeView(cover);
            }
        };
        h.postDelayed(uncover, 15_000L); // never leave it up
        h.post(new Runnable() {
            @Override public void run() {
                if (a.isFinishing()) return;
                try {
                    if ("dosa".equals(last)) {
                        if (panel == null || panel.activity != a) showPanel(a, -1);
                        h.postDelayed(uncover, 250);
                    } else if ("rider".equals(last)) {
                        if (panel == null || panel.activity != a) showPanel(a, Panel.RIDER);
                        h.postDelayed(uncover, 250);
                    } else if ("prep".equals(last)) {
                        if (PrepLive.available()) {
                            PrepLive.open(a);
                            h.postDelayed(uncover, 250);
                        } else if (System.currentTimeMillis() < until) {
                            h.postDelayed(this, 500); // history still loading
                        } else {
                            uncover.run();
                        }
                    } else {
                        uncover.run();
                    }
                } catch (Throwable ignored) {
                    uncover.run();
                }
            }
        });
    }

    /** @param mode -1 = the last Dosa Live / Order Ready mode, or Panel.RIDER */
    private static void showPanel(Activity activity, int mode) {
        remember(activity, mode == Panel.RIDER ? "rider" : "dosa");
        if (panel != null) panel.close();
        panel = new Panel(activity, mode);
        if (last == null && stats != null) last = stats.compute(System.currentTimeMillis());
        render();
    }

    private static void render() {
        if (panel != null && last != null) panel.bind(last);
        if (panel != null) panel.bindTokens();
    }

    /**
     * Full-screen overlay inside MainActivity (no new Activity / manifest change needed) with two
     * modes: the Dosa Live wait screen and the Order Ready token board (with voice calls).
     */
    private static final class Panel {
        static final int DOSA_LIVE = 0, ORDER_READY = 1, RIDER = 2;
        RiderBoardView riderView;
        AutoBrightness brightness;
        final Activity activity;
        final SharedPreferences ui;
        final DosaNeonView view;
        TokenBoardView board;
        TokenVoice voice;
        int mode;

        Panel(Activity a, int forceMode) {
            activity = a;
            float d = a.getResources().getDisplayMetrics().density;
            ui = a.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE);
            view = new DosaNeonView(a);
            view.setElevation(24 * d);
            view.setKeepScreenOn(true);
            view.setTheme(ui.getInt("theme", 0));
            view.setListener(new DosaNeonView.Listener() {
                @Override public void onClose() { closeByUser(); }

                @Override public void onTheme(int index) { ui.edit().putInt("theme", index).apply(); }

                @Override public void onOrderReady() { setMode(ORDER_READY); }
            });
            ViewGroup root = (ViewGroup) a.findViewById(android.R.id.content);
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            view.bringToFront();
            setMode(forceMode >= 0 ? forceMode : ui.getInt("mode", DOSA_LIVE));
        }

        private void showRider() {
            if (riderView == null) {
                riderView = new RiderBoardView(activity);
                riderView.setElevation(28 * activity.getResources().getDisplayMetrics().density);
                riderView.setKeepScreenOn(true);
                riderView.setListener(new RiderBoardView.Listener() {
                    @Override public void onClose() { closeByUser(); }

                    @Override public void onVoice(boolean on) { setRiderCalls(activity, on); }

                    @Override public void onBrightness(int m) {
                        ui.edit().putInt("rider_bright", m).apply();
                        if (brightness != null) brightness.start(m);
                    }

                    @Override public void onDuration(int minutes) {
                        ui.edit().putInt("rider_max_min", minutes).apply();
                        riderMaxMin = minutes;
                        riders.setMaxMinutes(minutes);
                    }
                });
                ((ViewGroup) activity.findViewById(android.R.id.content)).addView(riderView,
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
            }
            riderView.setVoice(riderOn);
            if (brightness == null) brightness = new AutoBrightness(activity);
            brightness.start(ui.getInt("rider_bright", 0));
            riderView.setBrightness(ui.getInt("rider_bright", 0), brightness.hasSensor());
            riderView.setDuration(ui.getInt("rider_max_min", 0));
            riderView.setVisibility(View.VISIBLE);
            riderView.bringToFront();
            view.setVisibility(View.INVISIBLE);
            if (board != null) board.setVisibility(View.GONE);
            bindRider();
        }

        void bindRider() {
            if (mode != RIDER || riderView == null) return;
            List<RiderCalls.Waiting> rl = riders.readyList();
            for (RiderCalls.Waiting w : rl) {
                String sl = BridgeSlots.slotFor(w.order.orderId);
                if (!sl.isEmpty()) w.order.slot = sl;
            }
            List<RiderCalls.Order> pl = riders.preparing();
            for (RiderCalls.Order o : pl) {
                String sl = BridgeSlots.slotFor(o.orderId);
                if (!sl.isEmpty()) o.slot = sl;
            }
            String addr = BridgeSlots.address();
            String src = addr == null || addr.isEmpty()
                    ? "Orders: Petpooja \u2022 Bridge Print not set (long-press the rider button)"
                    : bridgeMode() ? "Orders & slots: Bridge Print \u2022 " + BridgeSlots.status()
                    : "Orders: Petpooja (Bridge Print: " + BridgeSlots.status() + ")";
            riderView.setData(rl, pl, src, bridgeMode());
        }

        void setMode(int m) {
            mode = m;
            if (m == RIDER) {
                remember(activity, "rider");
                showRider();
                return;
            }
            remember(activity, "dosa");
            if (riderView != null) riderView.setVisibility(View.GONE);
            if (brightness != null) brightness.stop(); // normal brightness outside the rider board
            ui.edit().putInt("mode", m).apply();
            if (m == ORDER_READY) {
                if (board == null) {
                    board = new TokenBoardView(activity);
                    board.setElevation(26 * activity.getResources().getDisplayMetrics().density);
                    board.setKeepScreenOn(true);
                    board.setListener(new TokenBoardView.Listener() {
                        @Override public void onClose() { closeByUser(); }

                        @Override public void onDosaLive() { setMode(DOSA_LIVE); }

                        @Override public void onCollected(String label) {
                            tokens.collect(label, System.currentTimeMillis());
                            bindTokens();
                        }

                        @Override public void onAutoClear(int minutes) {
                            ui.edit().putInt("autoclear_min", minutes).apply();
                            tokens.setAutoClearMinutes(minutes);
                            tokens.refresh(System.currentTimeMillis());
                            bindTokens();
                        }

                        @Override public void onVoice(boolean on) {
                            ui.edit().putBoolean("voice", on).apply();
                            if (!on) {
                                tokens.clearPending();
                            } else if (voice == null) {
                                voice = sharedVoice(activity);
                            }
                        }
                    });
                    ((ViewGroup) activity.findViewById(android.R.id.content)).addView(board,
                            new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT));
                }
                board.setTheme(ui.getInt("theme", 0));
                board.setVoice(voiceOn());
                board.setAutoClear(ui.getInt("autoclear_min", 10));
                board.setVisibility(View.VISIBLE);
                board.bringToFront();
                view.setVisibility(View.INVISIBLE);
                if (voiceOn() && voice == null) voice = sharedVoice(activity);
                bindTokens();
            } else {
                view.setVisibility(View.VISIBLE);
                view.bringToFront();
                if (board != null) board.setVisibility(View.GONE);
                tokens.clearPending();
            }
        }

        boolean voiceOn() {
            return ui.getBoolean("voice", true);
        }

        void bindTokens() {
            if (mode == ORDER_READY && board != null) {
                board.setTheme(ui.getInt("theme", 0)); // follows the Dosa Live theme / auto theme
                board.setData(tokens.ready(), tokens.preparing(), last);
                board.setQr(PhoneTrack.qrUrl());
            }
        }

        void announce(List<TokenBoard.Token> call) {
            if (mode != ORDER_READY || board == null) return;
            board.showCall(call);
            if (voiceOn()) {
                if (voice == null) voice = sharedVoice(activity);
                voice.say(TokenBoard.phrase(call));
            }
        }

        void bind(DosaStats.Result r) {
            String time = DateFormat.getTimeFormat(activity).format(new java.util.Date(r.updatedAt));
            StringBuilder sb = new StringBuilder(String.format(Locale.US,
                    "Dine-In Dosa KOTs only | exact %.1f min | completed today %d | %.2f KOT/min (%d in last %.0f min)"
                            + " | queue %s | oldest waiting %.0f min\n",
                    r.etaMin, r.completedToday, r.throughputPerMin, r.recentCompletions, r.windowMin,
                    Double.isNaN(r.queueMin) ? "-" : String.format(Locale.US, "%.1f min", r.queueMin),
                    r.oldestActiveMin));
            sb.append("Date        Done  PrepMin   Avg  Peak/min");
            for (String line : r.history) {
                String[] p = line.split("\\|");
                if (p.length < 5) continue;
                sb.append(String.format(Locale.US, "%n%-10s %5s %8s %5s %8s", p[0], p[1], p[2], p[3], p[4]));
            }
            view.setData(r, time, sb.toString());
        }

        /** Closed with the close button / Back: next app start opens on the KDS board. */
        void closeByUser() {
            remember(activity, "");
            close();
        }

        void close() {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) parent.removeView(view);
            if (board != null && board.getParent() != null) ((ViewGroup) board.getParent()).removeView(board);
            if (riderView != null && riderView.getParent() != null) ((ViewGroup) riderView.getParent()).removeView(riderView);
            if (brightness != null) brightness.stop();
            voice = null; // the shared voice keeps running (rider calls may use it)
            tokens.clearPending();
            if (panel == this) panel = null;
        }
    }
}
