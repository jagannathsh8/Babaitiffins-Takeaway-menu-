package main

// Babai Console print engine: sending to printers, with a fallback printer per printer.
//
// Windows printers go straight to the Windows print queue (winspool, no PowerShell, a few
// milliseconds); network printers by IP get the bytes over TCP 9100. Before printing, the
// printer's state is read; if it is offline / out of paper / in error, or a print stays stuck
// in its queue, the slip goes to that printer's fallback printer and an alert is shown.

import (
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"
)

// PrinterInfo: Bridge Print's /api/printers shape plus the state.
type PrinterInfo struct {
	Name       string `json:"Name"`
	DriverName string `json:"DriverName"`
	PortName   string `json:"PortName"`
	Lan        bool   `json:"lan,omitempty"`
	Problem    string `json:"problem,omitempty"` // empty = ready
	Jobs       int    `json:"jobs,omitempty"`
}

const lanPrefix = "lan:"

var (
	printerLocks sync.Map // name -> *sync.Mutex: one slip at a time per printer, in order
	alertMu      sync.Mutex
	alerts       []Alert
)

// Alert is shown in Bridge Print → Alerts (and as a banner).
type Alert struct {
	At      int64  `json:"t"`
	Level   string `json:"lvl"` // "bad" / "warn" / "ok"
	Printer string `json:"printer,omitempty"`
	Msg     string `json:"msg"`
	Token   int    `json:"token,omitempty"`
}

func addAlert(level, printer, msg string, token int) {
	alertMu.Lock()
	defer alertMu.Unlock()
	alerts = append(alerts, Alert{At: time.Now().UnixMilli(), Level: level, Printer: printer, Msg: msg, Token: token})
	if len(alerts) > 200 {
		alerts = alerts[len(alerts)-200:]
	}
}

func alertsSnapshot() []Alert {
	alertMu.Lock()
	defer alertMu.Unlock()
	out := make([]Alert, len(alerts))
	for i := range alerts {
		out[len(alerts)-1-i] = alerts[i]
	}
	return out
}

func lockPrinter(name string) func() {
	v, _ := printerLocks.LoadOrStore(strings.ToLower(name), &sync.Mutex{})
	m := v.(*sync.Mutex)
	m.Lock()
	return m.Unlock
}

// allPrinters: Windows printers on this PC plus the network printers added by IP.
func allPrinters() []PrinterInfo {
	list, _ := listWindowsPrinters()
	prMu.Lock()
	lan := append([]LanPrinter(nil), prOpts.Lan...)
	prMu.Unlock()
	for _, l := range lan {
		list = append(list, PrinterInfo{Name: lanPrefix + l.Name, DriverName: "Network printer", PortName: net.JoinHostPort(l.IP, strconv.Itoa(l.Port)), Lan: true})
	}
	return list
}

func lanTarget(name string) (string, bool) {
	if !strings.HasPrefix(name, lanPrefix) {
		return "", false
	}
	n := strings.TrimPrefix(name, lanPrefix)
	if strings.HasPrefix(n, "@") { // the main printer set by IP in Bridge Print's settings
		return strings.TrimPrefix(n, "@"), true
	}
	prMu.Lock()
	defer prMu.Unlock()
	for _, l := range prOpts.Lan {
		if strings.EqualFold(l.Name, n) {
			port := l.Port
			if port == 0 {
				port = 9100
			}
			return net.JoinHostPort(l.IP, strconv.Itoa(port)), true
		}
	}
	return "", true
}

// printerProblem: "" when the printer looks ready, else what is wrong.
func printerProblem(name string) string {
	if name == "" {
		return "no printer chosen"
	}
	if addr, isLan := lanTarget(name); isLan {
		if addr == "" {
			return "network printer not found in the list"
		}
		c, err := net.DialTimeout("tcp", addr, 700*time.Millisecond)
		if err != nil {
			return "not answering on the network"
		}
		c.Close()
		return ""
	}
	return windowsPrinterProblem(name)
}

// sendOnce sends data to one printer; for Windows printers it returns the queue job id.
func sendOnce(name string, data []byte) (uint32, error) {
	if addr, isLan := lanTarget(name); isLan {
		if addr == "" {
			return 0, errors.New("network printer not found in the list")
		}
		return 0, sendTCP(addr, data, 12*time.Second)
	}
	return sendWindowsRaw(name, data, "Babai Console")
}

func sendTCP(addr string, data []byte, timeout time.Duration) error {
	c, err := net.DialTimeout("tcp", addr, 2*time.Second)
	if err != nil {
		return errors.New("printer not answering on the network")
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(timeout))
	if _, err := c.Write(data); err != nil {
		return errors.New("printer connection broke during the transfer; check the paper before reprinting")
	}
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.CloseWrite()
	}
	return nil
}

func fallbackOf(name, override string) string {
	if override != "" {
		return override
	}
	prMu.Lock()
	defer prMu.Unlock()
	return prOpts.Printers[name].Fallback
}

// deliver prints data on printer (or its fallback) and fills out. The slow part (watching the
// print leave the Windows queue) runs afterwards in the background.
func deliver(j *Job, out *PrintOut, printer, fallback string, data []byte, recv time.Time) {
	use := printer
	if p := printerProblem(printer); p != "" && fallback != "" && fallback != printer {
		if printerProblem(fallback) == "" {
			addAlert("bad", printer, fmt.Sprintf("%s: %s. Token %d printed on %s.", printerLabel(printer), p, j.Token, printerLabel(fallback)), j.Token)
			use, out.Fallback, out.Wanted = fallback, true, printer
		}
	}
	unlock := lockPrinter(use)
	id, err := sendOnce(use, data)
	unlock()
	if err != nil && fallback != "" && use != fallback {
		addAlert("bad", use, fmt.Sprintf("%s: %v. Token %d printed on %s.", printerLabel(use), err, j.Token, printerLabel(fallback)), j.Token)
		out.Fallback, out.Wanted, use = true, use, fallback
		unlock = lockPrinter(use)
		id, err = sendOnce(use, data)
		unlock()
	}
	out.Printer = use
	out.Ms = int(time.Since(recv).Milliseconds())
	if err != nil {
		out.OK, out.Error = false, err.Error()
		addAlert("bad", use, fmt.Sprintf("Token %d not printed on %s: %v", j.Token, printerLabel(use), err), j.Token)
		return
	}
	out.OK = true
	if id != 0 {
		go watchPrint(j.ID, out.Rule, use, fallback, id, data)
	}
}

// watchPrint follows a print in the Windows queue; if it errors or is stuck, it is taken out
// and sent to the fallback printer.
func watchPrint(jobID, rule, printer, fallback string, id uint32, data []byte) {
	problem := watchWindowsJob(printer, id, 25*time.Second)
	if problem == "" {
		return
	}
	prMu.Lock()
	j := findJobLocked(jobID)
	token := 0
	if j != nil {
		token = j.Token
	}
	prMu.Unlock()
	if fallback == "" || fallback == printer {
		addAlert("bad", printer, fmt.Sprintf("%s: token %d %s. No fallback printer set.", printerLabel(printer), token, problem), token)
		setOutResult(jobID, rule, printer, false, "printer: "+problem)
		return
	}
	cancelWindowsJob(printer, id)
	unlock := lockPrinter(fallback)
	_, err := sendOnce(fallback, data)
	unlock()
	if err != nil {
		addAlert("bad", fallback, fmt.Sprintf("Token %d: %s %s, and the fallback %s failed: %v", token, printerLabel(printer), problem, printerLabel(fallback), err), token)
		setOutResult(jobID, rule, printer, false, problem+"; fallback failed: "+err.Error())
		return
	}
	addAlert("bad", printer, fmt.Sprintf("%s: token %d %s. Printed again on %s.", printerLabel(printer), token, problem, printerLabel(fallback)), token)
	prMu.Lock()
	if j := findJobLocked(jobID); j != nil {
		for i := range j.Outs {
			if j.Outs[i].Rule == rule && j.Outs[i].Printer == printer {
				j.Outs[i].Wanted, j.Outs[i].Printer, j.Outs[i].Fallback = printer, fallback, true
			}
		}
		saveJobLocked(j)
	}
	prMu.Unlock()
}

func setOutResult(jobID, rule, printer string, ok bool, msg string) {
	prMu.Lock()
	defer prMu.Unlock()
	j := findJobLocked(jobID)
	if j == nil {
		return
	}
	for i := range j.Outs {
		if j.Outs[i].Rule == rule && j.Outs[i].Printer == printer {
			j.Outs[i].OK, j.Outs[i].Error = ok, msg
		}
	}
	if !ok {
		j.Status, j.Error = "uncertain", msg
	}
	saveJobLocked(j)
}

func printerLabel(name string) string { return strings.TrimPrefix(name, lanPrefix) }
