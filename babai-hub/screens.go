package main

// Babai Screens: the screens of the Babai Screens Android app (and any browser) on the shop Wi-Fi.
//
//	http://<Console PC>:8790/screens/rider    rider TV (online orders, slots, voice calls)
//	                         /screens/dosa     dosa order status TV (dine-in tokens, dosa wait)
//	                         /screens/pickup   pick-up TV (tokens with their dishes)
//	                         /screens/kitchen  kitchen screen (tick dishes, Food ready, dispatch)
//	                         /barker           dosa barker (barker.go)
//
// The app is only a frame around these pages: every rule runs here, on the Console, from the same
// Petpooja read as everything else. Read-only for everyone on the Wi-Fi, except the kitchen
// screen's Food Ready, which needs a screen paired once with the Console PIN. No customer names or
// phone numbers. The Console lists every screen with when it was last seen.

import (
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

var screenPages = map[string]string{"rider": "rider.html", "dosa": "dosa.html", "pickup": "pickup.html", "kitchen": "kitchen.html", "": "index.html"}

func screensRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/screens", func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, "/screens/", http.StatusFound) })
	mux.HandleFunc("/screens/", handleScreenPage)
	mux.HandleFunc("/screens/api/dosa", screenGet(handleScreenDosa))
	mux.HandleFunc("/screens/api/pickup", screenGet(handleScreenPickup))
	mux.HandleFunc("/screens/api/kitchen", screenGet(handleScreenKitchen))
	mux.HandleFunc("/screens/api/rider", screenGet(handleRider))
	mux.HandleFunc("/screens/api/hello", screenGet(func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"ok": true, "hub": version, "outlet": getConfig().Outlet, "app": screensAppInfo()})
	}))
	mux.HandleFunc("/screens/api/pair", handleScreenPair)
	mux.HandleFunc("/screens/api/ready", handleScreenReady)
	mux.HandleFunc("/screens/api/dispatch", handleScreenDispatch)
	mux.HandleFunc("/screens/voice.wav", handleScreenVoice)
	mux.HandleFunc("/screens/api/photos", screenGet(handleScreenPhotos))
	mux.HandleFunc("/screens/photo/", handleScreenPhoto)
	mux.Handle("/bsapp/", screensApp) // the app itself, for its self-update
	mux.HandleFunc("/console/api/screens", localOnly(handleScreensList))
}

func handleScreenPage(w http.ResponseWriter, r *http.Request) {
	name := strings.TrimPrefix(r.URL.Path, "/screens/")
	if f, ok := screenPages[name]; ok {
		serveConsoleFile("screens/"+f)(w, r)
		return
	}
	switch name {
	case "common.js", "common.css", "bg1.jpg", "bg2.jpg", "bg3.jpg":
		serveConsoleFile("screens/"+name)(w, r)
	case "logo.webp":
		serveConsoleFile("logo.webp")(w, r)
	default:
		http.NotFound(w, r)
	}
}

// ---- the list of screens ----------------------------------------------------------------------

type screenSeen struct {
	Dev    string `json:"dev"`
	Name   string `json:"name"`
	Screen string `json:"screen"`
	App    string `json:"app"`
	IP     string `json:"ip"`
	At     int64  `json:"at"`
	Paired bool   `json:"paired"`
}

var (
	screensMu   sync.Mutex
	screensSeen = map[string]*screenSeen{}
)

func cleanField(s string, n int) string {
	s = strings.Map(func(r rune) rune {
		if r < 32 || r == '<' || r == '>' || r == '"' {
			return -1
		}
		return r
	}, strings.TrimSpace(s))
	if len(s) > n {
		s = s[:n]
	}
	return s
}

// noteScreen: every screen request says who it is (?dev=&scr=&app=&name=).
func noteScreen(r *http.Request, screen string) {
	q := r.URL.Query()
	dev := cleanField(q.Get("dev"), 40)
	if dev == "" {
		host, _, _ := net.SplitHostPort(r.RemoteAddr)
		dev = "browser " + host
	}
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	screensMu.Lock()
	defer screensMu.Unlock()
	s := screensSeen[dev]
	if s == nil {
		if len(screensSeen) > 200 {
			return
		}
		s = &screenSeen{Dev: dev}
		screensSeen[dev] = s
	}
	if v := cleanField(q.Get("scr"), 20); v != "" {
		s.Screen = v
	} else if screen != "" {
		s.Screen = screen
	}
	if v := cleanField(q.Get("app"), 20); v != "" {
		s.App = v
	}
	if v := cleanField(q.Get("name"), 40); v != "" {
		s.Name = v
	}
	s.IP, s.At = host, time.Now().UnixMilli()
}

func screenGet(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "GET only", http.StatusMethodNotAllowed)
			return
		}
		noteScreen(r, strings.TrimPrefix(r.URL.Path, "/screens/api/"))
		h(w, r)
	}
}

func handleScreensList(w http.ResponseWriter, r *http.Request) {
	keys := getConfig().ScreenKeys
	screensMu.Lock()
	list := make([]screenSeen, 0, len(screensSeen))
	for _, s := range screensSeen {
		c := *s
		for _, d := range keys {
			if d == c.Dev {
				c.Paired = true
			}
		}
		list = append(list, c)
	}
	screensMu.Unlock()
	sort.Slice(list, func(i, j int) bool { return list[i].At > list[j].At })
	links := []string{}
	for _, a := range localIPv4s() {
		if !strings.HasPrefix(a, "127.") {
			links = append(links, fmt.Sprintf("http://%s:%d", a, consolePort()))
		}
	}
	writeJSON(w, map[string]any{"now": time.Now().UnixMilli(), "screens": list, "links": links, "app": screensAppInfo()})
}

// ---- pairing (kitchen screens, which change Petpooja) --------------------------------------------

// handleScreenPair: POST {pin, dev}; the Console PIN once, then the screen keeps its key.
func handleScreenPair(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Pin, Dev string }
	_ = json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&in)
	pinMu.Lock()
	if time.Now().Before(pinBlocked) {
		pinMu.Unlock()
		reply(w, false, "Too many wrong PINs. Wait a minute and try again.")
		return
	}
	pinMu.Unlock()
	if subtle.ConstantTimeCompare([]byte(strings.TrimSpace(in.Pin)), []byte(currentPin())) != 1 {
		pinMu.Lock()
		pinWrong++
		if pinWrong >= 5 {
			pinWrong, pinBlocked = 0, time.Now().Add(time.Minute)
		}
		pinMu.Unlock()
		reply(w, false, "Wrong PIN.")
		return
	}
	pinMu.Lock()
	pinWrong = 0
	pinMu.Unlock()
	dev := cleanField(in.Dev, 40)
	if dev == "" {
		dev = "browser"
	}
	key := newToken()
	cfgMu.Lock()
	if cfg.ScreenKeys == nil {
		cfg.ScreenKeys = map[string]string{}
	}
	for k, d := range cfg.ScreenKeys { // one key per screen
		if d == dev {
			delete(cfg.ScreenKeys, k)
		}
	}
	cfg.ScreenKeys[key] = dev
	c := cfg
	cfgMu.Unlock()
	if err := saveConfig(c); err != nil {
		reply(w, false, "Could not save: "+err.Error())
		return
	}
	log.Printf("screens: %s paired", dev)
	writeJSON(w, map[string]any{"ok": true, "key": key})
}

func screenKeyOK(r *http.Request) (string, bool) {
	k := r.Header.Get("X-Screen-Key")
	if k == "" {
		return "", false
	}
	for key, dev := range getConfig().ScreenKeys {
		if subtle.ConstantTimeCompare([]byte(k), []byte(key)) == 1 {
			return dev, true
		}
	}
	return "", false
}

func screenWrite(w http.ResponseWriter, r *http.Request) (string, bool) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return "", false
	}
	dev, ok := screenKeyOK(r)
	if !ok {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		io.WriteString(w, `{"ok":false,"pair":true,"msg":"Pair this screen with the Console PIN first."}`)
		return "", false
	}
	return dev, true
}

// ---- dosa order status TV ------------------------------------------------------------------------

func handleScreenDosa(w http.ResponseWriter, r *http.Request) {
	mu.Lock()
	toks, meta := tokensRaw, metaRaw
	mu.Unlock()
	if len(toks) == 0 {
		toks = []byte("{}")
	}
	if len(meta) == 0 {
		meta = []byte("{}")
	}
	writeJSON(w, map[string]any{"ok": ppFresh(), "now": time.Now().UnixMilli(), "skew": clockSkew(),
		"tokens": json.RawMessage(toks), "meta": json.RawMessage(meta), "outlet": getConfig().Outlet, "tawa": barkOnTawa(time.Now())})
}

// ---- pick-up TV ------------------------------------------------------------------------------

type pickupOrder struct {
	Tk    string      `json:"tk"`
	C     int64       `json:"c"`
	Ready bool        `json:"r"`
	RA    int64       `json:"ra,omitempty"`
	Items []PhoneItem `json:"i"`
}

var (
	pickMu      sync.Mutex
	pickReadyAt = map[int64]int64{}
)

func isPickup(k Kot) bool { return !k.Online && k.OrderType == 2 && barkChannel(k) == "P" }

func pickupOrders(kots []Kot, now int64) []pickupOrder {
	clear := int64(getConfig().AutoClearMin) * 60_000
	if clear <= 0 {
		clear = defaultClrM * 60_000
	}
	pickMu.Lock()
	defer pickMu.Unlock()
	open := map[int64]bool{}
	var out []pickupOrder
	for _, k := range kots {
		if !isPickup(k) || k.Status == "0" || k.Status == "10" || len(k.Items) == 0 {
			continue
		}
		open[k.ID] = true
		ready := kotReady(k)
		if ready && pickReadyAt[k.ID] == 0 {
			pickReadyAt[k.ID] = now
		} else if !ready {
			delete(pickReadyAt, k.ID)
		}
		ra := pickReadyAt[k.ID]
		if ready && now-ra >= clear {
			continue // collected long ago: off the screen
		}
		var items []KItem
		for _, it := range k.Items {
			if it.S >= 0 {
				items = append(items, it)
			}
		}
		out = append(out, pickupOrder{Tk: tokenLabel(k), C: k.Created, Ready: ready, RA: ra, Items: phoneItems(items)})
	}
	for id := range pickReadyAt {
		if !open[id] {
			delete(pickReadyAt, id)
		}
	}
	return out
}

func handleScreenPickup(w http.ResponseWriter, r *http.Request) {
	ppMu.Lock()
	kots := ppKots
	ppMu.Unlock()
	now := time.Now()
	writeJSON(w, map[string]any{"ok": ppFresh(), "now": now.UnixMilli(), "skew": clockSkew(), "orders": pickupOrders(kots, now.UnixMilli())})
}

// ---- kitchen screen ------------------------------------------------------------------------

type kitchenItem struct {
	ID int64    `json:"id"`
	N  string   `json:"n"`
	C  string   `json:"c,omitempty"`
	Q  float64  `json:"q"`
	R  bool     `json:"r,omitempty"` // ready in Petpooja
	A  []KAddon `json:"a,omitempty"`
	Nt string   `json:"nt,omitempty"`
}

type kitchenCard struct {
	Kot   int64         `json:"k"`
	KotN  string        `json:"kn"`
	Ch    string        `json:"ch"` // D P I S
	App   string        `json:"app,omitempty"`
	L4    string        `json:"l4,omitempty"`
	Slot  string        `json:"slot,omitempty"`
	Tb    string        `json:"tb,omitempty"`
	C     int64         `json:"c"`
	Ready bool          `json:"r,omitempty"`
	Note  string        `json:"nt,omitempty"`
	Items []kitchenItem `json:"i"`
}

var (
	kitchenMu   sync.Mutex
	kitchenGone = map[int64]int64{} // KOT -> dispatched on a kitchen screen (ms)
)

func kitchenCards(kots []Kot) ([]kitchenCard, []string) {
	kitchenMu.Lock()
	gone := map[int64]bool{}
	for k := range kitchenGone {
		gone[k] = true
	}
	kitchenMu.Unlock()
	catSet := map[string]bool{}
	var out []kitchenCard
	open := map[int64]bool{}
	for _, k := range kots {
		open[k.ID] = true
		if k.Status == "0" || k.Status == "10" || gone[k.ID] {
			continue
		}
		c := kitchenCard{Kot: k.ID, Ch: barkChannel(k), Tb: k.Table, C: k.Created, Ready: kotReady(k), Note: k.Note}
		if k.Token > 0 {
			c.KotN = fmt.Sprint(k.Token)
		} else {
			c.KotN = tokenLabel(k)
		}
		if c.Ch == "D" {
			c.App, c.L4, c.Slot = brandOf(k), last4Of(k.POID), slotForKot(k.ID, k.POID)
		}
		for _, it := range k.Items {
			if it.S < 0 {
				continue
			}
			cat := strings.TrimSpace(it.C)
			if cat == "" {
				cat = "Other"
			}
			catSet[cat] = true
			c.Items = append(c.Items, kitchenItem{ID: it.ID, N: it.N, C: cat, Q: it.Q, R: itemReady(it.S), A: it.A, Nt: it.Nt})
		}
		if len(c.Items) > 0 {
			out = append(out, c)
		}
	}
	kitchenMu.Lock()
	for k := range kitchenGone {
		if !open[k] {
			delete(kitchenGone, k)
		}
	}
	kitchenMu.Unlock()
	cats := make([]string, 0, len(catSet))
	for c := range catSet {
		cats = append(cats, c)
	}
	for c := range menuSeenSnapshotNoLoad() {
		if !catSet[c] {
			cats = append(cats, c)
		}
	}
	sort.Strings(cats)
	return out, cats
}

func handleScreenKitchen(w http.ResponseWriter, r *http.Request) {
	ppMu.Lock()
	kots := ppKots
	ppMu.Unlock()
	cards, cats := kitchenCards(kots)
	_, paired := screenKeyOK(r)
	writeJSON(w, map[string]any{"ok": ppFresh(), "now": time.Now().UnixMilli(), "skew": clockSkew(), "cards": cards, "cats": cats, "paired": paired})
}

// handleScreenReady: POST {kot, items:[item ids]} (no items: every dish of the KOT) -> Food ready
// in Petpooja, the same call the KDS app sends.
func handleScreenReady(w http.ResponseWriter, r *http.Request) {
	dev, ok := screenWrite(w, r)
	if !ok {
		return
	}
	var in struct {
		Kot   int64   `json:"kot"`
		Items []int64 `json:"items"`
	}
	if json.NewDecoder(io.LimitReader(r.Body, 16<<10)).Decode(&in) != nil || in.Kot == 0 {
		reply(w, false, "Bad request.")
		return
	}
	ok2, msg := kitchenReady(in.Kot, in.Items, dev)
	reply(w, ok2, msg)
}

func kitchenReady(kot int64, items []int64, dev string) (bool, string) {
	scanMu.Lock()
	defer scanMu.Unlock()
	c := getConfig()
	ppMu.Lock()
	kots, okAt, code := ppKots, ppOKAt, syncCode
	ppMu.Unlock()
	if c.Server == "" || c.Paused || okAt.IsZero() || time.Since(okAt) > scanFreshFor || code == "" {
		return false, "Can't reach Petpooja right now."
	}
	var k *Kot
	for i := range kots {
		if kots[i].ID == kot {
			k = &kots[i]
		}
	}
	if k == nil {
		return false, "This order is no longer in Petpooja's kitchen list."
	}
	todo := itemsToMark(*k)
	if len(items) > 0 {
		want := map[int64]bool{}
		for _, id := range items {
			want[id] = true
		}
		var sel []int64
		for _, id := range todo {
			if want[id] {
				sel = append(sel, id)
			}
		}
		todo = sel
	}
	if len(todo) == 0 {
		return true, "Already ready."
	}
	if err := sendItemsReady(serverBase(c.Server), code, *k, todo); err != nil {
		log.Printf("kitchen %s: KOT %d not marked: %v", dev, kot, err)
		return false, "Petpooja didn't accept it: " + err.Error()
	}
	if len(todo) == len(itemsToMark(*k)) {
		noteMarkedReady(k.ID, time.Now())
	}
	log.Printf("kitchen %s: KOT %d, %d dish(es) ready", dev, kot, len(todo))
	wakePoll()
	return true, "Food ready."
}

// sendItemsReady: like sendFoodReady, for the given dishes only.
func sendItemsReady(base, code string, k Kot, ids []int64) error {
	sub := k
	sub.Items = nil
	want := map[int64]bool{}
	for _, id := range ids {
		want[id] = true
	}
	for _, it := range k.Items {
		if want[it.ID] {
			sub.Items = append(sub.Items, it)
		}
	}
	return sendFoodReady(base, code, sub)
}

// handleScreenDispatch: POST {kot}: off the kitchen screens (Petpooja is not changed).
func handleScreenDispatch(w http.ResponseWriter, r *http.Request) {
	if _, ok := screenWrite(w, r); !ok {
		return
	}
	var in struct {
		Kot  int64 `json:"kot"`
		Undo bool  `json:"undo"`
	}
	if json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&in) != nil || in.Kot == 0 {
		reply(w, false, "Bad request.")
		return
	}
	kitchenMu.Lock()
	if in.Undo {
		delete(kitchenGone, in.Kot)
	} else {
		kitchenGone[in.Kot] = time.Now().UnixMilli()
	}
	kitchenMu.Unlock()
	reply(w, true, "Dispatched.")
}

// ---- the outlet's own photos behind the dosa screen: .jpg / .png in <data>/screen-photos -------

func screenPhotosDir() string { return filepath.Join(dataDir, "screen-photos") }

func screenPhotoNames() []string {
	es, _ := os.ReadDir(screenPhotosDir())
	var out []string
	for _, e := range es {
		n := strings.ToLower(e.Name())
		if !e.IsDir() && (strings.HasSuffix(n, ".jpg") || strings.HasSuffix(n, ".jpeg") || strings.HasSuffix(n, ".png") || strings.HasSuffix(n, ".webp")) && len(out) < 12 {
			out = append(out, e.Name())
		}
	}
	return out
}

func handleScreenPhotos(w http.ResponseWriter, r *http.Request) {
	list := []string{}
	for _, n := range screenPhotoNames() {
		list = append(list, "/screens/photo/"+url.PathEscape(n))
	}
	if len(list) == 0 {
		list = []string{"/screens/bg1.jpg", "/screens/bg2.jpg", "/screens/bg3.jpg"}
	}
	writeJSON(w, map[string]any{"ok": true, "photos": list})
}

func handleScreenPhoto(w http.ResponseWriter, r *http.Request) {
	name, err := url.PathUnescape(strings.TrimPrefix(r.URL.EscapedPath(), "/screens/photo/"))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	for _, n := range screenPhotoNames() {
		if n == name {
			w.Header().Set("Cache-Control", "max-age=3600")
			http.ServeFile(w, r, filepath.Join(screenPhotosDir(), n))
			return
		}
	}
	http.NotFound(w, r)
}
