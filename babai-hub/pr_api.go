package main

// Bridge Print's own pages and services, unchanged for the staff and devices that use them:
//
//	http://127.0.0.1:8787        the Bridge Print screen (Console → Bridge Print → Overview)
//	http://<this PC>:8787/display the token TV
//	TCP 8788 /api/mobile/token    the Android Token app's lookup
//	UDP 8789                      the Android Token app finding this PC
//
// Same files (bridgeui/), same API, same rules on who may use what.

import (
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

//go:embed bridgeui/*
var bridgeUI embed.FS

const (
	bridgeWebPort    = 8787
	bridgeMobilePort = 8788
	bridgeDiscPort   = 8789
	bridgeMobileKey  = "92d6b841b7c94fcba81ef778ed37a85f" // the Android Token app's key (as in Bridge Print)
)

var (
	bridgeCSRF = func() string {
		b := make([]byte, 32)
		_, _ = rand.Read(b)
		return hex.EncodeToString(b)
	}()
	mobileMu  sync.Mutex
	mobileAct = map[string]any{"requests": 0, "matches": 0, "lastRequestAt": nil, "lastOrderId": "", "lastClient": "", "lastMatchedToken": nil}
)

func bridgeServers() {
	go serveForever(fmt.Sprintf(":%d", bridgeWebPort), http.HandlerFunc(handleBridgeWeb), "Bridge Print screen")
	go serveForever(fmt.Sprintf(":%d", bridgeMobilePort), http.HandlerFunc(handleMobileToken), "Android token lookup")
	go discoveryLoop()
}

// serveForever keeps a port open, retrying while another program (the old Bridge Print) has it.
func serveForever(addr string, h http.Handler, what string) {
	warned := false
	for {
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			if !warned {
				log.Printf("%s: %s busy (%v); retrying", what, addr, err)
				warned = true
			}
			time.Sleep(3 * time.Second)
			continue
		}
		warned = false
		srv := &http.Server{Handler: h, ReadHeaderTimeout: 10 * time.Second}
		err = srv.Serve(ln)
		log.Printf("%s stopped: %v", what, err)
		time.Sleep(time.Second)
	}
}

func discoveryLoop() {
	for {
		pc, err := net.ListenPacket("udp4", fmt.Sprintf(":%d", bridgeDiscPort))
		if err != nil {
			time.Sleep(5 * time.Second)
			continue
		}
		buf := make([]byte, 512)
		for {
			n, addr, err := pc.ReadFrom(buf)
			if err != nil {
				break
			}
			if string(buf[:n]) == "BRIDGEPRINT:"+bridgeMobileKey {
				_, _ = pc.WriteTo([]byte(fmt.Sprintf("BRIDGEPRINT:%d", bridgeMobilePort)), addr)
			}
		}
		pc.Close()
	}
}

func localIPv4s() []string {
	var out []string
	ifs, _ := net.Interfaces()
	for _, i := range ifs {
		if i.Flags&net.FlagUp == 0 {
			continue
		}
		addrs, _ := i.Addrs()
		for _, a := range addrs {
			if n, ok := a.(*net.IPNet); ok && n.IP.To4() != nil {
				out = append(out, n.IP.String())
			}
		}
	}
	return out
}

func bjson(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

var (
	reJobAction = regexp.MustCompile(`^/api/jobs/([a-f0-9-]+)/(retry|reprint|ready|delete)$`)
	bridgeFiles = map[string]string{"/": "index.html", "/app.js": "app.js", "/style.css": "style.css",
		"/display": "display.html", "/display.js": "display.js", "/display.css": "display.css"}
)

func handleBridgeWeb(w http.ResponseWriter, r *http.Request) {
	host := r.Host
	loop := host == fmt.Sprintf("127.0.0.1:%d", bridgeWebPort) || host == fmt.Sprintf("localhost:%d", bridgeWebPort)
	lan := false
	if !loop {
		for _, a := range localIPv4s() {
			if !strings.HasPrefix(a, "127.") && host == fmt.Sprintf("%s:%d", a, bridgeWebPort) {
				lan = true
			}
		}
	}
	if !loop && !lan {
		bjson(w, 403, map[string]string{"error": "Use the Bridge computer address shown on its dashboard."})
		return
	}
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", fmt.Sprintf("default-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors http://localhost:%d http://127.0.0.1:%d; base-uri 'none'", consolePort(), consolePort()))
	p := r.URL.Path
	if lan {
		tvGet := r.Method == http.MethodGet && (p == "/display" || p == "/display.js" || p == "/display.css" || p == "/api/state")
		tvPost := r.Method == http.MethodPost && (p == "/api/jobs/clear" || (reJobAction.MatchString(p) && strings.HasSuffix(p, "/delete")))
		if !tvGet && !tvPost {
			bjson(w, 403, map[string]string{"error": "Only the TV display is available over the local network."})
			return
		}
	}
	switch {
	case r.Method == http.MethodGet && p == "/api/dashboard/watch":
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		io.WriteString(w, "watching\n")
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		select {
		case <-r.Context().Done():
		case <-time.After(10 * time.Minute):
		}
		return
	case r.Method == http.MethodGet && p == "/api/printers":
		list := allPrinters()
		out := make([]map[string]string, 0, len(list))
		for _, pi := range list {
			if pi.Lan {
				continue
			}
			out = append(out, map[string]string{"Name": pi.Name, "DriverName": pi.DriverName, "PortName": pi.PortName})
		}
		bjson(w, 200, map[string]any{"printers": out})
		return
	case r.Method == http.MethodGet && p == "/api/state":
		bjson(w, 200, bridgeState())
		return
	case r.Method == http.MethodPost:
		if r.Header.Get("X-Bridge-Token") != bridgeCSRF {
			bjson(w, 403, map[string]string{"error": "Refresh the dashboard and try again."})
			return
		}
		if !strings.HasPrefix(r.Header.Get("Content-Type"), "application/json") {
			bjson(w, 400, map[string]string{"error": "JSON required."})
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, 8193))
		if err != nil || len(body) > 8192 {
			bjson(w, 400, map[string]string{"error": "Request too large."})
			return
		}
		bridgePost(w, p, body)
		return
	case r.Method == http.MethodGet && p == "/download/android-app":
		serveOldTokenApk(w, r)
		return
	}
	if name, ok := bridgeFiles[p]; ok && r.Method == http.MethodGet {
		b, err := bridgeUI.ReadFile("bridgeui/" + name)
		if err != nil {
			bjson(w, 404, map[string]string{"error": "Not found."})
			return
		}
		ct := "text/css; charset=utf-8"
		if strings.HasSuffix(name, ".html") {
			ct = "text/html; charset=utf-8"
		} else if strings.HasSuffix(name, ".js") {
			ct = "text/javascript; charset=utf-8"
		}
		w.Header().Set("Content-Type", ct)
		w.Header().Set("Cache-Control", "no-store")
		w.Write(b)
		return
	}
	bjson(w, 404, map[string]string{"error": "Not found."})
}

func bridgeState() map[string]any {
	rxMu.Lock()
	rxMsg := rxErr
	rxMu.Unlock()
	prMu.Lock()
	n := len(prJobs)
	start := n - 100
	if start < 0 {
		start = 0
	}
	jobs := make([]Job, 0, n-start)
	for i := n - 1; i >= start; i-- {
		jobs = append(jobs, *prJobs[i])
	}
	q, u := 0, 0
	for _, j := range prJobs {
		if j.Status == "queued" || j.Status == "reading" {
			q++
		}
		if j.Status == "uncertain" {
			u++
		}
	}
	errMsg := prLastErr
	if errMsg == "" {
		errMsg = rxMsg
	}
	st := map[string]any{"settings": prSettings, "csrf": bridgeCSRF, "jobs": jobs,
		"counts": map[string]int{"queued": q, "uncertain": u}, "nextToken": prCounter.Next, "receiving": prRecv,
		"lastReceiveError": errMsg, "receivePort": receivePort}
	prMu.Unlock()
	var addrs []string
	for _, a := range localIPv4s() {
		if !strings.HasPrefix(a, "127.") {
			addrs = append(addrs, a)
		}
	}
	if addrs == nil {
		addrs = []string{}
	}
	st["addresses"] = addrs
	st["petpooja"] = liveSnapshot()
	mobileMu.Lock()
	m := map[string]any{}
	for k, v := range mobileAct {
		m[k] = v
	}
	mobileMu.Unlock()
	st["mobile"] = m
	return st
}

func bridgePost(w http.ResponseWriter, p string, body []byte) {
	fail := func(status int, msg string) { bjson(w, status, map[string]string{"error": msg}) }
	if p == "/api/token/reset" {
		var in struct{ Next json.Number }
		_ = json.Unmarshal(body, &in)
		n, err := in.Next.Int64()
		prMu.Lock()
		defer prMu.Unlock()
		if err != nil || n < 1 || int(n) > prSettings.TokenLimit {
			fail(400, "Choose a token number between 1 and your cycle limit.")
			return
		}
		prCounter = counterState{Next: int(n), ResetTo: int(n), ResetAt: nowISO()}
		_ = saveCounterLocked()
		bjson(w, 200, map[string]any{"ok": true, "nextToken": prCounter.Next})
		return
	}
	if p == "/api/settings" {
		prMu.Lock()
		prev := prSettings
		prMu.Unlock()
		next, err := validatePrintSettings(body, prev)
		if err != nil {
			fail(400, err.Error())
			return
		}
		if next.OutputMode == "windows" && next.PrinterName != "" {
			found := false
			list, _ := listWindowsPrinters()
			for _, pi := range list {
				if pi.Name == next.PrinterName {
					found = true
				}
			}
			if !found {
				fail(400, "That printer is not installed on this computer.")
				return
			}
		}
		prMu.Lock()
		prSettings = next
		err = savePrintSettingsLocked()
		if prCounter.Next > prSettings.TokenLimit {
			prCounter.Next = 1
			_ = saveCounterLocked()
		}
		prMu.Unlock()
		if err != nil {
			fail(400, "Couldn't save: "+err.Error())
			return
		}
		go drainQueued()
		go liveSync()
		bjson(w, 200, map[string]any{"ok": true, "warning": ""})
		return
	}
	if p == "/api/jobs/clear" {
		prMu.Lock()
		for _, j := range prJobs {
			if j.Status == "sending" {
				prMu.Unlock()
				fail(409, "Wait until the current print finishes, then clear the orders.")
				return
			}
		}
		for _, j := range prJobs {
			removeJobFiles(j)
		}
		prJobs = nil
		prMu.Unlock()
		bjson(w, 200, map[string]bool{"ok": true})
		return
	}
	m := reJobAction.FindStringSubmatch(p)
	if m == nil {
		fail(404, "Unknown action.")
		return
	}
	id, act := m[1], m[2]
	switch act {
	case "retry":
		if ok, msg := requeue(id, "uncertain"); !ok {
			fail(409, firstNonEmpty(msg, "Only uncertain jobs can be retried."))
			return
		}
	case "reprint":
		if ok, msg := requeue(id, "sent", "uncertain"); !ok {
			fail(409, firstNonEmpty(msg, "This job is already waiting or printing."))
			return
		}
	case "ready":
		prMu.Lock()
		j := findJobLocked(id)
		if j == nil {
			prMu.Unlock()
			fail(404, "Order not found.")
			return
		}
		t := true
		c := JobLive{}
		if j.Petpooja != nil {
			c = *j.Petpooja
		}
		c.Status, c.Active, c.ReadyAt = "ready", &t, nowISO()
		if j.Metadata != nil {
			c.Brand, c.OrderID = firstNonEmpty(j.Metadata.Brand, c.Brand), firstNonEmpty(j.Metadata.OrderID, c.OrderID)
		}
		j.Petpooja = &c
		saveJobLocked(j)
		prMu.Unlock()
	case "delete":
		prMu.Lock()
		idx := -1
		for i, j := range prJobs {
			if j.ID == id {
				idx = i
			}
		}
		if idx < 0 {
			prMu.Unlock()
			fail(404, "Order not found.")
			return
		}
		if prJobs[idx].Status == "sending" {
			prMu.Unlock()
			fail(409, "Wait until this order finishes printing, then delete it.")
			return
		}
		removeJobFiles(prJobs[idx])
		prJobs = append(prJobs[:idx], prJobs[idx+1:]...)
		prMu.Unlock()
	}
	bjson(w, 200, map[string]bool{"ok": true})
}

var reASCII32 = regexp.MustCompile(`^[\x20-\x7e]{0,32}$`)

// validatePrintSettings: Bridge Print's validateConfig.
func validatePrintSettings(body []byte, prev PrintSettings) (PrintSettings, error) {
	var in map[string]json.RawMessage
	if err := json.Unmarshal(body, &in); err != nil {
		return prev, errors.New("Invalid settings.")
	}
	c := prev
	str := func(k string) (string, bool) {
		var s string
		if v, ok := in[k]; ok && json.Unmarshal(v, &s) == nil {
			return s, true
		}
		return "", false
	}
	boolean := func(k string) (bool, bool) {
		var b bool
		if v, ok := in[k]; ok && json.Unmarshal(v, &b) == nil {
			return b, true
		}
		return false, false
	}
	integer := func(k string) (int, bool) {
		var f float64
		if v, ok := in[k]; ok && json.Unmarshal(v, &f) == nil && f == float64(int(f)) {
			return int(f), true
		}
		return 0, false
	}
	limit := prev.TokenLimit
	if _, ok := in["tokenLimit"]; ok {
		l, ok := integer("tokenLimit")
		if !ok {
			return prev, errors.New("Token cycle limit must be a whole number from 1 to 999999.")
		}
		limit = l
	}
	if limit < 1 || limit > 999999 {
		return prev, errors.New("Token cycle limit must be a whole number from 1 to 999999.")
	}
	c.TokenLimit = limit
	mode, _ := str("outputMode")
	if mode != "windows" && mode != "lan" {
		return prev, errors.New("Choose an output method.")
	}
	name, ok := str("printerName")
	if !ok || len(name) > 200 {
		return prev, errors.New("Select an installed printer.")
	}
	ip, ok := str("printerIp")
	if !ok || (ip != "" && (!isLanIP(ip) || isOwnIP(ip))) {
		return prev, errors.New("Enter the physical printer’s private LAN IPv4 address, not this computer’s address.")
	}
	port, ok := integer("printerPort")
	if !ok || port < 1 || port > 65535 {
		return prev, errors.New("Printer port must be between 1 and 65535.")
	}
	heading, ok1 := str("heading")
	label, ok2 := str("tokenLabel")
	if !ok1 || !ok2 || !reASCII32.MatchString(heading) || !reASCII32.MatchString(label) {
		return prev, errors.New("Heading and token label must use up to 32 English letters, numbers or punctuation.")
	}
	ap, ok1 := boolean("autoPrint")
	te, ok2 := boolean("tokenEnabled")
	if !ok1 || !ok2 {
		return prev, errors.New("Invalid print setting.")
	}
	for _, k := range []string{"autoSource", "showOrderId", "kdsEnabled"} {
		if _, has := in[k]; has {
			if _, ok := boolean(k); !ok {
				return prev, errors.New("Invalid receipt setting.")
			}
		}
	}
	if v, ok := boolean("autoSource"); ok {
		c.AutoSource = v
	}
	if v, ok := boolean("showOrderId"); ok {
		c.ShowOrderID = v
	}
	if v, ok := boolean("kdsEnabled"); ok {
		c.KdsEnabled = v
	}
	for _, k := range []string{"petpoojaIp", "androidIp"} {
		v, has := str(k)
		if !has {
			continue
		}
		v = strings.TrimSpace(v)
		if v != "" && !isLanIP(v) {
			return prev, errors.New("Manual device addresses must be private IPv4 addresses.")
		}
		if k == "petpoojaIp" {
			c.PetpoojaIP = v
		} else {
			c.AndroidIP = v
		}
	}
	if v, has := str("petpoojaSyncCode"); has {
		v = strings.TrimSpace(v)
		if len(v) > 80 {
			return prev, errors.New("Petpooja sync code is too long.")
		}
		c.PetpoojaSyncCode = v
	}
	c.OutputMode, c.PrinterName, c.PrinterIP, c.PrinterPort, c.Heading, c.TokenLabel, c.AutoPrint, c.TokenEnabled = mode, name, ip, port, heading, label, ap, te
	return c, nil
}

func isLanIP(s string) bool {
	ip := net.ParseIP(strings.TrimSpace(s)).To4()
	if ip == nil {
		return false
	}
	return ip[0] == 10 || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) || (ip[0] == 192 && ip[1] == 168)
}

func isOwnIP(s string) bool {
	for _, a := range localIPv4s() {
		if a == s {
			return true
		}
	}
	return false
}

func serveOldTokenApk(w http.ResponseWriter, r *http.Request) {
	old := oldBridgeDir()
	if old != "" {
		for _, n := range []string{"Petpooja Token Bridge.apk", "Petpooja-Token-Bridge-portable-aligned-debugSigned.apk"} {
			p := filepath.Join(filepath.Dir(old), "release", n)
			if st, err := os.Stat(p); err == nil {
				w.Header().Set("Content-Type", "application/vnd.android.package-archive")
				w.Header().Set("Content-Disposition", `attachment; filename="Petpooja-Token-Bridge.apk"`)
				w.Header().Set("Content-Length", fmt.Sprint(st.Size()))
				http.ServeFile(w, r, p)
				return
			}
		}
	}
	bjson(w, 404, map[string]string{"error": "Android app is not included in this installation."})
}

var reMobileOrder = regexp.MustCompile(`^[A-Za-z0-9-]{2,40}$`)

func alnumLower(s string) string {
	var b strings.Builder
	for _, c := range strings.ToLower(s) {
		if (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') {
			b.WriteRune(c)
		}
	}
	return b.String()
}

// handleMobileToken: the Android Token app asks for the token of an order (TCP 8788).
func handleMobileToken(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Cache-Control", "no-store")
	if r.Method != http.MethodGet || r.URL.Path != "/api/mobile/token" {
		bjson(w, 404, map[string]string{"error": "Not found."})
		return
	}
	if r.Header.Get("X-Bridge-Key") != bridgeMobileKey {
		bjson(w, 403, map[string]string{"error": "Not authorized."})
		return
	}
	orderID := strings.TrimSpace(r.URL.Query().Get("orderId"))
	if !reMobileOrder.MatchString(orderID) {
		bjson(w, 400, map[string]string{"error": "Invalid order ID."})
		return
	}
	want := alnumLower(orderID)
	same := func(v string) bool {
		c := alnumLower(v)
		return c != "" && (c == want || (len(c) >= 4 && len(want) >= 4 && (strings.HasSuffix(c, want) || strings.HasSuffix(want, c))))
	}
	client, _, _ := net.SplitHostPort(r.RemoteAddr)
	mobileMu.Lock()
	mobileAct["requests"] = mobileAct["requests"].(int) + 1
	mobileAct["lastRequestAt"], mobileAct["lastOrderId"], mobileAct["lastClient"] = nowISO(), orderID, client
	mobileMu.Unlock()
	prMu.Lock()
	token := 0
	for i := len(prJobs) - 1; i >= 0 && token == 0; i-- {
		j := prJobs[i]
		var ids []string
		if j.Metadata != nil {
			ids = append(ids, j.Metadata.OrderID)
		}
		if j.Petpooja != nil {
			ids = append(ids, j.Petpooja.OrderID)
		}
		for _, id := range ids {
			if same(id) {
				token = j.Token
				break
			}
		}
	}
	prMu.Unlock()
	mobileMu.Lock()
	defer mobileMu.Unlock()
	if token > 0 {
		mobileAct["matches"] = mobileAct["matches"].(int) + 1
		mobileAct["lastMatchedToken"] = token
		bjson(w, 200, map[string]string{"token": fmt.Sprint(token)})
		return
	}
	mobileAct["lastMatchedToken"] = nil
	bjson(w, 404, map[string]string{"error": "Token not ready."})
}
