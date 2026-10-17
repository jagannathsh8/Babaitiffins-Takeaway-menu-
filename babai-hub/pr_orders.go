package main

// Babai Console: one record per KOT for the Dispatch screen, "All orders" and the Data tab:
// punched, printed (token), food ready (and by what: this Console, a scanner, or Petpooja/KDS),
// dispatched, and delivered when Petpooja reports it. Kept on this PC, one file per day.

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type OrderRec struct {
	Kot        int64    `json:"kot"`
	KotNo      int64    `json:"kotNo,omitempty"`
	OrderID    string   `json:"orderId,omitempty"`
	Brand      string   `json:"brand,omitempty"`
	Channel    string   `json:"channel,omitempty"`
	Type       int      `json:"type"`
	Table      string   `json:"table,omitempty"`
	Items      []KItem  `json:"items,omitempty"`
	Punched    int64    `json:"punched"`              // KOT created (Petpooja's clock)
	Seen       int64    `json:"seen"`                 // first seen by this PC
	Token      int      `json:"token,omitempty"`      // Bridge token
	JobID      string   `json:"job,omitempty"`        // its print
	Printed    int64    `json:"printed,omitempty"`    // reached the printer
	PrintMs    int      `json:"printMs,omitempty"`    // KOT received -> printer
	Ready      int64    `json:"ready,omitempty"`      // food ready
	ReadyBy    string   `json:"readyBy,omitempty"`    // "Console", "Scanner: Counter phone", "Petpooja / KDS"
	Dispatched int64    `json:"dispatched,omitempty"` // left the kitchen list (picked up)
	Delivered  int64    `json:"delivered,omitempty"`  // only when Petpooja reports it
	Status     string   `json:"status"`               // preparing, ready, dispatched, cancelled
	Brands     []string `json:"brands,omitempty"`
	Slot       string   `json:"slot,omitempty"`      // token from Bridge Print elsewhere (or an older print)
	PrintOnly  bool     `json:"printOnly,omitempty"` // a print Petpooja's kitchen list doesn't have

	missing time.Time
}

var (
	ordMu       sync.Mutex
	orders      = map[int64]*OrderRec{}
	ordDirty    bool
	ordSaved    time.Time
	ordLoaded   bool
	readyByMap  = map[int64]string{} // KOT -> who marked it, until the next Petpooja read shows it
	skewSamples []int64
	skewNow     atomic.Int64
)

// clockSkew: ms to add to Petpooja's KOT times to get this PC's time (timers on Dispatch).
// Only differences beyond 2 minutes count as a clock difference; a KOT is seen a few seconds
// after it is punched anyway.
func clockSkew() int64 { return skewNow.Load() }

// updateSkew (ordMu held) works out the clock difference from the samples.
func updateSkewLocked() {
	if len(skewSamples) < 3 {
		skewNow.Store(0)
		return
	}
	s := append([]int64(nil), skewSamples...)
	sort.Slice(s, func(a, b int) bool { return s[a] < s[b] })
	m := s[len(s)/2]
	if m > -120_000 && m < 120_000 {
		m = 0
	}
	skewNow.Store(m)
}

func mkPrimedNow() bool {
	mkMu.Lock()
	defer mkMu.Unlock()
	return mkPrimed
}

func ordersDir() string { return filepath.Join(printDir(), "orders") }

func loadOrdersLocked() {
	if ordLoaded {
		return
	}
	ordLoaded = true
	_ = os.MkdirAll(ordersDir(), 0o755)
	for _, d := range []time.Time{time.Now().AddDate(0, 0, -1), time.Now()} {
		var list []*OrderRec
		if readJSONFile(filepath.Join(ordersDir(), d.Format("2006-01-02")+".json"), &list) {
			for _, o := range list {
				orders[o.Kot] = o
			}
		}
	}
}

func dayOf(o *OrderRec) string {
	t := o.Punched
	if t == 0 {
		t = o.Seen
	}
	return time.UnixMilli(t).Format("2006-01-02")
}

// saveOrdersMaybe writes today's (and yesterday's) records when something changed.
func saveOrdersMaybe(force bool) {
	ordMu.Lock()
	if !ordDirty || (!force && time.Since(ordSaved) < 4*time.Second) {
		ordMu.Unlock()
		return
	}
	byDay := map[string][]*OrderRec{}
	for _, o := range orders {
		byDay[dayOf(o)] = append(byDay[dayOf(o)], o)
	}
	ordDirty, ordSaved = false, time.Now()
	data := map[string][]byte{}
	for d, list := range byDay {
		sort.Slice(list, func(a, b int) bool { return list[a].Seen < list[b].Seen })
		b, _ := json.Marshal(list)
		data[d] = b
	}
	ordMu.Unlock()
	_ = os.MkdirAll(ordersDir(), 0o755)
	for d, b := range data {
		_ = writeFileAtomic(filepath.Join(ordersDir(), d+".json"), b)
	}
}

func pruneOrders(now time.Time) {
	ordMu.Lock()
	defer ordMu.Unlock()
	for id, o := range orders {
		if now.Sub(time.UnixMilli(o.Seen)) > 36*time.Hour {
			delete(orders, id)
		}
	}
	// day files are kept 120 days (the Data tab reads them)
	if es, err := os.ReadDir(ordersDir()); err == nil {
		for _, e := range es {
			if t, err := time.Parse("2006-01-02.json", e.Name()); err == nil && now.Sub(t) > 120*24*time.Hour {
				_ = os.Remove(filepath.Join(ordersDir(), e.Name()))
			}
		}
	}
}

func recFor(k *Kot, now time.Time) *OrderRec {
	o := orders[k.ID]
	if o == nil {
		o = &OrderRec{Kot: k.ID, Seen: now.UnixMilli(), Status: "preparing"}
		orders[k.ID] = o
		// how far Petpooja's clock is from this PC's: from KOTs seen within 2 min of being punched
		if d := now.UnixMilli() - k.Created; k.Created > 0 && d > -10*60_000 && d < 10*60_000 && ordLoaded && mkPrimedNow() {
			skewSamples = append(skewSamples, d)
			if len(skewSamples) > 25 {
				skewSamples = skewSamples[1:]
			}
			updateSkewLocked()
		}
	}
	o.KotNo, o.OrderID, o.Type, o.Table, o.Punched = k.Token, k.POID, k.OrderType, k.Table, k.Created
	o.Brand, o.Channel = brandOf(*k), k.Channel
	o.Items = k.Items
	o.Brands = factsFromKot(k).Brands
	return o
}

// updateJobsFromKots follows every KOT on Petpooja's kitchen list (called after each read).
func updateJobsFromKots(kots []Kot, now time.Time) {
	ordMu.Lock()
	defer ordMu.Unlock()
	loadOrdersLocked()
	ms := now.UnixMilli()
	seen := map[int64]bool{}
	for i := range kots {
		k := &kots[i]
		seen[k.ID] = true
		o := recFor(k, now)
		o.missing = time.Time{}
		switch {
		case k.Status == "0":
			if o.Status != "cancelled" {
				o.Status = "cancelled"
				ordDirty = true
			}
		case k.Status == "10":
			if o.Dispatched == 0 {
				o.Dispatched, o.Status = ms, "dispatched"
				ordDirty = true
			}
			fallthrough
		case kotReady(*k):
			if o.Ready == 0 {
				o.Ready = ms
				o.ReadyBy = firstNonEmpty(readyByMap[k.ID], "Petpooja / KDS")
				delete(readyByMap, k.ID)
				ordDirty = true
			}
			if o.Status == "preparing" {
				o.Status = "ready"
				ordDirty = true
			}
		default:
			if o.Status == "ready" && o.Ready != 0 {
				o.Status, o.Ready, o.ReadyBy = "preparing", 0, "" // un-marked in Petpooja
				ordDirty = true
			}
		}
	}
	// gone from the kitchen list for a minute: picked up / dispatched
	for id, o := range orders {
		if seen[id] || o.Status == "dispatched" || o.Status == "cancelled" || now.Sub(time.UnixMilli(o.Seen)) > 24*time.Hour {
			continue
		}
		if o.missing.IsZero() {
			o.missing = now
			continue
		}
		if now.Sub(o.missing) >= time.Minute {
			o.Dispatched, o.Status = o.missing.UnixMilli(), "dispatched"
			if o.Ready == 0 {
				o.Ready, o.ReadyBy = o.missing.UnixMilli(), "Petpooja / KDS"
			}
			ordDirty = true
		}
	}
}

// orderPrinted: a print was matched to this KOT.
func orderPrinted(k *Kot, j *Job) {
	ordMu.Lock()
	defer ordMu.Unlock()
	loadOrdersLocked()
	o := recFor(k, time.Now())
	o.Token, o.JobID = j.Token, j.ID
	ordDirty = true
}

func notePrinted(kot int64, j *Job) {
	ordMu.Lock()
	defer ordMu.Unlock()
	if o := orders[kot]; o != nil {
		if o.Printed == 0 && j.Status == "sent" {
			o.Printed = time.Now().UnixMilli()
		}
		if o.PrintMs == 0 {
			o.PrintMs = j.PrintMs
		}
		ordDirty = true
	}
}

// noteReadyBy remembers who marked an order Food Ready (shown on "All orders").
func noteReadyBy(kot int64, by string) {
	ordMu.Lock()
	defer ordMu.Unlock()
	readyByMap[kot] = by
	if o := orders[kot]; o != nil && o.Ready == 0 {
		o.Ready, o.ReadyBy, o.Status = time.Now().UnixMilli(), by, "ready"
		delete(readyByMap, kot)
		ordDirty = true
	}
}

func noteDelivered(orderID string, at int64) {
	d := digitsOnly(orderID)
	if len(d) < 4 {
		return
	}
	ordMu.Lock()
	defer ordMu.Unlock()
	for _, o := range orders {
		if o.Delivered == 0 && digitsOnly(o.OrderID) == d {
			o.Delivered = at
			ordDirty = true
		}
	}
}

// ordersSnapshot: today's and recent orders, newest first.
func ordersSnapshot(since time.Time) []OrderRec {
	ordMu.Lock()
	defer ordMu.Unlock()
	loadOrdersLocked()
	out := make([]OrderRec, 0, len(orders))
	for _, o := range orders {
		if time.UnixMilli(o.Seen).After(since) {
			out = append(out, *o)
		}
	}
	sort.Slice(out, func(a, b int) bool { return out[a].Seen > out[b].Seen })
	return out
}

// ordersOfDay reads a saved day (Data tab).
func ordersOfDay(day string) []OrderRec {
	if _, err := time.Parse("2006-01-02", day); err != nil {
		return nil
	}
	saveOrdersMaybe(true)
	var list []OrderRec
	readJSONFile(filepath.Join(ordersDir(), day+".json"), &list)
	return list
}

func brandKeyOf(o OrderRec) string {
	if o.Brand != "" {
		return o.Brand
	}
	return strings.TrimSpace(orderTypeName(o.Type))
}
