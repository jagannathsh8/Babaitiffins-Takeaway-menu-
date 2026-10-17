package main

// Babai Console print engine: settings, token counter and the saved print jobs.
// Settings and jobs keep Bridge Print's JSON shape, so its dashboard, TV page and Android
// Token app work unchanged, and the old Bridge Print's settings and next token are imported.

import (
	"encoding/json"
	"errors"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"
)

// PrintSettings: Bridge Print's settings.json, same keys.
type PrintSettings struct {
	OutputMode       string `json:"outputMode"` // "windows" or "lan"
	PrinterName      string `json:"printerName"`
	PrinterIP        string `json:"printerIp"`
	PrinterPort      int    `json:"printerPort"`
	AutoPrint        bool   `json:"autoPrint"`
	TokenEnabled     bool   `json:"tokenEnabled"`
	Heading          string `json:"heading"`
	TokenLabel       string `json:"tokenLabel"`
	AutoSource       bool   `json:"autoSource"`
	ShowOrderID      bool   `json:"showOrderId"`
	TokenLimit       int    `json:"tokenLimit"`
	PetpoojaIP       string `json:"petpoojaIp"`
	AndroidIP        string `json:"androidIp"`
	KdsEnabled       bool   `json:"kdsEnabled"`
	PetpoojaSyncCode string `json:"petpoojaSyncCode"`
}

func defaultPrintSettings() PrintSettings {
	return PrintSettings{OutputMode: "windows", PrinterPort: 9100, AutoPrint: true, TokenEnabled: true,
		TokenLabel: "TOKEN", AutoSource: true, ShowOrderID: true, TokenLimit: 60}
}

// PrintOptions: what the Console adds (printers & rules screen).
type PrintOptions struct {
	Enabled      bool                    `json:"enabled"`      // receive Petpooja prints on this PC (port 9100)
	IdentifyWait int                     `json:"identifyWait"` // ms to wait for the order details before printing anyway
	Main         string                  `json:"main"`         // main printer: "always" | "unmatched" | "never"
	Printers     map[string]PrinterSetup `json:"printers"`     // per printer: its fallback
	Lan          []LanPrinter            `json:"lan"`          // network printers by IP (no Windows driver)
	Rules        []Rule                  `json:"rules"`
	Window       *bool                   `json:"window,omitempty"` // keep the Console window open (reopen if closed)
	Sound        *bool                   `json:"sound,omitempty"`  // beep on a printer alert
}

type PrinterSetup struct {
	Fallback string `json:"fallback,omitempty"`
}

type LanPrinter struct {
	Name string `json:"name"`
	IP   string `json:"ip"`
	Port int    `json:"port"`
}

type counterState struct {
	Next    int    `json:"next"`
	ResetTo int    `json:"resetTo,omitempty"`
	ResetAt string `json:"resetAt,omitempty"`
}

// JobLive: the order's live Petpooja status (Bridge Print's job.petpooja, read by the TV page).
type JobLive struct {
	Active    *bool  `json:"active,omitempty"`
	Status    string `json:"status,omitempty"` // "preparing" / "ready"
	RawStatus int    `json:"rawStatus,omitempty"`
	OTP       string `json:"otp,omitempty"`
	Brand     string `json:"brand,omitempty"`
	OrderID   string `json:"orderId,omitempty"`
	ReadyAt   string `json:"readyAt,omitempty"`
	UpdatedAt string `json:"updatedAt,omitempty"`
	RemovedAt string `json:"removedAt,omitempty"`
}

// PrintOut is one slip sent for a job (main printer or a rule).
type PrintOut struct {
	Rule     string `json:"rule"`             // rule name, or "Main printer"
	Printer  string `json:"printer"`          // where it went in the end
	Wanted   string `json:"wanted,omitempty"` // the assigned printer, when the fallback was used
	Part     string `json:"part"`             // "bill" / "items"
	Copies   int    `json:"copies"`
	OK       bool   `json:"ok"`
	Fallback bool   `json:"fallback,omitempty"` // printed on the fallback printer
	Error    string `json:"error,omitempty"`
	Ms       int    `json:"ms"` // from receiving the KOT to the printer queue
}

// Job is one print received from Petpooja. The first fields are Bridge Print's.
type Job struct {
	ID            string        `json:"id"`
	Token         int           `json:"token"`
	CreatedAt     string        `json:"createdAt"`
	Source        string        `json:"source"`
	Bytes         int           `json:"bytes"`
	Status        string        `json:"status"` // reading, queued, sending, sent, uncertain
	Attempts      int           `json:"attempts"`
	Customization Customization `json:"customization"`
	Metadata      *ReceiptMeta  `json:"metadata,omitempty"`
	Destination   string        `json:"destination,omitempty"`
	Error         string        `json:"error,omitempty"`
	SentAt        string        `json:"sentAt,omitempty"`
	Petpooja      *JobLive      `json:"petpooja,omitempty"`

	// Babai Console
	Recv      int64      `json:"recv"`                // received (ms)
	Kot       int64      `json:"kot,omitempty"`       // the Petpooja KOT it is
	KotNo     int64      `json:"kotNo,omitempty"`     // KOT number
	OrderType int        `json:"orderType,omitempty"` // 1 delivery, 2 pick up, 3 dine in
	Platform  string     `json:"platform,omitempty"`  // Swiggy / Zomato / brand
	Brands    []string   `json:"brands,omitempty"`    // every name the order goes by (rules)
	Items     []KItem    `json:"items,omitempty"`
	IdentBy   string     `json:"identBy,omitempty"` // petpooja / text / ocr / none
	IdentMs   int        `json:"identMs,omitempty"`
	PrintMs   int        `json:"printMs,omitempty"` // receive -> printer queue (first slip)
	Outs      []PrintOut `json:"outs,omitempty"`
	OCR       string     `json:"ocr,omitempty"`     // what OCR read (order id), for checking
	ReadyAt   int64      `json:"readyAt,omitempty"` // Food ready on the Dispatch screen (prints not in Petpooja's list)
	ReadyBy   string     `json:"readyBy,omitempty"`

	slipBrand string // what the slip says it is (while its order is looked for)
}

var (
	prMu       sync.Mutex
	prSettings = defaultPrintSettings()
	prOpts     PrintOptions
	prCounter  = counterState{Next: 1}
	prJobs     []*Job
	prLastErr  string
	prRecv     int // connections being received
	prLastKOT  time.Time
)

func printDir() string { return filepath.Join(dataDir, "print") }
func jobDir() string   { return filepath.Join(printDir(), "jobs") }

func readJSONFile(path string, v any) bool {
	for _, p := range []string{path, path + ".backup"} {
		b, err := os.ReadFile(p)
		if err != nil {
			continue
		}
		b = []byte(strings.TrimPrefix(string(b), "\uFEFF"))
		if json.Unmarshal(b, v) == nil {
			return true
		}
	}
	return false
}

func writeJSONFile(path string, v any, backup bool) error {
	b, _ := json.MarshalIndent(v, "", "  ")
	if err := writeFileAtomic(path, b); err != nil {
		return err
	}
	if backup {
		_ = os.WriteFile(path+".backup", b, 0o600)
	}
	return nil
}

func defaultPrintOptions() PrintOptions {
	return PrintOptions{Enabled: true, IdentifyWait: 1500, Main: "always", Printers: map[string]PrinterSetup{}}
}

// oldBridgeDir: where Bridge Print kept its data (its installer: %ProgramData%\BridgePrint\data).
func oldBridgeDir() string {
	if d := os.Getenv("BABAI_OLD_BRIDGE"); d != "" {
		return d
	}
	pd := os.Getenv("ProgramData")
	if pd == "" {
		return ""
	}
	return filepath.Join(pd, "BridgePrint", "data")
}

// loadPrint reads the print settings, counter and jobs (first start: imports Bridge Print's).
func loadPrint() {
	_ = os.MkdirAll(jobDir(), 0o755)
	prMu.Lock()
	defer prMu.Unlock()
	setFile, cntFile := filepath.Join(printDir(), "settings.json"), filepath.Join(printDir(), "counter.json")
	prCounter = counterState{Next: 1}
	s := defaultPrintSettings()
	if !readJSONFile(setFile, &s) {
		if old := oldBridgeDir(); old != "" && readJSONFile(filepath.Join(old, "settings.json"), &s) {
			log.Printf("print: imported Bridge Print settings from %s", old)
			var c counterState
			if readJSONFile(filepath.Join(old, "counter.json"), &c) && c.Next > 0 {
				prCounter = c
				log.Printf("print: next token %d (from Bridge Print)", c.Next)
			}
			_ = writeJSONFile(cntFile, prCounter, true)
		}
		_ = writeJSONFile(setFile, s, true)
	}
	if s.TokenLimit < 1 {
		s.TokenLimit = 60
	}
	prSettings = s
	var c counterState
	if readJSONFile(cntFile, &c) && c.Next > 0 {
		prCounter = c
	}
	o := defaultPrintOptions()
	readJSONFile(filepath.Join(printDir(), "options.json"), &o)
	if o.Printers == nil {
		o.Printers = map[string]PrinterSetup{}
	}
	if o.IdentifyWait <= 0 {
		o.IdentifyWait = 1500
	}
	if o.Main == "" {
		o.Main = "always"
	}
	prOpts = o
	entries, _ := os.ReadDir(jobDir())
	prJobs = nil
	for _, e := range entries {
		if !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		var j Job
		if readJSONFile(filepath.Join(jobDir(), e.Name()), &j) && j.ID != "" {
			if j.Status == "sending" {
				j.Status, j.Error = "uncertain", "Console stopped during transfer. Inspect the printer before retrying."
				saveJobLocked(&j)
			}
			prJobs = append(prJobs, &j)
		}
	}
	sort.SliceStable(prJobs, func(a, b int) bool { return prJobs[a].CreatedAt < prJobs[b].CreatedAt })
	if prCounter.Next > prSettings.TokenLimit {
		prCounter.Next = 1
		_ = writeJSONFile(cntFile, prCounter, true)
	}
}

func saveJobLocked(j *Job) {
	if err := writeJSONFile(filepath.Join(jobDir(), j.ID+".json"), j, false); err != nil {
		prLastErr = "Could not save a job: " + err.Error()
	}
}

func savePrintSettingsLocked() error {
	return writeJSONFile(filepath.Join(printDir(), "settings.json"), prSettings, true)
}

func savePrintOptionsLocked() error {
	return writeJSONFile(filepath.Join(printDir(), "options.json"), prOpts, true)
}

func saveCounterLocked() error {
	return writeJSONFile(filepath.Join(printDir(), "counter.json"), prCounter, true)
}

// takeToken: the next token in the 1..limit cycle (Bridge Print's lifecycle.mjs).
func takeToken(c counterState, limit int) (int, counterState) {
	t := c.Next
	if t < 1 || t > limit {
		t = 1
	}
	n := c
	if t >= limit {
		n.Next = 1
	} else {
		n.Next = t + 1
	}
	return t, n
}

var reJobID = regexp.MustCompile(`^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$`)

const historyKeep = 2 * time.Hour

func removeJobFiles(j *Job) {
	if !reJobID.MatchString(j.ID) {
		return
	}
	for _, suf := range []string{".json", ".bin", ".output.bin", ".receipt.bmp", ".json.tmp", ".json.backup"} {
		_ = os.Remove(filepath.Join(jobDir(), j.ID+suf))
	}
}

// pruneHistory removes sent jobs two hours after sending (Bridge Print's rule). The order record
// for the Dispatch screen and Data tab is kept separately.
func pruneHistory(now time.Time) {
	prMu.Lock()
	defer prMu.Unlock()
	kept := prJobs[:0]
	for _, j := range prJobs {
		t, err := time.Parse(time.RFC3339Nano, firstNonEmpty(j.SentAt, j.CreatedAt))
		if j.Status == "sent" && err == nil && now.Sub(t) >= historyKeep && reJobID.MatchString(j.ID) {
			removeJobFiles(j)
			continue
		}
		kept = append(kept, j)
	}
	prJobs = kept
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if s != "" {
			return s
		}
	}
	return ""
}

func findJobLocked(id string) *Job {
	for _, j := range prJobs {
		if j.ID == id {
			return j
		}
	}
	return nil
}

func jobBytes(j *Job) ([]byte, error) {
	if !reJobID.MatchString(j.ID) {
		return nil, errors.New("bad job id")
	}
	return os.ReadFile(filepath.Join(jobDir(), j.ID+".bin"))
}

func nowISO() string { return time.Now().UTC().Format("2006-01-02T15:04:05.000Z") }
