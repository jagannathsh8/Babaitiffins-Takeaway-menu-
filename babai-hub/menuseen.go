package main

// The menu for the print-rule chips is every category and item Petpooja has ever sent this PC, not
// only what is on its item on/off list or in today's open orders (Chaat showed only while a Chaat
// order was open). Kept in menu_seen.json; filled once from the saved orders (120 days), then from
// every Petpooja read.

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const menuSeenMax = 3000 // items kept (a guard, far above any menu)

var (
	menuMu     sync.Mutex
	menuSeen   map[string]map[string]int64 // category -> item -> last seen (ms)
	menuDirty  bool
	menuSaveAt time.Time
)

func menuSeenPath() string { return filepath.Join(dataDir, "menu_seen.json") }

// loadMenuSeen (menuMu held): the saved list, or one built from the saved orders.
func loadMenuSeen() {
	if menuSeen != nil {
		return
	}
	menuSeen = map[string]map[string]int64{}
	if b, err := os.ReadFile(menuSeenPath()); err == nil && json.Unmarshal(b, &menuSeen) == nil && len(menuSeen) > 0 {
		return
	}
	menuSeen = map[string]map[string]int64{}
	es, _ := os.ReadDir(ordersDir())
	for _, e := range es {
		day := strings.TrimSuffix(e.Name(), ".json")
		var list []OrderRec
		if day == e.Name() || !readJSONFile(filepath.Join(ordersDir(), e.Name()), &list) {
			continue
		}
		for _, o := range list {
			for _, it := range o.Items {
				noteMenuLocked(it.C, it.N, o.Seen)
			}
		}
	}
	menuDirty = true
}

func noteMenuLocked(c, n string, at int64) {
	c, n = strings.TrimSpace(c), strings.TrimSpace(n)
	if n == "" {
		return
	}
	if c == "" {
		c = "Other"
	}
	m := menuSeen[c]
	if m == nil {
		m = map[string]int64{}
		menuSeen[c] = m
	}
	if old, ok := m[n]; !ok || at > old {
		if !ok && menuSize() >= menuSeenMax {
			return
		}
		m[n] = at
		menuDirty = true
	}
}

func menuSize() int {
	n := 0
	for _, m := range menuSeen {
		n += len(m)
	}
	return n
}

// noteMenu: after every Petpooja read (KOTs, and the item on/off list when it was read).
func noteMenu(kots []Kot, stock []StockItem, now time.Time) {
	menuMu.Lock()
	defer menuMu.Unlock()
	loadMenuSeen()
	ms := now.UnixMilli()
	for _, k := range kots {
		for _, it := range k.Items {
			noteMenuLocked(it.C, it.N, ms)
		}
	}
	for _, s := range stock {
		noteMenuLocked(s.Category, s.Name, ms)
	}
	if menuDirty && now.Sub(menuSaveAt) > time.Minute {
		if b, err := json.Marshal(menuSeen); err == nil && writeFileAtomic(menuSeenPath(), b) == nil {
			menuDirty, menuSaveAt = false, now
		}
	}
}

// menuSeenSnapshot: category -> items.
func menuSeenSnapshot() map[string][]string {
	menuMu.Lock()
	defer menuMu.Unlock()
	loadMenuSeen()
	out := map[string][]string{}
	for c, m := range menuSeen {
		for n := range m {
			out[c] = append(out[c], n)
		}
	}
	return out
}
