package main

import (
	"crypto/sha1"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

// Dish carousel ("Try next time") on the customers' page, set on the hub page:
// 0 to 10 dishes, each a photo + name + short line. Not set = the page's built-in dishes;
// 0 dishes = the carousel is hidden (only the game).
//
// Firebase (below /babai/o/<outlet>):
//
//	promo         {"v": version, "n": count, "s": [{"t": name, "d": line, "k": photo key}, ...]}
//	pimg/<key>    "data:image/webp;base64,..."   one photo (20-60 KB), fetched only when shown
//
// Photos are made small on the hub page before they are sent (640 x 396), and a phone downloads
// a photo once (it keeps it), so the carousel stays well inside the free plan.
const (
	promoMax      = 10
	promoImgMax   = 300 << 10 // bytes of the data URL
	promoNameMax  = 40
	promoLineMax  = 90
	promoSyncWait = time.Minute
)

type Dish struct {
	T string `json:"t"`
	D string `json:"d"`
	K string `json:"k"`
}

type promoState struct {
	Custom bool   `json:"custom"` // false: customers see the built-in dishes
	Dishes []Dish `json:"dishes"`
}

var (
	promoMu     sync.Mutex
	promo       promoState
	promoDirty  = true // send once after start (keeps Firebase and this PC the same)
	promoTryAt  time.Time
	promoErr    string
	promoSentAt time.Time
	reImgURL    = regexp.MustCompile(`^data:image/(webp|jpeg|png);base64,[A-Za-z0-9+/=]+$`)
)

func promoDir() string { return filepath.Join(dataDir, "promo") }

func loadPromo() {
	promoMu.Lock()
	defer promoMu.Unlock()
	b, err := os.ReadFile(filepath.Join(promoDir(), "promo.json"))
	if err != nil {
		return
	}
	var p promoState
	if json.Unmarshal(b, &p) == nil {
		promo = p
	}
}

// savePromoLocked writes the list (caller holds promoMu) and marks it to be sent.
func savePromoLocked() error {
	_ = os.MkdirAll(promoDir(), 0o755)
	b, _ := json.MarshalIndent(promo, "", "  ")
	promoDirty = true
	promoTryAt = time.Time{}
	return writeFileAtomic(filepath.Join(promoDir(), "promo.json"), b)
}

func promoImgPath(k string) string { return filepath.Join(promoDir(), k+".txt") }

func promoSnapshot() promoState {
	promoMu.Lock()
	defer promoMu.Unlock()
	p := promoState{Custom: promo.Custom, Dishes: append([]Dish(nil), promo.Dishes...)}
	return p
}

func promoStatus() map[string]any {
	promoMu.Lock()
	defer promoMu.Unlock()
	st := "not sent yet"
	if !promoDirty && !promoSentAt.IsZero() {
		st = "on customers' phones"
	} else if promoErr != "" {
		st = "waiting to send: " + promoErr
	}
	return map[string]any{"custom": promo.Custom, "dishes": promo.Dishes, "max": promoMax, "sync": st}
}

func cleanText(s string, max int) string {
	s = strings.Join(strings.Fields(s), " ")
	for utf8.RuneCountInString(s) > max {
		r := []rune(s)
		s = string(r[:max])
	}
	return s
}

// ---- hub page ----------------------------------------------------------------------------

// handlePromo: GET the list; POST {op: add|edit|delete|up|down|default|none, ...}.
func handlePromo(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(promoStatus())
		return
	}
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct {
		Op    string
		I     int
		T, D  string
		Image string
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, promoImgMax+8192)).Decode(&in); err != nil {
		reply(w, false, "Couldn't read the form (photo too big?).")
		return
	}
	msg, err := promoChange(in.Op, in.I, in.T, in.D, in.Image)
	if err != nil {
		reply(w, false, err.Error())
		return
	}
	reply(w, true, msg)
}

func promoChange(op string, i int, t, d, img string) (string, error) {
	promoMu.Lock()
	defer promoMu.Unlock()
	n := len(promo.Dishes)
	t, d = cleanText(t, promoNameMax), cleanText(d, promoLineMax)
	switch op {
	case "add":
		if promo.Custom && n >= promoMax {
			return "", errors.New("10 dishes is the most. Delete one first.")
		}
		if t == "" {
			return "", errors.New("Type the dish name.")
		}
		k, err := storePromoImage(img)
		if err != nil {
			return "", err
		}
		if !promo.Custom {
			promo.Custom, promo.Dishes = true, nil
		}
		promo.Dishes = append(promo.Dishes, Dish{T: t, D: d, K: k})
	case "edit":
		if i < 0 || i >= n || t == "" {
			return "", errors.New("Type the dish name.")
		}
		promo.Dishes[i].T, promo.Dishes[i].D = t, d
		if img != "" {
			k, err := storePromoImage(img)
			if err != nil {
				return "", err
			}
			promo.Dishes[i].K = k
		}
	case "delete":
		if i < 0 || i >= n {
			return "", errors.New("No such dish.")
		}
		promo.Dishes = append(promo.Dishes[:i], promo.Dishes[i+1:]...)
	case "up", "down":
		j := i - 1
		if op == "down" {
			j = i + 1
		}
		if i < 0 || i >= n || j < 0 || j >= n {
			return "Saved.", nil
		}
		promo.Dishes[i], promo.Dishes[j] = promo.Dishes[j], promo.Dishes[i]
	case "default":
		promo = promoState{}
	case "none":
		promo = promoState{Custom: true}
	default:
		return "", errors.New("Unknown change.")
	}
	if err := savePromoLocked(); err != nil {
		return "", errors.New("Couldn't save: " + err.Error())
	}
	removeUnusedPromoImagesLocked()
	if !cloudReady() {
		return "Saved on this PC. It goes to customers' phones once Firebase is set up (Step 2).", nil
	}
	return "Saved. Customers' phones show it within a minute.", nil
}

// storePromoImage checks a photo from the hub page and keeps it in the promo folder; returns its key.
func storePromoImage(img string) (string, error) {
	if img == "" {
		return "", errors.New("Choose a photo of the dish.")
	}
	if len(img) > promoImgMax {
		return "", errors.New("That photo is too big. Try another one.")
	}
	if !reImgURL.MatchString(img) {
		return "", errors.New("That file is not a photo.")
	}
	raw, err := base64.StdEncoding.DecodeString(img[strings.Index(img, ",")+1:])
	if err != nil || len(raw) < 200 {
		return "", errors.New("That photo could not be read. Try another one.")
	}
	sum := sha1.Sum([]byte(img))
	k := hex.EncodeToString(sum[:])[:16]
	_ = os.MkdirAll(promoDir(), 0o755)
	if err := writeFileAtomic(promoImgPath(k), []byte(img)); err != nil {
		return "", errors.New("Couldn't save the photo: " + err.Error())
	}
	return k, nil
}

func removeUnusedPromoImagesLocked() {
	used := map[string]bool{}
	for _, d := range promo.Dishes {
		used[d.K] = true
	}
	files, _ := filepath.Glob(filepath.Join(promoDir(), "*.txt"))
	for _, f := range files {
		k := strings.TrimSuffix(filepath.Base(f), ".txt")
		if !used[k] {
			_ = os.Remove(f)
		}
	}
}

func handlePromoImage(w http.ResponseWriter, r *http.Request) {
	k := r.URL.Query().Get("k")
	if !regexp.MustCompile(`^[0-9a-f]{16}$`).MatchString(k) {
		http.NotFound(w, r)
		return
	}
	b, err := os.ReadFile(promoImgPath(k))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	s := string(b)
	i := strings.Index(s, ",")
	raw, err := base64.StdEncoding.DecodeString(s[i+1:])
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", strings.TrimSuffix(strings.TrimPrefix(s[:i], "data:"), ";base64"))
	w.Header().Set("Cache-Control", "max-age=86400")
	_, _ = w.Write(raw)
}

// ---- Firebase ----------------------------------------------------------------------------

// syncPromo sends the dish list when it changed (called from the cloud loop; retries every minute).
func syncPromo(now time.Time) {
	promoMu.Lock()
	if !promoDirty || now.Before(promoTryAt) {
		promoMu.Unlock()
		return
	}
	promoTryAt = now.Add(promoSyncWait)
	p := promoState{Custom: promo.Custom, Dishes: append([]Dish(nil), promo.Dishes...)}
	promoMu.Unlock()

	err := sendPromo(p, now)
	promoMu.Lock()
	defer promoMu.Unlock()
	if err != nil {
		promoErr = err.Error()
		log.Printf("dish carousel: %v", err)
		return
	}
	promoErr = ""
	promoSentAt = now
	// changed again while sending: send again on the next round
	if promo.Custom == p.Custom && dishesEqual(promo.Dishes, p.Dishes) {
		promoDirty = false
	} else {
		promoTryAt = time.Time{}
	}
}

func dishesEqual(a, b []Dish) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func sendPromo(p promoState, now time.Time) error {
	base := outletBase()
	if base == "" {
		return errors.New("no outlet name")
	}
	if !p.Custom {
		// built-in dishes: nothing online
		if err := fb(http.MethodDelete, base+"/promo", "", nil); err != nil {
			return err
		}
		return fb(http.MethodDelete, base+"/pimg", "", nil)
	}
	// photos first (only the missing ones), then the list, then remove old photos
	online := map[string]bool{}
	if b, err := fbGet(base+"/pimg", "shallow=true"); err == nil {
		var keys map[string]any
		if json.Unmarshal(b, &keys) == nil {
			for k := range keys {
				online[k] = true
			}
		}
	} else {
		return err
	}
	want := map[string]bool{}
	for _, d := range p.Dishes {
		want[d.K] = true
		if online[d.K] {
			continue
		}
		img, err := os.ReadFile(promoImgPath(d.K))
		if err != nil {
			return errors.New("photo missing on this PC: " + d.T)
		}
		body, _ := json.Marshal(string(img))
		if err := fb(http.MethodPut, base+"/pimg/"+d.K, "", body); err != nil {
			return err
		}
	}
	list := p.Dishes
	if list == nil {
		list = []Dish{}
	}
	// "n": Firebase drops an empty list, so 0 dishes must still be readable as 0
	body, _ := json.Marshal(map[string]any{"v": now.UnixMilli(), "n": len(list), "s": list})
	if err := fb(http.MethodPut, base+"/promo", "", body); err != nil {
		return err
	}
	for k := range online {
		if !want[k] {
			_ = fb(http.MethodDelete, base+"/pimg/"+k, "", nil)
		}
	}
	return nil
}

// markPromoDirty: send the dishes again (e.g. new outlet name).
func markPromoDirty() {
	promoMu.Lock()
	promoDirty = true
	promoTryAt = time.Time{}
	promoMu.Unlock()
}
