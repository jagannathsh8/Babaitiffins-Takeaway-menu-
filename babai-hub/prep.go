package main

// Prep Live base data, kept on this PC for every Prep Live tablet:
//
//	prep/profile.json   recipes (BOM): prep items, dish -> quantity per portion, units
//	prep/seed.txt       Petpooja history the tablets came with ("T|date|item|h:v ..." / "D|date|dish|h:v ...")
//	prep/days/<date>.txt every day a tablet recorded (T/D/K lines, "C" = complete day)
//	prep/holidays.txt   holiday dates (projection uses past Sundays on those days)
//
// The tablets still do the projection themselves (same code as before). On start they read all
// of this from the hub; they send their recorded days whenever one is finished, and the first
// tablet that connects sends the data it came with. So nothing has to be typed in: the old data
// arrives from the tablet, new days are added by themselves, and a new or reset tablet gets the
// whole history at once. If the hub is off, a tablet keeps working on its own data.

import (
	"bufio"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const prepMaxBody = 24 << 20

var (
	prepMu     sync.Mutex
	reDate     = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)
	prepLastUp time.Time
	prepLastBy string
)

func prepDir() string     { return filepath.Join(dataDir, "prep") }
func prepDaysDir() string { return filepath.Join(prepDir(), "days") }

// prepDay is one date's lines, with what decides which copy is kept.
type prepDay struct {
	lines    []string
	complete bool
	total    float64
}

func (d prepDay) better(o prepDay) bool {
	if d.complete != o.complete {
		return d.complete
	}
	return d.total > o.total+1e-9
}

// splitHistory: a history text -> its days and holiday dates.
func splitHistory(text string) (map[string]*prepDay, []string) {
	days := map[string]*prepDay{}
	var hol []string
	sc := bufio.NewScanner(strings.NewReader(text))
	sc.Buffer(make([]byte, 64<<10), 4<<20)
	for sc.Scan() {
		line := strings.TrimRight(sc.Text(), "\r")
		p := strings.SplitN(line, "|", 4)
		if len(p) < 3 || !reDate.MatchString(p[1]) {
			continue
		}
		date := p[1]
		switch p[0] {
		case "H":
			hol = append(hol, date)
			continue
		case "T", "D", "K", "C":
		default:
			continue
		}
		d := days[date]
		if d == nil {
			d = &prepDay{}
			days[date] = d
		}
		switch p[0] {
		case "C":
			d.complete = true
		case "T":
			if len(p) == 4 {
				for _, cell := range strings.Fields(p[3]) {
					if i := strings.IndexByte(cell, ':'); i > 0 {
						v, _ := strconv.ParseFloat(cell[i+1:], 64)
						d.total += v
					}
				}
			}
		}
		if p[0] != "C" {
			d.lines = append(d.lines, line)
		}
	}
	return days, hol
}

func readPrepDay(date string) (prepDay, bool) {
	b, err := os.ReadFile(filepath.Join(prepDaysDir(), date+".txt"))
	if err != nil {
		return prepDay{}, false
	}
	days, _ := splitHistory(string(b))
	d, ok := days[date]
	if !ok {
		return prepDay{}, false
	}
	return *d, true
}

func writePrepDay(date string, d prepDay) error {
	var b strings.Builder
	for _, l := range d.lines {
		b.WriteString(l)
		b.WriteByte('\n')
	}
	if d.complete {
		b.WriteString("C|" + date + "||\n")
	}
	_ = os.MkdirAll(prepDaysDir(), 0o755)
	return writeFileAtomic(filepath.Join(prepDaysDir(), date+".txt"), []byte(b.String()))
}

// mergeHistory keeps, for each date, the better copy (a complete day beats a part day; then the
// one with more recorded). Holidays: the sender's list replaces the stored one when given.
func mergeHistory(text string, replaceHolidays bool) (added, updated int, err error) {
	prepMu.Lock()
	defer prepMu.Unlock()
	days, hol := splitHistory(text)
	for date, d := range days {
		old, ok := readPrepDay(date)
		if ok && !d.better(old) {
			continue
		}
		if err = writePrepDay(date, *d); err != nil {
			return
		}
		if ok {
			updated++
		} else {
			added++
		}
	}
	if replaceHolidays {
		sort.Strings(hol)
		err = writeFileAtomic(filepath.Join(prepDir(), "holidays.txt"), []byte(strings.Join(hol, "\n")))
	}
	return
}

func prepHistoryText() (string, []string, int) {
	prepMu.Lock()
	defer prepMu.Unlock()
	files, _ := filepath.Glob(filepath.Join(prepDaysDir(), "*.txt"))
	sort.Strings(files)
	var b strings.Builder
	for _, f := range files {
		if raw, err := os.ReadFile(f); err == nil {
			b.Write(raw)
		}
	}
	var hol []string
	if raw, err := os.ReadFile(filepath.Join(prepDir(), "holidays.txt")); err == nil {
		for _, l := range strings.Split(string(raw), "\n") {
			if l = strings.TrimSpace(l); reDate.MatchString(l) {
				hol = append(hol, l)
			}
		}
	}
	return b.String(), hol, len(files)
}

func readPrepFile(name string) string {
	b, err := os.ReadFile(filepath.Join(prepDir(), name))
	if err != nil {
		return ""
	}
	return string(b)
}

func shortHash(s string) string {
	if s == "" {
		return ""
	}
	h := sha1.Sum([]byte(s))
	return hex.EncodeToString(h[:6])
}

// validProfile: a recipes file the tablets can use (targets + menu).
func validProfile(s string) bool {
	var p struct {
		Targets []string                      `json:"targets"`
		Menu    map[string]map[string]float64 `json:"menu"`
	}
	return json.Unmarshal([]byte(s), &p) == nil && len(p.Targets) > 0 && len(p.Menu) > 0
}

// fromKDS: requests that change data come from the KDS app (a browser page can't add this header
// to another site's address without asking first).
func fromKDS(r *http.Request) bool {
	return r.Method == http.MethodPost && r.Header.Get("X-Babai-KDS") == "1"
}

// handlePrepBase: GET /prep/base -> everything a Prep Live tablet needs (shop Wi-Fi).
// ?have=<hash of the recipes+seed it already has> leaves those out.
func handlePrepBase(w http.ResponseWriter, r *http.Request) {
	profile, seed := readPrepFile("profile.json"), readPrepFile("seed.txt")
	hist, hol, n := prepHistoryText()
	base := shortHash(profile + "\x00" + seed)
	out := map[string]any{"v": 1, "base": base, "history": hist, "holidays": hol, "days": n, "hub": version}
	if r.URL.Query().Get("have") != base || base == "" {
		out["profile"], out["seed"] = profile, seed
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(out)
}

// handlePrepSeed: POST {profile, seed} from a tablet: kept only when the hub has none yet.
func handlePrepSeed(w http.ResponseWriter, r *http.Request) {
	if !fromKDS(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Profile, Seed string }
	if json.NewDecoder(io.LimitReader(r.Body, prepMaxBody)).Decode(&in) != nil || !validProfile(in.Profile) {
		reply(w, false, "bad data")
		return
	}
	prepMu.Lock()
	defer prepMu.Unlock()
	if readPrepFile("profile.json") != "" {
		reply(w, true, "hub already has recipes")
		return
	}
	_ = os.MkdirAll(prepDir(), 0o755)
	if err := writeFileAtomic(filepath.Join(prepDir(), "seed.txt"), []byte(in.Seed)); err != nil {
		reply(w, false, err.Error())
		return
	}
	if err := writeFileAtomic(filepath.Join(prepDir(), "profile.json"), []byte(in.Profile)); err != nil {
		reply(w, false, err.Error())
		return
	}
	log.Printf("prep live: recipes and Petpooja history received from a tablet")
	reply(w, true, "saved")
}

// handlePrepHistory: POST (text body) a tablet's recorded days.
func handlePrepHistory(w http.ResponseWriter, r *http.Request) {
	if !fromKDS(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	b, err := io.ReadAll(io.LimitReader(r.Body, prepMaxBody))
	if err != nil {
		reply(w, false, "bad data")
		return
	}
	a, u, err := mergeHistory(string(b), true)
	if err != nil {
		reply(w, false, err.Error())
		return
	}
	host, _, _ := strings.Cut(r.RemoteAddr, ":")
	prepMu.Lock()
	prepLastUp, prepLastBy = time.Now(), host
	prepMu.Unlock()
	reply(w, true, strconv.Itoa(a)+" new days, "+strconv.Itoa(u)+" updated")
}

// handlePrepAdmin (hub page): GET status; POST {op: "profile"|"seed"|"history", data}.
func handlePrepAdmin(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_ = json.NewEncoder(w).Encode(prepStatus())
		return
	}
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Op, Data string }
	if json.NewDecoder(io.LimitReader(r.Body, prepMaxBody)).Decode(&in) != nil {
		reply(w, false, "Couldn't read the file (too big?).")
		return
	}
	_ = os.MkdirAll(prepDir(), 0o755)
	switch in.Op {
	case "profile":
		if !validProfile(in.Data) {
			reply(w, false, "That is not a Prep Live recipes file (prep_profile.json).")
			return
		}
		prepMu.Lock()
		err := writeFileAtomic(filepath.Join(prepDir(), "profile.json"), []byte(in.Data))
		prepMu.Unlock()
		if err != nil {
			reply(w, false, err.Error())
			return
		}
		reply(w, true, "Recipes saved. Tablets use them the next time Prep Live starts.")
	case "seed":
		days, _ := splitHistory(in.Data)
		if len(days) == 0 {
			reply(w, false, "No days found in that file (prep_days.txt).")
			return
		}
		prepMu.Lock()
		err := writeFileAtomic(filepath.Join(prepDir(), "seed.txt"), []byte(in.Data))
		prepMu.Unlock()
		if err != nil {
			reply(w, false, err.Error())
			return
		}
		reply(w, true, "Petpooja history saved ("+strconv.Itoa(len(days))+" days). Tablets use it the next time Prep Live starts.")
	case "history":
		a, u, err := mergeHistory(in.Data, false)
		if err != nil {
			reply(w, false, err.Error())
			return
		}
		if a+u == 0 {
			reply(w, false, "Nothing new in that file.")
			return
		}
		reply(w, true, strconv.Itoa(a)+" days added, "+strconv.Itoa(u)+" updated.")
	default:
		reply(w, false, "Unknown change.")
	}
}

func prepStatus() map[string]any {
	profile, seed := readPrepFile("profile.json"), readPrepFile("seed.txt")
	items := 0
	if profile != "" {
		var p struct {
			Targets []string `json:"targets"`
		}
		if json.Unmarshal([]byte(profile), &p) == nil {
			items = len(p.Targets)
		}
	}
	seedDays, _ := splitHistory(seed)
	sd := make([]string, 0, len(seedDays))
	for d := range seedDays {
		sd = append(sd, d)
	}
	sort.Strings(sd)
	files, _ := filepath.Glob(filepath.Join(prepDaysDir(), "*.txt"))
	sort.Strings(files)
	var td []string
	for _, f := range files {
		td = append(td, strings.TrimSuffix(filepath.Base(f), ".txt"))
	}
	st := map[string]any{"items": items, "seedDays": len(sd), "days": len(td), "plannerKey": plannerKey(), "planState": planStatus()}
	if len(sd) > 0 {
		st["seedFrom"], st["seedTo"] = sd[0], sd[len(sd)-1]
	}
	if len(td) > 0 {
		st["from"], st["to"] = td[0], td[len(td)-1]
	}
	prepMu.Lock()
	if !prepLastUp.IsZero() {
		st["lastUp"], st["lastBy"] = prepLastUp.UnixMilli(), prepLastBy
	}
	prepMu.Unlock()
	return st
}
