package main

// Android apps on the shop Wi-Fi: the Console keeps the latest Dosa TV app and Babai Scanner app
// (from <site>/tv/ and <site>/scanner/version.json, checked against their sha256) next to the KDS
// app (kdsmirror.go, the "Rider app"),
// and the page http://<this PC>:8790/apps lets anyone on the Wi-Fi download them (QR codes).

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html"
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

// apkMirror keeps one app from <site>/<dir>/version.json on this PC and serves it on the Wi-Fi as
// /<dir>/version.json and /<dir>/app.apk.
type apkMirror struct {
	dir, label, file string // site/LAN folder, name in the log, file name for downloads
	mu               sync.Mutex
	have             kdsInfo
	path, state      string
}

var (
	scanner = &apkMirror{dir: "scanner", label: "Babai Scanner", file: "BabaiScanner.apk", state: "not checked yet"}
	// The Dosa TV app: the outlet's chosen KDS version for the Dosa TVs (no self-update, so the
	// tablets' KDS build never replaces it).
	tvApp = &apkMirror{dir: "tv", label: "Dosa TV app", file: "Petpooja_KDS_Dosa_TV.apk", state: "not checked yet"}
)

func (m *apkMirror) localDir() string { return filepath.Join(dataDir, m.dir) }

func (m *apkMirror) loop(wait time.Duration) {
	time.Sleep(wait)
	m.loadLocal()
	for {
		if autoUpdateOn() {
			m.check()
		}
		time.Sleep(30 * time.Minute)
	}
}

func (m *apkMirror) loadLocal() {
	var in kdsInfo
	if !readJSONFile(filepath.Join(m.localDir(), "version.json"), &in) || in.B <= 0 {
		return
	}
	p := filepath.Join(m.localDir(), m.dir+"-"+strconv.Itoa(in.B)+".apk")
	if st, err := os.Stat(p); err == nil && st.Size() > 0 {
		m.mu.Lock()
		m.have, m.path, m.state = in, p, fmt.Sprintf("version %d ready", in.B)
		m.mu.Unlock()
	}
}

func (m *apkMirror) status() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.state
}

func (m *apkMirror) setState(s string) {
	m.mu.Lock()
	m.state = s
	m.mu.Unlock()
}

func (m *apkMirror) check() {
	base, err := updateBase()
	if err == nil {
		base, err = base.Parse("../" + m.dir + "/")
	}
	if err != nil {
		m.setState(err.Error())
		return
	}
	resp, err := updClient.Get(base.ResolveReference(&url.URL{Path: "version.json"}).String())
	if err != nil {
		m.setState("can't check (internet?)")
		return
	}
	var in kdsInfo
	err = json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&in)
	resp.Body.Close()
	if resp.StatusCode == 404 {
		m.setState("not on the site yet")
		return
	}
	if err != nil || in.B <= 0 || in.URL == "" || len(in.SHA256) != 64 {
		m.setState(m.dir + "/version.json on the site is not right")
		return
	}
	m.mu.Lock()
	have := m.have.B
	m.mu.Unlock()
	if in.B <= have {
		return
	}
	u, err := base.Parse(in.URL)
	if err != nil || u.Scheme != "https" {
		m.setState(m.dir + "/version.json: the APK address is not right")
		return
	}
	path, size, err := apkDownload(u.String(), in.SHA256, m.localDir(), m.dir+"-"+strconv.Itoa(in.B)+".apk")
	if err != nil {
		m.setState(fmt.Sprintf("version %d found, download failed: %v", in.B, err))
		return
	}
	in.Size = size
	if b, err := json.Marshal(in); err == nil {
		_ = writeFileAtomic(filepath.Join(m.localDir(), "version.json"), b)
	}
	m.mu.Lock()
	old := m.path
	m.have, m.path, m.state = in, path, fmt.Sprintf("version %d ready", in.B)
	m.mu.Unlock()
	if old != "" && old != path {
		_ = os.Remove(old)
	}
	log.Printf("%s %d downloaded for the LAN", m.label, in.B)
}

// ServeHTTP: /<dir>/version.json and /<dir>/app.apk on the shop Wi-Fi.
func (m *apkMirror) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	m.mu.Lock()
	have, path := m.have, m.path
	m.mu.Unlock()
	if have.B <= 0 || path == "" {
		http.NotFound(w, r)
		return
	}
	switch r.URL.Path {
	case "/" + m.dir + "/version.json":
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(kdsInfo{B: have.B, URL: "app.apk", SHA256: have.SHA256, Size: have.Size})
	case "/" + m.dir + "/app.apk":
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		w.Header().Set("Content-Disposition", `attachment; filename="`+m.file+`"`)
		http.ServeFile(w, r, path)
	default:
		http.NotFound(w, r)
	}
}

func (m *apkMirror) info() (kdsInfo, string, string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.have, m.path, m.state
}

func scannerStatus() string { return scanner.status() }

func apkDownload(u, sum, dir, name string) (string, int64, error) {
	resp, err := updClient.Get(u)
	if err != nil {
		return "", 0, errors.New("internet?")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", 0, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	_ = os.MkdirAll(dir, 0o755)
	path := filepath.Join(dir, name)
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

type appInfo struct {
	Name, What, Path, Size, State string
	Build                         int
	Ready                         bool
}

func appsList() []appInfo {
	kdsMu.Lock()
	k, kp, ks := kdsHave, kdsPath, kdsState
	kdsMu.Unlock()
	t, tp, ts := tvApp.info()
	s, sp, ss := scanner.info()
	size := func(n int64) string {
		if n <= 0 {
			return ""
		}
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	}
	// The Dosa TV app and the Rider app are both the KDS app: a screen has one or the other, and
	// installing one replaces the other (settings are kept).
	return []appInfo{
		{Name: "Dosa TV app", What: "For the Dosa TVs: Dosa Live and Dosa Order Status (the outlet's chosen version, fast sync). Doesn't update itself; install new versions from here.",
			Path: "/tv/app.apk", Build: t.B, Size: size(t.Size), Ready: t.B > 0 && tp != "", State: ts},
		{Name: "Rider app", What: "For the rider screens and kitchen tablets: KDS, rider screen (Swiggy / Zomato / Ownly with slots), Dosa screens and Prep Live. Updates itself after this.",
			Path: "/kds/app.apk", Build: k.B, Size: size(k.Size), Ready: k.B > 0 && kp != "", State: ks},
		{Name: "Scanner app", What: "Phones and Android boxes that scan KOT barcodes to mark Food Ready.",
			Path: "/scanner/app.apk", Build: s.B, Size: size(s.Size), Ready: s.B > 0 && sp != "", State: ss},
	}
}

// handleApps: the download page for phones and TVs on the shop Wi-Fi (no PIN: anyone may install).
func handleApps(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/apps" {
		http.NotFound(w, r)
		return
	}
	host := r.Host
	var b strings.Builder
	for _, a := range appsList() {
		link := "http://" + host + a.Path
		b.WriteString(`<section><h2>` + html.EscapeString(a.Name) + `</h2><p>` + html.EscapeString(a.What) + `</p>`)
		if a.Ready {
			b.WriteString(`<div class="row"><div class="qr" data-qr="` + html.EscapeString(link) + `"></div><div><a class="btn" href="` + html.EscapeString(a.Path) + `">Download (build ` + strconv.Itoa(a.Build) + `, ` + html.EscapeString(a.Size) + `)</a><p class="mute">Or scan the code with the phone's camera.<br>` + html.EscapeString(link) + `</p></div></div>`)
		} else {
			b.WriteString(`<p class="mute">Not on this PC yet (` + html.EscapeString(a.State) + `). It downloads by itself from the Babai site when the PC is online.</p>`)
		}
		b.WriteString(`</section>`)
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	io.WriteString(w, strings.Replace(appsHTML, "__APPS__", b.String(), 1))
}

const appsHTML = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Babai apps</title><style>
body{margin:0;background:#f6f7f9;color:#1b2430;font:16px/1.5 "Segoe UI",system-ui,sans-serif;padding:20px 16px 40px}
main{max-width:720px;margin:auto}h1{font-size:1.5rem;margin:0 0 4px}h1 span{color:#d97706}
section{background:#fff;border:1px solid #e3e7ec;border-radius:14px;padding:16px 18px;margin-top:14px}
h2{margin:0 0 4px;font-size:1.1rem}.row{display:flex;gap:16px;align-items:center;flex-wrap:wrap;margin-top:10px}
.qr{background:#fff;padding:6px;border:1px solid #e3e7ec;border-radius:10px;min-width:150px;min-height:150px}
.btn{display:inline-block;background:#d97706;color:#fff;font-weight:700;text-decoration:none;padding:11px 18px;border-radius:10px}
.mute{color:#5b6573;font-size:.9rem;margin:8px 0 0;word-break:break-all}
ol{padding-left:20px;color:#5b6573;font-size:.92rem}
</style></head><body><main><h1><span>Babai</span> apps</h1>
<p class="mute">For phones, tablets and Android TVs on the shop Wi-Fi. The Dosa TV app and the Rider app are both the KDS app: a screen has one of them, installing the other replaces it.</p>__APPS__
<section><h2>Installing</h2><ol><li>Open this page on the phone or TV (or scan the code).</li><li>Tap Download, then open the file.</li><li>If Android asks, allow "Install unknown apps" for the browser, then tap Install.</li></ol></section>
</main><script src="/console/qrcode.min.js"></script><script>
document.querySelectorAll("[data-qr]").forEach(function(el){try{new QRCode(el,{text:el.getAttribute("data-qr"),width:150,height:150,correctLevel:QRCode.CorrectLevel.M})}catch(e){}});
</script></body></html>`
