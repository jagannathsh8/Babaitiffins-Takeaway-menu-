package main

// Android apps on the shop Wi-Fi: the Console keeps the latest Babai Scanner app (from
// <site>/scanner/version.json, checked against its sha256) next to the KDS app (kdsmirror.go),
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

var (
	scMu    sync.Mutex
	scHave  kdsInfo
	scPath  string
	scState = "not checked yet"
)

func scannerDir() string { return filepath.Join(dataDir, "scanner") }

func scannerMirrorLoop() {
	time.Sleep(2 * time.Minute)
	scannerLoadLocal()
	for {
		if autoUpdateOn() {
			scannerCheck()
		}
		time.Sleep(30 * time.Minute)
	}
}

func scannerLoadLocal() {
	var in kdsInfo
	if !readJSONFile(filepath.Join(scannerDir(), "version.json"), &in) || in.B <= 0 {
		return
	}
	p := filepath.Join(scannerDir(), "scanner-"+strconv.Itoa(in.B)+".apk")
	if st, err := os.Stat(p); err == nil && st.Size() > 0 {
		scMu.Lock()
		scHave, scPath, scState = in, p, fmt.Sprintf("version %d ready", in.B)
		scMu.Unlock()
	}
}

func scannerStatus() string {
	scMu.Lock()
	defer scMu.Unlock()
	return scState
}

func scannerSetState(s string) {
	scMu.Lock()
	scState = s
	scMu.Unlock()
}

func scannerCheck() {
	base, err := updateBase()
	if err == nil {
		base, err = base.Parse("../scanner/")
	}
	if err != nil {
		scannerSetState(err.Error())
		return
	}
	resp, err := updClient.Get(base.ResolveReference(&url.URL{Path: "version.json"}).String())
	if err != nil {
		scannerSetState("can't check (internet?)")
		return
	}
	var in kdsInfo
	err = json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&in)
	resp.Body.Close()
	if resp.StatusCode == 404 {
		scannerSetState("no scanner app on the site yet")
		return
	}
	if err != nil || in.B <= 0 || in.URL == "" || len(in.SHA256) != 64 {
		scannerSetState("scanner/version.json on the site is not right")
		return
	}
	scMu.Lock()
	have := scHave.B
	scMu.Unlock()
	if in.B <= have {
		return
	}
	u, err := base.Parse(in.URL)
	if err != nil || u.Scheme != "https" {
		scannerSetState("scanner/version.json: the APK address is not right")
		return
	}
	path, size, err := apkDownload(u.String(), in.SHA256, scannerDir(), "scanner-"+strconv.Itoa(in.B)+".apk")
	if err != nil {
		scannerSetState(fmt.Sprintf("version %d found, download failed: %v", in.B, err))
		return
	}
	in.Size = size
	if b, err := json.Marshal(in); err == nil {
		_ = writeFileAtomic(filepath.Join(scannerDir(), "version.json"), b)
	}
	scMu.Lock()
	old := scPath
	scHave, scPath, scState = in, path, fmt.Sprintf("version %d ready", in.B)
	scMu.Unlock()
	if old != "" && old != path {
		_ = os.Remove(old)
	}
	log.Printf("Babai Scanner %d downloaded for the LAN", in.B)
}

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

// handleScannerApp: /scanner/version.json and /scanner/app.apk on the shop Wi-Fi.
func handleScannerApp(w http.ResponseWriter, r *http.Request) {
	scMu.Lock()
	have, path := scHave, scPath
	scMu.Unlock()
	if have.B <= 0 || path == "" {
		http.NotFound(w, r)
		return
	}
	switch r.URL.Path {
	case "/scanner/version.json":
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(kdsInfo{B: have.B, URL: "app.apk", SHA256: have.SHA256, Size: have.Size})
	case "/scanner/app.apk":
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		w.Header().Set("Content-Disposition", `attachment; filename="BabaiScanner.apk"`)
		http.ServeFile(w, r, path)
	default:
		http.NotFound(w, r)
	}
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
	scMu.Lock()
	s, sp, ss := scHave, scPath, scState
	scMu.Unlock()
	size := func(n int64) string {
		if n <= 0 {
			return ""
		}
		return fmt.Sprintf("%.1f MB", float64(n)/(1<<20))
	}
	return []appInfo{
		{Name: "Android KDS (Dosa Live)", What: "Kitchen screens, Dosa Live, Order Ready TV, rider screen and Prep Live. Updates itself after this.",
			Path: "/kds/app.apk", Build: k.B, Size: size(k.Size), Ready: k.B > 0 && kp != "", State: ks},
		{Name: "Babai Scanner", What: "Phones and Android boxes that scan KOT barcodes to mark Food Ready.",
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
<p class="mute">For phones, tablets and Android TVs on the shop Wi-Fi.</p>__APPS__
<section><h2>Installing</h2><ol><li>Open this page on the phone or TV (or scan the code).</li><li>Tap Download, then open the file.</li><li>If Android asks, allow "Install unknown apps" for the browser, then tap Install.</li></ol></section>
</main><script src="/console/qrcode.min.js"></script><script>
document.querySelectorAll("[data-qr]").forEach(function(el){try{new QRCode(el,{text:el.getAttribute("data-qr"),width:150,height:150,correctLevel:QRCode.CorrectLevel.M})}catch(e){}});
</script></body></html>`
