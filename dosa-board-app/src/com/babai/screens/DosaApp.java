package com.babai.screens;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.babai.board.BoardView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Babai Dosa Board: a separate app only for the dosa pick-up TV. Reads the orders straight from the
 * Petpooja billing PC on the shop Wi-Fi, as the Petpooja KDS does (no Console), and shows the Babai
 * Dosa Board. Settings: MENU on the remote, Back 5 times, or 5 taps in the top-left corner.
 */
public class DosaApp extends Activity {
    static final String PREFS = "babai_dosa_board";
    private static final int YELLOW = 0xFFF9BC21, BLACK = 0xFF171717, CREAM = 0xFFFFF6DF, GREEN = 0xFF568B60, RED = 0xFFB91C1C;

    /** The "Did you know?" facts until the outlet adds its own on the TV's settings page. Only neutral
     *  lines: no recipe or ingredient claims (the outlet writes those itself if it wants them). */
    static final String[] FACTS = {
            "Dosas are made fresh to order: a short wait means a hot, crisp dosa.",
            "Watch the board: your token moves to Ready for pickup the moment your dosa is done.",
            "Pesarattu and upma: Andhra’s traditional breakfast buddies.",
            "“Kaaram” means spicy in Telugu. Your dosa comes with a delicious warning!",
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences P;
    private FrameLayout root;
    private Voice voice;
    private DosaBoard board;
    private DosaStats stats;
    private TvWeb web;
    private long lastCall; // when the last call was queued (one chime per run of calls)
    private long failSince; // when Petpooja stopped answering (0: it answers)
    private volatile String lastWhy = "";
    private TextView overlay;
    private boolean inSetup;
    private int fails;
    private long[] backs = new long[5], taps = new long[5];
    private int backI, tapI;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        P = getSharedPreferences(PREFS, 0);
        DosaBoard.defaultKinds = "I"; // this app: dine-in dosas unless chosen otherwise
        DosaBoard.remindMins = new int[]{1, 2, 4, 5}; // calls again while ready and not dispatched
        DosaBoard.defaultDigits = true; // "token two four zero one"
        DosaBoard.shortCalls = true;
        DosaBoard.roundsMode = true;    // wait times by tawa rounds: 8 dosas in 3 min (the KDS V11 Dosa Live rule)    // the outlet's choice: "Token 2 4 0 1, dosa ready."
        voice = new Voice(this);
        voice.tune(0.95f, 1.05f, P.getString("ttsVoice", "")); // the outlet's choice: speed 0.95, pitch 1.05
        stats = new DosaStats(new File(getFilesDir(), "today.json"));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        if (P.getString("ppHost", "").isEmpty()) showSetup(false);
        else showBoard();
        h.post(factsLoop);
        web = new TvWeb(webApp);
        web.start();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (board != null) board.stop();
        if (web != null) web.stop();
        voice.shutdown();
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

    /** Every minute: today's numbers first, then the facts. */
    private final Runnable factsLoop = new Runnable() {
        @Override public void run() {
            List<String> f = new ArrayList<String>();
            if (P.getBoolean("numbersOn", true)) f.addAll(stats.facts());
            f.addAll(shownFacts());
            if (f.isEmpty()) f.add(FACTS[0]);
            BoardView.FACTS = f.toArray(new String[0]);
            h.postDelayed(this, 60_000);
        }
    };

    // ---- facts and the TV's settings page (TvWeb) ----------------------------------------------------

    /** The facts list as saved on the settings page ([{t, on}]), or the built-in one. */
    private org.json.JSONArray factsList() {
        try {
            String s = P.getString("facts", "");
            if (!s.isEmpty()) return new org.json.JSONArray(s);
        } catch (Throwable ignored) {
        }
        org.json.JSONArray a = new org.json.JSONArray();
        try { for (String x : FACTS) a.put(new org.json.JSONObject().put("t", x).put("on", true)); } catch (Throwable ignored) { }
        return a;
    }

    private List<String> shownFacts() {
        List<String> out = new ArrayList<String>();
        org.json.JSONArray a = factsList();
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject f = a.optJSONObject(i);
            if (f != null && f.optBoolean("on", true) && !f.optString("t").trim().isEmpty()) out.add(f.optString("t").trim());
        }
        return out;
    }

    private static final String[] TYPE = {"I", "Dine in", "P", "Pick up", "S", "Self service", "D", "Online"};

    private final TvWeb.App webApp = new TvWeb.App() {
        @Override public org.json.JSONObject state() {
            org.json.JSONObject o = new org.json.JSONObject();
            try {
                org.json.JSONObject st = new org.json.JSONObject();
                st.put("kinds", P.getString("dosaKinds", "I"));
                org.json.JSONArray ch = new org.json.JSONArray();
                for (String c : DosaBoard.chans(P)) ch.put(c);
                st.put("chans", ch);
                org.json.JSONArray seen = new org.json.JSONArray();
                for (String c : DosaBoard.seenChans(P)) seen.put(c);
                st.put("seenChans", seen);
                st.put("clearMin", P.getInt("dosaClearMin", 10));
                st.put("roundSize", P.getInt("roundSize", 8));
                st.put("roundMin", (double) P.getFloat("roundMin", 3f));
                st.put("voice", P.getBoolean("dosaVoice", true));
                boolean dg = P.getBoolean("voiceDigits", true);
                st.put("digits", dg);
                st.put("numbersOn", P.getBoolean("numbersOn", true));
                st.put("ttsVoice", P.getString("ttsVoice", ""));
                org.json.JSONArray vs = new org.json.JSONArray();
                for (String v : voice.voices()) vs.put(v);
                st.put("voices", vs);
                List<String> ex = new ArrayList<String>();
                ex.add("2401");
                st.put("sample", DosaBoard.announcement(ex, dg));
                o.put("settings", st);
                o.put("facts", factsList());
                org.json.JSONArray nums = new org.json.JSONArray();
                for (String x : stats.facts()) nums.put(x);
                o.put("numbers", nums);
                o.put("ppHost", P.getString("ppHost", ""));
                if (failSince > 0) o.put("ppProblem", "Petpooja not answering since " + new java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(failSince) + " (" + lastWhy + "). The TV shows the last orders it read.");
                o.put("version", "app " + Version.NAME);
                DosaBoard b = board;
                org.json.JSONObject last = b == null ? null : b.last;
                org.json.JSONArray rows = new org.json.JSONArray();
                if (last != null) {
                    o.put("readAt", last.optLong("now"));
                    java.util.Set<String> kinds = DosaBoard.kinds(P), chans = DosaBoard.chans(P);
                    org.json.JSONArray cards = last.optJSONArray("cards");
                    for (int i = 0; cards != null && i < cards.length(); i++) {
                        org.json.JSONObject c = cards.optJSONObject(i);
                        if (c == null) continue;
                        org.json.JSONArray its = c.optJSONArray("i"), dos = new org.json.JSONArray();
                        for (int k = 0; its != null && k < its.length(); k++) {
                            org.json.JSONObject it = its.optJSONObject(k);
                            if (it != null && DosaBoard.isDosa(it.optString("c"))) dos.put(new org.json.JSONObject().put("n", it.optInt("q", 1) + "× " + it.optString("n")).put("s", it.optInt("s", it.optBoolean("r") ? 9 : 1)));
                        }
                        if (dos.length() == 0 && c.optInt("hid") == 0) continue;
                        String chn = c.optString("ch", "I"), type = chn;
                        for (int t = 0; t < TYPE.length; t += 2) if (TYPE[t].equals(chn)) type = TYPE[t + 1];
                        if ("D".equals(chn)) type = c.optString("app", "Online");
                        boolean want = kinds.contains(chn) && !("D".equals(chn) && !chans.isEmpty() && !chans.contains(c.optString("app")));
                        String tok = c.optString("kn");
                        if ("D".equals(chn)) { String app = c.optString("app"); tok = (app.isEmpty() ? "" : app.substring(0, 1).toUpperCase() + " ") + c.optString("l4", tok); }
                        rows.put(new org.json.JSONObject().put("token", tok).put("type", type).put("items", dos).put("st", c.optString("st")).put("oh", c.optString("oh"))
                                .put("hid", c.optInt("hid")).put("shown", !want ? "no (" + type + " is off)" : dos.length() == 0 ? "no (all dosas dispatched)" : "yes"));
                    }
                }
                o.put("board", rows);
            } catch (Throwable ignored) {
            }
            return o;
        }

        @Override public String save(final org.json.JSONObject in) {
            try {
                org.json.JSONArray f = in.optJSONArray("facts"), keep = new org.json.JSONArray();
                for (int i = 0; f != null && i < f.length() && keep.length() < 200; i++) {
                    org.json.JSONObject x = f.optJSONObject(i);
                    String t = x == null ? "" : x.optString("t").trim();
                    if (!t.isEmpty()) keep.put(new org.json.JSONObject().put("t", t.length() > 200 ? t.substring(0, 200) : t).put("on", x.optBoolean("on", true)));
                }
                String kinds = in.optString("kinds", "I").replaceAll("[^IPSD]", "");
                if (kinds.isEmpty()) kinds = "I";
                StringBuilder ch = new StringBuilder();
                org.json.JSONArray c = in.optJSONArray("chans");
                for (int i = 0; c != null && i < c.length(); i++) if (!c.optString(i).trim().isEmpty()) ch.append(c.optString(i).trim()).append('\n');
                int clr = in.optInt("clearMin", 10);
                if (clr != 5 && clr != 10 && clr != 15 && clr != 30) clr = 10;
                int rs = Math.max(2, Math.min(20, in.optInt("roundSize", 8)));
                float rm = (float) Math.max(1.5, Math.min(8, in.optDouble("roundMin", 3)));
                P.edit().putString("facts", keep.toString()).putBoolean("numbersOn", in.optBoolean("numbersOn", true))
                        .putString("dosaKinds", kinds).putString("dosaChans", ch.toString()).putInt("dosaClearMin", clr).putInt("roundSize", rs).putFloat("roundMin", rm)
                        .putBoolean("dosaVoice", in.optBoolean("voice", true)).putBoolean("voiceDigits", in.optBoolean("digits", true))
                        .putString("ttsVoice", in.optString("ttsVoice", "")).apply();
                voice.tune(0.95f, 1.05f, in.optString("ttsVoice", ""));
                h.post(new Runnable() { @Override public void run() { h.removeCallbacks(factsLoop); factsLoop.run(); } });
                return "";
            } catch (Throwable t) {
                return "Couldn't save: " + t.getMessage();
            }
        }

        @Override public void announce() {
            List<String> ex = new ArrayList<String>();
            ex.add("2401");
            voice.say(DosaBoard.announcement(ex, P.getBoolean("voiceDigits", true)), null, true);
        }
    };

    // ---- the board -----------------------------------------------------------------------------------

    private void showBoard() {
        inSetup = false;
        if (board != null) board.stop();
        root.removeAllViews();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        board = new DosaBoard(this, P, new DosaBoard.Host() {
            @Override public void boardOk() { fails = 0; failSince = 0; lastWhy = ""; }
            @Override public void boardFailed(String why) {
                // nothing on the TV: the board keeps the last orders; the laptop page says what is wrong
                fails++;
                if (failSince == 0) failSince = System.currentTimeMillis();
                lastWhy = why == null ? "" : why;
            }
            @Override public void say(String text, String wav) {
                // one chime for a run of calls: not while still speaking, nor within 4 s of the last call
                long now = System.currentTimeMillis();
                boolean chime = !voice.speaking() && now - lastCall > 4000;
                lastCall = now;
                voice.say(text, null, chime);
            }
            @Override public boolean speaking() { return voice.speaking(); }
        }, "", "");
        board.fetchAll = true;
        board.stats = stats;
        board.view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN && e.getX() < dp(70) && e.getY() < dp(70)) {
                    taps[tapI++ % 5] = System.currentTimeMillis();
                    if (fiveWithin(taps, 3000)) { taps = new long[5]; showSetup(true); }
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (inSetup) return super.dispatchKeyEvent(e);
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS) {
            if (e.getAction() == KeyEvent.ACTION_UP) showSetup(true);
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (e.getAction() != KeyEvent.ACTION_UP) return true;
            backs[backI++ % 5] = System.currentTimeMillis();
            if (fiveWithin(backs, 4000)) { backs = new long[5]; showSetup(true); }
            return true; // never leaves the board by accident
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean fiveWithin(long[] t, long ms) {
        long min = Long.MAX_VALUE, max = 0;
        for (long x : t) { if (x == 0) return false; min = Math.min(min, x); max = Math.max(max, x); }
        return max - min <= ms;
    }

    // ---- settings ------------------------------------------------------------------------------------

    private String pickPP = "", ppMsg = "", pickKinds = "I";
    private List<String> pickChans = new ArrayList<String>();
    private int pickClear = 10;
    private boolean pickVoice = true;
    private LinearLayout col;
    private TextView msg;

    private void showSetup(boolean again) {
        inSetup = true;
        if (board != null) { board.stop(); board = null; }
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        pickPP = P.getString("ppHost", "");
        pickKinds = P.getString("dosaKinds", "I");
        pickChans = new ArrayList<String>(DosaBoard.chans(P));
        pickClear = P.getInt("dosaClearMin", 10);
        pickVoice = P.getBoolean("dosaVoice", true);
        ppMsg = "";
        drawSetup(again);
        if (pickPP.isEmpty()) findPetpooja();
    }

    private void drawSetup(final boolean again) {
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(CREAM);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(24), dp(16), dp(24), dp(32));
        sv.addView(col);
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        TextView top = text("Babai Dosa Board  ·  " + Version.NAME, 26, BLACK, true);
        top.setBackgroundColor(YELLOW);
        top.setPadding(dp(16), dp(12), dp(16), dp(12));
        col.addView(top, lp(-1, -2, 0));

        // Petpooja
        col.addView(head("Petpooja billing PC"), lp(-1, -2, 16));
        LinearLayout pr = new LinearLayout(this);
        final EditText ppIn = edit("e.g. 192.168.1.34", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ppIn.setText(pickPP);
        ppIn.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            @Override public void onTextChanged(CharSequence a, int b, int c, int d) { }
            @Override public void afterTextChanged(Editable e) { pickPP = e.toString().trim(); }
        });
        pr.addView(ppIn, new LinearLayout.LayoutParams(0, -2, 1));
        Button fb = button("Find", false);
        fb.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { findPetpooja(); } });
        pr.addView(fb, new LinearLayout.LayoutParams(-2, -2));
        col.addView(pr, lp(-1, -2, 6));
        if (!ppMsg.isEmpty()) col.addView(text(ppMsg, 15, ppMsg.startsWith("✓") ? GREEN : RED, false), lp(-1, -2, 4));

        // which dosas
        col.addView(head("Dosas on this board"), lp(-1, -2, 16));
        LinearLayout kr = new LinearLayout(this);
        String[][] kinds = {{"I", "Dine in"}, {"P", "Pick up"}, {"S", "Self service"}, {"D", "Online"}};
        for (final String[] k : kinds) {
            boolean on = pickKinds.contains(k[0]);
            Button b = button((on ? "✓ " : "") + k[1], false);
            style(b, on);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pickKinds = pickKinds.contains(k[0]) ? pickKinds.replace(k[0], "") : pickKinds + k[0];
                    if (pickKinds.isEmpty()) pickKinds = "I"; // never an empty board
                    drawSetup(again);
                }
            });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1);
            l.setMargins(0, 0, dp(6), 0);
            kr.addView(b, l);
        }
        col.addView(kr, lp(-1, -2, 6));
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
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        if (c.isEmpty()) pickChans.clear();
                        else if (!pickChans.remove(c)) pickChans.add(c);
                        drawSetup(again);
                    }
                });
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
                l.setMargins(0, 0, dp(6), 0);
                cr.addView(b, l);
            }
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.addView(cr);
            col.addView(hs, lp(-1, -2, 6));
        }

        // always on
        col.addView(head("Always on"), lp(-1, -2, 16));
        LinearLayout ar = new LinearLayout(this);
        final boolean auto = P.getBoolean("autostart", true);
        Button ab = button((auto ? "✓ " : "") + "Open when the TV turns on", false);
        style(ab, auto);
        ab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                P.edit().putBoolean("autostart", !auto).apply();
                if (!auto) askOverlay(); // newer TVs only let an app open itself at power-on with this permission
                drawSetup(again);
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, -2, 1);
        l1.setMargins(0, 0, dp(6), 0);
        ar.addView(ab, l1);
        final android.content.ComponentName home = new android.content.ComponentName(this, "com.babai.screens.DosaHome");
        final boolean kiosk = getPackageManager().getComponentEnabledSetting(home) == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        Button kb = button((kiosk ? "✓ " : "") + "Kiosk mode", false);
        style(kb, kiosk);
        kb.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                getPackageManager().setComponentEnabledSetting(home, kiosk ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP);
                if (!kiosk) {
                    try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)); } catch (Throwable t) {
                        msg.setText("Press the remote's Home key and choose Babai Dosa Board, Always.");
                    }
                }
                drawSetup(again);
            }
        });
        ar.addView(kb, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(ar, lp(-1, -2, 6));
        if (kiosk) col.addView(text("Home key opens the board. To leave kiosk mode, tap Kiosk mode again.", 14, 0xFF666666, false), lp(-1, -2, 4));

        // start
        msg = text("", 16, RED, true);
        col.addView(msg, lp(-1, -2, 10));
        LinearLayout acts = new LinearLayout(this);
        Button start = button("Start", true);
        start.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
        acts.addView(start, new LinearLayout.LayoutParams(0, -2, 1));
        if (again) {
            Button back = button("Back to the board", false);
            back.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showBoard(); } });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -2, 1);
            l.setMargins(dp(8), 0, 0, 0);
            acts.addView(back, l);
        }
        col.addView(acts, lp(-1, -2, 6));
        if (web != null && web.port > 0) col.addView(text("Facts, ready time, voice: on a laptop on this Wi-Fi open " + TvWeb.addresses(web.port) + " (PIN 1234)", 15, 0xFF444444, false), lp(-1, -2, 14));
        start.requestFocus();
    }

    /** Android 10+: an app may open itself at power-on only with "display over other apps". */
    private void askOverlay() {
        if (android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(this)) return;
        try {
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {
        }
    }

    /** The typed address first (10 s, as the Console waits), else looks on the Wi-Fi. */
    private void findPetpooja() {
        final String typed = pickPP.trim();
        ppMsg = typed.isEmpty() ? "Looking on this Wi-Fi…" : "Checking " + typed + "…";
        if (inSetup) drawSetup(board == null);
        new Thread(new Runnable() {
            @Override public void run() {
                String why = typed.isEmpty() ? "" : Petpooja.check(typed);
                String m, use = null;
                if (!typed.isEmpty() && why.isEmpty()) {
                    m = "✓ Petpooja answers at " + typed + ".";
                    use = typed;
                } else {
                    List<String> f = Petpooja.find();
                    if (!f.isEmpty()) {
                        use = f.get(0);
                        m = "✓ Found Petpooja at " + f.get(0) + (f.size() > 1 ? " (also " + f.subList(1, f.size()) + ")" : "") + ".";
                    } else {
                        List<String> open = Petpooja.lastOpen;
                        m = "Not found. " + (typed.isEmpty() ? "" : why + ". ") + (open.isEmpty()
                                ? "Check: Petpooja open on the billing PC, this TV on the same Wi-Fi. Or type the address from Console › Setup › Petpooja."
                                : "Port " + Petpooja.PORT + " open at " + open + ", but no Petpooja answer.");
                    }
                }
                final String out = m, pick = use;
                h.post(new Runnable() {
                    @Override public void run() {
                        if (pick != null) pickPP = pick;
                        ppMsg = out;
                        if (inSetup) drawSetup(true);
                    }
                });
            }
        }).start();
    }

    private void save() {
        final String pp = pickPP.trim();
        if (pp.isEmpty()) { msg.setText("Type the Petpooja billing PC's address, or press Find."); return; }
        msg.setTextColor(0xFF444444);
        msg.setText("Checking Petpooja…");
        new Thread(new Runnable() {
            @Override public void run() {
                final String why = Petpooja.check(pp);
                h.post(new Runnable() {
                    @Override public void run() {
                        if (!why.isEmpty()) { msg.setTextColor(RED); msg.setText("Petpooja: " + why + ". Try Find."); return; }
                        StringBuilder ch = new StringBuilder();
                        for (String c : pickChans) ch.append(c).append('\n');
                        P.edit().putString("ppHost", pp).putString("dosaKinds", pickKinds).putString("dosaChans", ch.toString())
                                .putInt("dosaClearMin", pickClear).putBoolean("dosaVoice", pickVoice).apply();
                        showBoard();
                    }
                });
            }
        }).start();
    }

    // ---- small view helpers ----------------------------------------------------------------------------

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
