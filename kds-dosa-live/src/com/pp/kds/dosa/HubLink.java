package com.pp.kds.dosa;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Link to the Babai Hub program on a shop PC (pure Java, no Android).
 *
 * The TV only talks to the hub over the shop Wi-Fi; the hub sends the data on to the cloud
 * (customers' "track your dosa" page and later features). What is sent: the dine-in dosa token
 * status and the open KOTs on the board (items, token, order type, platform order ID, status) -
 * no customer names or bill amounts.
 *
 * Load on the TV: at most one small request every {@link #MIN_GAP_MS}, only when something
 * changed (plus a heartbeat), on one background thread that is never more than one deep.
 */
final class HubLink {

    static final int DEFAULT_PORT = 8790;
    static final long MIN_GAP_MS = 5_000L;
    static final long HEARTBEAT_MS = 30_000L;

    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Pattern QR = Pattern.compile("\"qr\"\\s*:\\s*\"([^\"]*)\"");

    private static volatile String base = "";   // http://192.168.1.3:8790
    private static volatile String qrUrl;        // given by the hub once it is connected to the cloud
    private static volatile String status = "Not set up";
    private static volatile String lastKey = "";
    private static volatile long lastSentAt;
    private static volatile boolean busy;

    private HubLink() {}

    static void configure(String address) {
        base = normalise(address);
        lastKey = "";
        qrUrl = null;
        status = enabled() ? "Waiting for the first send" : "Not set up";
    }

    static boolean enabled() {
        return !base.isEmpty();
    }

    static String status() {
        return status;
    }

    /** QR address for the Order Ready screen; null until the hub reports a working cloud link. */
    static String qrUrl() {
        return enabled() ? qrUrl : null;
    }

    /** "192.168.1.3" -> "http://192.168.1.3:8790"; keeps a given port. */
    static String normalise(String s) {
        if (s == null) return "";
        s = s.trim();
        if (s.isEmpty()) return "";
        s = s.replaceFirst("^https?://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.indexOf(':') < 0) s = s + ":" + DEFAULT_PORT;
        return "http://" + s;
    }

    /**
     * Dine-in dosa token status, the same data the Order Ready screen shows:
     * {"v":1,"w":"8-10 min","r":[{"t":"12","a":1700000000000}],"p":[{"t":"42","m":7,"o":0}]}
     * Preparing is oldest first, so the index is the place in the queue. Minutes are whole
     * numbers, so the data only changes when something a customer would notice changes.
     */
    static String dosaPayload(List<TokenBoard.Token> ready, List<TokenBoard.Token> preparing, DosaStats.Result r,
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
                    .append(",\"m\":").append(Double.isNaN(left) ? -1 : Math.max(1, Math.round(left)))
                    .append(",\"o\":").append(t.overtaken ? 1 : 0).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Sends when the data changed (at most every 5 s), else a heartbeat every 30 s. Never blocks. */
    static void send(String device, String dosa, String kots, long now) {
        if (!enabled() || busy) return;
        String key = dosa + kots;
        boolean changed = !key.equals(lastKey);
        if (now - lastSentAt < (changed ? MIN_GAP_MS : HEARTBEAT_MS)) return;
        busy = true;
        lastKey = key;
        lastSentAt = now;
        final String body = body(device, dosa, kots);
        try {
            NET.execute(new Runnable() {
                @Override public void run() {
                    try {
                        if (post(body) != null) lastKey = ""; // failed: send again next time
                    } finally {
                        busy = false;
                    }
                }
            });
        } catch (Throwable t) {
            busy = false;
        }
    }

    /** Blocking send for the settings "Save & test" button. Returns null when it worked. */
    static String test(String device, String dosa, String kots) {
        return post(body(device, dosa, kots));
    }

    static String body(String device, String dosa, String kots) {
        return "{\"v\":1,\"dev\":" + str(device) + ",\"dosa\":" + dosa + ",\"kots\":" + kots + "}";
    }

    private static String post(String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/ingest").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(3000);
            c.setReadTimeout(5000);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] b = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(b.length);
            OutputStream o = c.getOutputStream();
            o.write(b);
            o.close();
            int code = c.getResponseCode();
            String reply = read(code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code != 200) {
                status = "\u26A0 Hub answered HTTP " + code;
                return status;
            }
            Matcher m = QR.matcher(reply);
            qrUrl = m.find() && !m.group(1).isEmpty() ? m.group(1).replace("\\/", "/") : null;
            status = "\u2713 Connected \u2022 " + clock(System.currentTimeMillis())
                    + (qrUrl == null ? " \u2022 hub has no cloud link yet" : "");
            return null;
        } catch (java.net.ConnectException e) {
            status = "\u26A0 Hub PC not reachable (is Babai Hub running? right address?)";
        } catch (java.net.SocketTimeoutException e) {
            status = "\u26A0 Hub PC not answering (Windows firewall? right address?)";
        } catch (Throwable t) {
            status = "\u26A0 Can't reach the hub (" + t.getClass().getSimpleName() + ")";
        } finally {
            if (c != null) c.disconnect();
        }
        return status;
    }

    private static String read(InputStream in) throws java.io.IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < 16_384) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private static String clock(long ms) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ms);
        return String.format(Locale.US, "%d:%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND));
    }

    /** JSON string, ASCII only. */
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
