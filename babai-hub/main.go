// Babai Console (formerly Babai Hub + Bridge Print): one program for any PC on the shop network.
//
// It runs as a Windows service (always on, restarts itself) and does two jobs:
//   - Bridge Print: receives Petpooja's KOT prints, adds the token and prints them, with print
//     rules and a fallback printer (pr_*.go), plus the Dispatch screen;
//   - the Hub (below): reads Petpooja, feeds the customers' page, scanners and rider screens.
//
// The window (Edge in app mode, opened at login and reopened if closed) shows the Console page.
//
// It reads the live kitchen orders from the Petpooja billing station the same way the Petpooja
// KDS app on the TV does (read-only), works out the dine-in dosa tokens exactly like the Order
// Ready TV screen, and puts them in a free Firebase Realtime Database for the customers'
// "Track your dosa" page, opened from a printed QR code. It also keeps the open KOTs, the item
// on/off list and a day-by-day archive of finished KOTs (online and on this PC) for later
// features, staying inside Firebase's free plan.
//
// Its page is at http://localhost:8790 on this PC.
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	version     = "3.9"
	defaultPort = 8790
	defaultPage = "https://babaidosa.netlify.app/"
)

// Config is saved in config.json in the data folder.
type Config struct {
	Server       string   `json:"server"` // Petpooja billing station IP (port 3000 by default)
	Outlet       string   `json:"outlet"` // outlet name exactly as on the bill, e.g. "VTR", "Sarjapur"
	Lat          float64  `json:"lat"`    // outlet location (customers' page picks the nearest outlet)
	Lng          float64  `json:"lng"`
	AutoClearMin int      `json:"autoClearMin"` // ready tokens leave after this many minutes (TV default 10)
	Paused       bool     `json:"paused"`       // tray: Pause / Resume
	DB           string   `json:"db"`           // https://xxx.firebasedatabase.app
	Secret       string   `json:"secret"`       // Firebase database secret
	Page         string   `json:"page"`         // customer page address
	Port         int      `json:"port"`
	Bridge       string   `json:"bridge,omitempty"`     // Bridge Print address (empty: found on the Petpooja PC)
	Phones       []Phone  `json:"phones,omitempty"`     // paired Babai Scanner phones
	Pin          string   `json:"pin,omitempty"`        // hub page PIN (empty = 1234)
	AutoUpdate   *bool    `json:"autoUpdate,omitempty"` // nil = on
	HideBrands   []string `json:"hideBrands,omitempty"` // brands kept off the rider screen
	HideSet      bool     `json:"hideSet,omitempty"`    // false = the default (Go Self Service)
	CXURL        string   `json:"cxUrl,omitempty"`      // ratings sheet web app (Data > Ratings sheet)
	CXKey        string   `json:"cxKey,omitempty"`      // its key
}

// Phone is one paired Babai Scanner phone. Key: its private pairing key (in the QR code).
type Phone struct {
	Key   string `json:"key"`
	Name  string `json:"name"`
	Added int64  `json:"added"`
}

var (
	dataDir string
	cfgMu   sync.Mutex
	cfg     Config
)

func main() {
	if platformMain() {
		return // service, window, installer or uninstall (Windows)
	}
	runWorker()
}

// runWorker is the Console itself (started by the service, or directly with --run).
func runWorker() {
	dataDir = appDataDir()
	_ = os.MkdirAll(filepath.Join(dataDir, "days"), 0o755)
	setupLog()
	loadConfig()
	loadPromo()
	loadUsage()

	port := cfg.Port
	if port == 0 {
		port = defaultPort
	}
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", port))
	for i := 0; err != nil && i < 40; i++ {
		time.Sleep(500 * time.Millisecond) // an old copy (or the old Babai Hub) is still closing
		if i == 2 {
			askOldHubToQuit(port)
		}
		ln, err = net.Listen("tcp", fmt.Sprintf(":%d", port))
	}
	if err != nil {
		log.Fatalf("port %d busy: %v", port, err)
	}
	log.Printf("Babai Console %s listening on :%d, data in %s", version, port, dataDir)

	startPrinting()
	go cloudLoop()
	go petpoojaLoop()
	go bridgeLoop()
	go updateLoop()
	go kdsMirrorLoop()
	go scanner.loop(2 * time.Minute)
	go tvApp.loop(150 * time.Second)
	go scanQueueLoop()
	go cxLoop()
	go metricsLoop()

	mux := http.NewServeMux()
	mux.HandleFunc("/ping", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "babai-hub "+version) })
	mux.HandleFunc("/", handleRoot)
	mux.HandleFunc("/hub", localOnly(pinned(handleHome)))
	consoleRoutes(mux)
	mux.HandleFunc("/status.json", localOnly(pinned(handleStatus)))
	mux.HandleFunc("/settings", localOnly(pinned(handleSettings)))
	mux.HandleFunc("/test", localOnly(pinned(handleTest)))
	mux.HandleFunc("/petpooja", localOnly(pinned(handlePetpooja)))
	mux.HandleFunc("/autostart", localOnly(pinned(handleAutostart)))
	mux.HandleFunc("/poster", localOnly(pinned(handlePoster)))
	mux.HandleFunc("/customer-page", localOnly(pinned(handleCustomerPage)))
	mux.HandleFunc("/pause", localOnly(pinned(handlePause)))
	mux.HandleFunc("/promo", localOnly(pinned(handlePromo)))
	mux.HandleFunc("/promo/img", localOnly(pinned(handlePromoImage)))
	mux.HandleFunc("/phones", localOnly(pinned(handlePhones)))
	mux.HandleFunc("/unlock", localOnly(handleUnlock))
	mux.HandleFunc("/lock", localOnly(handleLock))
	mux.HandleFunc("/pin", localOnly(pinned(handlePinChange)))
	mux.HandleFunc("/kds/", handleKDS) // KDS app update for the screens on the Wi-Fi
	mux.HandleFunc("/bridge/find", localOnly(pinned(handleBridgeFind)))
	mux.HandleFunc("/hide", localOnly(pinned(handleHide)))
	mux.HandleFunc("/bridge", localOnly(pinned(handleBridge)))
	// shop Wi-Fi (other devices): scanner phones and the KDS rider screen
	mux.HandleFunc("/scan", handleScan)
	mux.HandleFunc("/rider.json", handleRider)
	mux.HandleFunc("/prep/base", handlePrepBase)
	mux.HandleFunc("/prep/seed", handlePrepSeed)
	mux.HandleFunc("/prep/history", handlePrepHistory)
	mux.HandleFunc("/prepdata", localOnly(pinned(handlePrepAdmin)))
	mux.HandleFunc("/update", localOnly(pinned(handleUpdate)))
	mux.HandleFunc("/quit", localOnly(func(w http.ResponseWriter, r *http.Request) {
		if !fromOwnPage(r) {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		io.WriteString(w, `{"ok":true}`)
		go func() { time.Sleep(300 * time.Millisecond); os.Exit(0) }() // a newer copy is taking over
	}))

	srv := &http.Server{Handler: mux, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second}
	go healthMark()
	log.Fatal(srv.Serve(ln))
}

func hasArg(a string) bool {
	for _, x := range os.Args[1:] {
		if x == a {
			return true
		}
	}
	return false
}

// setPaused stops / restarts reading Petpooja and updating the cloud (tray: Pause / Resume).
func setPaused(p bool) {
	c := getConfig()
	c.Paused = p
	_ = saveConfig(c)
	if p {
		log.Printf("paused")
	} else {
		log.Printf("resumed")
		wakePoll()
	}
}

// ---- config -------------------------------------------------------------------------------

func loadConfig() {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	b, err := os.ReadFile(filepath.Join(dataDir, "config.json"))
	if err == nil {
		_ = json.Unmarshal(b, &cfg)
	}
	if cfg.Page == "" || strings.Contains(cfg.Page, "github.io") {
		cfg.Page = defaultPage
	}
	if cfg.AutoClearMin <= 0 {
		cfg.AutoClearMin = defaultClrM
	}
}

func saveConfig(c Config) error {
	cfgMu.Lock()
	cfg = c
	cfgMu.Unlock()
	b, _ := json.MarshalIndent(c, "", "  ")
	return writeFileAtomic(filepath.Join(dataDir, "config.json"), b)
}

func getConfig() Config {
	cfgMu.Lock()
	defer cfgMu.Unlock()
	return cfg
}

// configured: the cloud part is set up.
func configured() bool {
	c := getConfig()
	return c.DB != "" && c.Secret != ""
}

// outletCode: the outlet's key in the cloud ("Sarjapur" -> "sarjapur", "VTR" -> "vtr").
func outletCode(name string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(name) {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') {
			b.WriteRune(r)
		}
	}
	s := b.String()
	if len(s) > 24 {
		s = s[:24]
	}
	return s
}

// outletBase is where this outlet's data lives: /babai/o/<code>.
func outletBase() string {
	if c := outletCode(getConfig().Outlet); c != "" {
		return "/babai/o/" + c
	}
	return ""
}

// cloudReady: Firebase set up and the outlet named.
func cloudReady() bool { return configured() && outletBase() != "" }

// normaliseDB turns "babai-x-default-rtdb.asia-southeast1.firebasedatabase.app/" into
// "https://babai-x-default-rtdb.asia-southeast1.firebasedatabase.app". Only Firebase hosts.
func normaliseDB(s string) (string, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return "", nil
	}
	s = strings.TrimPrefix(strings.TrimPrefix(s, "https://"), "http://")
	if i := strings.IndexAny(s, "/?#"); i >= 0 {
		s = s[:i]
	}
	s = strings.ToLower(s)
	if !strings.HasSuffix(s, ".firebasedatabase.app") && !strings.HasSuffix(s, ".firebaseio.com") {
		return "", errors.New("the database address must end in .firebasedatabase.app or .firebaseio.com")
	}
	return "https://" + s, nil
}

// qrURL is the address in the printed QR code (and on the Order Ready TV screen).
func qrURL() string {
	c := getConfig()
	if c.DB == "" || outletCode(c.Outlet) == "" {
		return ""
	}
	page := c.Page
	if page == "" {
		page = defaultPage
	}
	sep := "?"
	if strings.Contains(page, "?") {
		sep = "&"
	}
	u := page + sep + "d=" + strings.TrimPrefix(c.DB, "https://")
	if oc := outletCode(c.Outlet); oc != "" {
		u += "&o=" + oc
	}
	return u
}

// ---- helpers ------------------------------------------------------------------------------

func writeFileAtomic(path string, b []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	err := renameRetry(tmp, path)
	if err == nil {
		return nil
	}
	if werr := os.WriteFile(path, b, 0o600); werr != nil { // still held: write over it in place
		return err
	}
	_ = os.Remove(tmp)
	return nil
}

// renameRetry: Windows refuses to replace a file another program has open (often the antivirus
// scanning it right after a write), so try again for about 3 seconds.
func renameRetry(from, to string) error {
	var err error
	for i := 0; i < 8; i++ {
		if err = os.Rename(from, to); err == nil {
			return nil
		}
		time.Sleep(time.Duration(50<<i) * time.Millisecond / 4)
	}
	return err
}

func setupLog() {
	p := filepath.Join(dataDir, "hub.log")
	if st, err := os.Stat(p); err == nil && st.Size() > 5<<20 {
		_ = os.Rename(p, p+".old")
	}
	f, err := os.OpenFile(p, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err == nil {
		log.SetOutput(io.MultiWriter(f, os.Stderr))
	}
}

// localOnly: settings and the status page only from this PC.
func localOnly(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		ip := net.ParseIP(host)
		if ip == nil || !ip.IsLoopback() {
			http.Error(w, "Open this page on the hub PC itself: http://localhost:8790", http.StatusForbidden)
			return
		}
		h(w, r)
	}
}
