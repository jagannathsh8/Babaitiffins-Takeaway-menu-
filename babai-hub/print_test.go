package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestReceiptParsingLikeBridgePrint(t *testing.T) {
	kot := func(idLine, extra string) string {
		return "Ownly 28\n14/10/26 16:38\nKOT - 1171\n" + idLine + "\nDelivery\nItem\nMysore Bajji (4 Pcs)\nKesari Bath\nPayment Status : Online Paid\nScan to Mark food ready\n" + extra
	}
	cases := []struct{ in, brand, id string }{
		{kot("Ownly : 1791976083241811062", ""), "Ownly", "1791976083241811062"},
		{kot("Ownly : 179197608324181 1062", ""), "Ownly", "1791976083241811062"},
		{kot("Ownly : 179197608324l8l1O62", ""), "Ownly", "1791976083241811062"},
		{kot("Ownly : 179197608324181\n1062", "1791976083241811062"), "Ownly", "1791976083241811062"},
		{kot("Ownly :", "1791976083241811062"), "Ownly", "1791976083241811062"},
		{"Swiggy : 250951448167204\nKOT - 12\nDelivery", "Swiggy", "250951448167204"},
		{"Zomato : 8722626388\nKOT - 13", "Zomato", "8722626388"},
		{"Self service\nKOT - 55\nPhone : 9876543210\nTotal : 235", "Self service", ""},
	}
	for _, c := range cases {
		m := parseReceiptText(c.in)
		if m.OrderID != c.id || (c.brand != "" && m.Brand != c.brand) {
			t.Errorf("%q: got %+v, want %s %s", c.in, m, c.brand, c.id)
		}
	}
}

func TestRenderJobHeader(t *testing.T) {
	raw := []byte("RAW")
	out := renderJob(raw, 7, Customization{TokenEnabled: true, TokenLabel: "TOKEN", AutoSource: true, ShowOrderID: true}, ReceiptMeta{Brand: "Swiggy", OrderID: "250951448167204"})
	if !bytes.Contains(out, []byte("Swiggy 7\n")) || !bytes.Contains(out, []byte("Order ID: 7204\n")) || !bytes.HasSuffix(out, raw) {
		t.Fatalf("header: %q", out)
	}
	if got := renderJob(raw, 7, Customization{}, ReceiptMeta{}); !bytes.Equal(got, raw) {
		t.Fatalf("no token, no heading: bytes must be unchanged")
	}
}

func TestRuleMatching(t *testing.T) {
	f := OrderFacts{Known: true, OrderType: 1, Brands: []string{"Swiggy"}, Items: []KItem{{N: "Masala Dosa", C: "Dosa"}, {N: "Idli", C: "Idli"}}}
	r := Rule{On: true, Types: []int{1}, Brands: []string{"swiggy"}}
	if ok, _ := matchRule(r, f); !ok {
		t.Fatal("delivery + swiggy should match")
	}
	if ok, _ := matchRule(Rule{On: true, ItemMode: "none", Cats: []string{"Dosa"}}, f); ok {
		t.Fatal("has a dosa: 'none of Dosa' must not match")
	}
	ok, items := matchRule(Rule{On: true, ItemMode: "any", Cats: []string{"Idli"}, Part: "items"}, f)
	if !ok || len(items) != 1 || items[0].N != "Idli" {
		t.Fatalf("idli items: %v %v", ok, items)
	}
	if ok, _ := matchRule(Rule{On: true, ItemMode: "only", Cats: []string{"Dosa"}}, f); ok {
		t.Fatal("'only Dosa' with an Idli must not match")
	}
	if ok, _ := matchRule(r, OrderFacts{}); ok {
		t.Fatal("unknown order: rules with conditions don't match")
	}
}

func fakePrinters(t *testing.T, names ...string) string {
	d := t.TempDir()
	t.Setenv("BABAI_FAKE_PRINTERS", strings.Join(names, ","))
	t.Setenv("BABAI_FAKE_PRINT_DIR", d)
	t.Setenv("BABAI_OLD_BRIDGE", filepath.Join(t.TempDir(), "none"))
	return d
}

func printed(t *testing.T, dir, printer string) [][]byte {
	var out [][]byte
	es, _ := os.ReadDir(dir)
	for _, e := range es {
		if strings.HasPrefix(e.Name(), printer+".") && strings.HasSuffix(e.Name(), ".bin") {
			b, _ := os.ReadFile(filepath.Join(dir, e.Name()))
			out = append(out, b)
		}
	}
	return out
}

func waitJob(t *testing.T, j *Job, status string) {
	for i := 0; i < 100; i++ {
		prMu.Lock()
		st := j.Status
		prMu.Unlock()
		if st == status {
			return
		}
		time.Sleep(30 * time.Millisecond)
	}
	prMu.Lock()
	defer prMu.Unlock()
	t.Fatalf("job status %q, want %q (%+v)", j.Status, status, j)
}

func resetPrintState() {
	mkMu.Lock()
	mkFirst, mkLinked, mkKots, mkPrimed = map[int64]time.Time{}, map[int64]string{}, nil, false
	mkMu.Unlock()
	ordMu.Lock()
	orders, ordLoaded, readyByMap = map[int64]*OrderRec{}, false, map[int64]string{}
	ordMu.Unlock()
	alertMu.Lock()
	alerts = nil
	alertMu.Unlock()
}

func TestPrintFindsOrderAndAppliesRules(t *testing.T) {
	_, pp, done := setup(t)
	defer done()
	dir := fakePrinters(t, "Counter", "Packing", "Idli", "Backup")
	resetPrintState()
	loadPrint()
	prMu.Lock()
	prSettings.PrinterName = "Counter"
	prOpts.Printers = map[string]PrinterSetup{"Counter": {Fallback: "Backup"}}
	prOpts.Rules = []Rule{
		{Name: "Delivery Swiggy", On: true, Types: []int{1}, Brands: []string{"Swiggy"}, Token: true, OrderNo: true, Copies: 2, Printer: "Packing"},
		{Name: "Idli station", On: true, ItemMode: "any", Cats: []string{"Idli"}, Part: "items", Token: true, Copies: 1, Printer: "Idli"},
	}
	prMu.Unlock()

	now := time.Now()
	old := onlineRow(500, 40, "1", "250951448160001", now.Add(-5*time.Minute), "Masala Dosa", 11, 1)
	pp.set(old)
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	// a new Swiggy order arrives, and its KOT print
	idli := strings.Replace(onlineRow(501, 41, "1", "250951448167204", now, "Idli", 12, 1), `"category":"Dosa"`, `"category":"Idli"`, 1)
	pp.set(old, onlineRow(501, 41, "1", "250951448167204", now, "Masala Dosa", 11, 1), idli)
	j, err := capture([]byte("KOT picture bytes"), "192.168.0.109", time.Now())
	if err != nil {
		t.Fatal(err)
	}
	waitJob(t, j, "sent")
	prMu.Lock()
	jj := *j
	prMu.Unlock()
	if jj.Kot != 501 || jj.IdentBy != "petpooja" || jj.Metadata.Brand != "Swiggy" || jj.Metadata.OrderID != "250951448167204" {
		t.Fatalf("order not found right: %+v %+v", jj, jj.Metadata)
	}
	if jj.IdentMs > 800 {
		t.Fatalf("finding the order took %d ms", jj.IdentMs)
	}
	main := printed(t, dir, "Counter")
	if len(main) != 1 || !bytes.Contains(main[0], []byte("Swiggy 1\n")) || !bytes.Contains(main[0], []byte("Order ID: 7204")) {
		t.Fatalf("main printer: %q", main)
	}
	pack := printed(t, dir, "Packing")
	if len(pack) != 1 || bytes.Count(pack[0], []byte("KOT picture bytes")) != 2 {
		t.Fatalf("packing: 2 copies of the whole KOT expected: %q", pack)
	}
	id := printed(t, dir, "Idli")
	if len(id) != 1 || !bytes.Contains(id[0], []byte("1x   Idli")) || bytes.Contains(id[0], []byte("Masala")) {
		t.Fatalf("idli station: only the idli item expected: %q", id)
	}
	// order record for the Dispatch screen
	recs := ordersSnapshot(time.Now().Add(-time.Hour))
	found := false
	for _, o := range recs {
		if o.Kot == 501 && o.Token == 1 && o.Printed > 0 {
			found = true
		}
	}
	if !found {
		t.Fatalf("order record missing: %+v", recs)
	}

	// the Counter printer goes offline: the next print goes to its fallback, with an alert
	os.WriteFile(filepath.Join(dir, "Counter.problem"), []byte("offline"), 0o644)
	pp.set(old, onlineRow(502, 42, "1", "8722626388", now, "Pongal", 13, 1))
	j2, _ := capture([]byte("second KOT"), "192.168.0.109", time.Now())
	waitJob(t, j2, "sent")
	if b := printed(t, dir, "Backup"); len(b) != 1 || !bytes.Contains(b[0], []byte("second KOT")) {
		t.Fatalf("fallback printer: %q", b)
	}
	if a := alertsSnapshot(); len(a) == 0 || !strings.Contains(a[0].Msg, "Backup") {
		t.Fatalf("alert expected: %+v", a)
	}
}

func TestPrintWithoutPetpoojaUsesFallbackLabel(t *testing.T) {
	_, _, done := setup(t)
	defer done()
	cfg.Server = ""
	dir := fakePrinters(t, "Counter")
	resetPrintState()
	loadPrint()
	prMu.Lock()
	prSettings.PrinterName = "Counter"
	prOpts.IdentifyWait = 300
	prMu.Unlock()
	start := time.Now()
	j, _ := capture([]byte("Zomato : 8722626388\nKOT - 13\n"), "10.0.0.5", time.Now())
	waitJob(t, j, "sent")
	if el := time.Since(start); el > 250*time.Millisecond {
		t.Fatalf("a text KOT without Petpooja should print at once, took %v", el)
	}
	out := printed(t, dir, "Counter")
	if len(out) != 1 || !bytes.Contains(out[0], []byte("Zomato 1\n")) {
		t.Fatalf("expected Bridge Print style slip: %q", out)
	}
}

func TestTokenCycle(t *testing.T) {
	tk, n := takeToken(counterState{Next: 60}, 60)
	if tk != 60 || n.Next != 1 {
		t.Fatal(tk, n)
	}
	tk, n = takeToken(counterState{Next: 99}, 60)
	if tk != 1 || n.Next != 2 {
		t.Fatal(tk, n)
	}
}

func TestBridgePrintAPIUnchanged(t *testing.T) {
	_, _, done := setup(t)
	defer done()
	cfg.Server = ""
	fakePrinters(t, "Counter")
	resetPrintState()
	loadPrint()
	prMu.Lock()
	prSettings.PrinterName = "Counter"
	prMu.Unlock()
	j, _ := capture([]byte("Swiggy : 250951448167204\nKOT - 12\n"), "10.0.0.5", time.Now())
	waitJob(t, j, "sent")

	req := func(method, path, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.Host = "127.0.0.1:8787"
		if method == http.MethodPost {
			r.Header.Set("Content-Type", "application/json")
			r.Header.Set("X-Bridge-Token", bridgeCSRF)
		}
		w := httptest.NewRecorder()
		handleBridgeWeb(w, r)
		return w
	}
	w := req(http.MethodGet, "/api/state", "")
	var st struct {
		Settings  PrintSettings
		Jobs      []Job
		NextToken int
		Csrf      string
		Counts    map[string]int
	}
	if err := json.Unmarshal(w.Body.Bytes(), &st); err != nil || st.Csrf != bridgeCSRF || st.NextToken != 2 || len(st.Jobs) != 1 || st.Jobs[0].Metadata.OrderID != "250951448167204" {
		t.Fatalf("state: %v %s", err, w.Body.String())
	}
	if w := req(http.MethodGet, "/", ""); !strings.Contains(w.Body.String(), "Bridge print") {
		t.Fatal("dashboard page")
	}
	// settings: same checks as before
	if w := req(http.MethodPost, "/api/settings", `{"outputMode":"windows","printerName":"Nope","printerIp":"","printerPort":9100,"heading":"","tokenLabel":"TOKEN","autoPrint":true,"tokenEnabled":true,"tokenLimit":60}`); w.Code != 400 {
		t.Fatalf("unknown printer accepted: %d %s", w.Code, w.Body.String())
	}
	if w := req(http.MethodPost, "/api/settings", `{"outputMode":"windows","printerName":"Counter","printerIp":"","printerPort":9100,"heading":"COUNTER","tokenLabel":"TOKEN","autoPrint":true,"tokenEnabled":true,"tokenLimit":99}`); w.Code != 200 {
		t.Fatalf("settings: %d %s", w.Code, w.Body.String())
	}
	if prSettings.TokenLimit != 99 || prSettings.Heading != "COUNTER" {
		t.Fatalf("settings not saved: %+v", prSettings)
	}
	if w := req(http.MethodPost, "/api/token/reset", `{"next":7}`); w.Code != 200 || prCounter.Next != 7 {
		t.Fatalf("reset: %s", w.Body.String())
	}
	if w := req(http.MethodPost, "/api/jobs/"+j.ID+"/reprint", `{}`); w.Code != 200 {
		t.Fatalf("reprint: %s", w.Body.String())
	}
	// LAN: only the TV
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	r.Host = "10.9.9.9:8787"
	w = httptest.NewRecorder()
	handleBridgeWeb(w, r)
	if w.Code != 403 {
		t.Fatalf("other host: %d", w.Code)
	}
	// Android token lookup
	r = httptest.NewRequest(http.MethodGet, "/api/mobile/token?orderId=448167204", nil)
	r.Header.Set("X-Bridge-Key", bridgeMobileKey)
	w = httptest.NewRecorder()
	handleMobileToken(w, r)
	if !strings.Contains(w.Body.String(), `"token":"1"`) {
		t.Fatalf("mobile lookup: %s", w.Body.String())
	}
}

func TestDispatchShowsPrintsAndPetpoojaOrders(t *testing.T) {
	_, pp, done := setup(t)
	defer done()
	fakePrinters(t, "Counter")
	resetPrintState()
	loadPrint()
	prMu.Lock()
	prSettings.PrinterName = "Counter"
	prOpts.IdentifyWait = 300
	prMu.Unlock()
	now := time.Now()
	// in Petpooja's list, its print missed
	pp.set(onlineRow(700, 70, "1", "250951448160700", now.Add(-2*time.Minute), "Masala Dosa", 11, 1))
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	// printed, but Petpooja's kitchen list never has it
	j, _ := capture([]byte("Zomato : 8722626388\nKOT - 13\n"), "10.0.0.5", time.Now())
	waitJob(t, j, "sent")
	// Petpooja readable: only Petpooja's orders (the missed print shows without a token)
	for _, o := range dispatchOrders(now.Add(-time.Hour), time.Now()) {
		if o.PrintOnly {
			t.Fatalf("print-only card while Petpooja is readable: %+v", o)
		}
	}
	ppMu.Lock()
	ppOKAt = time.Now().Add(-time.Minute) // Petpooja can't be read
	ppMu.Unlock()
	list := dispatchOrders(now.Add(-time.Hour), time.Now())
	var fromPP, fromPrint *OrderRec
	for i := range list {
		if list[i].Kot == 700 {
			fromPP = &list[i]
		}
		if list[i].JobID == j.ID && list[i].PrintOnly {
			fromPrint = &list[i]
		}
	}
	if fromPP == nil || fromPP.Status != "preparing" || fromPP.Token != 0 {
		t.Fatalf("Petpooja order without a print: %+v", fromPP)
	}
	if fromPrint == nil || fromPrint.Token != 1 || fromPrint.Brand != "Zomato" || fromPrint.OrderID != "8722626388" || fromPrint.Status != "preparing" {
		t.Fatalf("print not in Petpooja: %+v", fromPrint)
	}
	// Food ready on the print-only card
	b, _ := json.Marshal(map[string]string{"job": j.ID})
	r := httptest.NewRequest(http.MethodPost, "/console/api/ready", bytes.NewReader(b))
	r.Header.Set("X-Babai-Hub", "1")
	w := httptest.NewRecorder()
	handleConsoleReady(w, r)
	for _, o := range dispatchOrders(now.Add(-time.Hour), time.Now()) {
		if o.JobID == j.ID && (o.Status != "ready" || o.ReadyBy != "Console") {
			t.Fatalf("not ready: %+v %s", o, w.Body.String())
		}
	}
}

func TestPrintNeverTakesAnotherNewKOT(t *testing.T) {
	_, pp, done := setup(t)
	defer done()
	dir := fakePrinters(t, "Counter")
	resetPrintState()
	loadPrint()
	prMu.Lock()
	prSettings.PrinterName = "Counter"
	prOpts.IdentifyWait = 400
	prMu.Unlock()
	now := time.Now()
	pp.set(onlineRow(900, 90, "1", "250000000000900", now.Add(-10*time.Minute), "Pongal", 13, 1))
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	dine := strings.Replace(row(901, 91, 3, "1", now, "Masala Dosa", "Dosa", 1, 1), `"p_o_id":""`, `"p_o_id":""`, 1)
	swiggy := onlineRow(902, 1217, "1", "251185952139584", now, "Idli (2 Pcs)", 12, 1)
	// a dine-in KOT (printed in the kitchen, not here) appears first; the Swiggy KOT a moment later
	pp.set(onlineRow(900, 90, "1", "250000000000900", now.Add(-10*time.Minute), "Pongal", 13, 1), dine)
	j, _ := capture([]byte("17/10/26 11:23\nKOT - 1217\nSwiggy : 251185952139584\nDelivery\n"), "10.0.0.5", time.Now())
	time.Sleep(150 * time.Millisecond)
	pp.set(onlineRow(900, 90, "1", "250000000000900", now.Add(-10*time.Minute), "Pongal", 13, 1), dine, swiggy)
	waitJob(t, j, "sent")
	prMu.Lock()
	kot, brand := j.Kot, j.Metadata.Brand
	prMu.Unlock()
	if kot != 902 || brand != "Swiggy" {
		t.Fatalf("matched KOT %d (%s), want the Swiggy KOT 902", kot, brand)
	}
	if out := printed(t, dir, "Counter"); len(out) != 1 || !bytes.Contains(out[0], []byte("Swiggy 1\n")) {
		t.Fatalf("slip: %q", out)
	}
	// a pick-up slip while only a dine-in KOT is new: not matched, printed as the slip says
	pp.set(dine, swiggy, strings.Replace(row(903, 92, 3, "1", time.Now(), "Vada", "Vada", 1, 1), "", "", 0))
	j2, _ := capture([]byte("Pick up\nKOT - 92\n"), "10.0.0.5", time.Now())
	waitJob(t, j2, "sent")
	prMu.Lock()
	defer prMu.Unlock()
	if j2.Kot != 0 || j2.Metadata.Brand != "Pick up" {
		t.Fatalf("pick-up slip took KOT %d (%+v)", j2.Kot, j2.Metadata)
	}
}

func TestOwnlyMisreadStillFindsItsKOT(t *testing.T) {
	_, pp, done := setup(t)
	defer done()
	fakePrinters(t, "Counter")
	resetPrintState()
	loadPrint()
	prMu.Lock()
	prSettings.PrinterName = "Counter"
	prOpts.IdentifyWait = 400
	prMu.Unlock()
	pp.set()
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	ownly := strings.Replace(onlineRow(950, 95, "1", "1792217946190158774", time.Now(), "Idli", 12, 1), `"o_u_id":"6736"`, `"o_u_id":"","online_order_user_name":"Ownly"`, 1)
	pp.set(ownly)
	// OCR read two digits wrong
	j, _ := capture([]byte("Ownly : 1792217946180158874\nKOT - 95\nDelivery\n"), "10.0.0.5", time.Now())
	waitJob(t, j, "sent")
	prMu.Lock()
	kot := j.Kot
	prMu.Unlock()
	if kot != 950 {
		t.Fatalf("Ownly slip not linked to its KOT (got %d)", kot)
	}
	if s := slotForKot(950, "1792217946190158774"); s != "1" {
		t.Fatalf("rider slot %q", s)
	}
}

// capPetpooja answers like the billing PC: at most 300 rows, oldest first, by order type.
type capPetpooja struct{ rows []map[string]any }

func (p *capPetpooja) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	var body map[string]any
	_ = json.NewDecoder(r.Body).Decode(&body)
	switch r.URL.Path {
	case "/petpooja_server/kiosk_get_default_data":
		w.Write([]byte(`{"data":{"sync_code":"S"}}`))
	case "/petpooja_server/get_kot_data_for_kitchen_view":
		types, _ := body["order_type"].(string)
		var out []map[string]any
		for _, row := range p.rows {
			if strings.Contains(types, fmt.Sprint(row["order_type"])) && len(out) < 300 {
				out = append(out, row)
			}
		}
		b, _ := json.Marshal(map[string]any{"data": out})
		w.Write(b)
	default:
		w.Write([]byte(`{}`))
	}
}

func TestOnlineOrdersNotLostBehindOldDineInKOTs(t *testing.T) {
	_, _, done := setup(t)
	defer done()
	cp := &capPetpooja{}
	old := time.Now().Add(-48 * time.Hour).Format("2006-01-02 15:04:05")
	for i := 0; i < 400; i++ { // 400 old dine-in KOTs left open
		cp.rows = append(cp.rows, map[string]any{"id": 1000 + i, "order_type": 3, "kot_status": "1", "created": old, "name": "Idli", "status": 1})
	}
	cp.rows = append(cp.rows, map[string]any{"id": 5000, "order_type": 1, "kot_status": "1", "created": time.Now().Format("2006-01-02 15:04:05"),
		"p_o_id": "251185952139584", "o_u_id": "6736", "name": "Masala Dosa", "status": 1})
	srv := httptest.NewServer(cp)
	defer srv.Close()
	cfg.Server = strings.TrimPrefix(srv.URL, "http://")
	resetPetpooja()
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	found := false
	for _, o := range riderOrders(ppKots) {
		if o.ID == 5000 {
			found = true
		}
	}
	if !found {
		t.Fatal("the Swiggy order fell off the capped kitchen list")
	}
}
