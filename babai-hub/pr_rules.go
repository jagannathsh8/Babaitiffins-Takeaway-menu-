package main

// Babai Console print rules: which printer gets what.
//
// A rule matches on order type (delivery / pick up / dine in), brand (Swiggy, Zomato, Ownly,
// Go Self Service …) and items / categories ("has any of", "has none of", "only these"). It
// prints the whole KOT (Petpooja's own slip with the token on top) or only the matching items
// (a new slip made from Petpooja's order data), N copies, on its printer with a fallback. A
// rule can also say "don't print" (e.g. a brand that needs no slip). The main printer (Bridge
// Print's printer) prints always, only when no rule matched, or never.

import (
	"bytes"
	"fmt"
	"strings"
	"time"
)

type Rule struct {
	ID       string   `json:"id"`
	Name     string   `json:"name"`
	On       bool     `json:"on"`
	Types    []int    `json:"types,omitempty"`    // 1 delivery, 2 pick up, 3 dine in; empty = any
	Brands   []string `json:"brands,omitempty"`   // empty = any
	ItemMode string   `json:"itemMode,omitempty"` // "" any order, "any" has any of, "none" has none of, "only" only these
	Items    []string `json:"items,omitempty"`
	Cats     []string `json:"cats,omitempty"`
	Part     string   `json:"part,omitempty"`   // "bill" (whole KOT) or "items" (matching items only)
	Action   string   `json:"action,omitempty"` // "print" or "skip" (don't print on the main printer)
	Token    bool     `json:"token"`
	OrderNo  bool     `json:"orderNo"`
	Copies   int      `json:"copies"`
	Printer  string   `json:"printer,omitempty"`
	Fallback string   `json:"fallback,omitempty"` // empty = the printer's own fallback
}

// OrderFacts: what is known about a print when rules are applied.
type OrderFacts struct {
	Known     bool
	OrderType int
	Brands    []string // every name the order goes by (platform, brand, channel)
	Items     []KItem
}

func (r Rule) hasConditions() bool {
	return len(r.Types) > 0 || len(r.Brands) > 0 || (r.ItemMode != "" && (len(r.Items) > 0 || len(r.Cats) > 0))
}

func eqFold(list []string, v string) bool {
	v = strings.TrimSpace(v)
	for _, x := range list {
		if strings.EqualFold(strings.TrimSpace(x), v) {
			return true
		}
	}
	return false
}

func (r Rule) itemMatches(it KItem) bool {
	return eqFold(r.Items, it.N) || (it.C != "" && eqFold(r.Cats, it.C))
}

// matchRule: does the rule apply, and which items (for "matching items only").
func matchRule(r Rule, f OrderFacts) (bool, []KItem) {
	if !r.On {
		return false, nil
	}
	if r.hasConditions() && !f.Known {
		return false, nil // unknown order: only rules without conditions apply
	}
	if len(r.Types) > 0 {
		ok := false
		for _, t := range r.Types {
			if t == f.OrderType {
				ok = true
			}
		}
		if !ok {
			return false, nil
		}
	}
	if len(r.Brands) > 0 {
		ok := false
		for _, b := range f.Brands {
			if eqFold(r.Brands, b) {
				ok = true
			}
		}
		if !ok {
			return false, nil
		}
	}
	var live, hit []KItem
	for _, it := range f.Items {
		if it.S == -9 {
			continue // cancelled
		}
		live = append(live, it)
		if r.itemMatches(it) {
			hit = append(hit, it)
		}
	}
	if r.ItemMode != "" && (len(r.Items) > 0 || len(r.Cats) > 0) {
		switch r.ItemMode {
		case "any":
			if len(hit) == 0 {
				return false, nil
			}
		case "none":
			if len(hit) > 0 {
				return false, nil
			}
			hit = live
		case "only":
			if len(hit) == 0 || len(hit) != len(live) {
				return false, nil
			}
		}
	} else {
		hit = live
	}
	return true, hit
}

// planned is one slip to print.
type planned struct {
	out      PrintOut
	printer  string
	fallback string
	data     []byte
}

// planPrints works out every slip for a job: rules first, then the main printer.
func planPrints(j *Job, raw []byte, f OrderFacts, s PrintSettings, o PrintOptions) []planned {
	var plan []planned
	matched, skipMain := false, false
	meta := ReceiptMeta{}
	if j.Metadata != nil {
		meta = *j.Metadata
	}
	for _, r := range o.Rules {
		ok, items := matchRule(r, f)
		if !ok {
			continue
		}
		matched = true
		if r.Action == "skip" {
			skipMain = true
			continue
		}
		if r.Printer == "" {
			continue
		}
		copies := r.Copies
		if copies < 1 {
			copies = 1
		}
		if copies > 5 {
			copies = 5
		}
		var one []byte
		part := "bill"
		if r.Part == "items" && f.Known {
			part = "items"
			if len(items) == 0 {
				continue
			}
			one = renderItemSlip(j, items, r, meta)
		} else {
			c := j.Customization
			c.TokenEnabled, c.ShowOrderID = r.Token, r.OrderNo
			one = renderJob(raw, j.Token, c, meta)
		}
		data := bytes.Repeat(one, copies)
		plan = append(plan, planned{out: PrintOut{Rule: r.Name, Part: part, Copies: copies}, printer: r.Printer, fallback: firstNonEmpty(r.Fallback, o.Printers[r.Printer].Fallback), data: data})
	}
	mainPrinter := s.PrinterName
	if s.OutputMode == "lan" && s.PrinterIP != "" {
		mainPrinter = "" // handled below as a direct address
	}
	wantMain := !skipMain && (o.Main == "always" || o.Main == "" || (o.Main == "unmatched" && !matched))
	if wantMain {
		data := renderJob(raw, j.Token, j.Customization, meta)
		switch {
		case s.OutputMode == "lan" && s.PrinterIP != "":
			plan = append(plan, planned{out: PrintOut{Rule: "Main printer", Part: "bill", Copies: 1},
				printer: lanPrefix + "@" + fmt.Sprintf("%s:%d", s.PrinterIP, s.PrinterPort), fallback: o.Printers[s.PrinterName].Fallback, data: data})
		case mainPrinter != "":
			plan = append(plan, planned{out: PrintOut{Rule: "Main printer", Part: "bill", Copies: 1}, printer: mainPrinter, fallback: o.Printers[mainPrinter].Fallback, data: data})
		}
	}
	return plan
}

// renderItemSlip: a slip made by the Console for "matching items only" rules.
func renderItemSlip(j *Job, items []KItem, r Rule, m ReceiptMeta) []byte {
	var b bytes.Buffer
	b.Write([]byte{27, 64, 27, 97, 1})
	if j.Customization.Heading != "" {
		b.WriteString(asciiOnly(j.Customization.Heading) + "\n")
	}
	if r.Token {
		label := asciiOnly(m.Brand)
		if label == "" || !j.Customization.AutoSource {
			label = j.Customization.TokenLabel
		}
		if label == "" {
			label = "TOKEN"
		}
		b.Write([]byte{27, 69, 1, 29, 33, 34})
		b.WriteString(label + " " + itoa(j.Token) + "\n")
		b.Write([]byte{29, 33, 0, 27, 69, 0})
	}
	if r.OrderNo && m.OrderID != "" {
		b.Write([]byte{29, 33, 1, 27, 69, 1})
		b.WriteString("Order ID: " + last4Of(m.OrderID) + "\n")
		b.Write([]byte{29, 33, 0, 27, 69, 0})
	}
	line := []string{}
	if j.KotNo > 0 {
		line = append(line, "KOT "+itoa(int(j.KotNo)))
	}
	if t := orderTypeName(j.OrderType); t != "" {
		line = append(line, t)
	}
	line = append(line, time.UnixMilli(j.Recv).Format("15:04"))
	b.WriteString(strings.Join(line, "  ") + "\n")
	b.WriteString(asciiOnly(r.Name) + "\n")
	b.Write([]byte{27, 97, 0})
	b.WriteString(strings.Repeat("-", 42) + "\n")
	b.Write([]byte{29, 33, 1, 27, 69, 1})
	for _, it := range items {
		q := it.Q
		if q <= 0 {
			q = 1
		}
		b.WriteString(fmt.Sprintf("%-4s %s\n", trimFloat(q)+"x", asciiOnly(it.N)))
	}
	b.Write([]byte{29, 33, 0, 27, 69, 0})
	b.WriteString(strings.Repeat("-", 42) + "\n\n\n\n")
	b.Write([]byte{29, 86, 66, 0}) // cut
	return b.Bytes()
}

func trimFloat(f float64) string {
	s := fmt.Sprintf("%.2f", f)
	s = strings.TrimRight(strings.TrimRight(s, "0"), ".")
	return s
}

func orderTypeName(t int) string {
	switch t {
	case 1:
		return "Delivery"
	case 2:
		return "Pick up"
	case 3:
		return "Dine in"
	}
	return ""
}

// factsFromKot: everything rules can look at.
func factsFromKot(k *Kot) OrderFacts {
	if k == nil {
		return OrderFacts{}
	}
	f := OrderFacts{Known: true, OrderType: k.OrderType, Items: k.Items}
	add := func(s string) {
		s = strings.TrimSpace(s)
		if s != "" && !eqFold(f.Brands, s) {
			f.Brands = append(f.Brands, s)
		}
	}
	add(brandOf(*k))
	add(k.Platform)
	add(k.Channel)
	add(slipBrand(k))
	return f
}

// slipBrand: the word printed before the token, like Bridge Print printed it.
func slipBrand(k *Kot) string {
	if k == nil {
		return ""
	}
	low := strings.ToLower(k.Platform + " " + k.Channel)
	switch {
	case k.Online && strings.Contains(strings.ToLower(k.Platform), "toing"):
		return "Swiggy"
	case k.Online && k.Platform != "" && k.Platform != "Online":
		return k.Platform
	case strings.Contains(low, "self service") || strings.Contains(low, "self-service"):
		return "Self service"
	case strings.Contains(low, "ownly"):
		return "Ownly"
	case k.Online && k.Channel != "" && k.Channel != "Online":
		return k.Channel
	case k.OrderType == 2:
		return "Pick up"
	case k.OrderType == 3:
		return "Dine in"
	case k.OrderType == 1:
		return "Delivery"
	}
	return ""
}
