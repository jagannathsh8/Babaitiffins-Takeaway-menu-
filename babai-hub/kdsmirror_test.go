package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
)

func TestKDSMirror(t *testing.T) {
	apk := []byte("PK fake apk for the test")
	sum := sha256.Sum256(apk)
	site := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/kds/version.json":
			_ = json.NewEncoder(w).Encode(kdsInfo{B: 3, URL: "Petpooja_KDS.apk", SHA256: hex.EncodeToString(sum[:])})
		case "/kds/Petpooja_KDS.apk":
			_, _ = w.Write(apk)
		default:
			http.NotFound(w, r)
		}
	}))
	defer site.Close()
	oldClient, oldCfg, oldDir := updClient, cfg, dataDir
	defer func() { updClient, cfg, dataDir = oldClient, oldCfg, oldDir }()
	updClient = site.Client()
	dataDir = t.TempDir()
	cfg = Config{Page: site.URL + "/"}
	kdsMu.Lock()
	kdsHave, kdsPath = kdsInfo{}, ""
	kdsMu.Unlock()

	w := httptest.NewRecorder()
	handleKDS(w, httptest.NewRequest(http.MethodGet, "/kds/version.json", nil))
	if w.Code != 404 {
		t.Fatalf("before download: %d", w.Code)
	}
	kdsCheck()
	w = httptest.NewRecorder()
	handleKDS(w, httptest.NewRequest(http.MethodGet, "/kds/version.json", nil))
	var v kdsInfo
	if json.Unmarshal(w.Body.Bytes(), &v) != nil || v.B != 3 || v.URL != "app.apk" || v.SHA256 != hex.EncodeToString(sum[:]) {
		t.Fatalf("version.json %s (%s)", w.Body.String(), kdsStatus())
	}
	w = httptest.NewRecorder()
	handleKDS(w, httptest.NewRequest(http.MethodGet, "/kds/app.apk", nil))
	if w.Body.String() != string(apk) {
		t.Fatal("apk not served")
	}
	// after a restart it serves the same file
	kdsMu.Lock()
	kdsHave, kdsPath = kdsInfo{}, ""
	kdsMu.Unlock()
	kdsLoadLocal()
	if kdsStatus() != "build 3 ready for the KDS screens" {
		t.Fatal(kdsStatus())
	}
	// a damaged file is refused
	bad := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/kds/version.json" {
			_ = json.NewEncoder(w).Encode(kdsInfo{B: 4, URL: "x.apk", SHA256: hex.EncodeToString(sum[:])})
			return
		}
		_, _ = w.Write([]byte("tampered"))
	}))
	defer bad.Close()
	updClient, cfg = bad.Client(), Config{Page: bad.URL + "/"}
	kdsCheck()
	if _, err := os.Stat(kdsPath); err != nil || kdsHave.B != 3 {
		t.Fatalf("tampered build replaced the good one: %v %d", err, kdsHave.B)
	}
}
