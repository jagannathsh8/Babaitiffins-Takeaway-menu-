package com.pp.kds.dosa;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * PREP LIVE report: pick any date (today live, the tablet's own 42 days, Petpooja seed days),
 * view prep items or dishes hour by hour, and export the date to Excel (.xlsx). Saving uses the
 * system "Save as" screen (Downloads, Drive, ...) - no storage permission needed - and then
 * offers to share the file (WhatsApp, e-mail, ...).
 */
public final class PrepReport {

    private static final int REQ_SAVE = 0x5A7E;
    private static byte[] pendingBytes;
    private static String pendingName;

    private PrepReport() {}

    static void open(final Activity a, final FrameLayout host) {
        final PrepStats stats = PrepLive.stats();
        if (stats == null) return;
        final float d = a.getResources().getDisplayMetrics().density;
        final FrameLayout overlay = new FrameLayout(a);
        overlay.setBackgroundColor(PrepLive.BG);
        overlay.setClickable(true);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (16 * d), (int) (10 * d), (int) (16 * d), (int) (10 * d));

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(PrepLive.text(a, "PREP REPORT", 20, PrepLive.GOLD, true));
        final TextView info = PrepLive.text(a, "", 12, PrepLive.MUTED, false);
        info.setPadding((int) (14 * d), 0, 0, 0);
        head.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final TextView export = PrepLive.text(a, "\u2B07 EXPORT EXCEL", 13, Color.BLACK, true);
        export.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        export.setBackground(PrepLive.box(PrepLive.LEAF, 0, 10 * d, 0));
        final TextView holidayBtn = PrepLive.text(a, "", 12, PrepLive.CREAM, true);
        holidayBtn.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        LinearLayout.LayoutParams hb = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hb.rightMargin = (int) (8 * d);
        head.addView(holidayBtn, hb);
        head.addView(export);
        TextView close = PrepLive.text(a, "   \u2715", 18, PrepLive.CREAM, true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                host.removeView(overlay);
            }
        });
        head.addView(close);
        col.addView(head);

        // date strip: today live, today + next 7 days forecast, then past days newest first
        long now0 = System.currentTimeMillis();
        final List<String> dates = new ArrayList<String>();      // "A:date" actual, "F:date" forecast
        List<String> fut = stats.forecastDates(now0, 7);
        dates.add("A:" + fut.get(0));
        for (String f : fut) dates.add("F:" + f);
        for (String pd : stats.availableDates(now0)) if (!pd.equals(fut.get(0))) dates.add("A:" + pd);
        HorizontalScrollView strip = new HorizontalScrollView(a);
        final LinearLayout chips = new LinearLayout(a);
        chips.setPadding(0, (int) (8 * d), 0, (int) (8 * d));
        strip.addView(chips);
        col.addView(strip);

        LinearLayout tabs = new LinearLayout(a);
        final TextView tabPrep = PrepLive.text(a, "PREP ITEMS (OP / CP)", 12, Color.BLACK, true);
        final TextView tabDish = PrepLive.text(a, "DISHES SOLD", 12, PrepLive.CREAM, true);
        for (TextView t : new TextView[]{tabPrep, tabDish}) {
            t.setPadding((int) (12 * d), (int) (5 * d), (int) (12 * d), (int) (5 * d));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (8 * d);
            tabs.addView(t, lp);
        }
        col.addView(tabs);

        final TextView table = PrepLive.text(a, "", 12, PrepLive.CREAM, false);
        table.setTypeface(Typeface.MONOSPACE);
        table.setPadding(0, (int) (8 * d), 0, 0);
        HorizontalScrollView hs = new HorizontalScrollView(a);
        hs.addView(table);
        ScrollView vs = new ScrollView(a);
        vs.addView(hs);
        col.addView(vs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        overlay.addView(col);
        overlay.setTag((Runnable) () -> host.removeView(overlay)); // remote BACK
        host.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        final String[] selected = {dates.get(1)}; // open on today's whole-day forecast
        final boolean[] dishesTab = {false};
        final List<TextView> chipViews = new ArrayList<TextView>();
        final Runnable render = new Runnable() {
            @Override public void run() {
                PrepStats.Day day = load(stats, selected[0]);
                boolean fc = selected[0].startsWith("F:");
                holidayBtn.setVisibility(fc ? View.VISIBLE : View.GONE);
                holidayBtn.setText(day.holiday ? "HOLIDAY \u2713" : "MARK HOLIDAY");
                holidayBtn.setTextColor(day.holiday ? Color.BLACK : PrepLive.CREAM);
                holidayBtn.setBackground(PrepLive.box(day.holiday ? PrepLive.AMBER : 0x22FFFFFF, 0x55FFFFFF, 10 * d, day.holiday ? 0 : 1 * d));
                info.setText(label(day.date) + "  \u2022  " + day.source + (day.holiday && !fc ? "  \u2022  holiday" : "")
                        + (fc ? "  \u2022  based on " + (day.sources.isEmpty() ? "no history" : android.text.TextUtils.join(", ", shortDates(day.sources))) : "")
                        + (day.kotsByType != null ? String.format(Locale.US, "  \u2022  KOTs: Dine-in %d, Pick-up %d, Delivery %d",
                        day.kotsByType[0], day.kotsByType[1], day.kotsByType[2]) : ""));
                table.setText(dishesTab[0] ? dishTable(day) : prepTable(stats.model(), day));
                for (int i = 0; i < chipViews.size(); i++) {
                    boolean sel = dates.get(i).equals(selected[0]);
                    boolean f = dates.get(i).startsWith("F:");
                    int on = f ? PrepLive.LEAF : PrepLive.GOLD;
                    chipViews.get(i).setTextColor(sel ? Color.BLACK : f ? PrepLive.LEAF : PrepLive.CREAM);
                    chipViews.get(i).setBackground(PrepLive.box(sel ? on : PrepLive.SURFACE, f ? PrepLive.LEAF : 0x44FFFFFF, 8 * d, sel ? 0 : 1 * d));
                }
                tabPrep.setTextColor(dishesTab[0] ? PrepLive.CREAM : Color.BLACK);
                tabPrep.setBackground(PrepLive.box(dishesTab[0] ? PrepLive.SURFACE : PrepLive.GOLD, 0x44FFFFFF, 8 * d, 1 * d));
                tabDish.setTextColor(dishesTab[0] ? Color.BLACK : PrepLive.CREAM);
                tabDish.setBackground(PrepLive.box(dishesTab[0] ? PrepLive.GOLD : PrepLive.SURFACE, 0x44FFFFFF, 8 * d, 1 * d));
            }
        };
        for (final String date : dates) {
            String dt = date.substring(2);
            String today = DosaStats.dayKey(System.currentTimeMillis());
            String txt = date.startsWith("F:") ? "\u25F7 " + (dt.equals(today) ? "Today forecast" : label(dt))
                    : dt.equals(today) ? "Today (live)" : label(dt);
            TextView c = PrepLive.text(a, txt, 12, PrepLive.CREAM, true);
            c.setPadding((int) (10 * d), (int) (6 * d), (int) (10 * d), (int) (6 * d));
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selected[0] = date;
                    render.run();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = (int) (6 * d);
            chips.addView(c, lp);
            chipViews.add(c);
        }
        tabPrep.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dishesTab[0] = false;
                render.run();
            }
        });
        tabDish.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dishesTab[0] = true;
                render.run();
            }
        });
        holidayBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String dt = selected[0].substring(2);
                long now = System.currentTimeMillis();
                stats.setHoliday(dt, !stats.holidayOn(dt, now), now);
                PrepLive.markDirty();
                render.run();
            }
        });
        export.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                exportDay(a, stats, selected[0]);
            }
        });
        render.run();
    }

    static PrepStats.Day load(PrepStats stats, String key) {
        long now = System.currentTimeMillis();
        String date = key.substring(2);
        return key.startsWith("F:") ? stats.forecast(date, now) : stats.day(date, now);
    }

    static List<String> shortDates(List<String> ds) {
        List<String> out = new ArrayList<String>();
        for (String x : ds) out.add(label(x));
        return out;
    }

    static String label(String date) {
        try {
            Date dt = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date);
            String today = DosaStats.dayKey(System.currentTimeMillis());
            return new SimpleDateFormat("EEE d MMM", Locale.US).format(dt);
        } catch (Exception e) {
            return date;
        }
    }

    private static final int FROM = 6, TO = 23;

    private static String header(String first, String unit, String total) {
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "%-34s %-4s %9s", first, unit, total));
        for (int h = FROM; h <= TO; h++) sb.append(String.format(Locale.US, " %6s", String.format(Locale.US, "%02d", h)));
        return sb.append('\n').toString();
    }

    private static String row(String name, String unit, double[] hrs) {
        String nm = name.length() > 34 ? name.substring(0, 33) + "\u2026" : name;
        StringBuilder sb = new StringBuilder(String.format(Locale.US, "%-34s %-4s %9s", nm, unit, num(XlsxWriter.total(hrs))));
        for (int h = FROM; h <= TO; h++) sb.append(String.format(Locale.US, " %6s", hrs[h] == 0 ? "\u00B7" : num(hrs[h])));
        return sb.append('\n').toString();
    }

    private static String num(double v) {
        return v >= 100 ? String.format(Locale.US, "%.0f", v) : v >= 10 ? String.format(Locale.US, "%.1f", v)
                : String.format(Locale.US, "%.2f", v);
    }

    static String prepTable(final PrepStats.Model m, final PrepStats.Day d) {
        List<Integer> order = new ArrayList<Integer>();
        for (int t = 0; t < m.targets.length; t++) if (t < d.prep.length && XlsxWriter.total(d.prep[t]) > 0) order.add(t);
        Collections.sort(order, new Comparator<Integer>() {
            @Override public int compare(Integer x, Integer y) {
                return Double.compare(XlsxWriter.total(d.prep[y]), XlsxWriter.total(d.prep[x]));
            }
        });
        StringBuilder sb = new StringBuilder(header("Item", "Unit", "Total"));
        for (int t : order) sb.append(row(m.targets[t], m.unit(t), d.prep[t]));
        if (order.isEmpty()) sb.append("\nNo usage recorded for this date.");
        return sb.toString();
    }

    static String dishTable(final PrepStats.Day d) {
        List<String> names = new ArrayList<String>(d.dishes.keySet());
        Collections.sort(names, new Comparator<String>() {
            @Override public int compare(String x, String y) {
                return Double.compare(XlsxWriter.total(d.dishes.get(y)), XlsxWriter.total(d.dishes.get(x)));
            }
        });
        StringBuilder sb = new StringBuilder(header("Dish", "", "Qty"));
        for (String n : names) sb.append(row(n, "", d.dishes.get(n)));
        if (names.isEmpty()) sb.append("\nNo dishes recorded for this date.");
        return sb.toString();
    }

    // ---- export ----------------------------------------------------------------------------

    static void exportDay(Activity a, PrepStats stats, String key) {
        try {
            PrepStats.Day day = load(stats, key);
            String date = key.substring(2) + (key.startsWith("F:") ? "_forecast" : "");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            XlsxWriter.dayReport(stats.model(), day,
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date())).write(bo);
            pendingBytes = bo.toByteArray();
            pendingName = "BabaiTiffins_Prep_" + date + ".xlsx";
            SaveFragment f = new SaveFragment();
            a.getFragmentManager().beginTransaction().add(f, "prep_export").commit();
            a.getFragmentManager().executePendingTransactions();
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            i.putExtra(Intent.EXTRA_TITLE, pendingName);
            f.startActivityForResult(i, REQ_SAVE);
        } catch (Throwable e) {
            Toast.makeText(a, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Headless fragment that receives the "Save as" result, writes the file and offers sharing. */
    public static final class SaveFragment extends Fragment {
        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            Activity a = getActivity();
            try {
                if (requestCode != REQ_SAVE || a == null) return;
                if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || pendingBytes == null) return;
                Uri uri = data.getData();
                OutputStream out = a.getContentResolver().openOutputStream(uri);
                out.write(pendingBytes);
                out.close();
                Toast.makeText(a, "Saved " + pendingName, Toast.LENGTH_LONG).show();
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.putExtra(Intent.EXTRA_SUBJECT, pendingName);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                a.startActivity(Intent.createChooser(share, "Share report"));
            } catch (Throwable e) {
                if (a != null) Toast.makeText(a, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show();
            } finally {
                pendingBytes = null;
                if (a != null) a.getFragmentManager().beginTransaction().remove(this).commitAllowingStateLoss();
            }
        }
    }
}
