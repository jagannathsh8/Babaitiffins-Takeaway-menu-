package main

// Babai Console print engine: which Petpooja order is this print?
//
// Petpooja prints the KOT as a picture, so the order can't be read off it directly. When a print
// arrives the Console reads Petpooja's kitchen list at once (the same local list the KDS uses,
// ~0.1 s) and takes the KOT that has just appeared and has no print yet. When two KOTs appeared
// together and two prints are waiting, they pair up in order. Windows OCR reads the slip at the
// same time (as Bridge Print did) and settles any doubt by the order number. If nothing is known
// after the wait time (1.5 s by default), the slip prints anyway, like Bridge Print would.

import (
	"sort"
	"strings"
	"sync"
	"time"
)

var (
	mkMu     sync.Mutex
	mkFirst  = map[int64]time.Time{} // KOT id -> first seen by this PC
	mkLinked = map[int64]string{}    // KOT id -> job id
	mkKots   []Kot
	mkWake   = make(chan struct{})
	pollMu   sync.Mutex
	mkPrimed bool
)

const newKotWindow = 45 * time.Second

// noteKotsForPrint is called after every read of Petpooja's kitchen list.
func noteKotsForPrint(kots []Kot, now time.Time) {
	mkMu.Lock()
	for _, k := range kots {
		if _, ok := mkFirst[k.ID]; !ok {
			if mkPrimed {
				mkFirst[k.ID] = now
			} else {
				mkFirst[k.ID] = now.Add(-time.Hour) // already open when the Console started: not a new KOT
			}
		}
	}
	mkPrimed = true
	for id, t := range mkFirst {
		if now.Sub(t) > 8*time.Hour {
			delete(mkFirst, id)
			delete(mkLinked, id)
		}
	}
	mkKots = kots
	close(mkWake)
	mkWake = make(chan struct{})
	mkMu.Unlock()
	updateJobsFromKots(kots, now)
}

// pollNow reads Petpooja right away (prints wait for it).
func pollNow() {
	c := getConfig()
	host := c.Server
	if host == "" {
		prMu.Lock()
		host = prSettings.PetpoojaIP
		prMu.Unlock()
	}
	if host == "" {
		return
	}
	pollMu.Lock()
	defer pollMu.Unlock()
	if err := pollOnce(host); err != nil {
		notePP(err)
	}

}

type matchResult struct {
	kot *Kot
	by  string
}

// ppFresh: Petpooja's kitchen list was read in the last 30 s.
func ppFresh() bool {
	ppMu.Lock()
	defer ppMu.Unlock()
	return !ppOKAt.IsZero() && time.Since(ppOKAt) < 30*time.Second
}

func kotByID(id int64) *Kot {
	for i := range mkKots {
		if mkKots[i].ID == id {
			k := mkKots[i]
			return &k
		}
	}
	return nil
}

// tryMatch (mkMu held): the KOT for job j, or nil when still unsure.
//
// The order number read off the slip (text or OCR) decides when there is one. Only when the
// slip has been read and has no order number (pick up, dine in) is the KOT matched by timing:
// a KOT that has just appeared, punched around the time of the print, of the same kind as the
// slip says (Swiggy / Zomato / Pick up …), with no print yet. Never by timing alone before the
// slip is read: not every KOT prints here (dine-in KOTs may print in the kitchen), so "the newest
// KOT" can be someone else's.
func tryMatch(j *Job, slip *ReceiptMeta, waiting []*Job) *matchResult {
	if slip == nil {
		return nil
	}
	if d := digitsOnly(slip.OrderID); len(d) >= 6 {
		best, bestN := []Kot(nil), 0
		for _, k := range mkKots {
			p := digitsOnly(k.POID)
			n := 0
			for n < len(p) && n < len(d) && p[len(p)-1-n] == d[len(d)-1-n] {
				n++
			}
			if n >= 6 || (n == len(p) && n >= 4) {
				switch {
				case n > bestN:
					best, bestN = []Kot{k}, n
				case n == bestN:
					best = append(best, k)
				}
			}
		}
		if len(best) == 1 {
			k := best[0]
			return &matchResult{&k, "ocr"}
		}
		return nil // its KOT isn't in Petpooja's list (yet): never guess another one
	}
	recv := time.UnixMilli(j.Recv)
	skew := time.Duration(clockSkew()) * time.Millisecond
	var cands []Kot
	for _, k := range mkKots {
		if _, linked := mkLinked[k.ID]; linked || k.Status == "0" {
			continue
		}
		t, ok := mkFirst[k.ID]
		if !ok || !t.After(recv.Add(-newKotWindow)) {
			continue
		}
		if k.Created > 0 {
			if d := time.UnixMilli(k.Created).Add(skew).Sub(recv); d < -5*time.Minute || d > 2*time.Minute {
				continue // punched long before (or after) this print: an old KOT showing up again
			}
		}
		if !slipFits(slip.Brand, k) {
			continue
		}
		cands = append(cands, k)
	}
	if len(cands) == 0 {
		return nil
	}
	sort.SliceStable(cands, func(a, b int) bool {
		if cands[a].Created != cands[b].Created {
			return cands[a].Created < cands[b].Created
		}
		return cands[a].ID < cands[b].ID
	})
	// prints still waiting for their order (of the same kind), oldest first
	var same []*Job
	for _, w := range waiting {
		if w.ID == j.ID || w.slipBrand == slip.Brand {
			same = append(same, w)
		}
	}
	pos := -1
	for i, w := range same {
		if w.ID == j.ID {
			pos = i
		}
	}
	if pos < 0 || len(cands) != len(same) {
		return nil
	}
	k := cands[pos]
	return &matchResult{&k, "petpooja"}
}

// slipFits: the KOT is of the kind the slip says ("" = the slip didn't say).
func slipFits(brand string, k Kot) bool {
	b := strings.ToLower(strings.TrimSpace(brand))
	if b == "" {
		return true
	}
	names := strings.ToLower(brandOf(k) + " " + k.Platform + " " + k.Channel)
	switch b {
	case "pick up":
		return k.OrderType == 2
	case "dine in":
		return k.OrderType == 3
	case "self service":
		return strings.Contains(names, "self")
	case "swiggy":
		return strings.Contains(names, "swiggy") || strings.Contains(names, "toing")
	}
	return strings.Contains(names, b) || (k.OrderType == 1 && !k.Online && b == "delivery")
}

var (
	identMu   sync.Mutex
	identWait []*Job // prints waiting for their order, in arrival order
)

func addWaiting(j *Job) {
	identMu.Lock()
	identWait = append(identWait, j)
	identMu.Unlock()
}

func dropWaiting(j *Job) {
	identMu.Lock()
	defer identMu.Unlock()
	for i, w := range identWait {
		if w == j {
			identWait = append(identWait[:i], identWait[i+1:]...)
			return
		}
	}
}

func waitingSnapshot() []*Job {
	identMu.Lock()
	defer identMu.Unlock()
	now := time.Now()
	out := make([]*Job, 0, len(identWait))
	for _, w := range identWait {
		if now.Sub(time.UnixMilli(w.Recv)) < newKotWindow {
			out = append(out, w)
		}
	}
	return out
}

// identify finds the order for a print within wait. OCR (if any) runs alongside.
func identify(j *Job, raw []byte, wait time.Duration) (*Kot, ReceiptMeta, string) {
	start := time.Now()
	images, text, _ := decodeReceipt(raw)
	textMeta := parseReceiptText(text)
	if len(images) > 0 {
		wait += 1500 * time.Millisecond // the slip is a picture: time for Windows OCR to read it
	}
	deadline := start.Add(wait)
	addWaiting(j)
	defer dropWaiting(j)

	ocrDone := make(chan ReceiptMeta, 1)
	if len(images) > 0 {
		go func() {
			m, err := ocrReceipt(j.ID, images, text)
			if err != nil {
				m = ReceiptMeta{Method: "unavailable", Warning: "Receipt text could not be read; fallback token label used."}
			}
			ocrDone <- m
		}()
	} else {
		textMeta.Method = "text"
		ocrDone <- textMeta
	}
	polled := make(chan struct{}, 1)
	go func() {
		// read Petpooja now, and again a few times while the order isn't there yet
		for _, at := range []time.Duration{0, 350 * time.Millisecond, 800 * time.Millisecond, 1400 * time.Millisecond, 2200 * time.Millisecond} {
			if d := time.Until(start.Add(at)); d > 0 {
				time.Sleep(d)
			}
			if time.Now().After(deadline) {
				return
			}
			select {
			case <-polled:
				return
			default:
			}
			pollNow()
		}
	}()
	defer func() { polled <- struct{}{} }()

	var ocr *ReceiptMeta
	for {
		mkMu.Lock()
		res := tryMatch(j, ocr, waitingSnapshot())
		if res != nil {
			mkLinked[res.kot.ID] = j.ID
		}
		wake := mkWake
		mkMu.Unlock()
		if res != nil {
			m := ReceiptMeta{Brand: slipBrand(res.kot), OrderID: res.kot.POID, Method: res.by}
			if strings.Contains(strings.ToLower(res.kot.Platform), "toing") {
				m.DisplayBrand = "Toing / Swiggy"
			}
			if ocr != nil && ocr.OrderID != "" {
				j.OCR = ocr.OrderID
				if res.by == "ocr" && ocr.Method == "text" {
					res.by, m.Method = "slip text", "slip text"
				}
			}
			return res.kot, m, res.by
		}
		left := time.Until(deadline)
		if left <= 0 {
			break
		}
		select {
		case <-wake:
		case m := <-ocrDone:
			ocr = &m
			ocrDone = nil
			mkMu.Lock()
			j.slipBrand = m.Brand
			mkMu.Unlock()
			if !ppFresh() {
				left = 0 // no Petpooja list to wait for: print with what OCR read
			}
		case <-time.After(left):
		}
		if left <= 0 {
			break
		}
	}
	// No order found in time: print with what the slip itself says (Bridge Print's way).
	if ocr == nil {
		select {
		case m := <-ocrDone:
			ocr = &m
		default:
		}
	}
	if ocr != nil {
		j.OCR = ocr.OrderID
		if digitsOnly(ocr.OrderID) != "" {
			go lateLink(j, ocr.OrderID) // its KOT may still reach Petpooja's list
		}
		return nil, *ocr, firstNonEmpty(ocr.Method, "ocr")
	}
	go lateIdentify(j, ocrDone)
	return nil, ReceiptMeta{Method: "none", Warning: "Order not found in time; fallback token label used."}, "none"
}

// lateIdentify: the slip has printed; when OCR finishes, the order is still linked for the
// Dispatch screen and the Android Token app.
func lateIdentify(j *Job, ocrDone chan ReceiptMeta) {
	if ocrDone == nil {
		return
	}
	select {
	case m := <-ocrDone:
		prMu.Lock()
		if j.Metadata == nil || j.Metadata.OrderID == "" {
			mm := m
			j.Metadata = &mm
			j.OCR = m.OrderID
			saveJobLocked(j)
		}
		prMu.Unlock()
		if m.OrderID != "" {
			lateLink(j, m.OrderID)
		}
	case <-time.After(30 * time.Second):
	}
}

// lateLink: keeps looking for the KOT of a printed slip's order number for two minutes.
func lateLink(j *Job, orderID string) {
	slip := &ReceiptMeta{OrderID: orderID}
	for end := time.Now().Add(2 * time.Minute); time.Now().Before(end); time.Sleep(2 * time.Second) {
		mkMu.Lock()
		res := tryMatch(j, slip, nil)
		if res != nil {
			if _, taken := mkLinked[res.kot.ID]; taken {
				res = nil
			} else {
				mkLinked[res.kot.ID] = j.ID
			}
		}
		mkMu.Unlock()
		if res != nil {
			linkJob(j, res.kot, "ocr")
			return
		}
	}
}
