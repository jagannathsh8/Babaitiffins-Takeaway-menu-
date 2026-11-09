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
