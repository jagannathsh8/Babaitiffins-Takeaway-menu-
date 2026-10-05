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
import android.widget.ScrollView;
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
            if (!hasDosa(kot)) continue;
            String status = kot.getKotStatus();
            boolean cancelled = "0".equals(status);
            boolean ready = card.getState().isDispatch() || "9".equals(status) || "10".equals(status);
            Long created = BoardVisualsKt.parseCreatedMillis(kot.getCreatedTime());
            out.add(new DosaStats.Entry(kot.getId(), created == null ? 0L : created, ready, cancelled));
        }
        return out;
    }

    private static boolean hasDosa(Kot kot) {
        List<KotItem> items = kot.getItems();
        if (items == null) return false;
        for (KotItem item : items) {
            String cat = item.getCategory();
            if (cat != null && cat.toLowerCase(Locale.US).contains(DOSA)
                    && !BoardVisualsKt.isItemCancelled(item.getStatus())) {
                return true;
            }
        }
        return false;
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
        b.setText("DOSA WAIT");
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setGravity(Gravity.CENTER);
        b.setPadding((int) (12 * d), 0, (int) (12 * d), 0);
        b.setBackground(rounded(0xFFE65100, 18 * d));
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

    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** Full-screen overlay inside MainActivity (no new Activity / manifest change needed). */
    private static final class Panel {
        final Activity activity;
        final FrameLayout root;
        final TextView count, eta, avg, updated, details, history;

        Panel(Activity a) {
            activity = a;
            float d = a.getResources().getDisplayMetrics().density;
            root = new FrameLayout(a);
            root.setBackgroundColor(0xCC000000);
            root.setClickable(true);
            root.setElevation(24 * d);
            root.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { close(); }
            });

            LinearLayout card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding((int) (28 * d), (int) (20 * d), (int) (28 * d), (int) (20 * d));
            card.setBackground(rounded(0xFF1B1B1F, 16 * d));
            card.setClickable(true); // taps inside the card don't close it

            LinearLayout header = new LinearLayout(a);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(a, "DOSA LIVE STATUS", 22, 0xFFFFB74D, true);
            header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView close = text(a, "CLOSE", 15, Color.WHITE, true);
            close.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
            close.setBackground(rounded(0xFF424242, 8 * d));
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { close(); }
            });
            header.addView(close);
            card.addView(header);

            count = row(a, card, "Current Dosa Orders");
            eta = row(a, card, "Approx. Wait Time");
            eta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
            eta.setTextColor(0xFF81C784);
            avg = row(a, card, "Today Avg Prep Time");
            updated = row(a, card, "Updated");

            details = text(a, "", 13, 0xFFB0BEC5, false);
            details.setPadding(0, (int) (14 * d), 0, 0);
            card.addView(details);
            history = text(a, "", 12, 0xFF90A4AE, false);
            history.setTypeface(Typeface.MONOSPACE);
            history.setPadding(0, (int) (10 * d), 0, 0);
            card.addView(history);

            ScrollView scroll = new ScrollView(a);
            scroll.addView(card);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    (int) Math.min(620 * d, a.getResources().getDisplayMetrics().widthPixels * 0.92f),
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            root.addView(scroll, lp);
            ((ViewGroup) a.findViewById(android.R.id.content)).addView(root,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void bind(DosaStats.Result r) {
            count.setText(String.valueOf(r.activeCount));
            eta.setText("Approx. " + r.etaLabel);
            avg.setText(Double.isNaN(r.avgPrepMin) ? "— (no Dosa KOT completed yet)"
                    : String.format(Locale.US, "%.1f min", r.avgPrepMin));
            updated.setText(DateFormat.getTimeFormat(activity).format(new java.util.Date(r.updatedAt)));
            details.setText(String.format(Locale.US,
                    "Exact estimate %.1f min  •  Completed today %d  •  Throughput %.2f KOT/min "
                            + "(%d in last %.0f min)  •  Queue %s  •  Oldest waiting %.0f min",
                    r.etaMin, r.completedToday, r.throughputPerMin, r.recentCompletions, r.windowMin,
                    Double.isNaN(r.queueMin) ? "—" : String.format(Locale.US, "%.1f min", r.queueMin),
                    r.oldestActiveMin));
            StringBuilder sb = new StringBuilder("Date        Done  PrepMin   Avg  Peak/min\n");
            for (String line : r.history) {
                String[] p = line.split("\\|");
                if (p.length < 5) continue;
                sb.append(String.format(Locale.US, "%-10s %5s %8s %5s %8s%n", p[0], p[1], p[2], p[3], p[4]));
            }
            history.setText(sb.toString().trim());
        }

        void close() {
            ViewGroup parent = (ViewGroup) root.getParent();
            if (parent != null) parent.removeView(root);
            if (panel == this) panel = null;
        }

        private static TextView row(Activity a, LinearLayout card, String label) {
            float d = a.getResources().getDisplayMetrics().density;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, (int) (12 * d), 0, 0);
            row.addView(text(a, label + ":", 18, 0xFFE0E0E0, false),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            TextView value = text(a, "—", 24, Color.WHITE, true);
            row.addView(value);
            card.addView(row);
            return value;
        }

        private static TextView text(Context c, String s, float sp, int color, boolean bold) {
            TextView t = new TextView(c);
            t.setText(s);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            t.setTextColor(color);
            if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
            return t;
        }
    }
}
