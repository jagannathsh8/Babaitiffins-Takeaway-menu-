package com.babai.screens;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import java.util.Locale;

/**
 * Calls on the screen: the TV's own voice when it has one; otherwise the call made by the Console PC
 * (/screens/voice.wav, the Windows voice); otherwise a chime. The Panasonic kiosk TV has no voice
 * engine, so it plays the Console's.
 */
final class Voice implements TextToSpeech.OnInitListener {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean ttsOk;
    private MediaPlayer mp;
    private volatile long busyUntil;

    Voice(Context c) {
        try {
            tts = new TextToSpeech(c.getApplicationContext(), this);
        } catch (Throwable t) {
            tts = null;
        }
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) return;
        try {
            int r = tts.setLanguage(new Locale("en", "IN"));
            if (r < 0) r = tts.setLanguage(Locale.US);
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
            ttsOk = r >= 0;
            if (ttsOk) useVoice(wanted);
        } catch (Throwable t) {
            ttsOk = false;
        }
    }

    // The Dosa Board app: speed, pitch and which of the TV's voices (chosen on its laptop page).
    private float rate = 0.9f, pitch = 1f;
    private String wanted = "";

    void tune(float rate, float pitch, String voiceName) {
        this.rate = rate;
        this.pitch = pitch;
        this.wanted = voiceName == null ? "" : voiceName;
        if (!ttsOk) return;
        try {
            tts.setSpeechRate(rate);
            tts.setPitch(pitch);
            useVoice(wanted);
        } catch (Throwable ignored) {
        }
    }

    /** The TV's English voices, Indian English first (names as the TV's voice engine gives them). */
    java.util.List<String> voices() {
        java.util.List<String> in = new java.util.ArrayList<String>(), other = new java.util.ArrayList<String>();
        if (!ttsOk || android.os.Build.VERSION.SDK_INT < 21) return in;
        try {
            for (android.speech.tts.Voice v : tts.getVoices()) {
                Locale l = v.getLocale();
                if (l == null || !"en".equals(l.getLanguage())) continue;
                if ("IN".equals(l.getCountry())) in.add(v.getName()); else other.add(v.getName());
            }
        } catch (Throwable ignored) {
        }
        java.util.Collections.sort(in);
        java.util.Collections.sort(other);
        in.addAll(other);
        return in;
    }

    /** The chosen voice, else a female Indian English one when the engine says so, else the default. */
    private void useVoice(String name) {
        if (android.os.Build.VERSION.SDK_INT < 21) return;
        try {
            android.speech.tts.Voice best = null;
            int bestScore = -1;
            for (android.speech.tts.Voice v : tts.getVoices()) {
                if (!name.isEmpty() && v.getName().equals(name)) { tts.setVoice(v); return; }
                Locale l = v.getLocale();
                if (l == null || !"en".equals(l.getLanguage()) || !"IN".equals(l.getCountry())) continue;
                String n = v.getName().toLowerCase(Locale.US);
                int sc = 1 + (n.contains("female") ? 4 : 0) - (n.contains("male") && !n.contains("female") ? 3 : 0) + (v.isNetworkConnectionRequired() ? 0 : 1);
                if (v.getFeatures() != null && v.getFeatures().contains("notInstalled")) sc -= 5;
                if (sc > bestScore) { bestScore = sc; best = v; }
            }
            if (best != null && name.isEmpty() && best.getName().toLowerCase(Locale.US).contains("female")) tts.setVoice(best);
        } catch (Throwable ignored) {
        }
    }

    String engine() { return ttsOk ? "TV voice" : "Console voice"; }

    void say(final String text, final String wav, final boolean chime) {
        h.post(new Runnable() {
            @Override public void run() {
                long delay = 0;
                if (chime) { tone(); delay = 450; }
                h.postDelayed(new Runnable() {
                    @Override public void run() { speakNow(text, wav); }
                }, delay);
            }
        });
    }

    private void speakNow(String text, String wav) {
        busyUntil = System.currentTimeMillis() + 1500 + text.length() * 80L;
        if (ttsOk) {
            try {
                if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, "c" + System.nanoTime()) == TextToSpeech.SUCCESS) return;
            } catch (Throwable ignored) {
            }
        }
        play(wav);
    }

    private void play(String wav) {
        try {
            if (mp != null) mp.release();
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDataSource(wav);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override public void onPrepared(MediaPlayer p) { p.start(); }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override public boolean onError(MediaPlayer p, int a, int b) { tone(); h.postDelayed(new Runnable() { @Override public void run() { tone(); } }, 500); return true; }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer p) { busyUntil = 0; }
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            tone();
        }
    }

    private void tone() {
        try {
            final ToneGenerator g = new ToneGenerator(AudioManager.STREAM_MUSIC, 90);
            g.startTone(ToneGenerator.TONE_PROP_BEEP2, 350);
            h.postDelayed(new Runnable() { @Override public void run() { g.release(); } }, 600);
        } catch (Throwable ignored) {
        }
    }

    boolean speaking() {
        try {
            if (ttsOk && tts.isSpeaking()) return true;
            if (mp != null && mp.isPlaying()) return true;
        } catch (Throwable ignored) {
        }
        return System.currentTimeMillis() < busyUntil && !ttsOk && mp != null;
    }

    void shutdown() {
        try { if (tts != null) tts.shutdown(); } catch (Throwable ignored) { }
        try { if (mp != null) mp.release(); } catch (Throwable ignored) { }
    }
}
