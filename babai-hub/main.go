// Babai Hub: a small program for a shop PC.
//
// The kitchen TV (Petpooja KDS with Dosa Live) sends its live data here over the shop Wi-Fi:
// the dine-in dosa token status and the open KOTs. The hub
//   - puts the token status online (Firebase Realtime Database) for the customers'
//     "Track your dosa" page, opened from a printed QR code,
//   - keeps a copy of the open KOTs online and a day-by-day archive of finished KOTs
//     (online and in local files on this PC) for later features such as reports and indent,
//   - stays inside Firebase's free plan (small, throttled writes; old days are pruned).
//
// The hub never talks to Petpooja. Its page is at http://localhost:8790 on this PC.
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
	version     = "1.0"
	defaultPort = 8790
	defaultPage = "https://jagannathsh8.github.io/Babaitiffins-Takeaway-menu-/dosa/"
)

// Config is saved in config.json in the data folder.
type Config struct {
	DB     string `json:"db"`     // https://xxx.firebasedatabase.app
	Secret string `json:"secret"` // Firebase database secret
	Page   string `json:"page"`   // customer page address
	Port   int    `json:"port"`
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

	mux := http.NewServeMux()
	mux.HandleFunc("/ingest", handleIngest)
	mux.HandleFunc("/ping", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "babai-hub "+version) })
	mux.HandleFunc("/", localOnly(handleHome))
	mux.HandleFunc("/status.json", localOnly(handleStatus))
	mux.HandleFunc("/settings", localOnly(handleSettings))
	mux.HandleFunc("/test", localOnly(handleTest))
	mux.HandleFunc("/autostart", localOnly(handleAutostart))
	mux.HandleFunc("/poster", localOnly(handlePoster))

	if !configured() {
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
	if cfg.Page == "" {
		cfg.Page = defaultPage
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

func configured() bool {
	c := getConfig()
	return c.DB != "" && c.Secret != ""
}

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

// fromShop: the TV must be on the shop network (private address).
func fromShop(r *http.Request) bool {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	ip := net.ParseIP(host)
	return ip != nil && (ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast())
}

// lanAddresses lists this PC's shop-network addresses (to type into the TV).
func lanAddresses() []string {
	var out []string
	ifaces, _ := net.Interfaces()
	for _, ifc := range ifaces {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			if ipn, ok := a.(*net.IPNet); ok {
				if ip4 := ipn.IP.To4(); ip4 != nil && ip4.IsPrivate() {
					out = append(out, ip4.String())
				}
			}
		}
	}
	return out
}
