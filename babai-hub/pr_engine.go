package main

// Babai Console print engine: receiving Petpooja's prints on TCP 9100 and printing them.
//
//	Petpooja "Bridge print" queue --TCP 9100--> save to disk --> token --> which order? (Petpooja
//	list, OCR) --> rules --> printer (fallback if it has a problem)
//
// Each print gets the next token in the cycle the moment it arrives and is saved before anything
// else happens, so nothing is lost if the PC stops. Unsent jobs are kept; sent ones are removed
// after two hours, like Bridge Print.

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	receivePort  = 9100
	maxJobBytes  = 8 << 20
	jobLifetime  = 2 * time.Minute
	jobIdleLimit = 30 * time.Second
)

var (
	busyJobs sync.Map // job id -> true while being identified / printed
	rxMu     sync.Mutex
	rxOn     bool
	rxErr    string
)

func newUUID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	h := hex.EncodeToString(b)
	return h[0:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

// startPrinting starts the print receiver, Bridge Print's pages and the housekeeping.
func startPrinting() {
	loadPrint()
	ocrStart()
	go receiverLoop()
	go bridgeServers()
	go petpoojaLiveLoop()
	go func() {
		for {
			pruneHistory(time.Now())
			pruneOrders(time.Now())
			saveOrdersMaybe(false)
			time.Sleep(time.Minute)
		}
	}()
	go func() {
		for {
			time.Sleep(5 * time.Second)
			saveOrdersMaybe(false)
		}
	}()
	// jobs that were waiting when the Console stopped
	prMu.Lock()
	var redo []*Job
	for _, j := range prJobs {
		if j.Status == "reading" || j.Status == "queued" {
			redo = append(redo, j)
		}
	}
	prMu.Unlock()
	for _, j := range redo {
		if raw, err := jobBytes(j); err == nil {
			go processJob(j, raw)
		}
	}
}

// receiverLoop keeps TCP 9100 open while printing is on (retrying if the port is busy, e.g.
// the old Bridge Print is still running).
func receiverLoop() {
	warned := false
	for {
		prMu.Lock()
		on := prOpts.Enabled
		prMu.Unlock()
		if !on {
			setRx(false, "Printing is switched off on this PC.")
			time.Sleep(3 * time.Second)
			continue
		}
		ln, err := net.Listen("tcp", fmt.Sprintf(":%d", receivePort))
		if err != nil {
			if !warned {
				log.Printf("print: can't open port %d: %v", receivePort, err)
				warned = true
			}
			setRx(false, fmt.Sprintf("Port %d is in use by another program (is the old Bridge Print still running?).", receivePort))
			time.Sleep(3 * time.Second)
			continue
		}
		warned = false
		setRx(true, "")
		log.Printf("print: receiving on TCP %d", receivePort)
		stop := make(chan struct{})
		go func() {
			for {
				prMu.Lock()
				on := prOpts.Enabled
				prMu.Unlock()
				if !on {
					ln.Close()
					return
				}
				select {
				case <-stop:
					return
				case <-time.After(2 * time.Second):
				}
			}
		}()
		sem := make(chan struct{}, 16)
		for {
			c, err := ln.Accept()
			if err != nil {
				break
			}
			select {
			case sem <- struct{}{}:
				go func() { receiveOne(c); <-sem }()
			default:
				c.Close()
			}
		}
		close(stop)
		ln.Close()
		setRx(false, "")
	}
}

func setRx(on bool, msg string) {
	rxMu.Lock()
	rxOn, rxErr = on, msg
	rxMu.Unlock()
}

// receiveOne: one TCP connection = one complete print job (never split on cut bytes or pauses).
func receiveOne(c net.Conn) {
	defer c.Close()
	prMu.Lock()
	prRecv++
	prMu.Unlock()
	defer func() {
		prMu.Lock()
		prRecv--
		prMu.Unlock()
	}()
	start := time.Now()
	var buf []byte
	chunk := make([]byte, 64<<10)
	for {
		if time.Since(start) > jobLifetime {
			noteRxErr("A connection exceeded 2 minutes; incomplete data was discarded.")
			return
		}
		_ = c.SetReadDeadline(time.Now().Add(jobIdleLimit))
		n, err := c.Read(chunk)
		if n > 0 {
			if len(buf)+n > maxJobBytes {
				noteRxErr("A print job exceeded 8 MB and was discarded.")
				return
			}
			buf = append(buf, chunk[:n]...)
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				noteRxErr("A connection stayed open without data for 30 seconds; incomplete data was discarded.")
			} else {
				noteRxErr("Incoming connection failed: " + err.Error())
			}
			return
		}
	}
	if len(buf) == 0 {
		return
	}
	src := c.RemoteAddr().String()
	if h, _, err := net.SplitHostPort(src); err == nil {
		src = h
	}
	if _, err := capture(buf, src, start); err != nil {
		noteRxErr("Unable to save incoming job: " + err.Error())
	}
}

func noteRxErr(s string) {
	prMu.Lock()
	prLastErr = s
	prMu.Unlock()
	log.Printf("print: %s", s)
}

// capture saves the print, gives it its token and starts it on its way.
func capture(raw []byte, source string, recv time.Time) (*Job, error) {
	prMu.Lock()
	token, next := takeToken(prCounter, prSettings.TokenLimit)
	prCounter = next
	if err := saveCounterLocked(); err != nil {
		prLastErr = "Could not save the token number: " + err.Error()
	}
	s := prSettings
	j := &Job{ID: newUUID(), Token: token, CreatedAt: recv.UTC().Format("2006-01-02T15:04:05.000Z"), Source: source,
		Bytes: len(raw), Status: "reading", Recv: recv.UnixMilli(),
		Customization: Customization{Heading: s.Heading, TokenLabel: s.TokenLabel, TokenEnabled: s.TokenEnabled, AutoSource: s.AutoSource, ShowOrderID: s.ShowOrderID}}
	if err := os.WriteFile(filepath.Join(jobDir(), j.ID+".bin"), raw, 0o600); err != nil {
		prMu.Unlock()
		return nil, err
	}
	saveJobLocked(j)
	prJobs = append(prJobs, j)
	prLastKOT = time.Now()
	prMu.Unlock()
	go processJob(j, raw)
	return j, nil
}

// processJob: which order, then print.
func processJob(j *Job, raw []byte) {
	if _, busy := busyJobs.LoadOrStore(j.ID, true); busy {
		return
	}
	defer busyJobs.Delete(j.ID)
	prMu.Lock()
	needIdent := j.Status == "reading" || j.Metadata == nil
	wait := time.Duration(prOpts.IdentifyWait) * time.Millisecond
	prMu.Unlock()
	if needIdent {
		kot, meta, by := identify(j, raw, wait)
		prMu.Lock()
		m := meta
		j.Metadata, j.IdentBy, j.IdentMs = &m, by, int(time.Since(time.UnixMilli(j.Recv)).Milliseconds())
		if kot != nil {
			fillJobFromKot(j, kot)
		}
		if j.Status == "reading" {
			j.Status = "queued"
		}
		saveJobLocked(j)
		prMu.Unlock()
		if kot != nil {
			orderPrinted(kot, j)
		}
	}
	printJob(j, raw)
}

func fillJobFromKot(j *Job, k *Kot) {
	j.Kot, j.KotNo, j.OrderType, j.Platform, j.Items = k.ID, k.Token, k.OrderType, brandOf(*k), k.Items
	j.Brands = factsFromKot(k).Brands
}

// linkJob: an order found after the slip printed (OCR came late).
func linkJob(j *Job, k *Kot, by string) {
	prMu.Lock()
	if j.Kot == 0 {
		fillJobFromKot(j, k)
		if j.IdentBy == "" || j.IdentBy == "none" {
			j.IdentBy = by
		}
		saveJobLocked(j)
	}
	prMu.Unlock()
	orderPrinted(k, j)
}

func jobFacts(j *Job) OrderFacts {
	if j.Kot == 0 {
		return OrderFacts{}
	}
	return OrderFacts{Known: true, OrderType: j.OrderType, Brands: j.Brands, Items: j.Items}
}

// printJob sends every slip for the job (in parallel to different printers).
func printJob(j *Job, raw []byte) {
	prMu.Lock()
	if j.Status != "queued" {
		prMu.Unlock()
		return
	}
	s, o := prSettings, prOpts
	if !s.AutoPrint {
		prMu.Unlock()
		return // waits until Auto print is on again
	}
	plan := planPrints(j, raw, jobFacts(j), s, o)
	if len(plan) == 0 {
		if mainSet(s) || len(o.Rules) > 0 {
			j.Status, j.SentAt, j.Destination, j.Error = "sent", nowISO(), "Not printed (rule)", ""
			saveJobLocked(j)
		}
		prMu.Unlock()
		return // no printer chosen yet: waits, like Bridge Print
	}
	j.Status, j.Error = "sending", ""
	j.Attempts++
	saveJobLocked(j)
	prMu.Unlock()

	recv := time.UnixMilli(j.Recv)
	if j.Attempts > 1 {
		recv = time.Now() // reprint: time from the button
	}
	outs := make([]PrintOut, len(plan))
	var wg sync.WaitGroup
	for i := range plan {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			outs[i] = plan[i].out
			deliver(j, &outs[i], plan[i].printer, plan[i].fallback, plan[i].data, recv)
		}(i)
	}
	wg.Wait()

	prMu.Lock()
	defer prMu.Unlock()
	j.Outs = outs
	var dests, errs []string
	first := 0
	for _, o := range outs {
		dests = append(dests, printerLabel(o.Printer))
		if !o.OK {
			errs = append(errs, printerLabel(o.Printer)+": "+o.Error)
		}
		if o.OK && (first == 0 || o.Ms < first) {
			first = o.Ms
		}
	}
	j.Destination = strings.Join(dests, ", ")
	if j.Attempts == 1 && first > 0 {
		j.PrintMs = first
	}
	if len(errs) > 0 {
		j.Status, j.Error = "uncertain", strings.Join(errs, " · ")
	} else {
		j.Status, j.SentAt = "sent", nowISO()
	}
	saveJobLocked(j)
	if j.Kot != 0 {
		notePrinted(j.Kot, j)
	}
}

func mainSet(s PrintSettings) bool {
	if s.OutputMode == "lan" {
		return s.PrinterIP != ""
	}
	return s.PrinterName != ""
}

// drainQueued starts every waiting job (after a settings change).
func drainQueued() {
	prMu.Lock()
	var q []*Job
	for _, j := range prJobs {
		if j.Status == "queued" {
			q = append(q, j)
		}
	}
	prMu.Unlock()
	sort.SliceStable(q, func(a, b int) bool { return q[a].Recv < q[b].Recv })
	for _, j := range q {
		if raw, err := jobBytes(j); err == nil {
			go processJob(j, raw)
		}
	}
}

// requeue: Retry (uncertain) or Reprint (sent / uncertain). The token stays the same.
func requeue(id string, allowed ...string) (bool, string) {
	prMu.Lock()
	j := findJobLocked(id)
	if j == nil {
		prMu.Unlock()
		return false, "Order not found."
	}
	ok := false
	for _, a := range allowed {
		if j.Status == a {
			ok = true
		}
	}
	if !ok {
		prMu.Unlock()
		return false, ""
	}
	j.Status, j.Error = "queued", ""
	saveJobLocked(j)
	prMu.Unlock()
	if raw, err := jobBytes(j); err == nil {
		go processJob(j, raw)
	}
	return true, ""
}

// printStatus for the Console pages.
func printStatus() map[string]any {
	rxMu.Lock()
	on, msg := rxOn, rxErr
	rxMu.Unlock()
	prMu.Lock()
	defer prMu.Unlock()
	q, u := 0, 0
	var times []int
	for _, j := range prJobs {
		switch j.Status {
		case "queued", "reading", "sending":
			q++
		case "uncertain":
			u++
		}
		if j.PrintMs > 0 {
			times = append(times, j.PrintMs)
		}
	}
	sort.Ints(times)
	med := 0
	if len(times) > 0 {
		med = times[len(times)/2]
	}
	last := int64(0)
	if !prLastKOT.IsZero() {
		last = prLastKOT.UnixMilli()
	}
	return map[string]any{"receiving": on, "rxMsg": msg, "queued": q, "uncertain": u, "nextToken": prCounter.Next,
		"tokenLimit": prSettings.TokenLimit, "printer": prSettings.PrinterName, "autoPrint": prSettings.AutoPrint,
		"medianMs": med, "lastKot": last, "enabled": prOpts.Enabled, "lastError": prLastErr}
}

func consolePort() int {
	if p := getConfig().Port; p != 0 {
		return p
	}
	return defaultPort
}

// localPrinting: this Console receives Petpooja's prints (Bridge Print built in).
func localPrinting() bool {
	rxMu.Lock()
	defer rxMu.Unlock()
	return rxOn
}

// localSlot: the token of a platform order printed by this Console (order digits).
func localSlot(d string) string {
	prMu.Lock()
	defer prMu.Unlock()
	for i := len(prJobs) - 1; i >= 0; i-- {
		j := prJobs[i]
		if j.Metadata == nil {
			continue
		}
		k := digitsOnly(j.Metadata.OrderID)
		if len(k) >= 4 && (k == d || (len(k) >= 8 && len(d) >= 8 && (strings.HasSuffix(k, d) || strings.HasSuffix(d, k)))) {
			return itoa(j.Token)
		}
	}
	// older prints (jobs leave after 2 h): the order records keep the token
	ordMu.Lock()
	defer ordMu.Unlock()
	for _, o := range orders {
		if o.Token > 0 && digitsOnly(o.OrderID) == d {
			return itoa(o.Token)
		}
	}
	return ""
}

// slotForKot: the token of a KOT: the print linked to it, else by its order number.
func slotForKot(id int64, poid string) string {
	prMu.Lock()
	for i := len(prJobs) - 1; i >= 0; i-- {
		if prJobs[i].Kot == id {
			t := prJobs[i].Token
			prMu.Unlock()
			return itoa(t)
		}
	}
	prMu.Unlock()
	ordMu.Lock()
	if o := orders[id]; o != nil && o.Token > 0 {
		t := o.Token
		ordMu.Unlock()
		return itoa(t)
	}
	ordMu.Unlock()
	return slotFor(poid)
}
