package main

// The rider's OTP / delivery passcode for the Console's bill slips, from Petpooja's pending
// orders (the list the Petpooja Token app reads: POST :3000/petpooja_server/get_pending_orders).
// Zomato calls it "OTP", Swiggy "Delivery Passcode". Read-only, like everything else here.

import (
	"errors"
	"log"
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

// orderOTP: the OTP of an online order (by its platform order ID), and what to call it.
func orderOTP(k Kot) (string, string) {
	id := strings.ToLower(strings.TrimSpace(k.POID))
	if id == "" {
		return "", ""
	}
	list, err := pendingOrders()
	if err != nil {
		return "", ""
	}
	for _, o := range list {
		match := false
		for _, f := range []string{"client_order_id", "auth_no", "web_id"} {
			if v := strings.ToLower(jsonClean(o[f])); v != "" && (v == id || len(v) >= 6 && strings.HasSuffix(id, v)) {
				match = true
			}
		}
		if !match {
			continue
		}
		otp := ""
		for key, v := range o { // "otp", or a passcode field however Petpooja names it
			lk := strings.ToLower(key)
			if s := jsonClean(v); s != "" && s != "0" && !strings.EqualFold(s, "null") && (lk == "otp" || strings.Contains(lk, "passcode") || strings.Contains(lk, "pass_code") || strings.HasSuffix(lk, "_otp")) {
				if otp == "" || lk == "otp" {
					otp = s
				}
			}
		}
		otpMu.Lock()
		if !otpLogged {
			otpLogged = true
			var keys []string
			for key := range o {
				lk := strings.ToLower(key)
				if strings.Contains(lk, "otp") || strings.Contains(lk, "pass") {
					keys = append(keys, key+"="+jsonClean(o[key]))
				}
			}
			log.Printf("print: OTP fields from Petpooja: %v", keys)
		}
		otpMu.Unlock()
		name := "OTP"
		if strings.Contains(strings.ToLower(brandOf(k)), "swiggy") {
			name = "Delivery Passcode"
		}
		return otp, name
	}
	return "", ""
}

// waitOTP: a new order's OTP may reach Petpooja a moment after its KOT: wait up to 3 s.
func waitOTP(k Kot) (string, string) {
	if barkChannel(k) != "D" || strings.TrimSpace(k.POID) == "" {
		return "", ""
	}
	deadline := time.Now().Add(3 * time.Second)
	for {
		otp, name := orderOTP(k)
		if otp != "" || time.Now().After(deadline) {
			return otp, name
		}
		time.Sleep(700 * time.Millisecond)
	}
}
