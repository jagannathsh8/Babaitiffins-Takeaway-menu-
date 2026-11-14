package main

import (
	"math"
	"strings"
	"testing"
	"time"
)

func TestPickupWaits(t *testing.T) {
	dataDir = t.TempDir()
	now := time.Now().UnixMilli()
	base := now - clockSkew() // KOT times are the billing PC's clock
	min := int64(60_000)
	// dosa KOTs of every channel share one queue, oldest first
	dine := Kot{ID: 1, OrderType: 3, Status: "1", Created: base - 9*min, Items: []KItem{{N: "Masala Dosa", C: "Dosa", Q: 1, S: 1, ID: 101}}}
	deliv := Kot{ID: 2, OrderType: 1, Online: true, Platform: "Swiggy", Status: "1", Created: base - 7*min, Items: []KItem{{N: "Ghee Roast", C: "Dosa", Q: 1, S: 1, ID: 102}}}
	pDosa := Kot{ID: 3, OrderType: 2, Status: "1", Created: base - 2*min, Items: []KItem{{N: "Rava Dosa", C: "Dosa", Q: 1, S: 1, ID: 103}, {N: "Filter Coffee", C: "Beverages", Q: 2, S: 1, ID: 104}}}
	pIdli := Kot{ID: 4, OrderType: 2, Status: "1", Created: base - 1*min, Items: []KItem{{N: "Idli (3 Pcs)", C: "Idli", Q: 2, S: 1, ID: 105}}}
	pReady := Kot{ID: 5, OrderType: 2, Status: "9", Created: base - 12*min, Items: []KItem{{N: "Poori", C: "Poori", Q: 1, S: 9, ID: 7}}}
	kots := []Kot{pIdli, pDosa, deliv, dine, pReady}
	q := dosaQueue(kots)
	if q[1] != 0 || q[2] != 1 || q[3] != 2 {
		t.Fatalf("one queue for every channel: %v", q)
	}
	times := pwTimes{item: map[string]float64{"idli (3 pcs)": 4}, cat: map[string]float64{}, all: 5}
	w := waitFor(pDosa, q, times, 8, 0, now)
	if w.Lead != "🔥 Dosa: " || w.Bold != "2 orders ahead on the tawa" || w.M != 6 {
		t.Fatalf("dosa order: %+v", w)
	}
	w = waitFor(pDosa, q, times, 8, 0.5, now) // 2 KOTs a minute off the tawa ... 0.5: 3 places = 6 min
	if w.M != 6 {
		t.Fatalf("queue speed: %+v", w)
	}
	w = waitFor(pIdli, q, times, 8, 0, now)
	if w.M != 3 || w.Bold != "Idli (3 Pcs) usually takes 4 min" || w.Lead != "" {
		t.Fatalf("idli: %+v", w)
	}
	late := pIdli
	late.Created = base - 30*min
	if w = waitFor(late, q, times, 8, 0, now); w.M != 1 {
		t.Fatalf("a late order shows almost ready: %+v", w)
	}
	if v := (pwTimes{}).dish(KItem{N: "New dish"}); v != pwDefaultMin || math.IsNaN(v) {
		t.Fatal("unknown dish: default time")
	}

	list, avg := pickupOrders(kots, now)
	if len(list) != 3 || avg == "" {
		t.Fatalf("3 pick-up orders and an average: %d %q", len(list), avg)
	}
	for _, o := range list {
		if o.K == 5 && (!o.Ready || o.M != 0) || o.K == 3 && (o.M == 0 || !strings.Contains(o.Y2, "ahead")) {
			t.Fatalf("order %+v", o)
		}
	}
	// triple tap on the TV, and Dispatch on a kitchen screen: off the Ready list
	pickupClear(5, false)
	if list, _ = pickupOrders(kots, now); len(list) != 2 {
		t.Fatal("cleared on the TV")
	}
	pickupClear(5, true)
	kitchenMu.Lock()
	kitchenGone[5] = now
	kitchenMu.Unlock()
	if list, _ = pickupOrders(kots, now); len(list) != 2 {
		t.Fatal("dispatched on a kitchen screen")
	}
	kitchenMu.Lock()
	delete(kitchenGone, 5)
	kitchenMu.Unlock()
	if list, _ = pickupOrders(kots, now); len(list) != 3 {
		t.Fatal("undo")
	}
}

func TestDosaFlow(t *testing.T) {
	flowMu.Lock()
	flow = dosaFlow{open: map[int64]bool{}}
	flowMu.Unlock()
	now := time.Now().UnixMilli()
	k := Kot{ID: 9, OrderType: 2, Status: "1", Items: []KItem{{N: "Masala Dosa", C: "Dosa", Q: 1, S: 1, ID: 106}}}
	flowUpdate([]Kot{k}, now-10*60_000)
	k.Items[0].S = 9
	flowUpdate([]Kot{k}, now-5*60_000)
	if p := flowPerMin(now); p <= 0 || p > 0.2 {
		t.Fatalf("one dosa KOT in 10 min: %v", p)
	}
}

func TestAutoClearChoices(t *testing.T) {
	for in, want := range map[int]int{0: 30, 10: 30, -1: -1, 30: 30, 45: 45, 60: 60, 90: 30} {
		if got := cleanAutoClear(in); got != want {
			t.Errorf("%d -> %d, want %d", in, got, want)
		}
	}
	dataDir = t.TempDir()
	now := time.Now().UnixMilli()
	k := Kot{ID: 50, OrderType: 2, Status: "9", Created: now - 90*60_000, Items: []KItem{{N: "Poori", C: "Poori", Q: 1, S: 9, ID: 1}}}
	pickMu.Lock()
	pickReadyAt[50] = now - 50*60_000 // ready 50 min ago
	pickMu.Unlock()
	old := getConfig()
	defer func() { cfgMu.Lock(); cfg = old; cfgMu.Unlock() }()
	for min, shown := range map[int]bool{30: false, 45: false, 60: true, -1: true} {
		cfgMu.Lock()
		cfg.AutoClearMin = min
		cfgMu.Unlock()
		if l, _ := pickupOrders([]Kot{k}, now); (len(l) == 1) != shown {
			t.Errorf("auto clear %d: shown %v", min, len(l) == 1)
		}
	}
}

// 4.16.2: a dosa KOT left open in Petpooja for over 2 hours is not on the Dosa board, the
// dosa counts or the pick-up waits.
func TestDosaLeftoversOffTheBoard(t *testing.T) {
	now := time.Now().UnixMilli() - clockSkew()
	dosa := []KItem{{N: "Masala Dosa", C: "Dosa", Q: 1, S: 1}}
	kots := []Kot{
		{ID: 1, Token: 184, OrderType: 3, Status: "1", Created: now - 3*3600_000, Items: dosa},
		{ID: 2, Token: 1748, OrderType: 3, Status: "1", Created: now - 5*60_000, Items: dosa},
		{ID: 3, Token: 531, OrderType: 2, Status: "1", Created: now - 26*3600_000, Items: dosa},
	}
	tb, _ := dineInDosa(freshKots(kots, now+clockSkew()))
	if len(tb) != 1 || tb[0].label != "1748" {
		t.Fatalf("board: want only 1748, got %+v", tb)
	}
	if q := dosaQueue(kots); len(q) != 1 || q[2] != 0 {
		t.Fatalf("dosa queue: want only KOT 2, got %v", q)
	}
	if list, _ := pickupOrders(kots, now+clockSkew()); len(list) != 0 {
		t.Fatalf("pick-up: the day-old KOT is shown: %+v", list)
	}
}
