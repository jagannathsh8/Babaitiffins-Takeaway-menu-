package main

// Network printers that follow their printer (4.18). A network printer is saved by its IP; the
// router can give it another one. The Console remembers each printer's hardware address (MAC),
// which never changes: when a printer stops answering at its IP, the Console looks for that MAC
// on the shop network, takes the new IP and prints there. Find lists the printers on the network.

import (
	"fmt"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

var lanRetry = 20 * time.Second

var (
	macLookup = macOf   // tests replace these two
	lanScan   = scanLan // the addresses on this PC's networks with the port open

	lanFindMu   sync.Mutex
	lanFinding  = map[string]bool{}      // printer name -> a search is running
	lanNotFound = map[string]time.Time{} // printer name -> when "not found" was last alerted
	lanTried    = map[string]time.Time{} // printer name -> the last search (not more than one every 20 s)
)

// scanLan: every address of this PC's /24 networks that accepts a connection on the port.
func scanLan(port int) []string {
	var cands []string
	seen := map[string]bool{}
	for _, me := range localIPv4s() {
		ip := net.ParseIP(me).To4()
		if ip == nil || ip.IsLoopback() || !ip.IsPrivate() {
			continue
		}
		pre := fmt.Sprintf("%d.%d.%d.", ip[0], ip[1], ip[2])
		for d := 1; d <= 254; d++ {
			if a := pre + strconv.Itoa(d); a != me && !seen[a] {
				seen[a] = true
				cands = append(cands, a)
			}
		}
	}
	var mu sync.Mutex
	var out []string
	sem := make(chan struct{}, 64)
	var wg sync.WaitGroup
	for _, a := range cands {
		wg.Add(1)
		sem <- struct{}{}
		go func(a string) {
			defer wg.Done()
			defer func() { <-sem }()
			c, err := net.DialTimeout("tcp", net.JoinHostPort(a, strconv.Itoa(port)), 500*time.Millisecond)
			if err == nil {
				c.Close()
				mu.Lock()
				out = append(out, a)
				mu.Unlock()
			}
		}(a)
	}
	wg.Wait()
	sort.Slice(out, func(i, j int) bool { return ipLess(out[i], out[j]) })
	return out
}

func ipLess(a, b string) bool {
	x, y := net.ParseIP(a).To4(), net.ParseIP(b).To4()
	if x == nil || y == nil {
		return a < b
	}
	return string(x) < string(y)
}

func lanPort(l LanPrinter) int {
	if l.Port <= 0 {
		return 9100
	}
	return l.Port
}

func lanAnswers(l LanPrinter) bool {
	c, err := net.DialTimeout("tcp", net.JoinHostPort(l.IP, strconv.Itoa(lanPort(l))), 700*time.Millisecond)
	if err != nil {
		return false
	}
	c.Close()
	return true
}

// lanWatch: every minute, learn the hardware address of each network printer that answers, and
// look for the ones that don't.
func lanWatch() {
	for {
		lanCheckAll()
		time.Sleep(time.Minute)
	}
}

func lanCheckAll() {
	prMu.Lock()
	list := append([]LanPrinter(nil), prOpts.Lan...)
	prMu.Unlock()
	for _, l := range list {
		if lanAnswers(l) {
			if l.MAC == "" {
				if m := macLookup(l.IP); m != "" {
					lanSet(l.Name, func(x *LanPrinter) { x.MAC = m })
				}
			}
			lanFindMu.Lock()
			if _, was := lanNotFound[strings.ToLower(l.Name)]; was {
				delete(lanNotFound, strings.ToLower(l.Name))
				lanFindMu.Unlock()
				addAlert("ok", lanPrefix+l.Name, fmt.Sprintf("%s is answering again at %s.", l.Name, l.IP), 0)
				continue
			}
			lanFindMu.Unlock()
		} else {
			lanRelocate(l.Name)
		}
	}
}

func lanSet(name string, f func(*LanPrinter)) {
	prMu.Lock()
	defer prMu.Unlock()
	for i := range prOpts.Lan {
		if strings.EqualFold(prOpts.Lan[i].Name, name) {
			f(&prOpts.Lan[i])
			_ = savePrintOptionsLocked()
		}
	}
}

// lanRelocateSoon: a print just found this network printer not answering: look for it now, in
// the background (the backup printer prints meanwhile).
func lanRelocateSoon(printer string) {
	if !strings.HasPrefix(printer, lanPrefix) || strings.HasPrefix(printer, lanPrefix+"@") {
		return
	}
	go lanRelocate(strings.TrimPrefix(printer, lanPrefix))
}

// lanRelocate: the printer doesn't answer at its IP: find its hardware address on the network
// and take that IP. Returns true when it moved.
func lanRelocate(name string) bool {
	key := strings.ToLower(name)
	lanFindMu.Lock()
	if lanFinding[key] || time.Since(lanTried[key]) < lanRetry {
		lanFindMu.Unlock()
		return false
	}
	lanFinding[key], lanTried[key] = true, time.Now()
	lanFindMu.Unlock()
	defer func() {
		lanFindMu.Lock()
		delete(lanFinding, key)
		lanFindMu.Unlock()
	}()
	var l LanPrinter
	found := false
	prMu.Lock()
	for _, x := range prOpts.Lan {
		if strings.EqualFold(x.Name, name) {
			l, found = x, true
		}
	}
	prMu.Unlock()
	if !found || lanAnswers(l) {
		return false
	}
	if l.MAC != "" {
		for _, ip := range lanScan(lanPort(l)) {
			if ip != l.IP && strings.EqualFold(macLookup(ip), l.MAC) {
				old := l.IP
				lanSet(name, func(x *LanPrinter) { x.IP = ip })
				lanFindMu.Lock()
				delete(lanNotFound, key)
				lanFindMu.Unlock()
				addAlert("ok", lanPrefix+name, fmt.Sprintf("%s moved to %s (was %s). Found by its hardware ID; printing there now.", name, ip, old), 0)
				return true
			}
		}
	}
	lanFindMu.Lock()
	last := lanNotFound[key]
	if time.Since(last) > 30*time.Minute {
		lanNotFound[key] = time.Now()
	}
	lanFindMu.Unlock()
	if time.Since(last) > 30*time.Minute {
		why := "switched off or unplugged?"
		if l.MAC == "" {
			why = "its hardware ID isn't known yet, so it can't be followed: use Find on the Printing page"
		}
		addAlert("bad", lanPrefix+name, fmt.Sprintf("%s: not answering at %s, and not found on the network (%s). Its backup prints meanwhile; the Console keeps looking.", name, l.IP, why), 0)
	}
	return false
}

// handleNetFind: GET -> the printers on this PC's networks (port 9100), with their hardware IDs
// and, for ones already added, their name. A printer that moved is switched to its new address.
func handleNetFind(w http.ResponseWriter, r *http.Request) {
	ips := lanScan(9100)
	prMu.Lock()
	list := append([]LanPrinter(nil), prOpts.Lan...)
	prMu.Unlock()
	type found struct {
		IP    string `json:"ip"`
		MAC   string `json:"mac"`
		Name  string `json:"name,omitempty"`
		Moved string `json:"moved,omitempty"` // the address it had
	}
	out := []found{}
	for _, ip := range ips {
		f := found{IP: ip, MAC: macLookup(ip)}
		for _, l := range list {
			switch {
			case l.IP == ip && lanPort(l) == 9100:
				f.Name = l.Name
				if l.MAC == "" && f.MAC != "" {
					lanSet(l.Name, func(x *LanPrinter) { x.MAC = f.MAC })
				}
			case f.MAC != "" && strings.EqualFold(l.MAC, f.MAC) && lanPort(l) == 9100 && f.Name == "":
				f.Name, f.Moved = l.Name, l.IP
			}
		}
		if f.Moved != "" {
			name, ip, old := f.Name, f.IP, f.Moved
			lanSet(name, func(x *LanPrinter) { x.IP = ip })
			addAlert("ok", lanPrefix+name, fmt.Sprintf("%s moved to %s (was %s). Found by its hardware ID; printing there now.", name, ip, old), 0)
		}
		out = append(out, f)
	}
	nets := []string{}
	for _, me := range localIPv4s() {
		if ip := net.ParseIP(me).To4(); ip != nil && !ip.IsLoopback() && ip.IsPrivate() {
			nets = append(nets, fmt.Sprintf("%d.%d.%d.x", ip[0], ip[1], ip[2]))
		}
	}
	writeJSON(w, map[string]any{"ok": true, "found": out, "nets": nets})
}
