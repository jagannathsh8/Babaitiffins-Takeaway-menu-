package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
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

func TestBridgeKeepsSlotsAfterJobLeaves(t *testing.T) {
	body := `{"jobs":[{"token":24,"orderId":"1791982801171825483"},{"token":7,"orderId":"250951448167204"}]}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/api/state" {
			_, _ = w.Write([]byte(body))
			return
		}
		http.NotFound(w, r)
	}))
	defer srv.Close()
	addr := strings.TrimPrefix(srv.URL, "http://")
	if err := bridgePoll(addr); err != nil {
		t.Fatal(err)
	}
	body = `{"jobs":[{"token":7,"orderId":"250951448167204"}]}` // the Ownly job left the list
	brMu.Lock()
	brProbeAt = time.Time{}
	brMu.Unlock()
	if err := bridgePoll(addr); err != nil {
		t.Fatal(err)
	}
	if slotFor("1791982801171825483") != "24" || slotFor("250951448167204") != "7" {
		t.Fatalf("slots %v / seen %v", brSlots, brSeen)
	}
}

func TestBridgeRoundedOwnlyIDs(t *testing.T) {
	// Bridge Print (JavaScript) rounds 19-digit IDs: 1791982601318835770 -> 1791982601318835700
	body := `{"jobs":[{"token":12,"orderId":1791982601318835700},{"token":13,"orderId":1791982537269922000},{"token":7,"orderId":250951448167204}]}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(body)) }))
	defer srv.Close()
	brMu.Lock()
	brSeen, brProbeAt, brPathsAll = map[string]brSeenSlot{}, time.Time{}, nil
	brMu.Unlock()
	if err := bridgePoll(strings.TrimPrefix(srv.URL, "http://")); err != nil {
		t.Fatal(err)
	}
	if s := slotFor("1791982601318835770"); s != "12" {
		t.Fatalf("5770 -> %q", s)
	}
	if s := slotFor("1791982537269921921"); s != "13" {
		t.Fatalf("1921 -> %q", s)
	}
	if s := slotFor("1791999999999999999"); s != "" {
		t.Fatalf("unrelated order got slot %q", s)
	}
	if slotFor("250951448167204") != "7" {
		t.Fatal("swiggy")
	}
}

func TestBridgeFindOwnly(t *testing.T) {
	ppMu.Lock()
	ppKots = []Kot{{ID: 9, Status: "1", POID: "1791982601318835770", Online: true}}
	ppMu.Unlock()
	brMu.Lock()
	brRaw = []byte(`### /api/state` + "\n" + `{"jobs":[{"token":12,"orderId":1791982601318835700}]}`)
	brSeen = map[string]brSeenSlot{"1791982601318835700": {"12", time.Now()}}
	brMu.Unlock()
	w := httptest.NewRecorder()
	handleBridgeFind(w, httptest.NewRequest(http.MethodGet, "/bridge/find?q=5770", nil))
	out := w.Body.String()
	if !strings.Contains(out, "Petpooja order: 1791982601318835770") || !strings.Contains(out, "slot 12") || !strings.Contains(out, `"token":12`) {
		t.Fatal(out)
	}
}

func TestBridgeJobBothOrderIDs(t *testing.T) {
	// metadata.orderId read from the KOT (right) and petpooja.orderId rounded by Bridge Print's feed
	body := []byte(`{"jobs":[{"token":31,"metadata":{"brand":"Ownly","orderId":"1791976083241811062"},"petpooja":{"orderId":"1791976083241811000","status":"preparing"}}]}`)
	dec := json.NewDecoder(bytes.NewReader(body))
	dec.UseNumber()
	var root any
	if err := dec.Decode(&root); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 20; i++ { // map order is random: must hold every time
		m := map[string]string{}
		bridgeWalk(root, m, 0)
		if m["1791976083241811062"] != "31" {
			t.Fatalf("run %d: %v", i, m)
		}
	}
}
