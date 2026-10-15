package main

// Pickup slots from Bridge Print (the PC that prints the KOT slips; its rider screen reads a small
// JSON "state" endpoint, e.g. http://192.168.1.3:8787/state). Same reading rules as the KDS app's
// BridgeSlots: any object that has an order-ID-like field and a token / slot field.
//
// Address: typed on the hub page, or found by itself on the Petpooja PC (port 8787).

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"math"
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

var bridgePaths = []string{"/state", "/api/state", "/display/state", "/api/display/state", "/api/orders", "/orders", "/api/tokens", "/api/display"}

var (
	brMu       sync.Mutex
	brSlots    = map[string]string{} // order digits -> slot
	brOKAt     time.Time
	brErr      string
	brPath     string
	brAuto     string // address found by itself
	brNote     string
	brTriedAt  time.Time
	brRaw      []byte // last answers, for "find an order" on the hub page
	brPathsAll []string
	brSeen     = map[string]brSeenSlot{} // every slot seen, kept 4 h: Bridge Print's list only holds recent print jobs
	brProbeAt  time.Time
	brClient   = &http.Client{Timeout: 4 * time.Second}
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
	// Read every Bridge Print page that answers (settings + print jobs on one, the slot board on
	// another) and combine the slots. Pages are looked for again every 2 minutes.
	brMu.Lock()
	paths := brPathsAll
	if len(paths) == 0 || time.Since(brProbeAt) > 2*time.Minute {
		paths = bridgePaths
		brProbeAt = time.Now()
	}
	brMu.Unlock()
	var last error
	m := map[string]string{}
	var ok []string
	var raw bytes.Buffer
	for _, p := range paths {
		body, err := httpGetText("http://" + addr + p)
		if err != nil {
			last = err
			continue
		}
		var root any
		// UseNumber: order IDs like Ownly's 1791976083241811062 (19 digits) lose their last digits
		// as float64, so they never matched an order and showed no slot.
		dec := json.NewDecoder(bytes.NewReader(body))
		dec.UseNumber()
		if dec.Decode(&root) != nil {
			last = fmt.Errorf("%s: not JSON", p)
			continue
		}
		bridgeWalk(root, m, 0)
		ok = append(ok, p)
		raw.WriteString("### " + p + "\n")
		raw.Write(body)
		raw.WriteString("\n")
	}
	if len(ok) > 0 {
		brMu.Lock()
		now := time.Now()
		for o, s := range m {
			brSeen[o] = brSeenSlot{s, now}
		}
		for o, x := range brSeen {
			if now.Sub(x.at) > brKeep {
				delete(brSeen, o)
			}
		}
		brSlots, brOKAt, brErr, brPath, brPathsAll, brRaw = m, now, "", ok[0], ok, raw.Bytes()
		brMu.Unlock()
		return nil
	}
	brMu.Lock()
	brPath, brPathsAll = "", nil
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
		if order == "" || token == "" {
			// order number or token deeper inside (e.g. job → payload → order → id): take it when
			// this part holds exactly one order and one token, so a list is never merged
			orders, tokens := map[string]bool{}, map[string]bool{}
			bridgeCollect(v, false, orders, tokens, 0)
			if len(orders) == 1 && len(tokens) == 1 {
				for o := range orders {
					order = o
				}
				for t := range tokens {
					token = t
				}
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
		case json.Number:
			sv = t.String()
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
		} else if token == "" && bridgeTokenKey(lk) && d != "" && len(d) <= 6 {
			n, _ := strconv.Atoi(d)
			token = strconv.Itoa(n)
		}
	}
	return order, token
}

// bridgeCollect: every order number and token in a part of Bridge Print's answer (4 levels).
func bridgeCollect(node any, child bool, orders, tokens map[string]bool, depth int) {
	if depth > 4 || len(orders) > 1 {
		return
	}
	switch v := node.(type) {
	case []any:
		for _, x := range v {
			bridgeCollect(x, true, orders, tokens, depth+1)
		}
	case map[string]any:
		o, t := bridgeRead(v, child, "", "")
		if o != "" {
			orders[o] = true
		}
		if t != "" {
			tokens[t] = true
		}
		for _, x := range v {
			switch x.(type) {
			case map[string]any, []any:
				bridgeCollect(x, true, orders, tokens, depth+1)
			}
		}
	}
}

// bridgeTokenKey: a field holding the pickup token / slot (not tokenLimit, tokenLabel, …).
func bridgeTokenKey(lk string) bool {
	if !strings.Contains(lk, "token") && !strings.Contains(lk, "slot") {
		return false
	}
	for _, bad := range []string{"limit", "label", "enabled", "count", "max", "min", "size", "total", "csrf"} {
		if strings.Contains(lk, bad) {
			return false
		}
	}
	return true
}

type brSeenSlot struct {
	slot string
	at   time.Time
}

const brKeep = 4 * time.Hour

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
	if x, ok := brSeen[d]; ok {
		return x.slot // printed earlier; its job has left Bridge Print's list since
	}
	for k, x := range brSeen { // same order written slightly differently: last 8+ digits
		n := min(len(k), len(d))
		if n >= 8 && (strings.HasSuffix(k, d) || strings.HasSuffix(d, k)) {
			return x.slot
		}
	}
	return roundedMatch(d)
}

// roundedMatch: Bridge Print (JavaScript) keeps numbers to ~16 digits, so a 19-digit Ownly order
// 1791982601318835770 comes back as e.g. 1791982601318835700. For long numbers, the order whose
// number is within that rounding (and nearest) is the same order.
func roundedMatch(d string) string {
	if len(d) < 17 || len(d) > 19 {
		return ""
	}
	want, err := strconv.ParseFloat(d, 64)
	if err != nil {
		return ""
	}
	best, bestGap := "", 2048.0
	for k, x := range brSeen {
		if len(k) != len(d) {
			continue
		}
		v, err := strconv.ParseFloat(k, 64)
		if err != nil {
			continue
		}
		if gap := math.Abs(v - want); gap <= bestGap {
			best, bestGap = x.slot, gap
		}
	}
	return best
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

// handleBridgeFind (hub page): where an order number appears in Bridge Print's last answers. With
// only the last digits typed, the full number is taken from Petpooja's open orders; long (Ownly)
// numbers are also looked for by their first 15 digits, which survive Bridge Print's rounding.
func handleBridgeFind(w http.ResponseWriter, r *http.Request) {
	q := digitsOnly(r.URL.Query().Get("q"))
	full := q
	if len(q) >= 4 && len(q) < 17 {
		ppMu.Lock()
		for _, k := range ppKots {
			if p := digitsOnly(k.POID); len(p) > len(q) && strings.HasSuffix(p, q) {
				full = p
				break
			}
		}
		ppMu.Unlock()
	}
	var b strings.Builder
	if len(q) < 4 {
		b.WriteString("Type the order number (at least the last 4 digits).")
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = io.WriteString(w, b.String())
		return
	}
	if full != q {
		b.WriteString("Petpooja order: " + full + "\n")
	}
	brMu.Lock()
	raw := string(brRaw)
	slot := ""
	if x, ok := brSeen[full]; ok {
		slot = "slot " + x.slot + " (seen " + x.at.Format("15:04") + ")"
	} else if s := roundedMatch(full); s != "" {
		slot = "slot " + s + " (Bridge Print has this number rounded)"
	}
	brMu.Unlock()
	if slot != "" {
		b.WriteString("Matched: " + slot + "\n\n")
	} else {
		b.WriteString("Not matched to a slot.\n\n")
	}
	if raw == "" {
		b.WriteString("Nothing received from Bridge Print yet.")
	} else {
		keys := []string{full}
		if len(full) >= 17 {
			keys = append(keys, full[:15]) // the rounded form keeps the first 15 digits
		}
		shown := 0
		for _, key := range keys {
			for i := 0; shown < 4; {
				j := strings.Index(raw[i:], key)
				if j < 0 {
					break
				}
				at := i + j
				from, to := max(0, at-400), min(len(raw), at+len(full)+200)
				b.WriteString("…" + raw[from:to] + "…\n\n")
				shown++
				i = at + len(full)
			}
			if shown > 0 {
				break
			}
		}
		if shown == 0 {
			if slot != "" {
				b.WriteString("Its print job has left Bridge Print's list; the hub keeps the slot it saw.")
			} else {
				b.WriteString("Bridge Print's data has no number like this now (not even rounded), and the hub hasn't seen a slot for it: Bridge Print hasn't given this order a token.")
			}
		}
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	_, _ = io.WriteString(w, b.String())
}
