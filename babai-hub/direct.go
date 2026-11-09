package main

// Direct KOT printing: the Console prints its own slip (kotslip.go) for each new KOT it reads from
// Petpooja, instead of (or before) Petpooja's slip that arrives through Bridge Print.
//
//	Mode "bridge"  Bridge Print only: as before, the Console prints nothing itself.
//	Mode "console" Console only: the Console prints; Petpooja's copy is not printed.
//	Mode "both"    Console + Bridge backup: the Console prints; Petpooja's copy prints only if the
//	               Console's slip didn't go out within 10 s.
//
// Only the chosen order types (Delivery / Online always, plus pick up, dine in, self service);
// others print through Bridge Print as before. A Petpooja slip the Console can't match to an
// order always prints (it may be anything). Petpooja's own reprint (2 minutes or more after the
// Console's slip) prints too.
//
// Printers: the main printer (the whole order, or only what no line takes) and lines by category
// or item, each with up to 3 backup printers tried in order. Every Console slip is a print job
// like Petpooja's, with the same token, so Dispatch, the rider screen and the metrics see it.

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

type DirectLine struct {
	Kind    string   `json:"kind"`   // "cat" or "item"
	Values  []string `json:"values"` // category or item names
	Printer string   `json:"printer"`
	Backups []string `json:"backups,omitempty"` // up to 3, in order
	Off     bool     `json:"off,omitempty"`     // switched off (the setup is kept)
	Skip    []string `json:"skip,omitempty"`    // order types it doesn't print for: D P I S
}

// SubMain: bills that contain any of its categories or items print whole (with the slot) here
// instead of on the main printer. Checked in order; a bill goes to the first it matches.
type SubMain struct {
	Cats    []string `json:"cats,omitempty"`
	Items   []string `json:"items,omitempty"`
	Printer string   `json:"printer"`
	Backups []string `json:"backups,omitempty"`
	Off     bool     `json:"off,omitempty"`
	Skip    []string `json:"skip,omitempty"`
}

// MainPrinter: prints every bill no sub main takes (each main its own copy).
type MainPrinter struct {
	Printer string   `json:"printer"`
	Backups []string `json:"backups,omitempty"`
	Off     bool     `json:"off,omitempty"`
	Skip    []string `json:"skip,omitempty"`
}

type DirectCfg struct {
	Mode    string        `json:"mode"`            // bridge / console / both
	Types   []string      `json:"types"`           // order types the Console prints: D P I S
	TypesV2 bool          `json:"typesV2"`         // Types includes D (before 4.11 delivery was always on)
	Mains   []MainPrinter `json:"mains,omitempty"` // 4.11: several main printers
	// before 4.11: one main printer (read once, moved into Mains)
	Main        string       `json:"main,omitempty"`
	MainBackups []string     `json:"mainBackups,omitempty"`
	MainRest    bool         `json:"mainRest,omitempty"` // main prints only what no line takes (off: the whole order)
	MainOff     bool         `json:"mainOff,omitempty"`
	MainSkip    []string     `json:"mainSkip,omitempty"`
	Subs        []SubMain    `json:"subs,omitempty"`
	Lines       []DirectLine `json:"lines,omitempty"`
}

func cleanSkip(l []string) []string {
	var out []string
	for _, x := range l {
		if (x == "D" || x == "P" || x == "I" || x == "S") && !containsStr(out, x) {
			out = append(out, x)
		}
	}
	return out
}

func cleanNames(l []string) []string {
	var v []string
	for _, x := range l {
		if x = strings.TrimSpace(x); x != "" && !containsStr(v, x) && len(v) < 100 {
			v = append(v, x)
		}
	}
	return v
}

// printsFor: a printer that is on and not switched off for this order type.
func printsFor(off bool, skip []string, ch string) bool { return !off && !containsStr(skip, ch) }

const (
	directBackupWait = 10 * time.Second // "both": Petpooja's slip waits this long for the Console's
	directReprintGap = 2 * time.Minute  // Petpooja's slip this long after the Console's: a reprint
	directNewWithin  = 5 * time.Minute  // a KOT first seen this long ago is not printed any more
)

var (
	dirMu        sync.Mutex
	dirCfg       = DirectCfg{Mode: "bridge", Types: []string{"D"}, TypesV2: true}
	dirLoaded    bool
	dirPrinted   = map[int64]string{} // KOT -> its Console job id
	dirCancelled = map[int64]bool{}   // KOT -> CANCELLED slip printed
	dirBusy      = map[int64]bool{}
)

func directPath() string { return filepath.Join(printDir(), "direct.json") }

func directConfig() DirectCfg {
	dirMu.Lock()
	defer dirMu.Unlock()
	if !dirLoaded {
		dirLoaded = true
		var c DirectCfg
		if readJSONFile(directPath(), &c) {
			dirCfg = cleanDirect(c)
		}
	}
	return dirCfg
}

func cleanDirect(c DirectCfg) DirectCfg {
	if c.Mode != "console" && c.Mode != "both" {
		c.Mode = "bridge"
	}
	if !c.TypesV2 { // before 4.11 Delivery / Online was always printed
		c.Types, c.TypesV2 = append([]string{"D"}, c.Types...), true
	}
	c.Types = cleanSkip(c.Types)
	if len(c.Mains) == 0 && c.Main != "" {
		c.Mains = []MainPrinter{{Printer: c.Main, Backups: c.MainBackups, Off: c.MainOff, Skip: c.MainSkip}}
	}
	c.Main, c.MainBackups, c.MainOff, c.MainSkip = "", nil, false, nil
	var mains []MainPrinter
	for _, m := range c.Mains {
		m.Backups, m.Skip = cleanBackups(m.Printer, m.Backups), cleanSkip(m.Skip)
		if len(mains) < 10 {
			mains = append(mains, m)
		}
	}
	c.Mains = mains
	var subs []SubMain
	for _, sm := range c.Subs {
		sm.Cats, sm.Items, sm.Skip = cleanNames(sm.Cats), cleanNames(sm.Items), cleanSkip(sm.Skip)
		sm.Backups = cleanBackups(sm.Printer, sm.Backups)
		if len(subs) < 10 {
			subs = append(subs, sm)
		}
	}
	c.Subs = subs
	var lines []DirectLine
	for _, l := range c.Lines {
		if l.Kind != "item" {
			l.Kind = "cat"
		}
		var v []string
		for _, x := range l.Values {
			if x = strings.TrimSpace(x); x != "" && !containsStr(v, x) && len(v) < 100 {
				v = append(v, x)
			}
		}
		l.Values = v
		l.Backups = cleanBackups(l.Printer, l.Backups)
		l.Skip = cleanSkip(l.Skip)
		if len(lines) < 30 {
			lines = append(lines, l)
		}
	}
	c.Lines = lines
	return c
}

func cleanBackups(main string, b []string) []string {
	var out []string
	for _, x := range b {
		if x = strings.TrimSpace(x); x != "" && x != main && !containsStr(out, x) && len(out) < 3 {
			out = append(out, x)
		}
	}
	return out
}

func containsStr(l []string, s string) bool {
	for _, x := range l {
		if strings.EqualFold(x, s) {
			return true
		}
	}
	return false
}

// directWants: the Console prints this KOT itself: its order type is chosen and a main or sub
// main printer takes its bill (else Bridge Print prints it, e.g. every main switched off).
func directWants(c DirectCfg, k Kot) bool {
	if c.Mode == "bridge" || !containsStr(c.Types, barkChannel(k)) {
		return false
	}
	return len(billPrinters(c, k)) > 0
}

// billPrinters: where the whole bill (with the slot and the OTP) prints: the first sub main the
// bill matches, else every main printer (each its own copy).
func billPrinters(c DirectCfg, k Kot) []directSlip {
	ch := barkChannel(k)
	for i, sm := range c.Subs {
		if sm.Printer == "" || !printsFor(sm.Off, sm.Skip, ch) {
			continue
		}
		for _, it := range k.Items {
			if it.S >= 0 && (containsStr(sm.Items, it.N) || containsStr(sm.Cats, strings.TrimSpace(it.C))) {
				return []directSlip{{name: fmt.Sprintf("Sub main %d", i+1), bill: true, printers: append([]string{sm.Printer}, sm.Backups...)}}
			}
		}
	}
	var out []directSlip
	for i, m := range c.Mains {
		if m.Printer != "" && printsFor(m.Off, m.Skip, ch) {
			out = append(out, directSlip{name: fmt.Sprintf("Main %d", i+1), bill: true, printers: append([]string{m.Printer}, m.Backups...)})
		}
	}
	return out
}

// ---- what goes to which printer ----------------------------------------------------------------

type directSlip struct {
	bill     bool   // a main / sub main slip: slot and OTP
	name     string // "Main 1", "Sub main 1" or the line's name
	station  string // shown on a line's slip
	printers []string
	items    []KItem
	also     []KItem
}

func lineName(l DirectLine) string {
	if len(l.Values) == 0 {
		return "Line"
	}
	return strings.Join(l.Values, " + ")
}

// directPlan: the slips for a KOT. An item goes to the first item line naming it, else the
// first category line with its category; the main printer gets the whole order (or the rest).
func directPlan(c DirectCfg, k Kot) []directSlip {
	var items []KItem
	for _, it := range k.Items {
		if it.S >= 0 { // not cancelled
			items = append(items, it)
		}
	}
	taken := make([]int, len(items)) // line index + 1
	for i, it := range items {
		for li, l := range c.Lines {
			if l.Kind == "item" && containsStr(l.Values, it.N) {
				taken[i] = li + 1
				break
			}
		}
		if taken[i] != 0 {
			continue
		}
		for li, l := range c.Lines {
			if l.Kind == "cat" && containsStr(l.Values, strings.TrimSpace(it.C)) {
				taken[i] = li + 1
				break
			}
		}
	}
	var out []directSlip
	ch := barkChannel(k)
	var mine []KItem
	for i, it := range items {
		if !c.MainRest || taken[i] == 0 || !lineOn(c.Lines[taken[i]-1], ch) {
			mine = append(mine, it)
		}
	}
	if len(mine) > 0 {
		for _, bill := range billPrinters(c, k) {
			bill.items = mine
			out = append(out, bill)
		}
	}
	for li, l := range c.Lines {
		if !lineOn(l, ch) {
			continue
		}
		var mine, rest []KItem
		for i, it := range items {
			if taken[i] == li+1 {
				mine = append(mine, it)
			} else {
				rest = append(rest, it)
			}
		}
		if len(mine) > 0 {
			out = append(out, directSlip{name: lineName(l), station: lineName(l), printers: append([]string{l.Printer}, l.Backups...), items: mine, also: rest})
		}
	}
	return out
}

func lineOn(l DirectLine, ch string) bool { return l.Printer != "" && printsFor(l.Off, l.Skip, ch) }

func slipFor(k Kot, token int, s directSlip, cancel bool) slipData {
	return slipForOTP(k, token, s, cancel, "", "")
}

func slipForOTP(k Kot, token int, s directSlip, cancel bool, otp, otpName string) slipData {
	ch := barkChannel(k)
	d := slipData{Label: "TOKEN", Token: token, Cancel: cancel, Station: s.station, KotNo: kotNumber(k), Note: k.Note}
	if k.Created > 0 {
		d.When = time.UnixMilli(k.Created)
	} else {
		d.When = time.Now()
	}
	switch ch {
	case "D":
		d.Label, d.Brand, d.OrderID, d.Type = "SLOT", brandOf(k), strings.TrimSpace(k.POID), "DELIVERY"
	case "P":
		d.Brand, d.Type = "Pick up", "PICK UP"
	case "S":
		d.Brand, d.Type = "Self service", "SELF SERVICE"
	default:
		d.Brand, d.Type = "Dine in", "DINE IN"
		if k.Table != "" {
			d.Brand = "Dine in · Table " + k.Table
		}
	}
	for _, it := range s.items {
		d.Items = append(d.Items, slipItem{N: it.N, Q: it.Q, A: it.A, Nt: it.Nt})
	}
	if s.bill && otp != "" {
		d.OTP, d.OTPName = otp, otpName
	}
	if s.station != "" {
		d.Token = 0 // the slot prints only on the main / sub main slip
		d.Note = ""
		var also []string
		for _, it := range s.also {
			also = append(also, it.N+" ×"+qtyStr(it.Q))
		}
		d.Also = strings.Join(also, ", ")
	}
	return d
}

func kotNumber(k Kot) string {
	if k.Token > 0 {
		return strconv.FormatInt(k.Token, 10)
	}
	s := strconv.FormatInt(k.ID, 10)
	if len(s) > 4 {
		s = s[len(s)-4:]
	}
	return s
}

var re58 = regexp.MustCompile(`(?i)(^|[^0-9])58(mm|[^0-9]|$)`)

// paperDots: 384 for 58 mm printers (by their name or driver), else 576 (80 mm).
func paperDots(name string) int {
	n := name
	for _, p := range allPrintersCached() {
		if p.Name == name {
			n += " " + p.DriverName
		}
	}
	if re58.MatchString(n) {
		return 384
	}
	return 576
}

var (
	prnCacheMu sync.Mutex
	prnCache   []PrinterInfo
	prnCacheAt time.Time
)

func allPrintersCached() []PrinterInfo {
	prnCacheMu.Lock()
	defer prnCacheMu.Unlock()
	if time.Since(prnCacheAt) > time.Minute {
		prnCache, prnCacheAt = allPrinters(), time.Now()
	}
	return prnCache
}

// ---- printing ---------------------------------------------------------------------------------

// sendChain prints data on the first printer of the list that takes it.
func sendChain(j *Job, out *PrintOut, printers []string, slip *image2bytes, recv time.Time) {
	out.Printer = printers[0]
	var lastErr error
	for i, p := range printers {
		if p == "" {
			continue
		}
		if pr := printerProblem(p); pr != "" && i < len(printers)-1 {
			addAlert("bad", p, fmt.Sprintf("%s: %s. Token %d printed on the next backup printer.", printerLabel(p), pr, j.Token), j.Token)
			continue
		}
		data := slip.forPrinter(p)
		unlock := lockPrinter(p)
		id, err := sendOnce(p, data)
		unlock()
		if err != nil {
			lastErr = err
			addAlert("bad", p, fmt.Sprintf("%s: %v. Token %d goes to the next backup printer.", printerLabel(p), err, j.Token), j.Token)
			continue
		}
		out.Printer, out.OK, out.Error = p, true, ""
		if i > 0 {
			out.Fallback, out.Wanted = true, printers[0]
		}
		out.Ms = int(time.Since(recv).Milliseconds())
		if id != 0 {
			next := ""
			if i+1 < len(printers) {
				next = printers[i+1]
			}
			go watchPrint(j.ID, out.Rule, p, next, id, data)
		}
		return
	}
	out.OK = false
	if lastErr != nil {
		out.Error = lastErr.Error()
	} else {
		out.Error = "no printer ready"
	}
	out.Ms = int(time.Since(recv).Milliseconds())
	addAlert("bad", printers[0], fmt.Sprintf("Token %d: the Console's slip was not printed (%s).", j.Token, out.Error), j.Token)
}

// image2bytes renders a slip once per paper width.
type image2bytes struct {
	d     slipData
	mu    sync.Mutex
	bytes map[int][]byte
}

func (s *image2bytes) forPrinter(p string) []byte {
	w := paperDots(p)
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.bytes == nil {
		s.bytes = map[int][]byte{}
	}
	if b := s.bytes[w]; b != nil {
		return b
	}
	b := escposSlip(renderSlip(s.d, w))
	s.bytes[w] = b
	return b
}

// directOnKots: after every Petpooja read. New KOTs of the chosen types print; a printed KOT
// that gets cancelled prints a CANCELLED slip.
func directOnKots(kots []Kot, now time.Time) {
	c := directConfig()
	if c.Mode == "bridge" {
		return
	}
	mkMu.Lock()
	first := map[int64]time.Time{}
	for _, k := range kots {
		first[k.ID] = mkFirst[k.ID]
	}
	mkMu.Unlock()
	for i := range kots {
		k := kots[i]
		if !directWants(c, k) {
			continue
		}
		dirMu.Lock()
		jobID, done := dirPrinted[k.ID]
		busy := dirBusy[k.ID]
		cancelled := dirCancelled[k.ID]
		dirMu.Unlock()
		if busy {
			continue
		}
		if done {
			if k.Status == "0" && !cancelled {
				dirMu.Lock()
				dirCancelled[k.ID] = true
				dirMu.Unlock()
				go directCancel(c, k, jobID)
			}
			continue
		}
		if k.Status == "0" || now.Sub(first[k.ID]) > directNewWithin || first[k.ID].IsZero() || consoleJobFor(k.ID) != nil {
			continue
		}
		dirMu.Lock()
		dirBusy[k.ID] = true
		dirMu.Unlock()
		go func(k Kot) {
			directPrint(c, k, now)
			dirMu.Lock()
			delete(dirBusy, k.ID)
			dirMu.Unlock()
		}(k)
	}
	// forget KOTs gone for long
	dirMu.Lock()
	if len(dirPrinted) > 2000 {
		dirPrinted, dirCancelled = map[int64]string{}, map[int64]bool{}
	}
	dirMu.Unlock()
}

// consoleJobFor: the Console's own job for a KOT (also after a restart, from the saved jobs).
func consoleJobFor(kot int64) *Job {
	prMu.Lock()
	defer prMu.Unlock()
	for i := len(prJobs) - 1; i >= 0; i-- {
		if prJobs[i].Kot == kot && prJobs[i].Source == "console" {
			return prJobs[i]
		}
	}
	return nil
}

// directPrint: one Job for the KOT (token shared with Petpooja's slip if it came first), every
// slip sent at once.
func directPrint(c DirectCfg, k Kot, seen time.Time) {
	plan := directPlan(c, k)
	if len(plan) == 0 {
		return
	}
	prMu.Lock()
	token := 0
	for i := len(prJobs) - 1; i >= 0; i-- {
		if prJobs[i].Kot == k.ID && prJobs[i].Source != "console" {
			token = prJobs[i].Token // Petpooja's slip already came: the same token
			break
		}
	}
	if token == 0 {
		var next counterState
		token, next = takeToken(prCounter, prSettings.TokenLimit)
		prCounter = next
		_ = saveCounterLocked()
	}
	j := &Job{ID: newUUID(), Token: token, CreatedAt: time.Now().UTC().Format("2006-01-02T15:04:05.000Z"), Source: "console",
		Status: "sending", Attempts: 1, Recv: seen.UnixMilli(), IdentBy: "petpooja"}
	fillJobFromKot(j, &k)
	saveJobLocked(j)
	prJobs = append(prJobs, j)
	prLastKOT = time.Now()
	prMu.Unlock()
	dirMu.Lock()
	dirPrinted[k.ID] = j.ID
	dirMu.Unlock()
	orderPrinted(&k, j)
	directSend(j, k, plan, false)
}

func directSend(j *Job, k Kot, plan []directSlip, cancel bool) {
	recv := time.UnixMilli(j.Recv)
	// the rider's OTP on the bill slips (kept on the job for reprints and the cancelled slip)
	prMu.Lock()
	otp, otpName := j.OTP, j.OTPName
	prMu.Unlock()
	if otp == "" {
		if j.Attempts <= 1 && !cancel {
			otp, otpName = waitOTP(k)
		} else {
			otp, otpName = orderOTP(k)
		}
		if otp != "" {
			prMu.Lock()
			j.OTP, j.OTPName = otp, otpName
			saveJobLocked(j)
			prMu.Unlock()
		}
	}
	outs := make([]PrintOut, len(plan))
	var wg sync.WaitGroup
	for i := range plan {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			outs[i] = PrintOut{Rule: plan[i].name, Part: "items", Copies: 1}
			if cancel {
				outs[i].Rule += " (cancelled)"
			}
			slip := &image2bytes{d: slipForOTP(k, j.Token, plan[i], cancel, otp, otpName)}
			sendChain(j, &outs[i], plan[i].printers, slip, recv)
		}(i)
	}
	wg.Wait()
	prMu.Lock()
	defer prMu.Unlock()
	if cancel {
		j.Outs = append(j.Outs, outs...)
		saveJobLocked(j)
		return
	}
	j.Outs = outs
	var dests, errs []string
	first := 0
	for _, o := range outs {
		dests = append(dests, printerLabel(o.Printer))
		if !o.OK {
			errs = append(errs, printerLabel(o.Printer)+": "+o.Error)
		} else if first == 0 || o.Ms < first {
			first = o.Ms
		}
	}
	j.Destination = strings.Join(dests, ", ")
	if first > 0 && j.PrintMs == 0 {
		j.PrintMs = first
	}
	if len(errs) > 0 {
		j.Status, j.Error = "uncertain", strings.Join(errs, " · ")
	} else {
		j.Status, j.SentAt, j.Error = "sent", nowISO(), ""
	}
	saveJobLocked(j)
	if j.Kot != 0 {
		go notePrinted(j.Kot, j)
	}
}

func directCancel(c DirectCfg, k Kot, jobID string) {
	prMu.Lock()
	j := findJobLocked(jobID)
	prMu.Unlock()
	if j == nil {
		return
	}
	k.Items = j.Items // the dishes it had when printed
	directSend(j, k, directPlan(c, k), true)
	log.Printf("print: KOT %d cancelled, CANCELLED slip printed", k.ID)
}

// directReprint: Reprint / Retry of a Console job (from the Dispatch or timing screens).
func directReprint(j *Job) {
	k := Kot{ID: j.Kot, Token: j.KotNo, OrderType: j.OrderType, Items: j.Items, Created: j.Recv}
	ppMu.Lock()
	for _, x := range ppKots {
		if x.ID == j.Kot {
			k = x
		}
	}
	ppMu.Unlock()
	prMu.Lock()
	j.Status, j.Attempts = "sending", j.Attempts+1
	saveJobLocked(j)
	prMu.Unlock()
	directSend(j, k, directPlan(directConfig(), k), false)
}

// directBridgeHold: called for Petpooja's slip (Bridge Print) once it is matched to its order.
// Returns false when it must not print (the Console prints this order).
func directBridgeHold(j *Job) bool {
	c := directConfig()
	if c.Mode == "bridge" || j.Kot == 0 {
		return true
	}
	var k Kot
	ppMu.Lock()
	found := false
	for _, x := range ppKots {
		if x.ID == j.Kot {
			k, found = x, true
		}
	}
	ppMu.Unlock()
	if !found {
		k = Kot{ID: j.Kot, OrderType: j.OrderType, Platform: j.Platform, Online: j.OrderType == 1 || j.Platform != "" && j.Platform != "Online"}
	}
	if !directWants(c, k) {
		return true
	}
	recv := time.UnixMilli(j.Recv)
	deadline := recv.Add(directBackupWait)
	for {
		cj := consoleJobFor(j.Kot)
		if cj != nil {
			created, _ := time.Parse("2006-01-02T15:04:05.000Z", cj.CreatedAt)
			if !created.IsZero() && recv.Sub(created) > directReprintGap {
				return true // Petpooja's own reprint, well after the Console's slip
			}
			prMu.Lock()
			st := cj.Status
			prMu.Unlock()
			if st == "sent" {
				directSkipBridge(j, cj, "Not printed (the Console printed this order)")
				return false
			}
			if st == "uncertain" && c.Mode == "both" {
				directTakeToken(j, cj) // the backup slip shows the order's slot
				if found {
					orderPrinted(&k, j)
				}
				addAlert("bad", "", fmt.Sprintf("Token %d: the Console's slip failed, Petpooja's slip printed instead.", cj.Token), cj.Token)
				return true
			}
		}
		if c.Mode == "console" && time.Now().After(deadline.Add(20*time.Second)) {
			directSkipBridge(j, cj, "Not printed (Console only)")
			return false // Console only: Petpooja's slip never prints for these orders
		}
		if c.Mode == "both" && time.Now().After(deadline) {
			if cj == nil || cj.Status != "sent" {
				if cj != nil {
					directTakeToken(j, cj)
					if found {
						orderPrinted(&k, j)
					}
				}
				addAlert("bad", "", fmt.Sprintf("Token %d: no Console slip within 10 s, Petpooja's slip printed instead.", j.Token), j.Token)
				return true
			}
		}
		time.Sleep(250 * time.Millisecond)
	}
}

// directSkipBridge: Petpooja's slip is not printed; it takes the Console job's token (and gives
// its own back when it was the last one taken).
// directTakeToken: Petpooja's slip takes the Console job's token (and gives its own back when it
// was the last one taken).
func directTakeToken(j, cj *Job) {
	prMu.Lock()
	defer prMu.Unlock()
	takeTokenLocked(j, cj)
}

func takeTokenLocked(j, cj *Job) {
	if cj == nil || cj.Token == j.Token {
		return
	}
	if _, after := takeToken(counterState{Next: j.Token}, prSettings.TokenLimit); after.Next == prCounter.Next {
		prCounter.Next = j.Token
		_ = saveCounterLocked()
	}
	j.Token = cj.Token
	saveJobLocked(j)
}

func directSkipBridge(j, cj *Job, why string) {
	prMu.Lock()
	defer prMu.Unlock()
	takeTokenLocked(j, cj)
	j.Status, j.SentAt, j.Error = "sent", nowISO(), ""
	j.Destination = why
	saveJobLocked(j)
	if cj != nil {
		cjCopy := cj
		go func() {
			var k Kot
			ppMu.Lock()
			for _, x := range ppKots {
				if x.ID == cjCopy.Kot {
					k = x
				}
			}
			ppMu.Unlock()
			if k.ID != 0 {
				orderPrinted(&k, cjCopy)
			}
		}()
	}
}

// ---- the settings page ---------------------------------------------------------------------

func handleDirect(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		var names []map[string]any
		for _, p := range allPrintersCached() {
			names = append(names, map[string]any{"name": p.Name, "label": printerLabel(p.Name), "problem": printerProblem(p.Name), "mm": map[bool]int{true: 58, false: 80}[paperDots(p.Name) == 384]})
		}
		cats, items := []string{}, []string{}
		for c, its := range menuSeenSnapshot() {
			cats = append(cats, c)
			items = append(items, its...)
		}
		sortStrings(cats)
		sortStrings(items)
		writeJSON(w, map[string]any{"cfg": directConfig(), "printers": names, "cats": cats, "items": items, "today": directToday()})
		return
	}
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var c DirectCfg
	if json.NewDecoder(io.LimitReader(r.Body, 128<<10)).Decode(&c) != nil {
		reply(w, false, "Couldn't read the settings.")
		return
	}
	c = cleanDirect(c)
	hasBill := len(c.Subs) > 0
	for _, m := range c.Mains {
		hasBill = hasBill || m.Printer != ""
	}
	if c.Mode != "bridge" && !hasBill {
		reply(w, false, "Choose a main printer first.")
		return
	}
	if c.Mode != "bridge" && len(c.Types) == 0 {
		reply(w, false, "Choose at least one order type (Delivery / Online, Pick up, Dine in or Self service).")
		return
	}
	if err := writeJSONFile(directPath(), c, true); err != nil {
		reply(w, false, "Couldn't save: "+err.Error())
		return
	}
	dirMu.Lock()
	dirCfg, dirLoaded = c, true
	dirMu.Unlock()
	log.Printf("print: KOT printing set to %s", c.Mode)
	reply(w, true, "Saved.")
}

// handleDirectTest: POST {printer} prints a sample slip; GET ?w=576|384 is its picture.
func handleDirectTest(w http.ResponseWriter, r *http.Request) {
	sample := slipData{Label: "SLOT", Token: 16, When: time.Now(), KotNo: "1874", Brand: "Test print", OrderID: "5000715116389", Type: "DELIVERY",
		Items: []slipItem{{N: "Masala Dosa", Q: 2, A: []KAddon{{N: "Breakfast Sambar (100ml)", Q: 2}}}, {N: "Filter Coffee", Q: 1, Nt: "less sugar"}},
		Note:  "This is a test slip from the Babai Console"}
	if r.Method == http.MethodGet {
		wd := 576
		if r.URL.Query().Get("w") == "384" {
			wd = 384
		}
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(slipPNG(renderSlip(sample, wd)))
		return
	}
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
	data := escposSlip(renderSlip(sample, paperDots(in.Printer)))
	unlock := lockPrinter(in.Printer)
	_, err := sendOnce(in.Printer, data)
	unlock()
	if err != nil {
		reply(w, false, err.Error())
		return
	}
	reply(w, true, "Test slip sent to "+printerLabel(in.Printer)+".")
}

// directToday: slips printed today by the Console and by Bridge Print, and backups used.
func directToday() map[string]int {
	prMu.Lock()
	defer prMu.Unlock()
	day := time.Now().Format("2006-01-02")
	out := map[string]int{"console": 0, "bridge": 0, "skipped": 0}
	for _, j := range prJobs {
		if time.UnixMilli(j.Recv).Format("2006-01-02") != day {
			continue
		}
		switch {
		case j.Source == "console":
			out["console"]++
		case strings.HasPrefix(j.Destination, "Not printed (the Console") || strings.HasPrefix(j.Destination, "Not printed (Console only"):
			out["skipped"]++
		default:
			out["bridge"]++
		}
	}
	return out
}

func sortStrings(l []string) {
	for i := 1; i < len(l); i++ {
		for j := i; j > 0 && strings.ToLower(l[j]) < strings.ToLower(l[j-1]); j-- {
			l[j], l[j-1] = l[j-1], l[j]
		}
	}
}
