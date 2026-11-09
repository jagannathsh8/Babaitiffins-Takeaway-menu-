package main

import (
	"testing"
)

func TestDirectPlan(t *testing.T) {
	k := Kot{ID: 1, OrderType: 1, Online: true, Platform: "Swiggy", POID: "252475655137731", Items: []KItem{
		{N: "Masala Dosa", C: "Dosa", Q: 2}, {N: "Filter Coffee", C: "Beverages", Q: 1}, {N: "Idli", C: "Idli", Q: 1}, {N: "Gone", C: "Dosa", Q: 1, S: -9}}}
	// a config saved before 4.11 (one main printer, delivery always on) is read as before
	c := cleanDirect(DirectCfg{Mode: "console", Main: "EPSON", MainBackups: []string{"TVS", "EPSON", "TVS", "R58", "X"},
		Lines: []DirectLine{{Kind: "cat", Values: []string{"Dosa"}, Printer: "TVS"}, {Kind: "item", Values: []string{"filter coffee"}, Printer: "COFFEE"}}})
	if len(c.Mains) != 1 || c.Main != "" || len(c.Mains[0].Backups) != 3 || c.Mains[0].Backups[0] != "TVS" || c.Mains[0].Backups[2] != "X" {
		t.Fatalf("old main moved into Mains: %+v", c.Mains)
	}
	if !containsStr(c.Types, "D") || !c.TypesV2 {
		t.Fatalf("old configs keep delivery on: %v", c.Types)
	}
	p := directPlan(c, k)
	if len(p) != 3 || !p[0].bill || len(p[0].items) != 3 || p[0].printers[0] != "EPSON" || len(p[0].printers) != 4 {
		t.Fatalf("main gets the whole order (cancelled dish left out): %+v", p)
	}
	if p[1].station != "Dosa" || len(p[1].items) != 1 || p[1].items[0].N != "Masala Dosa" || len(p[1].also) != 2 || p[1].bill {
		t.Fatalf("dosa line: %+v", p[1])
	}
	if p[2].printers[0] != "COFFEE" || p[2].items[0].N != "Filter Coffee" {
		t.Fatalf("item line: %+v", p[2])
	}
	c.MainRest = true
	p = directPlan(c, k)
	if len(p[0].items) != 1 || p[0].items[0].N != "Idli" {
		t.Fatalf("main prints only the rest: %+v", p[0].items)
	}
	c.Lines = append([]DirectLine{{Kind: "cat", Values: []string{"Beverages"}, Printer: "JUICE"}}, c.Lines...)
	for _, s := range directPlan(c, k) {
		if s.printers[0] == "JUICE" {
			t.Fatal("Filter Coffee belongs to its item line, not the Beverages category line")
		}
	}
}

func TestDirectWants(t *testing.T) {
	online := Kot{OrderType: 1, Online: true, Platform: "Zomato"}
	pick := Kot{OrderType: 2}
	dine := Kot{OrderType: 3, Table: "4"}
	c := cleanDirect(DirectCfg{Mode: "bridge", TypesV2: true, Types: []string{"D"}, Mains: []MainPrinter{{Printer: "EPSON"}}})
	if directWants(c, online) {
		t.Fatal("bridge mode prints nothing itself")
	}
	c.Mode = "both"
	if !directWants(c, online) || directWants(c, pick) || directWants(c, dine) {
		t.Fatal("delivery / online by default; others only when ticked")
	}
	c.Types = []string{"P"} // delivery unticked, pick up ticked
	if directWants(c, online) || !directWants(c, pick) || directWants(c, dine) {
		t.Fatal("delivery can be unticked")
	}
	c.Mains = nil
	if directWants(c, pick) {
		t.Fatal("no printer chosen: Bridge Print keeps printing")
	}
}

func TestPaperDots(t *testing.T) {
	for name, want := range map[string]int{"Retsol 58": 384, "POS-58": 384, "XP-58IIH": 384, "EPSON TM-T82X": 576, "TVS RP3160 Gold": 576, "Printer 580": 576} {
		if got := paperDots(name); got != want {
			t.Errorf("%s: %d, want %d", name, got, want)
		}
	}
}

func TestDirectSubMainsAndSwitches(t *testing.T) {
	dosaBill := Kot{ID: 1, OrderType: 1, Online: true, Platform: "Swiggy", POID: "252475655137731", Items: []KItem{
		{N: "Masala Dosa", C: "Dosa", Q: 2}, {N: "Idli", C: "Idli", Q: 1}}}
	idliBill := Kot{ID: 2, OrderType: 1, Online: true, Platform: "Zomato", Items: []KItem{{N: "Idli", C: "Idli", Q: 1}}}
	dineDosa := Kot{ID: 3, OrderType: 3, Items: []KItem{{N: "Masala Dosa", C: "Dosa", Q: 1}}}
	c := cleanDirect(DirectCfg{Mode: "both", TypesV2: true, Types: []string{"D", "I"},
		Mains: []MainPrinter{{Printer: "EPSON"}, {Printer: "PACKING"}},
		Subs:  []SubMain{{Cats: []string{"Dosa"}, Printer: "TVS"}},
		Lines: []DirectLine{{Kind: "cat", Values: []string{"Dosa"}, Printer: "DOSA"}}})
	p := directPlan(c, dosaBill)
	if p[0].name != "Sub main 1" || p[0].printers[0] != "TVS" || len(p[0].items) != 2 || len(p) != 2 {
		t.Fatalf("a bill with dosa prints whole on the sub main only (+ the dosa line): %+v", p)
	}
	if s := slipForOTP(dosaBill, 47, p[0], false, "5557", "Delivery Passcode"); s.Token != 47 || s.Station != "" || s.OTP != "5557" || s.OTPName != "Delivery Passcode" {
		t.Fatalf("the bill slip has the slot and the OTP: %+v", s)
	}
	if s := slipForOTP(dineDosa, 5, directPlan(c, dineDosa)[0], false, "", ""); s.Code != kotScanCode(dineDosa) || s.Code == "" || s.OrderID != "" {
		t.Fatalf("dine in slips get the KOT's scan code: %+v", s)
	}
	if s := slipForOTP(dosaBill, 47, p[0], false, "", ""); s.Code != "252475655137731" {
		t.Fatal("delivery slips: the order ID barcode")
	}
	if s := slipForOTP(dosaBill, 47, p[1], false, "5557", "Delivery Passcode"); s.Token != 0 || s.Station != "Dosa" || s.OTP != "" {
		t.Fatal("the station slip has no slot and no OTP")
	}
	if p := directPlan(c, idliBill); len(p) != 2 || p[0].printers[0] != "EPSON" || p[1].printers[0] != "PACKING" || !p[1].bill {
		t.Fatalf("other bills: one copy on every main: %+v", p)
	}
	c.Subs[0].Off = true // sub main off: its bills go to the mains
	if p := directPlan(c, dosaBill); p[0].printers[0] != "EPSON" || p[1].printers[0] != "PACKING" {
		t.Fatal("sub main off: the mains print the bill")
	}
	c.Subs[0].Off = false
	c.Subs[0].Skip = []string{"I"} // sub main not for dine in
	if p := directPlan(c, dineDosa); p[0].printers[0] != "EPSON" {
		t.Fatal("sub main skips dine in")
	}
	c.Mains[1].Off = true // one main off: only the other prints
	if p := directPlan(c, idliBill); len(p) != 1 || p[0].printers[0] != "EPSON" {
		t.Fatal("main 2 off")
	}
	c.Lines[0].Off = true
	if p := directPlan(c, dosaBill); len(p) != 1 {
		t.Fatal("line off")
	}
	c.Lines[0].Off, c.Lines[0].Skip = false, []string{"D"}
	if p := directPlan(c, dosaBill); len(p) != 1 {
		t.Fatal("line skips delivery")
	}
	c.Mains[0].Off = true // every main off: Bridge Print prints the idli bill
	if directWants(c, idliBill) {
		t.Fatal("mains off: Bridge Print prints the bill")
	}
	if !directWants(c, dosaBill) {
		t.Fatal("the sub main still takes dosa bills")
	}
}
