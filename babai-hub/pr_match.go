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
func tryMatch(j *Job, ocrID string, waiting []*Job) *matchResult {
	if d := digitsOnly(ocrID); len(d) >= 6 {
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
	}
	recv := time.UnixMilli(j.Recv)
	var cands []Kot
	for _, k := range mkKots {
		if _, linked := mkLinked[k.ID]; linked || k.Status == "0" {
			continue
		}
		if t, ok := mkFirst[k.ID]; ok && t.After(recv.Add(-newKotWindow)) {
			cands = append(cands, k)
		}
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
	// prints still waiting for their order, oldest first
	pos := -1
	for i, w := range waiting {
		if w.ID == j.ID {
			pos = i
		}
	}
	if pos < 0 {
		return nil
	}
	if len(cands) == len(waiting) {
		k := cands[pos]
		return &matchResult{&k, "petpooja"}
	}
	return nil
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
	deadline := start.Add(wait)
	addWaiting(j)
	defer dropWaiting(j)

	images, text, _ := decodeReceipt(raw)
	textMeta := parseReceiptText(text)
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
		ocrID := ""
		if ocr != nil {
			ocrID = ocr.OrderID
		}
		res := tryMatch(j, ocrID, waitingSnapshot())
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
			mkMu.Lock()
			res := tryMatch(j, m.OrderID, nil)
			if res != nil {
				mkLinked[res.kot.ID] = j.ID
			}
			mkMu.Unlock()
			if res != nil {
				linkJob(j, res.kot, "ocr")
			}
		}
	case <-time.After(30 * time.Second):
	}
}
