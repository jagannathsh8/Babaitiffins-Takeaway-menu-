package com.babai.screens;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/**
 * Babai Screens: one app for every screen at the outlet. The Babai Console on the shop Wi-Fi serves
 * the screens (rider TV, dosa order status, pick-up TV, kitchen, dosa barker) and does all the
 * work; this app finds the Console, shows the chosen screen full screen, speaks its calls, starts on
 * power-on, reconnects by itself and updates itself.
 *
 * Settings (choose another screen): MENU on the remote, Back 5 times, or tap the top-left corner 5
 * times. Saving asks for the Console PIN, so a screen is not changed by accident.
 */
public class MainActivity extends Activity {
    static final String PREFS = "babai_screens";
    static final String[][] SCREENS = {
            {"rider", "Rider TV", "Online orders by last 4 digits and slot, ready first, voice calls"},
            {"dosa", "Dosa board TV", "Babai Dosa Board: ready for pickup, preparing, est. prep time, calls"},
            {"dosaweb", "Dosa order status TV (classic)", "The earlier dine-in dosa screen, from the Console"},
            {"pickup", "Pick-up TV", "Pick-up tokens with their dishes, waiting and ready"},
            {"kitchen", "Kitchen screen", "Tick dishes, Food ready, dispatch (set up with the gear)"},
            {"barker", "Dosa barker", "Tawa by tawa for the dosa master, tap or foot pedal"},
    };
    private static final int YELLOW = 0xFFF9BC21, BLACK = 0xFF171717, CREAM = 0xFFFFF6DF, GREEN = 0xFF568B60, RED = 0xFFB91C1C;

    private SharedPreferences P;
    private DosaBoard board; // the Dosa screen: drawn by the app (1.6)
    private final Handler h = new Handler(Looper.getMainLooper());

    // Fit every screen page to this TV / phone (1.5): the pages ask for width=device-width but let
    // the web view zoom out when a box is wider than the screen, so the dosa and pick-up boards came
    // out cropped or small (only the rider page locked the zoom). The app locks the zoom on every
    // page, hides sideways overflow and stretches the 16:9 board to the screen's shape (the letters
    // keep their size; no black bars on a phone). The pages on the Console are not changed.
    private static final String FIT_JS =
        "(function(){try{" +
        "var c='width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=1,user-scalable=no';" +
        "var m=document.querySelector('meta[name=viewport]');if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
        "if(m.getAttribute('content')!==c)m.setAttribute('content',c);" +
        "if(!document.getElementById('babaiFit')){var s=document.createElement('style');s.id='babaiFit';s.textContent='html,body{overflow-x:hidden!important;max-width:100%}html.babai-tv,html.babai-tv body{overflow:hidden!important;width:100%;height:100%}';(document.head||document.documentElement).appendChild(s);}" +
        "function fill(){var tv=document.querySelectorAll('.tv');if(!tv.length)return;var d=document.documentElement;if(!/babai-tv/.test(d.className))d.className+=' babai-tv';" +
        "var w=window.innerWidth,h=window.innerHeight,rem=parseFloat(d.style.fontSize||getComputedStyle(d).fontSize)||19.2;if(!w||!h)return;" +
        "var a=w/h,bw=100,bh=56.25;if(a>16/9)bw=bh*a;else bh=bw/a;var k=Math.min(w/(bw*rem),h/(bh*rem));" +
        "for(var i=0;i<tv.length;i++){var t='translate(-50%,-50%) scale('+k.toFixed(4)+')';tv[i].style.width=bw.toFixed(3)+'rem';tv[i].style.height=bh.toFixed(3)+'rem';tv[i].style.webkitTransform=t;tv[i].style.transform=t;}window.scrollTo(0,0);}" +
        "if(!window.__babaiFit){window.__babaiFit=1;window.addEventListener('resize',function(){setTimeout(fill,0);});}" +
        "var e;try{e=new Event('resize');}catch(x){e=document.createEvent('Event');e.initEvent('resize',true,true);}window.dispatchEvent(e);fill();" +
        "}catch(x){}})();";

    private void fit() {
        if (web == null || inSetup) return;
        try { web.evaluateJavascript(FIT_JS, null); } catch (Throwable t) { }
    }

    private void fitSoon() {
        fit();
        h.postDelayed(new Runnable() { @Override public void run() { fit(); } }, 400);
        h.postDelayed(new Runnable() { @Override public void run() { fit(); } }, 1500);
        h.postDelayed(new Runnable() { @Override public void run() { fit(); } }, 4000);
    }
    private FrameLayout root;
    private WebView web;
    private TextView overlay;
    private Voice voice;
    private boolean inSetup, pageError;
    private int fails;
    private long[] backs = new long[5];
    private int backI;
    private long[] taps = new long[5];
    private int tapI;
    private final Runnable retry = new Runnable() { @Override public void run() { loadScreen(); } };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        P = getSharedPreferences(PREFS, 0);
        if (P.getString("dev", "").isEmpty()) P.edit().putString("dev", "tv-" + UUID.randomUUID().toString().substring(0, 8)).apply();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        voice = new Voice(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        boolean ppBoard = "dosa".equals(P.getString("screen", "")) && !P.getString("ppHost", "").isEmpty();
        if ((P.getString("console", "").isEmpty() && !ppBoard) || P.getString("screen", "").isEmpty()) showSetup(false);
        else showScreen();
        h.postDelayed(updateLoop, 20_000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        if (web != null) web.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        voice.shutdown();
        if (web != null) web.destroy();
        if (board != null) board.stop();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) immersive();
    }

    private void immersive() {
        if (inSetup) return;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // ---- the screen ------------------------------------------------------------------------------

    private String base() { return Net.base(P.getString("console", "")); }

    private String screenUrl() {
        String scr = P.getString("screen", "rider");
        String q;
        try {
            q = "dev=" + URLEncoder.encode(P.getString("dev", ""), "UTF-8") + "&app=" + Version.NAME + "&name=" + URLEncoder.encode(P.getString("name", Build.MODEL), "UTF-8");
        } catch (Exception e) {
            q = "";
        }
        if (scr.equals("barker")) {
            int t = P.getInt("tawa", 0);
            return base() + "/barker?" + (t > 0 ? "tawa=" + t + "&" : "") + q;
        }
        if (scr.equals("dosaweb")) scr = "dosa"; // the classic page on the Console
        return base() + "/screens/" + scr + "?" + q;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void showScreen() {
        inSetup = false;
        if (board != null) { board.stop(); board = null; }
        if ("dosa".equals(P.getString("screen", ""))) { showDosaBoard(); return; }
        root.removeAllViews();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (web != null) web.destroy();
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadWithOverviewMode(false); // never zoomed out to show a too-wide page (1.5)
        s.setUseWideViewPort(true);
        s.setTextZoom(100); // the TV's system font size must not zoom the screen past its edges
        // no minimum font size (Android's web view enlarges any text under 8 px, which pushed small
        // labels on phones and TV boxes past their boxes) and no pinch zoom
        s.setMinimumFontSize(1);
        s.setMinimumLogicalFontSize(1);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        web.addJavascriptInterface(new Bridge(), "BabaiApp");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) { pageError = false; }

            @Override public void onReceivedError(WebView v, int code, String desc, String url) {
                if (url != null && url.equals(v.getUrl()) || url != null && url.startsWith(base())) failed(desc);
            }

            @Override public void onPageFinished(WebView v, String url) {
                if (!pageError) { fails = 0; overlay.setVisibility(View.GONE); }
                fitSoon();
            }

            @Override public void onPageCommitVisible(WebView v, String url) { fit(); }

            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return !url.startsWith(base()); } // stays on the Console

            @Override public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                h.post(new Runnable() { @Override public void run() { showScreen(); } }); // the page crashed: start it again
                return true;
            }
        });
        web.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN && e.getX() < dp(70) && e.getY() < dp(70)) {
                    taps[tapI++ % 5] = System.currentTimeMillis();
                    if (fiveWithin(taps, 3000)) { taps = new long[5]; showSetup(true); return true; }
                }
                return false;
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        web.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { // turned, resized, system bars hidden
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) fitSoon();
            }
        });
        overlay = new TextView(this);
        overlay.setBackgroundColor(0xEE121212);
        overlay.setTextColor(CREAM);
        overlay.setGravity(Gravity.CENTER);
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        overlay.setPadding(dp(40), dp(40), dp(40), dp(40));
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        immersive();
        loadScreen();
    }

    /** The Dosa screen: the Babai Dosa Board, drawn by the app from the Console's kitchen feed. */
    private void showDosaBoard() {
        root.removeAllViews();
        if (web != null) { web.destroy(); web = null; }
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        String q;
        try {
            q = "dev=" + URLEncoder.encode(P.getString("dev", ""), "UTF-8") + "&app=" + Version.NAME + "&name=" + URLEncoder.encode(P.getString("name", Build.MODEL), "UTF-8");
        } catch (Exception e) {
            q = "";
        }
        board = new DosaBoard(this, P, new DosaBoard.Host() {
            @Override public void boardOk() { fails = 0; pageError = false; overlay.setVisibility(View.GONE); }
            @Override public void boardFailed(String why) { if (!inSetup) failed(why); }
            @Override public void say(String text, String wav) { voice.say(text, wav, true); }
        }, base(), q);
        board.view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN && e.getX() < dp(70) && e.getY() < dp(70)) {
                    taps[tapI++ % 5] = System.currentTimeMillis();
                    if (fiveWithin(taps, 3000)) { taps = new long[5]; showSetup(true); return true; }
                }
                return true;
            }
        });
        root.addView(board.view, new FrameLayout.LayoutParams(-1, -1));
        overlay = new TextView(this);
        overlay.setBackgroundColor(0xEE121212);
        overlay.setTextColor(CREAM);
        overlay.setGravity(Gravity.CENTER);
        overlay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        overlay.setPadding(dp(40), dp(40), dp(40), dp(40));
        overlay.setVisibility(View.GONE);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        immersive();
        board.start();
    }

    private void loadScreen() {
        if (inSetup || web == null) return;
        h.removeCallbacks(retry);
        web.loadUrl(screenUrl());
    }

    private void failed(String why) {
        pageError = true;
        fails++;
        if (board != null) {
            // the Dosa board keeps its last picture through a short blip (3 reads, about 5 s)
            String pp = P.getString("ppHost", ""), con = P.getString("console", "");
            overlay.setText("Babai Screens\n\nReading the orders from " + (pp.isEmpty() ? "" : "Petpooja (" + pp + ")") + (!pp.isEmpty() && !con.isEmpty() ? " or " : "")
                    + (con.isEmpty() ? "" : "the Babai Console (" + con + ")") + "…\n\nCheck that the billing PC is on and this TV is on the shop Wi-Fi.\n(" + why + ")\n\nMENU or Back ×5: settings");
            if (fails >= 3) overlay.setVisibility(View.VISIBLE);
            if (fails % 6 == 0 && !con.isEmpty()) rediscover();
            return;
        }
        overlay.setText("Babai Screens\n\nReconnecting to the Babai Console at " + P.getString("console", "") + "…\n\n"
                + "Check that the Console PC is on and this TV is on the shop Wi-Fi.\n(" + why + ")\n\nMENU or Back ×5: settings");
        overlay.setVisibility(View.VISIBLE);
        h.removeCallbacks(retry);
        h.postDelayed(retry, Math.min(30_000, 3_000L * fails));
        if (fails % 6 == 0) rediscover(); // the PC may have a new address
    }

    /** The Console moved to another address (DHCP): look for it on the Wi-Fi. */
    private void rediscover() {
        new Thread(new Runnable() {
            @Override public void run() {
                if (Net.isConsole(P.getString("console", ""), 2000)) return;
                List<String> found = Net.findConsoles();
                if (found.size() == 1) {
                    P.edit().putString("console", found.get(0)).apply();
                    h.post(retry);
                }
            }
        }).start();
    }

    private final Runnable updateLoop = new Runnable() {
        @Override public void run() {
            new Thread(new Runnable() {
                @Override public void run() {
                    Updater.check(MainActivity.this, base());
                    int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                    if (Updater.hasUpdate() && !inSetup && (hour >= 3 && hour < 6 || !P.getBoolean("updAsked", false))) {
                        P.edit().putBoolean("updAsked", true).apply();
                        h.post(new Runnable() { @Override public void run() { Updater.install(MainActivity.this); } });
                    }
                }
            }).start();
            h.postDelayed(this, 30 * 60_000);
        }
    };

    // ---- keys: the remote's Back goes to the page (closes its search / settings); 5 × Back or MENU
    // opens the app's settings.
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (inSetup || (web == null && board == null)) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS) {
            if (e.getAction() == KeyEvent.ACTION_UP) showSetup(true);
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() != KeyEvent.ACTION_UP) return true;
            backs[backI++ % 5] = System.currentTimeMillis();
            if (fiveWithin(backs, 4000)) { backs = new long[5]; showSetup(true); return true; }
            if (web != null) web.evaluateJavascript("window.babaiBack&&window.babaiBack()", new ValueCallback<String>() { @Override public void onReceiveValue(String v) { } });
            return true; // never leaves the screen by accident
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean fiveWithin(long[] t, long ms) {
        long min = Long.MAX_VALUE, max = 0;
        for (long x : t) { if (x == 0) return false; min = Math.min(min, x); max = Math.max(max, x); }
        return max - min <= ms;
    }

    /** What the pages can call: window.BabaiApp. */
    final class Bridge {
        @JavascriptInterface public void speak(String text, String wav, boolean chime) { voice.say(text, wav, chime); }
        @JavascriptInterface public boolean speaking() { return voice.speaking(); }
        @JavascriptInterface public String key() { return P.getString("key", ""); }
        @JavascriptInterface public void setKey(String k) { P.edit().putString("key", k).apply(); }
        @JavascriptInterface public String version() { return Version.NAME; }
    }

    // ---- setup ----------------------------------------------------------------------------------

    private String pickScreen;
    private int pickTawa;
    private LinearLayout tawaRow, consoleList;
    private TextView findMsg, msg;
    private EditText hostIn, nameIn, pinIn;
    private final List<Button> screenBtns = new ArrayList<Button>(), tawaBtns = new ArrayList<Button>();

    private void showSetup(boolean again) {
        inSetup = true;
        h.removeCallbacks(retry);
        if (web != null) { web.destroy(); web = null; }
        if (board != null) { board.stop(); board = null; }
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root.removeAllViews();
        pickScreen = P.getString("screen", "");
        pickTawa = P.getInt("tawa", 0);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(CREAM);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(16), dp(24), dp(40));
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));

        TextView top = text("Babai Screens", 28, BLACK, true);
        top.setBackgroundColor(YELLOW);
        top.setPadding(dp(16), dp(14), dp(16), dp(14));
        col.addView(top, lp(-1, -2, 0));
        col.addView(text("One app for every screen at the outlet. It shows what the Babai Console sends. Version " + Version.NAME
                + (Updater.status.isEmpty() ? "" : " · update: " + Updater.status), 15, 0xFF444444, false), lp(-1, -2, 10));

        // 1. the Console
        col.addView(head("1. Babai Console on this Wi-Fi"), lp(-1, -2, 18));
        findMsg = text("Looking for the Console…", 16, 0xFF444444, false);
        col.addView(findMsg, lp(-1, -2, 6));
        consoleList = new LinearLayout(this);
        consoleList.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(consoleList, lp(-1, -2, 6));
        LinearLayout hostRow = new LinearLayout(this);
        hostIn = edit("Console PC address, e.g. 192.168.1.5", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        hostIn.setText(P.getString("console", ""));
        hostRow.addView(hostIn, new LinearLayout.LayoutParams(0, -2, 1));
        Button test = button("Check", false);
        test.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { checkHost(hostIn.getText().toString()); } });
        hostRow.addView(test, new LinearLayout.LayoutParams(-2, -2));
        col.addView(hostRow, lp(-1, -2, 6));

        // 2. the screen
        col.addView(head("2. What this screen shows"), lp(-1, -2, 18));
        screenBtns.clear();
        for (final String[] s : SCREENS) {
            Button bt = button(s[1] + "\n" + s[2], false);
            bt.setTag(s[0]);
            bt.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            bt.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickScreen = s[0]; paintPicks(); } });
            screenBtns.add(bt);
            col.addView(bt, lp(-1, -2, 6));
        }
        tawaRow = new LinearLayout(this);
        tawaRow.setOrientation(LinearLayout.HORIZONTAL);
        tawaBtns.clear();
        for (int t = 0; t <= 6; t++) {
            final int tt = t;
            Button bt = button(t == 0 ? "All tawas" : "Tawa " + t, false);
            bt.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickTawa = tt; paintPicks(); } });
            tawaBtns.add(bt);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, t == 0 ? 1.6f : 1);
            l.setMargins(0, 0, dp(6), 0);
            tawaRow.addView(bt, l);
        }
        col.addView(tawaRow, lp(-1, -2, 6));
        dosaBox = new LinearLayout(this);
        dosaBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(dosaBox, lp(-1, -2, 6));
        pickKinds = P.getString("dosaKinds", "IP");
        pickChans = new ArrayList<String>(DosaBoard.chans(P));
        pickVoice = P.getBoolean("dosaVoice", true);
        pickPP = P.getString("ppHost", "");
        pickClear = P.getInt("dosaClearMin", 10);
        ppMsg = "";

        // 3. name, PIN, start
        col.addView(head("3. Name of this screen (Console PIN 1234 is filled in)"), lp(-1, -2, 18));
        nameIn = edit("Name of this screen, e.g. Rider TV door", InputType.TYPE_CLASS_TEXT);
        nameIn.setText(P.getString("name", Build.MODEL));
        col.addView(nameIn, lp(-1, -2, 6));
        pinIn = edit("Console PIN", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinIn.setText("1234"); // the same PIN on every Console and screen
        col.addView(pinIn, lp(-1, -2, 6));
        msg = text("", 16, RED, true);
        col.addView(msg, lp(-1, -2, 6));
        LinearLayout acts = new LinearLayout(this);
        Button start = button("Start", true);
        start.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
        acts.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        if (again) {
            Button cancel = button("Back to the screen", false);
            cancel.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showScreen(); } });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1);
            l.setMargins(dp(8), 0, 0, 0);
            acts.addView(cancel, l);
        }
        col.addView(acts, lp(-1, -2, 10));

        // power-on
        col.addView(head("On power-on"), lp(-1, -2, 22));
        col.addView(text("The screen opens by itself when the TV starts. Some TVs need one of these:", 15, 0xFF444444, false), lp(-1, -2, 4));
        if (Build.VERSION.SDK_INT >= 23) {
            Button ov = button(Settings.canDrawOverlays(this) ? "✓ Allowed to open on power-on" : "Allow opening on power-on", false);
            ov.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); } catch (Throwable t) { msg.setText("This TV has no such setting; use the home-app option below."); }
                }
            });
            col.addView(ov, lp(-1, -2, 6));
        }
        final ComponentName home = new ComponentName(this, getPackageName() + ".Home");
        final boolean isHome = getPackageManager().getComponentEnabledSetting(home) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        Button hb = button(isHome ? "✓ Kiosk mode on: Babai Screens is the home app (press to turn off)" : "Kiosk mode: make Babai Screens the TV's home app", false);
        hb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getPackageManager().setComponentEnabledSetting(home, isHome ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                if (!isHome) { try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); } catch (Throwable t) { msg.setText("Press the remote's Home key and choose Babai Screens, Always."); } }
                showSetup(true);
            }
        });
        col.addView(hb, lp(-1, -2, 6));
        if (Updater.hasUpdate()) {
            Button up = button("Install update (build " + Updater.readyBuild + ")", false);
            up.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { Updater.install(MainActivity.this); } });
            col.addView(up, lp(-1, -2, 10));
        }
        paintPicks();
        (screenBtns.isEmpty() ? start : screenBtns.get(0)).requestFocus();
        if (hostIn.getText().length() == 0 || !again) find();
    }

    private void paintPicks() {
        for (Button b : screenBtns) style(b, pickScreen.equals(b.getTag()));
        for (int i = 0; i < tawaBtns.size(); i++) style(tawaBtns.get(i), i == pickTawa);
        tawaRow.setVisibility("barker".equals(pickScreen) ? View.VISIBLE : View.GONE);
        paintDosa();
    }

    // ---- the Dosa board's choice: which orders' dosas it shows (saved on this TV) ---------------------

    private LinearLayout dosaBox;
    private String pickKinds = "IP";
    private List<String> pickChans = new ArrayList<String>();
    private boolean pickVoice = true;
    private String pickPP = null, ppMsg = "";
    private int pickClear = 10;

    private void paintDosa() {
        if (dosaBox == null) return;
        dosaBox.removeAllViews();
        dosaBox.setVisibility("dosa".equals(pickScreen) ? View.VISIBLE : View.GONE);
        if (!"dosa".equals(pickScreen)) return;
        dosaBox.addView(text("Dosas on this board (tap to turn on or off):", 16, BLACK, true), lp(-1, -2, 4));
        LinearLayout row = new LinearLayout(this);
        String[][] kinds = {{"I", "Dine in"}, {"P", "Pick up"}, {"S", "Self service"}, {"D", "Online"}};
        for (final String[] k : kinds) {
            boolean on = pickKinds.contains(k[0]);
            String label = k[1];
            if (k[0].equals("D") && on) label += pickChans.isEmpty() ? " · all channels" : " · " + pickChans.size() + " channel" + (pickChans.size() > 1 ? "s" : "");
            Button b = button((on ? "✓ " : "") + label, false);
            style(b, on);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pickKinds = pickKinds.contains(k[0]) ? pickKinds.replace(k[0], "") : pickKinds + k[0];
                    if (pickKinds.isEmpty()) pickKinds = "I"; // never an empty board
                    paintDosa();
                    focusTag("k" + k[0]);
                }
            });
            b.setTag("k" + k[0]);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1);
            l.setMargins(0, 0, dp(6), 0);
            row.addView(b, l);
        }
        dosaBox.addView(row, lp(-1, -2, 4));
        if (pickKinds.contains("D")) {
            List<String> all = DosaBoard.seenChans(P);
            for (String c : pickChans) if (!all.contains(c)) all.add(c);
            LinearLayout cr = new LinearLayout(this);
            List<String> opts = new ArrayList<String>();
            opts.add("");
            opts.addAll(all);
            for (final String c : opts) {
                boolean on = c.isEmpty() ? pickChans.isEmpty() : pickChans.contains(c);
                Button b = button((on ? "✓ " : "") + (c.isEmpty() ? "All channels" : c), false);
                style(b, on);
                b.setTag("c" + c);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (c.isEmpty()) pickChans.clear();
                        else if (!pickChans.remove(c)) pickChans.add(c);
                        paintDosa();
                        focusTag("c" + c);
                    }
                });
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
                l.setMargins(0, 0, dp(6), 0);
                cr.addView(b, l);
            }
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.addView(cr);
            dosaBox.addView(hs, lp(-1, -2, 4));
            if (all.isEmpty()) dosaBox.addView(text("Online channels (Swiggy, Zomato…) show here once their first order has been on the board's Console.", 14, 0xFF666666, false), lp(-1, -2, 2));
        }
        // how long a ready token stays (when it isn't dispatched in Petpooja sooner)
        dosaBox.addView(text("Ready tokens stay on the board for (they leave sooner when dispatched in Petpooja):", 16, BLACK, true), lp(-1, -2, 10));
        LinearLayout cl = new LinearLayout(this);
        for (final int m : new int[]{5, 10, 15, 30}) {
            Button b = button((m == pickClear ? "✓ " : "") + m + " min", false);
            style(b, m == pickClear);
            b.setTag("t" + m);
            b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickClear = m; paintDosa(); focusTag("t" + m); } });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1);
            l.setMargins(0, 0, dp(6), 0);
            cl.addView(b, l);
        }
        dosaBox.addView(cl, lp(-1, -2, 4));

        // Petpooja's billing PC: the board reads it directly, like the KDS app (no Console needed)
        dosaBox.addView(text("Petpooja billing PC (the board reads orders from it like the Petpooja KDS, also when the Console PC is off):", 16, BLACK, true), lp(-1, -2, 10));
        LinearLayout pr = new LinearLayout(this);
        final EditText ppIn = edit("Petpooja billing PC address, e.g. 192.168.0.10", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ppIn.setText(pickPP == null ? "" : pickPP);
        ppIn.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            @Override public void onTextChanged(CharSequence a, int b, int c, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) { pickPP = e.toString().trim(); }
        });
        pr.addView(ppIn, new LinearLayout.LayoutParams(0, -2, 1));
        Button fb = button("Find", false);
        fb.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { findPetpooja(); } });
        pr.addView(fb, new LinearLayout.LayoutParams(-2, -2));
        dosaBox.addView(pr, lp(-1, -2, 4));
        if (!ppMsg.isEmpty()) dosaBox.addView(text(ppMsg, 15, 0xFF444444, false), lp(-1, -2, 2));
        Button vb = button(pickVoice ? "✓ Voice calls: on" : "Voice calls: off", false);
        style(vb, pickVoice);
        vb.setTag("voice");
        vb.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickVoice = !pickVoice; paintDosa(); focusTag("voice"); } });
        dosaBox.addView(vb, lp(-2, -2, 6));
    }

    /** Looks for the Petpooja billing PC on this Wi-Fi (port 3000, as the KDS app). */
    private void findPetpooja() {
        ppMsg = "Looking for the Petpooja billing PC on this Wi-Fi…";
        paintDosa();
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> f = Petpooja.find();
                h.post(new Runnable() {
                    @Override public void run() {
                        if (f.isEmpty()) ppMsg = "Not found. Is Petpooja open on the billing PC, and this TV on the shop Wi-Fi? You can type its address.";
                        else { pickPP = f.get(0); ppMsg = "Found Petpooja at " + f.get(0) + (f.size() > 1 ? " (also " + f.subList(1, f.size()) + ")" : "") + "."; }
                        if (!inSetup) return;
                        paintDosa();
                    }
                });
            }
        }).start();
    }

    private void focusTag(String tag) {
        View v = dosaBox.findViewWithTag(tag);
        if (v != null) v.requestFocus();
    }

    private void find() {
        findMsg.setText("Looking for the Babai Console on this Wi-Fi…");
        new Thread(new Runnable() {
            @Override public void run() {
                final List<String> found = Net.findConsoles();
                h.post(new Runnable() {
                    @Override public void run() {
                        if (!inSetup) return;
                        consoleList.removeAllViews();
                        if (found.isEmpty()) { findMsg.setText("No Console found automatically. Type the Console PC's address below and press Check (the Console shows it on Bridge Print › Screens)."); return; }
                        findMsg.setText(found.size() == 1 ? "Found the Console:" : "Found " + found.size() + " Consoles; pick this outlet's:");
                        for (final String ip : found) {
                            Button b = button(ip, ip.equals(hostIn.getText().toString()));
                            b.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { hostIn.setText(ip); checkHost(ip); } });
                            consoleList.addView(b, new LinearLayout.LayoutParams(-2, -2));
                        }
                        if (found.size() == 1 && hostIn.getText().length() == 0) hostIn.setText(found.get(0));
                    }
                });
            }
        }).start();
    }

    private void checkHost(final String host) {
        findMsg.setText("Checking " + host + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean ok = Net.isConsole(host, 3000);
                h.post(new Runnable() { @Override public void run() { findMsg.setText(ok ? "✓ The Babai Console answers at " + host : "No Console at " + host + ". Check the address and the Wi-Fi."); } });
            }
        }).start();
    }

    /** Start: the Console must answer and accept the PIN (it gives this screen its key). */
    private void save() {
        final String host = hostIn.getText().toString().trim();
        final String pin = pinIn.getText().toString().trim();
        final String name = nameIn.getText().toString().trim();
        final String pp = "dosa".equals(pickScreen) && pickPP != null ? pickPP.trim() : "";
        if (host.isEmpty() && pp.isEmpty()) { msg.setText("Type or pick the Console PC's address (step 1)."); return; }
        if (pickScreen.isEmpty()) { msg.setText("Pick what this screen shows (step 2)."); return; }
        if (pin.isEmpty()) { msg.setText("Type the Console PIN (step 3)."); return; }
        msg.setTextColor(0xFF444444);
        msg.setText("Connecting…");
        new Thread(new Runnable() {
            @Override public void run() {
                String err;
                StringBuilder ch = new StringBuilder();
                for (String c : pickChans) ch.append(c).append('\n');
                if (!pp.isEmpty()) {
                    // the Dosa board with Petpooja: Petpooja must answer; the Console is optional
                    if (!Petpooja.isPetpooja(pp, 5000)) {
                        h.post(new Runnable() { @Override public void run() { msg.setTextColor(RED); msg.setText("Petpooja doesn't answer at " + pp + ". Is Petpooja open on the billing PC? Try Find."); } });
                        return;
                    }
                    String key = P.getString("key", "");
                    if (!host.isEmpty()) {
                        try {
                            String r = Net.post(Net.base(host) + "/screens/api/pair", "{\"pin\":" + Net.quote(pin) + ",\"dev\":" + Net.quote(P.getString("dev", "")) + "}", 5000);
                            if (Net.jsonTrue(r, "ok")) key = Net.jsonStr(r, "key");
                        } catch (Throwable ignored) {
                            // the Console is off: the board reads Petpooja
                        }
                    }
                    P.edit().putString("console", host).putString("screen", pickScreen).putInt("tawa", pickTawa).putString("ppHost", pp)
                            .putString("name", name.isEmpty() ? Build.MODEL : name).putString("key", key)
                            .putString("dosaKinds", pickKinds).putString("dosaChans", ch.toString()).putBoolean("dosaVoice", pickVoice).putInt("dosaClearMin", pickClear).apply();
                    h.post(new Runnable() { @Override public void run() { showScreen(); } });
                    return;
                }
                try {
                    String r = Net.post(Net.base(host) + "/screens/api/pair", "{\"pin\":" + Net.quote(pin) + ",\"dev\":" + Net.quote(P.getString("dev", "")) + "}", 8000);
                    if (Net.jsonTrue(r, "ok")) {
                        P.edit().putString("console", host).putString("screen", pickScreen).putInt("tawa", pickTawa)
                                .putString("dosaKinds", pickKinds).putString("dosaChans", ch.toString()).putBoolean("dosaVoice", pickVoice).putInt("dosaClearMin", pickClear)
                                .putString("name", name.isEmpty() ? Build.MODEL : name).putString("key", Net.jsonStr(r, "key")).apply();
                        h.post(new Runnable() { @Override public void run() { showScreen(); } });
                        return;
                    }
                    err = Net.jsonStr(r, "msg");
                    if (err.isEmpty()) err = "The Console didn't accept it. Is it version 4.6 or newer?";
                } catch (Throwable t) {
                    err = "Can't reach the Console at " + host + ".";
                }
                final String e = err;
                h.post(new Runnable() { @Override public void run() { msg.setTextColor(RED); msg.setText(e); } });
            }
        }).start();
    }

    // ---- small view helpers ------------------------------------------------------------------------

    private int dp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private LinearLayout.LayoutParams lp(int w, int h2, int top) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(w, h2);
        l.setMargins(0, dp(top), 0, 0);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView head(String s) { return text(s, 20, BLACK, true); }

    private EditText edit(String hint, int type) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        e.setTextColor(BLACK);
        e.setHintTextColor(0xFF888888);
        e.setBackgroundColor(Color.WHITE);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    private Button button(String s, boolean primary) {
        final Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setFocusable(true);
        style(b, primary);
        b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean f) { ((GradientDrawable) v.getBackground()).setStroke(dp(f ? 4 : 2), f ? GREEN : BLACK); }
        });
        return b;
    }

    private void style(Button b, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(10));
        g.setColor(on ? BLACK : Color.WHITE);
        g.setStroke(dp(b.isFocused() ? 4 : 2), b.isFocused() ? GREEN : BLACK);
        b.setBackground(g);
        b.setTextColor(on ? YELLOW : BLACK);
    }
}
