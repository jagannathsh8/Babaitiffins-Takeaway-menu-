package main

// Ratings sheet: every 15 minutes this PC sends its order times (punched, printed, food ready,
// picked up, delivered; KPT and rider wait) to the CX METRICS Google Sheet, where they are joined
// with ratings and complaints on the platform order ID. The sheet's Apps Script web app receives
// them (doPost) and keeps them in its "Console times" tab. No Firebase secret is involved: the
// web app link and its key are pasted in Data > Ratings sheet. Only order times and IDs are sent.

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const (
	cxEvery   = 15 * time.Minute
	cxBack    = 48 * time.Hour     // each send covers the last two days…
	cxSweep   = 7 * 24 * time.Hour // …and the first send after a change of link covers a week
	cxBatch   = 400                // orders per request
	cxMaxBody = 1 << 20            // reply size read
)

var (
	cxMu     sync.Mutex
	cxSent   = map[int64]string{} // KOT -> what was last sent for it
	cxAt     time.Time            // last attempt
	cxOKAt   time.Time            // last success
	cxState  = "not set up"
	cxFor    string // link the cxSent map belongs to
	cxWake   = make(chan struct{}, 1)
	cxClient = &http.Client{Timeout: 60 * time.Second}
	cxSendFn = cxPost // tests swap it
)

// CXRow is one order as the sheet stores it. Times are milliseconds on this PC's clock.
type CXRow struct {
	ID         string  `json:"id"`
	Outlet     string  `json:"outlet"`
	Channel    string  `json:"channel,omitempty"`
	Brand      string  `json:"brand,omitempty"`
	Type       int     `json:"type"`
	Kot        int64   `json:"kot"`
	Token      int     `json:"token,omitempty"`
	Slot       string  `json:"slot,omitempty"`
	Items      int     `json:"items"`
	Names      string  `json:"names,omitempty"` // "2 x Idli, 1 x Vada" (matches Swiggy complaints, whose order ID differs)
	Punched    int64   `json:"punched,omitempty"`
	Printed    int64   `json:"printed,omitempty"`
	Ready      int64   `json:"ready,omitempty"`
	ReadyBy    string  `json:"readyBy,omitempty"`
	Dispatched int64   `json:"dispatched,omitempty"`
	Delivered  int64   `json:"delivered,omitempty"`
	Status     string  `json:"status"`
	KptMin     float64 `json:"kptMin,omitempty"`  // punched -> food ready
	WaitMin    float64 `json:"waitMin,omitempty"` // food ready -> picked up (rider wait)
	PrintSec   float64 `json:"printSec,omitempty"`
}

func validCXURL(s string) error {
	u, err := url.Parse(strings.TrimSpace(s))
	if err != nil || u.Scheme != "https" || u.Host != "script.google.com" || !strings.HasPrefix(u.Path, "/macros/s/") {
		return errors.New("paste the web app link from Apps Script (https://script.google.com/macros/s/…/exec)")
	}
	return nil
}

func round1(f float64) float64 { return float64(int64(f*10+0.5)) / 10 }

// cxRow turns an order record into what the sheet gets (nil: not a platform order).
func cxRow(o OrderRec, outlet string) *CXRow {
	id := strings.TrimSpace(o.OrderID)
	if len(digitsOnly(id)) < 6 || o.Status == "cancelled" {
		return nil
	}
	start := o.Punched
	if start > 0 {
		start += clockSkew() // Petpooja's clock -> this PC's
	}
	if start == 0 || (o.Seen > 0 && o.Seen-start > 3600_000) {
		start = o.Seen
	}
	r := &CXRow{ID: id, Outlet: outlet, Channel: o.Channel, Brand: o.Brand, Type: o.Type, Kot: o.Kot, Token: o.Token,
		Slot: o.Slot, Items: len(o.Items), Punched: start, Printed: o.Printed, Ready: o.Ready, ReadyBy: o.ReadyBy,
		Dispatched: o.Dispatched, Delivered: o.Delivered, Status: o.Status}
	var names []string
	for _, it := range o.Items {
		if n := strings.TrimSpace(it.N); n != "" {
			names = append(names, fmt.Sprintf("%g x %s", it.Q, n))
		}
	}
	r.Names = strings.Join(names, ", ")
	if r.Slot == "" && o.Token > 0 {
		r.Slot = fmt.Sprint(o.Token)
	}
	if o.Ready > start && start > 0 && o.Ready-start < 4*3600_000 {
		r.KptMin = round1(float64(o.Ready-start) / 60000)
	}
	if o.Dispatched >= o.Ready && o.Ready > 0 && o.Dispatched-o.Ready < 4*3600_000 {
		r.WaitMin = round1(float64(o.Dispatched-o.Ready) / 60000)
	}
	if o.PrintMs > 0 {
		r.PrintSec = round1(float64(o.PrintMs) / 1000)
	}
	return r
}

func cxLoop() {
	time.Sleep(20 * time.Second)
	t := time.NewTicker(time.Minute)
	defer t.Stop()
	for {
		force := false
		select {
		case <-t.C:
		case <-cxWake:
			force = true
		}
		cxSync(time.Now(), force)
	}
}

// cxSync sends what changed, every 15 minutes (or now when asked).
func cxSync(now time.Time, force bool) {
	c := getConfig()
	link, key := strings.TrimSpace(c.CXURL), strings.TrimSpace(c.CXKey)
	cxMu.Lock()
	if link == "" || key == "" {
		cxState = "not set up"
		cxMu.Unlock()
		return
	}
	if !force && now.Sub(cxAt) < cxEvery {
		cxMu.Unlock()
		return
	}
	cxAt = now
	back := cxBack
	if cxFor != link+"|"+key {
		cxFor, cxSent, back = link+"|"+key, map[int64]string{}, cxSweep
	}
	cxMu.Unlock()

	outlet := strings.TrimSpace(c.Outlet)
	var rows []CXRow
	var sigs []string
	for _, o := range ordersSnapshot(now.Add(-back)) {
		r := cxRow(o, outlet)
		if r == nil {
			continue
		}
		b, _ := json.Marshal(r)
		cxMu.Lock()
		same := cxSent[r.Kot] == string(b)
		cxMu.Unlock()
		if !same {
			rows, sigs = append(rows, *r), append(sigs, string(b))
		}
	}
	sent := 0
	for i := 0; i < len(rows); i += cxBatch {
		j := min(i+cxBatch, len(rows))
		if err := cxSendFn(link, key, rows[i:j]); err != nil {
			setCXState("couldn't send: " + err.Error())
			cxMu.Lock()
			cxAt = now.Add(-cxEvery + 2*time.Minute) // try again in 2 minutes
			cxMu.Unlock()
			return
		}
		cxMu.Lock()
		for k := i; k < j; k++ {
			cxSent[rows[k].Kot] = sigs[k]
		}
		cxMu.Unlock()
		sent += j - i
	}
	cxMu.Lock()
	cxOKAt = now
	cxState = fmt.Sprintf("sent %s · %d order%s updated", now.Format("15:04"), sent, map[bool]string{true: "", false: "s"}[sent == 1])
	// forget orders that left the window
	if len(cxSent) > 5000 {
		cxSent = map[int64]string{}
	}
	cxMu.Unlock()
}

func setCXState(s string) {
	cxMu.Lock()
	cxState = s
	cxMu.Unlock()
}

func cxStatus() (string, bool) {
	cxMu.Lock()
	defer cxMu.Unlock()
	return cxState, !cxOKAt.IsZero() && strings.HasPrefix(cxState, "sent")
}

// cxPost sends one batch to the Apps Script web app. Google answers a POST with a redirect to
// the result, which the client follows as a GET.
func cxPost(link, key string, rows []CXRow) error {
	body, _ := json.Marshal(map[string]any{"key": key, "orders": rows})
	req, err := http.NewRequest(http.MethodPost, link, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := cxClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(resp.Body, cxMaxBody))
	var out struct {
		OK  bool   `json:"ok"`
		Msg string `json:"msg"`
	}
	if json.Unmarshal(b, &out) != nil {
		if resp.StatusCode != http.StatusOK {
			return fmt.Errorf("HTTP %d", resp.StatusCode)
		}
		return errors.New("the link didn't answer like the sheet's web app (check the link and that it is deployed for anyone)")
	}
	if !out.OK {
		return errors.New(out.Msg)
	}
	return nil
}

// handleCXSheet: GET the setting and state; POST {url, key} to save, {op:"send"} to send now.
func handleCXSheet(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		c := getConfig()
		st, ok := cxStatus()
		writeJSON(w, map[string]any{"url": c.CXURL, "keySet": c.CXKey != "", "state": st, "ok": ok})
		return
	}
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct {
		Op  string  `json:"op"`
		URL *string `json:"url"`
		Key *string `json:"key"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in)
	if in.Op == "send" {
		select {
		case cxWake <- struct{}{}:
		default:
		}
		reply(w, true, "Sending…")
		return
	}
	c := getConfig()
	if in.URL != nil {
		u := strings.TrimSpace(*in.URL)
		if u != "" {
			if err := validCXURL(u); err != nil {
				reply(w, false, err.Error())
				return
			}
		}
		c.CXURL = u
	}
	if in.Key != nil && strings.TrimSpace(*in.Key) != "" {
		c.CXKey = strings.TrimSpace(*in.Key)
	}
	if in.URL != nil && c.CXURL == "" {
		c.CXKey = ""
	}
	if err := saveConfig(c); err != nil {
		reply(w, false, err.Error())
		return
	}
	if c.CXURL != "" && c.CXKey != "" {
		select {
		case cxWake <- struct{}{}:
		default:
		}
		reply(w, true, "Saved. Sending the last 7 days now…")
		return
	}
	reply(w, true, "Saved.")
}
