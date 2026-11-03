package main

import (
	"encoding/json"
	"testing"
)

// Add-ons, item notes and the customer's note come through from Petpooja's kitchen list.
func TestAddonsAndNotes(t *testing.T) {
	raw := `[
	 {"id":699,"kot_unique_id":23,"order_type":1,"kot_status":"1","created":"2026-11-03 12:51:00","p_o_id":"8796250481","o_u_id":"5409","name":"Masala Dosa","quantity":2,"status":1,"item_id":11,
	  "addons":[{"group_name":"Extras & Sides","name":"Breakfast Sambar (100ml)","quantity":2,"status":1},{"group_name":"Extras & Sides","name":"Chutney","quantity":1,"status":-9}],
	  "special_notes":"","description":"Send cutlery"},
	 {"id":699,"kot_unique_id":23,"order_type":1,"kot_status":"1","created":"2026-11-03 12:51:00","p_o_id":"8796250481","o_u_id":"5409","name":"Veg Hyderabadi Dum Biryani","quantity":1,"status":1,"item_id":12,
	  "addons":null,"special_notes":"less spicy","i_desc":"Serves 1 | Rice cooked in the traditional Andhra style","description":"Send cutlery"},
	 {"id":700,"kot_unique_id":24,"order_type":1,"kot_status":"1","created":"2026-11-03 12:52:00","p_o_id":"252475675152540","o_u_id":"6736","name":"Ghee Dosa","quantity":1,"status":1,"item_id":13,
	  "addons":"[{\"group_name\":\"Toppings\",\"name\":\"Extra Ghee\",\"quantity\":\"1\"}]","special_notes":"Serves 1 | Deep-fried, crispy cauliflower florets","i_desc":"Serves 1 | Deep-fried","description":"Call me on 98765 43210 at the gate"},
	 {"id":701,"kot_unique_id":25,"order_type":3,"kot_status":"1","created":"2026-11-03 12:53:00","name":"Idli","quantity":2,"status":1,"item_id":14,"addons":"","description":""}
	]`
	var rows []Row
	if err := json.Unmarshal([]byte(raw), &rows); err != nil {
		t.Fatal(err)
	}
	kots := groupRows(rows)
	if len(kots) != 3 {
		t.Fatalf("kots: %d", len(kots))
	}
	z := kots[0]
	if z.Note != "Send cutlery" || len(z.Items) != 2 {
		t.Fatalf("zomato kot: %+v", z)
	}
	if a := z.Items[0].A; len(a) != 1 || a[0].N != "Breakfast Sambar (100ml)" || a[0].G != "Extras & Sides" || a[0].Q != 2 {
		t.Fatalf("add-ons (the cancelled one left out): %+v", a)
	}
	if z.Items[1].Nt != "less spicy" || z.Items[1].A != nil {
		t.Fatalf("item note: %+v", z.Items[1])
	}
	s := kots[1]
	if a := s.Items[0].A; len(a) != 1 || a[0].N != "Extra Ghee" || a[0].Q != 1 {
		t.Fatalf("add-ons written as text: %+v", a)
	}
	if s.Items[0].Nt != "" || s.Note != "Call me on ••• at the gate" {
		t.Fatalf("note without the phone number: %q / %q", s.Items[0].Nt, s.Note)
	}
	if d := kots[2]; d.Note != "" || d.Items[0].A != nil {
		t.Fatalf("no add-ons, no note: %+v", d)
	}
	ro := riderOrders([]Kot{z})
	if len(ro) != 1 || ro[0].Items[0] != "2 × Masala Dosa + 2 × Breakfast Sambar (100ml)" {
		t.Fatalf("rider items: %+v", ro)
	}
}
