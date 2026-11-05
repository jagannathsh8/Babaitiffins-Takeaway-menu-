package main

import (
	"os"
	"testing"
	"time"
)

func barkReset(t *testing.T) {
	t.Helper()
	dir := t.TempDir()
	old := dataDir
	dataDir = dir
	t.Cleanup(func() { dataDir = old })
	barkMu.Lock()
	barkLoaded, barkMarks, barkOwner, barkSeen, barkGrp = false, map[string]barkMark{}, map[int64]int{}, map[int64]int64{}, 0
	barkMu.Unlock()
	_ = os.MkdirAll(dir, 0o755)
}

func bk(id int64, ot int, created int64, items ...KItem) Kot {
	return Kot{ID: id, Token: id % 1000, OrderType: ot, Created: created, Status: "1", Items: items}
}

func dosa(n string, q float64) KItem { return KItem{N: n, C: "Dosa", Q: q} }

func TestBarkerKotNeverSplitAndShared(t *testing.T) {
	barkReset(t)
	now := time.Date(2026, 11, 2, 12, 0, 0, 0, time.Local) // a Monday: default plan, 2 tawas on All
	base := now.UnixMilli() - 600_000
	kots := []Kot{
		bk(101, 3, base, dosa("Masala Dosa", 2), dosa("Ghee Roast", 2), KItem{N: "Coffee", C: "Beverages", Q: 1}),
		bk(102, 2, base+60_000, dosa("Masala Dosa", 1)),
		bk(103, 1, base+120_000, dosa("Karam Dosa", 3)),
		bk(104, 3, base+180_000, dosa("Podi Dosa", 1)),
	}
	barkMu.Lock()
	_, plan, us := barkSnapshot(kots, now)
	barkMu.Unlock()
	if len(plan) != 2 {
		t.Fatalf("plan %v", plan)
	}
	if len(us) != 5 {
		t.Fatalf("units %d (coffee must not be on the barker)", len(us))
	}
	tawa := map[int64]map[int]bool{}
	load := []int{0, 0}
	for _, u := range us {
		if tawa[u.Kot] == nil {
			tawa[u.Kot] = map[int]bool{}
		}
		tawa[u.Kot][u.T] = true
		load[u.T] += u.Q
	}
	for k, ts := range tawa {
		if len(ts) != 1 {
			t.Fatalf("KOT %d split over tawas %v", k, ts)
		}
	}
	if load[0] == 0 || load[1] == 0 {
		t.Fatalf("both tawas on All should share: %v", load)
	}
	// a KOT keeps its tawa when more come
	first := map[int64]int{}
	for _, u := range us {
		first[u.Kot] = u.T
	}
	kots = append(kots, bk(105, 3, base+240_000, dosa("Masala Dosa", 6)))
	barkMu.Lock()
	_, _, us = barkSnapshot(kots, now)
	barkMu.Unlock()
	for _, u := range us {
		if t0, ok := first[u.Kot]; ok && t0 != u.T {
			t.Fatalf("KOT %d moved from tawa %d to %d", u.Kot, t0, u.T)
		}
	}
}

func TestBarkerChannelsAndCap(t *testing.T) {
	barkReset(t)
	now := time.Date(2026, 11, 2, 12, 0, 0, 0, time.Local)
	if msg := barkSetConfig(barkClean(barkCfg{Plans: map[string][]string{"default": {"D", "ips"}}, Cap: 4, Win: 3, Max: 5, Chain: true, Merge: true})); msg != "" {
		t.Fatal(msg)
	}
	base := now.UnixMilli() - 300_000
	online := bk(201, 1, base, dosa("Masala Dosa", 3))
	online.Online, online.Platform, online.POID = true, "Swiggy", "2209183136"
	kots := []Kot{online, bk(202, 2, base+1000, dosa("Masala Dosa", 2)), bk(203, 3, base+2000, dosa("Rava Dosa", 2))}
	setKots := func() {
		ppMu.Lock()
		ppKots, ppOKAt = kots, time.Now()
		ppMu.Unlock()
	}
	setKots()
	barkMu.Lock()
	_, plan, us := barkSnapshot(kots, now)
	barkMu.Unlock()
	if plan[1] != "PIS" {
		t.Fatalf("channels cleaned: %v", plan)
	}
	ids := map[int64][]string{}
	for _, u := range us {
		ids[u.Kot] = append(ids[u.Kot], u.ID)
		if u.Kot == 201 && (u.T != 0 || u.App != "Swiggy" || u.Ref != "3136" || u.Ch != "D") {
			t.Fatalf("delivery unit %+v", u)
		}
		if u.Kot != 201 && u.T != 1 {
			t.Fatalf("pick-up / dine-in on tawa %d", u.T)
		}
	}
	if msg := barkTap(now, nil, ids[202], nil); msg != "" {
		t.Fatal(msg)
	}
	if msg := barkTap(now, nil, ids[203], nil); msg != "" {
		t.Fatalf("2 on + 2 more = 4 of 4 should fit: %s", msg)
	}
	kots = append(kots, bk(204, 3, base+3000, dosa("Plain Dosa", 1)))
	setKots()
	barkMu.Lock()
	_, _, us = barkSnapshot(kots, now)
	barkMu.Unlock()
	var last string
	for _, u := range us {
		if u.Kot == 204 {
			last = u.ID
		}
	}
	if msg := barkTap(now, nil, []string{last}, nil); msg == "" {
		t.Fatal("a 5th dosa on a tawa that holds 4 must be refused")
	}
	// finishing a card and putting the next one on in one press (the pedal) fits again
	if msg := barkTap(now, ids[202], []string{last}, nil); msg != "" {
		t.Fatalf("done + next: %s", msg)
	}
	barkMu.Lock()
	_, _, us = barkSnapshot(kots, now)
	barkMu.Unlock()
	st := map[string]string{}
	for _, u := range us {
		st[u.ID] = u.St
	}
	if st[ids[202][0]] != "done" || st[last] != "on" {
		t.Fatalf("states %v", st)
	}
	// undo brings a done card back
	barkTap(now, nil, nil, ids[202])
	barkMu.Lock()
	_, _, us = barkSnapshot(kots, now)
	barkMu.Unlock()
	for _, u := range us {
		if u.ID == ids[202][0] && u.St != "wait" {
			t.Fatalf("undo: %s", u.St)
		}
	}
}
