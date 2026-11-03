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

// A keyboard-mode scanner on the Console PC marks the order Food Ready, like a scanner phone.
func TestPCScanKeyboardMode(t *testing.T) {
	_, pp, done := setup(t)
	defer done()
	pp.set(onlineRow(701, 71, "1", "4937881707454", time.Now().Add(-3*time.Minute), "Pesarattu", 1308051404, 1))
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	send := func(code string, own bool) (*httptest.ResponseRecorder, pcScanEvent) {
		b, _ := json.Marshal(map[string]string{"C": code})
		r := httptest.NewRequest(http.MethodPost, "/console/api/scan", bytes.NewReader(b))
		if own {
			r.Header.Set("X-Babai-Hub", "1")
		}
		w := httptest.NewRecorder()
		handlePCScan(w, r)
		var e pcScanEvent
		_ = json.Unmarshal(w.Body.Bytes(), &e)
		return w, e
	}
	if w, _ := send("4937881707454", false); w.Code != http.StatusForbidden {
		t.Fatalf("a scan not from the Console page: %d", w.Code)
	}
	_, e := send("4937881707454", true)
	if !e.OK || e.Already || e.Last4 != "7454" {
		t.Fatalf("scan: %+v", e)
	}
	if _, e = send("4937881707454", true); !e.OK || !e.Already {
		t.Fatalf("the same bill again: want already food ready, got %+v", e)
	}
	pp.mu.Lock()
	n := len(pp.marks)
	pp.mu.Unlock()
	if n != 1 {
		t.Fatalf("want 1 change_kot_status, got %d", n)
	}
	if l := scanLogSnapshot()[0]; l.Device != "PC scanner" || l.Route != "pc" {
		t.Fatalf("log %+v", l)
	}
	if _, e = send("hello", true); e.OK || !strings.Contains(e.Msg, "Not an order barcode") {
		t.Fatalf("not a barcode: %+v", e)
	}
	if _, e = send("99999999", true); e.OK {
		t.Fatalf("unknown order marked: %+v", e)
	}
}

// COM mode: what the port sends becomes scans, with or without Enter, and shows up in the feed.
func TestPCScanCOMMode(t *testing.T) {
	var sp lineSplitter
	got := sp.feed([]byte("49378817"))
	got = append(got, sp.feed([]byte("07454\r\n252475675152540\n"))...)
	if len(got) != 2 || got[0] != "4937881707454" || got[1] != "252475675152540" {
		t.Fatalf("lines %q", got)
	}
	if got := sp.feed([]byte("8794428444")); len(got) != 0 || sp.quiet() != "8794428444" || sp.quiet() != "" {
		t.Fatal("a scan with no Enter ends when the port goes quiet")
	}

	_, pp, done := setup(t)
	defer done()
	pp.set(onlineRow(702, 72, "1", "8794428444", time.Now().Add(-2*time.Minute), "Idli", 1308051407, 1))
	if err := pollOnce(cfg.Server); err != nil {
		t.Fatal(err)
	}
	w := httptest.NewRecorder()
	handlePCScanFeed(w, httptest.NewRequest(http.MethodGet, "/console/api/scanfeed?after=-1", nil))
	var f struct {
		Seq   int64
		Items []pcScanEvent
	}
	_ = json.Unmarshal(w.Body.Bytes(), &f)
	if len(f.Items) != 0 {
		t.Fatal("the page opening gets no old scans")
	}
	comLine("COM3", "8794428444\x00")
	w = httptest.NewRecorder()
	handlePCScanFeed(w, httptest.NewRequest(http.MethodGet, "/console/api/scanfeed?after="+itoa(int(f.Seq)), nil))
	_ = json.Unmarshal(w.Body.Bytes(), &f)
	if len(f.Items) != 1 || !f.Items[0].OK || f.Items[0].Last4 != "8444" {
		t.Fatalf("feed %s", w.Body.String())
	}
	if l := scanLogSnapshot()[0]; l.Device != "PC scanner (COM3)" || l.Route != "com" {
		t.Fatalf("log %+v", l)
	}
	if !validComName("COM3") || validComName("LPT1") || validComName("COM0") || validComName("COM3;") {
		t.Fatal("port names")
	}
}
