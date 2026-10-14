package main

// Local copy of the KDS app update for the tablets and TVs on the shop Wi-Fi. The hub reads
// <customer page>/kds/version.json {"b": build, "url": "...apk", "sha256": "..."}, downloads a
// newer APK once (checked against its sha256) and serves it on the LAN:
//
//	GET /kds/version.json   {"b", "url": "app.apk", "sha256", "size"}   (404 until it has one)
//	GET /kds/app.apk
//
// The KDS app asks the hub first and the Netlify site second, so ten TVs don't each download it
// over the internet. The app checks the sha256 again itself before installing.

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const kdsMaxSize = 300 << 20

type kdsInfo struct {
	B      int    `json:"b"`
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size,omitempty"`
}

var (
	kdsMu    sync.Mutex
	kdsHave  kdsInfo // what /kds/app.apk serves (B == 0: nothing yet)
	kdsPath  string
	kdsState = "not checked yet"
)

func kdsBase() (*url.URL, error) {
	b, err := updateBase() // .../hub/
	if err != nil {
		return nil, err
	}
	return b.Parse("../kds/")
}

func kdsMirrorLoop() {
	time.Sleep(90 * time.Second)
	kdsLoadLocal()
	for {
		if autoUpdateOn() {
			kdsCheck()
		}
		time.Sleep(30 * time.Minute)
	}
}

// kdsLoadLocal: after a restart, serve the APK already downloaded.
func kdsLoadLocal() {
	b, err := os.ReadFile(filepath.Join(dataDir, "kds", "version.json"))
	if err != nil {
		return
	}
	var in kdsInfo
	if json.Unmarshal(b, &in) != nil || in.B <= 0 {
		return
	}
	p := filepath.Join(dataDir, "kds", "kds-"+strconv.Itoa(in.B)+".apk")
	if st, err := os.Stat(p); err == nil && st.Size() > 0 {
		kdsMu.Lock()
		kdsHave, kdsPath, kdsState = in, p, fmt.Sprintf("build %d ready for the KDS screens", in.B)
		kdsMu.Unlock()
	}
}

func kdsCheck() {
	base, err := kdsBase()
	if err != nil {
		kdsSetState(err.Error())
		return
	}
	resp, err := updClient.Get(base.ResolveReference(&url.URL{Path: "version.json"}).String())
	if err != nil {
		kdsSetState("can't check (internet?)")
		return
	}
	var in kdsInfo
	err = json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&in)
	resp.Body.Close()
	if resp.StatusCode == 404 {
		kdsSetState("no KDS update on the site")
		return
	}
	if err != nil || in.B <= 0 || in.URL == "" || len(in.SHA256) != 64 {
		kdsSetState("kds/version.json on the site is not right")
		return
	}
	kdsMu.Lock()
	have := kdsHave.B
	kdsMu.Unlock()
	if in.B <= have {
		return
	}
	u, err := base.Parse(in.URL)
	if err != nil || u.Scheme != "https" {
		kdsSetState("kds/version.json: the APK address is not right")
		return
	}
	path, size, err := kdsDownload(u.String(), in.SHA256, in.B)
	if err != nil {
		kdsSetState(fmt.Sprintf("build %d found, download failed: %v", in.B, err))
		return
	}
	in.Size = size
	if b, err := json.Marshal(in); err == nil {
		_ = writeFileAtomic(filepath.Join(dataDir, "kds", "version.json"), b)
	}
	kdsMu.Lock()
	old := kdsPath
	kdsHave, kdsPath, kdsState = in, path, fmt.Sprintf("build %d ready for the KDS screens", in.B)
	kdsMu.Unlock()
	if old != "" && old != path {
		_ = os.Remove(old)
	}
	log.Printf("KDS build %d downloaded for the LAN", in.B)
}

func kdsSetState(s string) {
	kdsMu.Lock()
	kdsState = s
	kdsMu.Unlock()
}

func kdsStatus() string {
	kdsMu.Lock()
	defer kdsMu.Unlock()
	return kdsState
}

func kdsDownload(u, sum string, b int) (string, int64, error) {
	resp, err := updClient.Get(u)
	if err != nil {
		return "", 0, errors.New("internet?")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", 0, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	dir := filepath.Join(dataDir, "kds")
	_ = os.MkdirAll(dir, 0o755)
	path := filepath.Join(dir, "kds-"+strconv.Itoa(b)+".apk")
	tmp := path + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return "", 0, err
	}
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(resp.Body, kdsMaxSize+1))
	f.Close()
	if err != nil || n > kdsMaxSize {
		os.Remove(tmp)
		return "", 0, errors.New("download broken")
	}
	if !strings.EqualFold(hex.EncodeToString(h.Sum(nil)), sum) {
		os.Remove(tmp)
		return "", 0, errors.New("the file doesn't match its sha256")
	}
	if err := os.Rename(tmp, path); err != nil {
		return "", 0, err
	}
	return path, n, nil
}

// handleKDS serves the update to the KDS screens on the shop Wi-Fi (read-only).
func handleKDS(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	kdsMu.Lock()
	have, path := kdsHave, kdsPath
	kdsMu.Unlock()
	if have.B <= 0 || path == "" {
		http.NotFound(w, r)
		return
	}
	switch r.URL.Path {
	case "/kds/version.json":
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(kdsInfo{B: have.B, URL: "app.apk", SHA256: have.SHA256, Size: have.Size})
	case "/kds/app.apk":
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		http.ServeFile(w, r, path)
	default:
		http.NotFound(w, r)
	}
}
