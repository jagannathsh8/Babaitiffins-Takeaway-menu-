package main

// The Babai Console window (http://localhost:8790/console): three areas.
//
//	Bridge Print (open to all): Overview (the Bridge Print screen), Dispatch, Printers & rules,
//	                            Print timing, Alerts, Apps
//	Hub (PIN):                  Status, Scanners & rider, Customers' page, This PC & updates, Settings
//	Data (PIN):                 Metrics, Prep Live, Indent planner, Connections
//
// Page files are in console/; the JSON it uses is below.

import (
	"embed"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

//go:embed console/*
var consoleFiles embed.FS

var (
	winMu   sync.Mutex
	winPing time.Time
)

func consoleRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/console", localOnly(serveConsoleFile("index.html")))
	mux.HandleFunc("/console/", localOnly(handleConsoleAsset))
	mux.HandleFunc("/console/qrcode.min.js", serveConsoleFile("qrcode.min.js")) // also for /apps on the Wi-Fi
	mux.HandleFunc("/apps", handleApps)
	mux.HandleFunc("/scanner/", handleScannerApp)
	mux.HandleFunc("/window/ping", localOnly(handleWindowPing))
	mux.HandleFunc("/window/state", localOnly(handleWindowState))

	// Bridge Print area: no PIN
	mux.HandleFunc("/console/api/state", localOnly(handleConsoleState))
	mux.HandleFunc("/console/api/dispatch", localOnly(handleDispatch))
	mux.HandleFunc("/console/api/ready", localOnly(handleConsoleReady))
	mux.HandleFunc("/console/api/jobs", localOnly(handleJobsTiming))
	mux.HandleFunc("/console/api/alerts", localOnly(handleAlerts))
	mux.HandleFunc("/console/api/rules", localOnly(handleRules))
	mux.HandleFunc("/console/api/menu", localOnly(handleMenu))
	mux.HandleFunc("/console/api/testprint", localOnly(handleTestPrint))
	mux.HandleFunc("/console/api/apps", localOnly(handleAppsJSON))
	// Hub and Data areas: PIN
	mux.HandleFunc("/console/api/metrics", localOnly(pinnedAPI(handleMetrics)))
	mux.HandleFunc("/console/api/window", localOnly(pinnedAPI(handleWindowSetting)))
	mux.HandleFunc("/console/api/sources", localOnly(pinnedAPI(handleSources)))
}

func handleRoot(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		http.Redirect(w, r, "/apps", http.StatusFound) // phones on the Wi-Fi: the app downloads
		return
	}
	http.Redirect(w, r, "/console", http.StatusFound)
}

func serveConsoleFile(name string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		b, err := consoleFiles.ReadFile("console/" + name)
		if err != nil {
			http.NotFound(w, r)
			return
		}
		switch {
		case strings.HasSuffix(name, ".html"):
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
		case strings.HasSuffix(name, ".js"):
			w.Header().Set("Content-Type", "text/javascript; charset=utf-8")
		case strings.HasSuffix(name, ".css"):
			w.Header().Set("Content-Type", "text/css; charset=utf-8")
		}
		w.Header().Set("Cache-Control", "no-store")
		w.Write(b)
	}
}

func handleConsoleAsset(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/console/")
	if name == "" || strings.Contains(name, "/") || strings.Contains(name, "..") {
		http.NotFound(w, r)
		return
	}
	serveConsoleFile(name)(w, r)
}

// pinnedAPI: JSON 401 until the PIN is entered (Hub and Data areas).
func pinnedAPI(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !unlocked(r) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			io.WriteString(w, `{"ok":false,"locked":true,"msg":"Enter the PIN."}`)
			return
		}
		h(w, r)
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(v)
}

func keepWindow() bool {
	prMu.Lock()
	defer prMu.Unlock()
	return prOpts.Window == nil || *prOpts.Window
}

func handleWindowPing(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	winMu.Lock()
	winPing = time.Now()
	winMu.Unlock()
	reply(w, true, "")
}

func handleWindowState(w http.ResponseWriter, r *http.Request) {
	winMu.Lock()
	age := int64(-1)
	if !winPing.IsZero() {
		age = time.Since(winPing).Milliseconds()
	}
	winMu.Unlock()
	writeJSON(w, map[string]any{"keep": keepWindow(), "age": age, "status": consoleLine()})
}

// consoleLine: one line for the tray.
func consoleLine() string {
	ps := printStatus()
	parts := []string{}
	if on, _ := ps["receiving"].(bool); on {
		parts = append(parts, fmt.Sprintf("Printing on · next token %v", ps["nextToken"]))
	} else if en, _ := ps["enabled"].(bool); en {
		parts = append(parts, "⚠ Not receiving prints")
	}
	if !ppFresh() && getConfig().Server != "" {
		parts = append(parts, "⚠ Can't read Petpooja")
	}
	if u, _ := ps["uncertain"].(int); u > 0 {
		parts = append(parts, fmt.Sprintf("%d need attention", u))
	}
	if len(parts) == 0 {
		return "Running"
	}
	return strings.Join(parts, " · ")
}

func handleWindowSetting(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct {
		Keep  *bool `json:"keep"`
		Sound *bool `json:"sound"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 512)).Decode(&in)
	prMu.Lock()
	if in.Keep != nil {
		prOpts.Window = in.Keep
	}
	if in.Sound != nil {
		prOpts.Sound = in.Sound
	}
	err := savePrintOptionsLocked()
	prMu.Unlock()
	if err != nil {
		reply(w, false, err.Error())
		return
	}
	reply(w, true, "Saved.")
}

func handleConsoleState(w http.ResponseWriter, r *http.Request) {
	c := getConfig()
	ps := printStatus()
	ppOK := ppFresh()
	ok, ocrMsg := ocrState()
	var addrs []string
	for _, a := range localIPv4s() {
		if !strings.HasPrefix(a, "127.") {
			addrs = append(addrs, a)
		}
	}
	al := alertsSnapshot()
	recentBad := 0
	for _, a := range al {
		if a.Level == "bad" && time.Since(time.UnixMilli(a.At)) < 30*time.Minute {
			recentBad++
		}
	}
	snd := true
	prMu.Lock()
	if prOpts.Sound != nil {
		snd = *prOpts.Sound
	}
	prMu.Unlock()
	writeJSON(w, map[string]any{
		"version": version, "outlet": c.Outlet, "addrs": addrs, "unlocked": unlocked(r),
		"print": ps, "ppOK": ppOK, "ppSet": c.Server != "", "cloudOK": configured() && cloudOK(), "cloudSet": configured(),
		"ocr": ok, "ocrMsg": ocrMsg, "alertsRecent": recentBad, "lastAlert": firstAlert(al),
		"rollback": rollbackNote(), "keepWindow": keepWindow(), "sound": snd, "service": supervised(),
		"now": time.Now().UnixMilli(),
	})
}

func firstAlert(a []Alert) any {
	if len(a) == 0 {
		return nil
	}
	return a[0]
}

// ---- Dispatch ---------------------------------------------------------------------------

// handleDispatch: every order in Petpooja's kitchen list (read by the Hub part), exactly as
// Petpooja has it, with the token of its print added (this Console's prints, or Bridge Print on
// another PC). An order whose KOT never printed is shown all the same, without a token. Only
// while Petpooja can't be read are the prints shown on their own.
func handleDispatch(w http.ResponseWriter, r *http.Request) {
	since := time.Now().Add(-16 * time.Hour)
	writeJSON(w, map[string]any{"orders": dispatchOrders(since, time.Now()), "now": time.Now().UnixMilli(), "skew": clockSkew(), "ppOK": ppFresh()})
}

const printOnlyKeep = 3 * time.Hour

func dispatchOrders(since, now time.Time) []OrderRec {
	list := ordersSnapshot(since) // every order in Petpooja's kitchen list, like the KDS
	byID := map[string]int{}
	for i := range list {
		o := &list[i]
		if o.Token == 0 && o.OrderID != "" {
			o.Slot = slotFor(o.OrderID)
		}
		if d := digitsOnly(o.OrderID); len(d) >= 6 {
			byID[d] = i
		}
	}
	if ppFresh() {
		sort.Slice(list, func(a, b int) bool { return list[a].Seen > list[b].Seen })
		return list // Petpooja's list is the screen; prints only add their token
	}
	// Petpooja can't be read right now: show the prints so the counter isn't blind
	prMu.Lock()
	jobs := make([]Job, 0, len(prJobs))
	for _, j := range prJobs {
		jobs = append(jobs, *j)
	}
	prMu.Unlock()
	for _, j := range jobs {
		recv := time.UnixMilli(j.Recv)
		if j.Kot != 0 || j.Recv == 0 || recv.Before(since) {
			continue // shown as its Petpooja order
		}
		m := ReceiptMeta{}
		if j.Metadata != nil {
			m = *j.Metadata
		}
		if d := digitsOnly(m.OrderID); len(d) >= 6 {
			if i, ok := byID[d]; ok { // the same order is in Petpooja's list: just its token
				if list[i].Token == 0 {
					list[i].Token = j.Token
				}
				continue
			}
		}
		o := OrderRec{JobID: j.ID, Token: j.Token, OrderID: m.OrderID, Brand: firstNonEmpty(m.DisplayBrand, m.Brand, j.Customization.TokenLabel),
			Punched: j.Recv, Seen: j.Recv, Printed: j.Recv + int64(j.PrintMs), PrintMs: j.PrintMs, Status: "preparing", PrintOnly: true}
		if j.Petpooja != nil && j.Petpooja.Status == "ready" {
			o.Status = "ready"
		}
		if j.ReadyAt > 0 {
			o.Status, o.Ready, o.ReadyBy = "ready", j.ReadyAt, j.ReadyBy
		}
		if o.Status == "preparing" && now.Sub(recv) > printOnlyKeep {
			continue // an old print that never showed up in Petpooja: off the screen after 3 h
		}
		list = append(list, o)
	}
	sort.Slice(list, func(a, b int) bool { return list[a].Seen > list[b].Seen })
	return list
}

// handleConsoleReady: the Dispatch screen's "Food ready" (marks it in Petpooja, as the KDS does).
func handleConsoleReady(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct {
		Kot int64  `json:"kot"`
		Job string `json:"job"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 512)).Decode(&in)
	if in.Kot == 0 && in.Job != "" {
		// a print Petpooja's list doesn't have: marked on this screen (and the token TV)
		prMu.Lock()
		j := findJobLocked(in.Job)
		if j != nil {
			t := true
			c := JobLive{}
			if j.Petpooja != nil {
				c = *j.Petpooja
			}
			c.Status, c.Active, c.ReadyAt = "ready", &t, nowISO()
			j.Petpooja, j.ReadyAt, j.ReadyBy = &c, time.Now().UnixMilli(), "Console"
			saveJobLocked(j)
		}
		prMu.Unlock()
		if j == nil {
			reply(w, false, "This print is no longer on this PC.")
			return
		}
		reply(w, true, "Food ready (this order isn't in Petpooja's kitchen list, so it is marked here only).")
		return
	}
	c := getConfig()
	ppMu.Lock()
	kots, okAt, code := ppKots, ppOKAt, syncCode
	ppMu.Unlock()
	if c.Server == "" || okAt.IsZero() || time.Since(okAt) > scanFreshFor || code == "" {
		reply(w, false, "Can't reach Petpooja right now.")
		return
	}
	var k *Kot
	for i := range kots {
		if kots[i].ID == in.Kot {
			k = &kots[i]
		}
	}
	if k == nil {
		reply(w, false, "This order is no longer in Petpooja's kitchen list.")
		return
	}
	if kotReady(*k) {
		noteReadyBy(k.ID, "Console")
		reply(w, true, "Already Food Ready.")
		return
	}
	if err := sendFoodReady(serverBase(c.Server), code, *k); err != nil {
		reply(w, false, "Petpooja didn't accept it: "+err.Error())
		return
	}
	now := time.Now()
	noteMarkedReady(k.ID, now)
	noteReadyBy(k.ID, "Console")
	wakePoll()
	reply(w, true, "Food ready.")
}

// ---- Print timing, alerts ----------------------------------------------------------------

func handleJobsTiming(w http.ResponseWriter, r *http.Request) {
	prMu.Lock()
	n := len(prJobs)
	out := make([]Job, 0, 200)
	for i := n - 1; i >= 0 && len(out) < 200; i-- {
		out = append(out, *prJobs[i])
	}
	prMu.Unlock()
	writeJSON(w, map[string]any{"jobs": out, "print": printStatus()})
}

func handleAlerts(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]any{"alerts": alertsSnapshot()})
}

// ---- Printers & rules ----------------------------------------------------------------------

func handleRules(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		prMu.Lock()
		o := prOpts
		s := prSettings
		prMu.Unlock()
		writeJSON(w, map[string]any{"options": o, "main": s.PrinterName, "printers": allPrinters()})
		return
	}
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct {
		Rules        []Rule                  `json:"rules"`
		Main         string                  `json:"main"`
		Printers     map[string]PrinterSetup `json:"printers"`
		Lan          []LanPrinter            `json:"lan"`
		IdentifyWait int                     `json:"identifyWait"`
		Enabled      *bool                   `json:"enabled"`
		MainPrinter  *string                 `json:"mainPrinter"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 256<<10)).Decode(&in); err != nil {
		reply(w, false, "Couldn't read the rules.")
		return
	}
	if in.Main != "always" && in.Main != "unmatched" && in.Main != "never" {
		in.Main = "always"
	}
	if in.IdentifyWait < 200 || in.IdentifyWait > 5000 {
		in.IdentifyWait = 1500
	}
	for i := range in.Rules {
		r := &in.Rules[i]
		if r.ID == "" {
			r.ID = newUUID()[:8]
		}
		r.Name = strings.TrimSpace(r.Name)
		if r.Name == "" {
			r.Name = fmt.Sprintf("Rule %d", i+1)
		}
		if r.Copies < 1 {
			r.Copies = 1
		}
		if r.Copies > 5 {
			r.Copies = 5
		}
		if r.Part != "items" {
			r.Part = "bill"
		}
		if r.Action != "skip" {
			r.Action = "print"
		}
		switch r.ItemMode {
		case "", "any", "none", "only":
		default:
			r.ItemMode = ""
		}
	}
	for i := range in.Lan {
		l := &in.Lan[i]
		l.Name = strings.TrimSpace(l.Name)
		if l.Port == 0 {
			l.Port = 9100
		}
		if l.Name == "" || !isLanIP(l.IP) || l.Port < 1 || l.Port > 65535 {
			reply(w, false, "Network printer: give a name and its shop-network IP address (e.g. 192.168.0.50).")
			return
		}
	}
	prMu.Lock()
	prOpts.Rules, prOpts.Main, prOpts.Lan, prOpts.IdentifyWait = in.Rules, in.Main, in.Lan, in.IdentifyWait
	if in.Printers != nil {
		prOpts.Printers = in.Printers
	}
	if in.Enabled != nil {
		prOpts.Enabled = *in.Enabled
	}
	if in.MainPrinter != nil {
		prSettings.PrinterName = *in.MainPrinter
		_ = savePrintSettingsLocked()
	}
	err := savePrintOptionsLocked()
	prMu.Unlock()
	if err != nil {
		reply(w, false, "Couldn't save: "+err.Error())
		return
	}
	go drainQueued()
	reply(w, true, "Saved. New KOTs use these rules.")
}

// handleMenu: order types, brands and the menu (categories with their items) for the rule chips.
func handleMenu(w http.ResponseWriter, r *http.Request) {
	cats := map[string]map[string]bool{}
	add := func(c, n string) {
		c, n = strings.TrimSpace(c), strings.TrimSpace(n)
		if n == "" {
			return
		}
		if c == "" {
			c = "Other"
		}
		if cats[c] == nil {
			cats[c] = map[string]bool{}
		}
		cats[c][n] = true
	}
	ppMu.Lock()
	for _, s := range ppStock {
		add(s.Category, s.Name)
	}
	for _, k := range ppKots {
		for _, it := range k.Items {
			add(it.C, it.N)
		}
	}
	ppMu.Unlock()
	brands := map[string]bool{"Swiggy": true, "Zomato": true, "Ownly": true, "Toing By Swiggy": true, "Go Self Service": true, "Magicpin": true}
	for _, o := range ordersSnapshot(time.Now().AddDate(0, 0, -2)) {
		for _, b := range o.Brands {
			if b != "" && b != "Online" && b != "Delivery" && b != "Pick up" && b != "Dine in" {
				brands[b] = true
			}
		}
	}
	type cat struct {
		Name  string   `json:"name"`
		Items []string `json:"items"`
	}
	var out []cat
	for c, m := range cats {
		var items []string
		for n := range m {
			items = append(items, n)
		}
		sort.Strings(items)
		out = append(out, cat{c, items})
	}
	sort.Slice(out, func(a, b int) bool { return out[a].Name < out[b].Name })
	var bl []string
	for b := range brands {
		bl = append(bl, b)
	}
	sort.Strings(bl)
	writeJSON(w, map[string]any{"cats": out, "brands": bl, "types": []map[string]any{{"id": 1, "name": "Delivery"}, {"id": 2, "name": "Pick up"}, {"id": 3, "name": "Dine in"}}})
}

func handleTestPrint(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Printer string }
	_ = json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&in)
	if in.Printer == "" {
		reply(w, false, "Choose a printer.")
		return
	}
	if p := printerProblem(in.Printer); p != "" {
		reply(w, false, printerLabel(in.Printer)+": "+p)
		return
	}
	slip := []byte{27, 64, 27, 97, 1, 27, 69, 1, 29, 33, 17}
	slip = append(slip, []byte("Babai Console\n")...)
	slip = append(slip, 29, 33, 0, 27, 69, 0)
	slip = append(slip, []byte("Test print\n"+printerLabel(in.Printer)+"\n"+time.Now().Format("02 Jan 15:04:05")+"\n\n\n\n")...)
	slip = append(slip, 29, 86, 66, 0)
	start := time.Now()
	unlock := lockPrinter(in.Printer)
	_, err := sendOnce(in.Printer, slip)
	unlock()
	if err != nil {
		reply(w, false, err.Error())
		return
	}
	reply(w, true, fmt.Sprintf("Sent to %s in %d ms. Check the paper.", printerLabel(in.Printer), time.Since(start).Milliseconds()))
}

func handleAppsJSON(w http.ResponseWriter, r *http.Request) {
	var links []string
	for _, a := range localIPv4s() {
		if !strings.HasPrefix(a, "127.") {
			links = append(links, fmt.Sprintf("http://%s:%d/apps", a, consolePort()))
		}
	}
	writeJSON(w, map[string]any{"apps": appsList(), "links": links})
}

// ---- Data -------------------------------------------------------------------------------

type brandRow struct {
	Brand     string  `json:"brand"`
	Orders    int     `json:"orders"`
	PrepMin   float64 `json:"prepMin"`
	ReadyMin  float64 `json:"readyMin"`
	Over15    int     `json:"over15"`
	PrintMsMd int     `json:"printMs"`
}

func median(v []float64) float64 {
	if len(v) == 0 {
		return 0
	}
	s := append([]float64(nil), v...)
	sort.Float64s(s)
	return s[len(s)/2]
}

// handleMetrics: one day's numbers from the order records (punched → ready → dispatched).
func handleMetrics(w http.ResponseWriter, r *http.Request) {
	day := r.URL.Query().Get("day")
	if day == "" {
		day = time.Now().Format("2006-01-02")
	}
	list := ordersOfDay(day)
	var prep, wait, printMs []float64
	hourDosa := map[int][]float64{}
	byBrand := map[string]*struct {
		n, over         int
		prep, wait, pms []float64
	}{}
	total := 0
	for _, o := range list {
		if o.Status == "cancelled" {
			continue
		}
		total++
		key := brandKeyOf(o)
		b := byBrand[key]
		if b == nil {
			b = &struct {
				n, over         int
				prep, wait, pms []float64
			}{}
			byBrand[key] = b
		}
		b.n++
		start := o.Punched
		if start == 0 || (o.Seen > 0 && o.Seen-start > 3600_000) {
			start = o.Seen // Petpooja's clock far off: use when this PC saw it
		}
		if o.Ready > 0 && start > 0 && o.Ready > start {
			m := float64(o.Ready-start) / 60000
			if m < 240 {
				prep = append(prep, m)
				b.prep = append(b.prep, m)
				if m > 15 {
					b.over++
				}
				dosa := false
				for _, it := range o.Items {
					if isDosa(it.C) || isDosa(it.N) {
						dosa = true
					}
				}
				if dosa {
					h := time.UnixMilli(start).Hour()
					hourDosa[h] = append(hourDosa[h], m)
				}
			}
		}
		if o.Dispatched > 0 && o.Ready > 0 && o.Dispatched >= o.Ready {
			m := float64(o.Dispatched-o.Ready) / 60000
			if m < 240 {
				wait = append(wait, m)
				b.wait = append(b.wait, m)
			}
		}
		if o.PrintMs > 0 {
			printMs = append(printMs, float64(o.PrintMs))
			b.pms = append(b.pms, float64(o.PrintMs))
		}
	}
	var rows []brandRow
	for k, b := range byBrand {
		rows = append(rows, brandRow{Brand: k, Orders: b.n, PrepMin: median(b.prep), ReadyMin: median(b.wait), Over15: b.over, PrintMsMd: int(median(b.pms))})
	}
	sort.Slice(rows, func(a, b int) bool { return rows[a].Orders > rows[b].Orders })
	type hourRow struct {
		Hour int     `json:"h"`
		Min  float64 `json:"min"`
		N    int     `json:"n"`
	}
	var hours []hourRow
	for h, v := range hourDosa {
		hours = append(hours, hourRow{h, median(v), len(v)})
	}
	sort.Slice(hours, func(a, b int) bool { return hours[a].Hour < hours[b].Hour })
	// days that have records (for the day picker)
	var days []string
	if es, err := os.ReadDir(ordersDir()); err == nil {
		for _, e := range es {
			if strings.HasSuffix(e.Name(), ".json") {
				days = append(days, strings.TrimSuffix(e.Name(), ".json"))
			}
		}
	}
	sort.Sort(sort.Reverse(sort.StringSlice(days)))
	writeJSON(w, map[string]any{"day": day, "days": days, "orders": total, "prepMin": median(prep), "waitMin": median(wait),
		"printMs": median(printMs), "brands": rows, "hours": hours, "dosaN": func() int {
			n := 0
			for _, v := range hourDosa {
				n += len(v)
			}
			return n
		}()})
}

// handleSources: where the Console's data comes from and goes to (Data → Connections).
func handleSources(w http.ResponseWriter, r *http.Request) {
	c := getConfig()
	pp := petpoojaStatus()
	ago, _ := pp["ppOkAgo"].(int)
	files := 0
	if es, err := os.ReadDir(ordersDir()); err == nil {
		files = len(es)
	}
	type src struct {
		Name   string `json:"name"`
		What   string `json:"what"`
		State  string `json:"state"`
		OK     bool   `json:"ok"`
		Detail string `json:"detail"`
	}
	list := []src{
		{"Petpooja (shop network)", "Kitchen orders, items, Food Ready; open orders for the token TV", map[bool]string{true: fmt.Sprintf("connected · read %d s ago", ago), false: "not connected"}[ago >= 0 && ago < 30], ago >= 0 && ago < 30, c.Server},
		{"Petpooja KOT prints", "Every KOT print, given a token (port 9100)", map[bool]string{true: "receiving", false: "not receiving"}[localPrinting()], localPrinting(), "this PC, port 9100"},
		{"Firebase", "Customers' dosa page, scanner phones over the internet, planner sales", map[bool]string{true: "connected", false: "not set up"}[configured() && cloudOK()], configured() && cloudOK(), strings.TrimPrefix(c.DB, "https://")},
		{"Indent planner", "Sales by hour sent for the planner (babaidosa.netlify.app/indent)", planStatus(), strings.HasPrefix(planStatus(), "sent"), ""},
		{"Order records on this PC", "Punched, printed, ready, dispatched for every KOT (kept 120 days)", fmt.Sprintf("%d days saved", files), true, filepath.Join(printDir(), "orders")},
	}
	writeJSON(w, map[string]any{"sources": list})
}
