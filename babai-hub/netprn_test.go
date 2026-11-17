package main

import (
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// A network printer whose IP changed is found again by its hardware address and takes the new
// IP; one that is nowhere on the network gets one alert and keeps its address.
func TestLanPrinterFollowsItsHardwareID(t *testing.T) {
	dataDir = t.TempDir()
	_ = os.MkdirAll(filepath.Join(dataDir, "print"), 0o755)
	ln, err := net.Listen("tcp", "127.0.0.1:0") // the printer at its new address
	if err != nil {
		t.Skip(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	port, _ := strconv.Atoi(ln.Addr().String()[strings.LastIndex(ln.Addr().String(), ":")+1:])
	oldMac, oldScan, oldRetry := macLookup, lanScan, lanRetry
	defer func() { macLookup, lanScan, lanRetry = oldMac, oldScan, oldRetry }()
	lanRetry = 0
	macLookup = func(ip string) string {
		return map[string]string{"127.0.0.1": "00:1E:C0:4A:9B:21", "127.0.0.9": "AA:AA:AA:AA:AA:AA"}[ip]
	}
	lanScan = func(p int) []string { return []string{"127.0.0.9", "127.0.0.1"} }
	prMu.Lock()
	oldLan := prOpts.Lan
	prOpts.Lan = []LanPrinter{{Name: "Kitchen", IP: "127.0.0.77", Port: port, MAC: "00:1e:c0:4a:9b:21"}, {Name: "Tea", IP: "127.0.0.78", Port: port, MAC: "BB:BB:BB:BB:BB:BB"}}
	prMu.Unlock()
	defer func() { prMu.Lock(); prOpts.Lan = oldLan; prMu.Unlock() }()
	alertMu.Lock()
	alerts = nil
	alertMu.Unlock()
	if !lanRelocate("Kitchen") {
		t.Fatal("Kitchen not found at its new address")
	}
	if addr, _ := lanTarget("lan:Kitchen"); addr != "127.0.0.1:"+strconv.Itoa(port) {
		t.Fatalf("Kitchen prints to %s", addr)
	}
	if lanRelocate("Tea") || lanRelocate("Tea") {
		t.Fatal("Tea is nowhere: it must not move")
	}
	if addr, _ := lanTarget("lan:Tea"); !strings.HasPrefix(addr, "127.0.0.78:") {
		t.Fatalf("Tea's address changed: %s", addr)
	}
	var moved, lost int
	for _, a := range alertsSnapshot() {
		if strings.Contains(a.Msg, "Kitchen moved to 127.0.0.1 (was 127.0.0.77)") && a.Level == "ok" {
			moved++
		}
		if strings.Contains(a.Msg, "Tea: not answering") && a.Level == "bad" {
			lost++
		}
	}
	if moved != 1 || lost != 1 {
		t.Fatalf("alerts: moved %d (want 1), not found %d (want 1, not one per search)", moved, lost)
	}
}
