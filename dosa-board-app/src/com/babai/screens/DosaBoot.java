package com.babai.screens;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Opens the Dosa Board when the TV powers on, and again after an update. */
public class DosaBoot extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            if (!c.getSharedPreferences(DosaApp.PREFS, 0).getBoolean("autostart", true)) return;
            c.startActivity(new Intent(c, DosaApp.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
    }
}
