package main

// The dine-in dosa token logic of the Order Ready TV screen (TokenBoard.java and DosaStats.java in
// kds-dosa-live), ported so the customers' phones show the same thing as the TV.

import (
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	tbMissing   = 5 * time.Second  // board blip protection
	tbStale     = 30 * time.Minute // already ready + this old when first seen: leftover, hidden
	maxEtaMin   = 40.0             // never show more than 40 min
	winMs       = 15 * 60_000      // recent-throughput window
	minWinMs    = 3 * 60_000       // avoid huge rates right after start
	maxPrepMs   = 150 * 60_000     // forgotten tickets are outliers
	confidentN  = 6                // recent completions for full queue weighting
	kotRowCap   = 300              // the billing PC sends at most this many rows
	defaultClrM = 10               // ready tokens leave after 10 min if nobody dispatches
)

// ---- token board ------------------------------------------------------------------------------

type tbKot struct {
	id                          int64
	label                       string
	created                     int64
	ready, dispatched, canceled bool
	dosas                       int
	items                       []KItem
}

type tbRec struct {
	kot          tbKot
	missingSince int64
	readyAt      int64
	released     bool
}

type Token struct {
	Label     string
	Created   int64
	ReadyAt   int64
	Ready     bool
	Dosas     int
	Overtaken bool
	Items     []KItem
}

type tokenBoard struct {
	kots      map[int64]*tbRec
	order     []int64
	ready     []*Token
	preparing []*Token
	clearMs   int64
}

func newTokenBoard() *tokenBoard {
	return &tokenBoard{kots: map[int64]*tbRec{}, clearMs: defaultClrM * 60_000}
}

func (b *tokenBoard) update(board []tbKot, now int64) {
	seen := map[int64]bool{}
	for _, k := range board {
		seen[k.id] = true
		r := b.kots[k.id]
		if r == nil {
			r = &tbRec{}
			b.kots[k.id] = r
			b.order = append(b.order, k.id)
			if k.ready {
				r.readyAt = now
				if k.created > 0 && now-k.created > tbStale.Milliseconds() {
					r.released = true
				}
			}
		} else if k.ready && r.readyAt == 0 {
			r.readyAt = now
		} else if !k.ready {
			r.readyAt = 0
			r.released = false
		}
		r.kot = k
		r.missingSince = 0
	}
	keep := b.order[:0]
	for _, id := range b.order {
		r := b.kots[id]
		drop := r.kot.dispatched || r.kot.canceled
		if !drop && !seen[id] {
			if r.missingSince == 0 {
				r.missingSince = now
			} else if now-r.missingSince >= tbMissing.Milliseconds() {
				drop = true
			}
		}
		if drop {
			delete(b.kots, id)
		} else {
			keep = append(keep, id)
		}
	}
	b.order = keep
	b.rebuild(now)
}

func (b *tokenBoard) rebuild(now int64) {
	next := map[string]*Token{}
	var labels []string
	recs := map[string][]*tbRec{}
	for _, id := range b.order {
		r := b.kots[id]
		if r.released {
			continue
		}
		k := r.kot
		t := next[k.label]
		if t == nil {
			t = &Token{Label: k.label, Created: k.created, Ready: true}
			next[k.label] = t
			labels = append(labels, k.label)
		}
		recs[k.label] = append(recs[k.label], r)
		if k.created > 0 && (t.Created <= 0 || k.created < t.Created) {
			t.Created = k.created
		}
		t.Ready = t.Ready && k.ready
		if r.readyAt > t.ReadyAt {
			t.ReadyAt = r.readyAt
		}
		t.Dosas += max(1, k.dosas)
		t.Items = append(t.Items, k.items...)
	}
	b.ready, b.preparing = nil, nil
	for _, l := range labels {
		t := next[l]
		if b.clearMs > 0 && t.Ready && t.ReadyAt > 0 && now-t.ReadyAt >= b.clearMs {
			for _, r := range recs[l] {
				r.released = true // auto-clear: ready for too long
			}
			continue
		}
		if t.Ready {
			b.ready = append(b.ready, t)
		} else {
			b.preparing = append(b.preparing, t)
		}
	}
	sort.SliceStable(b.ready, func(i, j int) bool { return b.ready[i].ReadyAt > b.ready[j].ReadyAt })
	sort.SliceStable(b.preparing, func(i, j int) bool { return b.preparing[i].Created < b.preparing[j].Created })
	var newestReady int64
	for _, t := range b.ready {
		newestReady = max(newestReady, t.Created)
	}
	for _, t := range b.preparing {
		t.Overtaken = t.Created > 0 && t.Created < newestReady
	}
}

func minutesLeft(pos int, elapsedMin, avgPrepMin, perMin float64) float64 {
	haveAvg := !math.IsNaN(avgPrepMin) && avgPrepMin > 0
	haveRate := perMin > 0
	if !haveAvg && !haveRate {
		return math.NaN()
	}
	left := 0.0
	if haveAvg {
		left = avgPrepMin - elapsedMin
	}
	if haveRate {
		left = math.Max(left, float64(pos+1)/perMin)
	}
	return math.Max(0.5, math.Min(maxEtaMin, left))
}

// ---- dosa statistics (today) ------------------------------------------------------------------

type dsEntry struct {
	id       int64
	created  int64
	ready    bool
	canceled bool
	names    []string
	qty      []float64
}

func (e dsEntry) pieces() int {
	if len(e.names) == 0 {
		return 1
	}
	p := 0.0
	for _, q := range e.qty {
		p += q
	}
	return max(1, int(math.Round(p)))
}

// DayStats is saved in stats.json so a restart of the PC keeps today's figures.
type DayStats struct {
	Day         string         `json:"day"`
	Completed   int            `json:"completed"`
	TotalPrepMs int64          `json:"totalPrepMs"`
	Counted     []int64        `json:"counted"`
	SeenToday   []int64        `json:"seenToday"`
	Kinds       map[string]int `json:"kinds"`
	Hourly      [24]int        `json:"hourly"`
	FastestMs   int64          `json:"fastestMs"`
	Served      int            `json:"served"`
	LastDayAvg  float64        `json:"lastDayAvg"`
}

type dosaStats struct {
	DayStats
	trackingSince int64
	counted       map[int64]bool
	seenToday     map[int64]bool
	recent        []int64
	active        map[int64]int64
	activePieces  map[int64]int
	running       int
	dirty         bool
}

type statsResult struct {
	avgPrep, perMin, eta float64
	etaLabel             string
	running              int
	facts                []string
}

func newDosaStats() *dosaStats {
	s := &dosaStats{active: map[int64]int64{}, activePieces: map[int64]int{}}
	s.reset(time.Now(), 0)
	return s
}

func (s *dosaStats) reset(now time.Time, lastAvg float64) {
	s.DayStats = DayStats{Day: now.Format("2006-01-02"), Kinds: map[string]int{}, LastDayAvg: lastAvg}
	s.counted, s.seenToday, s.recent = map[int64]bool{}, map[int64]bool{}, nil
	s.trackingSince = now.UnixMilli()
}

func (s *dosaStats) rollover(now time.Time) {
	if now.Format("2006-01-02") == s.Day {
		return
	}
	last := s.LastDayAvg
	if s.Completed > 0 {
		last = float64(s.TotalPrepMs) / float64(s.Completed) / 60000
	}
	s.reset(now, last)
	s.dirty = true
}

func (s *dosaStats) update(entries []dsEntry, now time.Time, capPressure bool) {
	s.rollover(now)
	ms := now.UnixMilli()
	y, m, d := now.Date()
	dayStart := time.Date(y, m, d, 0, 0, 0, 0, now.Location()).UnixMilli()
	seen := map[int64]bool{}
	running := 0
	for _, e := range entries {
		seen[e.id] = true
		if !e.canceled && !e.ready {
			running += e.pieces()
		}
		if !e.canceled && (e.created <= 0 || e.created >= dayStart) && !s.seenToday[e.id] {
			s.seenToday[e.id] = true
			at := e.created
			if at <= 0 {
				at = ms
			}
			h := time.UnixMilli(at).Hour()
			for i, n := range e.names {
				q := max(1, int(math.Round(e.qty[i])))
				s.Kinds[n] += q
				s.Hourly[h] += q
			}
			s.dirty = true
		}
		switch {
		case e.canceled:
			delete(s.active, e.id)
		case e.ready:
			if c, ok := s.active[e.id]; ok {
				delete(s.active, e.id)
				if e.created > 0 {
					c = e.created
				}
				s.record(e.id, c, ms, e.pieces())
			}
		default:
			s.activePieces[e.id] = e.pieces()
			s.active[e.id] = e.created
		}
	}
	s.running = running
	for id, c := range s.active {
		if !seen[id] {
			if !capPressure {
				s.record(id, c, ms, max(1, s.activePieces[id]))
			}
			delete(s.active, id)
		}
	}
	for id := range s.activePieces {
		if _, ok := s.active[id]; !ok {
			delete(s.activePieces, id)
		}
	}
}

func (s *dosaStats) record(id, created, now int64, pieces int) {
	if s.counted[id] {
		return
	}
	s.counted[id] = true
	s.Served += pieces
	s.dirty = true
	prep := now - created
	if created <= 0 || prep < 0 || prep > maxPrepMs {
		return
	}
	s.Completed++
	s.TotalPrepMs += prep
	if s.FastestMs <= 0 || prep < s.FastestMs {
		s.FastestMs = prep
	}
	s.recent = append(s.recent, now)
}

func (s *dosaStats) compute(now time.Time) statsResult {
	s.rollover(now)
	ms := now.UnixMilli()
	for len(s.recent) > 0 && s.recent[0] < ms-winMs {
		s.recent = s.recent[1:]
	}
	r := statsResult{avgPrep: math.NaN(), running: s.running}
	if s.Completed > 0 {
		r.avgPrep = float64(s.TotalPrepMs) / float64(s.Completed) / 60000
	}
	y, m, d := now.Date()
	dayStart := time.Date(y, m, d, 0, 0, 0, 0, now.Location()).UnixMilli()
	observed := ms - max(s.trackingSince, dayStart)
	window := float64(max(minWinMs, min(winMs, observed))) / 60000
	r.perMin = float64(len(s.recent)) / window
	oldest := 0.0
	for _, c := range s.active {
		if c > 0 {
			oldest = math.Max(oldest, float64(ms-c)/60000)
		}
	}
	avg := r.avgPrep
	if math.IsNaN(avg) && s.LastDayAvg > 0 {
		avg = s.LastDayAvg
	}
	var eta float64
	active := len(s.active)
	switch {
	case active == 0:
		eta = avg
	case r.perMin <= 0:
		floor := avg
		if math.IsNaN(floor) {
			floor = 0
		}
		eta = math.Max(floor, oldest)
		if eta <= 0 {
			eta = math.NaN()
		}
	case math.IsNaN(avg):
		eta = float64(active) / r.perMin
	default:
		core := math.Max(float64(active)/r.perMin, 0.6*avg)
		w := math.Min(1, float64(len(s.recent))/confidentN)
		eta = (1-w)*avg + w*core
	}
	if !math.IsNaN(eta) {
		eta = math.Min(eta, maxEtaMin)
	}
	r.eta = eta
	if math.IsNaN(eta) {
		r.etaLabel = ""
	} else {
		r.etaLabel = friendlyRange(eta)
	}
	r.facts = s.facts(r)
	return r
}

func (s *dosaStats) facts(r statsResult) []string {
	type kv struct {
		k string
		v int
	}
	var top []kv
	total := 0
	for k, v := range s.Kinds {
		top = append(top, kv{k, v})
		total += v
	}
	sort.Slice(top, func(i, j int) bool {
		if top[i].v != top[j].v {
			return top[i].v > top[j].v
		}
		return top[i].k < top[j].k
	})
	var f []string
	if len(top) > 0 {
		f = append(f, top[0].k+" is today's favourite at Babai Tiffins")
	}
	if len(top) > 1 {
		f = append(f, top[1].k+" is today's runner-up — a close race!")
	}
	if s.Served > 0 {
		f = append(f, fmt.Sprintf("%d %s of dosa served so far today", s.Served, plural(s.Served, "happy plate", "happy plates")))
	}
	best := -1
	for h := 0; h < 24; h++ {
		if s.Hourly[h] > 0 && (best < 0 || s.Hourly[h] > s.Hourly[best]) {
			best = h
		}
	}
	if best >= 0 && total >= 5 {
		f = append(f, "Dosa rush hour today: "+hourLabel(best))
	}
	if s.FastestMs > 0 {
		f = append(f, fmt.Sprintf("Today's fastest dosa order was ready in just %d min", max(1, int(math.Round(float64(s.FastestMs)/60000)))))
	}
	if r.perMin > 0 {
		sec := int(math.Round(60 / r.perMin))
		if sec < 100 {
			f = append(f, fmt.Sprintf("Right now a fresh dosa order leaves our tawa every %d seconds", sec))
		} else {
			f = append(f, fmt.Sprintf("Right now a fresh dosa order leaves our tawa every %d min", int(math.Round(float64(sec)/60))))
		}
	}
	if r.running > 0 {
		f = append(f, fmt.Sprintf("%d %s sizzling on the tawa right now", r.running, plural(r.running, "dosa is", "dosas are")))
	}
	if s.Served >= 10 {
		f = append(f, fmt.Sprintf("Laid end to end, today's dosas would stretch about %d metres", int(math.Round(float64(s.Served)*0.35))))
	}
	return f
}

func plural(n int, one, many string) string {
	if n == 1 {
		return one
	}
	return many
}

func hourLabel(h int) string {
	a, b := h%12, (h+1)%12
	if a == 0 {
		a = 12
	}
	if b == 0 {
		b = 12
	}
	ap := "AM"
	if (h+1)%24 >= 12 {
		ap = "PM"
	}
	return fmt.Sprintf("%d–%d %s", a, b, ap)
}

func friendlyRange(eta float64) string {
	if eta < 5 {
		return "Under 5 min"
	}
	e := int(math.Round(eta))
	var lo, hi int
	if e <= 30 {
		lo = 5 * (e / 5)
		if e-lo >= 3 {
			lo += 3
		}
		hi = lo + 3
	} else {
		lo = 5 * (e / 5)
		hi = lo + 5
	}
	if hi < e {
		hi = e
	}
	return fmt.Sprintf("%d–%d min", lo, hi)
}

func (s *dosaStats) save(path string) {
	if !s.dirty {
		return
	}
	s.dirty = false
	s.Counted, s.SeenToday = keys(s.counted), keys(s.seenToday)
	b, _ := json.Marshal(s.DayStats)
	_ = writeFileAtomic(path, b)
}

func (s *dosaStats) load(path string, now time.Time) {
	b, err := os.ReadFile(path)
	if err != nil {
		return
	}
	var d DayStats
	if json.Unmarshal(b, &d) != nil {
		return
	}
	if d.Day != now.Format("2006-01-02") {
		last := d.LastDayAvg
		if d.Completed > 0 {
			last = float64(d.TotalPrepMs) / float64(d.Completed) / 60000
		}
		s.reset(now, last)
		return
	}
	if d.Kinds == nil {
		d.Kinds = map[string]int{}
	}
	s.DayStats = d
	for _, id := range d.Counted {
		s.counted[id] = true
	}
	for _, id := range d.SeenToday {
		s.seenToday[id] = true
	}
}

func keys(m map[int64]bool) []int64 {
	out := make([]int64, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}

// ---- from KOTs to tokens ----------------------------------------------------------------------

func isDosa(category string) bool { return strings.Contains(strings.ToLower(category), "dosa") }
func itemReady(s int) bool        { return s == 9 || s == 10 }

// tokenLabel: the token number, else "T<table>", else "#<last 3 of the KOT id>" (as on the TV).
func tokenLabel(k Kot) string {
	if k.Token > 0 {
		return strconv.FormatInt(k.Token, 10)
	}
	if k.Table != "" {
		return "T" + k.Table
	}
	id := strconv.FormatInt(k.ID, 10)
	if len(id) > 3 {
		id = id[len(id)-3:]
	}
	return "#" + id
}

var nonAlnum = regexp.MustCompile(`[^A-Z0-9]`)

// tokenKey is how a customer finds the token: what they type, normalised the same way as the page.
func tokenKey(label string) string {
	if strings.HasPrefix(label, "#") {
		return "X" + nonAlnum.ReplaceAllString(label[1:], "")
	}
	k := nonAlnum.ReplaceAllString(strings.ToUpper(label), "")
	if n, err := strconv.ParseInt(k, 10, 64); err == nil {
		return strconv.FormatInt(n, 10)
	}
	return k
}

// dineInDosa builds the token-board input and the statistics input from the open KOTs.
func dineInDosa(kots []Kot) ([]tbKot, []dsEntry) {
	var tb []tbKot
	var ds []dsEntry
	for _, k := range kots {
		if k.OrderType != 3 {
			continue
		}
		var names []string
		var qty []float64
		allReady, anyDosa, allDispatched := true, false, true
		var items []KItem
		for _, it := range k.Items {
			if it.S == -9 {
				continue
			}
			items = append(items, it)
			if !isDosa(it.C) {
				continue
			}
			anyDosa = true
			names = append(names, it.N)
			q := it.Q
			if q <= 0 {
				q = 1
			}
			qty = append(qty, q)
			if !itemReady(it.S) {
				allReady = false
			}
			if it.S != 10 {
				allDispatched = false
			}
		}
		if !anyDosa {
			continue
		}
		canceled := k.Status == "0"
		ready := k.Status == "9" || k.Status == "10" || allReady
		dosas := 0
		for _, q := range qty {
			dosas += max(1, int(math.Round(q)))
		}
		ds = append(ds, dsEntry{id: k.ID, created: k.Created, ready: ready, canceled: canceled, names: names, qty: qty})
		tb = append(tb, tbKot{id: k.ID, label: tokenLabel(k), created: k.Created, ready: ready,
			dispatched: k.Status == "10" || allDispatched, canceled: canceled, dosas: dosas, items: items})
	}
	return tb, ds
}

// ---- what goes online -----------------------------------------------------------------------

// PhoneItem is one line of the customer's own order: k "d" dosa, "o" other dish; s 1 = ready.
type PhoneItem struct {
	N string  `json:"n"`
	Q float64 `json:"q"`
	K string  `json:"k"`
	S int     `json:"s"`
}

// PhoneToken is /babai/t/<token>: everything one customer's page needs, nothing about others.
type PhoneToken struct {
	S   string      `json:"s"`           // "p" preparing, "r" ready
	Pos int         `json:"pos"`         // orders before this one (preparing)
	M   int         `json:"m"`           // minutes left, -1 unknown
	O   int         `json:"o,omitempty"` // 1 = a newer order came out first
	D   int         `json:"d"`           // dosas
	C   int64       `json:"c,omitempty"` // ordered (ms)
	A   int64       `json:"a,omitempty"` // ready since (ms)
	I   []PhoneItem `json:"i"`
	Lbl string      `json:"l"` // token as printed
}

type PhoneMeta struct {
	W string   `json:"w"` // dosa wait, e.g. "8–10 min"
	N int      `json:"n"` // dosas on the tawa
	F []string `json:"f"` // live "did you know" facts
	V int      `json:"v"`
}

func phoneItems(items []KItem) []PhoneItem {
	// merge the same dish across the token's KOTs
	idx := map[string]int{}
	var out []PhoneItem
	for _, it := range items {
		k := "o"
		if isDosa(it.C) {
			k = "d"
		}
		s := 0
		if itemReady(it.S) {
			s = 1
		}
		key := k + "|" + it.N + "|" + strconv.Itoa(s)
		if i, ok := idx[key]; ok {
			out[i].Q += it.Q
			continue
		}
		idx[key] = len(out)
		out = append(out, PhoneItem{N: it.N, Q: it.Q, K: k, S: s})
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].K < out[j].K }) // dosas first
	return out
}

func phoneTokens(b *tokenBoard, r statsResult, now int64) map[string]PhoneToken {
	out := map[string]PhoneToken{}
	for _, t := range b.ready {
		out[tokenKey(t.Label)] = PhoneToken{S: "r", A: t.ReadyAt, D: t.Dosas, C: t.Created, M: 0, I: phoneItems(t.Items), Lbl: t.Label}
	}
	for i, t := range b.preparing {
		elapsed := 0.0
		if t.Created > 0 {
			elapsed = float64(now-t.Created) / 60000
		}
		left := minutesLeft(i, elapsed, r.avgPrep, r.perMin)
		m := -1
		if !math.IsNaN(left) {
			m = max(1, int(math.Round(left)))
		}
		o := 0
		if t.Overtaken {
			o = 1
		}
		k := tokenKey(t.Label)
		if _, dup := out[k]; dup {
			continue
		}
		out[k] = PhoneToken{S: "p", Pos: i, M: m, O: o, D: t.Dosas, C: t.Created, I: phoneItems(t.Items), Lbl: t.Label}
	}
	return out
}

// ---- the hub's live state -------------------------------------------------------------------

var (
	board     = newTokenBoard()
	stats     = newDosaStats()
	statsPath = func() string { return filepath.Join(dataDir, "stats.json") }
	statsLoad bool
)

// onBoard is called with every fresh KOT list from Petpooja.
func onBoard(kots []Kot, now time.Time) {
	tb, ds := dineInDosa(kots)
	mu.Lock()
	defer mu.Unlock()
	if !statsLoad {
		statsLoad = true
		stats.load(statsPath(), now)
	}
	board.clearMs = int64(getConfig().AutoClearMin) * 60_000
	board.update(tb, now.UnixMilli())
	stats.update(ds, now, ppRowsAtCap())
	res := stats.compute(now)
	tokensRaw, _ = json.Marshal(phoneTokens(board, res, now.UnixMilli()))
	metaRaw, _ = json.Marshal(PhoneMeta{W: res.etaLabel, N: res.running, F: res.facts, V: 1})
	liveRaw, _ = json.Marshal(kots)
	readyCount, prepCount = len(board.ready), len(board.preparing)
	lastBoard = now
	track(kots, now)
	if now.Sub(statsSaved) >= time.Minute {
		statsSaved = now
		stats.save(statsPath())
	}
}

func ppRowsAtCap() bool {
	ppMu.Lock()
	defer ppMu.Unlock()
	return ppRows >= kotRowCap-5
}
