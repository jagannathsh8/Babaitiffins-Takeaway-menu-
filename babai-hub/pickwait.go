package main

// Pick-up TV wait times (4.13): every waiting pick-up token shows about how long it still takes,
// and the top bar the average wait now.
//
//   - Dosas: the Dosa screen's logic (minutesLeft: today's average dosa time and how fast KOTs come
//     off the tawa), with the queue of ALL dosa KOTs together (dine in, pick up, delivery), oldest
//     first, because they share the tawas.
//   - Other dishes: each dish's usual kitchen time, learned from this outlet's last 30 days of
//     orders without a dosa (median punch -> Food ready); a dish with too few orders uses its
//     category's time, then all dishes', then 6 min.
//   - The order is ready when its slowest part is: the bigger of the two. Never more than 40 min;
//     the last minute (or a late order) shows "Almost ready".

import (
	"math"
	"sort"
	"strings"
	"sync"
	"time"
)

const (
	pwDefaultMin  = 6.0 // a dish nobody has learned yet
	pwDosaDefault = 8.0 // dosa time before today has any
	pwMinSamples  = 5
	pwLearnEvery  = time.Hour
	pwDays        = 30
	pwStartKots   = 4.0 // dosa KOTs cooking at once, before the real speed is measured
)

// ---- dish times from the order records -----------------------------------------------------------

type pwTimes struct {
	item, cat map[string]float64
	all       float64
	pickup    float64 // a pick-up order, start to Food ready (the average wait when none is waiting)
	at        time.Time
}

var (
	pwMu sync.Mutex
	pw   = pwTimes{}
)

func hasDosaItem(items []KItem) bool {
	for _, it := range items {
		if it.S >= 0 && isDosa(it.C) {
			return true
		}
	}
	return false
}

func dishKey(s string) string { return strings.ToLower(strings.Join(strings.Fields(s), " ")) }

func pwLearn(now time.Time) pwTimes {
	pwMu.Lock()
	defer pwMu.Unlock()
	if !pw.at.IsZero() && now.Sub(pw.at) < pwLearnEvery {
		return pw
	}
	items, cats := map[string][]float64{}, map[string][]float64{}
	var all, pick []float64
	for d := 0; d <= pwDays; d++ {
		for _, o := range readDayOrders(now.AddDate(0, 0, -d).Format("2006-01-02")) {
			k := kptMs(o)
			if k <= 0 || k > 60*60_000 {
				continue
			}
			m := float64(k) / 60000
			if o.Type == 2 {
				pick = append(pick, m)
			}
			if hasDosaItem(o.Items) {
				continue // a dosa queue would make every dish on it look slow
			}
			all = append(all, m)
			for _, it := range o.Items {
				if it.S < 0 {
					continue
				}
				items[dishKey(it.N)] = append(items[dishKey(it.N)], m)
				if c := dishKey(it.C); c != "" {
					cats[c] = append(cats[c], m)
				}
			}
		}
	}
	t := pwTimes{item: map[string]float64{}, cat: map[string]float64{}, at: now}
	for k, v := range items {
		if len(v) >= pwMinSamples {
			t.item[k] = median(v)
		}
	}
	for k, v := range cats {
		if len(v) >= pwMinSamples {
			t.cat[k] = median(v)
		}
	}
	if len(all) >= pwMinSamples {
		t.all = median(all)
	}
	if len(pick) >= pwMinSamples {
		t.pickup = median(pick)
	}
	pw = t
	return t
}

func (t pwTimes) dish(it KItem) float64 {
	if v := t.item[dishKey(it.N)]; v > 0 {
		return v
	}
	if v := t.cat[dishKey(it.C)]; v > 0 {
		return v
	}
	if t.all > 0 {
		return t.all
	}
	return pwDefaultMin
}

// ---- how fast dosa KOTs come off the tawa, every channel together ---------------------------------

type dosaFlow struct {
	open   map[int64]bool
	recent []int64
	since  int64
}

var (
	flowMu sync.Mutex
	flow   = dosaFlow{open: map[int64]bool{}}
)

func unreadyDosa(k Kot) bool {
	if k.Status == "0" || k.Status == "9" || k.Status == "10" || dosaLeftover(k, time.Now().UnixMilli()) {
		return false
	}
	for _, it := range k.Items {
		if it.S >= 0 && isDosa(it.C) && !itemReady(it.S) {
			return true
		}
	}
	return false
}

// flowUpdate: with every fresh KOT list; a dosa KOT that turns ready counts as one off the tawa.
func flowUpdate(kots []Kot, now int64) {
	flowMu.Lock()
	defer flowMu.Unlock()
	if flow.since == 0 {
		flow.since = now
	}
	seen := map[int64]bool{}
	for _, k := range kots {
		seen[k.ID] = true
		if unreadyDosa(k) {
			flow.open[k.ID] = true
		} else if flow.open[k.ID] {
			delete(flow.open, k.ID)
			if k.Status != "0" && hasDosaItem(k.Items) {
				flow.recent = append(flow.recent, now)
			}
		}
	}
	for id := range flow.open {
		if !seen[id] {
			delete(flow.open, id) // left the list without turning ready here: not counted
		}
	}
	for len(flow.recent) > 0 && flow.recent[0] < now-winMs {
		flow.recent = flow.recent[1:]
	}
}

func flowPerMin(now int64) float64 {
	flowMu.Lock()
	defer flowMu.Unlock()
	if flow.since == 0 {
		return 0
	}
	window := float64(max(minWinMs, min(winMs, now-flow.since))) / 60000
	n := 0
	for _, t := range flow.recent {
		if t >= now-winMs {
			n++
		}
	}
	return float64(n) / window
}

// ---- one pick-up order's wait ---------------------------------------------------------------------

type pickWait struct {
	M      int     // minutes left (1 = almost ready)
	Lead   string  // "🔥 Dosa: " / ""
	Bold   string  // "2 orders ahead on the tawa" / "Poori usually takes 6 min"
	total  float64 // elapsed + left (for the average)
	queued bool
}

// dosaQueue: every unready dosa KOT, oldest first (its place = KOTs ahead of it).
func dosaQueue(kots []Kot) map[int64]int {
	var q []Kot
	for _, k := range kots {
		if unreadyDosa(k) {
			q = append(q, k)
		}
	}
	sort.SliceStable(q, func(i, j int) bool { return q[i].Created < q[j].Created })
	pos := map[int64]int{}
	for i, k := range q {
		pos[k.ID] = i
	}
	return pos
}

func waitFor(k Kot, pos map[int64]int, t pwTimes, avgDosa, perMin float64, now int64) pickWait {
	elapsed := 0.0
	if k.Created > 0 {
		elapsed = math.Max(0, float64(now-(k.Created+clockSkew()))/60000)
	}
	dosaLeft, otherLeft := -1.0, -1.0
	p, inQueue := pos[k.ID]
	if inQueue {
		avg := avgDosa
		if math.IsNaN(avg) || avg <= 0 {
			avg = pwDosaDefault
		}
		if perMin <= 0 {
			// the tawas' speed isn't measured yet (the Console just started): about 4 KOTs at a
			// time across the tawas, each taking today's dosa time
			perMin = pwStartKots / avg
		}
		dosaLeft = minutesLeft(p, elapsed, avg, perMin)
	}
	var slow []string
	slowT := 0.0
	for _, it := range k.Items {
		if it.S < 0 || itemReady(it.S) || isDosa(it.C) {
			continue
		}
		d := t.dish(it)
		left := d - elapsed
		if left > otherLeft {
			otherLeft = left
		}
		if d > slowT+0.5 {
			slow, slowT = []string{it.N}, d
		} else if math.Abs(d-slowT) <= 0.5 && len(slow) < 2 && !containsStr(slow, it.N) {
			slow = append(slow, it.N)
		}
	}
	w := pickWait{queued: inQueue}
	left := math.Max(dosaLeft, otherLeft)
	if left < 0 && dosaLeft < 0 && otherLeft < 0 {
		left = 0
	}
	left = math.Min(maxEtaMin, math.Max(0, left))
	w.M = max(1, int(math.Round(left)))
	w.total = elapsed + left
	if inQueue && dosaLeft >= otherLeft {
		w.Lead = "🔥 Dosa: "
		switch p {
		case 0:
			w.Bold = "next on the tawa"
		case 1:
			w.Bold = "1 order ahead on the tawa"
		default:
			w.Bold = itoa(p) + " orders ahead on the tawa"
		}
	} else if len(slow) > 0 {
		mins := max(1, int(math.Round(slowT)))
		verb := " usually takes "
		if len(slow) > 1 {
			verb = " usually take "
		}
		w.Bold = strings.Join(slow, " & ") + verb + itoa(mins) + " min"
	}
	return w
}

// ---- triple tap on the Pick-up TV / Dispatch on a kitchen screen: off the Ready list -------------

var dosaAvgNow = math.NaN() // today's dosa time (min), set with every board under mu

var pickCleared = map[int64]int64{} // KOT -> taken off the Pick-up TV (ms); under pickMu

func pickupClear(kot int64, undo bool) {
	pickMu.Lock()
	defer pickMu.Unlock()
	if undo {
		delete(pickCleared, kot)
	} else {
		pickCleared[kot] = time.Now().UnixMilli()
	}
}
