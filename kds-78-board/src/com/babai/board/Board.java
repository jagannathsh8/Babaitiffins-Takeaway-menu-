package com.babai.board;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.pp.kds.core.common.model.OrderType;
import com.pp.kds.domain.model.Kot;
import com.pp.kds.domain.model.KotItem;
import com.pp.kds.feature.dashboard.DashboardUiState;
import com.pp.kds.feature.dashboard.DashboardViewModel;
import com.pp.kds.feature.dashboard.KotCard;
import com.pp.kds.feature.dashboard.ui.BoardVisualsKt;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The patch into the original Petpooja KDS 7.8: two calls added to Petpooja's code, nothing else.
 *
 * <pre>
 *   MainActivity.onCreate (end)        Board.install(this)   the DOSA BOARD button
 *   DashboardViewModel.&lt;init&gt; (end)  Board.attach(this)    the live KDS board, read only
 * </pre>
 *
 * The board shows dine in and take away KOTs with a dosa (category containing "dosa"), as the KDS
 * has them: nothing is marked or changed in Petpooja. Tap DOSA BOARD to open it full screen; Back
 * (or a long press on the board) closes it. A long press on the button makes the board open by
 * itself when the KDS starts (for a TV).
 */
public final class Board {

    private static volatile Object vm; // DashboardViewModel
    private static final BoardModel model = new BoardModel();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static BoardView.Look look;
    private static BoardView view;
    private static View button;
    private static boolean ticking;

    private Board() {
    }

    /** From DashboardViewModel's constructor: the board the KDS shows. */
    public static void attach(Object dashboardViewModel) {
        vm = dashboardViewModel;
    }

    /** From MainActivity.onCreate: the DOSA BOARD button, and the board if it opens by itself. */
    public static void install(final Activity a) {
        try {
            look = loadLook(a);
            final FrameLayout root = (FrameLayout) a.findViewById(android.R.id.content);
            if (root == null) return;
            addButton(a, root);
            if (!ticking) {
                ticking = true;
                main.post(tick);
            }
            if (prefs(a).getBoolean("auto", false)) {
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        open(a, root);
                    }
                }, 1500);
            }
        } catch (Throwable ignored) {
            // never break the KDS
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("babai_board", Context.MODE_PRIVATE);
    }

    /** Every second: the KDS board -> the Dosa Board (also while closed, so "just ready" is right). */
    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                List<BoardModel.Order> orders = read();
                if (orders != null) {
                    long now = System.currentTimeMillis();
                    model.update(orders, now);
                    if (view != null) view.show(model, now);
                }
            } catch (Throwable ignored) {
            }
            main.postDelayed(this, 1000);
        }
    };

    static List<BoardModel.Order> read() {
        Object v = vm;
        if (!(v instanceof DashboardViewModel)) return null;
        DashboardUiState st = ((DashboardViewModel) v).getState().getValue();
        if (st == null || st.getCards() == null) return null;
        List<BoardModel.Order> out = new ArrayList<BoardModel.Order>();
        for (KotCard card : st.getCards()) {
            Kot k = card.getKot();
            if (k == null) continue;
            OrderType ot = OrderType.Companion.fromId(k.getOrderType());
            if (ot != OrderType.DINE_IN && ot != OrderType.TAKE_AWAY) continue;
            List<BoardModel.Item> items = new ArrayList<BoardModel.Item>();
            boolean allReady = true;
            List<KotItem> its = k.getItems();
            if (its != null) {
                for (KotItem it : its) {
                    String cat = it.getCategory();
                    if (cat == null || !cat.toLowerCase(Locale.US).contains("dosa") || BoardVisualsKt.isItemCancelled(it.getStatus())) continue;
                    boolean ready = BoardVisualsKt.isItemReady(it.getStatus());
                    String n = it.getName();
                    Double q = it.getQuantity();
                    items.add(new BoardModel.Item(n == null || n.trim().isEmpty() ? "Dosa" : n.trim(), q == null || q <= 0 ? 1 : q, ready));
                    allReady &= ready;
                }
            }
            if (items.isEmpty()) continue;
            String ks = k.getKotStatus() == null ? "" : k.getKotStatus().trim();
            boolean gone = "10".equals(ks) || "0".equals(ks) || card.getState() != null && card.getState().isDispatch();
            boolean ready = allReady || "9".equals(ks);
            Long created = BoardVisualsKt.parseCreatedMillis(k.getCreatedTime());
            Long tok = k.getTokenNo();
            long id = k.getId() == null ? 0 : k.getId();
            String token = tok != null && tok > 0 ? String.valueOf(tok) : String.valueOf(id % 10000);
            out.add(new BoardModel.Order(id, token, created == null ? 0 : created, ready, gone, items));
        }
        return out;
    }

    // ---- the button and the full-screen board ---------------------------------------------------

    private static void addButton(final Activity a, final FrameLayout root) {
        final float d = a.getResources().getDisplayMetrics().density;
        LinearLayout pill = new LinearLayout(a);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding((int) (8 * d), (int) (6 * d), (int) (14 * d), (int) (6 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BoardView.GREEN);
        bg.setCornerRadius(999);
        bg.setStroke((int) (2 * d), BoardView.YELLOW);
        pill.setBackground(bg);
        pill.setElevation(10 * d);
        if (look.spinner != null) {
            ImageView ic = new ImageView(a);
            ic.setImageBitmap(look.spinner);
            pill.addView(ic, new LinearLayout.LayoutParams((int) (22 * d), (int) (22 * d)));
        }
        TextView t = new TextView(a);
        t.setText("DOSA BOARD");
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        t.setLetterSpacing(.08f);
        t.setTypeface(look.fig800);
        t.setPadding((int) (6 * d), 0, 0, 0);
        pill.addView(t);
        pill.setContentDescription("Open the Dosa Board");
        final SharedPreferences p = prefs(a);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, (int) (16 * d), (int) (16 * d));
        root.addView(pill, lp);
        pill.setTranslationX(p.getFloat("bx", 0));
        pill.setTranslationY(p.getFloat("by", 0));
        button = pill;
        // tap: open; long press: open by itself at start (on/off); drag: move it out of the way
        final int slop = ViewConfiguration.get(a).getScaledTouchSlop();
        pill.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy, tx, ty;
            boolean drag;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        tx = v.getTranslationX();
                        ty = v.getTranslationY();
                        drag = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        if (!drag && Math.hypot(e.getRawX() - sx, e.getRawY() - sy) > slop * 2) {
                            drag = true;
                            v.cancelLongPress();
                        }
                        if (drag) {
                            v.setTranslationX(tx + e.getRawX() - sx);
                            v.setTranslationY(ty + e.getRawY() - sy);
                        }
                        return drag;
                    case MotionEvent.ACTION_UP:
                        if (drag) {
                            p.edit().putFloat("bx", v.getTranslationX()).putFloat("by", v.getTranslationY()).apply();
                            return true;
                        }
                        return false;
                }
                return false;
            }
        });
        pill.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                open(a, root);
            }
        });
        pill.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                boolean on = !p.getBoolean("auto", false);
                p.edit().putBoolean("auto", on).apply();
                Toast.makeText(a, on ? "Dosa Board will open by itself when the KDS starts" : "Dosa Board: opens only with the button",
                        Toast.LENGTH_LONG).show();
                return true;
            }
        });
    }

    static void open(final Activity a, final FrameLayout root) {
        if (view != null || a.isFinishing()) return;
        final BoardView v = new BoardView(a, look);
        v.show(model, System.currentTimeMillis());
        v.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View vv, int code, KeyEvent e) {
                if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                    if (e.getAction() == KeyEvent.ACTION_UP) close(root);
                    return true;
                }
                return false;
            }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View vv) {
                close(root);
                return true;
            }
        });
        v.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View vv) {
                vv.requestFocus();
            }
        });
        root.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view = v;
        if (button != null) button.setVisibility(View.GONE);
        v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        v.requestFocus();
    }

    static void close(FrameLayout root) {
        if (view == null) return;
        root.removeView(view);
        view = null;
        if (button != null) button.setVisibility(View.VISIBLE);
    }

    // ---- the page's fonts and pictures (assets/babai_board) ---------------------------------------

    private static BoardView.Look loadLook(Context c) {
        if (look != null) return look;
        BoardView.Look l = new BoardView.Look();
        l.mont800 = tf(c, "montserrat-800.ttf", Typeface.DEFAULT_BOLD);
        l.mont900 = tf(c, "montserrat-900.ttf", Typeface.DEFAULT_BOLD);
        l.fig600 = tf(c, "figtree-600.ttf", Typeface.DEFAULT);
        l.fig700 = tf(c, "figtree-700.ttf", Typeface.DEFAULT_BOLD);
        l.fig800 = tf(c, "figtree-800.ttf", Typeface.DEFAULT_BOLD);
        l.mascot = png(c, "mascot.png");
        l.spinner = png(c, "spinner.png");
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
