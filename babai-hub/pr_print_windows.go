//go:build windows

package main

// Windows print queue access (winspool.drv), straight from Go: listing printers, reading their
// state, sending RAW bytes and following a print until it has left the queue.

import (
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

var (
	winspool             = windows.NewLazySystemDLL("winspool.drv")
	procEnumPrinters     = winspool.NewProc("EnumPrintersW")
	procOpenPrinter      = winspool.NewProc("OpenPrinterW")
	procClosePrinter     = winspool.NewProc("ClosePrinter")
	procGetPrinter       = winspool.NewProc("GetPrinterW")
	procStartDocPrinter  = winspool.NewProc("StartDocPrinterW")
	procEndDocPrinter    = winspool.NewProc("EndDocPrinter")
	procStartPagePrinter = winspool.NewProc("StartPagePrinter")
	procEndPagePrinter   = winspool.NewProc("EndPagePrinter")
	procWritePrinter     = winspool.NewProc("WritePrinter")
	procAbortPrinter     = winspool.NewProc("AbortPrinter")
	procEnumJobs         = winspool.NewProc("EnumJobsW")
	procGetJob           = winspool.NewProc("GetJobW")
	procSetJob           = winspool.NewProc("SetJobW")
)

type printerInfo2 struct {
	ServerName, PrinterName, ShareName, PortName, DriverName, Comment, Location *uint16
	DevMode                                                                     uintptr
	SepFile, PrintProcessor, Datatype, Parameters                               *uint16
	SecurityDescriptor                                                          uintptr
	Attributes, Priority, DefaultPriority, StartTime, UntilTime, Status, CJobs  uint32
	AveragePPM                                                                  uint32
}

type jobInfo1 struct {
	JobID                                                        uint32
	PrinterName, MachineName, UserName, Document, Datatype, Stat *uint16
	Status, Priority, Position, TotalPages, PagesPrinted         uint32
	Submitted                                                    windows.Systemtime
}

type docInfo1 struct {
	DocName, OutputFile, Datatype *uint16
}

func u16(p *uint16) string {
	if p == nil {
		return ""
	}
	return windows.UTF16PtrToString(p)
}

func listWindowsPrinters() ([]PrinterInfo, error) {
	const flags = 0x2 | 0x4 // PRINTER_ENUM_LOCAL | PRINTER_ENUM_CONNECTIONS
	var needed, returned uint32
	procEnumPrinters.Call(flags, 0, 2, 0, 0, uintptr(unsafe.Pointer(&needed)), uintptr(unsafe.Pointer(&returned)))
	if needed == 0 {
		return []PrinterInfo{}, nil
	}
	buf := make([]byte, needed)
	r, _, err := procEnumPrinters.Call(flags, 0, 2, uintptr(unsafe.Pointer(&buf[0])), uintptr(needed),
		uintptr(unsafe.Pointer(&needed)), uintptr(unsafe.Pointer(&returned)))
	if r == 0 {
		return nil, fmt.Errorf("can't list printers (is the Print Spooler running?): %v", err)
	}
	size := unsafe.Sizeof(printerInfo2{})
	out := make([]PrinterInfo, 0, returned)
	for i := uint32(0); i < returned; i++ {
		p := (*printerInfo2)(unsafe.Pointer(&buf[uintptr(i)*size]))
		name := u16(p.PrinterName)
		pi := PrinterInfo{Name: name, DriverName: u16(p.DriverName), PortName: u16(p.PortName), Jobs: int(p.CJobs)}
		pi.Problem = statusProblem(p.Status, p.Attributes)
		if pi.Problem == "" && portIsThisPC(pi.PortName) {
			pi.Problem = "points back to this PC (the incoming queue)"
		}
		out = append(out, pi)
	}
	return out, nil
}

func statusProblem(status, attrs uint32) string {
	switch {
	case attrs&0x400 != 0:
		return "set to 'Use printer offline'"
	case status&0x80 != 0:
		return "offline"
	case status&0x10 != 0:
		return "out of paper"
	case status&0x8 != 0:
		return "paper jam"
	case status&0x400000 != 0:
		return "cover open"
	case status&0x1000 != 0:
		return "not available"
	case status&0x100000 != 0:
		return "needs attention"
	case status&0x2 != 0:
		return "in error"
	case status&0x1 != 0:
		return "paused"
	case status&0x40000 != 0:
		return "out of ink/toner"
	}
	return ""
}

func openPrinter(name string) (syscall.Handle, error) {
	n, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return 0, err
	}
	var h syscall.Handle
	r, _, e := procOpenPrinter.Call(uintptr(unsafe.Pointer(n)), uintptr(unsafe.Pointer(&h)), 0)
	if r == 0 {
		return 0, fmt.Errorf("printer %q not found on this PC (%v)", name, e)
	}
	return h, nil
}

func closePrinter(h syscall.Handle) { procClosePrinter.Call(uintptr(h)) }

func windowsPrinterProblem(name string) string {
	h, err := openPrinter(name)
	if err != nil {
		return "not installed on this PC"
	}
	defer closePrinter(h)
	var needed uint32
	procGetPrinter.Call(uintptr(h), 2, 0, 0, uintptr(unsafe.Pointer(&needed)))
	if needed > 0 {
		buf := make([]byte, needed)
		if r, _, _ := procGetPrinter.Call(uintptr(h), 2, uintptr(unsafe.Pointer(&buf[0])), uintptr(needed), uintptr(unsafe.Pointer(&needed))); r != 0 {
			p := (*printerInfo2)(unsafe.Pointer(&buf[0]))
			if s := statusProblem(p.Status, p.Attributes); s != "" {
				return s
			}
			if portIsThisPC(u16(p.PortName)) {
				return "points back to this PC (the incoming queue)"
			}
		}
	}
	for _, j := range enumJobs(h) {
		if s := jobProblem(j.Status); s != "" {
			return "a print is stuck: " + s
		}
		sub := time.Date(int(j.Submitted.Year), time.Month(j.Submitted.Month), int(j.Submitted.Day), int(j.Submitted.Hour),
			int(j.Submitted.Minute), int(j.Submitted.Second), 0, time.UTC)
		if j.Status&(0x80|0x1000) == 0 && time.Since(sub) > 30*time.Second && time.Since(sub) < 24*time.Hour {
			return "a print is stuck in its queue"
		}
	}
	return ""
}

func jobProblem(st uint32) string {
	switch {
	case st&0x40 != 0:
		return "out of paper"
	case st&0x20 != 0:
		return "printer offline"
	case st&0x400 != 0:
		return "printer needs attention"
	case st&0x200 != 0:
		return "blocked"
	case st&0x2 != 0:
		return "printer error"
	}
	return ""
}

func enumJobs(h syscall.Handle) []jobInfo1 {
	var needed, returned uint32
	procEnumJobs.Call(uintptr(h), 0, 50, 1, 0, 0, uintptr(unsafe.Pointer(&needed)), uintptr(unsafe.Pointer(&returned)))
	if needed == 0 {
		return nil
	}
	buf := make([]byte, needed)
	r, _, _ := procEnumJobs.Call(uintptr(h), 0, 50, 1, uintptr(unsafe.Pointer(&buf[0])), uintptr(needed),
		uintptr(unsafe.Pointer(&needed)), uintptr(unsafe.Pointer(&returned)))
	if r == 0 {
		return nil
	}
	size := unsafe.Sizeof(jobInfo1{})
	out := make([]jobInfo1, returned)
	for i := range out {
		out[i] = *(*jobInfo1)(unsafe.Pointer(&buf[uintptr(i)*size]))
	}
	return out
}

// sendWindowsRaw: one RAW print job (OpenPrinter / StartDocPrinter / WritePrinter).
func sendWindowsRaw(name string, data []byte, doc string) (uint32, error) {
	h, err := openPrinter(name)
	if err != nil {
		return 0, err
	}
	defer closePrinter(h)
	dn, _ := windows.UTF16PtrFromString(doc)
	dt, _ := windows.UTF16PtrFromString("RAW")
	di := docInfo1{DocName: dn, Datatype: dt}
	id, _, e := procStartDocPrinter.Call(uintptr(h), 1, uintptr(unsafe.Pointer(&di)))
	if id == 0 {
		return 0, fmt.Errorf("Windows didn't take the print: %v", e)
	}
	ok := false
	defer func() {
		if !ok {
			procAbortPrinter.Call(uintptr(h))
		}
	}()
	if r, _, e := procStartPagePrinter.Call(uintptr(h)); r == 0 {
		return 0, fmt.Errorf("Windows didn't take the print: %v", e)
	}
	for off := 0; off < len(data); {
		var written uint32
		r, _, e := procWritePrinter.Call(uintptr(h), uintptr(unsafe.Pointer(&data[off])), uintptr(len(data)-off), uintptr(unsafe.Pointer(&written)))
		if r == 0 {
			return 0, fmt.Errorf("sending to the printer failed: %v", e)
		}
		if written == 0 {
			return 0, errors.New("the printer accepted no data")
		}
		off += int(written)
	}
	procEndPagePrinter.Call(uintptr(h))
	if r, _, e := procEndDocPrinter.Call(uintptr(h)); r == 0 {
		return 0, fmt.Errorf("finishing the print failed: %v", e)
	}
	ok = true
	return uint32(id), nil
}

// watchWindowsJob waits until the print has left the queue: "" when fine, else the problem.
func watchWindowsJob(name string, id uint32, limit time.Duration) string {
	h, err := openPrinter(name)
	if err != nil {
		return ""
	}
	defer closePrinter(h)
	start := time.Now()
	buf := make([]byte, 4096)
	for {
		var needed uint32
		r, _, _ := procGetJob.Call(uintptr(h), uintptr(id), 1, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)), uintptr(unsafe.Pointer(&needed)))
		if r == 0 {
			return "" // gone from the queue: printed
		}
		j := (*jobInfo1)(unsafe.Pointer(&buf[0]))
		if j.Status&(0x80|0x1000) != 0 {
			return ""
		}
		if s := jobProblem(j.Status); s != "" && time.Since(start) > 1500*time.Millisecond {
			return s
		}
		if time.Since(start) > limit {
			return "stuck in the printer queue"
		}
		time.Sleep(300 * time.Millisecond)
	}
}

func cancelWindowsJob(name string, id uint32) {
	h, err := openPrinter(name)
	if err != nil {
		return
	}
	defer closePrinter(h)
	procSetJob.Call(uintptr(h), uintptr(id), 0, 0, 5) // JOB_CONTROL_DELETE
}

var (
	portCacheMu sync.Mutex
	portCache   = map[string]struct {
		self bool
		at   time.Time
	}{}
)

// portIsThisPC: a Standard TCP/IP port whose address is this PC (a print loop).
func portIsThisPC(port string) bool {
	if port == "" {
		return false
	}
	portCacheMu.Lock()
	if c, ok := portCache[port]; ok && time.Since(c.at) < time.Minute {
		portCacheMu.Unlock()
		return c.self
	}
	portCacheMu.Unlock()
	self := false
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, `SYSTEM\CurrentControlSet\Control\Print\Monitors\Standard TCP/IP Port\Ports\`+port, registry.QUERY_VALUE)
	if err == nil {
		host, _, _ := k.GetStringValue("HostName")
		if host == "" {
			host, _, _ = k.GetStringValue("IPAddress")
		}
		k.Close()
		self = hostIsThisPC(host)
	}
	portCacheMu.Lock()
	portCache[port] = struct {
		self bool
		at   time.Time
	}{self, time.Now()}
	portCacheMu.Unlock()
	return self
}

func hostIsThisPC(host string) bool {
	host = strings.TrimSpace(host)
	if host == "" {
		return false
	}
	ips, err := net.LookupIP(host)
	if err != nil {
		return false
	}
	own := map[string]bool{}
	addrs, _ := net.InterfaceAddrs()
	for _, a := range addrs {
		if n, ok := a.(*net.IPNet); ok {
			own[n.IP.String()] = true
		}
	}
	for _, ip := range ips {
		if ip.IsLoopback() || own[ip.String()] {
			return true
		}
	}
	return false
}
