package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// Console only (no Bridge Print slip at all): the slot the Console prints is the slot the rider
// screen shows for that order.
func TestConsoleOnlySlotOnRiderScreen(t *testing.T) {
	dataDir = t.TempDir()
	_ = os.MkdirAll(filepath.Join(dataDir, "print"), 0o755)
	prMu.Lock()
	prJobs, prCounter = nil, counterState{}
	prMu.Unlock()
	k := Kot{ID: 777001, Token: 1874, OrderType: 1, Online: true, Platform: "Zomato", POID: "8824477281",
		Items: []KItem{{N: "Masala Dosa", C: "Dosa", Q: 1}}}
	c := cleanDirect(DirectCfg{Mode: "console", TypesV2: true, Types: []string{"D"}, Mains: []MainPrinter{{Printer: "lan:127.0.0.1:9"}}})
	done := make(chan struct{})
	go func() { directPrint(c, k, time.Now()); close(done) }()
	deadline := time.Now().Add(5 * time.Second)
	var slot string
	for time.Now().Before(deadline) {
		if slot = slotForKot(k.ID, k.POID); slot != "" && slot != "0" {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	prMu.Lock()
	var tok int
	for _, j := range prJobs {
		if j.Kot == k.ID {
			tok = j.Token
		}
	}
	prMu.Unlock()
	if tok <= 0 || slot != itoa(tok) {
		t.Fatalf("console-only slot %d, rider screen shows %q", tok, slot)
	}
	t.Logf("console printed slot %d; rider screen slot %q", tok, slot)
	select {
	case <-done:
	case <-time.After(20 * time.Second):
	}
}

// Reprint (4.16.1): the order's Console slip is the one reprinted, also when asked from Petpooja's
// slip of the order; a Petpooja slip that was not printed is not printed by a reprint either.
func TestReprintPicksTheOrdersSlip(t *testing.T) {
	dataDir = t.TempDir()
	_ = os.MkdirAll(filepath.Join(dataDir, "print"), 0o755)
	prMu.Lock()
	pp := &Job{ID: "pp-1", Kot: 880001, Token: 12, OrderType: 1, Status: "sent", Destination: "Not printed (the Console printed this order)"}
	cj := &Job{ID: "con-1", Kot: 880001, Token: 12, OrderType: 1, Source: "console", Status: "sending"}
	skip := &Job{ID: "pp-2", Kot: 880002, OrderType: 3, Status: "sent", Destination: "Not printed (no printer is set for Dine in)"}
	prJobs = []*Job{pp, cj, skip}
	prMu.Unlock()
	if ok, msg := reprintOrder("pp-1", 0); ok || msg != "This slip is printing now." {
		t.Fatalf("asked from Petpooja's slip: want the Console's slip (busy), got %v %q", ok, msg)
	}
	if ok, msg := reprintOrder("", 880001); ok || msg != "This slip is printing now." {
		t.Fatalf("asked by KOT: want the Console's slip (busy), got %v %q", ok, msg)
	}
	if ok, _ := reprintOrder("pp-2", 0); ok {
		t.Fatal("an order no printer is set for was reprinted")
	}
	if ok, _ := reprintOrder("", 999); ok {
		t.Fatal("reprint of an unknown order")
	}
	prMu.Lock()
	prJobs = nil
	prMu.Unlock()
}
