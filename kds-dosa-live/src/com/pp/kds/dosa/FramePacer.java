package com.pp.kds.dosa;

import android.view.Choreographer;
import android.view.View;

/**
 * Steady 30 fps for TV boxes: redraws on every second display refresh (vsync). A fixed 33 ms timer
 * drifts against the TV's 60 Hz refresh, so frames land 33 ms, then 50 ms apart, which shows as a
 * "tick tick" judder on a big screen; following the refresh keeps every frame the same length.
 */
final class FramePacer implements Choreographer.FrameCallback {
    private final View view;
    private boolean pending;
    private int beats;

    FramePacer(View view) {
        this.view = view;
    }

    /** Ask for the next frame, two refreshes from now. Call on the UI thread (from onDraw). */
    void next() {
        if (pending) return;
        pending = true;
        beats = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (++beats < 2) {
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }
        pending = false;
        view.invalidate();
    }
}
