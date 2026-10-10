package com.pp.kds.dosa;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * "Track your dosa" on the customer's phone (pure Java, no Android).
 *
 * The TV sends the dine-in dosa token list (token numbers, ready / preparing, minutes left; no
 * names or bill details) to a Firebase Realtime Database. The customer scans the QR code on the
 * Order Ready screen (or a printed poster), types the token number and the page shows the live
 * status from the database on mobile data.
 */
final class PhoneTrack {

    static final String PATH = "/babai/dosa.json";
    static final String DEFAULT_PAGE = "https://jagannathsh8.github.io/Babaitiffins-Takeaway-menu-/dosa/";
    static final long HEARTBEAT_MS = 30_000L;   // resend unchanged data so phones know the TV is live

    private static volatile String db = "", secret = "", page = DEFAULT_PAGE;
    private static volatile String lastSent = "", status = "Not set up";
    private static volatile long lastSentAt, lastOkAt;
    private static volatile boolean busy;

    private PhoneTrack() {}

    static void configure(String database, String key, String pageUrl) {
        db = normaliseDb(database);
        secret = key == null ? "" : key.trim();
        page = pageUrl == null || pageUrl.trim().isEmpty() ? DEFAULT_PAGE : pageUrl.trim();
        lastSent = "";
        status = enabled() ? "Waiting for the first update" : "Not set up";
    }

    static boolean enabled() {
        return !db.isEmpty() && !secret.isEmpty();
    }

    static String status() {
        return status;
    }

    /** "babai-dosa-default-rtdb.asia-southeast1.firebasedatabase.app/" -> "https://babai...app" */
    static String normaliseDb(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** The address in the QR code: the status page plus which database to read. */
    static String qrUrl() {
        if (!enabled()) return null;
        String host = db.replaceFirst("^https?://", "");
        return page + (page.contains("?") ? "&" : "?") + "d=" + host;
    }

    /**
     * {"v":1,"u":{".sv":"timestamp"},"w":"8\u201310 min","r":[{"t":"12","a":1700000000000}],
     *  "p":[{"t":"42","m":6.5,"o":1}]}  - preparing oldest first, so the index is the queue place.
     */
    static String payload(List<TokenBoard.Token> ready, List<TokenBoard.Token> preparing, DosaStats.Result r,
                          long wallNow) {
        double avg = r == null ? Double.NaN : r.avgPrepMin, perMin = r == null ? 0 : r.throughputPerMin;
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"v\":1,\"w\":").append(str(r == null || Double.isNaN(r.etaMin) ? "" : r.etaLabel));
        sb.append(",\"r\":[");
        for (int i = 0; i < ready.size(); i++) {
            TokenBoard.Token t = ready.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"t\":").append(str(t.label)).append(",\"a\":").append(t.readyAt).append('}');
        }
        sb.append("],\"p\":[");
        for (int i = 0; i < preparing.size(); i++) {
            TokenBoard.Token t = preparing.get(i);
            double elapsed = t.createdMs > 0 ? (wallNow - t.createdMs) / 60_000.0 : 0;
            double left = TokenBoard.minutesLeft(i, elapsed, avg, perMin);
            if (i > 0) sb.append(',');
            sb.append("{\"t\":").append(str(t.label))
                    .append(",\"m\":").append(Double.isNaN(left) ? "-1" : String.format(Locale.US, "%.1f", left))
                    .append(",\"o\":").append(t.overtaken ? 1 : 0).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Sends when the list changed, or every 30 s as a heartbeat. Never blocks the caller. */
    static void publish(final String body, long now) {
        if (!enabled() || busy) return;
        if (body.equals(lastSent) && now - lastSentAt < HEARTBEAT_MS) return;
        busy = true;
        lastSent = body;
        lastSentAt = now;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String err = put(body);
                    if (err == null) {
                        lastOkAt = System.currentTimeMillis();
                        status = "\u2713 Live \u2022 last update " + timeOf(lastOkAt);
                    } else {
                        status = "\u26A0 " + err;
                        lastSent = ""; // try again on the next tick
                    }
                } finally {
                    busy = false;
                }
            }
        }, "phone-track").start();
    }

    /** Blocking send for the settings "Save & test" button. Returns null when it worked. */
    static String test(String body) {
        String err = put(body);
        if (err == null) {
            lastOkAt = System.currentTimeMillis();
            status = "\u2713 Live \u2022 last update " + timeOf(lastOkAt);
        } else {
            status = "\u26A0 " + err;
        }
        return err;
    }

    /** The server stamps the time, so phones compare against a correct clock even if the TV's is off. */
    static String withServerTime(String body) {
        return "{\"u\":{\".sv\":\"timestamp\"}," + body.substring(1);
    }

    private static String put(String body) {
        HttpURLConnection c = null;
        try {
            URL url = new URL(db + PATH + "?auth=" + java.net.URLEncoder.encode(secret, "UTF-8"));
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("PUT");
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] b = withServerTime(body).getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream o = c.getOutputStream();
            o.write(b);
            o.close();
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            if (in != null) {
                byte[] buf = new byte[512];
                while (in.read(buf) > 0) { /* drain so the connection can be reused */ }
                in.close();
            }
            if (code == 401 || code == 403) return "Database refused the key (check the secret)";
            if (code == 404) return "Database not found (check the address)";
            return code >= 200 && code < 300 ? null : "Database answered HTTP " + code;
        } catch (java.net.UnknownHostException e) {
            return "No internet / wrong database address";
        } catch (Throwable t) {
            return "Can't reach the database (" + t.getClass().getSimpleName() + ")";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String timeOf(long ms) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ms);
        return String.format(Locale.US, "%d:%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND));
    }

    static String str(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\\') sb.append('\\').append(ch);
            else if (ch < 0x20 || ch > 0x7E) sb.append(String.format(Locale.US, "\\u%04x", (int) ch));
            else sb.append(ch);
        }
        return sb.append('"').toString();
    }
}
