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
	"sync"
	"time"
)

// Firebase Realtime Database layout (all under /babai):
//
//	dosa                 token status for the customers' page (public read)        ~1-2 KB
//	live                 open KOTs on the kitchen board (private)                  ~10-20 KB
//	days/<date>/<kotId>  finished KOTs, one small record each (private)            ~0.4 KB each
//	hub                  hub heartbeat / version (private)
//
// Free plan (Spark): 1 GB stored, 10 GB downloaded per month. The hub keeps far below it:
// token status at most every 5 s and only when it changed, open KOTs at most every 15 s,
// finished KOTs once each, days older than KEEP_DAYS deleted.
const (
	dosaGap       = 5 * time.Second
	dosaHeartbeat = time.Minute
	liveGap       = 15 * time.Second
	liveHeartbeat = 5 * time.Minute
	archiveEvery  = time.Minute
	freshFor      = 90 * time.Second // TV data older than this is not re-sent
	keepDays      = 400
)

var (
	httpc = &http.Client{Timeout: 20 * time.Second}

	cmu        sync.Mutex
	lastOKAt   time.Time
	lastErr    string
	lastErrAt  time.Time
	sentDosa   []byte
	sentDosaAt time.Time
	sentLive   []byte
	sentLiveAt time.Time
	archAt     time.Time
	prunedDay  string
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
	if !configured() {
		return
	}
	mu.Lock()
	fresh := !lastIngest.IsZero() && now.Sub(lastIngest) < freshFor
	dosa := dosaRaw
	live := liveRaw
	dev := lastDev
	mu.Unlock()

	if fresh && dosa != nil {
		changed := !bytes.Equal(dosa, sentDosa)
		if (changed && now.Sub(sentDosaAt) >= dosaGap) || now.Sub(sentDosaAt) >= dosaHeartbeat {
			err := fb(http.MethodPut, "/babai/dosa", "", withServerTime(dosa))
			noteResult(err)
			sentDosaAt = now
			if err == nil {
				sentDosa = dosa
			}
		}
	}
	if fresh && live != nil {
		changed := !bytes.Equal(live, sentLive)
		if (changed && now.Sub(sentLiveAt) >= liveGap) || now.Sub(sentLiveAt) >= liveHeartbeat {
			body, _ := json.Marshal(map[string]any{"u": map[string]string{".sv": "timestamp"}, "dev": dev,
				"kots": json.RawMessage(live)})
			err := fb(http.MethodPut, "/babai/live", "", body)
			noteResult(err)
			sentLiveAt = now
			if err == nil {
				sentLive = live
			}
		}
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
		err := fb(http.MethodPatch, "/babai/days/"+d, "", body)
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
	b, err := fbGet("/babai/days", "shallow=true")
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
			if err := fb(http.MethodDelete, "/babai/days/"+k, "", nil); err == nil {
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
	err := fb(http.MethodPut, "/babai/hub", "", body)
	noteResult(err)
	return err
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
