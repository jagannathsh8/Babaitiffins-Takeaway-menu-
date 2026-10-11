package com.pp.kds.dosa;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.InputStream;

/**
 * Text reader for the rider camera: Tesseract (from the APK assets, assets/ocr/) running in a tiny
 * hidden WebView. The heavy work happens in the WebView's own renderer, never on the KDS screen
 * thread. One picture at a time; a picture that is not answered in time restarts the reader.
 */
final class OcrEngine {

    interface Callback {
        /** Called on a background thread. ms < 0: not read (reader busy / not ready). */
        void onText(String text, long ms);
    }

    private static final String BASE = "https://babai.local/ocr/";
    private static final long TIMEOUT_MS = 15_000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static WebView web;
    private static Activity owner;
    private static volatile boolean ready;
    private static volatile String mode = "";
    private static volatile String lastError = "";
    private static volatile Callback pending;
    private static volatile long sentAt;
    private static volatile long lastMs;
    private static int seq;
    private static int restarts;

    private OcrEngine() {}

    static boolean ready() {
        return ready;
    }

    static boolean busy() {
        return pending != null;
    }

    /** "simd 420 ms" / "starting" / the last problem, for the settings window. */
    static String status() {
        if (web == null) return "off";
        if (!ready) return lastError.isEmpty() ? "starting" : lastError;
        return "ready (" + mode + ")" + (lastMs > 0 ? " • " + lastMs + " ms per read" : "");
    }

    /** Main thread. Starts the reader once (about 2 s; nothing happens on the KDS screen). */
    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    static void start(Activity a) {
        if (web != null && owner == a) return;
        stop();
        owner = a;
        try {
            WebView w = new WebView(a.getApplicationContext());
            WebSettings s = w.getSettings();
            s.setJavaScriptEnabled(true);
            s.setAllowFileAccess(false);
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            w.addJavascriptInterface(new Bridge(), "BabaiOcr");
            w.setWebViewClient(new Client());
            // Attached (1 px, invisible to the eye) so the renderer keeps running normally.
            w.setAlpha(0.01f);
            w.setFocusable(false);
            w.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            ViewGroup content = (ViewGroup) a.findViewById(android.R.id.content);
            content.addView(w, 0, new FrameLayout.LayoutParams(1, 1));
            web = w;
            ready = false;
            lastError = "";
            w.loadUrl(BASE + "ocr.html");
            main.postDelayed(watchdog, 3_000L);
        } catch (Throwable t) {
            web = null;
            lastError = "no WebView: " + t.getClass().getSimpleName();
        }
    }

    /** Main thread. Frees the reader (memory) when the rider camera is not used. */
    static void stop() {
        main.removeCallbacks(watchdog);
        WebView w = web;
        web = null;
        ready = false;
        Callback cb = pending;
        pending = null;
        if (cb != null) cb.onText("", -1);
        if (w != null) {
            try {
                if (w.getParent() != null) ((ViewGroup) w.getParent()).removeView(w);
                w.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Any thread. False when the reader cannot take a picture now (the caller just tries later). */
    static boolean recognize(byte[] jpeg, final int psm, Callback cb) {
        if (!ready || pending != null || web == null) return false;
        pending = cb;
        sentAt = SystemClock.uptimeMillis();
        final int id = ++seq;
        final String b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
        main.post(new Runnable() {
            @Override public void run() {
                WebView w = web;
                if (w == null) {
                    finish("", -1);
                    return;
                }
                try {
                    w.evaluateJavascript("ocr(" + id + ",'" + b64 + "'," + psm + ")", null);
                } catch (Throwable t) {
                    finish("", -1);
                }
            }
        });
        return true;
    }

    private static void finish(String text, long ms) {
        Callback cb = pending;
        pending = null;
        if (ms > 0) lastMs = ms;
        if (cb != null) {
            try {
                cb.onText(text == null ? "" : text, ms);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Restarts a reader that stopped answering or never came up. */
    private static final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (web == null) return;
            long now = SystemClock.uptimeMillis();
            boolean hung = pending != null && now - sentAt > TIMEOUT_MS;
            if (hung) restart("reader did not answer");
            else main.postDelayed(this, 3_000L);
        }
    };

    private static void restart(String why) {
        lastError = why;
        Activity a = owner;
        stop();
        if (a == null || a.isFinishing() || ++restarts > 20) return; // give up after many: barcode still works
        final Activity act = a;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (web == null && !act.isFinishing() && RiderCam.wantsOcr()) start(act);
            }
        }, 4_000L);
    }

    private static final class Bridge {
        @JavascriptInterface
        public void ready(String m) {
            mode = m;
            ready = true;
            restarts = 0;
        }

        @JavascriptInterface
        public void result(int id, String text, long ms) {
            finish(text, ms);
        }

        @JavascriptInterface
        public void error(String msg) {
            lastError = msg == null ? "error" : msg;
            if (!ready) {
                main.post(new Runnable() {
                    @Override public void run() {
                        restart(lastError);
                    }
                });
            }
        }
    }

    /** Serves https://babai.local/ocr/* from the APK; nothing goes to the network. */
    private static final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            return serve(req.getUrl().toString());
        }

        @Override
        @SuppressWarnings("deprecation")
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            return serve(url);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The reader's process died (memory): never let that take the KDS down.
            if (view == web) {
                web = null;
                ready = false;
                try {
                    if (view.getParent() != null) ((ViewGroup) view.getParent()).removeView(view);
                    view.destroy();
                } catch (Throwable ignored) {
                }
                finish("", -1);
                main.post(new Runnable() {
                    @Override public void run() {
                        restart("reader restarted");
                    }
                });
            }
            return true;
        }

        private WebResourceResponse serve(String url) {
            if (url == null || !url.startsWith(BASE)) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not here",
                        java.util.Collections.<String, String>emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
            }
            String name = url.substring(BASE.length()).replaceAll("[?#].*$", "");
            if (name.contains("/") || name.contains("..")) name = "none";
            String mime = name.endsWith(".wasm") ? "application/wasm" : name.endsWith(".js") ? "text/javascript"
                    : name.endsWith(".html") ? "text/html" : "application/octet-stream";
            try {
                Activity a = owner;
                InputStream in = a.getAssets().open("ocr/" + name);
                WebResourceResponse r = new WebResourceResponse(mime, name.endsWith(".html") || name.endsWith(".js")
                        ? "utf-8" : null, in);
                java.util.Map<String, String> h = new java.util.HashMap<String, String>();
                h.put("Access-Control-Allow-Origin", "*");
                h.put("Cache-Control", "no-store");
                r.setResponseHeaders(h);
                return r;
            } catch (Throwable t) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Missing",
                        java.util.Collections.<String, String>emptyMap(), new java.io.ByteArrayInputStream(new byte[0]));
            }
        }
    }
}
