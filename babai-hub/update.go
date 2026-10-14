package main

// Automatic updates. Once a day the hub reads <customer page>/hub/version.json, e.g.
//
//	https://babaidosa.netlify.app/hub/version.json   {"v": "2.2", "url": "babai-hub.exe", "sha256": "..."}
//
// When it names a newer version, the exe is downloaded next to it, checked against the sha256,
// and started between 3 and 5 at night (or at once with "Update now" on the page). The new exe
// installs itself the usual way: it stops this hub, replaces it and starts; settings stay.
// Can be switched off on the hub page.

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
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	updateEvery   = 24 * time.Hour
	updateFirst   = 10 * time.Minute
	updateMaxSize = 60 << 20
)

type updateInfo struct {
	V      string `json:"v"`
	URL    string `json:"url"`
	SHA256 string `json:"sha256"`
	Notes  string `json:"notes"`
}

var (
	updMu      sync.Mutex
	updReady   string // downloaded, checked exe waiting to be started
	updReadyV  string
	updState   = "not checked yet"
	updChecked time.Time
	updWake    = make(chan bool, 1) // true = install at once
	updClient  = &http.Client{Timeout: 5 * time.Minute}
)

func autoUpdateOn() bool {
	c := getConfig()
	return c.AutoUpdate == nil || *c.AutoUpdate
}

// newerVersion: "2.10" > "2.9"; non-numbers count as 0.
func newerVersion(a, b string) bool {
	pa, pb := strings.Split(strings.TrimPrefix(a, "v"), "."), strings.Split(strings.TrimPrefix(b, "v"), ".")
	for i := 0; i < len(pa) || i < len(pb); i++ {
		var x, y int
		if i < len(pa) {
			x, _ = strconv.Atoi(pa[i])
		}
		if i < len(pb) {
			y, _ = strconv.Atoi(pb[i])
		}
		if x != y {
			return x > y
		}
	}
	return false
}

func updateBase() (*url.URL, error) {
	p := strings.TrimSpace(getConfig().Page)
	if p == "" {
		p = defaultPage
	}
	u, err := url.Parse(p)
	if err != nil || u.Scheme != "https" || u.Host == "" {
		return nil, errors.New("the customer page address (Step 2) is not an https address")
	}
	u.Path = strings.TrimSuffix(u.Path, "index.html")
	if !strings.HasSuffix(u.Path, "/") {
		u.Path += "/"
	}
	u.RawQuery, u.Fragment = "", ""
	return u.ResolveReference(&url.URL{Path: "hub/"}), nil
}

func updateLoop() {
	time.Sleep(updateFirst)
	for {
		now := time.Now()
		updMu.Lock()
		due := autoUpdateOn() && (updChecked.IsZero() || now.Sub(updChecked) >= updateEvery)
		ready := updReady != ""
		updMu.Unlock()
		if due {
			checkUpdate()
		}
		if ready && autoUpdateOn() && now.Hour() >= 3 && now.Hour() < 5 {
			installUpdate()
		}
		select {
		case <-time.After(10 * time.Minute):
		case now := <-updWake:
			checkUpdate()
			if now {
				installUpdate()
			}
		}
	}
}

func setUpdState(s string) {
	updMu.Lock()
	updState = s
	updMu.Unlock()
}

// checkUpdate reads version.json and downloads a newer exe (checked) when there is one.
func checkUpdate() {
	updMu.Lock()
	updChecked = time.Now()
	updMu.Unlock()
	base, err := updateBase()
	if err != nil {
		setUpdState(err.Error())
		return
	}
	resp, err := updClient.Get(base.ResolveReference(&url.URL{Path: "version.json"}).String())
	if err != nil {
		setUpdState("can't check (internet?)")
		return
	}
	var info updateInfo
	err = json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&info)
	resp.Body.Close()
	if resp.StatusCode == 404 {
		setUpdState("up to date (" + version + ")")
		return
	}
	if err != nil || info.V == "" || info.URL == "" || len(info.SHA256) != 64 {
		setUpdState("version.json on the site is not right")
		return
	}
	if !newerVersion(info.V, version) {
		setUpdState("up to date (" + version + ")")
		return
	}
	updMu.Lock()
	have := updReadyV == info.V && updReady != ""
	updMu.Unlock()
	if have {
		return
	}
	exeURL, err := base.Parse(info.URL)
	if err != nil || exeURL.Scheme != "https" {
		setUpdState("version.json: the exe address is not right")
		return
	}
	path, err := downloadChecked(exeURL.String(), info.SHA256, info.V)
	if err != nil {
		setUpdState("version " + info.V + " found, download failed: " + err.Error())
		return
	}
	updMu.Lock()
	updReady, updReadyV = path, info.V
	updState = "version " + info.V + " downloaded · installs between 3 and 5 at night (or Update now)"
	updMu.Unlock()
	log.Printf("update %s downloaded and checked", info.V)
}

func downloadChecked(u, sum, v string) (string, error) {
	resp, err := updClient.Get(u)
	if err != nil {
		return "", errors.New("internet?")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	dir := filepath.Join(dataDir, "update")
	_ = os.MkdirAll(dir, 0o755)
	path := filepath.Join(dir, "babai-hub-"+strings.NewReplacer("/", "", "\\", "", "..", "").Replace(v)+".exe")
	tmp := path + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return "", err
	}
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(f, h), io.LimitReader(resp.Body, updateMaxSize+1))
	f.Close()
	if err != nil || n > updateMaxSize {
		os.Remove(tmp)
		return "", errors.New("download broken")
	}
	if !strings.EqualFold(hex.EncodeToString(h.Sum(nil)), sum) {
		os.Remove(tmp)
		return "", errors.New("the file doesn't match its sha256 (not installed)")
	}
	if err := os.Rename(tmp, path); err != nil {
		return "", err
	}
	return path, nil
}

// installUpdate starts the downloaded exe; it replaces this hub (see ensureInstalled).
func installUpdate() {
	updMu.Lock()
	path, v := updReady, updReadyV
	updMu.Unlock()
	if path == "" {
		return
	}
	if runtime.GOOS != "windows" {
		setUpdState("version " + v + " downloaded (installs itself on Windows only)")
		return
	}
	log.Printf("installing update %s", v)
	setUpdState("installing version " + v + "…")
	if err := exec.Command(path, "--restart").Start(); err != nil {
		setUpdState("couldn't start the update: " + err.Error())
		return
	}
	updMu.Lock()
	updReady = ""
	updMu.Unlock()
}

// handleUpdate (hub page): GET status; POST {op: "check"|"now"|"on"|"off"}.
func handleUpdate(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		kds := kdsStatus()
		updMu.Lock()
		st := map[string]any{"version": version, "state": updState, "on": autoUpdateOn(), "ready": updReadyV, "kds": kds}
		if !updChecked.IsZero() {
			st["checked"] = updChecked.UnixMilli()
		}
		updMu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(st)
		return
	}
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Op string }
	_ = json.NewDecoder(io.LimitReader(r.Body, 512)).Decode(&in)
	switch in.Op {
	case "on", "off":
		c := getConfig()
		on := in.Op == "on"
		c.AutoUpdate = &on
		if err := saveConfig(c); err != nil {
			reply(w, false, err.Error())
			return
		}
		reply(w, true, map[bool]string{true: "Automatic updates on.", false: "Automatic updates off."}[on])
	case "check", "now":
		go kdsCheck()
		select {
		case updWake <- in.Op == "now":
		default:
		}
		reply(w, true, map[bool]string{true: "Checking, and installing if there is a new version…", false: "Checking…"}[in.Op == "now"])
	default:
		reply(w, false, "Unknown change.")
	}
}
