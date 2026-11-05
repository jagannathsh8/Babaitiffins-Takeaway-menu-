package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func sampleSlip() slipData {
	return slipData{Label: "SLOT", Token: 47, When: time.Date(2026, 11, 4, 21, 46, 0, 0, time.Local), KotNo: "1876",
		Brand: "Swiggy", OrderID: "252475655137731", Type: "DELIVERY",
		Items: []slipItem{{N: "Masala Dosa", Q: 2, A: []KAddon{{N: "Breakfast Sambar (100ml)", Q: 2}}},
			{N: "Cheese Karam Dosa", Q: 1, A: []KAddon{{N: "Extra Cheese", Q: 1}, {N: "Extra Ghee", Q: 1}}},
			{N: "Filter Coffee", Q: 1, Nt: "less sugar"}},
		Note: "Please send cutlery, ring the bell once"}
}

func TestSlipRenders(t *testing.T) {
	for _, w := range []int{576, 384} {
		img := renderSlip(sampleSlip(), w)
		if img.Bounds().Dx() != w || img.Bounds().Dy() < 600 || img.Bounds().Dy() > 2500 {
			t.Fatalf("width %d: size %v", w, img.Bounds())
		}
		data := escposSlip(img)
		if data[0] != 0x1b || data[1] != 0x40 || !strings.HasSuffix(string(data), "\x1dVB\x00") {
			t.Fatal("ESC/POS: init / cut missing")
		}
		if d := os.Getenv("SLIP_OUT"); d != "" {
			_ = os.WriteFile(filepath.Join(d, "slip-"+itoa(w)+".png"), slipPNG(img), 0o644)
		}
	}
}

func TestCode128(t *testing.T) {
	// even digits: subset C (start 105), pairs, check digit
	v := code128Values("20261104")
	want := []int{105, 20, 26, 11, 4}
	sum := 105 + 20*1 + 26*2 + 11*3 + 4*4
	want = append(want, sum%103)
	if len(v) != len(want) {
		t.Fatal(v)
	}
	for i := range v {
		if v[i] != want[i] {
			t.Fatalf("%v != %v", v, want)
		}
	}
	if code128Values("") != nil || code128Values("é") != nil {
		t.Fatal("bad input accepted")
	}
	if v := code128Values("5000715116389"); v[0] != 105 || v[len(v)-3] != 100 {
		t.Fatalf("odd digits: C then B for the last: %v", v)
	}
}
