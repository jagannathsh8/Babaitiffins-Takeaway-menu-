package main

import (
	"testing"
	"time"
)

// EatIn By Minutes prints "EatIn By Minutes :" with the order number on the next line, and a
// "Prepare By : <date> <time>" line that must never be taken for a brand and an order ID.
func TestEatInSlip(t *testing.T) {
	const id = "4937881707454"
	cases := map[string]string{
		"text":                 "03/11/26 07:46\nKOT - 74\nEatIn By Minutes :\n4937881707454\nDelivery\nItem Qty.\nPesarattu 1\n[Note] Pesarattu\nUggani Bajji 1\nPayment Status : Online Paid\nPrepare By : 2026-11-03 07:56:48\nScan to Mark food ready\n4937881707454",
		"time on its own line": "03/11/26 07:46\nKOT - 74\nEatIn By Minutes :\n4937881707454\nDelivery\nPesarattu 1\nPayment Status : Online Paid\nPrepare By : 2026-11-03\n07:56:48\nScan to Mark food ready\n4937881707454",
		"bold digits split":    "03/11/26 07:46\nKOT - 74\nEatln By Minutes :\n493788170 7454\nDelivery\nPesarattu 1\nPayment Status : Online Paid\nPrepare By : 2026-11-03\n07:56:48",
		"only barcode digits":  "03/11/26 07:46\nKOT - 74\nEatIn By Minutes :\nDelivery\nPesarattu 1\nPayment Status : Online Paid\nPrepare By : 2026-11-03\n4937881707454",
		"O and l for 0 and 1":  "03/11/26 07:46\nKOT - 74\nEatIn By Minutes :\n49378817O7454\nDelivery\nPrepare By : 2026-11-03\n07:56:48",
	}
	for name, s := range cases {
		m := parseReceiptText(s)
		if m.Brand != "EatIn By Minutes" || m.OrderID != id {
			t.Errorf("%s: got brand %q order %q", name, m.Brand, m.OrderID)
		}
	}
	m := parseReceiptText("03/11/26 07:46\nKOT - 74\nEatIn By Minutes :\nDelivery\nPesarattu 1\nPrepare By : 2026-11-03\nScan to Mark food ready")
	if m.Brand != "EatIn By Minutes" || m.OrderID != "" {
		t.Errorf("no number on the slip: got brand %q order %q (want the brand, no order: matched by timing)", m.Brand, m.OrderID)
	}
	// the other brands read as before, even with a Prepare By line
	same := map[string][2]string{
		"Swiggy 12\n03/11/26 07:40\nKOT - 70\nSwiggy : 252475675152540\nDelivery\nMasala Dosa 1\nPrepare By : 2026-11-03 07:50:10\nScan to Mark food ready\n252475675152540": {"Swiggy", "252475675152540"},
		"03/11/26 07:41\nKOT - 71\nZomato : 8794428444\nDelivery\nIdli 2\nPrepare By : 2026-11-03\n07:51:00":                                                                 {"Zomato", "8794428444"},
		"Ownly 28\nKOT - 1171\nOwnly : 1791976083241811062\nDelivery\nPrepare By : 2026-11-03":                                                                               {"Ownly", "1791976083241811062"},
		"TOING BY SWIGGY\n252474992162142\nKOT - 9\nDelivery":                                                                                                                {"Swiggy", "252474992162142"},
		"Order ID : 98765432\nKOT - 59\nDelivery":                                                                                                                            {"", "98765432"},
	}
	for s, want := range same {
		if m := parseReceiptText(s); m.Brand != want[0] || m.OrderID != want[1] {
			t.Errorf("%q: got brand %q order %q, want %q %q", s, m.Brand, m.OrderID, want[0], want[1])
		}
	}
}

// The EatIn print links to its order, so the rider screen gets its slot (the print's token).
func TestEatInPrintLinksToItsOrder(t *testing.T) {
	now := time.Now()
	mkMu.Lock()
	old := mkKots
	mkKots = []Kot{
		{ID: 501, POID: "4937881707454", Platform: "EatIn By Minutes", Online: true, OrderType: 1, Created: now.Add(-20 * time.Second).UnixMilli(), Status: "1"},
		{ID: 502, POID: "252475675152540", Platform: "Swiggy", Online: true, OrderType: 1, Created: now.Add(-10 * time.Second).UnixMilli(), Status: "1"},
	}
	m := parseReceiptText("03/11/26 07:46\nKOT - 74\nEatIn By Minutes :\n493788170 7454\nDelivery\nPesarattu 1\nPrepare By : 2026-11-03\n07:56:48")
	j := &Job{ID: "j42", Token: 42, Recv: now.UnixMilli()}
	r := tryMatch(j, &m, []*Job{j})
	mkKots = old
	mkMu.Unlock()
	if r == nil || r.kot.ID != 501 {
		t.Fatalf("EatIn print not linked to its order: %+v", r)
	}
}
