package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// Search finds an order by its last digits among the last 3 days, whatever its status.
func TestFindOrdersLast3Days(t *testing.T) {
	_, _, done := setup(t)
	defer done()
	resetPrintState()
	now := time.Now()
	day := func(d int) string { return now.AddDate(0, 0, -d).Format("2006-01-02") }
	write := func(d int, list []OrderRec) {
		_ = os.MkdirAll(ordersDir(), 0o755)
		b, _ := json.Marshal(list)
		if err := writeFileAtomic(filepath.Join(ordersDir(), day(d)+".json"), b); err != nil {
			t.Fatal(err)
		}
	}
	seen := func(h time.Duration) int64 { return now.Add(-h).UnixMilli() }
	write(0, []OrderRec{{Kot: 1, OrderID: "8796204531673", KotNo: 898, Brand: "Zomato", Type: 1, Status: "dispatched", Seen: seen(time.Hour), Token: 23}})
	write(1, []OrderRec{{Kot: 2, OrderID: "252476677311673", KotNo: 2214, Brand: "Swiggy", Type: 1, Status: "dispatched", Seen: seen(26 * time.Hour)},
		{Kot: 3, OrderID: "111", KotNo: 1673, Type: 3, Status: "preparing", Seen: seen(25 * time.Hour)}})
	write(5, []OrderRec{{Kot: 4, OrderID: "999991673", Brand: "Ownly", Status: "dispatched", Seen: seen(5 * 24 * time.Hour)}})

	got := findOrders("1673", now)
	if len(got) != 3 || got[0].Kot != 1 || got[1].Kot != 3 || got[2].Kot != 2 {
		t.Fatalf("want the 3 orders of the last 3 days, newest first (order IDs and the KOT number), got %+v", got)
	}
	w := httptest.NewRecorder()
	handleFindOrders(w, httptest.NewRequest(http.MethodGet, "/console/api/find?q=16", nil))
	var r struct{ Orders []OrderRec }
	_ = json.Unmarshal(w.Body.Bytes(), &r)
	if len(r.Orders) != 0 {
		t.Fatal("fewer than 3 digits: no search")
	}
}

// The print-rule menu keeps every category Petpooja has sent, not only what is open now.
func TestMenuRemembersCategories(t *testing.T) {
	_, _, done := setup(t)
	defer done()
	resetPrintState()
	menuMu.Lock()
	menuSeen = nil
	menuMu.Unlock()
	// a saved order from last month had a Chaat item
	_ = os.MkdirAll(ordersDir(), 0o755)
	b, _ := json.Marshal([]OrderRec{{Kot: 9, Seen: time.Now().AddDate(0, -1, 0).UnixMilli(), Items: []KItem{{N: "Pani Puri", C: "Chaat", Q: 1}}}})
	_ = writeFileAtomic(filepath.Join(ordersDir(), time.Now().AddDate(0, -1, 0).Format("2006-01-02")+".json"), b)
	// today's read: a dosa order, and the item on/off list without Chaat
	noteMenu([]Kot{{ID: 1, Items: []KItem{{N: "Masala Dosa", C: "Dosa", Q: 1}}}}, []StockItem{{Name: "Filter Coffee", Category: "Beverages"}}, time.Now())
	ppMu.Lock()
	ppKots, ppStock = nil, nil // nothing open now
	ppMu.Unlock()
	w := httptest.NewRecorder()
	handleMenu(w, httptest.NewRequest(http.MethodGet, "/console/api/menu", nil))
	var m struct {
		Cats []struct {
			Name  string
			Items []string
		}
	}
	_ = json.Unmarshal(w.Body.Bytes(), &m)
	has := map[string]string{}
	for _, c := range m.Cats {
		for _, it := range c.Items {
			has[c.Name] = it
		}
	}
	if has["Chaat"] != "Pani Puri" || has["Dosa"] != "Masala Dosa" || has["Beverages"] != "Filter Coffee" {
		t.Fatalf("menu: %s", w.Body.String())
	}
}
