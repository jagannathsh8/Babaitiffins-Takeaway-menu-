package main

// Babai Scanner phones: pairing, and the two routes a scan takes to this hub.
//
//  1. Shop Wi-Fi (fastest):  POST http://<hub PC>:8790/scan  {"k": key, "id": scan id, "c": code, "d": device}
//  2. Internet (anywhere):   the phone writes /babai/o/<outlet>/sq/<key>/<scan id> = {c, t, d} in Firebase;
//     the hub listens live, marks the order and answers in /babai/o/<outlet>/sr/<key>/<scan id>.
//
// A phone sends a scan on Wi-Fi first and falls back to the internet; the scan id makes sure an
// order is marked once even when both arrive. The pairing key (in the QR code on this page) is
// the phone's only credential: Firebase only lets a key that is listed in
// /babai/o/<outlet>/pk/<key> (private, written by the hub) write scans or read answers, and the
// database secret never leaves this PC. Removing a phone on the page cuts it off at once.

import (
	"bufio"
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/json"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	scanQueueMaxAge = 10 * time.Minute // older scans in the queue are dropped, not marked
	answerKeep      = 15 * time.Minute
	pairKeyLen      = 24
)

var (
	phoneMu    sync.Mutex
	phoneSeen  = map[string]time.Time{} // key -> last scan / hello
	phoneRoute = map[string]string{}
	phoneHits  = map[string][]time.Time{}
	pkSynced   string // phones list last written to Firebase
	pkTryAt    time.Time
	sqWake     = make(chan struct{}, 1)
	sqLive     bool // the live Firebase connection is open
	answers    = map[string]time.Time{} // "key/id" -> written
)

func newPairKey() string {
	const abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
	b := make([]byte, pairKeyLen)
	for i := range b {
		n, _ := rand.Int(rand.Reader, big.NewInt(int64(len(abc))))
		b[i] = abc[n.Int64()]
	}
	return string(b)
}

func validKey(k string) bool {
	if len(k) != pairKeyLen {
		return false
	}
	for _, c := range k {
		if !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
			return false
		}
	}
	return true
}

// phoneByKey: the paired phone with this key (constant-time compare).
func phoneByKey(k string) (Phone, bool) {
	if !validKey(k) {
		return Phone{}, false
	}
	for _, p := range getConfig().Phones {
		if subtle.ConstantTimeCompare([]byte(p.Key), []byte(k)) == 1 {
			return p, true
		}
	}
	return Phone{}, false
}

// tooFast: more than 12 scans in 3 seconds from one phone (a stuck scanner / abuse).
func tooFast(k string, now time.Time) bool {
	phoneMu.Lock()
	defer phoneMu.Unlock()
	h := phoneHits[k][:0]
	for _, t := range phoneHits[k] {
		if now.Sub(t) < 3*time.Second {
			h = append(h, t)
		}
	}
	if len(h) >= 12 {
		phoneHits[k] = h
		return true
	}
	phoneHits[k] = append(h, now)
	return false
}

func notePhone(k, route string) {
	phoneMu.Lock()
	phoneSeen[k], phoneRoute[k] = time.Now(), route
	phoneMu.Unlock()
}

// ---- route 1: shop Wi-Fi ------------------------------------------------------------------

// handleScan: POST /scan (a scan) and GET /scan?k= (the phone checks the Wi-Fi route).
func handleScan(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	if r.Method == http.MethodGet {
		p, ok := phoneByKey(r.URL.Query().Get("k"))
		if !ok {
			w.WriteHeader(http.StatusForbidden)
			io.WriteString(w, `{"ok":false,"m":"This phone is not paired with this hub"}`)
			return
		}
		notePhone(p.Key, "wifi")
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "outlet": getConfig().Outlet, "name": p.Name, "hub": version})
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "POST", http.StatusMethodNotAllowed)
		return
	}
	var in struct {
		K, ID, C, D string
	}
	if json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in) != nil {
		http.Error(w, `{"ok":false,"m":"bad request"}`, http.StatusBadRequest)
		return
	}
	p, ok := phoneByKey(in.K)
	if !ok {
		w.WriteHeader(http.StatusForbidden)
		io.WriteString(w, `{"ok":false,"m":"This phone is not paired with this hub"}`)
		return
	}
	if tooFast(p.Key, time.Now()) {
		w.WriteHeader(http.StatusTooManyRequests)
		io.WriteString(w, `{"ok":false,"m":"Too many scans at once, wait a moment"}`)
		return
	}
	notePhone(p.Key, "wifi")
	res := markFromScan(cleanID(in.ID), cleanCode(in.C), deviceName(p, in.D), "wifi")
	_ = json.NewEncoder(w).Encode(res)
}

func cleanID(s string) string {
	var b strings.Builder
	for _, c := range s {
		if (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' {
			b.WriteRune(c)
		}
	}
	if b.Len() > 40 {
		return b.String()[:40]
	}
	return b.String()
}

func cleanCode(s string) string {
	s = strings.TrimSpace(s)
	if len(s) > 80 {
		s = s[:80]
	}
	return s
}

func deviceName(p Phone, d string) string {
	if p.Name != "" {
		return p.Name
	}
	return cleanCode(d)
}

// ---- route 2: internet (Firebase) ---------------------------------------------------------

// scanQueueLoop keeps a live Firebase connection on this outlet's scan queue (and checks it every
// 15 s in case that connection is quietly broken).
func scanQueueLoop() {
	go func() {
		for {
			if cloudReady() && len(getConfig().Phones) > 0 && !getConfig().Paused {
				listenQueue()
			}
			time.Sleep(5 * time.Second)
		}
	}()
	tick := time.NewTicker(15 * time.Second)
	defer tick.Stop()
	for {
		select {
		case <-tick.C:
		case <-sqWake:
		}
		if !cloudReady() || getConfig().Paused {
			continue
		}
		syncPairKeys(time.Now())
		if len(getConfig().Phones) > 0 {
			processQueue()
		}
		cleanAnswers()
	}
}

func wakeQueue() {
	select {
	case sqWake <- struct{}{}:
	default:
	}
}

// listenQueue: Firebase streaming (server-sent events) until it breaks or goes quiet for 90 s.
func listenQueue() {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, fbURL(outletBase()+"/sq", ""), nil)
	if err != nil {
		return
	}
	req.Header.Set("Accept", "text/event-stream")
	resp, err := (&http.Client{}).Do(req)
	if err != nil {
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		time.Sleep(30 * time.Second)
		return
	}
	phoneMu.Lock()
	sqLive = true
	phoneMu.Unlock()
	defer func() {
		phoneMu.Lock()
		sqLive = false
		phoneMu.Unlock()
	}()
	quiet := time.AfterFunc(90*time.Second, cancel) // Firebase sends keep-alive every ~30 s
	defer quiet.Stop()
	sc := bufio.NewScanner(resp.Body)
	sc.Buffer(make([]byte, 64<<10), 4<<20)
	ev := ""
	for sc.Scan() {
		quiet.Reset(90 * time.Second)
		line := sc.Text()
		switch {
		case strings.HasPrefix(line, "event:"):
			ev = strings.TrimSpace(line[6:])
			if ev == "cancel" || ev == "auth_revoked" {
				return
			}
		case strings.HasPrefix(line, "data:"):
			if ev == "put" || ev == "patch" {
				if d := strings.TrimSpace(line[5:]); !strings.Contains(d, `"data":null`) {
					wakeQueue()
				}
			}
		}
		if len(getConfig().Phones) == 0 || !cloudReady() {
			return
		}
	}
}

type queuedScan struct {
	C string  `json:"c"`
	T float64 `json:"t"`
	D string  `json:"d"`
}

// processQueue reads the queue, marks each scan once, answers it and removes it.
func processQueue() {
	base := outletBase()
	b, err := fbGet(base+"/sq", "")
	if err != nil || len(b) == 0 || string(b) == "null" {
		return
	}
	var q map[string]map[string]queuedScan
	if json.Unmarshal(b, &q) != nil {
		_ = fb(http.MethodDelete, base+"/sq", "", nil) // something odd in the queue
		return
	}
	now := time.Now()
	for k, scans := range q {
		p, ok := phoneByKey(k)
		if !ok {
			_ = fb(http.MethodDelete, base+"/sq/"+k, "", nil) // removed phone
			continue
		}
		ids := make([]string, 0, len(scans))
		for id := range scans {
			ids = append(ids, id)
		}
		sort.Strings(ids)
		for _, id := range ids {
			s := scans[id]
			if cid := cleanID(id); cid != id || cid == "" {
				_ = fb(http.MethodDelete, base+"/sq/"+k+"/"+id, "", nil)
				continue
			}
			age := now.Sub(time.UnixMilli(int64(s.T)))
			var res ScanResult
			switch {
			case s.T > 0 && age > scanQueueMaxAge:
				res = ScanResult{Msg: "Too old, scan again", At: now.UnixMilli()}
			case tooFast(k, now):
				res = ScanResult{Msg: "Too many scans at once, wait a moment", At: now.UnixMilli()}
			default:
				notePhone(k, "internet")
				res = markFromScan(id, cleanCode(s.C), deviceName(p, s.D), "internet")
			}
			body, _ := json.Marshal(res)
			if fb(http.MethodPut, base+"/sr/"+k+"/"+id, "", body) == nil {
				phoneMu.Lock()
				answers[k+"/"+id] = now
				phoneMu.Unlock()
			}
			_ = fb(http.MethodDelete, base+"/sq/"+k+"/"+id, "", nil)
		}
	}
}

// cleanAnswers removes answers the phones have had time to read.
func cleanAnswers() {
	now := time.Now()
	var old []string
	phoneMu.Lock()
	for k, t := range answers {
		if now.Sub(t) > answerKeep {
			old = append(old, k)
			delete(answers, k)
		}
	}
	phoneMu.Unlock()
	base := outletBase()
	for _, k := range old {
		_ = fb(http.MethodDelete, base+"/sr/"+k, "", nil)
	}
}

// syncPairKeys writes the list of paired keys (what Firebase's rules check) when it changed, and
// again every 30 minutes.
func syncPairKeys(now time.Time) {
	c := getConfig()
	m := map[string]any{}
	keys := make([]string, 0, len(c.Phones))
	for _, p := range c.Phones {
		m[p.Key] = map[string]any{"n": p.Name, "t": p.Added}
		keys = append(keys, p.Key)
	}
	sort.Strings(keys)
	sig := outletBase() + "|" + strings.Join(keys, ",")
	phoneMu.Lock()
	due := sig != pkSynced || now.After(pkTryAt)
	phoneMu.Unlock()
	if !due {
		return
	}
	var err error
	if len(m) == 0 {
		err = fb(http.MethodDelete, outletBase()+"/pk", "", nil)
	} else {
		body, _ := json.Marshal(m)
		err = fb(http.MethodPut, outletBase()+"/pk", "", body)
	}
	phoneMu.Lock()
	if err == nil {
		pkSynced, pkTryAt = sig, now.Add(30*time.Minute)
	} else {
		pkTryAt = now.Add(time.Minute)
	}
	phoneMu.Unlock()
}

// ---- pairing (hub page) -------------------------------------------------------------------

// lanAddrs: this PC's shop-network addresses with the hub port ("192.168.1.20:8790").
func lanAddrs() []string {
	port := getConfig().Port
	if port == 0 {
		port = defaultPort
	}
	var out []string
	ifs, _ := net.Interfaces()
	for _, ifc := range ifs {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			ipn, ok := a.(*net.IPNet)
			if !ok || ipn.IP.To4() == nil || !ipn.IP.IsPrivate() {
				continue
			}
			out = append(out, net.JoinHostPort(ipn.IP.String(), strconv.Itoa(port)))
		}
	}
	return out
}

// pairText is what the QR code holds; the Babai Scanner app reads it.
func pairText(p Phone) string {
	c := getConfig()
	db := strings.TrimPrefix(strings.TrimPrefix(c.DB, "https://"), "http://")
	return "BABAISCAN1;h=" + strings.Join(lanAddrs(), ",") + ";db=" + db + ";o=" + outletCode(c.Outlet) +
		";k=" + p.Key + ";n=" + strings.NewReplacer(";", " ", "=", " ").Replace(p.Name) + ";on=" +
		strings.NewReplacer(";", " ", "=", " ").Replace(c.Outlet)
}

// handlePhones: GET the list; POST {op: add|remove|qr, name, key}.
func handlePhones(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	if r.Method == http.MethodGet {
		_ = json.NewEncoder(w).Encode(phonesStatus())
		return
	}
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Op, Name, Key string }
	if json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in) != nil {
		reply(w, false, "Couldn't read the form.")
		return
	}
	c := getConfig()
	switch in.Op {
	case "add":
		name := cleanText(in.Name, 30)
		if name == "" {
			name = "Phone " + strconv.Itoa(len(c.Phones)+1)
		}
		if len(c.Phones) >= 30 {
			reply(w, false, "30 phones is the most. Remove one first.")
			return
		}
		p := Phone{Key: newPairKey(), Name: name, Added: time.Now().UnixMilli()}
		c.Phones = append(append([]Phone(nil), c.Phones...), p)
		if err := saveConfig(c); err != nil {
			reply(w, false, "Couldn't save: "+err.Error())
			return
		}
		log.Printf("scanner phone added: %s", name)
		wakeQueue()
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "msg": "Scan this QR code with the Babai Scanner app on " + name + ".", "qr": pairText(p), "key": p.Key})
	case "qr":
		p, ok := phoneByKey(in.Key)
		if !ok {
			reply(w, false, "No such phone.")
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"ok": true, "qr": pairText(p), "key": p.Key, "msg": p.Name})
	case "remove":
		var keep []Phone
		removed := ""
		for _, p := range c.Phones {
			if p.Key == in.Key {
				removed = p.Name
				continue
			}
			keep = append(keep, p)
		}
		if removed == "" {
			reply(w, false, "No such phone.")
			return
		}
		c.Phones = keep
		if err := saveConfig(c); err != nil {
			reply(w, false, "Couldn't save: "+err.Error())
			return
		}
		if cloudReady() && validKey(in.Key) {
			base := outletBase()
			go func() {
				_ = fb(http.MethodDelete, base+"/pk/"+in.Key, "", nil)
				_ = fb(http.MethodDelete, base+"/sq/"+in.Key, "", nil)
				_ = fb(http.MethodDelete, base+"/sr/"+in.Key, "", nil)
			}()
		}
		log.Printf("scanner phone removed: %s", removed)
		wakeQueue()
		reply(w, true, removed+" removed. It can't mark orders any more.")
	default:
		reply(w, false, "Unknown change.")
	}
}

func phonesStatus() map[string]any {
	c := getConfig()
	phoneMu.Lock()
	defer phoneMu.Unlock()
	list := []map[string]any{}
	for _, p := range c.Phones {
		e := map[string]any{"key": p.Key, "name": p.Name, "added": p.Added}
		if t, ok := phoneSeen[p.Key]; ok {
			e["seen"], e["route"] = t.UnixMilli(), phoneRoute[p.Key]
		}
		list = append(list, e)
	}
	internet := "needs Step 2 (Firebase)"
	if cloudReady() {
		internet = "ready"
		if len(c.Phones) > 0 {
			if sqLive {
				internet = "live"
			} else {
				internet = "connecting…"
			}
		}
	}
	return map[string]any{"phones": list, "wifi": lanAddrs(), "internet": internet, "scans": scanLogSnapshot(),
		"bridge": bridgeStatus(), "bridgeAddr": c.Bridge}
}
