package main

// Dosa barker: the dosa master's screen, tawa by tawa, on any TV / tablet / monitor on the shop
// Wi-Fi: http://<Console PC>:8790/barker (one tawa only, big: /barker?tawa=2).
//
// The dosas of the open KOTs (read from Petpooja like everything else here, read-only), first come
// first served. Each tawa cooks the channels set for the day (Delivery, Pick up, Dine in, Self
// service or all); a KOT is never split between tawas and keeps its tawa once given. A tap (or a
// foot pedal, which types a key) puts a card on the tawa (grey), the next one marks it done.
// "On" / "done" live only on this PC: nothing is sent to Petpooja. A dosa marked ready in
// Petpooja, or a KOT that is ready / cancelled, leaves the barker by itself.
// Settings from another device need the Console PIN.

import (
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

type barkCfg struct {
	Plans map[string][]string `json:"plans"` // "Monday".."Sunday" or "default" -> per tawa: "A" all, or letters of D P I S
	Cats  []string            `json:"cats"`  // categories cooked on the tawas (empty: every category with "dosa" in its name)
	Cap   int                 `json:"cap"`   // dosas on one tawa at a time
	Win   int                 `json:"win"`   // minutes: the same dosa ordered this close is added up in one card
	Max   int                 `json:"max"`   // dosas in one card
	Chain bool                `json:"chain"` // one pedal press: finish the grey card and put the next one on
	Help  bool                `json:"help"`  // an empty tawa takes KOTs when the others are full
	Merge bool                `json:"merge"` // add up the same dosa
}

type barkMark struct {
	St  string `json:"st"` // "on" or "done"
	At  int64  `json:"at"`
	Grp int64  `json:"g,omitempty"`
}

type barkUnit struct {
	ID   string `json:"id"`
	Kot  int64  `json:"k"`
	KotN string `json:"kn"` // KOT number as printed
	Ch   string `json:"ch"` // D P I S
	App  string `json:"app,omitempty"`
	Ref  string `json:"ref,omitempty"` // delivery: last 4 of the order ID; else the table
	C    int64  `json:"c"`
	Q    int    `json:"q"`
	N    string `json:"n"`
	Nt   string `json:"nt,omitempty"`
	O    int    `json:"o"`  // place in the list: KOT time, then the bill's order
	St   string `json:"st"` // wait / on / done
	At   int64  `json:"at,omitempty"`
	Grp  int64  `json:"g,omitempty"`
	T    int    `json:"t"`
	Help bool   `json:"h,omitempty"` // on a tawa that doesn't cook its channel (helping)
}

type barkSaved struct {
	Marks map[string]barkMark `json:"marks"`
	Owner map[int64]int       `json:"owner"`
	Seen  map[int64]int64     `json:"seen"`
	Grp   int64               `json:"grp"`
}

var (
	barkMu     sync.Mutex
	barkConf   barkCfg
	barkLoaded bool
	barkMarks  = map[string]barkMark{}
	barkOwner  = map[int64]int{}   // KOT -> tawa
	barkSeen   = map[int64]int64{} // KOT -> last time it was open (ms)
	barkGrp    int64
	barkSaveAt time.Time
	barkDirty  bool
)

var barkDays = []string{"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"}

func barkDefaults() barkCfg {
	return barkCfg{Plans: map[string][]string{"default": {"A", "A"}}, Cap: 8, Win: 3, Max: 5, Chain: true, Help: true, Merge: true}
}

func barkCfgPath() string   { return filepath.Join(dataDir, "barker.json") }
func barkStatePath() string { return filepath.Join(dataDir, "barker_state.json") }

// barkLoad (barkMu held)
func barkLoad() {
	if barkLoaded {
		return
	}
	barkLoaded = true
	barkConf = barkDefaults()
	var c barkCfg
	if readJSONFile(barkCfgPath(), &c) {
		barkConf = barkClean(c)
	}
	var s barkSaved
	if readJSONFile(barkStatePath(), &s) {
		if s.Marks != nil {
			barkMarks = s.Marks
		}
		if s.Owner != nil {
			barkOwner = s.Owner
		}
		if s.Seen != nil {
			barkSeen = s.Seen
		}
		barkGrp = s.Grp
	}
}

func clampInt(v, lo, hi, def int) int {
	if v == 0 {
		return def
	}
	return max(lo, min(hi, v))
}

// barkClean: a saved or posted setting made safe.
func barkClean(c barkCfg) barkCfg {
	d := barkDefaults()
	out := barkCfg{Plans: map[string][]string{}, Cap: clampInt(c.Cap, 1, 30, d.Cap), Win: clampInt(c.Win, 1, 30, d.Win),
		Max: clampInt(c.Max, 1, 30, d.Max), Chain: c.Chain, Help: c.Help, Merge: c.Merge}
	for day, p := range c.Plans {
		ok := day == "default"
		for _, d := range barkDays {
			ok = ok || day == d
		}
		if !ok || len(p) == 0 {
			continue
		}
		var q []string
		for _, ch := range p {
			if len(q) == 6 {
				break
			}
			q = append(q, barkChannels(ch))
		}
		out.Plans[day] = q
	}
	if len(out.Plans["default"]) == 0 {
		out.Plans["default"] = d.Plans["default"]
	}
	for _, cat := range c.Cats {
		if cat = strings.TrimSpace(cat); cat != "" && len(out.Cats) < 200 {
			out.Cats = append(out.Cats, cat)
		}
	}
	return out
}

// barkChannels: "A", or the letters D P I S in that order (unknown letters dropped).
func barkChannels(s string) string {
	s = strings.ToUpper(s)
	if strings.Contains(s, "A") {
		return "A"
	}
	var b strings.Builder
	for _, c := range "DPIS" {
		if strings.ContainsRune(s, c) {
			b.WriteRune(c)
		}
	}
	if b.Len() == 0 {
		return "A"
	}
	return b.String()
}

func barkPlan(c barkCfg, now time.Time) (string, []string) {
	day := now.Weekday().String()
	if p := c.Plans[day]; len(p) > 0 {
		return day, p
	}
	return day, c.Plans["default"]
}

func barkCooks(plan, ch string) bool { return plan == "A" || strings.Contains(plan, ch) }

func barkChannel(k Kot) string {
	b := strings.ToLower(brandOf(k) + " " + k.Channel)
	switch {
	case strings.Contains(b, "self service") || strings.Contains(b, "self-service"):
		return "S"
	case k.Online || k.OrderType == 1:
		return "D"
	case k.OrderType == 2:
		return "P"
	}
	return "I"
}

func barkIsTawa(c barkCfg, it KItem) bool {
	if len(c.Cats) == 0 {
		return isDosa(it.C) || isDosa(it.N)
	}
	for _, cat := range c.Cats {
		if strings.EqualFold(cat, strings.TrimSpace(it.C)) {
			return true
		}
	}
	return false
}

// barkUnits: the tawa dishes of the open KOTs, oldest KOT first (no state yet).
func barkUnits(c barkCfg, kots []Kot) []barkUnit {
	var out []barkUnit
	for _, k := range kots {
		if k.Status == "0" || k.Status == "9" || k.Status == "10" {
			continue
		}
		ch := barkChannel(k)
		kn := ""
		if k.Token > 0 {
			kn = fmt.Sprint(k.Token)
		} else {
			kn = fmt.Sprint(k.ID)
			if len(kn) > 3 {
				kn = kn[len(kn)-3:]
			}
		}
		app, ref := "", ""
		if ch == "D" {
			app, ref = brandOf(k), last4Of(k.POID)
		} else if k.Table != "" {
			ref = "Table " + k.Table
		}
		seen := map[string]int{}
		for _, it := range k.Items {
			key := strings.ToLower(it.N)
			seen[key]++
			if !barkIsTawa(c, it) || itemReady(it.S) || it.S < 0 {
				continue
			}
			q := int(math.Round(it.Q))
			if q < 1 {
				q = 1
			}
			out = append(out, barkUnit{ID: fmt.Sprintf("%d-%s-%d", k.ID, key, seen[key]), Kot: k.ID, KotN: kn, Ch: ch,
				App: app, Ref: ref, C: k.Created, Q: q, N: it.N, Nt: it.Nt, St: "wait"})
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].C < out[j].C })
	for i := range out {
		out[i].O = i
	}
	return out
}

// barkSnapshot (barkMu held): the units with their state and tawa. Whole KOTs go to a tawa: a KOT
// keeps the tawa it was given; a new one goes, oldest first, to the tawa (that cooks its channel)
// with the fewest dosas not done yet.
func barkSnapshot(kots []Kot, now time.Time) (string, []string, []barkUnit) {
	barkLoad()
	day, plan := barkPlan(barkConf, now)
	us := barkUnits(barkConf, kots)
	ms := now.UnixMilli()
	open := map[int64]bool{}
	for i := range us {
		u := &us[i]
		open[u.Kot] = true
		if m, ok := barkMarks[u.ID]; ok {
			u.St, u.At, u.Grp = m.St, m.At, m.Grp
		}
	}
	for k := range open {
		if barkSeen[k] < ms-60_000 {
			barkDirty = true
		}
		barkSeen[k] = ms
	}
	// forget KOTs gone for 2 hours
	for k, at := range barkSeen {
		if !open[k] && ms-at > 2*3600_000 {
			delete(barkSeen, k)
			delete(barkOwner, k)
			for id := range barkMarks {
				if strings.HasPrefix(id, fmt.Sprintf("%d-", k)) {
					delete(barkMarks, id)
				}
			}
			barkDirty = true
		}
	}
	n := len(plan)
	load := make([]int, n)
	type grp struct {
		kot  int64
		ch   string
		live int
		on   bool
		idx  []int
	}
	var groups []*grp
	byK := map[int64]*grp{}
	for i, u := range us {
		g := byK[u.Kot]
		if g == nil {
			g = &grp{kot: u.Kot, ch: u.Ch}
			byK[u.Kot] = g
			groups = append(groups, g)
		}
		g.idx = append(g.idx, i)
		if u.St != "done" {
			g.live += u.Q
		}
		if u.St == "on" {
			g.on = true
		}
	}
	var fresh []*grp
	for _, g := range groups {
		if t, ok := barkOwner[g.kot]; ok && t < n {
			load[t] += g.live
			continue
		}
		if g.on {
			barkOwner[g.kot] = 0
			load[0] += g.live
			continue
		}
		fresh = append(fresh, g)
	}
	for _, g := range fresh {
		var ok, idle []int
		for t, p := range plan {
			if barkCooks(p, g.ch) {
				ok = append(ok, t)
			} else if load[t] == 0 {
				idle = append(idle, t)
			}
		}
		if barkConf.Help && len(idle) > 0 {
			full := true
			for _, t := range ok {
				full = full && load[t] >= barkConf.Cap
			}
			if full {
				ok = append(ok, idle...)
			}
		}
		if len(ok) == 0 {
			for t := range plan {
				ok = append(ok, t)
			}
		}
		best := ok[0]
		for _, t := range ok {
			if load[t] < load[best] {
				best = t
			}
		}
		barkOwner[g.kot] = best
		load[best] += g.live
		barkDirty = true
	}
	for _, g := range groups {
		t := barkOwner[g.kot]
		for _, i := range g.idx {
			us[i].T, us[i].Help = t, !barkCooks(plan[t], g.ch)
		}
	}
	return day, plan, us
}

// barkSave (barkMu held): the state now (force) or at most every 30 s.
func barkSave(now time.Time, force bool) {
	if !barkDirty || (!force && now.Sub(barkSaveAt) < 30*time.Second) {
		return
	}
	if b, err := json.Marshal(barkSaved{Marks: barkMarks, Owner: barkOwner, Seen: barkSeen, Grp: barkGrp}); err == nil && writeFileAtomic(barkStatePath(), b) == nil {
		barkDirty, barkSaveAt = false, now
	}
}

func barkKots() ([]Kot, int64) {
	ppMu.Lock()
	defer ppMu.Unlock()
	var age int64 = -1
	if !ppOKAt.IsZero() {
		age = time.Since(ppOKAt).Milliseconds()
	}
	return append([]Kot(nil), ppKots...), age
}

func barkerRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/barker", handleBarkerPage)
	mux.HandleFunc("/barker/", handleBarkerPage)
	mux.HandleFunc("/barker/logo.webp", serveConsoleFile("logo.webp"))
	mux.HandleFunc("/barker/api/state", handleBarkerState)
	mux.HandleFunc("/barker/api/tap", handleBarkerTap)
	mux.HandleFunc("/barker/api/config", handleBarkerConfig)
	mux.HandleFunc("/barker/unlock", handleUnlock) // POST with the page's header; 5 wrong PINs = 1 min wait
}

func handleBarkerPage(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/barker" && r.URL.Path != "/barker/" {
		http.NotFound(w, r)
		return
	}
	b, err := consoleFiles.ReadFile("console/barker.html")
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(b)
}

func isLoopback(r *http.Request) bool {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func barkState(now time.Time) map[string]any {
	kots, age := barkKots()
	barkMu.Lock()
	defer barkMu.Unlock()
	day, plan, us := barkSnapshot(kots, now)
	barkSave(now, false)
	cats := []string{}
	for c := range menuSeenSnapshotNoLoad() {
		cats = append(cats, c)
	}
	sort.Strings(cats)
	return map[string]any{"ok": true, "now": now.UnixMilli(), "day": day, "plan": plan, "cfg": barkConf, "units": us,
		"ppAge": age, "menuCats": cats, "v": version}
}

// menuSeenSnapshotNoLoad: the categories for the settings, without blocking on a first load.
func menuSeenSnapshotNoLoad() map[string][]string {
	if !menuMu.TryLock() {
		return nil
	}
	defer menuMu.Unlock()
	out := map[string][]string{}
	for c := range menuSeen {
		out[c] = nil
	}
	return out
}

func handleBarkerState(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	writeJSON(w, barkState(time.Now()))
}

// handleBarkerTap: POST {done:[ids], on:[ids], undo:[ids]} from the barker page (tap or pedal).
func handleBarkerTap(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Done, On, Undo []string }
	if json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&in) != nil {
		writeJSON(w, map[string]any{"ok": false, "msg": "Bad request."})
		return
	}
	now := time.Now()
	msg := barkTap(now, in.Done, in.On, in.Undo)
	st := barkState(now)
	if msg != "" {
		st["ok"], st["msg"] = false, msg
	}
	writeJSON(w, st)
}

// barkTap applies a tap; the answer is "" or why the "on" part was not done.
func barkTap(now time.Time, done, on, undo []string) string {
	kots, _ := barkKots()
	barkMu.Lock()
	defer barkMu.Unlock()
	_, plan, us := barkSnapshot(kots, now)
	byID := map[string]barkUnit{}
	for _, u := range us {
		byID[u.ID] = u
	}
	ms := now.UnixMilli()
	for _, id := range undo {
		if _, ok := byID[id]; ok {
			delete(barkMarks, id)
			barkDirty = true
		}
	}
	for _, id := range done {
		if u, ok := byID[id]; ok && u.St != "done" {
			barkMarks[id] = barkMark{St: "done", At: ms}
			u.St = "done"
			byID[id] = u
			barkDirty = true
		}
	}
	msg := ""
	if len(on) > 0 {
		t, add := -1, 0
		var ids []string
		for _, id := range on {
			if u, ok := byID[id]; ok && u.St == "wait" {
				t = u.T
				add += u.Q
				ids = append(ids, id)
			}
		}
		if t >= 0 {
			cur := 0
			for _, u := range byID {
				if u.St == "on" && u.T == t {
					cur += u.Q
				}
			}
			if cur > 0 && cur+add > barkConf.Cap {
				msg = fmt.Sprintf("Tawa %d is full (%d of %d). Finish a grey card first.", t+1, cur, barkConf.Cap)
			} else {
				barkGrp++
				for _, id := range ids {
					barkMarks[id] = barkMark{St: "on", At: ms, Grp: barkGrp}
				}
				barkDirty = true
			}
		}
	}
	_ = plan
	barkSave(now, true)
	return msg
}

// handleBarkerConfig: POST the settings; from this PC, or with the Console PIN.
func handleBarkerConfig(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	if !isLoopback(r) && !unlocked(r) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		io.WriteString(w, `{"ok":false,"locked":true,"msg":"Enter the Console PIN to change the tawas."}`)
		return
	}
	var c barkCfg
	if json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&c) != nil {
		writeJSON(w, map[string]any{"ok": false, "msg": "Bad settings."})
		return
	}
	now := time.Now()
	if msg := barkSetConfig(barkClean(c)); msg != "" {
		writeJSON(w, map[string]any{"ok": false, "msg": msg})
		return
	}
	writeJSON(w, barkState(now))
}

func barkSetConfig(c barkCfg) string {
	barkMu.Lock()
	defer barkMu.Unlock()
	barkLoad()
	if err := writeJSONFile(barkCfgPath(), c, false); err != nil {
		return "Could not save: " + err.Error()
	}
	barkConf = c
	// KOTs with nothing on a tawa yet are shared out again with the new plan
	on := map[int64]bool{}
	for id, m := range barkMarks {
		if m.St == "on" {
			var k int64
			fmt.Sscanf(id, "%d-", &k)
			on[k] = true
		}
	}
	for k := range barkOwner {
		if !on[k] {
			delete(barkOwner, k)
		}
	}
	barkDirty = true
	return ""
}
