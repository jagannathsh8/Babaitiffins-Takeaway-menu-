package main

import "testing"

func TestPendingOrderMatchAndPasscode(t *testing.T) {
	k := Kot{ID: 9002, POID: "253276847155798", Online: true, Platform: "Toing By Swiggy"}
	// the order ID under a field name we never saw, nested, and a passcode field by another name
	o := map[string]any{"id": "77", "customer_phone": "253276847155798", "online": map[string]any{"order_no": "253276847155798", "delivery_passcode": "4821"}, "otp": ""}
	if !pendingMatches(o, k) {
		t.Fatal("matched by any field holding the order ID")
	}
	if otp, keys := findOTP(o); otp != "4821" || len(keys) != 2 {
		t.Fatalf("passcode %q keys %v", otp, keys)
	}
	if pendingMatches(map[string]any{"customer_phone": "253276847155798", "client_order_id": "1111111"}, k) {
		t.Fatal("a phone number field never matches")
	}
	if !pendingMatches(map[string]any{"kot_id": "9002"}, k) {
		t.Fatal("same KOT id")
	}
	if otp, _ := findOTP(map[string]any{"otp": "2775", "passcode": "9999"}); otp != "2775" {
		t.Fatal("otp wins")
	}
	if otp, _ := findOTP(map[string]any{"otp": "0", "sync_code": "ABC123"}); otp != "" {
		t.Fatal("empty / not an OTP")
	}
}
