package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestOutletAndPlatformCodes(t *testing.T) {
	for in, want := range map[string]string{"Whitefield": "VTR", "Varthur Main Road, Whitefield": "VTR", "vtr": "VTR", "Sarjapur Road": "SJP",
		"JP Nagar": "JPN", "Arekere": "JPN", "HSR Layout": "HSR", "": ""} {
		if got := outletCodeCX(in); got != want {
			t.Errorf("outletCodeCX(%q) = %q, want %q", in, got, want)
		}
	}
	for _, c := range []struct {
		o    OrderRec
		want string
	}{{OrderRec{Channel: "Swiggy"}, "S"}, {OrderRec{Channel: "Zomato"}, "Z"}, {OrderRec{Brand: "Ownly"}, "O"},
		{OrderRec{OrderID: "12345678", Channel: "Magicpin"}, "X"}, {OrderRec{Type: 3}, ""}} {
		if got := platformCode(c.o); got != c.want {
			t.Errorf("platformCode(%+v) = %q, want %q", c.o, got, c.want)
		}
	}
}

// writeDay saves a day of orders the way the Console does.
func writeDay(t *testing.T, day time.Time, list []OrderRec) {
	t.Helper()
	_ = os.MkdirAll(ordersDir(), 0o755)
	b, _ := json.Marshal(list)
	if err := os.WriteFile(filepath.Join(ordersDir(), day.Format("2006-01-02")+".json"), b, 0o644); err != nil {
		t.Fatal(err)
	}
}

func metricsEnv(t *testing.T) func() {
	oldDir, oldCfg := dataDir, cfg
	dataDir = t.TempDir()
	cfg = Config{Outlet: "Whitefield"}
	dayCacheMu.Lock()
	dayCache = map[string]dayCached{}
	dayCacheMu.Unlock()
	lrnMu.Lock()
	oldL := lrn
	lrnMu.Unlock()
	return func() {
		dataDir, cfg = oldDir, oldCfg
		lrnMu.Lock()
		lrn = oldL
		lrnMu.Unlock()
	}
}

func TestRelearnEarlyLimitsAndArrival(t *testing.T) {
	defer metricsEnv(t)()
	now := time.Date(2026, 10, 20, 15, 0, 0, 0, time.Local)
	var kot int64 = 1
	for i := 1; i <= 30; i++ {
		d := now.AddDate(0, 0, -i)
		var list []OrderRec
		for j := 0; j < 3; j++ {
			p := time.Date(d.Year(), d.Month(), d.Day(), 9, 10+j, 0, 0, time.Local).UnixMilli()
			// Swiggy: scanned Food Ready, rider takes 2 min (two thirds) or 4 min after it
			gap := int64(2 * 60000)
			if j == 2 {
				gap = 4 * 60000
			}
			list = append(list, OrderRec{Kot: kot, OrderID: "21234567890", Channel: "Swiggy", Type: 1, Punched: p, Seen: p,
				Ready: p + 8*60000, ReadyBy: "Scanner: Counter", Dispatched: p + 8*60000 + gap, Status: "dispatched"})
			kot++
		}
		writeDay(t, d, list)
	}
	relearn(now)
	lrnMu.Lock()
	l := lrn
	lrnMu.Unlock()
	if l.Days != 30 {
		t.Fatalf("days %d", l.Days)
	}
	if got := l.Early["S"]["B"]; got != 4 {
		t.Fatalf("learned Swiggy breakfast limit %v, want 4 (P90)", got)
	}
	// arrival learned from the orders whose food waited 3+ min: punch -> pickup - 1 min = 11 min
	p := time.Date(2026, 10, 20, 9, 12, 0, 0, time.Local).UnixMilli()
	if a := arrivalMs("S", p); a != p+11*60000 {
		t.Fatalf("arrival %v min after punch, want 11", float64(a-p)/60000)
	}
	if arrivalMs("Z", p) != 0 {
		t.Fatal("Zomato has nothing learned")
	}
}

func TestOpsAndLoadHandlers(t *testing.T) {
	defer metricsEnv(t)()
	today := time.Now()
	at := func(h, m int) int64 {
		return time.Date(today.Year(), today.Month(), today.Day(), h, m, 0, 0, time.Local).UnixMilli()
	}
	list := []OrderRec{
		{Kot: 1, OrderID: "7400000001", Channel: "Zomato", Type: 1, Punched: at(0, 10), Seen: at(0, 10), Items: []KItem{{N: "Ghee Roast Dosa", C: "Dosa", Q: 2}},
			Ready: at(0, 22), ReadyBy: "Scanner: Pass", Dispatched: at(0, 25), Status: "dispatched"},
		{Kot: 2, Type: 3, Punched: at(0, 5), Seen: at(0, 5), Items: []KItem{{N: "Masala Dosa", C: "Dosa", Q: 3}}, Ready: at(0, 20), Status: "ready"},
		{Kot: 3, OrderID: "2100000000001", Channel: "Swiggy", Type: 1, Punched: at(0, 40), Seen: at(0, 40), Items: []KItem{{N: "Idli Vada", Q: 1}}, Status: "cancelled"},
	}
	ordMu.Lock()
	saved := orders
	orders = map[int64]*OrderRec{}
	ordMu.Unlock()
	defer func() { ordMu.Lock(); orders = saved; ordMu.Unlock() }()
	writeDay(t, today, list)

	w := httptest.NewRecorder()
	handleOps(w, httptest.NewRequest("GET", "/console/api/ops", nil))
	var ops struct {
		Rows    []opsRow `json:"rows"`
		Outlet  string   `json:"outlet"`
		Targets MetricTargets
	}
	if err := json.Unmarshal(w.Body.Bytes(), &ops); err != nil {
		t.Fatal(err, w.Body.String())
	}
	if ops.Outlet != "VTR" || len(ops.Rows) != 2 || ops.Rows[0].P != "Z" || ops.Rows[1].P != "" || ops.Rows[0].Items[0] != "2 x Ghee Roast Dosa" {
		t.Fatalf("ops %+v", ops)
	}

	w = httptest.NewRecorder()
	handleOpsLoad(w, httptest.NewRequest("GET", "/console/api/opsload?kot=1&day="+today.Format("2006-01-02"), nil))
	var L struct {
		Vol       int            `json:"vol"`
		Plat      map[string]int `json:"plat"`
		InKitchen int            `json:"inKitchen"`
		DosaQueue float64        `json:"dosaQueue"`
		Items     []loadItem     `json:"items"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &L); err != nil {
		t.Fatal(err, w.Body.String())
	}
	// both KOTs are within 15 min; the dine-in one (3 dosas) was cooking when order 1 came in
	if L.Vol != 2 || L.Plat["Z"] != 1 || L.Plat["D"] != 1 || L.InKitchen != 2 || L.DosaQueue != 5 || len(L.Items) != 2 {
		t.Fatalf("load %+v", L)
	}
	for _, it := range L.Items {
		if it.Name == "Masala Dosa" && (it.Waiting != 3 || it.AvgThen != 15) {
			t.Fatalf("masala dosa %+v", it)
		}
	}
}

func TestCXProxyFiltersOutlet(t *testing.T) {
	defer metricsEnv(t)()
	sheet := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("format") != "json" {
			http.Error(w, "no", 400)
			return
		}
		_, _ = w.Write([]byte(`{"updated":"x","cols":["id","outlet","rating"],"rows":[["1","VTR",5],["2","SJP",1],["3","VTR whitefield",2]]}`))
	}))
	defer sheet.Close()
	cfg = Config{Outlet: "Whitefield", CXURL: sheet.URL + "/exec"}
	cxGetMu.Lock()
	cxGetBody = nil
	cxGetMu.Unlock()
	w := httptest.NewRecorder()
	handleCX(w, httptest.NewRequest("GET", "/console/api/cx", nil))
	var d struct {
		OK   bool    `json:"ok"`
		Rows [][]any `json:"rows"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &d)
	if !d.OK || len(d.Rows) != 2 {
		t.Fatalf("cx %s", w.Body.String())
	}
}

func TestTargetsSaveAndPrune(t *testing.T) {
	defer metricsEnv(t)()
	body := `{"kptG":8,"kptO":12,"rwtG":3,"rwtO":5,"frG":85,"frO":80,"rS":4.6,"rZ":4.4,"rO":4.5,"rBand":0.1,"late":20,"pick":1,"eS":5,"eZ":3,"eO":5,"useLearned":false}`
	r := httptest.NewRequest("POST", "/console/api/metrics/targets", strings.NewReader(body))
	w := httptest.NewRecorder()
	handleMetricTargets(w, r)
	if w.Code != 403 {
		t.Fatal("a POST without the Console header must be refused")
	}
	r = httptest.NewRequest("POST", "/console/api/metrics/targets", strings.NewReader(body))
	r.Header.Set("X-Babai-Hub", "1")
	w = httptest.NewRecorder()
	handleMetricTargets(w, r)
	if tg := loadTargets(); tg.KptG != 8 || tg.UseLearned {
		t.Fatalf("targets %+v (%s)", tg, w.Body.String())
	}
	bad := strings.Replace(body, `"kptO":12`, `"kptO":5`, 1)
	r = httptest.NewRequest("POST", "/console/api/metrics/targets", strings.NewReader(bad))
	r.Header.Set("X-Babai-Hub", "1")
	w = httptest.NewRecorder()
	handleMetricTargets(w, r)
	if !strings.Contains(w.Body.String(), `"ok":false`) {
		t.Fatal("orange below green must be refused")
	}

	now := time.Now()
	old, recent := now.AddDate(0, 0, -121).Format("2006-01-02"), now.AddDate(0, 0, -119).Format("2006-01-02")
	for _, f := range []string{filepath.Join(ordersDir(), old+".json"), filepath.Join(ordersDir(), recent+".json"),
		filepath.Join(dataDir, "days", old+".jsonl"), filepath.Join(prepDaysDir(), old+".txt")} {
		_ = os.MkdirAll(filepath.Dir(f), 0o755)
		_ = os.WriteFile(f, []byte("[]"), 0o644)
	}
	pruneOldData(now)
	for f, keep := range map[string]bool{filepath.Join(ordersDir(), old+".json"): false, filepath.Join(ordersDir(), recent+".json"): true,
		filepath.Join(dataDir, "days", old+".jsonl"): false, filepath.Join(prepDaysDir(), old+".txt"): false} {
		if _, err := os.Stat(f); (err == nil) != keep {
			t.Errorf("%s kept=%v, want %v", f, err == nil, keep)
		}
	}
}

func TestMetricsOnTheWiFi(t *testing.T) {
	defer metricsEnv(t)()
	cfg = Config{Outlet: "Whitefield", Pin: "4321"}
	mux := http.NewServeMux()
	consoleRoutes(mux)
	metricsLANRoutes(mux)
	lan := func(method, path, body, cookie string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, path, strings.NewReader(body))
		r.RemoteAddr = "192.168.0.50:51000"
		r.Header.Set("X-Babai-Hub", "1")
		if cookie != "" {
			r.AddCookie(&http.Cookie{Name: pinCookie, Value: cookie})
		}
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, r)
		return w
	}
	if w := lan("GET", "/metrics", "", ""); !strings.Contains(w.Body.String(), `fetch("/metrics/unlock"`) {
		t.Fatal("locked: the PIN screen first")
	}
	if w := lan("GET", "/metrics/api/ops", "", ""); w.Code != 401 {
		t.Fatalf("api without PIN: %d", w.Code)
	}
	if w := lan("POST", "/metrics/unlock", `{"Pin":"1111"}`, ""); !strings.Contains(w.Body.String(), `"ok":false`) {
		t.Fatal("wrong PIN accepted")
	}
	w := lan("POST", "/metrics/unlock", `{"Pin":"4321"}`, "")
	var tok string
	for _, c := range w.Result().Cookies() {
		if c.Name == pinCookie {
			tok = c.Value
		}
	}
	if tok == "" {
		t.Fatal("no session after the right PIN")
	}
	if w := lan("GET", "/metrics", "", tok); !strings.Contains(w.Body.String(), "MX_READONLY = true") {
		t.Fatal("unlocked: the view-only page")
	}
	if w := lan("GET", "/metrics/api/ops", "", tok); w.Code != 200 || !strings.Contains(w.Body.String(), `"outlet":"VTR"`) {
		t.Fatalf("ops on the Wi-Fi: %d %s", w.Code, w.Body.String())
	}
	if w := lan("POST", "/metrics/api/ops", "{}", tok); w.Code != 405 {
		t.Fatal("view only")
	}
	if w := lan("GET", "/console/api/ops", "", tok); w.Code != 403 {
		t.Fatal("the Console's own API stays on the PC")
	}
	if w := lan("POST", "/console/api/metrics/targets", "{}", tok); w.Code != 403 {
		t.Fatal("targets can't be changed from the Wi-Fi")
	}
}
