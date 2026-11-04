package main

// Search on the dispatch page: the last digits of an order ID (or a KOT number / slot) finds the
// order among every order of the last 3 days, whatever the page's filters show.

import (
	"net/http"
	"sort"
	"strconv"
	"time"
)

const findDays = 3

func handleFindOrders(w http.ResponseWriter, r *http.Request) {
	q := digitsOnly(r.URL.Query().Get("q"))
	if len(q) < 3 || len(q) > 24 {
		writeJSON(w, map[string]any{"q": q, "orders": []OrderRec{}})
		return
	}
	writeJSON(w, map[string]any{"q": q, "orders": findOrders(q, time.Now()), "skew": clockSkew()})
}

// findOrders: newest first, at most 20.
func findOrders(q string, now time.Time) []OrderRec {
	seen := map[string]bool{}
	var out []OrderRec
	add := func(o OrderRec) {
		key := strconv.FormatInt(o.Kot, 10)
		if o.Kot == 0 {
			key = "j" + o.JobID
		}
		if seen[key] || !orderMatches(o, q) {
			return
		}
		seen[key] = true
		if o.Token == 0 && o.Slot == "" && o.OrderID != "" {
			o.Slot = slotFor(o.OrderID)
		}
		out = append(out, o)
	}
	since := now.AddDate(0, 0, -findDays)
	for _, o := range ordersSnapshot(since) { // live records first: the freshest status
		add(o)
	}
	for d := 0; d < findDays; d++ {
		for _, o := range ordersOfDay(now.AddDate(0, 0, -d).Format("2006-01-02")) {
			if time.UnixMilli(o.Seen).After(since) {
				add(o)
			}
		}
	}
	sort.SliceStable(out, func(a, b int) bool { return out[a].Seen > out[b].Seen })
	if len(out) > 20 {
		out = out[:20]
	}
	return out
}

// orderMatches: the order ID ends with q, or q is its KOT number or its slot.
func orderMatches(o OrderRec, q string) bool {
	if id := digitsOnly(o.OrderID); len(id) >= len(q) && id[len(id)-len(q):] == q {
		return true
	}
	if o.KotNo > 0 && strconv.FormatInt(o.KotNo, 10) == q {
		return true
	}
	if o.Token > 0 && strconv.Itoa(o.Token) == q {
		return true
	}
	return o.Slot != "" && o.Slot == q
}
