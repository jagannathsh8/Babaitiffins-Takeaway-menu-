package com.pp.kds.dosa;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.WindowManager;

/**
 * Screen brightness for the rider board only (window-level: no permission, nothing changed in
 * system settings). AUTO follows the room light when the device has a light sensor (bright
 * sunlight -> full, dark room -> dimmer, changes smoothly); without a sensor AUTO means full
 * brightness. Manual: 50 / 75 / 100 %. Leaving the board restores the normal brightness.
 */
final class AutoBrightness implements SensorEventListener {

    private final Activity activity;
    private final SensorManager sm;
    private final Sensor light;
    private int mode;          // 0 auto, 1 = 50 %, 2 = 75 %, 3 = 100 %
    private float smooth = -1f, applied = -2f;
    private boolean listening;

    AutoBrightness(Activity a) {
        activity = a;
        sm = (SensorManager) a.getSystemService(Context.SENSOR_SERVICE);
        light = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LIGHT);
    }

    boolean hasSensor() {
        return light != null;
    }

    void start(int m) {
        mode = m;
        if (mode == 0 && light != null) {
            if (!listening) {
                sm.registerListener(this, light, SensorManager.SENSOR_DELAY_NORMAL);
                listening = true;
            }
        } else {
            unregister();
            apply(mode == 1 ? 0.5f : mode == 2 ? 0.75f : 1.0f); // AUTO without a sensor = full
        }
    }

    void stop() {
        unregister();
        apply(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
    }

    private void unregister() {
        if (listening) {
            try {
                sm.unregisterListener(this);
            } catch (Throwable ignored) {
            }
            listening = false;
        }
    }

    /** Room light (lux) -> brightness: 1 lux ~35 %, 100 lux ~65 %, 1 000 lux ~83 %, sunlight 100 %. */
    static float forLux(float lux) {
        double b = 0.30 + 0.175 * Math.log10(Math.max(0, lux) + 1);
        return (float) Math.max(0.30, Math.min(1.0, b));
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float target = forLux(e.values[0]);
        smooth = smooth < 0 ? target : smooth * 0.8f + target * 0.2f; // no flicker on passing shadows
        if (Math.abs(smooth - applied) >= 0.03f) apply(smooth);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void apply(float v) {
        try {
            WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
            lp.screenBrightness = v;
            activity.getWindow().setAttributes(lp);
            applied = v;
        } catch (Throwable ignored) {
        }
    }
}
