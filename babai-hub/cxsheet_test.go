package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func resetCX() {
	cxMu.Lock()
	cxSent, cxAt, cxOKAt, cxState, cxFor = map[int64]string{}, time.Time{}, time.Time{}, "not set up", ""
	cxMu.Unlock()
	cxSendFn = cxPost
}

func TestCXSheetSendsKitchenTimesOnce(t *testing.T) {
	_, _, done := setup(t)
	defer done()
	resetCX()
	defer resetCX()
	now := time.Now()
	p := now.Add(-40 * time.Minute).UnixMilli()
	ordMu.Lock()
	ordLoaded = true
	orders = map[int64]*OrderRec{
		1: {Kot: 1, OrderID: "251265509181492", Channel: "Swiggy", Punched: p, Seen: p + 2000, Token: 41, Printed: p + 3000, PrintMs: 1200,
			Ready: p + 9*60000, Dispatched: p + 30*60000, Status: "dispatched", Items: []KItem{{N: "Idli", Q: 2}, {N: "Vada", Q: 1}}},
		2: {Kot: 2, OrderID: "8736920371", Channel: "Zomato", Punched: p, Seen: p, Status: "preparing"},
		3: {Kot: 3, OrderID: "", Table: "4", Punched: p, Seen: p, Status: "preparing"},                 // dine in: no order ID
		4: {Kot: 4, OrderID: "8736920999", Punched: p, Seen: p, Status: "cancelled"},                   // cancelled
		5: {Kot: 5, OrderID: "8700000001", Punched: p, Seen: now.Add(-9 * 24 * time.Hour).UnixMilli()}, // too old
	}
	ordMu.Unlock()
	skewNow.Store(0)

	var got [][]CXRow
	cxSendFn = func(link, key string, rows []CXRow) error {
		if key != "k1" {
			t.Fatalf("key %q", key)
		}
		got = append(got, append([]CXRow(nil), rows...))
		return nil
	}
	cxSync(now, false)
	if len(got) != 0 {
		t.Fatal("sent without a link")
	}
	cfg.CXURL, cfg.CXKey = "https://script.google.com/macros/s/x/exec", "k1"
	cxSync(now, false)
	if len(got) != 1 || len(got[0]) != 2 {
		t.Fatalf("want 2 orders in one send, got %+v", got)
	}
	var sw CXRow
	for _, r := range got[0] {
		if r.Kot == 1 {
			sw = r
		}
	}
	if sw.ID != "251265509181492" || sw.Outlet != "VTR" || sw.KptMin != 9 || sw.WaitMin != 21 || sw.Slot != "41" || sw.Items != 2 || sw.Names != "2 x Idli, 1 x Vada" || sw.PrintSec != 1.2 {
		t.Fatalf("row %+v", sw)
	}
	// nothing changed: nothing sent, even when asked
	cxSync(now.Add(16*time.Minute), true)
	if len(got) != 1 {
		t.Fatalf("resent unchanged orders: %d sends", len(got))
	}
	// food ready on order 2: only that one goes, and only when due
	ordMu.Lock()
	orders[2].Ready, orders[2].Status = p+12*60000, "ready"
	ordMu.Unlock()
	cxSync(now.Add(20*time.Minute), false)
	if len(got) != 1 {
		t.Fatal("sent before 15 minutes")
	}
	cxSync(now.Add(32*time.Minute), false)
	if len(got) != 2 || len(got[1]) != 1 || got[1][0].Kot != 2 || got[1][0].KptMin != 12 {
		t.Fatalf("update %+v", got)
	}
	if st, ok := cxStatus(); !ok || !strings.HasPrefix(st, "sent") {
		t.Fatalf("state %q", st)
	}
}

// The Apps Script web app answers a POST with a redirect to its reply.
func TestCXSheetPostFollowsAppsScriptRedirect(t *testing.T) {
	var body map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/macros/s/x/exec":
			b, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(b, &body)
			http.Redirect(w, r, "/echo", http.StatusFound)
		case "/echo":
			if r.Method != http.MethodGet {
				t.Errorf("redirect followed with %s", r.Method)
			}
			io.WriteString(w, `{"ok":true,"n":1}`)
		case "/bad/exec":
			io.WriteString(w, `{"ok":false,"msg":"wrong key"}`)
		default:
			io.WriteString(w, "<html>sign in</html>")
		}
	}))
	defer srv.Close()
	rows := []CXRow{{ID: "8736920371", Kot: 2}}
	if err := cxPost(srv.URL+"/macros/s/x/exec", "k1", rows); err != nil {
		t.Fatal(err)
	}
	if body["key"] != "k1" || len(body["orders"].([]any)) != 1 {
		t.Fatalf("body %v", body)
	}
	if err := cxPost(srv.URL+"/bad/exec", "k1", rows); err == nil || err.Error() != "wrong key" {
		t.Fatalf("want wrong key, got %v", err)
	}
	if err := cxPost(srv.URL+"/login", "k1", rows); err == nil {
		t.Fatal("a sign-in page counted as sent")
	}
	if validCXURL("https://script.google.com/macros/s/AKfy/exec") != nil || validCXURL("https://evil.example/macros/s/x") == nil || validCXURL("http://script.google.com/macros/s/x") == nil {
		t.Fatal("link check")
	}
}
