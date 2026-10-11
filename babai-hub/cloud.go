package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Firebase Realtime Database layout (all under /babai):
//
//	t/<token>            one customer's token: status, place, minutes, items (public: that token only)
//	meta                 dosa wait, dosas on the tawa, live facts, time of the last update (public)
//	live                 open KOTs on the kitchen board (private)
//	stock                item on/off list (private)
//	days/<date>/<kotId>  finished KOTs, one small record each (private)
//	hub                  hub version and PC name (private)
//
// Free plan (Spark): 1 GB stored, 10 GB downloaded per month. Phones read only their own token
// (~0.5 KB) and the small meta; the hub writes only when something changed, at most every few
// seconds, with print=silent, and deletes online days older than keepDays.
const (
	tokensGap    = 5 * time.Second
	tokensHB     = 10 * time.Minute
	metaGap      = 15 * time.Second
	metaHB       = time.Minute // phones use meta.u to see that the hub is live
	liveGap      = 15 * time.Second
	liveHB       = 5 * time.Minute
	stockGap     = 5 * time.Minute
	stockHB      = 6 * time.Hour
	archiveEvery = time.Minute
	freshFor     = 90 * time.Second // Petpooja data older than this is not re-sent
	keepDays     = 400
)

// stream is one path that is re-sent when its data changed (not more often than gap) and
// at least every heartbeat.
type stream struct {
	path           string
	gap, heartbeat time.Duration
	stamp          bool // add the server time "u"
	sent           []byte
	at             time.Time
}

func (s *stream) push(now time.Time, data []byte) {
	if data == nil {
		return
	}
	changed := !bytes.Equal(data, s.sent)
	if !((changed && now.Sub(s.at) >= s.gap) || now.Sub(s.at) >= s.heartbeat) {
		return
	}
	body := data
	if s.stamp {
		body = withServerTime(data)
	}
	base := outletBase()
	if base == "" {
		return
	}
	err := fb(http.MethodPut, base+s.path, "", body)
	noteResult(err)
	s.at = now
	if err == nil {
		s.sent = data
	}
}

var (
	httpc = &http.Client{Timeout: 20 * time.Second}

	cmu       sync.Mutex
	lastOKAt  time.Time
	lastErr   string
	lastErrAt time.Time
	archAt    time.Time
	prunedDay string

	// paths below /babai/o/<outlet>
	stTokens = &stream{path: "/t", gap: tokensGap, heartbeat: tokensHB}
	stMeta   = &stream{path: "/meta", gap: metaGap, heartbeat: metaHB, stamp: true}
	stLive   = &stream{path: "/live", gap: liveGap, heartbeat: liveHB, stamp: true}
	stStock  = &stream{path: "/stock", gap: stockGap, heartbeat: stockHB, stamp: true}

	registeredOutlet string // "<code>|<name>" written to /babai/outlets
)

func cloudOK() bool {
	cmu.Lock()
	defer cmu.Unlock()
	return !lastOKAt.IsZero() && (lastErrAt.Before(lastOKAt) || time.Since(lastErrAt) > 5*time.Minute)
}

func cloudStatus() map[string]any {
	cmu.Lock()
	defer cmu.Unlock()
	okAgo, errAgo := -1, -1
	if !lastOKAt.IsZero() {
		okAgo = int(time.Since(lastOKAt).Seconds())
	}
	if !lastErrAt.IsZero() && lastErrAt.After(lastOKAt) {
		errAgo = int(time.Since(lastErrAt).Seconds())
	}
	um.Lock()
	defer um.Unlock()
	return map[string]any{"cloudOkAgo": okAgo, "cloudErr": lastErr, "cloudErrAgo": errAgo,
		"month": usage.Month, "upMB": float64(usage.Up) / 1e6, "downMB": float64(usage.Down) / 1e6, "writes": usage.Writes}
}

func noteResult(err error) {
	cmu.Lock()
	defer cmu.Unlock()
	if err == nil {
		lastOKAt = time.Now()
		return
	}
	if err.Error() != lastErr || time.Since(lastErrAt) > 10*time.Minute {
		log.Printf("cloud: %v", err)
	}
	lastErr, lastErrAt = err.Error(), time.Now()
}

// cloudLoop runs for the life of the program.
func cloudLoop() {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	for range t.C {
		cloudStep(time.Now())
	}
}

// cloudStep sends whatever is due (called every second).
func cloudStep(now time.Time) {
	if !cloudReady() || getConfig().Paused {
		return
	}
	registerOutlet()
	mu.Lock()
	fresh := !lastBoard.IsZero() && now.Sub(lastBoard) < freshFor
	tokens, meta, live := tokensRaw, metaRaw, liveRaw
	mu.Unlock()
	if fresh {
		stTokens.push(now, tokens)
		stMeta.push(now, meta)
		if live != nil {
			stLive.push(now, []byte(`{"kots":`+string(live)+`}`))
		}
		stStock.push(now, stockJSON())
	}
	if now.Sub(archAt) >= archiveEvery {
		archAt = now
		flushArchive()
	}
	if day := now.Format("2006-01-02"); day != prunedDay && now.Hour() >= 3 {
		prunedDay = day
		go prune(now)
	}
	saveUsageMaybe(now)
}

// flushArchive uploads finished KOTs, one request per day.
func flushArchive() {
	mu.Lock()
	days := make([]string, 0, len(pending))
	for d := range pending {
		days = append(days, d)
	}
	mu.Unlock()
	sort.Strings(days)
	for _, d := range days {
		mu.Lock()
		batch := pending[d]
		body, _ := json.Marshal(batch)
		n := len(batch)
		mu.Unlock()
		if n == 0 {
			continue
		}
		err := fb(http.MethodPatch, outletBase()+"/days/"+d, "", body)
		noteResult(err)
		if err != nil {
			return // try again next minute (records also stay in the local files)
		}
		mu.Lock()
		if len(pending[d]) == n {
			delete(pending, d)
		} else {
			for id := range batch {
				delete(pending[d], id)
			}
		}
		mu.Unlock()
	}
}

// prune deletes archived days older than keepDays online (local files are kept).
func prune(now time.Time) {
	base := outletBase()
	if base == "" {
		return
	}
	b, err := fbGet(base+"/days", "shallow=true")
	if err != nil {
		return
	}
	var keys map[string]any
	if json.Unmarshal(b, &keys) != nil {
		return
	}
	cut := now.AddDate(0, 0, -keepDays).Format("2006-01-02")
	for k := range keys {
		if k < cut {
			if err := fb(http.MethodDelete, base+"/days/"+k, "", nil); err == nil {
				log.Printf("pruned online day %s", k)
			}
		}
	}
}

// ---- Firebase REST --------------------------------------------------------------------------

func fbURL(path, query string) string {
	c := getConfig()
	u := c.DB + path + ".json?auth=" + url.QueryEscape(c.Secret)
	if query != "" {
		u += "&" + query
	}
	return u
}

func fb(method, path, query string, body []byte) error {
	// print=silent: Firebase doesn't echo the written data back (downloads count on the free plan)
	if query == "" {
		query = "print=silent"
	}
	_, err := fbDo(method, path, query, body)
	return err
}

func fbGet(path, query string) ([]byte, error) {
	return fbDo(http.MethodGet, path, query, nil)
}

func fbDo(method, path, query string, body []byte) ([]byte, error) {
	var rd io.Reader
	if body != nil {
		rd = bytes.NewReader(body)
	}
	req, err := http.NewRequest(method, fbURL(path, query), rd)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Firebase-ETag", "false")
	resp, err := httpc.Do(req)
	if err != nil {
		var ue *url.Error
		if errors.As(err, &ue) {
			err = ue.Err // never log the URL (it holds the secret)
		}
		return nil, fmt.Errorf("can't reach Firebase (internet?): %v", err)
	}
	defer resp.Body.Close()
	out, _ := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	countUsage(len(body), len(out))
	switch {
	case resp.StatusCode == 401 || resp.StatusCode == 403:
		return nil, errors.New("Firebase refused the database secret")
	case resp.StatusCode == 404:
		return nil, errors.New("Firebase database not found (check the address)")
	case resp.StatusCode >= 300:
		return nil, fmt.Errorf("Firebase answered HTTP %d", resp.StatusCode)
	}
	return out, nil
}

// testCloud writes /babai/hub once (Save & test on the page).
func testCloud() error {
	host, _ := os.Hostname()
	body, _ := json.Marshal(map[string]any{"u": map[string]string{".sv": "timestamp"}, "v": version, "pc": host})
	path := "/babai/hubtest" // before the outlet is named
	if b := outletBase(); b != "" {
		path = b + "/hub"
	}
	err := fb(http.MethodPut, path, "", body)
	noteResult(err)
	if err == nil {
		registeredOutlet = ""
		registerOutlet()
	}
	return err
}

// registerOutlet lists this outlet in /babai/outlets/<code> = {"n": name} (public), so the
// customers' page can offer it. Done once per name.
func registerOutlet() {
	c := getConfig()
	code := outletCode(c.Outlet)
	if code == "" || registeredOutlet == code+"|"+c.Outlet {
		return
	}
	body, _ := json.Marshal(map[string]string{"n": strings.TrimSpace(c.Outlet)})
	if err := fb(http.MethodPut, "/babai/outlets/"+code, "", body); err == nil {
		registeredOutlet = code + "|" + c.Outlet
	}
}

// resetStreams makes everything be sent again (new outlet name).
func resetStreams() {
	for _, s := range []*stream{stTokens, stMeta, stLive, stStock} {
		s.sent, s.at = nil, time.Time{}
	}
	registeredOutlet = ""
}

// ---- monthly usage (shown on the page, to keep an eye on the free plan) --------------------

type Usage struct {
	Month  string `json:"month"`
	Up     int64  `json:"up"`
	Down   int64  `json:"down"`
	Writes int64  `json:"writes"`
}

var (
	um        sync.Mutex
	usage     Usage
	usageSave time.Time
)

func loadUsage() {
	b, err := os.ReadFile(filepath.Join(dataDir, "usage.json"))
	if err == nil {
		_ = json.Unmarshal(b, &usage)
	}
}

func countUsage(up, down int) {
	um.Lock()
	defer um.Unlock()
	m := time.Now().Format("2006-01")
	if usage.Month != m {
		usage = Usage{Month: m}
	}
	usage.Up += int64(up)
	usage.Down += int64(down)
	usage.Writes++
}

func saveUsageMaybe(now time.Time) {
	if now.Sub(usageSave) < 5*time.Minute {
		return
	}
	usageSave = now
	um.Lock()
	b, _ := json.Marshal(usage)
	um.Unlock()
	_ = writeFileAtomic(filepath.Join(dataDir, "usage.json"), b)
}

// stockJSON: {"items":{"<id>":{"n":name,"c":category,"on":1}}} or nil before the first read.
func stockJSON() []byte {
	ppMu.Lock()
	defer ppMu.Unlock()
	if ppStock == nil {
		return nil
	}
	m := map[string]map[string]any{}
	for _, it := range ppStock {
		id := strings.Map(func(r rune) rune {
			if strings.ContainsRune(".#$[]/", r) {
				return '_'
			}
			return r
		}, it.ID)
		if id == "" {
			continue
		}
		on := 1
		if it.InStock != nil && *it.InStock == 0 {
			on = 0
		}
		m[id] = map[string]any{"n": it.Name, "c": it.Category, "on": on}
	}
	b, _ := json.Marshal(map[string]any{"items": m})
	return b
}
