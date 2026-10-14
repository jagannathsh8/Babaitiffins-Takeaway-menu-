package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

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

func TestBridgeLongOrderIDs(t *testing.T) {
	body := []byte(`{"ready":[{"orderId":1791976083241811062,"token":28}],"preparing":[{"order":{"id":1791976196959550050},"slot":30}]}`)
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	var root any
	if err := dec.Decode(&root); err != nil {
		t.Fatal(err)
	}
	m := map[string]string{}
	bridgeWalk(root, m, 0)
	if m["1791976083241811062"] != "28" || m["1791976196959550050"] != "30" {
		t.Fatalf("long order ids: %v", m)
	}
}

func TestBridgePollEndToEnd(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"ready":[{"orderId":250945926197377,"token":31},{"orderId":"1791976083241811062","token":"28"},{"orderId":1791976196959550050,"slot":30}]}`))
	}))
	defer srv.Close()
	addr := strings.TrimPrefix(srv.URL, "http://")
	if err := bridgePoll(addr); err != nil {
		t.Fatal(err)
	}
	for id, want := range map[string]string{"250945926197377": "31", "1791976083241811062": "28", "1791976196959550050": "30"} {
		if got := slotFor(id); got != want {
			t.Fatalf("%s: %q want %q (%v)", id, got, want, brSlots)
		}
	}
}

func TestBridgeNestedJobs(t *testing.T) {
	body := []byte(`{"settings":{"tokenLimit":32,"tokenLabel":"TOKEN"},
	 "jobs":[
	  {"id":"4fb3661d","token":22,"customization":{"tokenLimit":32},"payload":{"meta":{"order":{"id":1791982468606340038}}}},
	  {"id":"9aa","token":5,"payload":{"orderId":"250951461115032"}}],
	 "orders":[{"orderId":"1791982278801803444"},{"orderId":"1791981763126265342"}]}`)
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	var root any
	if err := dec.Decode(&root); err != nil {
		t.Fatal(err)
	}
	m := map[string]string{}
	bridgeWalk(root, m, 0)
	if m["1791982468606340038"] != "22" || m["250951461115032"] != "5" {
		t.Fatalf("nested: %v", m)
	}
	if _, ok := m["1791982278801803444"]; ok {
		t.Fatalf("order without a token got one: %v", m)
	}
}
