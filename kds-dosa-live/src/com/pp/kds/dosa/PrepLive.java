package com.pp.kds.dosa;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.pp.kds.core.common.model.OrderType;
import com.pp.kds.domain.model.Kot;
import com.pp.kds.domain.model.KotItem;
import com.pp.kds.feature.dashboard.KotCard;
import com.pp.kds.feature.dashboard.ui.BoardVisualsKt;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PREP LIVE: staff production screen. Every KOT on the board (all order types) is turned into
 * usage of the fresh-prep items through the BOM (assets/prep_profile.json, quantities only), with
 * used today / last hour / next-hour projection, batch-made tracking and raw-ingredient breakdown.
 */
final class PrepLive {

    private static final String PREFS = "prep_live_stats";
    private static final String HISTORY_PREFS = "prep_live_history";
    private static final String UI_PREFS = "prep_live_ui";
    private static final int CARDS = 11;
    private static int[] slots;                       // target shown on each card
    private static SharedPreferences historyPrefs, uiPrefs;
    private static final long PERSIST_MS = 60_000L;
    private static final double[] BATCH_CHIPS = {1, 2, 5, 10, 20};

    private static final int BG = 0xFF120E07, SURFACE = 0xFF1E1810, CREAM = 0xFFFFF4DC, MUTED = 0xFFB9AE98,
            GOLD = 0xFFF5B21B, GREEN = 0xFF69F0AE, AMBER = 0xFFFFB300, RED = 0xFFFF5A36, LEAF = 0xFF8BCB8E;

    private static PrepStats stats;
    private static PrepStats.Result last;
    private static SharedPreferences prefs;
    private static long lastPersist;
    private static boolean dirty;
    private static Screen screen;

    private PrepLive() {}

    // ---- data ----------------------------------------------------------------------------

    static void init(Context ctx) {
        if (stats != null) return;
        PrepStats.Model m = loadModel(ctx);
        if (m == null) return;
        stats = new PrepStats(m);
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        historyPrefs = ctx.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE);
        uiPrefs = ctx.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
        stats.loadHistory(historyPrefs.getString("history", ""));
        slots = new int[CARDS];
        String[] saved0 = uiPrefs.getString("slots", "").split(",");
        for (int i = 0; i < CARDS; i++) {
            int t = -1;
            try {
                if (i < saved0.length) t = Integer.parseInt(saved0[i]);
            } catch (NumberFormatException ignored) {
            }
            if (t < 0 || t >= m.targets.length) t = i < m.defaults.length ? m.defaults[i] : Math.min(i, m.targets.length - 1);
            slots[i] = t;
        }
        Map<String, String> saved = new HashMap<String, String>();
        for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) saved.put(e.getKey(), String.valueOf(e.getValue()));
        stats.load(saved, System.currentTimeMillis());
        last = stats.compute(System.currentTimeMillis());
    }

    static double seedDosaAvg() {
        return stats == null ? Double.NaN : stats.model().seedDosaAvgPrepMin;
    }

    private static PrepStats.Model loadModel(Context ctx) {
        try {
            InputStream in = ctx.getAssets().open("prep_profile.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            in.close();
            JSONObject o = new JSONObject(bo.toString("UTF-8"));
            PrepStats.Model m = new PrepStats.Model();
            JSONArray t = o.getJSONArray("targets");
            int n = t.length();
            m.targets = new String[n];
            for (int i = 0; i < n; i++) m.targets[i] = t.getString(i);
            JSONObject menu = o.getJSONObject("menu");
            Iterator<String> keys = menu.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject per = menu.getJSONObject(k);
                double[] v = new double[n];
                Iterator<String> ti = per.keys();
                while (ti.hasNext()) {
                    String idx = ti.next();
                    int i = Integer.parseInt(idx);
                    if (i < n) v[i] = per.getDouble(idx);
                }
                m.menu.put(k, v);
            }
            JSONArray rec = o.getJSONArray("recipes");
            m.recipeNames = new String[n][];
            m.recipeQty = new double[n][];
            m.recipeUnits = new String[n][];
            for (int i = 0; i < n; i++) {
                JSONArray rows = i < rec.length() ? rec.getJSONArray(i) : new JSONArray();
                m.recipeNames[i] = new String[rows.length()];
                m.recipeQty[i] = new double[rows.length()];
                m.recipeUnits[i] = new String[rows.length()];
                for (int j = 0; j < rows.length(); j++) {
                    JSONArray row = rows.getJSONArray(j);
                    m.recipeNames[i][j] = row.getString(0);
                    m.recipeQty[i][j] = row.getDouble(1);
                    m.recipeUnits[i][j] = row.getString(2);
                }
            }
            JSONArray prof = o.getJSONArray("profile");
            m.profile = new double[n][7][24];
            for (int i = 0; i < n && i < prof.length(); i++) {
                JSONArray wk = prof.getJSONArray(i);
                for (int wd = 0; wd < 7 && wd < wk.length(); wd++) {
                    JSONArray hrs = wk.getJSONArray(wd);
                    for (int h = 0; h < 24 && h < hrs.length(); h++) m.profile[i][wd][h] = hrs.getDouble(h);
                }
            }
            if (!o.isNull("dosaDineInAvgPrepMin")) m.seedDosaAvgPrepMin = o.getDouble("dosaDineInAvgPrepMin");
            JSONArray un = o.optJSONArray("units");
            m.units = new String[n];
            for (int i = 0; i < n; i++) m.units[i] = un != null && i < un.length() ? un.getString(i) : "kg";
            JSONArray df = o.optJSONArray("defaults");
            m.defaults = new int[df == null ? 0 : df.length()];
            for (int i = 0; i < m.defaults.length; i++) m.defaults[i] = df.getInt(i);
            return m;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Called by the tracker whenever the board list changes. */
    static void onBoard(List<KotCard> cards, long now) {
        if (stats == null) return;
        List<PrepStats.Kot> kots = new ArrayList<PrepStats.Kot>();
        for (KotCard card : cards) {
            Kot kot = card.getKot();
            if (kot == null || kot.getId() == null) continue;
            List<KotItem> items = kot.getItems();
            if (items == null) continue;
            List<String> names = new ArrayList<String>();
            List<Double> qty = new ArrayList<Double>();
            for (KotItem item : items) {
                if (BoardVisualsKt.isItemCancelled(item.getStatus())) continue;
                names.add(item.getName() == null ? "" : item.getName());
                Double q = item.getQuantity();
                qty.add(q == null || q <= 0 ? 1.0 : q);
            }
            OrderType ot = OrderType.Companion.fromId(kot.getOrderType());
            int type = ot == OrderType.DINE_IN ? 0 : ot == OrderType.TAKE_AWAY ? 1 : ot == OrderType.DELIVERY ? 2 : 3;
            Long created = BoardVisualsKt.parseCreatedMillis(kot.getCreatedTime());
            double[] q = new double[qty.size()];
            for (int i = 0; i < q.length; i++) q[i] = qty.get(i);
            kots.add(new PrepStats.Kot(kot.getId(), created == null ? 0L : created, type,
                    "0".equals(kot.getKotStatus()), names.toArray(new String[0]), q));
        }
        if (stats.update(kots, now)) dirty = true;
    }

    /** Periodic refresh from the tracker loop. */
    static void tick(long now, boolean force) {
        if (stats == null) return;
        if (force || screen != null || dirty) last = stats.compute(now);
        if ((dirty || stats.historyChanged()) && now - lastPersist >= PERSIST_MS) persist(now);
        if (screen != null) screen.bind(last);
    }

    private static void persist(long now) {
        SharedPreferences.Editor ed = prefs.edit();
        ed.clear();
        for (Map.Entry<String, String> e : stats.save().entrySet()) ed.putString(e.getKey(), e.getValue());
        ed.apply();
        if (stats.historyChanged()) historyPrefs.edit().putString("history", stats.historyBlob()).apply();
        lastPersist = now;
        dirty = false;
    }

    static boolean available() {
        return stats != null;
    }

    static void setSlot(int card, int target) {
        slots[card] = target;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < slots.length; i++) sb.append(i == 0 ? "" : ",").append(slots[i]);
        uiPrefs.edit().putString("slots", sb.toString()).apply();
    }

    static void open(Activity a) {
        if (stats == null) return;
        if (screen != null) screen.close();
        screen = new Screen(a);
        tick(System.currentTimeMillis(), true);
    }

    // ---- formatting ----------------------------------------------------------------------

    /** Amount in the item's own unit: kg (g below 1), ltr (ml below 1), pcs, ... */
    static String amt(double v, String unit) {
        if (Double.isNaN(v)) return "\u2014";
        String u = unit == null ? "kg" : unit.toLowerCase(Locale.US);
        if (u.equals("kg") || u.equals("ltr") || u.equals("l")) {
            boolean kg = u.equals("kg");
            if (Math.abs(v) < 1) return String.format(Locale.US, "%.0f %s", v * 1000, kg ? "g" : "ml");
            return String.format(Locale.US, Math.abs(v) < 100 ? "%.1f %s" : "%.0f %s", v, kg ? "kg" : "L");
        }
        return String.format(Locale.US, "%.0f %s", v, u);
    }

    static String qty(double v, String unit) {
        String u = unit == null ? "" : unit.toLowerCase(Locale.US);
        if (u.equals("kg") || u.equals("ltr") || u.equals("l")) {
            if (Math.abs(v) < 1) return String.format(Locale.US, "%.0f %s", v * 1000, u.equals("kg") ? "g" : "ml");
            return String.format(Locale.US, "%.2f %s", v, u);
        }
        return String.format(Locale.US, "%.1f %s", v, unit);
    }

    static GradientDrawable box(int fill, int stroke, float radius, float strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke((int) strokeW, stroke);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static int statusColor(int status) {
        return status == 3 ? RED : status == 2 ? AMBER : status == 1 ? GREEN : 0x44FFFFFF;
    }

    // ---- screen --------------------------------------------------------------------------

    private static final class Screen {
        final Activity a;
        final float d;
        final FrameLayout root;
        final TextView updated;
        final List<Card> cards = new ArrayList<Card>();
        final TextView coverage;
        Detail detail;

        Screen(Activity act) {
            a = act;
            d = a.getResources().getDisplayMetrics().density;
            root = new FrameLayout(a);
            root.setBackgroundColor(BG);
            root.setClickable(true);
            root.setElevation(30 * d);

            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));

            LinearLayout header = new LinearLayout(a);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(a, "PREP LIVE", 22, GOLD, true);
            title.setLetterSpacing(0.12f);
            header.addView(title);
            TextView sub = text(a, "   Babai Tiffins \u2022 fresh production \u2022 all orders", 12, MUTED, false);
            header.addView(sub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            updated = text(a, "", 12, CREAM, true);
            header.addView(updated);
            TextView close = text(a, "  \u2715  ", 18, CREAM, true);
            close.setPadding((int) (12 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));
            close.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { close(); }
            });
            header.addView(close);
            col.addView(header);

            boolean landscape = a.getResources().getDisplayMetrics().widthPixels
                    >= a.getResources().getDisplayMetrics().heightPixels;
            int cols = landscape ? 4 : 2;
            GridLayout grid = new GridLayout(a);
            grid.setColumnCount(cols);
            int n = CARDS;
            for (int i = 0; i < n + 1; i++) {
                View cell;
                if (i < n) {
                    Card c = new Card(a, i);
                    cards.add(c);
                    cell = c.view;
                } else {
                    LinearLayout info = new LinearLayout(a);
                    info.setOrientation(LinearLayout.VERTICAL);
                    info.setPadding((int) (12 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
                    info.setBackground(box(SURFACE, 0x33FFFFFF, 12 * d, 1 * d));
                    info.addView(text(a, "BOARD TODAY", 12, GOLD, true));
                    TextView cv = text(a, "", 11, MUTED, false);
                    info.addView(cv);
                    cell = info;
                    coverageHolder[0] = cv;
                }
                GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f));
                lp.width = 0;
                lp.height = 0;
                lp.setMargins((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
                grid.addView(cell, lp);
            }
            coverage = coverageHolder[0];
            col.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            root.addView(col);
            ((ViewGroup) a.findViewById(android.R.id.content)).addView(root,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.bringToFront();
        }

        private final TextView[] coverageHolder = new TextView[1];

        void bind(PrepStats.Result r) {
            if (r == null) return;
            updated.setText("\u25CF LIVE  \u2022  UPDATED " + DateFormat.getTimeFormat(a).format(new java.util.Date(r.updatedAt)));
            updated.setTextColor(CREAM);
            for (int i = 0; i < cards.size(); i++) cards.get(i).bind(r.items[slots[i]]);
            StringBuilder sb = new StringBuilder();
            sb.append("KOTs counted: Dine-in ").append(r.kotsByType[0]).append(" \u2022 Pick-up ").append(r.kotsByType[1])
                    .append(" \u2022 Delivery ").append(r.kotsByType[2]);
            if (r.kotsByType[3] > 0) sb.append(" \u2022 Other ").append(r.kotsByType[3]);
            sb.append("\n\nNot in BOM (not counted):");
            if (r.unmatched.isEmpty()) sb.append("\nnone");
            for (String u : r.unmatched) sb.append("\n\u2022 ").append(u);
            sb.append("\n\nProjection = typical for this weekday/hour (Petpooja + this tablet), scaled by the last hour.");
            if (coverage != null) coverage.setText(sb.toString());
            if (detail != null) detail.bind(r.items[detail.index]);
            if (picker != null) picker.refreshSelection();
        }

        void close() {
            if (detail != null) detail.close();
            ViewGroup p = (ViewGroup) root.getParent();
            if (p != null) p.removeView(root);
            if (screen == this) screen = null;
        }

        /** One item tile. */
        final class Card {
            final int index;
            final LinearLayout view;
            final TextView name, chip, next, sub;
            final Bars bars;

            Card(Context c, final int i) {
                index = i;
                view = new LinearLayout(c);
                view.setOrientation(LinearLayout.VERTICAL);
                view.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (6 * d));
                view.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { openDetail(i); }
                });
                view.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        openPicker(i);
                        return true;
                    }
                });
                LinearLayout top = new LinearLayout(c);
                top.setGravity(Gravity.CENTER_VERTICAL);
                name = text(c, "", 14, CREAM, true);
                name.setSingleLine(true);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                chip = text(c, "", 10, Color.BLACK, true);
                chip.setPadding((int) (7 * d), (int) (2 * d), (int) (7 * d), (int) (2 * d));
                top.addView(chip);
                view.addView(top);
                view.addView(text(c, "NEXT 1 HR", 9, MUTED, true));
                next = text(c, "", 22, Color.WHITE, true);
                view.addView(next);
                sub = text(c, "", 11, MUTED, false);
                view.addView(sub);
                bars = new Bars(c);
                view.addView(bars, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            }

            void bind(PrepStats.Item it) {
                name.setText(it.name);
                double diff = it.nextHour - it.lastHour;
                String arrow = Math.abs(diff) < 0.05 * Math.max(1, it.lastHour) ? "" : diff > 0 ? "  \u25B2" : "  \u25BC";
                next.setText("\u2248 " + amt(it.nextHour, it.unit) + arrow);
                sub.setText("Last hr " + amt(it.lastHour, it.unit) + "  \u2022  Today " + amt(it.usedToday, it.unit));
                if (it.status == 0) {
                    chip.setText("+ BATCH");
                    chip.setTextColor(CREAM);
                    chip.setBackground(box(0x22FFFFFF, 0x55FFFFFF, 8 * d, 1 * d));
                } else {
                    String s = it.status == 3 ? "PREP NOW" : it.status == 2
                            ? String.format(Locale.US, "PREP IN %.0fm", Math.max(0, it.minutesLeft - 15))
                            : String.format(Locale.US, "OK %.0fm", Math.min(999, it.minutesLeft));
                    chip.setText(s);
                    chip.setTextColor(Color.BLACK);
                    chip.setBackground(box(statusColor(it.status), 0, 8 * d, 0));
                }
                view.setBackground(box(SURFACE, it.status >= 2 ? statusColor(it.status) : 0x33FFFFFF, 12 * d,
                        (it.status >= 2 ? 2.5f : 1f) * d));
                bars.set(it.todayHourly, it.typicalHourly);
            }
        }

        Picker picker;

        /** card = which tile was tapped; the detail shows that tile's current item. */
        void openDetail(int card) {
            if (detail != null) detail.close();
            detail = new Detail(a, card, slots[card]);
            if (last != null) detail.bind(last.items[slots[card]]);
        }

        void openPicker(int card) {
            if (picker != null) picker.close();
            picker = new Picker(a, card);
        }

        /** Searchable list of every OP / CP item, busiest first. Tap one to show it on the card. */
        final class Picker {
            final int card;
            final FrameLayout overlay;
            final GridLayout list;
            final List<TextView> rows = new ArrayList<TextView>();
            final List<Integer> rowTarget = new ArrayList<Integer>();

            Picker(Context c, int cardIndex) {
                card = cardIndex;
                overlay = new FrameLayout(c);
                overlay.setBackgroundColor(0xF0120E07);
                overlay.setClickable(true);
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setPadding((int) (18 * d), (int) (12 * d), (int) (18 * d), (int) (12 * d));
                LinearLayout head = new LinearLayout(c);
                head.setGravity(Gravity.CENTER_VERTICAL);
                head.addView(text(c, "CHOOSE ITEM FOR CARD " + (card + 1), 16, GOLD, true),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                final android.widget.EditText search = new android.widget.EditText(c);
                search.setHint("Search OP / CP item");
                search.setHintTextColor(MUTED);
                search.setTextColor(CREAM);
                search.setSingleLine(true);
                search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                search.setBackground(box(SURFACE, 0x55FFFFFF, 8 * d, 1 * d));
                search.setPadding((int) (10 * d), (int) (6 * d), (int) (10 * d), (int) (6 * d));
                head.addView(search, new LinearLayout.LayoutParams((int) (240 * d), ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView x = text(c, "   ✕", 18, CREAM, true);
                x.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { close(); }
                });
                head.addView(x);
                col.addView(head);
                col.addView(text(c, "Sorted by typical daily use. Long-press any card to come back here.", 11, MUTED, false));

                list = new GridLayout(c);
                list.setColumnCount(3);
                final PrepStats.Model m = stats.model();
                List<Integer> order = new ArrayList<Integer>();
                for (int t = 0; t < m.targets.length; t++) order.add(t);
                java.util.Collections.sort(order, new java.util.Comparator<Integer>() {
                    @Override public int compare(Integer p, Integer q) {
                        return Double.compare(m.avgPerDay(q), m.avgPerDay(p));
                    }
                });
                int w = (int) ((a.getResources().getDisplayMetrics().widthPixels - 48 * d) / 3);
                for (final int t : order) {
                    TextView row = text(c, m.targets[t] + "\n~" + amt(m.avgPerDay(t), m.unit(t)) + " / day", 13, CREAM, false);
                    row.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            setSlot(card, t);
                            close();
                            tick(System.currentTimeMillis(), true);
                        }
                    });
                    GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                    lp.width = w - (int) (8 * d);
                    lp.setMargins((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (4 * d));
                    list.addView(row, lp);
                    rows.add(row);
                    rowTarget.add(t);
                }
                refreshSelection();
                search.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s1, int a1, int b1, int c1) {}
                    @Override public void onTextChanged(CharSequence s1, int a1, int b1, int c1) {}
                    @Override public void afterTextChanged(android.text.Editable e) {
                        String q = e.toString().trim().toLowerCase(Locale.US);
                        for (int i = 0; i < rows.size(); i++) {
                            boolean show = q.isEmpty() || m.targets[rowTarget.get(i)].toLowerCase(Locale.US).contains(q);
                            rows.get(i).setVisibility(show ? View.VISIBLE : View.GONE);
                        }
                    }
                });
                ScrollView sc = new ScrollView(c);
                sc.addView(list);
                col.addView(sc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
                overlay.addView(col);
                root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }

            void refreshSelection() {
                for (int i = 0; i < rows.size(); i++) {
                    boolean sel = rowTarget.get(i) == slots[card];
                    rows.get(i).setBackground(box(sel ? 0x33F5B21B : SURFACE, sel ? GOLD : 0x22FFFFFF, 10 * d, (sel ? 2 : 1) * d));
                }
            }

            void close() {
                ViewGroup p = (ViewGroup) overlay.getParent();
                if (p != null) p.removeView(overlay);
                if (picker == this) picker = null;
            }
        }


        /** Item detail: batch buttons, raw-ingredient breakdown for the next hour, top drivers. */
        final class Detail {
            final int index;
            final FrameLayout overlay;
            final TextView title, numbers, batch, breakdown, drivers;

            final int card;

            Detail(Context c, final int cardIndex, final int i) {
                index = i;
                card = cardIndex;
                overlay = new FrameLayout(c);
                overlay.setBackgroundColor(0xCC000000);
                overlay.setClickable(true);
                overlay.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { close(); }
                });
                LinearLayout panel = new LinearLayout(c);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setClickable(true);
                panel.setPadding((int) (20 * d), (int) (14 * d), (int) (20 * d), (int) (14 * d));
                panel.setBackground(box(SURFACE, GOLD, 16 * d, 1.5f * d));

                LinearLayout head = new LinearLayout(c);
                head.setGravity(Gravity.CENTER_VERTICAL);
                title = text(c, "", 20, GOLD, true);
                head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView change = text(c, "\u21C4 CHANGE ITEM", 13, Color.BLACK, true);
                change.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
                change.setBackground(box(LEAF, 0, 10 * d, 0));
                change.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        close();
                        openPicker(card);
                    }
                });
                head.addView(change);
                TextView x = text(c, "  \u2715  ", 18, CREAM, true);
                x.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { close(); }
                });
                head.addView(x);
                panel.addView(head);
                numbers = text(c, "", 13, CREAM, false);
                panel.addView(numbers);

                LinearLayout cols = new LinearLayout(c);
                cols.setPadding(0, (int) (10 * d), 0, 0);
                LinearLayout left = new LinearLayout(c);
                left.setOrientation(LinearLayout.VERTICAL);
                left.addView(text(c, "BATCH MADE (tap to add)", 11, GOLD, true));
                LinearLayout chips = new LinearLayout(c);
                chips.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
                final String unit = stats.model().unit(i);
                boolean pieces = !(unit.equalsIgnoreCase("kg") || unit.equalsIgnoreCase("ltr"));
                double[] sizes = pieces ? new double[]{10, 25, 50, 100, 200} : BATCH_CHIPS;
                String shortUnit = unit.equalsIgnoreCase("ltr") ? "L" : unit.toLowerCase(Locale.US);
                for (final double kgv : sizes) {
                    TextView b = text(c, "+" + (int) kgv + " " + shortUnit, 14, Color.BLACK, true);
                    b.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
                    b.setBackground(box(GOLD, 0, 10 * d, 0));
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            stats.addBatch(index, kgv, System.currentTimeMillis());
                            dirty = true;
                            persist(System.currentTimeMillis());
                            tick(System.currentTimeMillis(), true);
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.rightMargin = (int) (6 * d);
                    chips.addView(b, lp);
                }
                TextView undo = text(c, "UNDO", 13, CREAM, true);
                undo.setPadding((int) (10 * d), (int) (8 * d), (int) (10 * d), (int) (8 * d));
                undo.setBackground(box(0x22FFFFFF, 0x55FFFFFF, 10 * d, 1 * d));
                undo.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (stats.undoBatch(index)) {
                            dirty = true;
                            persist(System.currentTimeMillis());
                            tick(System.currentTimeMillis(), true);
                        }
                    }
                });
                chips.addView(undo);
                left.addView(chips);
                batch = text(c, "", 13, CREAM, false);
                left.addView(batch);
                left.addView(text(c, "\nDRIVEN BY (today)", 11, GOLD, true));
                drivers = text(c, "", 12, CREAM, false);
                left.addView(drivers);
                cols.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));

                LinearLayout right = new LinearLayout(c);
                right.setOrientation(LinearLayout.VERTICAL);
                right.setPadding((int) (16 * d), 0, 0, 0);
                right.addView(text(c, "NEXT 1 HOUR NEEDS (from BOM recipe)", 11, GOLD, true));
                breakdown = text(c, "", 12, CREAM, false);
                breakdown.setTypeface(Typeface.MONOSPACE);
                right.addView(breakdown);
                cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                panel.addView(cols);

                ScrollView sc = new ScrollView(c);
                sc.addView(panel);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        (int) Math.min(760 * d, a.getResources().getDisplayMetrics().widthPixels * 0.94f),
                        ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
                overlay.addView(sc, lp);
                root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }

            void bind(PrepStats.Item it) {
                title.setText(it.name);
                numbers.setText("Next 1 hr \u2248 " + amt(it.nextHour, it.unit) + "   \u2022   Last 1 hr " + amt(it.lastHour, it.unit)
                        + "   \u2022   Used today " + amt(it.usedToday, it.unit) + "   \u2022   Typical next hr " + amt(it.typicalNextHour, it.unit));
                if (it.made > 0) {
                    batch.setText("Made today " + amt(it.made, it.unit) + "  \u2022  Left \u2248 " + amt(Math.max(0, it.remaining), it.unit)
                            + (it.minutesLeft < 999 ? String.format(Locale.US, "  \u2022  lasts \u2248 %.0f min", it.minutesLeft) : ""));
                    batch.setTextColor(it.status >= 2 ? statusColor(it.status) : CREAM);
                } else {
                    batch.setText("No batch logged today \u2014 tap a size when a fresh batch is ready.");
                    batch.setTextColor(MUTED);
                }
                StringBuilder dr = new StringBuilder();
                for (String s : it.drivers) {
                    String[] p = s.split("\\|");
                    dr.append(String.format(Locale.US, "%3.0f%%  %s%n", Double.parseDouble(p[1]) * 100, p[0]));
                }
                drivers.setText(dr.length() == 0 ? "\u2014" : dr.toString().trim());
                StringBuilder bd = new StringBuilder();
                for (String s : it.breakdown) {
                    String[] p = s.split("\\|");
                    String nm = p[0].length() > 22 ? p[0].substring(0, 22) : p[0];
                    bd.append(String.format(Locale.US, "%-22s %10s%n", nm, qty(Double.parseDouble(p[1]), p[2])));
                }
                breakdown.setText(bd.length() == 0 ? "Central-kitchen item (no outlet recipe in BOM)" : bd.toString().trim());
            }

            void close() {
                ViewGroup p = (ViewGroup) overlay.getParent();
                if (p != null) p.removeView(overlay);
                if (detail == this) detail = null;
            }
        }
    }

    /** Mini chart: today's usage per hour (bars) vs this weekday's typical (line), 6 AM - 11 PM. */
    private static final class Bars extends View {
        private double[] today = new double[24], typical = new double[24];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private static final int FROM = 6, TO = 23;

        Bars(Context c) {
            super(c);
        }

        void set(double[] t, double[] typ) {
            today = t;
            typical = typ;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            double max = 0.001;
            for (int i = FROM; i <= TO; i++) max = Math.max(max, Math.max(today[i], typical[i]));
            int slots = TO - FROM + 1;
            float bw = w / slots;
            int nowH = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
            for (int i = FROM; i <= TO; i++) {
                float x = (i - FROM) * bw;
                float bh = (float) (today[i] / max) * (h - 2);
                p.setStyle(Paint.Style.FILL);
                p.setColor(i == nowH ? GOLD : 0xCCF5B21B);
                c.drawRect(x + bw * 0.18f, h - bh, x + bw * 0.82f, h, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1.5f, h * 0.04f));
            p.setColor(LEAF);
            android.graphics.Path path = new android.graphics.Path();
            for (int i = FROM; i <= TO; i++) {
                float x = (i - FROM + 0.5f) * bw, y = h - (float) (typical[i] / max) * (h - 2);
                if (i == FROM) path.moveTo(x, y); else path.lineTo(x, y);
            }
            c.drawPath(path, p);
        }
    }
}
