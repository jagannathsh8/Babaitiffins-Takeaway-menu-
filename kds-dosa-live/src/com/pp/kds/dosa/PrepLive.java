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
    private static final long PERSIST_MS = 20_000L;
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
                    m.recipeNames[i][j] = row.getString(0).replaceFirst("^(OP|CP) - ", "");
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
        if (dirty && now - lastPersist >= PERSIST_MS) persist(now);
        if (screen != null) screen.bind(last);
    }

    private static void persist(long now) {
        SharedPreferences.Editor ed = prefs.edit();
        ed.clear();
        for (Map.Entry<String, String> e : stats.save().entrySet()) ed.putString(e.getKey(), e.getValue());
        ed.apply();
        lastPersist = now;
        dirty = false;
    }

    static boolean available() {
        return stats != null;
    }

    static void open(Activity a) {
        if (stats == null) return;
        if (screen != null) screen.close();
        screen = new Screen(a);
        tick(System.currentTimeMillis(), true);
    }

    // ---- formatting ----------------------------------------------------------------------

    static String kg(double v) {
        if (Double.isNaN(v)) return "—";
        if (Math.abs(v) < 1) return String.format(Locale.US, "%.0f g", v * 1000);
        return String.format(Locale.US, Math.abs(v) < 100 ? "%.1f kg" : "%.0f kg", v);
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
            TextView sub = text(a, "   Babai Tiffins • fresh production • all orders", 12, MUTED, false);
            header.addView(sub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            updated = text(a, "", 12, CREAM, true);
            header.addView(updated);
            TextView close = text(a, "  ✕  ", 18, CREAM, true);
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
            int n = last == null ? 0 : last.items.length;
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
            updated.setText("● LIVE  •  UPDATED " + DateFormat.getTimeFormat(a).format(new java.util.Date(r.updatedAt)));
            updated.setTextColor(CREAM);
            for (int i = 0; i < cards.size() && i < r.items.length; i++) cards.get(i).bind(r.items[i]);
            StringBuilder sb = new StringBuilder();
            sb.append("KOTs counted: Dine-in ").append(r.kotsByType[0]).append(" • Pick-up ").append(r.kotsByType[1])
                    .append(" • Delivery ").append(r.kotsByType[2]);
            if (r.kotsByType[3] > 0) sb.append(" • Other ").append(r.kotsByType[3]);
            sb.append("\n\nNot in BOM (not counted):");
            if (r.unmatched.isEmpty()) sb.append("\nnone");
            for (String u : r.unmatched) sb.append("\n• ").append(u);
            sb.append("\n\nProjection = typical for this weekday/hour (Petpooja + this tablet), scaled by the last hour.");
            if (coverage != null) coverage.setText(sb.toString());
            if (detail != null) detail.bind(r.items[detail.index]);
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
                LinearLayout top = new LinearLayout(c);
                top.setGravity(Gravity.CENTER_VERTICAL);
                name = text(c, "", 14, CREAM, true);
                name.setSingleLine(true);
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
                String arrow = Math.abs(diff) < 0.05 * Math.max(1, it.lastHour) ? "" : diff > 0 ? "  ▲" : "  ▼";
                next.setText("≈ " + kg(it.nextHour) + arrow);
                sub.setText("Last hr " + kg(it.lastHour) + "  •  Today " + kg(it.usedToday));
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

        void openDetail(int i) {
            if (detail != null) detail.close();
            detail = new Detail(a, i);
            if (last != null) detail.bind(last.items[i]);
        }

        /** Item detail: batch buttons, raw-ingredient breakdown for the next hour, top drivers. */
        final class Detail {
            final int index;
            final FrameLayout overlay;
            final TextView title, numbers, batch, breakdown, drivers;

            Detail(Context c, final int i) {
                index = i;
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
                TextView x = text(c, "  ✕  ", 18, CREAM, true);
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
                for (final double kgv : BATCH_CHIPS) {
                    TextView b = text(c, "+" + (int) kgv + " kg", 14, Color.BLACK, true);
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
                numbers.setText("Next 1 hr ≈ " + kg(it.nextHour) + "   •   Last 1 hr " + kg(it.lastHour)
                        + "   •   Used today " + kg(it.usedToday) + "   •   Typical next hr " + kg(it.typicalNextHour));
                if (it.made > 0) {
                    batch.setText("Made today " + kg(it.made) + "  •  Left ≈ " + kg(Math.max(0, it.remaining))
                            + (it.minutesLeft < 999 ? String.format(Locale.US, "  •  lasts ≈ %.0f min", it.minutesLeft) : ""));
                    batch.setTextColor(it.status >= 2 ? statusColor(it.status) : CREAM);
                } else {
                    batch.setText("No batch logged today — tap a size when a fresh batch is ready.");
                    batch.setTextColor(MUTED);
                }
                StringBuilder dr = new StringBuilder();
                for (String s : it.drivers) {
                    String[] p = s.split("\\|");
                    dr.append(String.format(Locale.US, "%3.0f%%  %s%n", Double.parseDouble(p[1]) * 100, p[0]));
                }
                drivers.setText(dr.length() == 0 ? "—" : dr.toString().trim());
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
