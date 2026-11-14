package com.babai.screens;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Small HTTP helpers and finding the Babai Console on the shop Wi-Fi (plain Java: unit-tested). */
final class Net {
    static final int PORT = 8790;

    private Net() {}

    static String get(String url, int timeoutMs) throws Exception { return get(url, timeoutMs, 256 * 1024); }

    /** GET, at most max bytes; a longer reply is an error (never a cut-off reply). */
    static String get(String url, int timeoutMs, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            String r = read(c.getInputStream(), max + 1);
            if (r.length() > max) throw new java.io.IOException("reply too long (over " + max / 1024 + " KB)");
            return r;
        } finally {
            c.disconnect();
        }
    }

    static String post(String url, String json, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Babai-Hub", "1");
        try {
            OutputStream o = c.getOutputStream();
            o.write(json.getBytes("UTF-8"));
            o.close();
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            return in == null ? "" : read(in, 64 * 1024);
        } finally {
            c.disconnect();
        }
    }

    static String read(InputStream in, int max) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && b.size() < max) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    /** "192.168.1.5" or "192.168.1.5:8790" -> "http://192.168.1.5:8790" */
    static String base(String host) {
        String h = host == null ? "" : host.trim();
        if (h.startsWith("http://")) h = h.substring(7);
        if (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        if (h.isEmpty()) return "";
        if (h.indexOf(':') < 0) h = h + ":" + PORT;
        return "http://" + h;
    }

    static boolean isConsole(String host, int timeoutMs) {
        try {
            return get(base(host) + "/ping", timeoutMs).startsWith("babai-hub");
        } catch (Throwable t) {
            return false;
        }
    }

    /** This device's IPv4 addresses on the local network. */
    static List<String> myAddresses() {
        List<String> out = new ArrayList<String>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** The other addresses of the same /24 network, nearest first. */
    static List<String> neighbours(String ip) {
        List<String> out = new ArrayList<String>();
        int dot = ip.lastIndexOf('.');
        if (dot < 0) return out;
        String pre = ip.substring(0, dot + 1);
        int me;
        try { me = Integer.parseInt(ip.substring(dot + 1)); } catch (NumberFormatException e) { return out; }
        for (int d = 1; d < 254; d++) {
            if (me - d >= 1) out.add(pre + (me - d));
            if (me + d <= 254) out.add(pre + (me + d));
        }
        return out;
    }

    /** Every Babai Console answering on this device's networks (a few seconds). */
    static List<String> findConsoles() {
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        ExecutorService ex = Executors.newFixedThreadPool(48);
        for (String me : myAddresses()) {
            for (final String ip : neighbours(me)) {
                ex.execute(new Runnable() {
                    @Override public void run() { if (isConsole(ip, 700)) found.add(ip); }
                });
            }
        }
        ex.shutdown();
        try { ex.awaitTermination(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        ex.shutdownNow();
        List<String> out = new ArrayList<String>(found);
        Collections.sort(out);
        return out;
    }

    /** A JSON string value: "msg" from {"ok":false,"msg":"Wrong PIN."} (enough for our small answers). */
    static String jsonStr(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return "";
        i = json.indexOf(':', i + k.length());
        if (i < 0) return "";
        i++;
        while (i < json.length() && json.charAt(i) == ' ') i++;
        if (i >= json.length() || json.charAt(i) != '"') return "";
        StringBuilder sb = new StringBuilder();
        for (i++; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) { char n = json.charAt(++i); sb.append(n == 'n' ? '\n' : n); continue; }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    static long jsonNum(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return -1;
        i = json.indexOf(':', i + k.length()) + 1;
        while (i > 0 && i < json.length() && json.charAt(i) == ' ') i++;
        int j = i;
        while (j < json.length() && (Character.isDigit(json.charAt(j)) || json.charAt(j) == '-')) j++;
        try { return Long.parseLong(json.substring(i, j)); } catch (Throwable t) { return -1; }
    }

    static boolean jsonTrue(String json, String key) {
        return json.replace(" ", "").contains("\"" + key + "\":true");
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 32) sb.append(' ');
            else sb.append(c);
        }
        return sb.append('"').toString();
    }
}
