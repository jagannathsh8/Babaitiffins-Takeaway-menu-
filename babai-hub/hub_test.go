package main

import (
	"encoding/json"
	"fmt"
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
	cfg = Config{Server: strings.TrimPrefix(psrv.URL, "http://"), DB: fsrv.URL, Secret: "s3cret", Page: defaultPage, AutoClearMin: 10}
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
	archAt = time.Time{}
	lastOKAt, lastErrAt, lastErr = time.Time{}, time.Time{}, ""
	prunedDay = time.Now().Format("2006-01-02")
	return fb, pp, func() { fsrv.Close(); psrv.Close() }
}

func phoneToken(t *testing.T, fb *fakeFirebase, key string) (PhoneToken, bool) {
	var m map[string]PhoneToken
	raw := fb.get("/babai/t.json")
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
	if strings.Contains(fb.get("/babai/live.json"), "9999999999") || strings.Contains(fb.get("/babai/live.json"), "Ravi") {
		t.Fatal("customer details sent to the cloud")
	}
	if !strings.HasPrefix(fb.get("/babai/meta.json"), `{"u":{".sv":"timestamp"},`) {
		t.Fatalf("meta: %s", fb.get("/babai/meta.json"))
	}
	if !strings.Contains(fb.get("/babai/stock.json"), `"12":{"c":"Dosa","n":"Pesarattu","on":0}`) {
		t.Fatalf("stock: %s", fb.get("/babai/stock.json"))
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
	n := fb.count("PUT /babai/t.json")
	cloudStep(now.Add(8 * time.Second))
	if fb.count("PUT /babai/t.json") != n {
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
		if strings.HasPrefix(r, "PATCH /babai/days/") {
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
	now := time.Now()
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
	if serverBase("192.168.1.5") != "http://192.168.1.5:3000/" || serverBase("http://192.168.1.5:8080/") != "http://192.168.1.5:8080/" {
		t.Fatalf("server base: %s", serverBase("192.168.1.5"))
	}
}
