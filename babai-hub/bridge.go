package main

// Pickup slots from Bridge Print (the PC that prints the KOT slips; its rider screen reads a small
// JSON "state" endpoint, e.g. http://192.168.1.3:8787/state). Same reading rules as the KDS app's
// BridgeSlots: any object that has an order-ID-like field and a token / slot field.
//
// Address: typed on the hub page, or found by itself on the Petpooja PC (port 8787).

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	bridgePort     = 8787
	bridgeEvery    = 3 * time.Second
	bridgeFindWait = 2 * time.Minute
)

var bridgePaths = []string{"/state", "/api/state", "/display/state", "/api/display/state"}

var (
	brMu      sync.Mutex
	brSlots   = map[string]string{} // order digits -> slot
	brOKAt    time.Time
	brErr     string
	brPath    string
	brAuto    string // address found by itself
	brNote    string
	brTriedAt time.Time
	brClient  = &http.Client{Timeout: 4 * time.Second}
)

func bridgeAddr() string {
	if a := normAddr(getConfig().Bridge, bridgePort); a != "" {
		return a
	}
	brMu.Lock()
	defer brMu.Unlock()
	return brAuto
}

// normAddr: "http://192.168.1.3:8787/state" -> "192.168.1.3:8787"; the port is added when missing.
func normAddr(s string, port int) string {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(strings.TrimPrefix(s, "http://"), "https://")
	if i := strings.IndexAny(s, "/?#"); i >= 0 {
		s = s[:i]
	}
	if s == "" {
		return ""
	}
	if _, _, err := net.SplitHostPort(s); err != nil {
		s = net.JoinHostPort(s, strconv.Itoa(port))
	}
	return s
}

func bridgeLoop() {
	for {
		if !getConfig().Paused {
			addr := bridgeAddr()
			if addr == "" {
				bridgeFind()
			} else if err := bridgePoll(addr); err != nil {
				brMu.Lock()
				brErr = err.Error()
				brMu.Unlock()
			}
		}
		time.Sleep(bridgeEvery)
	}
}

// bridgeFind looks for Bridge Print on the Petpooja PC, every 2 minutes until found.
func bridgeFind() {
	brMu.Lock()
	due := time.Since(brTriedAt) >= bridgeFindWait
	if due {
		brTriedAt = time.Now()
	}
	brMu.Unlock()
	srv := getConfig().Server
	if !due || srv == "" {
		return
	}
	addr := net.JoinHostPort(hostOnly(srv), strconv.Itoa(bridgePort))
	if err := bridgePoll(addr); err == nil {
		brMu.Lock()
		brAuto, brNote = addr, "Found by itself on the Petpooja PC ("+addr+")"
		brMu.Unlock()
		return
	}
	brMu.Lock()
	brNote = "Not found on the Petpooja PC " + hostOnly(srv) + " (port 8787) · checking every 2 min"
	brMu.Unlock()
}

func bridgePoll(addr string) error {
	brMu.Lock()
	paths := bridgePaths
	if brPath != "" {
		paths = []string{brPath}
	}
	brMu.Unlock()
	var last error
	for _, p := range paths {
		body, err := httpGetText("http://" + addr + p)
		if err != nil {
			last = err
			continue
		}
		var root any
		if json.Unmarshal(body, &root) != nil {
			last = fmt.Errorf("%s: not JSON", p)
			continue
		}
		m := map[string]string{}
		bridgeWalk(root, m, 0)
		brMu.Lock()
		brSlots, brOKAt, brErr, brPath = m, time.Now(), "", p
		brMu.Unlock()
		return nil
	}
	brMu.Lock()
	brPath = ""
	brMu.Unlock()
	if last == nil {
		last = fmt.Errorf("no answer")
	}
	return last
}

func httpGetText(u string) ([]byte, error) {
	resp, err := brClient.Get(u)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 2<<20))
}

func bridgeWalk(node any, out map[string]string, depth int) {
	if depth > 12 || node == nil {
		return
	}
	switch v := node.(type) {
	case []any:
		for _, x := range v {
			bridgeWalk(x, out, depth+1)
		}
	case map[string]any:
		order, token := bridgeRead(v, false, "", "")
		for _, x := range v {
			if c, ok := x.(map[string]any); ok {
				order, token = bridgeRead(c, true, order, token) // one level down
			}
		}
		if order != "" && token != "" {
			if _, ok := out[order]; !ok {
				out[order] = token
			}
			return
		}
		for _, x := range v {
			switch x.(type) {
			case map[string]any, []any:
				bridgeWalk(x, out, depth+1)
			}
		}
	}
}

func bridgeRead(o map[string]any, child bool, order, token string) (string, string) {
	for k, v := range o {
		var sv string
		switch t := v.(type) {
		case string:
			sv = strings.TrimSpace(t)
		case float64:
			sv = strconv.FormatFloat(t, 'f', -1, 64)
		default:
			continue
		}
		lk := strings.NewReplacer("_", "", "-", "").Replace(strings.ToLower(k))
		d := digitsOnly(sv)
		if order == "" && (strings.Contains(lk, "orderid") || lk == "order" || strings.Contains(lk, "orderno") ||
			strings.Contains(lk, "orderuid") || strings.Contains(lk, "poid") || (child && lk == "id")) && len(d) >= 6 {
			order = d
		} else if token == "" && (strings.Contains(lk, "token") || strings.Contains(lk, "slot")) && d != "" && len(d) <= 6 {
			n, _ := strconv.Atoi(d)
			token = strconv.Itoa(n)
		}
	}
	return order, token
}

// slotFor: the Bridge Print slot of a platform order ID ("" when unknown).
func slotFor(orderID string) string {
	d := digitsOnly(orderID)
	if len(d) < 4 {
		return ""
	}
	brMu.Lock()
	defer brMu.Unlock()
	if time.Since(brOKAt) > time.Minute {
		return ""
	}
	if s, ok := brSlots[d]; ok {
		return s
	}
	for k, s := range brSlots { // same order written slightly differently: last 8+ digits
		n := min(len(k), len(d))
		if n >= 8 && (strings.HasSuffix(k, d) || strings.HasSuffix(d, k)) {
			return s
		}
	}
	return ""
}

func bridgeStatus() map[string]any {
	brMu.Lock()
	defer brMu.Unlock()
	addr := normAddr(getConfig().Bridge, bridgePort)
	auto := addr == ""
	if auto {
		addr = brAuto
	}
	st := map[string]any{"addr": addr, "auto": auto, "note": brNote, "slots": len(brSlots)}
	switch {
	case addr == "":
		st["state"] = "not found yet"
	case !brOKAt.IsZero() && time.Since(brOKAt) < 20*time.Second:
		st["state"] = fmt.Sprintf("connected · %d orders with a slot", len(brSlots))
		st["ok"] = true
	case brErr != "":
		st["state"] = "can't read it: " + brErr
	default:
		st["state"] = "connecting…"
	}
	return st
}

// handleBridge: POST {addr} saves the Bridge Print address ("" = find it on the Petpooja PC) and tests it.
func handleBridge(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Addr string }
	if json.NewDecoder(io.LimitReader(r.Body, 2048)).Decode(&in) != nil {
		reply(w, false, "Couldn't read the form.")
		return
	}
	addr := normAddr(in.Addr, bridgePort)
	c := getConfig()
	c.Bridge = addr
	if err := saveConfig(c); err != nil {
		reply(w, false, "Couldn't save: "+err.Error())
		return
	}
	brMu.Lock()
	brPath, brSlots, brOKAt, brTriedAt, brAuto = "", map[string]string{}, time.Time{}, time.Time{}, ""
	brMu.Unlock()
	if addr == "" {
		reply(w, true, "Saved. The hub looks for Bridge Print on the Petpooja PC by itself.")
		return
	}
	if err := bridgePoll(addr); err != nil {
		reply(w, false, "Saved, but Bridge Print doesn't answer at "+addr+": "+err.Error())
		return
	}
	brMu.Lock()
	n := len(brSlots)
	brMu.Unlock()
	reply(w, true, fmt.Sprintf("Connected to Bridge Print: %d orders with a slot right now.", n))
}
