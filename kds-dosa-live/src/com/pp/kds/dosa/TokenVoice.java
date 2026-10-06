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

    TokenVoice(Context c) {
        app = c.getApplicationContext();
        audio = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        tts = new TextToSpeech(app, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS || tts == null) return;
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
        } catch (Throwable ignored) {
        }
        tts.setSpeechRate(0.92f); // brisk but every digit clear
        tts.setPitch(1.0f);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}

            @Override public void onDone(String id) { main.post(restore); }

            @Override public void onError(String id) { main.post(restore); }
        });
    }

    /** Chime now, then speak. Calls made while one is playing are queued behind it. */
    void say(final String phrase) {
        if (tts == null) return;
        if (!ready) {
            waiting = phrase;
            return;
        }
        boolean busy = tts.isSpeaking();
        if (!busy) playChime();
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (tts == null) return;
                Bundle p = new Bundle();
                p.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
                p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f);
                tts.speak(phrase, TextToSpeech.QUEUE_ADD, p, "tok" + System.nanoTime());
            }
        }, busy ? 0 : 850);
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

    void stop() {
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
