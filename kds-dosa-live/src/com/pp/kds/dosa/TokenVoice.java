package com.pp.kds.dosa;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.Locale;
import java.util.Set;

/**
 * Speaks "Token number 1, 2, 6, dosa ready, kindly collect." on the TV, in English, using the
 * device's offline text-to-speech. A soft two-note chime plays first so people look up.
 * Loudness follows the TV / device media volume (the remote's volume keys).
 */
final class TokenVoice {

    private static final int RATE = 22050;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private TextToSpeech tts;
    private boolean ready;
    private String waiting;           // phrase requested before the engine finished starting
    private int restoreVolume = -1;
    private AudioTrack chime;

    private long createdAt;
    private boolean failed;

    TokenVoice(Context c) {
        app = c.getApplicationContext();
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        startEngine();
    }

    private Voice defaultVoice, femaleVoice, maleVoice;
    private boolean nextMale;

    /**
     * A female and a male installed Indian-English voice for alternating rider calls.
     * Google's en-IN voices: ahp / cxx = female, ene / end = male (names like en-in-x-ene-local).
     * Unknown names: the first two different en-IN voices; none: one voice, two pitches.
     */
    private void pickPair(Set<Voice> voices) {
        femaleVoice = maleVoice = null;
        if (voices == null) return;
        java.util.List<Voice> in = new java.util.ArrayList<Voice>();
        for (Voice v : voices) {
            Locale l = v.getLocale();
            if (l == null || !"en".equals(l.getLanguage()) || !"IN".equals(l.getCountry())) continue;
            Set<String> f = v.getFeatures();
            if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            in.add(v);
        }
        // offline voices first (work without internet), then by quality
        java.util.Collections.sort(in, new java.util.Comparator<Voice>() {
            @Override public int compare(Voice a, Voice b) {
                if (a.isNetworkConnectionRequired() != b.isNetworkConnectionRequired()) {
                    return a.isNetworkConnectionRequired() ? 1 : -1;
                }
                return b.getQuality() - a.getQuality();
            }
        });
        for (Voice v : in) {
            String n = v.getName().toLowerCase(Locale.US);
            boolean male = n.contains("ene") || n.contains("end") || n.contains("#male") || n.contains("-male");
            boolean female = n.contains("ahp") || n.contains("cxx") || n.contains("#female") || n.contains("-female");
            if (male && maleVoice == null) maleVoice = v;
            else if (female && femaleVoice == null) femaleVoice = v;
        }
        for (Voice v : in) { // fill a missing side with any other en-IN voice
            if (femaleVoice == null && v != maleVoice) femaleVoice = v;
            else if (maleVoice == null && v != femaleVoice) maleVoice = v;
        }
    }

    /** Rider calls: female, male, female, ... (Indian English, normal speed). */
    void sayAlternating(String phrase, boolean chime) {
        say(phrase, chime, true);
    }

    /** (Re)connects to the device text-to-speech engine. */
    private void startEngine() {
        ready = false;
        failed = false;
        createdAt = System.currentTimeMillis();
        final TextToSpeech[] self = new TextToSpeech[1];
        self[0] = tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (tts == null || tts != self[0]) return; // an older engine instance
                if (status != TextToSpeech.SUCCESS) {
                    failed = true;
                    return;
                }
                configure();
                ready = true;
                if (waiting != null) {
                    String s = waiting;
                    waiting = null;
                    say(s);
                }
            }
        });
    }

    /** The speech service died or never started (it can, over days of running): start it again. */
    private long restartWindowAt;
    private int restarts;

    private void restartEngine(String phrase) {
        long now = System.currentTimeMillis();
        if (now - restartWindowAt > 60_000L) {
            restartWindowAt = now;
            restarts = 0;
        }
        if (++restarts > 3) return; // engine broken right now: skip this call, try again next time
        try {
            if (tts != null) tts.shutdown();
        } catch (Throwable ignored) {
        }
        waiting = phrase;
        startEngine();
    }

    private void configure() {
        Locale india = new Locale("en", "IN");
        int r = tts.setLanguage(india);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            r = tts.setLanguage(Locale.UK);
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
        }
        try {
            // Best installed, offline English voice (Indian English first).
            Voice best = null;
            int bestScore = -1;
            Set<Voice> voices = tts.getVoices();
            if (voices != null) {
                for (Voice v : voices) {
                    Locale l = v.getLocale();
                    if (l == null || !"en".equals(l.getLanguage()) || v.isNetworkConnectionRequired()) continue;
                    Set<String> f = v.getFeatures();
                    if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                    int score = v.getQuality() + ("IN".equals(l.getCountry()) ? 1000 : "GB".equals(l.getCountry()) ? 500 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = v;
                    }
                }
            }
            if (best != null) tts.setVoice(best);
            defaultVoice = best;
            pickPair(voices);
        } catch (Throwable ignored) {
        }
        tts.setSpeechRate(1.0f); // normal speed: quick calls, digits still clear
        tts.setPitch(1.0f);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}

            @Override public void onDone(String id) { finished(); main.post(restore); }

            @Override public void onError(String id) { finished(); main.post(restore); }
        });
    }

    /** Chime now, then speak. Calls made while one is playing are queued behind it. */
    void say(String phrase) {
        say(phrase, true);
    }

    /** @param chime soft ding-dong first (new calls); repeats are spoken without it. */
    void say(final String phrase, final boolean chime) {
        say(phrase, chime, false);
    }

    private void say(final String phrase, final boolean chime, final boolean alternate) {
        if (tts == null) return;
        if (failed || (!ready && System.currentTimeMillis() - createdAt > 20_000L)) {
            restartEngine(phrase);
            return;
        }
        if (!ready) {
            waiting = phrase;
            return;
        }
        boolean busy = tts.isSpeaking();
        boolean ding = chime && !busy;
        pending.incrementAndGet();
        lastQueuedAt = System.currentTimeMillis();
        riderLast = alternate;
        if (ding) playChime();
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (tts == null) return;
                try {
                    if (alternate) {
                        boolean male = nextMale;
                        nextMale = !nextMale;
                        Voice v = male ? maleVoice : femaleVoice;
                        if (v != null && v != (male ? femaleVoice : maleVoice)) {
                            tts.setVoice(v);
                            tts.setPitch(1.0f);
                        } else {
                            if (defaultVoice != null) tts.setVoice(defaultVoice);
                            tts.setPitch(male ? 0.82f : 1.12f); // only one voice installed: two pitches
                        }
                    } else {
                        if (defaultVoice != null) tts.setVoice(defaultVoice);
                        tts.setPitch(1.0f);
                    }
                } catch (Throwable ignored) {
                }
                Bundle p = new Bundle();
                p.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
                p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
                int r;
                try {
                    r = tts.speak(phrase, TextToSpeech.QUEUE_ADD, p, "tok" + System.nanoTime());
                } catch (Throwable t) {
                    r = TextToSpeech.ERROR;
                }
                if (r == TextToSpeech.ERROR) {
                    finished();
                    restartEngine(phrase); // dead engine: reconnect, say it then
                }
            }
        }, ding ? 850 : 0);
    }

    private final Runnable restore = new Runnable() {
        @Override public void run() {
            try {
                if (tts != null && tts.isSpeaking()) {
                    main.postDelayed(this, 500);
                    return;
                }
                if (restoreVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume, 0);
            } catch (Throwable ignored) {
            }
            restoreVolume = -1;
        }
    };

    /** Soft "ding-dong" (E6 then C6) with bell-like decay, generated in code. */
    private void playChime() {
        try {
            if (chime == null) {
                int n = (int) (RATE * 0.8f);
                short[] pcm = new short[n];
                note(pcm, 0, 1318.5, 0.30f);
                note(pcm, (int) (RATE * 0.28f), 1046.5, 0.30f);
                chime = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, n * 2, AudioTrack.MODE_STATIC);
                chime.write(pcm, 0, n);
            } else {
                chime.stop();
                chime.reloadStaticData();
            }
            chime.play();
        } catch (Throwable ignored) {
        }
    }

    private static void note(short[] pcm, int from, double hz, float amp) {
        for (int i = from; i < pcm.length; i++) {
            double s = (i - from) / (double) RATE;
            double env = Math.min(1.0, s / 0.006) * Math.exp(-s * 5.0);
            double v = Math.sin(2 * Math.PI * hz * s) + 0.25 * Math.sin(4 * Math.PI * hz * s);
            int mixed = pcm[i] + (int) (v * env * amp * 0.8 * Short.MAX_VALUE);
            pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long lastDoneAt;
    private volatile boolean riderLast;

    private void finished() {
        if (pending.decrementAndGet() < 0) pending.set(0);
        lastDoneAt = System.currentTimeMillis();
    }

    /** Something is queued or being spoken (rider calls wait for it, so no backlog builds up). */
    boolean busy() {
        boolean speaking;
        try {
            speaking = tts != null && tts.isSpeaking();
        } catch (Throwable t) {
            speaking = false;
        }
        // An engine that never reported "done" must not block calls for good.
        if (!speaking && pending.get() > 0 && System.currentTimeMillis() - lastQueuedAt > 20_000L) pending.set(0);
        return speaking || pending.get() > 0;
    }

    private volatile long lastQueuedAt;

    /** Milliseconds since the last utterance finished. */
    long idleMs() {
        return lastDoneAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastDoneAt;
    }

    /** Nothing is waiting for pickup any more: cut off a rider call that is still being read. */
    void stopRiderCalls() {
        if (riderLast && busy()) stop();
    }

    void stop() {
        pending.set(0);
        try {
            if (tts != null) tts.stop();
        } catch (Throwable ignored) {
        }
        main.post(restore);
    }

    void shutdown() {
        stop();
        restore.run();
        try {
            if (tts != null) tts.shutdown();
        } catch (Throwable ignored) {
        }
        tts = null;
        try {
            if (chime != null) chime.release();
        } catch (Throwable ignored) {
        }
        chime = null;
    }
}
