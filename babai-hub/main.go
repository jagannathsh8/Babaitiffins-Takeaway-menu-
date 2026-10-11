// Babai Hub: a small program for any PC on the shop network.
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
	version     = "2.0"
	defaultPort = 8790
	defaultPage = "https://babaidosa.netlify.app/"
)

// Config is saved in config.json in the data folder.
type Config struct {
	Server       string `json:"server"`       // Petpooja billing station IP (port 3000 by default)
	AutoClearMin int    `json:"autoClearMin"` // ready tokens leave after this many minutes (TV default 10)
	DB           string `json:"db"`           // https://xxx.firebasedatabase.app
	Secret       string `json:"secret"`       // Firebase database secret
	Page         string `json:"page"`         // customer page address
	Port         int    `json:"port"`
}

var (
	dataDir string
	cfgMu   sync.Mutex
	cfg     Config
)

func main() {
	dataDir = appDataDir()
	_ = os.MkdirAll(filepath.Join(dataDir, "days"), 0o755)
	setupLog()
	loadConfig()
	loadUsage()

	port := cfg.Port
	if port == 0 {
		port = defaultPort
	}
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		// Already running (or the port is taken): just show the page of the running hub.
		log.Printf("port %d busy: %v", port, err)
		openBrowser(fmt.Sprintf("http://localhost:%d/", port))
		return
	}
	log.Printf("Babai Hub %s listening on :%d, data in %s", version, port, dataDir)

	go cloudLoop()
	go petpoojaLoop()

	mux := http.NewServeMux()
	mux.HandleFunc("/ping", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "babai-hub "+version) })
	mux.HandleFunc("/", localOnly(handleHome))
	mux.HandleFunc("/status.json", localOnly(handleStatus))
	mux.HandleFunc("/settings", localOnly(handleSettings))
	mux.HandleFunc("/test", localOnly(handleTest))
	mux.HandleFunc("/petpooja", localOnly(handlePetpooja))
	mux.HandleFunc("/autostart", localOnly(handleAutostart))
	mux.HandleFunc("/poster", localOnly(handlePoster))
	mux.HandleFunc("/customer-page", localOnly(handleCustomerPage))

	if !configured() || getConfig().Server == "" {
		go func() {
			time.Sleep(800 * time.Millisecond)
			openBrowser(fmt.Sprintf("http://localhost:%d/", port))
		}()
	}
	srv := &http.Server{Handler: mux, ReadTimeout: 15 * time.Second, WriteTimeout: 30 * time.Second}
	log.Fatal(srv.Serve(ln))
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

func cloudConfigured() bool { return configured() }

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
	if c.DB == "" {
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
	return page + sep + "d=" + strings.TrimPrefix(c.DB, "https://")
}

// ---- helpers ------------------------------------------------------------------------------

func writeFileAtomic(path string, b []byte) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
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
