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
    private static long lastComputeAt;
    private static Field vmField;
    private static Panel panel;
    private static SharedPreferences prefs;

    private DosaLive() {}

    public static void install(final Activity activity) {
        try {
            addButton(activity);
            start(activity.getApplicationContext());
        } catch (Throwable ignored) {
            // Never let the add-on break the KDS.
        }
    }

    // ---- live tracking -------------------------------------------------------------------

    private static void start(Context ctx) {
        if (handler != null) return; // already running (activity recreated)
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        stats = new DosaStats();
        Map<String, String> saved = new HashMap<String, String>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
            saved.put(e.getKey(), String.valueOf(e.getValue()));
        }
        stats.load(saved, System.currentTimeMillis());
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
        if (state != null && state.getError() == null && !state.isLoading() && !state.getReconnecting()) {
            List<KotCard> cards = state.getCards();
            if (cards != null && cards != lastCards) { // StateFlow emits a new list on every change
                lastCards = cards;
                changed = stats.update(dosaEntries(cards), now, cards.size() >= BOARD_CAP - 5);
            }
        }
        if (changed || now - lastComputeAt >= RECOMPUTE_MS) {
            lastComputeAt = now;
            last = stats.compute(now);
            if (changed) persist();
            render();
        }
    }

    private static List<DosaStats.Entry> dosaEntries(List<KotCard> cards) {
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
        }
        return out;
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

    private static void persist() {
        SharedPreferences.Editor ed = prefs.edit();
        for (Map.Entry<String, String> e : stats.save().entrySet()) ed.putString(e.getKey(), e.getValue());
        ed.apply();
    }

    // ---- UI ------------------------------------------------------------------------------

    private static void addButton(final Activity activity) {
        float d = activity.getResources().getDisplayMetrics().density;
        TextView b = new TextView(activity);
        b.setText("\u2726 DOSA LIVE");
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setGravity(Gravity.CENTER);
        b.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFF5200, 0xFFFF8A00, 0xFFFFC107});
        bg.setCornerRadius(18 * d);
        bg.setStroke((int) (1.5f * d), 0xCCFFFFFF);
        b.setBackground(bg);
        b.setShadowLayer(8 * d, 0, 0, 0xFFFF5200);
        b.setElevation(8 * d);
        b.setContentDescription("Dosa Live Wait");
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showPanel(activity);
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) (36 * d));
        lp.gravity = Gravity.TOP | Gravity.START;
        // Just right of the scanner camera button (which sits at 14% width, 44dp wide).
        lp.leftMargin = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.14f + 52 * d);
        lp.topMargin = (int) (7 * d);
        ((ViewGroup) activity.findViewById(android.R.id.content)).addView(b, lp);
    }

    private static void showPanel(Activity activity) {
        if (panel != null) panel.close();
        panel = new Panel(activity);
        if (last == null && stats != null) last = stats.compute(System.currentTimeMillis());
        render();
    }

    private static void render() {
        if (panel != null && last != null) panel.bind(last);
    }

    /** Full-screen neon overlay inside MainActivity (no new Activity / manifest change needed). */
    private static final class Panel {
        final Activity activity;
        final DosaNeonView view;

        Panel(Activity a) {
            activity = a;
            view = new DosaNeonView(a);
            view.setElevation(24 * a.getResources().getDisplayMetrics().density);
            final SharedPreferences ui = a.getSharedPreferences("dosa_live_ui", Context.MODE_PRIVATE);
            view.setTheme(ui.getInt("theme", 0));
            view.setListener(new DosaNeonView.Listener() {
                @Override public void onClose() { close(); }

                @Override public void onTheme(int index) { ui.edit().putInt("theme", index).apply(); }
            });
            ((ViewGroup) a.findViewById(android.R.id.content)).addView(view,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            view.bringToFront();
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

        void close() {
            ViewGroup parent = (ViewGroup) view.getParent();
            if (parent != null) parent.removeView(view);
            if (panel == this) panel = null;
        }
    }
}
