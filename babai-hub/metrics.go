package main

// Metrics area (CX metrics and Ops metrics) for the outlet team, from this PC's own order records
// and the CX METRICS Google Sheet:
//
//	GET  /console/api/ops?from=&to=        this outlet's KOTs for the days (platform orders + dine-in)
//	GET  /console/api/opsload?kot=&day=    the kitchen in the 30 min around one order (volume, items)
//	GET  /console/api/cx                   the sheet's ratings and complaints for this outlet
//	GET/POST /console/api/metrics/targets  targets and colours (saved on this PC)
//
// Numbers are approximate: the platforms know when a rider arrives (GPS); this PC learns it from
// orders where the food was ready well before pickup. Early-mark limits start at 5 min (Swiggy) /
// 3 min (Zomato) and, after 30 days of orders, follow what this outlet's riders really take after
// a scanned Food Ready.

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	pcKeepDays   = 120 // days of data kept on this PC
	learnDays    = 30  // days of orders the learning looks at
	learnMinN    = 30  // orders a learned limit needs
	handoverMs   = 60_000
	maxKptMs     = 4 * 3600_000
	loadWindowMs = 15 * 60_000
)

// MetricTargets are the colours and limits (⚙ in the Metrics area).
type MetricTargets struct {
	KptG       float64 `json:"kptG"`
	KptO       float64 `json:"kptO"`
	RwtG       float64 `json:"rwtG"`
	RwtO       float64 `json:"rwtO"`
	FrG        float64 `json:"frG"`
	FrO        float64 `json:"frO"`
	RS         float64 `json:"rS"`
	RZ         float64 `json:"rZ"`
	RO         float64 `json:"rO"`
	RBand      float64 `json:"rBand"`
	Late       float64 `json:"late"` // delayed order: KPT over this
	Pick       float64 `json:"pick"` // "Marked late": Food Ready less than this before pickup
	ES         float64 `json:"eS"`   // "Early mark?": pickup more than this after Food Ready
	EZ         float64 `json:"eZ"`
	EO         float64 `json:"eO"`
	UseLearned bool    `json:"useLearned"`
}

var defaultTargets = MetricTargets{KptG: 9, KptO: 13, RwtG: 3, RwtO: 5, FrG: 85, FrO: 80, RS: 4.65, RZ: 4.45, RO: 4.5,
	RBand: 0.1, Late: 20, Pick: 1, ES: 5, EZ: 3, EO: 5, UseLearned: true}

var mtMu sync.Mutex

func targetsPath() string { return filepath.Join(dataDir, "metrics_targets.json") }

func loadTargets() MetricTargets {
	mtMu.Lock()
	defer mtMu.Unlock()
	t := defaultTargets
	readJSONFile(targetsPath(), &t)
	return t
}

func handleMetricTargets(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		if r.Header.Get("X-Babai-Hub") != "1" {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		var in MetricTargets
		if err := json.NewDecoder(io.LimitReader(r.Body, 8<<10)).Decode(&in); err != nil {
			reply(w, false, "Not saved: "+err.Error())
			return
		}
		if in.KptG <= 0 || in.KptO < in.KptG || in.RwtG <= 0 || in.RwtO < in.RwtG || in.FrO > in.FrG || in.Pick < 0 ||
			in.ES <= 0 || in.EZ <= 0 || in.EO <= 0 || in.Late <= 0 {
			reply(w, false, "Not saved: green must be better than orange, and limits above 0.")
			return
		}
		mtMu.Lock()
		b, _ := json.Marshal(in)
		err := writeFileAtomic(targetsPath(), b)
		mtMu.Unlock()
		if err != nil {
			reply(w, false, "Not saved: "+err.Error())
			return
		}
		reply(w, true, "Saved.")
		return
	}
	writeJSON(w, map[string]any{"targets": loadTargets(), "defaults": defaultTargets})
}

// ---- platform and outlet ------------------------------------------------------------------------

// platformCode: S Swiggy, Z Zomato, O Ownly, X another online platform, "" not an online order.
func platformCode(o OrderRec) string {
	c := strings.ToLower(o.Channel + " " + o.Brand)
	switch {
	case strings.Contains(c, "swiggy"):
		return "S"
	case strings.Contains(c, "zomato"):
		return "Z"
	case strings.Contains(c, "ownly"):
		return "O"
	}
	if len(digitsOnly(o.OrderID)) >= 6 || o.Type == 1 {
		return "X"
	}
	return ""
}

// outletCodeCX: this PC's outlet as the ratings sheet names it (VTR, SJP, JPN, HSR).
func outletCodeCX(name string) string {
	n := strings.ToUpper(strings.TrimSpace(name))
	switch {
	case n == "":
		return ""
	case strings.Contains(n, "WHITEFIELD") || strings.Contains(n, "VARTHUR") || strings.HasPrefix(n, "VTR"):
		return "VTR"
	case strings.Contains(n, "SARJAPUR") || strings.HasPrefix(n, "SJP"):
		return "SJP"
	case strings.Contains(n, "JP NAGAR") || strings.Contains(n, "JPNAGAR") || strings.Contains(n, "AREKERE") || strings.HasPrefix(n, "JPN"):
		return "JPN"
	case strings.Contains(n, "HSR"):
		return "HSR"
	}
	return strings.Fields(n)[0]
}

var outletNames = map[string]string{"VTR": "Whitefield", "SJP": "Sarjapur Road", "JPN": "JP Nagar", "HSR": "HSR"}

// startMs: when the order was punched, on this PC's clock.
func startMs(o OrderRec) int64 {
	s := o.Punched
	if s > 0 {
		s += clockSkew()
	}
	if s == 0 || (o.Seen > 0 && o.Seen-s > 3600_000) {
		s = o.Seen
	}
	return s
}

func kptMs(o OrderRec) int64 {
	s := startMs(o)
	if o.Ready > s && s > 0 && o.Ready-s < maxKptMs {
		return o.Ready - s
	}
	return 0
}

func mealOf(t time.Time) string {
	h := t.Hour()
	switch {
	case h < 12:
		return "B"
	case h < 16:
		return "L"
	case h < 19:
		return "S"
	}
	return "D"
}

func readDayOrders(day string) []OrderRec {
	if day == time.Now().Format("2006-01-02") || day == time.Now().AddDate(0, 0, -1).Format("2006-01-02") {
		return ordersOfDay(day) // saves what is still in memory first
	}
	path := filepath.Join(ordersDir(), day+".json")
	st, err := os.Stat(path)
	if err != nil {
		return nil
	}
	dayCacheMu.Lock()
	defer dayCacheMu.Unlock()
	if c, ok := dayCache[day]; ok && c.mod.Equal(st.ModTime()) {
		return c.list
	}
	var list []OrderRec
	readJSONFile(path, &list)
	if len(dayCache) > 45 {
		dayCache = map[string]dayCached{}
	}
	dayCache[day] = dayCached{st.ModTime(), list}
	return list
}

// Past days don't change: kept in memory once read (the learning and Kitchen load read many).
type dayCached struct {
	mod  time.Time
	list []OrderRec
}

var (
	dayCacheMu sync.Mutex
	dayCache   = map[string]dayCached{}
)

// ---- learning -----------------------------------------------------------------------------------

type learned struct {
	Days    int                           `json:"days"`    // days with platform orders in the last 30
	Updated int64                         `json:"updated"` // ms
	Early   map[string]map[string]float64 `json:"early"`   // platform -> meal -> min (only when learned)
	Arrive  map[string]float64            `json:"arrive"`  // "S|wd|slot" / "S||slot" / "S" -> min after punch
}

var (
	lrnMu sync.Mutex
	lrn   = learned{Early: map[string]map[string]float64{}, Arrive: map[string]float64{}}
)

func learnPath() string { return filepath.Join(dataDir, "metrics_learn.json") }

func metricsLoop() {
	lrnMu.Lock()
	readJSONFile(learnPath(), &lrn)
	lrnMu.Unlock()
	time.Sleep(time.Minute)
	for {
		pruneOldData(time.Now())
		relearn(time.Now())
		time.Sleep(6 * time.Hour)
	}
}

func percentile(v []float64, p float64) float64 {
	if len(v) == 0 {
		return 0
	}
	s := append([]float64(nil), v...)
	sort.Float64s(s)
	i := int(math.Ceil(p*float64(len(s)))) - 1
	if i < 0 {
		i = 0
	}
	return s[i]
}

func halfHour(t time.Time) int { return t.Hour()*2 + t.Minute()/30 }

// relearn: early-mark limits (P90 of Food Ready -> pickup after a scanned Food Ready, per platform
// and meal, kept within 2-8 min) and rider arrival (median punch -> arrival per platform, weekday
// and half hour, from orders whose food waited 3+ min for the rider).
func relearn(now time.Time) {
	gaps := map[string]map[string][]float64{}
	arr := map[string][]float64{}
	days := 0
	for i := 1; i <= learnDays; i++ {
		d := now.AddDate(0, 0, -i).Format("2006-01-02")
		any := false
		for _, o := range readDayOrders(d) {
			p := platformCode(o)
			if p == "" || p == "X" || o.Ready == 0 || o.Dispatched < o.Ready {
				continue
			}
			any = true
			s := startMs(o)
			gap := float64(o.Dispatched-o.Ready) / 60000
			if strings.HasPrefix(o.ReadyBy, "Scanner") && gap < 30 {
				st := time.UnixMilli(s)
				if gaps[p] == nil {
					gaps[p] = map[string][]float64{}
				}
				gaps[p][mealOf(st)] = append(gaps[p][mealOf(st)], gap)
			}
			if gap >= 3 && s > 0 {
				a := float64(o.Dispatched-handoverMs-s) / 60000
				if a > 0 && a < 120 {
					st := time.UnixMilli(s)
					hh := halfHour(st)
					for _, k := range []string{fmt.Sprintf("%s|%d|%d", p, st.Weekday(), hh), fmt.Sprintf("%s||%d", p, hh), p} {
						arr[k] = append(arr[k], a)
					}
				}
			}
		}
		if any {
			days++
		}
	}
	out := learned{Days: days, Updated: now.UnixMilli(), Early: map[string]map[string]float64{}, Arrive: map[string]float64{}}
	for p, ms := range gaps {
		for m, v := range ms {
			if len(v) >= learnMinN {
				if out.Early[p] == nil {
					out.Early[p] = map[string]float64{}
				}
				out.Early[p][m] = math.Round(math.Min(8, math.Max(2, percentile(v, 0.9)))*10) / 10
			}
		}
	}
	for k, v := range arr {
		if len(v) >= 5 {
			out.Arrive[k] = math.Round(median(v)*10) / 10
		}
	}
	lrnMu.Lock()
	lrn = out
	lrnMu.Unlock()
	if b, err := json.Marshal(out); err == nil {
		_ = writeFileAtomic(learnPath(), b)
	}
}

// arrivalMs: learned rider arrival for an order (0: not learned yet).
func arrivalMs(p string, s int64) int64 {
	if s == 0 {
		return 0
	}
	st := time.UnixMilli(s)
	hh := halfHour(st)
	lrnMu.Lock()
	defer lrnMu.Unlock()
	for _, k := range []string{fmt.Sprintf("%s|%d|%d", p, st.Weekday(), hh), fmt.Sprintf("%s||%d", p, hh), p} {
		if v, ok := lrn.Arrive[k]; ok {
			return s + int64(v*60000)
		}
	}
	return 0
}

// pruneOldData: days older than pcKeepDays leave this PC (orders, KOT day files, Prep Live days).
func pruneOldData(now time.Time) {
	cut := now.AddDate(0, 0, -pcKeepDays)
	for _, dir := range []struct{ path, ext string }{{ordersDir(), ".json"}, {filepath.Join(dataDir, "days"), ".jsonl"}, {prepDaysDir(), ".txt"}} {
		es, err := os.ReadDir(dir.path)
		if err != nil {
			continue
		}
		for _, e := range es {
			name := e.Name()
			if !strings.HasSuffix(name, dir.ext) {
				continue
			}
			if t, err := time.ParseInLocation("2006-01-02", strings.TrimSuffix(name, dir.ext), time.Local); err == nil && t.Before(cut) {
				_ = os.Remove(filepath.Join(dir.path, name))
			}
		}
	}
}

// ---- /console/api/ops ---------------------------------------------------------------------------

type opsRow struct {
	Kot    int64    `json:"k"`
	ID     string   `json:"id,omitempty"`
	P      string   `json:"p,omitempty"` // S Z O X, "" not online
	Brand  string   `json:"b,omitempty"`
	Slot   string   `json:"sl,omitempty"`
	Type   int      `json:"t"`
	Items  []string `json:"it,omitempty"`
	Punch  int64    `json:"pu"`
	Print  int64    `json:"pr,omitempty"`
	Ready  int64    `json:"rd,omitempty"`
	Pick   int64    `json:"pk,omitempty"`
	By     string   `json:"by,omitempty"`
	Status string   `json:"st,omitempty"`
	Arrive int64    `json:"ar,omitempty"` // learned rider arrival
}

func itemLines(o OrderRec) []string {
	var out []string
	for _, it := range o.Items {
		if n := strings.TrimSpace(it.N); n != "" {
			out = append(out, strconv.FormatFloat(it.Q, 'f', -1, 64)+" x "+n)
		}
	}
	return out
}

func toRow(o OrderRec) opsRow {
	p := platformCode(o)
	r := opsRow{Kot: o.Kot, ID: strings.TrimSpace(o.OrderID), P: p, Brand: o.Brand, Slot: o.Slot, Type: o.Type, Items: itemLines(o),
		Punch: startMs(o), Print: o.Printed, Ready: o.Ready, Pick: o.Dispatched, By: o.ReadyBy, Status: o.Status}
	if r.Slot == "" && o.Token > 0 {
		r.Slot = strconv.Itoa(o.Token)
	}
	if p != "" && p != "X" {
		r.Arrive = arrivalMs(p, r.Punch)
	}
	return r
}

func dayRange(r *http.Request) (time.Time, time.Time, error) {
	today := time.Now()
	from, to := today, today
	var err error
	if s := r.URL.Query().Get("from"); s != "" {
		if from, err = time.ParseInLocation("2006-01-02", s, time.Local); err != nil {
			return from, to, errors.New("bad from")
		}
	}
	if s := r.URL.Query().Get("to"); s != "" {
		if to, err = time.ParseInLocation("2006-01-02", s, time.Local); err != nil {
			return from, to, errors.New("bad to")
		}
	}
	if to.Before(from) {
		from, to = to, from
	}
	if to.Sub(from) > pcKeepDays*24*time.Hour {
		from = to.AddDate(0, 0, -pcKeepDays)
	}
	return from, to, nil
}

func handleOps(w http.ResponseWriter, r *http.Request) {
	from, to, err := dayRange(r)
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	var rows []opsRow
	for d := from; !d.After(to); d = d.AddDate(0, 0, 1) {
		for _, o := range readDayOrders(d.Format("2006-01-02")) {
			if o.Status == "cancelled" || o.PrintOnly {
				continue
			}
			rows = append(rows, toRow(o))
		}
	}
	sort.Slice(rows, func(a, b int) bool { return rows[a].Punch > rows[b].Punch })
	lrnMu.Lock()
	l := lrn
	lrnMu.Unlock()
	code := outletCodeCX(getConfig().Outlet)
	writeJSON(w, map[string]any{"rows": rows, "targets": loadTargets(), "learn": l, "learnDays": learnDays, "pcKeepDays": pcKeepDays,
		"outlet": code, "outletName": outletNames[code], "outletSet": getConfig().Outlet, "now": time.Now().UnixMilli()})
}

// ---- /console/api/opsload -----------------------------------------------------------------------

type loadItem struct {
	Name    string  `json:"n"`
	Qty     float64 `json:"q"`
	Waiting float64 `json:"w"`
	AvgThen float64 `json:"a"`
	Usual   float64 `json:"u"`
	Dosa    bool    `json:"d,omitempty"`
}

func inKitchenAt(list []OrderRec, at int64) (int, float64) {
	n, dosas := 0, 0.0
	for _, o := range list {
		s := startMs(o)
		if s == 0 || s > at || at-s > 2*3600_000 || o.Status == "cancelled" {
			continue
		}
		if (o.Ready == 0 || o.Ready > at) && (o.Dispatched == 0 || o.Dispatched > at) {
			n++
			for _, it := range o.Items {
				if isDosa(it.C) || isDosa(it.N) {
					dosas += it.Q
				}
			}
		}
	}
	return n, dosas
}

func handleOpsLoad(w http.ResponseWriter, r *http.Request) {
	kot, _ := strconv.ParseInt(r.URL.Query().Get("kot"), 10, 64)
	day := r.URL.Query().Get("day")
	if _, err := time.Parse("2006-01-02", day); err != nil || kot == 0 {
		http.Error(w, "kot and day", 400)
		return
	}
	list := readDayOrders(day)
	var me *OrderRec
	for i := range list {
		if list[i].Kot == kot {
			me = &list[i]
		}
	}
	if me == nil {
		http.Error(w, "not found", 404)
		return
	}
	at := startMs(*me)
	t0 := time.UnixMilli(at)
	clock := func(d time.Time) int64 { // the same clock time on day d
		return time.Date(d.Year(), d.Month(), d.Day(), t0.Hour(), t0.Minute(), t0.Second(), 0, time.Local).UnixMilli()
	}
	// this window
	vol, plat := 0, map[string]int{}
	items := map[string]*loadItem{}
	sum := map[string][]float64{}
	var order []string
	for _, o := range list {
		s := startMs(o)
		if o.Status == "cancelled" || s < at-loadWindowMs || s > at+loadWindowMs {
			continue
		}
		vol++
		p := platformCode(o)
		if p == "" {
			p = "D"
		}
		plat[p]++
		k := kptMs(o)
		for _, it := range o.Items {
			n := strings.TrimSpace(it.N)
			if n == "" {
				continue
			}
			if items[n] == nil {
				items[n] = &loadItem{Name: n, Dosa: isDosa(it.C) || isDosa(n)}
				order = append(order, n)
			}
			items[n].Qty += it.Q
			if k > 0 {
				sum[n] = append(sum[n], float64(k)/60000)
			}
		}
	}
	inK, dosaQ := inKitchenAt(list, at)
	for _, o := range list { // waiting at punch, per item
		s := startMs(o)
		if s == 0 || s > at || at-s > 2*3600_000 || o.Kot == me.Kot || o.Status == "cancelled" {
			continue
		}
		if o.Ready > at || (o.Ready == 0 && (o.Dispatched == 0 || o.Dispatched > at)) {
			for _, it := range o.Items {
				if li := items[strings.TrimSpace(it.N)]; li != nil {
					li.Waiting += it.Q
				}
			}
		}
	}
	// usual: same clock time over the last 4 weeks (same weekday first), and item prep at that half hour
	var usualVol, usualInK, usualKpt []float64
	usualItem := map[string][]float64{}
	hh := halfHour(t0)
	for i := 1; i <= 28; i++ {
		d := t0.AddDate(0, 0, -i)
		past := readDayOrders(d.Format("2006-01-02"))
		if len(past) == 0 {
			continue
		}
		c := clock(d)
		if d.Weekday() == t0.Weekday() {
			n := 0
			for _, o := range past {
				if s := startMs(o); s >= c-loadWindowMs && s <= c+loadWindowMs && o.Status != "cancelled" {
					n++
				}
			}
			usualVol = append(usualVol, float64(n))
			k, _ := inKitchenAt(past, c)
			usualInK = append(usualInK, float64(k))
		}
		for _, o := range past {
			s := startMs(o)
			k := kptMs(o)
			if s == 0 || k == 0 || halfHour(time.UnixMilli(s)) != hh {
				continue
			}
			usualKpt = append(usualKpt, float64(k)/60000)
			for _, it := range o.Items {
				if n := strings.TrimSpace(it.N); items[n] != nil {
					usualItem[n] = append(usualItem[n], float64(k)/60000)
				}
			}
		}
	}
	var rows []loadItem
	for _, n := range order {
		li := items[n]
		li.AvgThen = round1(avgF(sum[n]))
		li.Usual = round1(avgF(usualItem[n]))
		rows = append(rows, *li)
	}
	sort.SliceStable(rows, func(a, b int) bool { return rows[a].Qty > rows[b].Qty })
	writeJSON(w, map[string]any{"from": at - loadWindowMs, "to": at + loadWindowMs, "vol": vol, "plat": plat,
		"usualVol": round1(avgF(usualVol)), "inKitchen": inK, "usualInKitchen": round1(avgF(usualInK)),
		"usualKpt": round1(avgF(usualKpt)), "dosaQueue": dosaQ, "items": rows})
}

func avgF(v []float64) float64 {
	if len(v) == 0 {
		return 0
	}
	s := 0.0
	for _, x := range v {
		s += x
	}
	return s / float64(len(v))
}

// ---- /console/api/cx: the ratings sheet for this outlet -----------------------------------------

var (
	cxGetMu   sync.Mutex
	cxGetAt   time.Time
	cxGetBody []byte
	cxGetErr  string
)

func handleCX(w http.ResponseWriter, r *http.Request) {
	c := getConfig()
	link := strings.TrimSpace(c.CXURL)
	code := outletCodeCX(c.Outlet)
	if link == "" {
		writeJSON(w, map[string]any{"ok": false, "msg": "Ratings sheet not set up (Data › Ratings sheet).", "outlet": code})
		return
	}
	cxGetMu.Lock()
	defer cxGetMu.Unlock()
	if cxGetBody == nil || time.Since(cxGetAt) > 5*time.Minute || r.URL.Query().Get("fresh") == "1" && time.Since(cxGetAt) > 30*time.Second {
		body, err := fetchCX(link)
		cxGetAt = time.Now()
		if err != nil {
			cxGetErr = err.Error()
			log.Printf("ratings sheet: %v", err)
		} else {
			cxGetBody, cxGetErr = body, ""
		}
	}
	if cxGetBody == nil {
		writeJSON(w, map[string]any{"ok": false, "msg": "Can't read the ratings sheet: " + cxGetErr, "outlet": code})
		return
	}
	var in struct {
		Updated string   `json:"updated"`
		Cols    []string `json:"cols"`
		Rows    [][]any  `json:"rows"`
	}
	if err := json.Unmarshal(cxGetBody, &in); err != nil {
		writeJSON(w, map[string]any{"ok": false, "msg": "The ratings sheet sent something unexpected.", "outlet": code})
		return
	}
	oc := -1
	for i, n := range in.Cols {
		if n == "outlet" {
			oc = i
		}
	}
	var rows [][]any
	for _, row := range in.Rows {
		if oc >= 0 && code != "" {
			if s, _ := row[oc].(string); !strings.EqualFold(strings.Fields(s + " -")[0], code) {
				continue
			}
		}
		rows = append(rows, row)
	}
	writeJSON(w, map[string]any{"ok": true, "updated": in.Updated, "cols": in.Cols, "rows": rows, "outlet": code,
		"stale": cxGetErr != "", "fetched": cxGetAt.UnixMilli()})
}

func fetchCX(link string) ([]byte, error) {
	u := link
	if strings.Contains(u, "?") {
		u += "&format=json"
	} else {
		u += "?format=json"
	}
	resp, err := cxClient.Get(u) // Google answers with a redirect to the result; Get follows it
	if err != nil {
		return nil, errors.New("internet?")
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 20<<20))
	if err != nil {
		return nil, err
	}
	if len(b) == 0 || b[0] != '{' {
		return nil, errors.New("not the sheet's data (is the web app deployed for anyone?)")
	}
	return b, nil
}
