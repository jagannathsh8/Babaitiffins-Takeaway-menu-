package main

// A barcode scanner on the Console PC marks orders Food Ready, the same way the scanner phones do
// (markFromScan: same matching, same "already ready" answer, same log).
//
//   - Keyboard mode (most scanners as they come): the scanner types the number + Enter into the
//     window in front. The Console page catches it on every tab (console/wedge.js) and posts it
//     here. Only while the Console window is in front: in Petpooja it types into Petpooja.
//   - COM mode (the scanner switched to "USB COM / virtual serial" by its setup barcode): the
//     Console reads the port itself, in the background, whatever window is in front. The page
//     shows those scans from the feed below.

import (
	"encoding/json"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// pcScanEvent: one COM scan, for the Console page's toast and beep.
type pcScanEvent struct {
	Seq     int64  `json:"seq"`
	OK      bool   `json:"ok"`
	Already bool   `json:"already,omitempty"`
	Msg     string `json:"m"`
	Brand   string `json:"b,omitempty"`
	Last4   string `json:"l,omitempty"`
	Slot    string `json:"s,omitempty"`
	Code    string `json:"c"`
	At      int64  `json:"t"`
}

var (
	pcFeedMu sync.Mutex
	pcFeed   []pcScanEvent
	pcSeq    int64
)

func pcEvent(res ScanResult, code string) pcScanEvent {
	return pcScanEvent{OK: res.OK, Already: res.Already, Msg: res.Msg, Brand: res.Brand, Last4: res.Last4, Slot: res.Slot, Code: code, At: res.At}
}

func pushPCFeed(e pcScanEvent) {
	pcFeedMu.Lock()
	defer pcFeedMu.Unlock()
	pcSeq++
	e.Seq = pcSeq
	pcFeed = append(pcFeed, e)
	if len(pcFeed) > 50 {
		pcFeed = pcFeed[len(pcFeed)-50:]
	}
}

// scanCodeOK: a barcode worth marking from (the order number: 4+ digits, nothing odd).
func scanCodeOK(s string) bool {
	if len(s) < 4 || len(s) > 80 {
		return false
	}
	return len(digitsOnly(s)) >= 4
}

// handlePCScan: a keyboard-mode scan caught by the Console page.
func handlePCScan(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ C string }
	if json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&in) != nil {
		reply(w, false, "Couldn't read the scan.")
		return
	}
	code := cleanCode(in.C)
	if !scanCodeOK(code) {
		writeJSON(w, pcEvent(ScanResult{Msg: "Not an order barcode", At: time.Now().UnixMilli()}, code))
		return
	}
	res := markFromScan("", code, "PC scanner", "pc")
	writeJSON(w, pcEvent(res, code))
}

// handlePCScanFeed: COM scans after ?after=N. after=-1 (the page opening): just the current number.
func handlePCScanFeed(w http.ResponseWriter, r *http.Request) {
	after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	pcFeedMu.Lock()
	out := []pcScanEvent{}
	if after >= 0 {
		for _, e := range pcFeed {
			if e.Seq > after {
				out = append(out, e)
			}
		}
	}
	seq := pcSeq
	pcFeedMu.Unlock()
	st := comStatus()
	writeJSON(w, map[string]any{"seq": seq, "items": out, "com": st.Port, "comOn": st.Open})
}

// ---- COM mode ------------------------------------------------------------------------------------

type comState struct {
	Port     string `json:"port"`          // chosen in the Console ("" = off)
	Open     bool   `json:"open"`          // reading now
	Err      string `json:"err,omitempty"` // why not
	LastCode string `json:"lastCode,omitempty"`
	LastAt   int64  `json:"lastAt,omitempty"`
}

var (
	comMu   sync.Mutex
	comSt   comState
	comWake = make(chan struct{}, 1)
)

func comStatus() comState {
	comMu.Lock()
	defer comMu.Unlock()
	s := comSt
	s.Port = getConfig().ScanCOM
	return s
}

func setComState(open bool, err string) {
	comMu.Lock()
	comSt.Open, comSt.Err = open, err
	comMu.Unlock()
}

// comLine: one scan read from the COM port.
func comLine(port, line string) {
	code := cleanCode(strings.Map(func(r rune) rune {
		if r < 32 || r == 127 {
			return -1
		}
		return r
	}, line))
	if !scanCodeOK(code) {
		return
	}
	comMu.Lock()
	comSt.LastCode, comSt.LastAt = code, time.Now().UnixMilli()
	comMu.Unlock()
	res := markFromScan("", code, "PC scanner ("+port+")", "com")
	pushPCFeed(pcEvent(res, code))
}

// lineSplitter: bytes from the port -> scans. A scan ends with Enter / Tab, or (scanners set to
// send nothing after the number) when the port goes quiet.
type lineSplitter struct{ buf []byte }

func (s *lineSplitter) feed(b []byte) []string {
	var out []string
	for _, c := range b {
		if c == '\r' || c == '\n' || c == '\t' {
			if len(s.buf) > 0 {
				out = append(out, string(s.buf))
				s.buf = s.buf[:0]
			}
			continue
		}
		if len(s.buf) < 200 {
			s.buf = append(s.buf, c)
		}
	}
	return out
}

func (s *lineSplitter) quiet() string {
	if len(s.buf) == 0 {
		return ""
	}
	v := string(s.buf)
	s.buf = s.buf[:0]
	return v
}

// handleComScan: GET the COM ports and the state; POST {Port} (PIN) chooses the port ("" = off).
func handleComScan(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost {
		pinnedAPI(func(w http.ResponseWriter, r *http.Request) {
			if !fromOwnPage(r) {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			var in struct{ Port string }
			if json.NewDecoder(io.LimitReader(r.Body, 512)).Decode(&in) != nil {
				reply(w, false, "Couldn't read the form.")
				return
			}
			p := strings.ToUpper(strings.TrimSpace(in.Port))
			if p != "" && !validComName(p) {
				reply(w, false, "Not a COM port: "+p)
				return
			}
			c := getConfig()
			c.ScanCOM = p
			if err := saveConfig(c); err != nil {
				reply(w, false, "Couldn't save: "+err.Error())
				return
			}
			select {
			case comWake <- struct{}{}:
			default:
			}
			if p == "" {
				reply(w, true, "COM scanner off.")
				return
			}
			reply(w, true, "Saved. Reading the scanner on "+p+".")
		})(w, r)
		return
	}
	writeJSON(w, map[string]any{"state": comStatus(), "ports": comPorts()})
}

func validComName(p string) bool {
	if !strings.HasPrefix(p, "COM") || len(p) < 4 || len(p) > 6 {
		return false
	}
	n, err := strconv.Atoi(p[3:])
	return err == nil && n >= 1 && n <= 256
}

// comScanLoop keeps the chosen port open and reads it; reopens after an unplug or a change.
func comScanLoop() {
	for {
		port := getConfig().ScanCOM
		if port == "" {
			setComState(false, "")
			select {
			case <-comWake:
			case <-time.After(5 * time.Second):
			}
			continue
		}
		err := readComPort(port, func() bool { return getConfig().ScanCOM != port })
		if err != nil {
			setComState(false, err.Error())
		}
		select {
		case <-comWake:
		case <-time.After(3 * time.Second):
		}
	}
}
