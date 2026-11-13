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
	"net"
	"net/http"
	"path/filepath"
	"regexp"
	"sort"
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
	Chans   []string `json:"chans,omitempty"`   // online channels it prints (none: every channel)
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
	Chans   []string `json:"chans,omitempty"`
}

// MainPrinter: prints every bill no sub main takes (each main its own copy).
type MainPrinter struct {
	Printer string   `json:"printer"`
	Backups []string `json:"backups,omitempty"`
	Off     bool     `json:"off,omitempty"`
	Skip    []string `json:"skip,omitempty"`
	Chans   []string `json:"chans,omitempty"`
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
	ExtraChans  []string     `json:"extraChans,omitempty"` // channels added by hand (before their first order)
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

// chanOf: the online channel of a KOT, as Petpooja names it ("Swiggy", "Go Self Serve" ...).
func chanOf(k Kot) string { return strings.TrimSpace(brandOf(k)) }

// printsKot: a printer prints this KOT: on, its order type ticked and, for an online order, its
// channel ticked (no channels chosen: every channel).
func printsKot(off bool, skip, chans []string, k Kot) bool {
	ch := barkChannel(k)
	if !printsFor(off, skip, ch) {
		return false
	}
	return ch != "D" || len(chans) == 0 || containsStr(chans, chanOf(k))
}

// channelBlocked: the Console prints, but no printer is set for this order (its channel or order
// type ticked nowhere): not printed at all, by the Console nor by Petpooja's slip.
func channelBlocked(c DirectCfg, k Kot) bool {
	return consoleOn(c) && len(directPlan(c, k)) == 0
}

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
	// 4.16: one way. "console" (the Console prints every KOT, with the safety net) or "bridge"
	// (the emergency switch: Petpooja's slips, as Bridge Print did). "both" was the safety net.
	if c.Mode == "both" {
		c.Mode = "console"
	}
	if c.Mode != "console" {
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
		m.Backups, m.Skip, m.Chans = cleanBackups(m.Printer, m.Backups), cleanSkip(m.Skip), cleanNames(m.Chans)
		if len(mains) < 10 {
			mains = append(mains, m)
		}
	}
	c.Mains = mains
	var subs []SubMain
	for _, sm := range c.Subs {
		sm.Cats, sm.Items, sm.Skip, sm.Chans = cleanNames(sm.Cats), cleanNames(sm.Items), cleanSkip(sm.Skip), cleanNames(sm.Chans)
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
		l.Skip, l.Chans = cleanSkip(l.Skip), cleanNames(l.Chans)
		if len(lines) < 30 {
			lines = append(lines, l)
		}
	}
	c.Lines = lines
	c.ExtraChans = cleanNames(c.ExtraChans)
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

// consoleOn: the Console prints the KOTs (not the emergency switch, and a printer is set up).
func consoleOn(c DirectCfg) bool {
	if c.Mode != "console" {
		return false
	}
	for _, m := range c.Mains {
		if m.Printer != "" {
			return true
		}
	}
	for _, sm := range c.Subs {
		if sm.Printer != "" {
			return true
		}
	}
	return false
}

// directWants: the Console prints this KOT: some printer is set for it (a bill or a line slip).
// An order no printer is set for is not printed at all (4.16: no Bridge Print for it either).
func directWants(c DirectCfg, k Kot) bool {
	return consoleOn(c) && len(directPlan(c, k)) > 0
}

// billPrinters: where the whole bill (with the slot and the OTP) prints: the first sub main the
// bill matches, else every main printer (each its own copy).
func billPrinters(c DirectCfg, k Kot) []directSlip {
	for i, sm := range c.Subs {
		if sm.Printer == "" || !printsKot(sm.Off, sm.Skip, sm.Chans, k) {
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
		if m.Printer != "" && printsKot(m.Off, m.Skip, m.Chans, k) {
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
	var mine []KItem
	for i, it := range items {
		if !c.MainRest || taken[i] == 0 || !lineOn(c.Lines[taken[i]-1], k) {
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
		if !lineOn(l, k) {
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

func lineOn(l DirectLine, k Kot) bool { return l.Printer != "" && printsKot(l.Off, l.Skip, l.Chans, k) }

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
		d.Label, d.Brand, d.OrderID, d.Type = "SLOT", brandOf(k), strings.TrimSpace(k.POID), "Delivery"
	case "P":
		d.Type = "Pick Up"
	case "S":
		d.Type = "Self Service"
	default:
		d.Type = "Dine In"
		if k.Table != "" {
			d.Brand = "Table : " + k.Table
		}
	}
	// the barcode: the platform's order ID on delivery (the same code the rider's app shows),
	// the KOT's own scan code on every other order
	if d.Code = digitsOnly(d.OrderID); d.Code == "" {
		d.Code = kotScanCode(k)
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

// tokWord: delivery orders have a slot, the others a token.
func tokWord(j *Job) string {
	if j.OrderType == 1 {
		return "Slot"
	}
	return "Token"
}

// sendChain prints data on the first printer of the list that takes it.
func sendChain(j *Job, out *PrintOut, printers []string, slip *image2bytes, recv time.Time) {
	out.Printer = printers[0]
	var lastErr error
	for i, p := range printers {
		if p == "" {
			continue
		}
		if pr := printerProblem(p); pr != "" && i < len(printers)-1 {
			addAlert("bad", p, fmt.Sprintf("%s: %s. %s %d printed on the next backup printer.", printerLabel(p), pr, tokWord(j), j.Token), j.Token)
			continue
		}
		data := slip.forPrinter(p)
		unlock := lockPrinter(p)
		id, err := sendOnce(p, data)
		unlock()
		if err != nil {
			lastErr = err
			addAlert("bad", p, fmt.Sprintf("%s: %v. %s %d goes to the next backup printer.", printerLabel(p), err, tokWord(j), j.Token), j.Token)
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
	addAlert("bad", printers[0], fmt.Sprintf("%s %d: the Console's slip was not printed (%s).", tokWord(j), j.Token, out.Error), j.Token)
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
		if k.Status == "0" || now.Sub(first[k.ID]) > directNewWithin || first[k.ID].IsZero() || consoleJobFor(k.ID) != nil || petpoojaPrinted(k.ID) {
			continue // already printed (also: Petpooja's slip printed it, the safety net)
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

// petpoojaPrinted: Petpooja's slip for this KOT was let through (the safety net): no second slip.
func petpoojaPrinted(kot int64) bool {
	prMu.Lock()
	defer prMu.Unlock()
	for _, j := range prJobs {
		if j.Kot == kot && j.Source != "console" && j.Token > 0 && (j.Status == "queued" || j.Status == "sending" || j.Status == "sent" || j.Status == "uncertain") &&
			!strings.HasPrefix(j.Destination, "Not printed") {
			return true
		}
	}
	return false
}

// petpoojaSlipPrinters: where Petpooja's slip prints when it has to (the safety net or the
// emergency switch): the order's bill printer, else the first main that is on, with a backup.
// nil: no Console printers set up (Bridge Print's own settings, as before).
func petpoojaSlipPrinters(j *Job) []string {
	c := directConfig()
	k := Kot{ID: j.Kot, OrderType: j.OrderType, Platform: j.Platform, Online: j.OrderType == 1 || j.Platform != "" && j.Platform != "Online"}
	ppMu.Lock()
	for _, x := range ppKots {
		if x.ID == j.Kot {
			k = x
		}
	}
	ppMu.Unlock()
	if j.Kot != 0 {
		c2 := c
		c2.Mode = "console"
		if b := billPrinters(c2, k); len(b) > 0 {
			return b[0].printers
		}
	}
	for _, m := range c.Mains {
		if m.Printer != "" && !m.Off {
			return append([]string{m.Printer}, m.Backups...)
		}
	}
	for _, m := range c.Mains {
		if m.Printer != "" {
			return append([]string{m.Printer}, m.Backups...)
		}
	}
	return nil
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

// petpoojaSlipTags: [BOLT-Urgent Order] and the like for this KOT (kept on the job for reprints):
// from Petpooja's kitchen data at once (p_urgentgen, p_bulk_order), and from Petpooja's slip.
func petpoojaSlipTags(j *Job, k Kot) []string {
	prMu.Lock()
	tags := append([]string(nil), j.Tags...)
	prMu.Unlock()
	n := len(tags)
	has := func(word string) bool {
		for _, t := range tags {
			if strings.Contains(strings.ToLower(t), word) {
				return true
			}
		}
		return false
	}
	for _, t := range slipTagsFor(k) {
		lt := strings.ToLower(t)
		if (strings.Contains(lt, "urgent") || strings.Contains(lt, "bolt")) && (has("urgent") || has("bolt")) {
			continue // the kitchen data's urgent flag already put it on
		}
		if !containsStr(tags, t) {
			tags = append(tags, t)
		}
	}
	if k.Urgent && !has("urgent") && !has("bolt") {
		if strings.Contains(strings.ToLower(brandOf(k)), "swiggy") {
			tags = append(tags, "[BOLT-Urgent Order]")
		} else {
			tags = append(tags, "[Urgent Order]")
		}
	}
	if k.Bulk && !has("bulk") {
		tags = append(tags, "[Bulk Order]")
	}
	if len(tags) != n {
		prMu.Lock()
		j.Tags = tags
		saveJobLocked(j)
		prMu.Unlock()
	}
	return tags
}

// knownChans: the online channels Petpooja's orders came from (open KOTs and the last 30 days on
// this PC), the ones printers have ticked and the ones added by hand: a channel added in Petpooja
// shows here with its first order.
func knownChans() []string {
	var out []string
	add := func(s string) {
		s = strings.TrimSpace(s)
		if s != "" && !strings.EqualFold(s, "Online") && !strings.EqualFold(s, "Delivery") && !containsStr(out, s) && len(out) < 60 {
			out = append(out, s)
		}
	}
	ppMu.Lock()
	kots := ppKots
	ppMu.Unlock()
	for _, k := range kots {
		if barkChannel(k) == "D" {
			add(chanOf(k))
		}
	}
	now := time.Now()
	for d := 0; d <= 30; d++ {
		for _, o := range readDayOrders(now.AddDate(0, 0, -d).Format("2006-01-02")) {
			if o.Type == 1 || platformCode(o) != "" {
				add(o.Brand)
			}
		}
	}
	c := directConfig()
	for _, l := range [][]string{c.ExtraChans} {
		for _, x := range l {
			add(x)
		}
	}
	for _, m := range c.Mains {
		for _, x := range m.Chans {
			add(x)
		}
	}
	for _, sm := range c.Subs {
		for _, x := range sm.Chans {
			add(x)
		}
	}
	for _, l := range c.Lines {
		for _, x := range l.Chans {
			add(x)
		}
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i]) < strings.ToLower(out[j]) })
	return out
}

// directPrint: one Job for the KOT (token shared with Petpooja's slip if it came first), every
// slip sent at once.
func directPrint(c DirectCfg, k Kot, seen time.Time) {
	plan := directPlan(c, k)
	if len(plan) == 0 {
		return
	}
	prMu.Lock()
	token := orderSlotLocked(k.ID, k.POID) // the order's slot, if it has one (Petpooja's slip printed first)
	j := &Job{ID: newUUID(), Token: token, CreatedAt: time.Now().UTC().Format("2006-01-02T15:04:05.000Z"), Source: "console",
		Status: "sending", Attempts: 1, Recv: seen.UnixMilli(), IdentBy: "petpooja"}
	fillJobFromKot(j, &k)
	j.Metadata = &ReceiptMeta{Brand: brandOf(k), OrderID: k.POID, Method: "console"}
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
	outs := make([]PrintOut, len(plan))
	var wg sync.WaitGroup
	send := func(i int, otp, otpName string, tags []string) {
		defer wg.Done()
		outs[i] = PrintOut{Rule: plan[i].name, Part: "items", Copies: 1}
		if cancel {
			outs[i].Rule += " (cancelled)"
		}
		d := slipForOTP(k, j.Token, plan[i], cancel, otp, otpName)
		d.Tags = tags
		sendChain(j, &outs[i], plan[i].printers, &image2bytes{d: d}, recv)
	}
	// line printers first, at once: they don't carry the OTP
	early := petpoojaSlipTags(j, k)
	for i := range plan {
		if !plan[i].bill {
			wg.Add(1)
			go send(i, "", "", early)
		}
	}
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
	tags := petpoojaSlipTags(j, k)
	for i := range plan {
		if plan[i].bill {
			wg.Add(1)
			go send(i, otp, otpName, tags)
		}
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
	if !consoleOn(c) {
		giveSlot(j) // the emergency switch (or nothing set up yet): Petpooja's slip, as Bridge Print
		return true
	}
	if !waitForKot(j) { // Petpooja's list can't be read, or the order never showed: print it (never lose a slip)
		giveSlot(j)
		prMu.Lock()
		t := j.Token
		prMu.Unlock()
		addAlert("bad", "", fmt.Sprintf("Slot %d: Petpooja's slip printed (its order couldn't be read from Petpooja).", t), t)
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
	if found && !directWants(c, k) { // no printer is set for this order: not printed at all
		what := map[string]string{"P": "Pick up", "I": "Dine in", "S": "Self service"}[barkChannel(k)]
		if barkChannel(k) == "D" {
			what = firstNonEmpty(chanOf(k), "Online")
		}
		directSkipBridge(j, consoleJobFor(j.Kot), "Not printed (no printer is set for "+what+")")
		return false
	}
	recv := time.UnixMilli(j.Recv)
	deadline := recv.Add(directBackupWait)
	for {
		cj := consoleJobFor(j.Kot)
		if cj != nil {
			created, _ := time.Parse("2006-01-02T15:04:05.000Z", cj.CreatedAt)
			if !created.IsZero() && recv.Sub(created) > directReprintGap {
				directSkipBridge(j, cj, "Not printed (Petpooja reprint: reprint from the Console)")
				return false
			}
			prMu.Lock()
			st := cj.Status
			prMu.Unlock()
			if st == "sent" {
				directSkipBridge(j, cj, "Not printed (the Console printed this order)")
				return false
			}
			if st == "uncertain" { // every printer and backup failed: Petpooja's slip, same slot
				directTakeToken(j, cj)
				if found {
					orderPrinted(&k, j)
				}
				addAlert("bad", "", fmt.Sprintf("Slot %d: no printer took the Console's slip, Petpooja's slip printed instead.", cj.Token), cj.Token)
				return true
			}
		}
		if time.Now().After(deadline) && (cj == nil || cj.Status != "sent") {
			if cj != nil {
				directTakeToken(j, cj)
			} else {
				giveSlot(j)
			}
			if found {
				orderPrinted(&k, j)
			}
			prMu.Lock()
			t := j.Token
			prMu.Unlock()
			addAlert("bad", "", fmt.Sprintf("Slot %d: no Console slip within 10 s, Petpooja's slip printed instead.", t), t)
			return true
		}
		time.Sleep(250 * time.Millisecond)
	}
}

// waitForKot: Petpooja's slip not matched to its order yet (OCR, a slow Petpooja list): wait up to
// 10 s for the match, or for the Console's own slip with the order ID read from it, before the
// slip may print with a slot of its own (4.15.1: the cause of "slip says 8, TV says 7").
func waitForKot(j *Job) bool {
	if !ppFresh() { // Petpooja's list can't be read: nothing to wait for (Bridge Print's way)
		prMu.Lock()
		k := j.Kot
		prMu.Unlock()
		return k != 0
	}
	for end := time.Now().Add(directBackupWait); ; time.Sleep(250 * time.Millisecond) {
		prMu.Lock()
		kot := j.Kot
		oid := ""
		if j.Metadata != nil {
			oid = digitsOnly(j.Metadata.OrderID)
		}
		var cj *Job
		if kot == 0 && len(oid) >= 6 {
			for i := len(prJobs) - 1; i >= 0; i-- {
				x := prJobs[i]
				if x.Source == "console" && x.Metadata != nil {
					d := digitsOnly(x.Metadata.OrderID)
					if len(d) >= 6 && (d == oid || len(oid) >= 8 && (strings.HasSuffix(d, oid) || strings.HasSuffix(oid, d))) {
						cj = x
						break
					}
				}
			}
			if cj != nil {
				j.Kot, j.KotNo, j.OrderType, j.Platform, j.Items, j.Brands = cj.Kot, cj.KotNo, cj.OrderType, cj.Platform, cj.Items, cj.Brands
				kot = cj.Kot
				saveJobLocked(j)
			}
		}
		prMu.Unlock()
		if kot != 0 {
			return true
		}
		if time.Now().After(end) {
			return false
		}
	}
}

// orderSlotLocked (prMu held): the slot an order already has (a print linked to it, or its order
// number on a printed slip), else the next one. One slot per order (4.16).
func orderSlotLocked(kot int64, poid string) int {
	for pass := 0; pass < 2; pass++ {
		for i := len(prJobs) - 1; i >= 0; i-- {
			x := prJobs[i]
			if x.Token <= 0 || (pass == 0) != (x.Source == "console") {
				continue
			}
			if kot != 0 && x.Kot == kot {
				return x.Token
			}
			if d := digitsOnly(poid); len(d) >= 6 && x.Metadata != nil && digitsOnly(x.Metadata.OrderID) == d {
				return x.Token
			}
		}
	}
	t, next := takeToken(prCounter, prSettings.TokenLimit)
	prCounter = next
	_ = saveCounterLocked()
	return t
}

// giveSlot: Petpooja's slip is going to print: it gets its order's slot (or the next one).
func giveSlot(j *Job) {
	prMu.Lock()
	defer prMu.Unlock()
	if j.Token > 0 {
		return
	}
	oid := ""
	if j.Metadata != nil {
		oid = j.Metadata.OrderID
	}
	j.Token = orderSlotLocked(j.Kot, oid)
	saveJobLocked(j)
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
	if j.Token > 0 {
		if _, after := takeToken(counterState{Next: j.Token}, prSettings.TokenLimit); after.Next == prCounter.Next {
			prCounter.Next = j.Token
			_ = saveCounterLocked()
		}
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
		writeJSON(w, map[string]any{"cfg": directConfig(), "printers": names, "cats": cats, "items": items, "today": directToday(), "otp": otpLastCheck(), "chans": knownChans(), "health": directHealth()})
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
	if !hasBill {
		reply(w, false, "Add a main printer first.")
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
	sample := slipData{Label: "SLOT", Token: 16, When: time.Now(), KotNo: "1874", Brand: "Test print", OrderID: "5000715116389", Code: "5000715116389", Type: "Delivery",
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
// directHealth: the Printers page's status line (4.16).
func directHealth() map[string]any {
	c := directConfig()
	day := time.Now().Format("2006-01-02")
	prMu.Lock()
	orders := map[int64]bool{}
	consoleTok := map[int64]int{}
	ppTok := map[int64]int{}
	slips, backups := 0, 0
	for _, j := range prJobs {
		if time.UnixMilli(j.Recv).Format("2006-01-02") != day {
			continue
		}
		if j.Kot != 0 {
			orders[j.Kot] = true
		}
		printed := j.Source == "console" || !strings.HasPrefix(j.Destination, "Not printed")
		if printed && (j.Status == "sent" || j.Status == "uncertain" || j.Status == "sending") {
			slips++
		}
		for _, o := range j.Outs {
			if o.Fallback {
				backups++
				break
			}
		}
		if j.Kot != 0 && j.Token > 0 {
			if j.Source == "console" {
				consoleTok[j.Kot] = j.Token
			} else if printed {
				ppTok[j.Kot] = j.Token
				if j.Source != "console" && consoleOn(c) {
					backups++ // Petpooja's slip printed: the safety net
				}
			}
		}
	}
	prMu.Unlock()
	mism := 0
	for k, t := range ppTok {
		if ct, ok := consoleTok[k]; ok && ct != t {
			mism++
		}
	}
	var bad []string
	seen := map[string]bool{}
	for _, m := range c.Mains {
		for _, p := range append([]string{m.Printer}, m.Backups...) {
			if p != "" && !seen[p] {
				seen[p] = true
				if printerProblem(p) != "" {
					bad = append(bad, printerLabel(p))
				}
			}
		}
	}
	for _, sm := range c.Subs {
		if sm.Printer != "" && !seen[sm.Printer] && printerProblem(sm.Printer) != "" {
			seen[sm.Printer] = true
			bad = append(bad, printerLabel(sm.Printer))
		}
	}
	for _, l := range c.Lines {
		if l.Printer != "" && !seen[l.Printer] && printerProblem(l.Printer) != "" {
			seen[l.Printer] = true
			bad = append(bad, printerLabel(l.Printer))
		}
	}
	return map[string]any{"pp": ppFresh(), "orders": len(orders), "slips": slips, "backups": backups, "mismatches": mism, "badPrinters": bad, "emergency": c.Mode != "console"}
}

// handleNetPrinter: POST {name, ip, port} adds a network printer, {name, del: true} removes it.
func handleNetPrinter(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost || !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct {
		Name string `json:"name"`
		IP   string `json:"ip"`
		Port int    `json:"port"`
		Del  bool   `json:"del"`
	}
	if json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&in) != nil || strings.TrimSpace(in.Name) == "" {
		reply(w, false, "Give the printer a name.")
		return
	}
	in.Name = strings.TrimSpace(in.Name)
	prMu.Lock()
	var keep []LanPrinter
	for _, l := range prOpts.Lan {
		if !strings.EqualFold(l.Name, in.Name) {
			keep = append(keep, l)
		}
	}
	if !in.Del {
		if net.ParseIP(strings.TrimSpace(in.IP)) == nil {
			prMu.Unlock()
			reply(w, false, "Give the printer's IP address, e.g. 192.168.1.50.")
			return
		}
		if in.Port <= 0 || in.Port > 65535 {
			in.Port = 9100
		}
		keep = append(keep, LanPrinter{Name: in.Name, IP: strings.TrimSpace(in.IP), Port: in.Port})
	}
	prOpts.Lan = keep
	err := savePrintOptionsLocked()
	prMu.Unlock()
	if err != nil {
		reply(w, false, "Couldn't save: "+err.Error())
		return
	}
	reply(w, true, "Saved.")
}

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
