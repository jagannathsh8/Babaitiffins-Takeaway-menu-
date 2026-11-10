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

func TestOTPFromPetpoojaSlip(t *testing.T) {
	zom := "10/11/26 16:21\nKOT - 1390\nZomato : 8830749615\nOTP : 1476\nDelivery\nItem Qty.\nMasala Dosa 2\nPrepare By : 2026-11-10 16:34:14\n8830749615\nPickup barcode for Zomato delivery partner"
	m := parseReceiptText(zom)
	if m.OTP != "1476" || m.OTPName != "OTP" {
		t.Fatalf("zomato slip: %+v", m)
	}
	swg := "10/11/26 16:16\nKOT - 1380\nToing By Swiggy :\n253276847155798\nDelivery Passcode : 4821\nDelivery\nChole Bhatura 1"
	if m2 := parseReceiptText(swg); m2.OTP != "4821" || m2.OTPName != "Delivery Passcode" {
		t.Fatalf("swiggy slip: %+v", m2)
	}
	if m3 := parseReceiptText("10/11/26 11:09\nKOT - 856\nPick Up\nGhee Podi Dosa 2"); m3.OTP != "" {
		t.Fatal("no OTP on a pick up slip")
	}
	if d, _ := slipOTP([]string{"OTP : 1O76"}); d != "1076" {
		t.Fatalf("OCR O for 0: %q", d)
	}
	// the Console's slip for the same order uses it
	j := &Job{ID: "b1"}
	noteSlipOTP(j, m)
	k := Kot{ID: 5501, POID: "8830749615", Platform: "Zomato", Online: true}
	if otp, name := slipOTPFor(k); otp != "1476" || name != "OTP" {
		t.Fatalf("by order ID: %q %q", otp, name)
	}
	j2 := &Job{ID: "b2"}
	noteSlipOTP(j2, ReceiptMeta{OTP: "4821", OTPName: "Delivery Passcode"}) // the slip's order ID not read
	prMu.Lock()
	j2.Kot = 5502
	prMu.Unlock()
	if otp, _ := slipOTPFor(Kot{ID: 5502, POID: "253276847155798"}); otp != "4821" {
		t.Fatal("by the KOT the slip was linked to")
	}
}

func TestBoltSlip(t *testing.T) {
	bolt := "10/11/26 16:22\nKOT - 1394\nSwiggy : 253277531102151\nDelivery\n[BOLT-Urgent Order]\nItem Qty.\nFilter Coffee Flask 1\n1\nCustomer Notes: Don't send cutlery\nPayment Status : Online Paid\nPrepare By : 2026-11-10 16:23:24\nDelivery Passcode : 8338\nScan to Mark food ready\n253277531102151\nPickup barcode for Swiggy delivery partner"
	m := parseReceiptText(bolt)
	if m.OTP != "8338" || m.OTPName != "Delivery Passcode" || len(m.Tags) != 1 || m.Tags[0] != "[BOLT-Urgent Order]" {
		t.Fatalf("bolt slip: %+v", m)
	}
	noteSlipOTP(&Job{ID: "bolt"}, m)
	otp, name, tags := petpoojaSlipFor(Kot{ID: 77, POID: "253277531102151"})
	if otp != "8338" || name != "Delivery Passcode" || len(tags) != 1 {
		t.Fatalf("%q %q %v", otp, name, tags)
	}
	d := slipForOTP(Kot{ID: 77, OrderType: 1, Online: true, Platform: "Swiggy", POID: "253277531102151"}, 16, directSlip{bill: true}, false, otp, name)
	d.Tags = tags
	if img := renderSlip(d, 576); img.Bounds().Dy() < 300 {
		t.Fatal("renders")
	}
}

func TestUrgentFromKitchenData(t *testing.T) {
	rows := []Row{{ID: looseNum{V: 1, OK: true}, OrderType: looseNum{V: 1, OK: true}, OUID: "6736", POID: "253277531102151", Name: "Filter Coffee Flask", Urgent: "1", Bulk: "0"}}
	ks := groupRows(rows)
	if len(ks) != 1 || !ks[0].Urgent || ks[0].Bulk {
		t.Fatalf("%+v", ks)
	}
	if flagOn("0") || flagOn("") || flagOn("null") || !flagOn("1") || !flagOn("2026-11-10 16:22:00") {
		t.Fatal("flagOn")
	}
	k := ks[0]
	k.Platform = "Swiggy"
	tags := petpoojaSlipTags(&Job{ID: "u1"}, k)
	if len(tags) != 1 || tags[0] != "[BOLT-Urgent Order]" {
		t.Fatalf("server flag: %v", tags)
	}
	k.Bulk = true
	j := &Job{ID: "u2", Tags: []string{"[BOLT-Urgent Order]"}}
	if tags = petpoojaSlipTags(j, k); len(tags) != 2 || tags[1] != "[Bulk Order]" {
		t.Fatalf("no duplicate, bulk added: %v", tags)
	}
}
