package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

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
	Tk  int64      `json:"tk,omitempty"`
	Tb  string     `json:"tb,omitempty"`
	Ot  int        `json:"ot"`
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
	tokensRaw   []byte // /babai/t
	metaRaw     []byte // /babai/meta
	liveRaw     []byte // /babai/live
	lastBoard   time.Time
	readyCount  int
	prepCount   int
	statsSaved  time.Time
	open        = map[int64]*Rec{}
	pending     = map[string]map[string]*Rec{} // date -> id -> finished KOT waiting for upload
	openKots    int
	finishedDay int
	dayKey      string
)

const maxOpen = 3000

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
		rec.Tk, rec.Tb, rec.Ot, rec.Ch, rec.Po, rec.C, rec.St = k.Token, k.Table, k.OrderType, k.Channel, k.POID, k.Created, k.Status
		allReady := len(k.Items) > 0
		old := rec.I
		items := make([]ArchItem, 0, len(k.Items))
		for i, it := range k.Items {
			a := ArchItem{N: it.N, C: it.C, Q: it.Q}
			if i < len(old) && old[i].N == it.N {
				a.R, a.X = old[i].R, old[i].X
			}
			switch {
			case it.S == -9:
				a.X = 1
			case itemReady(it.S):
				if a.R == 0 {
					a.R = ms
				}
			default:
				allReady = false
			}
			items = append(items, a)
		}
		rec.I = items
		if rec.Rdy == 0 && (k.Status == "9" || k.Status == "10" || allReady) {
			rec.Rdy = ms
		}
	}
	openKots = len(kots)
	// Gone from the board for a minute (while Petpooja keeps answering): finished.
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
	if today := time.Now().Format("2006-01-02"); today != dayKey {
		dayKey, finishedDay = today, 0
	}
	if day == dayKey {
		finishedDay++
	}
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

func snapshotStatus() map[string]any {
	mu.Lock()
	boardAgo := -1
	if !lastBoard.IsZero() {
		boardAgo = int(time.Since(lastBoard).Seconds())
	}
	q := 0
	for _, m := range pending {
		q += len(m)
	}
	st := map[string]any{
		"version": version, "boardAgo": boardAgo, "openKots": openKots,
		"ready": readyCount, "preparing": prepCount, "finishedToday": finishedDay, "queued": q,
	}
	mu.Unlock()
	for k, v := range petpoojaStatus() {
		st[k] = v
	}
	for k, v := range cloudStatus() {
		st[k] = v
	}
	c := getConfig()
	st["server"] = c.Server
	st["configured"] = c.DB != "" && c.Secret != ""
	st["db"] = c.DB
	st["page"] = c.Page
	st["qr"] = qrURL()
	st["autostart"] = autostartEnabled()
	st["dataDir"] = dataDir
	return st
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
