package main

import "testing"

func TestHiddenBrands(t *testing.T) {
	old := cfg
	defer func() { cfg = old }()
	cfg = Config{}
	if !kotHidden(Kot{Channel: "GO SELF-SERVICE"}) || !kotHidden(Kot{Platform: "Go Self Service Kiosk"}) {
		t.Fatal("default Go Self Service not hidden")
	}
	if kotHidden(Kot{Platform: "Swiggy"}) || kotHidden(Kot{}) {
		t.Fatal("other orders hidden")
	}
	cfg = Config{HideSet: true}
	if kotHidden(Kot{Channel: "Go Self Service"}) {
		t.Fatal("empty list should hide nothing")
	}
	cfg = Config{HideSet: true, HideBrands: []string{"Ownly"}}
	if !kotHidden(Kot{Channel: "ownly"}) || kotHidden(Kot{Channel: "Go Self Service"}) {
		t.Fatal("custom list")
	}
	o := []Kot{{ID: 1, Online: true, Status: "1", Platform: "Swiggy"}, {ID: 2, Online: true, Status: "1", Channel: "Ownly"}}
	if r := riderOrders(o); len(r) != 1 {
		t.Fatalf("rider feed kept a hidden brand: %d orders", len(r))
	}
}
