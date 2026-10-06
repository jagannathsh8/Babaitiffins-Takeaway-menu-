package com.pp.kds.dosa;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * TV remote / D-pad support for everything this add-on puts on screen.
 *
 *  - Dosa Live and Order Ready (drawn screens) get every remote key directly.
 *  - Normal views (DOSA LIVE / PREP LIVE buttons, Prep Live, report, pickers) become focusable
 *    with a clear highlight, so arrows move between them and OK clicks (hold OK = long-press).
 *  - BACK closes the top-most add-on screen instead of leaving the KDS.
 *
 * Overlays register how they close with {@code view.setTag(Runnable)}.
 */
final class RemoteNav {

    private static final WeakHashMap<View, Boolean> prepared = new WeakHashMap<View, Boolean>();

    private RemoteNav() {}

    static boolean isRemoteKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    /** Called for remote keys before the KDS sees them. True = handled here. */
    static boolean dispatch(Activity a, KeyEvent e) {
        int k = e.getKeyCode();
        if (DosaLive.panelKey(e)) return true;
        ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
        if (content == null) return false;
        Runnable closer = topCloser(content);
        if (k == KeyEvent.KEYCODE_BACK) {
            if (closer == null) return false;
            if (e.getAction() == KeyEvent.ACTION_UP) closer.run();
            return true;
        }
        View scope = topOverlay(content);
        if (scope != null) {
            // One of our full-screen windows is open: keep the remote inside it.
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                prepare(scope);
                View focused = content.findFocus();
                List<View> roots = new ArrayList<View>();
                roots.add(scope);
                if (focused == null || !insideAny(focused, roots)) {
                    View first = firstFocusable(deepestOverlay(scope));
                    if (first == null) first = firstFocusable(scope);
                    if (first != null) {
                        first.requestFocus();
                        return true; // this press only puts the highlight on screen
                    }
                }
            }
            return false;
        }
        // On the KDS board / Petpooja settings: never take focus away from the KDS itself.
        List<View> ours = DosaLive.controlViews();
        for (View r : ours) prepare(r); // reachable by normal arrow navigation
        boolean menu = k == KeyEvent.KEYCODE_MENU;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
            View focused = content.findFocus();
            if (menu || (focused == null && k != KeyEvent.KEYCODE_ENTER && k != KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                // MENU = jump to DOSA LIVE; or nothing on screen has focus yet.
                DosaLive.onUserTouch();
                View first = firstFocusableIn(ours);
                if (first != null) {
                    first.requestFocus();
                    return true;
                }
            }
        }
        if (focusedIsOurs(content, ours)) DosaLive.onUserTouch(); // keep our buttons visible while used
        return menu;
    }

    private static boolean focusedIsOurs(ViewGroup content, List<View> ours) {
        View f = content.findFocus();
        return f != null && insideAny(f, ours);
    }

    /** Makes clickable views focusable with a visible focus highlight. */
    static void prepare(View v) {
        if (v == null) return;
        if ((v.hasOnClickListeners() || v.isLongClickable()) && !prepared.containsKey(v)) {
            prepared.put(v, Boolean.TRUE);
            v.setFocusable(true);
            final View.OnFocusChangeListener old = v.getOnFocusChangeListener();
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override public void onFocusChange(View view, boolean has) {
                    if (old != null) old.onFocusChange(view, has);
                    highlight(view, has);
                }
            });
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) prepare(g.getChildAt(i));
        }
    }

    private static void highlight(View v, boolean on) {
        v.animate().scaleX(on ? 1.08f : 1f).scaleY(on ? 1.08f : 1f).setDuration(120).start();
        if (Build.VERSION.SDK_INT >= 23) {
            if (on) {
                float d = v.getResources().getDisplayMetrics().density;
                GradientDrawable ring = new GradientDrawable();
                ring.setCornerRadius(14 * d);
                ring.setStroke((int) (3 * d), 0xFFFFFFFF);
                ring.setColor(0x22FFFFFF);
                v.setForeground(ring);
            } else {
                v.setForeground(null);
            }
        } else {
            v.setAlpha(on ? 1f : 0.85f);
        }
    }

    /** The add-on overlay on top of the KDS (Prep Live screen, report ...), or null. */
    private static View topOverlay(ViewGroup content) {
        for (int i = content.getChildCount() - 1; i >= 0; i--) {
            View c = content.getChildAt(i);
            if (c.getVisibility() != View.VISIBLE) continue;
            return c.getTag() instanceof Runnable ? c : null;
        }
        return null;
    }

    /** Innermost open overlay inside {@code v} (e.g. the item picker on the Prep Live screen). */
    private static View deepestOverlay(View v) {
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                View c = g.getChildAt(i);
                if (c.getVisibility() != View.VISIBLE) continue;
                View d = deepestOverlay(c);
                if (d != null) return d;
            }
        }
        return v.getTag() instanceof Runnable ? v : null;
    }

    private static Runnable topCloser(ViewGroup content) {
        View top = topOverlay(content);
        if (top == null) return null;
        View d = deepestOverlay(top);
        return (Runnable) (d != null ? d : top).getTag();
    }

    private static boolean insideAny(View v, List<View> roots) {
        for (View r : roots) {
            View p = v;
            while (p != null) {
                if (p == r) return true;
                p = p.getParent() instanceof View ? (View) p.getParent() : null;
            }
        }
        return false;
    }

    private static View firstFocusableIn(List<View> roots) {
        for (View r : roots) {
            View f = firstFocusable(r);
            if (f != null) return f;
        }
        return null;
    }

    private static View firstFocusable(View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;
        if (v.isFocusable() && (v.hasOnClickListeners() || v.isLongClickable())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = firstFocusable(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }
}
