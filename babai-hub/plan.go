package main

// Sales history for the Babai Indent Planner (the ops website). Each outlet's hub sends, per day,
// how many of each dish were ordered in each hour, to
//
//	/babai/plan/<planner key>/o/<outlet code>/n            outlet name
//	/babai/plan/<planner key>/o/<outlet code>/d/<date>     {"s": "hub"|"tablet", "r": [[dish, "h:qty h:qty ..."], ...]}
//
// Dish names only: no customers, prices or KOT numbers. The planner key is worked out from the
// Firebase secret, so every hub of the same company gets the same key without typing it; the
// website gets it once in its settings. Firebase lets anyone who knows the key read that branch
// (nothing else), and the website may save the indent it sent under .../sent/ (for accuracy).
//
// Days come from the hub's own record of finished KOTs (days/<date>.jsonl, by order time); days
// before the hub was installed come from the Prep Live tablets' recorded days on this PC.

import (
	"bufio"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"log"
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
	planDays     = 42
	planTodayGap = 15 * time.Minute
	planSweepGap = 6 * time.Hour
)

var (
	planMu      sync.Mutex
	planSent    = map[string]string{} // date -> hash of what was sent
	planTodayAt time.Time
	planSweepAt time.Time
	planNameAt  string
)

// plannerKey: the same for every hub using the same Firebase secret.
func plannerKey() string {
	c := getConfig()
	if c.Secret == "" {
		return ""
	}
	m := hmac.New(sha256.New, []byte(c.Secret))
	m.Write([]byte("babai-indent-planner-v1"))
	return hex.EncodeToString(m.Sum(nil))[:24]
}

// dishHours: dish -> qty per hour for one date, from the hub's own KOT record.
func dishHoursFromRecords(date string) (map[string]*[24]float64, bool) {
	out := map[string]*[24]float64{}
	found := false
	d0, err := time.ParseInLocation("2006-01-02", date, time.Local)
	if err != nil {
		return out, false
	}
	// a KOT finished after midnight is in the next day's file
	for _, f := range []string{date, d0.AddDate(0, 0, 1).Format("2006-01-02")} {
		fh, err := os.Open(filepath.Join(dataDir, "days", f+".jsonl"))
		if err != nil {
			continue
		}
		sc := bufio.NewScanner(fh)
		sc.Buffer(make([]byte, 64<<10), 4<<20)
		for sc.Scan() {
			var r Rec
			if json.Unmarshal(sc.Bytes(), &r) != nil || r.C == 0 || r.St == "0" {
				continue
			}
			t := time.UnixMilli(r.C)
			if t.Format("2006-01-02") != date {
				continue
			}
			found = true
			addItems(out, t.Hour(), r.I)
		}
		fh.Close()
	}
	return out, found
}

func addItems(out map[string]*[24]float64, h int, items []ArchItem) {
	for _, it := range items {
		n := strings.TrimSpace(it.N)
		if n == "" || it.X == 1 {
			continue
		}
		q := it.Q
		if q <= 0 {
			q = 1
		}
		a := out[n]
		if a == nil {
			a = &[24]float64{}
			out[n] = a
		}
		a[h] += q
	}
}

// todayOpen adds the KOTs still on the kitchen board (not yet in the record) for today.
func todayOpen(out map[string]*[24]float64, date string) {
	ppMu.Lock()
	kots := ppKots
	ppMu.Unlock()
	for _, k := range kots {
		if k.Status == "0" || k.Created == 0 {
			continue
		}
		t := time.UnixMilli(k.Created)
		if t.Format("2006-01-02") != date {
			continue
		}
		var items []ArchItem
		for _, it := range k.Items {
			x := 0
			if it.S == -9 {
				x = 1
			}
			items = append(items, ArchItem{N: it.N, Q: it.Q, X: x})
		}
		addItems(out, t.Hour(), items)
	}
}

// dishHoursFromTablets: the Prep Live tablets' recorded dish lines for a date (before the hub).
func dishHoursFromTablets(date string) (map[string]*[24]float64, bool) {
	out := map[string]*[24]float64{}
	d, ok := readPrepDay(date)
	if !ok {
		return out, false
	}
	for _, l := range d.lines {
		p := strings.SplitN(l, "|", 4)
		if len(p) < 4 || p[0] != "D" {
			continue
		}
		a := &[24]float64{}
		for _, cell := range strings.Fields(p[3]) {
			if i := strings.IndexByte(cell, ':'); i > 0 {
				h, _ := strconv.Atoi(cell[:i])
				v, _ := strconv.ParseFloat(cell[i+1:], 64)
				if h >= 0 && h < 24 {
					a[h] += v
				}
			}
		}
		out[p[2]] = a
	}
	return out, len(out) > 0
}

func planPayload(src string, m map[string]*[24]float64) []byte {
	names := make([]string, 0, len(m))
	for n := range m {
		names = append(names, n)
	}
	sort.Strings(names)
	rows := make([][2]string, 0, len(names))
	for _, n := range names {
		var b strings.Builder
		for h, v := range m[n] {
			if v > 0 {
				if b.Len() > 0 {
					b.WriteByte(' ')
				}
				b.WriteString(strconv.Itoa(h) + ":" + strconv.FormatFloat(v, 'f', -1, 64))
			}
		}
		if b.Len() > 0 {
			rows = append(rows, [2]string{n, b.String()})
		}
	}
	body, _ := json.Marshal(map[string]any{"s": src, "r": rows})
	return body
}

// syncPlan (cloud loop): today every 15 minutes, the last 42 days every 6 hours (only what changed).
func syncPlan(now time.Time) {
	key := plannerKey()
	code := outletCode(getConfig().Outlet)
	if key == "" || code == "" {
		return
	}
	base := "/babai/plan/" + key + "/o/" + code
	planMu.Lock()
	todayDue := now.Sub(planTodayAt) >= planTodayGap
	sweepDue := now.Sub(planSweepAt) >= planSweepGap
	nameDue := planNameAt != getConfig().Outlet
	if todayDue {
		planTodayAt = now
	}
	if sweepDue {
		planSweepAt = now
	}
	planMu.Unlock()
	if nameDue {
		body, _ := json.Marshal(strings.TrimSpace(getConfig().Outlet))
		if fb(http.MethodPut, base+"/n", "", body) == nil {
			planMu.Lock()
			planNameAt = getConfig().Outlet
			planMu.Unlock()
		}
	}
	var dates []string
	if todayDue {
		dates = append(dates, now.Format("2006-01-02"), now.AddDate(0, 0, -1).Format("2006-01-02"))
	}
	if sweepDue {
		for i := 2; i <= planDays; i++ {
			dates = append(dates, now.AddDate(0, 0, -i).Format("2006-01-02"))
		}
	}
	today := now.Format("2006-01-02")
	for _, date := range dates {
		m, ok := dishHoursFromRecords(date)
		src := "hub"
		if date == today {
			todayOpen(m, date)
			ok = ok || len(m) > 0
		}
		if !ok {
			if m, ok = dishHoursFromTablets(date); !ok {
				continue
			}
			src = "tablet"
		}
		body := planPayload(src, m)
		h := shortHash(string(body))
		planMu.Lock()
		same := planSent[date] == h
		planMu.Unlock()
		if same {
			continue
		}
		if err := fb(http.MethodPut, base+"/d/"+date, "", body); err != nil {
			log.Printf("planner: %v", err)
			return
		}
		planMu.Lock()
		planSent[date] = h
		planMu.Unlock()
	}
}

func resetPlan() {
	planMu.Lock()
	planSent = map[string]string{}
	planTodayAt, planSweepAt, planNameAt = time.Time{}, time.Time{}, ""
	planMu.Unlock()
}
