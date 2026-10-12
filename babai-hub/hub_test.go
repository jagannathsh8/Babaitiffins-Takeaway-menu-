package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// ---- fakes ----------------------------------------------------------------------------------

type fakeFirebase struct {
	mu   sync.Mutex
	reqs []string
	data map[string]string
	code int
}

func (f *fakeFirebase) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	b, _ := io.ReadAll(r.Body)
	f.mu.Lock()
	defer f.mu.Unlock()
	f.reqs = append(f.reqs, r.Method+" "+r.URL.Path+" "+r.URL.RawQuery)
	if f.code != 0 {
		w.WriteHeader(f.code)
		return
	}
	if r.Method != http.MethodGet {
		f.data[r.URL.Path] = string(b)
	}
	if r.URL.Query().Get("print") == "silent" {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	io.WriteString(w, "{}")
}

func (f *fakeFirebase) count(prefix string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, r := range f.reqs {
		if strings.HasPrefix(r, prefix) {
			n++
		}
	}
	return n
}

func (f *fakeFirebase) get(path string) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.data[path]
}

// fakePetpooja answers like the billing station: default data, then KOT rows.
type fakePetpooja struct {
	mu       sync.Mutex
	rows     string
	bodies   map[string]map[string]any
	headers  http.Header
	syncAsks int
}

func (p *fakePetpooja) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	var body map[string]any
	_ = json.NewDecoder(r.Body).Decode(&body)
	p.mu.Lock()
	defer p.mu.Unlock()
	p.bodies[r.URL.Path] = body
	p.headers = r.Header.Clone()
	switch r.URL.Path {
	case "/petpooja_server/kiosk_get_default_data":
		p.syncAsks++
		io.WriteString(w, `{"error":"","message":"","data":{"sync_code":"SYNC123","restaurant_id":"77"}}`)
	case "/petpooja_server/get_kot_data_for_kitchen_view":
		if body["sync_code"] != "SYNC123" {
			io.WriteString(w, `{"error":"1","message":"Invalid sync code","data":[]}`)
			return
		}
		io.WriteString(w, `{"error":"","message":"","data":[`+p.rows+`]}`)
	case "/petpooja_server/touch_items":
		io.WriteString(w, `{"items":[{"id":"11","name":"Masala Dosa","category":"Dosa","in_stock":1},{"id":"12","name":"Pesarattu","category":"Dosa","in_stock":0}]}`)
	default:
		http.NotFound(w, r)
	}
}

func (p *fakePetpooja) set(rows ...string) {
	p.mu.Lock()
	p.rows = strings.Join(rows, ",")
	p.mu.Unlock()
}

func row(id, token int64, ot int, kotSt string, created time.Time, name, cat string, qty float64, st int) string {
	return fmt.Sprintf(`{"id":%d,"kot_unique_id":%d,"table_no":"","order_type":%d,"kot_status":"%s","created":"%s","p_o_id":"","o_u_id":"","name":%q,"category":%q,"quantity":%g,"status":%d,"customer_name":"Ravi","customer_phone":"9999999999"}`,
		id, token, ot, kotSt, created.Format("2006-01-02 15:04:05"), name, cat, qty, st)
}

func setup(t *testing.T) (*fakeFirebase, *fakePetpooja, func()) {
	fb := &fakeFirebase{data: map[string]string{}}
	fsrv := httptest.NewServer(fb)
	pp := &fakePetpooja{bodies: map[string]map[string]any{}}
	psrv := httptest.NewServer(pp)
	dataDir = t.TempDir()
	_ = os.MkdirAll(filepath.Join(dataDir, "days"), 0o755)
	cfg = Config{Server: strings.TrimPrefix(psrv.URL, "http://"), DB: fsrv.URL, Secret: "s3cret", Page: defaultPage, AutoClearMin: 10, Outlet: "VTR"}
	mu.Lock()
	tokensRaw, metaRaw, liveRaw, lastBoard = nil, nil, nil, time.Time{}
	open = map[int64]*Rec{}
	pending = map[string]map[string]*Rec{}
	board, stats, statsLoad = newTokenBoard(), newDosaStats(), true
	mu.Unlock()
	resetPetpooja()
	for _, s := range []*stream{stTokens, stMeta, stLive, stStock} {
		s.sent, s.at = nil, time.Time{}
	}
	resetStreams()
	archAt = time.Time{}
	lastOKAt, lastErrAt, lastErr = time.Time{}, time.Time{}, ""
	prunedDay = time.Now().Format("2006-01-02")
	return fb, pp, func() { fsrv.Close(); psrv.Close() }
}

func phoneToken(t *testing.T, fb *fakeFirebase, key string) (PhoneToken, bool) {
	var m map[string]PhoneToken
	raw := fb.get("/babai/o/vtr/t.json")
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		t.Fatalf("tokens JSON: %v %s", err, raw)
	}
	tk, ok := m[key]
	return tk, ok
}

// ---- tests ----------------------------------------------------------------------------------

func TestPetpoojaToPhone(t *testing.T) {
	fb, pp, stop := setup(t)
	defer stop()
	now := time.Now()
	t0 := now.Add(-12 * time.Minute)
	// token 41: dine-in, 2 Ghee Karam Dosa + 2 Idli (idli ready); token 42 dine-in dosa; token 50 delivery
	pp.set(
		row(1001, 41, 3, "1", t0, "Ghee Karam Dosa", "Dosa", 2, 1),
		row(1001, 41, 3, "1", t0, "Ghee Idli", "Idli", 2, 9),
		row(1002, 42, 3, "1", t0.Add(time.Minute), "Masala Dosa", "DOSA Items", 1, 1),
		row(1003, 50, 1, "1", t0, "Masala Dosa", "Dosa", 1, 1),
		row(1004, 60, 3, "1", t0, "Ghee Idli", "Idli", 2, 1),
	)
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	// the request looks like the KDS app's
	pp.mu.Lock()
	kb := pp.bodies["/petpooja_server/get_kot_data_for_kitchen_view"]
	db := pp.bodies["/petpooja_server/kiosk_get_default_data"]
	hdr := pp.headers.Get("x-app-client")
	pp.mu.Unlock()
	if kb["clientIdentifier"] != "android_kds" || kb["order_type"] != "1,2,3" || kb["category_ids"] != "-1" || kb["intra_server_version"] != float64(7) {
		t.Fatalf("KOT request: %v", kb)
	}
	if obj, _ := db["kds_details_obj"].(map[string]any); obj["ip"] != "127.0.0.1" {
		t.Fatalf("default data request: %v", db)
	}
	if hdr != "android-kds" {
		t.Fatalf("header: %q", hdr)
	}
	cloudStep(now)
	tk, ok := phoneToken(t, fb, "41")
	if !ok || tk.S != "p" || tk.Pos != 0 || tk.D != 2 {
		t.Fatalf("token 41: %+v %v", tk, ok)
	}
	if len(tk.I) != 2 || tk.I[0].K != "d" || tk.I[0].Q != 2 || tk.I[1].K != "o" || tk.I[1].S != 1 {
		t.Fatalf("token 41 items: %+v", tk.I)
	}
	if t42, _ := phoneToken(t, fb, "42"); t42.Pos != 1 {
		t.Fatalf("token 42 place: %+v", t42)
	}
	if _, ok := phoneToken(t, fb, "50"); ok {
		t.Fatal("delivery order shown as a dine-in token")
	}
	if t60, ok := phoneToken(t, fb, "60"); !ok || t60.S != "n" || len(t60.I) != 1 || t60.I[0].N != "Ghee Idli" {
		t.Fatalf("no-dosa token: %+v %v", t60, ok)
	}
	if strings.Contains(fb.get("/babai/o/vtr/live.json"), "9999999999") || strings.Contains(fb.get("/babai/o/vtr/live.json"), "Ravi") {
		t.Fatal("customer details sent to the cloud")
	}
	if !strings.HasPrefix(fb.get("/babai/o/vtr/meta.json"), `{"u":{".sv":"timestamp"},`) {
		t.Fatalf("meta: %s", fb.get("/babai/o/vtr/meta.json"))
	}
	if fb.get("/babai/outlets/vtr.json") != `{"n":"VTR"}` {
		t.Fatalf("outlet not listed: %q", fb.get("/babai/outlets/vtr.json"))
	}
	if !strings.Contains(fb.get("/babai/o/vtr/stock.json"), `"12":{"c":"Dosa","n":"Pesarattu","on":0}`) {
		t.Fatalf("stock: %s", fb.get("/babai/o/vtr/stock.json"))
	}

	// 42's dosa comes out first: 41 is "overtaken"
	pp.set(
		row(1001, 41, 3, "1", t0, "Ghee Karam Dosa", "Dosa", 2, 1),
		row(1001, 41, 3, "1", t0, "Ghee Idli", "Idli", 2, 9),
		row(1002, 42, 3, "1", t0.Add(time.Minute), "Masala Dosa", "DOSA Items", 1, 9),
	)
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	cloudStep(now.Add(6 * time.Second))
	if t42, _ := phoneToken(t, fb, "42"); t42.S != "r" || t42.A == 0 {
		t.Fatalf("42 not ready: %+v", t42)
	}
	if t41, _ := phoneToken(t, fb, "41"); t41.O != 1 {
		t.Fatalf("41 not overtaken: %+v", t41)
	}
	// unchanged: not re-sent within the gap
	n := fb.count("PUT /babai/o/vtr/t.json")
	cloudStep(now.Add(8 * time.Second))
	if fb.count("PUT /babai/o/vtr/t.json") != n {
		t.Fatal("unchanged tokens re-sent")
	}

	// both KOTs dispatched / gone: 41 drops after the board blip time, archive after a minute
	pp.set()
	_ = pollOnce(cfg.Server)
	mu.Lock()
	board.update(nil, now.Add(20*time.Second).UnixMilli())
	track(nil, now.Add(80*time.Second))
	q := len(pending)
	mu.Unlock()
	if q != 1 {
		t.Fatalf("finished KOTs not queued (%d days)", q)
	}
	archAt = time.Time{}
	mu.Lock()
	lastBoard = now.Add(80 * time.Second)
	mu.Unlock()
	cloudStep(now.Add(81 * time.Second))
	var arch string
	for _, r := range fb.reqs {
		if strings.HasPrefix(r, "PATCH /babai/o/vtr/days/") {
			arch = r
		}
	}
	if arch == "" {
		t.Fatalf("archive not uploaded: %v", fb.reqs)
	}
	files, _ := filepath.Glob(filepath.Join(dataDir, "days", "*.jsonl"))
	if len(files) != 1 {
		t.Fatal("local archive missing")
	}
	b, _ := os.ReadFile(files[0])
	if strings.Contains(string(b), "Ravi") || !strings.Contains(string(b), `"rdy":`) {
		t.Fatalf("archive content: %s", b)
	}
}

func TestSyncCodeRenewed(t *testing.T) {
	_, pp, stop := setup(t)
	defer stop()
	pp.set(row(1, 7, 3, "1", time.Now(), "Masala Dosa", "Dosa", 1, 1))
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	ppMu.Lock()
	syncCode = "OLD"
	ppMu.Unlock()
	if err := pollOnce(cfg.Server); err == nil || !strings.Contains(err.Error(), "Invalid sync code") {
		t.Fatalf("expected refusal, got %v", err)
	}
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatalf("not recovered: %v", err)
	}
	if pp.syncAsks != 2 {
		t.Fatalf("sync code asked %d times", pp.syncAsks)
	}
}

func TestAutoClearAndStale(t *testing.T) {
	b := newTokenBoard()
	now := time.Now().UnixMilli()
	b.update([]tbKot{{id: 1, label: "5", created: now - 60_000, ready: true, dosas: 1}}, now)
	if len(b.ready) != 1 {
		t.Fatal("ready token missing")
	}
	b.update([]tbKot{{id: 1, label: "5", created: now - 60_000, ready: true, dosas: 1}}, now+11*60_000)
	if len(b.ready) != 0 {
		t.Fatal("not auto-cleared after 10 min")
	}
	b2 := newTokenBoard()
	b2.update([]tbKot{{id: 2, label: "6", created: now - 40*60_000, ready: true}}, now)
	if len(b2.ready) != 0 {
		t.Fatal("old leftover shown")
	}
}

func TestStatsAndLabels(t *testing.T) {
	if friendlyRange(9.4) != "8–11 min" || friendlyRange(3) != "Under 5 min" {
		t.Fatalf("range: %s", friendlyRange(9.4))
	}
	if tokenKey("042") != "42" || tokenKey("T4") != "T4" || tokenKey("#123") != "X123" {
		t.Fatal("token keys")
	}
	s := newDosaStats()
	y, mo, d := time.Now().Date()
	now := time.Date(y, mo, d, 12, 0, 0, 0, time.Local) // midday: the test order is from today
	c := now.Add(-9 * time.Minute).UnixMilli()
	s.update([]dsEntry{{id: 1, created: c, names: []string{"Masala Dosa"}, qty: []float64{2}}}, now, false)
	s.update([]dsEntry{{id: 1, created: c, ready: true, names: []string{"Masala Dosa"}, qty: []float64{2}}}, now.Add(time.Minute), false)
	r := s.compute(now.Add(time.Minute))
	if r.avgPrep < 9.9 || r.avgPrep > 10.1 || r.etaLabel == "" || len(r.facts) == 0 {
		t.Fatalf("stats: %+v", r)
	}
	if !strings.Contains(strings.Join(r.facts, "|"), "Masala Dosa is today's favourite") {
		t.Fatalf("facts: %v", r.facts)
	}
	// survives a restart
	p := filepath.Join(t.TempDir(), "stats.json")
	s.save(p)
	s2 := newDosaStats()
	s2.load(p, now.Add(time.Minute))
	if s2.Completed != 1 || s2.Kinds["Masala Dosa"] != 2 {
		t.Fatalf("reload: %+v", s2.DayStats)
	}
}

func TestCloudErrorHidesQR(t *testing.T) {
	fb, pp, stop := setup(t)
	defer stop()
	fb.code = 401
	pp.set(row(1, 7, 3, "1", time.Now(), "Masala Dosa", "Dosa", 1, 1))
	_ = pollOnce(cfg.Server)
	cloudStep(time.Now())
	if cloudOK() {
		t.Fatal("cloud OK after 401")
	}
	if !strings.Contains(cloudStatus()["cloudErr"].(string), "secret") {
		t.Fatalf("error text: %v", cloudStatus()["cloudErr"])
	}
}

func TestSettingsNeedOwnPage(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/settings", strings.NewReader(`{"DB":"x.firebasedatabase.app"}`))
	req.RemoteAddr = "127.0.0.1:1"
	w := httptest.NewRecorder()
	localOnly(handleSettings)(w, req)
	if w.Code != http.StatusForbidden {
		t.Fatalf("settings changed without the page header: %d", w.Code)
	}
	req = httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "192.168.1.9:1"
	w = httptest.NewRecorder()
	localOnly(handleHome)(w, req)
	if w.Code != http.StatusForbidden {
		t.Fatalf("hub page open to the network: %d", w.Code)
	}
}

func TestNormalise(t *testing.T) {
	got, err := normaliseDB(" babai-x-default-rtdb.asia-southeast1.firebasedatabase.app/ ")
	if err != nil || got != "https://babai-x-default-rtdb.asia-southeast1.firebasedatabase.app" {
		t.Fatalf("%q %v", got, err)
	}
	if _, err := normaliseDB("evil.example.com"); err == nil {
		t.Fatal("non-Firebase host accepted")
	}
	if outletCode(" Sarjapur ") != "sarjapur" || outletCode("JP Nagar") != "jpnagar" || outletCode("--") != "" {
		t.Fatal("outlet codes")
	}
	if serverBase("192.168.1.5") != "http://192.168.1.5:3000/" || serverBase("http://192.168.1.5:8080/") != "http://192.168.1.5:8080/" {
		t.Fatalf("server base: %s", serverBase("192.168.1.5"))
	}
}

func TestCustomerPage(t *testing.T) {
	cfg = Config{DB: "https://babai-x-default-rtdb.asia-southeast1.firebasedatabase.app"}
	w := httptest.NewRecorder()
	handleCustomerPage(w, httptest.NewRequest(http.MethodGet, "/customer-page", nil))
	b := w.Body.String()
	if !strings.Contains(b, "var BUILT_IN='babai-x-default-rtdb.asia-southeast1.firebasedatabase.app'") ||
		!strings.Contains(w.Header().Get("Content-Disposition"), "index.html") {
		t.Fatalf("page: %d %s", w.Code, w.Header())
	}
}

func TestLocation(t *testing.T) {
	for in, want := range map[string][2]float64{
		"12.9356, 77.7425": {12.9356, 77.7425},
		"https://www.google.com/maps/place/Babai+Tiffins/@12.9358123,77.7421456,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d12.9356789!4d77.7425123": {12.9356789, 77.7425123},
		"https://maps.google.com/?q=12.93567,77.74251": {12.93567, 77.74251},
	} {
		lat, lng, err := resolveLocation(in)
		if err != nil || lat != want[0] || lng != want[1] {
			t.Fatalf("%s -> %v %v %v", in, lat, lng, err)
		}
	}
	if _, _, err := resolveLocation("near the bus stop"); err == nil {
		t.Fatal("nonsense accepted")
	}
}

func dishPhoto(t *testing.T, shade uint8) string {
	img := image.NewGray(image.Rect(0, 0, 64, 40))
	for i := range img.Pix {
		img.Pix[i] = shade + uint8(i%7)
	}
	var b bytes.Buffer
	if err := jpeg.Encode(&b, img, nil); err != nil {
		t.Fatal(err)
	}
	return "data:image/jpeg;base64," + base64.StdEncoding.EncodeToString(b.Bytes())
}

func promoPost(t *testing.T, body map[string]any, own bool) map[string]any {
	b, _ := json.Marshal(body)
	req := httptest.NewRequest(http.MethodPost, "/promo", bytes.NewReader(b))
	if own {
		req.Header.Set("X-Babai-Hub", "1")
	}
	w := httptest.NewRecorder()
	handlePromo(w, req)
	if !own {
		if w.Code != http.StatusForbidden {
			t.Fatalf("promo change without the hub page header: %d", w.Code)
		}
		return nil
	}
	var r map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &r)
	return r
}

func TestDishCarousel(t *testing.T) {
	fb, _, done := setup(t)
	defer done()
	promoMu.Lock()
	promo, promoDirty, promoTryAt, promoErr = promoState{}, false, time.Time{}, ""
	promoMu.Unlock()
	rulesOK, rulesTryAt = false, time.Time{}

	promoPost(t, map[string]any{"Op": "add", "T": "x", "Image": dishPhoto(t, 10)}, false)
	if r := promoPost(t, map[string]any{"Op": "add", "T": "Masala Dosa", "Image": "data:text/html;base64,PGI+"}, true); r["ok"] == true {
		t.Fatal("a non-photo was accepted")
	}
	if r := promoPost(t, map[string]any{"Op": "add", "T": "", "Image": dishPhoto(t, 10)}, true); r["ok"] == true {
		t.Fatal("a dish without a name was accepted")
	}
	r := promoPost(t, map[string]any{"Op": "add", "T": "  Ghee   Karam Dosa ", "D": "Hot tawa", "Image": dishPhoto(t, 20)}, true)
	if r["ok"] != true {
		t.Fatalf("add: %v", r)
	}
	p := promoSnapshot()
	if !p.Custom || len(p.Dishes) != 1 || p.Dishes[0].T != "Ghee Karam Dosa" {
		t.Fatalf("saved: %+v", p)
	}
	k := p.Dishes[0].K
	if _, err := os.Stat(promoImgPath(k)); err != nil {
		t.Fatal("photo not kept on the PC")
	}

	cloudStep(time.Now())
	if !strings.Contains(fb.get("/.settings/rules.json"), `"pimg"`) {
		t.Fatalf("rules not updated: %q", fb.get("/.settings/rules.json"))
	}
	if !strings.HasPrefix(fb.get("/babai/o/vtr/pimg/"+k+".json"), `"data:image/jpeg;base64,`) {
		t.Fatal("photo not sent")
	}
	var online struct {
		V int64
		S []Dish
	}
	_ = json.Unmarshal([]byte(fb.get("/babai/o/vtr/promo.json")), &online)
	if len(online.S) != 1 || online.S[0].K != k || online.V == 0 {
		t.Fatalf("list not sent: %q", fb.get("/babai/o/vtr/promo.json"))
	}
	puts := fb.count("PUT /babai/o/vtr/pimg/")
	cloudStep(time.Now().Add(2 * time.Minute)) // nothing changed: nothing sent again
	if fb.count("PUT /babai/o/vtr/pimg/") != puts || fb.count("PUT /babai/o/vtr/promo") != 1 {
		t.Fatal("sent again without a change")
	}

	// up to 10
	for i := 1; i < promoMax; i++ {
		if r := promoPost(t, map[string]any{"Op": "add", "T": "Dish", "Image": dishPhoto(t, uint8(30+i))}, true); r["ok"] != true {
			t.Fatalf("add %d: %v", i, r)
		}
	}
	if r := promoPost(t, map[string]any{"Op": "add", "T": "Eleven", "Image": dishPhoto(t, 200)}, true); r["ok"] == true {
		t.Fatal("an 11th dish was accepted")
	}
	promoPost(t, map[string]any{"Op": "down", "I": 0}, true)
	if promoSnapshot().Dishes[1].K != k {
		t.Fatal("move did not work")
	}
	promoPost(t, map[string]any{"Op": "delete", "I": 1}, true)
	if n := len(promoSnapshot().Dishes); n != promoMax-1 {
		t.Fatalf("delete: %d left", n)
	}
	if _, err := os.Stat(promoImgPath(k)); err == nil {
		t.Fatal("deleted dish's photo kept")
	}

	// 0 dishes: the list is sent empty (customers' page hides the carousel)
	promoPost(t, map[string]any{"Op": "none"}, true)
	cloudStep(time.Now().Add(4 * time.Minute))
	_ = json.Unmarshal([]byte(fb.get("/babai/o/vtr/promo.json")), &online)
	if len(online.S) != 0 || !strings.Contains(fb.get("/babai/o/vtr/promo.json"), `"n":0`) {
		t.Fatalf("0 dishes: %q", fb.get("/babai/o/vtr/promo.json"))
	}
	// default: the list is removed online
	promoPost(t, map[string]any{"Op": "default"}, true)
	cloudStep(time.Now().Add(6 * time.Minute))
	if fb.count("DELETE /babai/o/vtr/promo") == 0 {
		t.Fatal("default dishes: list not removed online")
	}
	// survives a restart
	promoPost(t, map[string]any{"Op": "add", "T": "Pongal", "Image": dishPhoto(t, 90)}, true)
	promoMu.Lock()
	promo = promoState{}
	promoMu.Unlock()
	loadPromo()
	if p := promoSnapshot(); len(p.Dishes) != 1 || p.Dishes[0].T != "Pongal" {
		t.Fatalf("after restart: %+v", p)
	}
}

// A real row from the SJP billing PC: numbers where the KDS app's DTO has text (and the other way
// round) must still give the KOT, not silently drop it.
func TestRowsAnyFieldTypes(t *testing.T) {
	raw := `{"error":"","data":[
{"is_running":0,"created":"2026-10-12 07:06:53","is_informed":0,"kot_item_id":17239604227,"id":9677671302,"old_order_id":258623,"order_id":9620116129,"order_type":3,"table_no":"0","kot_unique_id":9,"extra_data":"{\"s_o_name\":\"Self Service\"}","status":1,"quantity":1,"name":"Filter Coffee","category_id":10148052,"p_o_id":null,"o_u_id":null,"kot_status":1,"category":"Beverages","tp_virtual_brand_name":""},
{"created":"2026-10-12 07:08:10","kot_item_id":"17239604300","id":"9677671400","order_type":"3","table_no":5,"kot_unique_id":"569","status":"9","quantity":"2.000","name":"Ghee Podi Dosa","p_o_id":12345,"kot_status":"9","category":"Dosa"},
{"created":"2026-10-12 07:08:10","id":null,"name":"no id"}]}`
	var res struct {
		Data []json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal([]byte(raw), &res); err != nil {
		t.Fatal(err)
	}
	var rows []Row
	for _, d := range res.Data {
		var r Row
		if json.Unmarshal(d, &r) == nil && r.ID.OK {
			rows = append(rows, r)
		}
	}
	kots := groupRows(rows)
	if len(kots) != 2 {
		t.Fatalf("want 2 KOTs, got %d", len(kots))
	}
	a, b := kots[0], kots[1]
	if a.ID != 9677671302 || a.Token != 9 || a.OrderType != 3 || a.Status != "1" || a.Table != "0" || len(a.Items) != 1 {
		t.Fatalf("first KOT wrong: %+v", a)
	}
	if b.Token != 569 || b.OrderType != 3 || b.Status != "9" || b.Table != "5" || b.POID != "12345" ||
		b.Items[0].Q != 2 || b.Items[0].S != 9 || !isDosa(b.Items[0].C) {
		t.Fatalf("second KOT wrong: %+v", b)
	}
	tb, _ := dineInDosa(kots)
	if len(tb) != 1 || tb[0].label != "569" || !tb[0].ready {
		t.Fatalf("dosa token 569 not found: %+v", tb)
	}
}
