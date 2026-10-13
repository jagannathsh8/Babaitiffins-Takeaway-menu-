package main

// Marks online / delivery orders Food Ready on the Petpooja billing PC from a scanned KOT barcode
// (Babai Scanner phones, over the shop Wi-Fi or the internet). It is the only thing the hub ever
// changes in Petpooja, and it sends exactly what the Petpooja KDS app sends when its own scanner
// marks an order ready (DashboardViewModel.markKotFoodReady):
//
//	POST petpooja_server/change_kot_status
//	{kot_id, kot_unique_id, kot_item_id: [item_id of every item not yet ready], kot_item_status: "9",
//	 oh_status: <kot status>, order_type, sync_code, user, clientIdentifier, intra_server_version,
//	 item_food_ready_print: "0"}
//
// Matching is the KDS scanner's rule (the barcode's last 4 digits = the end of the platform order
// ID), made safer: when more digits match, only the longest match counts.

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"strings"
	"sync"
	"time"
)

const (
	scanFreshFor = 30 * time.Second // Petpooja list older than this: don't mark from it
	scanDupFor   = 8 * time.Second  // the same code again within this: same answer, nothing sent
	scanLogMax   = 300
)

// ScanResult is what the phone shows.
type ScanResult struct {
	OK      bool   `json:"ok"`
	Already bool   `json:"already,omitempty"` // was already Food Ready
	N       int    `json:"n"`                 // KOTs marked now
	Msg     string `json:"m"`
	Brand   string `json:"b,omitempty"`
	Order   string `json:"o,omitempty"` // full platform order ID
	Last4   string `json:"l,omitempty"`
	Slot    string `json:"s,omitempty"`
	At      int64  `json:"t"`
	Code    string `json:"-"`
	Device  string `json:"-"`
	Route   string `json:"-"` // "wifi" / "internet"
}

type scanLogEntry struct {
	At     int64  `json:"t"`
	Device string `json:"d"`
	Route  string `json:"r"`
	Code   string `json:"c"`
	OK     bool   `json:"ok"`
	Msg    string `json:"m"`
}

var (
	scanMu     sync.Mutex // one marking at a time
	scanByID   = map[string]ScanResult{}
	scanIDAt   = map[string]time.Time{}
	lastCode   string
	lastCodeAt time.Time
	lastRes    ScanResult
	scanLogMu  sync.Mutex
	scanLog    []scanLogEntry
)

func digitsOnly(s string) string {
	var b strings.Builder
	for _, c := range s {
		if c >= '0' && c <= '9' {
			b.WriteRune(c)
		}
	}
	return b.String()
}

// matchKots: open KOTs whose platform order ID ends with the most digits of the code (4 at least).
func matchKots(kots []Kot, code string) ([]Kot, int) {
	d := digitsOnly(code)
	if len(d) < 4 {
		return nil, 0
	}
	best, bestN := []Kot(nil), 0
	for _, k := range kots {
		if k.Status == "0" || k.Status == "10" {
			continue
		}
		p := digitsOnly(k.POID)
		if len(p) < 4 {
			continue
		}
		n := 0
		for n < len(p) && n < len(d) && p[len(p)-1-n] == d[len(d)-1-n] {
			n++
		}
		if n < 4 {
			continue
		}
		switch {
		case n > bestN:
			best, bestN = []Kot{k}, n
		case n == bestN:
			best = append(best, k)
		}
	}
	return best, bestN
}

// itemsToMark: what the KDS app sends: items not yet ready / dispatched (and not cancelled).
func itemsToMark(k Kot) []int64 {
	var ids []int64
	for _, it := range k.Items {
		if it.S == 9 || it.S == 10 || it.S == -9 || it.ID <= 0 {
			continue
		}
		ids = append(ids, it.ID)
	}
	return ids
}

func kotReady(k Kot) bool {
	if k.Status == "9" || k.Status == "10" {
		return true
	}
	return len(itemsToMark(k)) == 0
}

// markFromScan handles one scan (Wi-Fi or internet). id: the phone's own scan id (a scan sent on
// both routes is marked once).
func markFromScan(id, code, device, route string) ScanResult {
	scanMu.Lock()
	defer scanMu.Unlock()
	now := time.Now()
	for k, t := range scanIDAt {
		if now.Sub(t) > 2*time.Hour {
			delete(scanIDAt, k)
			delete(scanByID, k)
		}
	}
	if id != "" {
		if r, ok := scanByID[id]; ok {
			return r
		}
	}
	d := digitsOnly(code)
	var res ScanResult
	if d == lastCode && now.Sub(lastCodeAt) < scanDupFor {
		res = lastRes
	} else {
		res = markCode(code, now)
		lastCode, lastCodeAt, lastRes = d, now, res
	}
	res.At, res.Code, res.Device, res.Route = now.UnixMilli(), code, device, route
	if id != "" {
		scanByID[id], scanIDAt[id] = res, now
	}
	addScanLog(res)
	return res
}

func markCode(code string, now time.Time) ScanResult {
	d := digitsOnly(code)
	if len(d) < 4 {
		return ScanResult{Msg: "Barcode must contain at least 4 digits"}
	}
	tail := d[len(d)-4:]
	c := getConfig()
	if c.Server == "" {
		return ScanResult{Msg: "Hub: Petpooja PC not set up", Last4: tail}
	}
	if c.Paused {
		return ScanResult{Msg: "Hub is paused", Last4: tail}
	}
	ppMu.Lock()
	kots, okAt, code0 := ppKots, ppOKAt, syncCode
	ppMu.Unlock()
	if okAt.IsZero() || now.Sub(okAt) > scanFreshFor || code0 == "" {
		return ScanResult{Msg: "Hub can't reach Petpooja right now", Last4: tail}
	}
	matched, _ := matchKots(kots, d)
	if len(matched) == 0 {
		return ScanResult{Msg: "No preparing order ending " + tail, Last4: tail}
	}
	first := matched[0]
	res := ScanResult{Brand: brandOf(first), Order: first.POID, Last4: last4Of(first.POID), Slot: slotFor(first.POID)}
	base := serverBase(c.Server)
	marked, already := 0, 0
	var lastErr error
	for _, k := range matched {
		if kotReady(k) {
			already++
			continue
		}
		if err := sendFoodReady(base, code0, k); err != nil {
			lastErr = err
			log.Printf("scan: marking KOT %d failed: %v", k.ID, err)
			continue
		}
		marked++
		noteMarkedReady(k.ID, now)
	}
	switch {
	case marked > 0:
		res.OK, res.N = true, marked
		res.Msg = "FOOD READY"
		wakePoll() // everyone sees it on the next read
	case already > 0 && lastErr == nil:
		res.OK, res.Already = true, true
		res.Msg = "Already Food Ready"
	case lastErr != nil:
		res.Msg = "Petpooja didn't accept it: " + lastErr.Error()
	}
	return res
}

func sendFoodReady(base, code string, k Kot) error {
	req := map[string]any{
		"sync_code":             code,
		"kot_id":                k.ID,
		"kot_item_id":           itemsToMark(k),
		"kot_item_status":       "9",
		"oh_status":             k.Status,
		"order_type":            k.OrderType,
		"user":                  userJSON,
		"clientIdentifier":      clientID,
		"intra_server_version":  intraVersion,
		"item_food_ready_print": "0",
	}
	if k.Token > 0 {
		req["kot_unique_id"] = k.Token
	}
	var res struct {
		Message string          `json:"message"`
		Error   json.RawMessage `json:"error"`
	}
	if _, err := ppPost(base+"petpooja_server/change_kot_status", req, &res); err != nil {
		return err
	}
	if truthy(res.Error) {
		msg := strings.TrimSpace(res.Message)
		if msg == "" {
			msg = "error " + string(res.Error)
		}
		return errors.New(msg)
	}
	return nil
}

func brandOf(k Kot) string {
	if k.Online && k.Platform != "" && k.Platform != "Online" {
		return k.Platform
	}
	if k.Channel != "" {
		return k.Channel
	}
	if k.OrderType == 1 {
		return "Delivery"
	}
	return "Online"
}

func last4Of(s string) string {
	d := digitsOnly(s)
	if len(d) > 4 {
		return d[len(d)-4:]
	}
	return d
}

func addScanLog(r ScanResult) {
	scanLogMu.Lock()
	defer scanLogMu.Unlock()
	msg := r.Msg
	if r.OK && r.Brand != "" {
		msg = fmt.Sprintf("%s · %s …%s", r.Msg, r.Brand, r.Last4)
	}
	scanLog = append(scanLog, scanLogEntry{At: r.At, Device: r.Device, Route: r.Route, Code: r.Code, OK: r.OK, Msg: msg})
	if len(scanLog) > scanLogMax {
		scanLog = scanLog[len(scanLog)-scanLogMax:]
	}
}

func scanLogSnapshot() []scanLogEntry {
	scanLogMu.Lock()
	defer scanLogMu.Unlock()
	out := make([]scanLogEntry, len(scanLog))
	for i := range scanLog {
		out[len(scanLog)-1-i] = scanLog[i] // newest first
	}
	return out
}
