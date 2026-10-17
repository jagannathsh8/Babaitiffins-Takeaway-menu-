package main

// Rider screen feed for the KDS app on the shop Wi-Fi: GET http://<hub PC>:8790/rider.json
//
// Online / delivery orders straight from Petpooja (all order types, kept until dispatched, no
// 300-line limit), joined with the Bridge Print slot. "Ready since" times are kept on this PC, so a
// TV restart keeps its timers. Read-only; no customer names or phone numbers.

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"time"
)

type riderOrder struct {
	ID       int64    `json:"id"`
	Platform string   `json:"p"`
	Order    string   `json:"o"`
	Slot     string   `json:"s,omitempty"`
	Ready    int      `json:"r"` // 1 = Food Ready
	Created  int64    `json:"c,omitempty"`
	ReadyAt  int64    `json:"ra,omitempty"`
	Items    []string `json:"i,omitempty"`
}

var (
	riderMu      sync.Mutex
	readyAtByKot = map[int64]int64{} // KOT id -> when it was first seen Food Ready (ms)
	readySeenAt  = map[int64]int64{} // KOT id -> last time it was on the list (ms)
	readyDirty   bool
	readySavedAt time.Time
	readyLoaded  bool
)

func riderReadyPath() string { return filepath.Join(dataDir, "rider_ready.json") }

func loadRiderReady() {
	b, err := os.ReadFile(riderReadyPath())
	if err != nil {
		return
	}
	var m map[string]int64
	if json.Unmarshal(b, &m) != nil {
		return
	}
	now := time.Now().UnixMilli()
	for k, v := range m {
		if id, err := strconv.ParseInt(k, 10, 64); err == nil && now-v < 6*3600_000 {
			readyAtByKot[id], readySeenAt[id] = v, now
		}
	}
}

func isRiderOrder(k Kot) bool {
	return k.Online || k.OrderType == 1
}

// trackRiderReady notes when each online order turned Food Ready (called after every Petpooja read).
func trackRiderReady(kots []Kot, now time.Time) {
	riderMu.Lock()
	defer riderMu.Unlock()
	if !readyLoaded {
		readyLoaded = true
		loadRiderReady()
	}
	ms := now.UnixMilli()
	for _, k := range kots {
		if !isRiderOrder(k) || k.Status == "0" || k.Status == "10" || kotHidden(k) {
			continue
		}
		readySeenAt[k.ID] = ms
		if kotReady(k) {
			if _, ok := readyAtByKot[k.ID]; !ok {
				readyAtByKot[k.ID], readyDirty = ms, true
			}
		} else if _, ok := readyAtByKot[k.ID]; ok {
			delete(readyAtByKot, k.ID) // back to preparing
			readyDirty = true
		}
	}
	for id, t := range readySeenAt {
		if ms-t > 6*3600_000 {
			delete(readySeenAt, id)
			if _, ok := readyAtByKot[id]; ok {
				delete(readyAtByKot, id)
				readyDirty = true
			}
		}
	}
	if readyDirty && now.Sub(readySavedAt) >= 15*time.Second {
		m := make(map[string]int64, len(readyAtByKot))
		for id, v := range readyAtByKot {
			m[strconv.FormatInt(id, 10)] = v
		}
		b, _ := json.Marshal(m)
		if writeFileAtomic(riderReadyPath(), b) == nil {
			readyDirty, readySavedAt = false, now
		}
	}
}

// noteMarkedReady: marked from a scan; the timer starts now, not at the next read.
func noteMarkedReady(id int64, now time.Time) {
	riderMu.Lock()
	defer riderMu.Unlock()
	if _, ok := readyAtByKot[id]; !ok {
		readyAtByKot[id], readyDirty = now.UnixMilli(), true
	}
}

func riderOrders(kots []Kot) []riderOrder {
	riderMu.Lock()
	defer riderMu.Unlock()
	out := []riderOrder{}
	for _, k := range kots {
		if !isRiderOrder(k) || k.Status == "0" || k.Status == "10" || kotHidden(k) {
			continue
		}
		o := riderOrder{ID: k.ID, Platform: brandOf(k), Order: k.POID, Slot: slotForKot(k.ID, k.POID), Created: k.Created}
		if o.Order == "" {
			o.Order = strconv.FormatInt(k.ID, 10)
		}
		if kotReady(k) {
			o.Ready = 1
			o.ReadyAt = readyAtByKot[k.ID]
		}
		for _, it := range k.Items {
			if it.S == -9 {
				continue
			}
			q := it.Q
			if q <= 0 {
				q = 1
			}
			o.Items = append(o.Items, strconv.FormatFloat(q, 'f', -1, 64)+" × "+it.N)
		}
		out = append(out, o)
	}
	return out
}

// handleRider: GET /rider.json (shop Wi-Fi).
func handleRider(w http.ResponseWriter, r *http.Request) {
	ppMu.Lock()
	kots, okAt := ppKots, ppOKAt
	ppMu.Unlock()
	now := time.Now()
	fresh := !okAt.IsZero() && now.Sub(okAt) < scanFreshFor && !getConfig().Paused
	var orders []riderOrder
	if fresh {
		orders = riderOrders(kots)
	} else {
		orders = []riderOrder{}
	}
	ppAt := int64(0)
	if !okAt.IsZero() {
		ppAt = okAt.UnixMilli()
	}
	br := bridgeStatus()
	_, brOK := br["ok"]
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(map[string]any{
		"v": 1, "hub": version, "now": now.UnixMilli(), "pp": ppAt, "ok": fresh,
		"bridge": brOK, "outlet": getConfig().Outlet, "orders": orders, "hide": hiddenBrands(),
	})
}
