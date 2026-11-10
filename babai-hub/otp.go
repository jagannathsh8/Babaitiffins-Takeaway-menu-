package main

// The rider's OTP / delivery passcode for the Console's bill slips, from Petpooja's pending
// orders (the list the Petpooja Token app reads: POST :3000/petpooja_server/get_pending_orders).
// Zomato calls it "OTP", Swiggy "Delivery Passcode". Read-only, like everything else here.

import (
	"errors"
	"fmt"
	"log"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	otpMu     sync.Mutex
	otpList   []map[string]any
	otpAt     time.Time
	otpCode   string
	otpLogged bool
)

// pendingOrders: Petpooja's pending orders, read at most every 2 s.
func pendingOrders() ([]map[string]any, error) {
	otpMu.Lock()
	defer otpMu.Unlock()
	if time.Since(otpAt) < 2*time.Second && otpList != nil {
		return otpList, nil
	}
	server := getConfig().Server
	if strings.TrimSpace(server) == "" {
		return nil, errors.New("no Petpooja server")
	}
	base := serverBase(server) + "petpooja_server/" // the same address (and port) the Console reads KOTs from
	if otpCode == "" {
		prMu.Lock()
		otpCode = prSettings.PetpoojaSyncCode
		prMu.Unlock()
	}
	if otpCode == "" {
		setup, err := livePost(base+"get_token_default_data", map[string]any{"clientIdentifier": "token", "intra_server_version": "1"})
		if err != nil {
			return nil, err
		}
		otpCode = jsonClean(setup["sync_code"])
		if otpCode == "" {
			return nil, errors.New("no restaurant code from Petpooja")
		}
	}
	res, err := livePost(base+"get_pending_orders", map[string]any{"sync_code": otpCode, "clientIdentifier": "token", "intra_server_version": "1",
		"online": "1", "pickup": "1", "delivery": "1", "dinein": "1"})
	if err != nil {
		otpCode = "" // a fresh code next time
		return nil, err
	}
	if e := jsonClean(res["error"]); e == "1" || strings.EqualFold(e, "true") {
		otpCode = ""
		return nil, errors.New(firstNonEmpty(jsonClean(res["message"]), "Petpooja rejected the request"))
	}
	var list []map[string]any
	for _, k := range []string{"data", "pos_data", "dine_data"} {
		if arr, ok := res[k].([]any); ok {
			for _, x := range arr {
				if m, ok := x.(map[string]any); ok {
					list = append(list, m)
				}
			}
		}
	}
	otpList, otpAt = list, time.Now()
	return list, nil
}

// otpLast: what happened to the last delivery order's OTP / passcode (shown on the KOT printing
// page, so a missing passcode says why).
type otpCheck struct {
	At    int64  `json:"at"`
	Order string `json:"order"` // last 4 of the order ID
	Brand string `json:"brand"`
	OK    bool   `json:"ok"`
	Msg   string `json:"msg"`
}

var otpLast otpCheck

func noteOTP(k Kot, ok bool, msg string) {
	otpMu.Lock()
	otpLast = otpCheck{At: time.Now().UnixMilli(), Order: last4Of(k.POID), Brand: brandOf(k), OK: ok, Msg: msg}
	otpMu.Unlock()
}

func otpLastCheck() otpCheck {
	otpMu.Lock()
	defer otpMu.Unlock()
	return otpLast
}

// pendingMatches: a pending order is this KOT's: the same KOT id, or any of its values is the
// platform order ID (or its end) whatever Petpooja calls the field (Toing, Swiggy, Zomato ... differ).
func pendingMatches(o map[string]any, k Kot) bool {
	if v := jsonClean(o["kot_id"]); v != "" && v == strconv.FormatInt(k.ID, 10) {
		return true
	}
	id := digitsOnly(k.POID)
	if len(id) < 6 {
		return false
	}
	var walk func(key string, v any, depth int) bool
	walk = func(key string, v any, depth int) bool {
		lk := strings.ToLower(key)
		if strings.Contains(lk, "phone") || strings.Contains(lk, "mobile") || strings.Contains(lk, "contact") {
			return false
		}
		switch x := v.(type) {
		case map[string]any:
			if depth < 3 {
				for kk, vv := range x {
					if walk(kk, vv, depth+1) {
						return true
					}
				}
			}
		case []any:
			if depth < 3 {
				for _, vv := range x {
					if walk(key, vv, depth+1) {
						return true
					}
				}
			}
		default:
			d := digitsOnly(jsonClean(x))
			if len(d) >= 6 && (d == id || strings.HasSuffix(id, d) || strings.HasSuffix(d, id)) {
				return true
			}
		}
		return false
	}
	for kk, vv := range o {
		if walk(kk, vv, 0) {
			return true
		}
	}
	return false
}

func otpKey(lk string) bool {
	return lk == "otp" || lk == "pin" || strings.Contains(lk, "passcode") || strings.Contains(lk, "pass_code") ||
		strings.HasSuffix(lk, "_otp") || strings.HasPrefix(lk, "otp_") || strings.Contains(lk, "delivery_code") ||
		strings.Contains(lk, "verification_code")
}

// findOTP: the OTP / passcode in a pending order (nested too); "otp" itself wins. keys: the
// fields that look like one (names only, for the log).
func findOTP(o map[string]any) (string, []string) {
	otp, best := "", 0
	var keys []string
	var walk func(m map[string]any, depth int)
	walk = func(m map[string]any, depth int) {
		for key, v := range m {
			lk := strings.ToLower(key)
			if sub, ok := v.(map[string]any); ok && depth < 3 {
				walk(sub, depth+1)
				continue
			}
			if !otpKey(lk) {
				continue
			}
			keys = append(keys, key)
			s := jsonClean(v)
			if s == "" || s == "0" || strings.EqualFold(s, "null") || len(s) > 12 {
				continue
			}
			rank := 1
			if lk == "otp" {
				rank = 2
			}
			if rank > best {
				otp, best = s, rank
			}
		}
	}
	walk(o, 0)
	sort.Strings(keys)
	return otp, keys
}

// orderOTP: the OTP of an online order (by its platform order ID), and what to call it.
func orderOTP(k Kot) (string, string) {
	if otp, name := slipOTPFor(k); otp != "" {
		return otp, name
	}
	otp, name, _ := orderOTPWhy(k)
	return otp, name
}

func orderOTPWhy(k Kot) (string, string, string) {
	if strings.TrimSpace(k.POID) == "" {
		return "", "", "the KOT has no online order ID"
	}
	list, err := pendingOrders()
	if err != nil {
		return "", "", "can't read Petpooja's pending orders: " + err.Error()
	}
	name := "OTP"
	if strings.Contains(strings.ToLower(brandOf(k)), "swiggy") {
		name = "Delivery Passcode"
	}
	for _, o := range list {
		if !pendingMatches(o, k) {
			continue
		}
		otp, keys := findOTP(o)
		otpMu.Lock()
		if !otpLogged || otp == "" {
			otpLogged = true
			log.Printf("print: OTP for %s …%s: %q, fields %v", brandOf(k), last4Of(k.POID), otp, keys)
		}
		otpMu.Unlock()
		if otp == "" {
			if len(keys) == 0 {
				return "", "", "Petpooja has the order but no OTP / passcode field on it yet"
			}
			return "", "", "Petpooja's OTP / passcode is empty for this order (" + strings.Join(keys, ", ") + ")"
		}
		return otp, name, ""
	}
	return "", "", fmt.Sprintf("the order isn't in Petpooja's pending orders yet (%d read)", len(list))
}

// ---- the OTP on Petpooja's own slip (it always has it): received by the Console's Bridge Print
// receiver, read from its text or by OCR, and used first.

type slipOTPRec struct {
	otp, name, order string
	tags             []string
	kot              int64
	job              *Job
	at               time.Time
}

var slipOTPs []slipOTPRec // under otpMu, the last few minutes

func noteSlipOTP(j *Job, m ReceiptMeta) {
	if m.OTP == "" && len(m.Tags) == 0 {
		return
	}
	otpMu.Lock()
	defer otpMu.Unlock()
	now := time.Now()
	keep := slipOTPs[:0]
	for _, r := range slipOTPs {
		if now.Sub(r.at) < 15*time.Minute && r.job != j {
			keep = append(keep, r)
		}
	}
	slipOTPs = append(keep, slipOTPRec{otp: m.OTP, name: m.OTPName, tags: m.Tags, order: digitsOnly(m.OrderID), job: j, at: now})
}

// petpoojaSlipFor: what Petpooja printed for this KOT (by the job it was linked to, or its order ID).
func petpoojaSlipFor(k Kot) (otp, name string, tags []string) {
	id := digitsOnly(k.POID)
	otpMu.Lock()
	recs := append([]slipOTPRec(nil), slipOTPs...)
	otpMu.Unlock()
	for i := len(recs) - 1; i >= 0; i-- {
		r := recs[i]
		prMu.Lock()
		kot := r.job.Kot
		prMu.Unlock()
		if kot == k.ID || len(r.order) >= 6 && len(id) >= 6 && (r.order == id || strings.HasSuffix(id, r.order) || strings.HasSuffix(r.order, id)) {
			if otp == "" && r.otp != "" {
				otp, name = r.otp, r.name
				if name == "" {
					name = "OTP"
				}
			}
			if tags == nil && len(r.tags) > 0 {
				tags = r.tags
			}
		}
	}
	return otp, name, tags
}

func slipOTPFor(k Kot) (string, string) {
	otp, name, _ := petpoojaSlipFor(k)
	return otp, name
}

// slipTagsFor: "[BOLT-Urgent Order]" and the like, from Petpooja's slip for this KOT.
func slipTagsFor(k Kot) []string {
	_, _, tags := petpoojaSlipFor(k)
	return tags
}

// waitOTP: Petpooja's slip or its pending orders may have the OTP a moment after the KOT: wait up to 8 s.
func waitOTP(k Kot) (string, string) {
	if barkChannel(k) != "D" || strings.TrimSpace(k.POID) == "" {
		return "", ""
	}
	start := time.Now()
	deadline := start.Add(8 * time.Second)
	for {
		if otp, name := slipOTPFor(k); otp != "" {
			noteOTP(k, true, name+" "+otp+" printed (from Petpooja's slip)")
			return otp, name
		}
		otp, name, why := orderOTPWhy(k)
		// Petpooja's slip also has the order's flags ([BOLT-Urgent Order]): give it 3 s first
		if otp != "" && time.Since(start) >= 3*time.Second {
			noteOTP(k, true, name+" "+otp+" printed")
			return otp, name
		}
		if time.Now().After(deadline) {
			noteOTP(k, false, "not printed: no OTP on Petpooja's slip yet, and "+why)
			log.Printf("print: no OTP / passcode for %s …%s: %s", brandOf(k), last4Of(k.POID), why)
			return "", ""
		}
		time.Sleep(700 * time.Millisecond)
	}
}
