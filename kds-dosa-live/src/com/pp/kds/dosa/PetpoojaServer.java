package com.pp.kds.dosa;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The Petpooja billing PC this KDS is connected to, as the KDS saved it ("intra_ip").
 * The KDS keeps its settings in encrypted preferences ("KdsV2Prefs"); they are opened the same
 * way the KDS opens them, falling back to the plain file on old Android.
 */
final class PetpoojaServer {

    private PetpoojaServer() {}

    static String ip(Context c) {
        SharedPreferences p = prefs(c);
        if (p == null) return null;
        try {
            String ip = p.getString("intra_ip", null);
            if (ip == null || ip.trim().isEmpty()) {
                String url = p.getString("intranet_url", null);   // "http://192.168.1.3:3000/"
                if (url != null) ip = url.replaceFirst("^https?://", "").replaceAll("[:/].*$", "");
            }
            ip = ip == null ? null : ip.trim();
            return ip == null || ip.isEmpty() ? null : ip;
        } catch (Throwable t) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context c) {
        SharedPreferences plain = c.getSharedPreferences("KdsV2Prefs", Context.MODE_PRIVATE);
        if (plain.contains("intra_ip") || plain.contains("intranet_url")) return plain;   // not encrypted
        // Encrypted: open it only after the KDS has created its key (never race the KDS to create it).
        if (!plain.contains("__androidx_security_crypto_encrypted_prefs_key_keyset__")) return null;
        try {
            Class<?> k = Class.forName("com.pp.kds.core.preferences.di.PreferencesModule");
            Object module = k.getField("INSTANCE").get(null);
            return (SharedPreferences) k.getMethod("providePreferences", Context.class).invoke(module, c);
        } catch (Throwable t) {
            try {
                return c.getSharedPreferences("KdsV2Prefs", Context.MODE_PRIVATE);
            } catch (Throwable t2) {
                return null;
            }
        }
    }
}
