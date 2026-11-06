package main

import (
	"testing"
)

func TestDirectPlan(t *testing.T) {
	k := Kot{ID: 1, OrderType: 1, Online: true, Platform: "Swiggy", POID: "252475655137731", Items: []KItem{
		{N: "Masala Dosa", C: "Dosa", Q: 2}, {N: "Filter Coffee", C: "Beverages", Q: 1}, {N: "Idli", C: "Idli", Q: 1}, {N: "Gone", C: "Dosa", Q: 1, S: -9}}}
	c := cleanDirect(DirectCfg{Mode: "console", Main: "EPSON", MainBackups: []string{"TVS", "EPSON", "TVS", "R58", "X"},
		Lines: []DirectLine{{Kind: "cat", Values: []string{"Dosa"}, Printer: "TVS"}, {Kind: "item", Values: []string{"filter coffee"}, Printer: "COFFEE"}}})
	if len(c.MainBackups) != 3 || c.MainBackups[0] != "TVS" || c.MainBackups[2] != "X" {
		t.Fatalf("backups: %v", c.MainBackups)
	}
	p := directPlan(c, k)
	if len(p) != 3 || len(p[0].items) != 3 || p[0].printers[0] != "EPSON" || len(p[0].printers) != 4 {
		t.Fatalf("main gets the whole order (cancelled dish left out): %+v", p)
	}
	if p[1].station != "Dosa" || len(p[1].items) != 1 || p[1].items[0].N != "Masala Dosa" || len(p[1].also) != 2 {
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
	// an item line wins over a category line
	c.Lines = append([]DirectLine{{Kind: "cat", Values: []string{"Beverages"}, Printer: "JUICE"}}, c.Lines...)
	p = directPlan(c, k)
	for _, s := range p {
		if s.printers[0] == "JUICE" {
			t.Fatal("Filter Coffee belongs to its item line, not the Beverages category line")
		}
	}
}

func TestDirectWants(t *testing.T) {
	online := Kot{OrderType: 1, Online: true, Platform: "Zomato"}
	pick := Kot{OrderType: 2}
	dine := Kot{OrderType: 3, Table: "4"}
	c := DirectCfg{Mode: "bridge", Main: "EPSON"}
	if directWants(c, online) {
		t.Fatal("bridge mode prints nothing itself")
	}
	c.Mode = "both"
	if !directWants(c, online) || directWants(c, pick) || directWants(c, dine) {
		t.Fatal("delivery / online always; others only when ticked")
	}
	c.Types = []string{"P"}
	if !directWants(c, pick) || directWants(c, dine) {
		t.Fatal("pick up ticked")
	}
	c.Main = ""
	if directWants(c, online) {
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
	c := cleanDirect(DirectCfg{Mode: "both", Types: []string{"I"}, Main: "EPSON",
		Subs:  []SubMain{{Cats: []string{"Dosa"}, Printer: "TVS"}},
		Lines: []DirectLine{{Kind: "cat", Values: []string{"Dosa"}, Printer: "DOSA"}}})
	p := directPlan(c, dosaBill)
	if p[0].name != "Sub main 1" || p[0].printers[0] != "TVS" || len(p[0].items) != 2 {
		t.Fatalf("a bill with dosa prints whole on the sub main: %+v", p[0])
	}
	if s := slipFor(dosaBill, 47, p[0], false); s.Token != 47 || s.Station != "" {
		t.Fatal("the bill slip has the slot")
	}
	if s := slipFor(dosaBill, 47, p[1], false); s.Token != 0 || s.Station != "Dosa" {
		t.Fatal("the station slip has no slot")
	}
	if p := directPlan(c, idliBill); p[0].name != "Main printer" || len(p) != 1 {
		t.Fatalf("other bills: main: %+v", p)
	}
	// sub main switched off: its bills go to the main
	c.Subs[0].Off = true
	if p := directPlan(c, dosaBill); p[0].printers[0] != "EPSON" {
		t.Fatal("sub main off: the main prints the bill")
	}
	c.Subs[0].Off = false
	// sub main not for dine in: dine-in dosa bills go to the main
	c.Subs[0].Skip = []string{"I"}
	if p := directPlan(c, dineDosa); p[0].printers[0] != "EPSON" {
		t.Fatal("sub main skips dine in")
	}
	// a line switched off / not for this order type prints nothing
	c.Lines[0].Off = true
	if p := directPlan(c, dosaBill); len(p) != 1 {
		t.Fatal("line off")
	}
	c.Lines[0].Off, c.Lines[0].Skip = false, []string{"D"}
	if p := directPlan(c, dosaBill); len(p) != 1 {
		t.Fatal("line skips delivery")
	}
	// main switched off: an idli bill has no bill printer, so Bridge Print prints it
	c.MainOff = true
	if directWants(c, idliBill) {
		t.Fatal("main off: Bridge Print prints the bill")
	}
	if !directWants(c, dosaBill) {
		t.Fatal("the sub main still takes dosa bills")
	}
}
