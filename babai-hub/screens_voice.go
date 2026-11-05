package main

// Voice calls made on this PC for screens without a voice of their own (the Panasonic kiosk TV has
// no text-to-speech engine): GET /screens/voice.wav?t=Swiggy 3 1 3 6, slot 47.
// Windows' own voice (System.Speech; Config.Voice picks one), kept in voice/ so a call is made once.
// Only letters, digits and simple punctuation are spoken.

import (
	"crypto/sha1"
	"encoding/hex"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
)

var voiceMu sync.Mutex

func voiceText(s string) string {
	s = strings.Map(func(r rune) rune {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', strings.ContainsRune(" ,.-!?", r):
			return r
		}
		return ' '
	}, s)
	s = strings.Join(strings.Fields(s), " ")
	if len(s) > 300 {
		s = s[:300]
	}
	return s
}

func handleScreenVoice(w http.ResponseWriter, r *http.Request) {
	t := voiceText(r.URL.Query().Get("t"))
	if t == "" || runtime.GOOS != "windows" {
		http.NotFound(w, r)
		return
	}
	voice := voiceText(getConfig().Voice)
	h := sha1.Sum([]byte(voice + "|" + t))
	dir := filepath.Join(dataDir, "voice")
	path := filepath.Join(dir, hex.EncodeToString(h[:])+".wav")
	if st, err := os.Stat(path); err != nil || st.Size() < 100 {
		voiceMu.Lock()
		err := makeVoice(dir, path, voice, t)
		voiceMu.Unlock()
		if err != nil {
			http.Error(w, "no voice on this PC", http.StatusServiceUnavailable)
			return
		}
	}
	w.Header().Set("Content-Type", "audio/wav")
	w.Header().Set("Cache-Control", "max-age=86400")
	http.ServeFile(w, r, path)
}

func makeVoice(dir, path, voice, text string) error {
	if st, err := os.Stat(path); err == nil && st.Size() >= 100 {
		return nil
	}
	_ = os.MkdirAll(dir, 0o755)
	pruneVoice(dir)
	tmp := path + ".part.wav"
	// text and voice go in through the environment, never into the script
	ps := `Add-Type -AssemblyName System.Speech; $s = New-Object System.Speech.Synthesis.SpeechSynthesizer; ` +
		`if ($env:BABAI_VOICE) { try { $s.SelectVoice($env:BABAI_VOICE) } catch {} }; $s.Rate = -1; ` +
		`$s.SetOutputToWaveFile($env:BABAI_OUT); $s.Speak($env:BABAI_TEXT); $s.Dispose()`
	cmd := hiddenCmd("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", ps)
	cmd.Env = append(os.Environ(), "BABAI_TEXT="+text, "BABAI_VOICE="+voice, "BABAI_OUT="+tmp)
	done := make(chan error, 1)
	if err := cmd.Start(); err != nil {
		return err
	}
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			os.Remove(tmp)
			return err
		}
	case <-time.After(20 * time.Second):
		_ = cmd.Process.Kill()
		os.Remove(tmp)
		return os.ErrDeadlineExceeded
	}
	return os.Rename(tmp, path)
}

// pruneVoice keeps the folder small (calls change with every order).
func pruneVoice(dir string) {
	es, _ := os.ReadDir(dir)
	if len(es) < 500 {
		return
	}
	cut := time.Now().Add(-24 * time.Hour)
	for _, e := range es {
		if fi, err := e.Info(); err == nil && fi.ModTime().Before(cut) {
			os.Remove(filepath.Join(dir, e.Name()))
		}
	}
}
