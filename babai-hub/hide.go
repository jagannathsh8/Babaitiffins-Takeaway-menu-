package main

// Brands whose orders stay off the rider screen (e.g. "Go Self Service":
// the customer collects it at the counter, no rider). Matched on the brand / platform name,
// ignoring case, spaces and punctuation. Their sales still count for the indent planner.

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"unicode"
)

var defaultHidden = []string{"Go Self Service"}

func hiddenBrands() []string {
	c := getConfig()
	if !c.HideSet {
		return defaultHidden
	}
	return c.HideBrands
}

func brandKey(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}

// kotHidden: the order's brand or platform matches a hidden brand.
func kotHidden(k Kot) bool {
	ch, pf := brandKey(k.Channel), brandKey(k.Platform)
	for _, h := range hiddenBrands() {
		hk := brandKey(h)
		if hk != "" && (strings.Contains(ch, hk) || strings.Contains(pf, hk)) {
			return true
		}
	}
	return false
}

func handleHide(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Brands string }
	if json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in) != nil {
		reply(w, false, "Couldn't read the form.")
		return
	}
	list := []string{}
	for _, p := range strings.Split(in.Brands, ",") {
		if p = strings.TrimSpace(p); p != "" && len(list) < 20 {
			list = append(list, p)
		}
	}
	c := getConfig()
	c.HideBrands, c.HideSet = list, true
	if err := saveConfig(c); err != nil {
		reply(w, false, "Couldn't save: "+err.Error())
		return
	}
	if len(list) == 0 {
		reply(w, true, "Saved. No brand is hidden.")
		return
	}
	reply(w, true, "Saved. Hidden on the rider screen: "+strings.Join(list, ", ")+".")
}
