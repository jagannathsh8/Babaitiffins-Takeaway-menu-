package main

import (
	"bytes"
	"encoding/json"
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

// fakeFirebase records every request the hub makes.
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

func setup(t *testing.T) (*fakeFirebase, func()) {
	fake := &fakeFirebase{data: map[string]string{}}
	srv := httptest.NewServer(fake)
	dataDir = t.TempDir()
	_ = os.MkdirAll(filepath.Join(dataDir, "days"), 0o755)
	cfg = Config{DB: srv.URL, Secret: "s3cret", Page: defaultPage}
	mu.Lock()
	dosaRaw, liveRaw, lastIngest = nil, nil, time.Time{}
	open = map[int64]*Rec{}
	pending = map[string]map[string]*Rec{}
	mu.Unlock()
	sentDosa, sentDosaAt, sentLive, sentLiveAt, archAt = nil, time.Time{}, nil, time.Time{}, time.Time{}
	lastOKAt, lastErrAt, lastErr = time.Time{}, time.Time{}, ""
	prunedDay = time.Now().Format("2006-01-02") // no prune during tests
	return fake, srv.Close
}

func ingest(t *testing.T, body string) map[string]any {
	req := httptest.NewRequest(http.MethodPost, "/ingest", strings.NewReader(body))
	req.RemoteAddr = "192.168.1.20:5555"
	w := httptest.NewRecorder()
	handleIngest(w, req)
	if w.Code != 200 {
		t.Fatalf("ingest: HTTP %d %s", w.Code, w.Body.String())
	}
	var out map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return out
}

func tvBody(tokensReady string, kots string) string {
	return `{"v":1,"dev":"Test TV","dosa":{"v":1,"w":"8-10 min","r":[` + tokensReady + `],"p":[{"t":"42","m":6,"o":0}]},"kots":` + kots + `}`
}

const kot1 = `{"id":101,"tk":42,"tb":"","ot":1,"ch":"","po":"","st":"1","c":1760000000000,"rd":0,"i":[{"n":"Masala Dosa","c":"Dosa","q":2,"s":1}]}`
const kot1Ready = `{"id":101,"tk":42,"tb":"","ot":1,"ch":"","po":"","st":"9","c":1760000000000,"rd":1,"i":[{"n":"Masala Dosa","c":"Dosa","q":2,"s":9}]}`
const kot2 = `{"id":202,"tk":7,"tb":"","ot":3,"ch":"Swiggy","po":"250413427652902","st":"1","c":1760000005000,"rd":0,"i":[{"n":"Idli","c":"Tiffin","q":1,"s":1}]}`

func TestFlow(t *testing.T) {
	fake, stop := setup(t)
	defer stop()
	t0 := time.Now()

	// first data from the TV: no QR yet (cloud not proven)
	out := ingest(t, tvBody("", "["+kot1+","+kot2+"]"))
	if out["qr"] != "" {
		t.Fatalf("QR before cloud OK: %v", out["qr"])
	}
	cloudStep(t0)
	if fake.count("PUT /babai/dosa.json") != 1 || fake.count("PUT /babai/live.json") != 1 {
		t.Fatalf("first push missing: %v", fake.reqs)
	}
	d := fake.data["/babai/dosa.json"]
	if !strings.HasPrefix(d, `{"u":{".sv":"timestamp"},"v":1`) {
		t.Fatalf("dosa body: %s", d)
	}
	for _, r := range fake.reqs {
		if !strings.Contains(r, "auth=s3cret") || !strings.Contains(r, "print=silent") {
			t.Fatalf("auth/silent missing: %s", r)
		}
	}
	// now the TV gets the QR address
	out = ingest(t, tvBody("", "["+kot1+","+kot2+"]"))
	if !strings.HasPrefix(out["qr"].(string), defaultPage+"?d=") {
		t.Fatalf("QR: %v", out["qr"])
	}

	// unchanged data: nothing sent for a while (free plan)
	for s := 1; s <= 40; s++ {
		cloudStep(t0.Add(time.Duration(s) * time.Second))
	}
	if n := fake.count("PUT /babai/dosa.json"); n != 1 {
		t.Fatalf("unchanged dosa re-sent %d times", n)
	}
	// changed: sent, but not more than once per 5 s
	ingest(t, tvBody(`{"t":"42","a":1}`, "["+kot1Ready+","+kot2+"]"))
	for s := 41; s <= 44; s++ {
		cloudStep(t0.Add(time.Duration(s) * time.Second))
	}
	if n := fake.count("PUT /babai/dosa.json"); n != 2 {
		t.Fatalf("changed dosa: %d sends", n)
	}
	// heartbeat after a minute even when unchanged (the TV is still sending)
	ingest(t, tvBody(`{"t":"42","a":1}`, "["+kot1Ready+","+kot2+"]"))
	lastIngest = t0.Add(101 * time.Second)
	cloudStep(t0.Add(102 * time.Second))
	if n := fake.count("PUT /babai/dosa.json"); n != 3 {
		t.Fatalf("heartbeat: %d sends", n)
	}

	// KOT 101 leaves the board; archived after a minute of absence, once
	base := t0.Add(110 * time.Second)
	mu.Lock()
	track([]Kot{}, base) // (via the same path as an ingest)
	mu.Unlock()
	ingest(t, tvBody("", "["+kot2+"]"))
	mu.Lock()
	track(mustKots(t, "["+kot2+"]"), base.Add(61*time.Second))
	n := len(pending)
	mu.Unlock()
	if n != 1 {
		t.Fatalf("finished KOT not queued: %d", n)
	}
	archAt = time.Time{}
	lastIngest = base.Add(61 * time.Second)
	cloudStep(base.Add(62 * time.Second))
	var day string
	for p := range fake.data {
		if strings.HasPrefix(p, "/babai/days/") {
			day = p
		}
	}
	if day == "" || !strings.Contains(fake.data[day], `"101":{"id":101,"tk":42`) {
		t.Fatalf("archive not uploaded: %v / %s", fake.reqs, fake.data[day])
	}
	if !strings.Contains(fake.data[day], `"rdy":`) || !strings.Contains(fake.data[day], `"r":`) {
		t.Fatalf("ready times missing: %s", fake.data[day])
	}
	files, _ := filepath.Glob(filepath.Join(dataDir, "days", "*.jsonl"))
	if len(files) != 1 {
		t.Fatalf("local archive file missing")
	}
	mu.Lock()
	left := len(pending)
	mu.Unlock()
	if left != 0 {
		t.Fatalf("pending not cleared")
	}

	// TV stops sending: hub stops re-sending old data
	before := fake.count("PUT")
	for s := 0; s < 300; s += 5 {
		cloudStep(base.Add(time.Duration(200+s) * time.Second))
	}
	if fake.count("PUT") != before {
		t.Fatalf("stale data re-sent")
	}
}

func TestCloudErrorHidesQR(t *testing.T) {
	fake, stop := setup(t)
	defer stop()
	fake.code = 401
	ingest(t, tvBody("", "[]"))
	cloudStep(time.Now())
	if cloudOK() {
		t.Fatal("cloud OK after 401")
	}
	out := ingest(t, tvBody("", "[]"))
	if out["qr"] != "" {
		t.Fatalf("QR shown with a broken cloud")
	}
	if !strings.Contains(cloudStatus()["cloudErr"].(string), "secret") {
		t.Fatalf("error text: %v", cloudStatus()["cloudErr"])
	}
}

func TestIngestOnlyFromShop(t *testing.T) {
	req := httptest.NewRequest(http.MethodPost, "/ingest", bytes.NewBufferString(`{}`))
	req.RemoteAddr = "8.8.8.8:1234"
	w := httptest.NewRecorder()
	handleIngest(w, req)
	if w.Code != http.StatusForbidden {
		t.Fatalf("public address allowed: %d", w.Code)
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
	if string(withServerTime([]byte(`{}`))) != `{"u":{".sv":"timestamp"}}` {
		t.Fatal("empty object")
	}
}

func mustKots(t *testing.T, s string) []Kot {
	var k []Kot
	if err := json.Unmarshal([]byte(s), &k); err != nil {
		t.Fatal(err)
	}
	return k
}
