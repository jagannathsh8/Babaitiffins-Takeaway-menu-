package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"sync"
	"time"
)

// What the TV sends to POST /ingest.
type Item struct {
	N string  `json:"n"` // name
	C string  `json:"c"` // category
	Q float64 `json:"q"` // quantity
	S *int    `json:"s"` // item status (9 ready, 10 dispatched, -9 cancelled)
}

type Kot struct {
	ID int64  `json:"id"`
	Tk *int64 `json:"tk"` // token number
	Tb string `json:"tb"` // table
	Ot *int   `json:"ot"` // order type
	Ch string `json:"ch"` // channel / platform name
	Po string `json:"po"` // platform order ID
	St string `json:"st"` // KOT status ("9" ready, "10" dispatched, "0" cancelled)
	C  int64  `json:"c"`  // created (ms)
	Rd int    `json:"rd"` // 1 = food ready on the KDS
	I  []Item `json:"i"`
}

type Ingest struct {
	V    int             `json:"v"`
	Dev  string          `json:"dev"`
	Dosa json.RawMessage `json:"dosa"`
	Kots []Kot           `json:"kots"`
}

// A finished KOT, kept for reports: online under /babai/days/<date>/<id> and in days/<date>.jsonl.
type ArchItem struct {
	N string  `json:"n"`
	C string  `json:"c,omitempty"`
	Q float64 `json:"q"`
	R int64   `json:"r,omitempty"` // first seen ready (ms)
	X int     `json:"x,omitempty"` // 1 = cancelled
}

type Rec struct {
	ID  int64      `json:"id"`
	Tk  *int64     `json:"tk,omitempty"`
	Tb  string     `json:"tb,omitempty"`
	Ot  *int       `json:"ot,omitempty"`
	Ch  string     `json:"ch,omitempty"`
	Po  string     `json:"po,omitempty"`
	C   int64      `json:"c,omitempty"`   // created (Petpooja time)
	Fs  int64      `json:"fs"`            // first seen by the hub
	Rdy int64      `json:"rdy,omitempty"` // KOT food ready
	Out int64      `json:"out"`           // left the kitchen board (dispatched / done)
	St  string     `json:"st,omitempty"`  // last KOT status
	I   []ArchItem `json:"i"`

	missingSince time.Time
}

var (
	mu          sync.Mutex
	dosaRaw     []byte // latest token status from the TV
	liveRaw     []byte // latest open KOTs (normalised)
	lastIngest  time.Time
	lastDev     string
	devices     = map[string]time.Time{}
	open        = map[int64]*Rec{}
	pending     = map[string]map[string]*Rec{} // date -> id -> finished KOT waiting for upload
	openKots    int
	finishedDay int
	dayKey      string
)

const maxOpen = 3000

func handleIngest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "POST only", http.StatusMethodNotAllowed)
		return
	}
	if !fromShop(r) {
		http.Error(w, "shop network only", http.StatusForbidden)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 4<<20))
	if err != nil {
		http.Error(w, "read error", http.StatusBadRequest)
		return
	}
	var in Ingest
	if err := json.Unmarshal(body, &in); err != nil {
		http.Error(w, "bad JSON", http.StatusBadRequest)
		return
	}
	now := time.Now()
	mu.Lock()
	if len(in.Dosa) > 1 && in.Dosa[0] == '{' {
		dosaRaw = append([]byte(nil), in.Dosa...)
	}
	if in.Kots != nil {
		liveRaw, _ = json.Marshal(in.Kots)
		track(in.Kots, now)
	}
	lastIngest = now
	lastDev = in.Dev
	devices[in.Dev] = now
	mu.Unlock()

	qr := ""
	if configured() && cloudOK() {
		qr = qrURL()
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "qr": qr, "v": version})
}

// track follows each KOT from first sight until it leaves the board, then archives it.
// Called with mu held.
func track(kots []Kot, now time.Time) {
	ms := now.UnixMilli()
	seen := make(map[int64]bool, len(kots))
	for _, k := range kots {
		seen[k.ID] = true
		rec := open[k.ID]
		if rec == nil {
			if len(open) >= maxOpen {
				continue
			}
			rec = &Rec{ID: k.ID, Fs: ms}
			open[k.ID] = rec
		}
		rec.missingSince = time.Time{}
		rec.Tk, rec.Tb, rec.Ot, rec.Ch, rec.Po, rec.C, rec.St = k.Tk, k.Tb, k.Ot, k.Ch, k.Po, k.C, k.St
		if rec.Rdy == 0 && (k.Rd == 1 || k.St == "9" || k.St == "10") {
			rec.Rdy = ms
		}
		// items: keep ready times already seen (matched by position + name)
		old := rec.I
		items := make([]ArchItem, 0, len(k.I))
		for i, it := range k.I {
			a := ArchItem{N: it.N, C: it.C, Q: it.Q}
			if i < len(old) && old[i].N == it.N {
				a.R, a.X = old[i].R, old[i].X
			}
			if it.S != nil {
				switch {
				case *it.S == -9:
					a.X = 1
				case (*it.S == 9 || *it.S == 10) && a.R == 0:
					a.R = ms
				}
			}
			items = append(items, a)
		}
		rec.I = items
	}
	openKots = len(kots)
	// Gone from the board for a minute (while the TV keeps reporting): finished.
	for id, rec := range open {
		if seen[id] {
			continue
		}
		if rec.missingSince.IsZero() {
			rec.missingSince = now
			continue
		}
		if now.Sub(rec.missingSince) >= time.Minute {
			rec.Out = rec.missingSince.UnixMilli()
			finish(rec)
			delete(open, id)
		}
	}
}

// finish stores a finished KOT locally at once and queues it for the cloud. Called with mu held.
func finish(rec *Rec) {
	t := rec.Fs
	if rec.C > 0 {
		t = rec.C
	}
	day := time.UnixMilli(t).Format("2006-01-02")
	if day != dayKey {
		dayKey, finishedDay = day, 0
	}
	finishedDay++
	b, _ := json.Marshal(rec)
	if f, err := os.OpenFile(filepath.Join(dataDir, "days", day+".jsonl"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644); err == nil {
		_, _ = f.Write(append(b, '\n'))
		_ = f.Close()
	}
	if pending[day] == nil {
		pending[day] = map[string]*Rec{}
	}
	pending[day][strconv.FormatInt(rec.ID, 10)] = rec
}

// ---- status page data ---------------------------------------------------------------------

type DeviceSeen struct {
	Name string `json:"name"`
	Ago  int    `json:"ago"` // seconds
}

func snapshotStatus() map[string]any {
	mu.Lock()
	var devs []DeviceSeen
	for d, t := range devices {
		if time.Since(t) < 24*time.Hour {
			devs = append(devs, DeviceSeen{d, int(time.Since(t).Seconds())})
		}
	}
	sort.Slice(devs, func(i, j int) bool { return devs[i].Ago < devs[j].Ago })
	ready, prep := tokenCounts(dosaRaw)
	ingestAgo := -1
	if !lastIngest.IsZero() {
		ingestAgo = int(time.Since(lastIngest).Seconds())
	}
	q := 0
	for _, m := range pending {
		q += len(m)
	}
	st := map[string]any{
		"version": version, "devices": devs, "ingestAgo": ingestAgo, "openKots": openKots,
		"ready": ready, "preparing": prep, "finishedToday": finishedDay, "queued": q,
	}
	mu.Unlock()
	c := getConfig()
	cs := cloudStatus()
	for k, v := range cs {
		st[k] = v
	}
	st["configured"] = c.DB != "" && c.Secret != ""
	st["db"] = c.DB
	st["page"] = c.Page
	st["qr"] = qrURL()
	st["lan"] = lanAddresses()
	st["port"] = portOf(c)
	st["autostart"] = autostartEnabled()
	st["dataDir"] = dataDir
	return st
}

func tokenCounts(raw []byte) (int, int) {
	var d struct {
		R []json.RawMessage `json:"r"`
		P []json.RawMessage `json:"p"`
	}
	if len(raw) == 0 || json.Unmarshal(raw, &d) != nil {
		return 0, 0
	}
	return len(d.R), len(d.P)
}

func portOf(c Config) int {
	if c.Port == 0 {
		return defaultPort
	}
	return c.Port
}

// withServerTime puts {"u":{".sv":"timestamp"}} at the front of a JSON object.
func withServerTime(obj []byte) []byte {
	obj = bytes.TrimSpace(obj)
	if len(obj) < 2 || obj[0] != '{' {
		return []byte(`{"u":{".sv":"timestamp"}}`)
	}
	rest := bytes.TrimSpace(obj[1:])
	if len(rest) > 0 && rest[0] == '}' {
		return []byte(`{"u":{".sv":"timestamp"}}`)
	}
	out := make([]byte, 0, len(obj)+32)
	out = append(out, `{"u":{".sv":"timestamp"},`...)
	return append(out, rest...)
}

func init() { log.SetFlags(log.LstdFlags) }
