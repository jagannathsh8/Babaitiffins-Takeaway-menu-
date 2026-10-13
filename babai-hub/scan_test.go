package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// onlineRow: one item line of an online order (Swiggy user id 6736).
func onlineRow(id, token int64, kotSt, poid string, created time.Time, name string, itemID int64, st int) string {
	return fmt.Sprintf(`{"id":%d,"kot_unique_id":%d,"table_no":"","order_type":1,"kot_status":"%s","created":"%s","p_o_id":%q,"o_u_id":"6736","name":%q,"category":"Dosa","quantity":1,"status":%d,"item_id":%d}`,
		id, token, kotSt, created.Format("2006-01-02 15:04:05"), poid, name, st, itemID)
}

func TestScanMarksFoodReady(t *testing.T) {
	_, pp, done := setup(t)
	defer done()
	now := time.Now()
	pp.set(
		onlineRow(501, 41, "1", "250787919580744", now.Add(-9*time.Minute), "Masala Dosa", 1308051404, 1),
		onlineRow(501, 41, "1", "250787919580744", now.Add(-9*time.Minute), "Filter Coffee", 1308051405, 9), // already ready
		onlineRow(501, 41, "1", "250787919580744", now.Add(-9*time.Minute), "Vada", 1308051406, -9),         // cancelled
		onlineRow(502, 42, "1", "8496851046", now.Add(-5*time.Minute), "Idli", 1308051407, 1),               // Zomato-like id
		onlineRow(503, 43, "9", "250712345678001", now.Add(-20*time.Minute), "Pesarattu", 1308051408, 9),    // already ready
		row(600, 7, 3, "1", now, "Ghee Dosa", "Dosa", 1, 1),                                                 // dine-in
	)
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	brMu.Lock()
	brSlots, brOKAt = map[string]string{"250787919580744": "40"}, time.Now()
	brMu.Unlock()

	// the KOT barcode with the platform order ID
	r := markFromScan("a1", "250787919580744", "Counter phone", "wifi")
	if !r.OK || r.N != 1 || r.Brand != "Swiggy" || r.Last4 != "0744" || r.Slot != "40" {
		t.Fatalf("scan result %+v", r)
	}
	pp.mu.Lock()
	if len(pp.marks) != 1 {
		t.Fatalf("want 1 change_kot_status, got %d", len(pp.marks))
	}
	m := pp.marks[0]
	pp.mu.Unlock()
	ids, _ := json.Marshal(m["kot_item_id"])
	if m["kot_id"] != float64(501) || m["kot_unique_id"] != float64(41) || string(ids) != "[1308051404]" ||
		m["kot_item_status"] != "9" || m["oh_status"] != "1" || m["order_type"] != float64(1) ||
		m["sync_code"] != "SYNC123" || m["clientIdentifier"] != "android_kds" || m["item_food_ready_print"] != "0" {
		t.Fatalf("request body %v", m)
	}

	// same scan id again (phone sent it on Wi-Fi and internet): no second request
	r2 := markFromScan("a1", "250787919580744", "Counter phone", "internet")
	pp.mu.Lock()
	n := len(pp.marks)
	pp.mu.Unlock()
	if n != 1 || !r2.OK {
		t.Fatalf("duplicate scan id sent again (%d) %+v", n, r2)
	}

	// last 4 only (the KDS rule)
	r = markFromScan("b1", "XX1046", "", "wifi")
	if !r.OK || r.Last4 != "1046" {
		t.Fatalf("4-digit scan %+v", r)
	}
	// already ready
	r = markFromScan("c1", "250712345678001", "", "wifi")
	if !r.OK || !r.Already || r.N != 0 {
		t.Fatalf("already ready %+v", r)
	}
	// unknown / dine-in tokens are not online orders with that id
	r = markFromScan("d1", "99999999", "", "wifi")
	if r.OK || !strings.Contains(r.Msg, "No preparing order ending 9999") {
		t.Fatalf("unknown %+v", r)
	}
	r = markFromScan("e1", "12", "", "wifi")
	if r.OK || !strings.Contains(r.Msg, "4 digits") {
		t.Fatalf("short %+v", r)
	}
	if len(scanLogSnapshot()) < 5 {
		t.Fatalf("scan log %d", len(scanLogSnapshot()))
	}
}

func TestMatchLongestTail(t *testing.T) {
	kots := []Kot{
		{ID: 1, Status: "1", POID: "250700000001234"},
		{ID: 2, Status: "1", POID: "250799999991234"},
		{ID: 3, Status: "10", POID: "250799999991234"}, // dispatched: never
	}
	m, n := matchKots(kots, "250799999991234")
	if len(m) != 1 || m[0].ID != 2 || n != 15 {
		t.Fatalf("longest tail: %v %d", m, n)
	}
	m, _ = matchKots(kots, "1234")
	if len(m) != 2 {
		t.Fatalf("4 digits matches both open ones like the KDS: %v", m)
	}
}

func TestScanEndpointNeedsPairedPhone(t *testing.T) {
	_, pp, done := setup(t)
	defer done()
	pp.set(onlineRow(501, 41, "1", "250787919580744", time.Now(), "Masala Dosa", 1308051404, 1))
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	key := newPairKey()
	cfg.Phones = []Phone{{Key: key, Name: "Packing"}}
	post := func(k string) *httptest.ResponseRecorder {
		b, _ := json.Marshal(map[string]string{"k": k, "id": "z9", "c": "250787919580744", "d": "x"})
		w := httptest.NewRecorder()
		handleScan(w, httptest.NewRequest(http.MethodPost, "/scan", bytes.NewReader(b)))
		return w
	}
	if w := post(newPairKey()); w.Code != http.StatusForbidden {
		t.Fatalf("unpaired key: %d", w.Code)
	}
	w := post(key)
	var res ScanResult
	_ = json.Unmarshal(w.Body.Bytes(), &res)
	if w.Code != 200 || !res.OK {
		t.Fatalf("paired: %d %s", w.Code, w.Body.String())
	}
	if e := scanLogSnapshot()[0]; e.Device != "Packing" || e.Route != "wifi" {
		t.Fatalf("log %+v", e)
	}
	if len(key) != pairKeyLen || !validKey(key) || validKey("short") {
		t.Fatal("key format")
	}
}

func TestInternetQueue(t *testing.T) {
	fb, pp, done := setup(t)
	defer done()
	pp.set(onlineRow(501, 41, "1", "250787919580744", time.Now(), "Masala Dosa", 1308051404, 1))
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	key := newPairKey()
	cfg.Phones = []Phone{{Key: key, Name: "Rider desk"}}
	// the phone wrote a scan into Firebase
	q := fmt.Sprintf(`{%q:{"s1":{"c":"250787919580744","t":%d,"d":"x"}}}`, key, time.Now().UnixMilli())
	fb.mu.Lock()
	fb.data["/babai/o/vtr/sq.json"] = q
	fb.mu.Unlock()
	processQueue()
	ans := fb.get("/babai/o/vtr/sr/" + key + "/s1.json")
	var res ScanResult
	if json.Unmarshal([]byte(ans), &res) != nil || !res.OK || res.Last4 != "0744" {
		t.Fatalf("answer %q", ans)
	}
	if fb.count("DELETE /babai/o/vtr/sq/"+key+"/s1.json") != 1 {
		t.Fatalf("queue entry not removed: %v", fb.reqs)
	}
	syncPairKeys(time.Now())
	if !strings.Contains(fb.get("/babai/o/vtr/pk.json"), key) {
		t.Fatalf("pair keys not written: %s", fb.get("/babai/o/vtr/pk.json"))
	}
	if !strings.Contains(rulesJSON, `"sq"`) || !strings.Contains(rulesJSON, `"sr"`) || strings.Contains(rulesJSON, `"pk":{".read":true`) {
		t.Fatal("rules")
	}
}

func TestBridgeAndRiderFeed(t *testing.T) {
	var root any
	_ = json.Unmarshal([]byte(`{"ready":[{"orderId":"250787919580744","token":"040","platform":"Swiggy"}],
		"preparing":[{"order":{"id":"8496851046"},"slot":7}]}`), &root)
	m := map[string]string{}
	bridgeWalk(root, m, 0)
	if m["250787919580744"] != "40" || m["8496851046"] != "7" {
		t.Fatalf("bridge slots %v", m)
	}
	if normAddr("http://192.168.1.3/state", 8787) != "192.168.1.3:8787" || normAddr("", 8787) != "" {
		t.Fatal("normAddr")
	}

	_, pp, done := setup(t)
	defer done()
	now := time.Now()
	pp.set(
		onlineRow(501, 41, "9", "250787919580744", now.Add(-12*time.Minute), "Masala Dosa", 1, 9),
		onlineRow(502, 42, "1", "8496851046", now.Add(-5*time.Minute), "Idli", 2, 1),
		row(600, 7, 3, "1", now, "Ghee Dosa", "Dosa", 1, 1),
	)
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	brMu.Lock()
	brSlots, brOKAt = m, time.Now()
	brMu.Unlock()
	w := httptest.NewRecorder()
	handleRider(w, httptest.NewRequest(http.MethodGet, "/rider.json", nil))
	var feed struct {
		OK     bool         `json:"ok"`
		Orders []riderOrder `json:"orders"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &feed); err != nil || !feed.OK || len(feed.Orders) != 2 {
		t.Fatalf("feed %s", w.Body.String())
	}
	for _, o := range feed.Orders {
		switch o.ID {
		case 501:
			if o.Ready != 1 || o.ReadyAt == 0 || o.Slot != "40" || o.Platform != "Swiggy" || o.Items[0] != "1 × Masala Dosa" {
				t.Fatalf("ready order %+v", o)
			}
		case 502:
			if o.Ready != 0 || o.Slot != "7" {
				t.Fatalf("preparing order %+v", o)
			}
		}
	}
	if strings.Contains(w.Body.String(), "Ravi") || strings.Contains(w.Body.String(), "9999999999") {
		t.Fatal("customer details in the feed")
	}
}

func TestLiveQueueWakes(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Accept") != "text/event-stream" || r.URL.Query().Get("auth") != "s3cret" {
			w.WriteHeader(400)
			return
		}
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprint(w, "event: put\ndata: {\"path\":\"/\",\"data\":null}\n\n")
		w.(http.Flusher).Flush()
		time.Sleep(50 * time.Millisecond)
		fmt.Fprint(w, "event: put\ndata: {\"path\":\"/k/s1\",\"data\":{\"c\":\"1234\",\"t\":1}}\n\n")
		w.(http.Flusher).Flush()
		time.Sleep(50 * time.Millisecond)
		fmt.Fprint(w, "event: cancel\ndata: null\n\n")
	}))
	defer srv.Close()
	dataDir = t.TempDir()
	cfg = Config{DB: srv.URL, Secret: "s3cret", Outlet: "VTR", Phones: []Phone{{Key: newPairKey()}}}
	for len(sqWake) > 0 {
		<-sqWake
	}
	done := make(chan struct{})
	go func() { listenQueue(); close(done) }()
	select {
	case <-sqWake:
	case <-time.After(2 * time.Second):
		t.Fatal("a new scan in Firebase did not wake the hub")
	}
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("cancel did not end the stream")
	}
}

func TestPin(t *testing.T) {
	dataDir = t.TempDir()
	cfg = Config{}
	h := pinned(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte("secret page")) })
	get := func(cookie string, accept string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodGet, "/status.json", nil)
		if accept != "" {
			r.Header.Set("Accept", accept)
		}
		if cookie != "" {
			r.AddCookie(&http.Cookie{Name: pinCookie, Value: cookie})
		}
		w := httptest.NewRecorder()
		h(w, r)
		return w
	}
	if w := get("", ""); w.Code != http.StatusUnauthorized {
		t.Fatalf("locked JSON: %d", w.Code)
	}
	if w := get("", "text/html"); !strings.Contains(w.Body.String(), "Enter the PIN") {
		t.Fatal("locked page should ask for the PIN")
	}
	unlock := func(pin string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodPost, "/unlock", strings.NewReader(`{"Pin":"`+pin+`"}`))
		r.Header.Set("X-Babai-Hub", "1")
		w := httptest.NewRecorder()
		handleUnlock(w, r)
		return w
	}
	if w := unlock("0000"); strings.Contains(w.Body.String(), `"ok":true`) || len(w.Result().Cookies()) > 0 {
		t.Fatal("wrong PIN unlocked")
	}
	w := unlock("1234")
	cs := w.Result().Cookies()
	if len(cs) != 1 || cs[0].Name != pinCookie || !cs[0].HttpOnly {
		t.Fatalf("cookie %v %s", cs, w.Body.String())
	}
	if w := get(cs[0].Value, ""); w.Body.String() != "secret page" {
		t.Fatal("unlocked browser refused")
	}
	if w := get("forged", ""); w.Code != http.StatusUnauthorized {
		t.Fatal("forged cookie accepted")
	}
	// 5 wrong in a row: blocked for a minute, even for the right PIN
	for i := 0; i < 5; i++ {
		unlock("9999")
	}
	if w := unlock("1234"); !strings.Contains(w.Body.String(), "Wait a minute") {
		t.Fatal("not blocked after 5 wrong PINs")
	}
	pinBlocked = time.Time{}
}

func TestPrepStore(t *testing.T) {
	dataDir = t.TempDir()
	cfg = Config{}
	kds := func(path, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodPost, path, strings.NewReader(body))
		r.Header.Set("X-Babai-KDS", "1")
		w := httptest.NewRecorder()
		switch path {
		case "/prep/seed":
			handlePrepSeed(w, r)
		case "/prep/history":
			handlePrepHistory(w, r)
		}
		return w
	}
	prof := `{"targets":["Sambar","Dosa Batter"],"menu":{"Masala Dosa":{"1":0.2}},"recipes":[[],[]]}`
	b, _ := json.Marshal(map[string]string{"profile": prof, "seed": "T|2026-09-01|0|8:1.5 9:2\nD|2026-09-01|Masala Dosa|8:4\n"})
	if w := kds("/prep/seed", string(b)); !strings.Contains(w.Body.String(), `"ok":true`) {
		t.Fatal(w.Body.String())
	}
	// a second tablet doesn't replace the recipes
	b2, _ := json.Marshal(map[string]string{"profile": strings.Replace(prof, "Sambar", "X", 1), "seed": ""})
	kds("/prep/seed", string(b2))
	if !strings.Contains(readPrepFile("profile.json"), "Sambar") {
		t.Fatal("recipes replaced by a second tablet")
	}
	// tablet A: part of 10 Oct; tablet B: the complete day -> B kept; then A again (part) -> still B
	kds("/prep/history", "T|2026-10-10|0|8:1\nK|2026-10-10|1,0,0,0|\n")
	kds("/prep/history", "T|2026-10-10|0|8:1 9:3\nD|2026-10-10|Masala Dosa|8:5\nC|2026-10-10||\nH|2026-10-15||\n")
	kds("/prep/history", "T|2026-10-10|0|8:9\nH|2026-10-15||\n") // tablets always send their holiday list
	d, ok := readPrepDay("2026-10-10")
	if !ok || !d.complete || len(d.lines) != 2 {
		t.Fatalf("kept day %+v", d)
	}
	// no header: refused
	r := httptest.NewRequest(http.MethodPost, "/prep/history", strings.NewReader("T|2026-10-11|0|8:1\n"))
	w := httptest.NewRecorder()
	handlePrepHistory(w, r)
	if w.Code != http.StatusForbidden {
		t.Fatal("history without the KDS header accepted")
	}
	// what a tablet gets back
	w = httptest.NewRecorder()
	handlePrepBase(w, httptest.NewRequest(http.MethodGet, "/prep/base", nil))
	var base struct {
		Base, Profile, Seed, History string
		Holidays                     []string
		Days                         int
	}
	if err := json.Unmarshal(w.Body.Bytes(), &base); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(base.Profile, "Sambar") || !strings.Contains(base.Seed, "2026-09-01") || base.Days != 1 ||
		!strings.Contains(base.History, "C|2026-10-10") || len(base.Holidays) != 1 || base.Holidays[0] != "2026-10-15" {
		t.Fatalf("base %+v", base)
	}
	// same base already on the tablet: recipes + seed not sent again
	w = httptest.NewRecorder()
	handlePrepBase(w, httptest.NewRequest(http.MethodGet, "/prep/base?have="+base.Base, nil))
	if strings.Contains(w.Body.String(), "Sambar") {
		t.Fatal("recipes sent again")
	}
}
