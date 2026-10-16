//go:build !windows

package main

// Printers outside Windows (tests and trying the Console on Linux): BABAI_FAKE_PRINTERS lists
// printer names; each print is written to BABAI_FAKE_PRINT_DIR/<name>.<n>.bin. A file
// <name>.problem in that folder makes the printer report that problem.

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"time"
)

var fakeSeq atomic.Uint32

func fakeDir() string { return os.Getenv("BABAI_FAKE_PRINT_DIR") }

func listWindowsPrinters() ([]PrinterInfo, error) {
	out := []PrinterInfo{}
	for _, n := range strings.Split(os.Getenv("BABAI_FAKE_PRINTERS"), ",") {
		if n = strings.TrimSpace(n); n != "" {
			out = append(out, PrinterInfo{Name: n, DriverName: "EPSON TM-T82 Receipt", PortName: "IP_192.168.0.50", Problem: windowsPrinterProblem(n)})
		}
	}
	return out, nil
}

func fakeKnown(name string) bool {
	for _, n := range strings.Split(os.Getenv("BABAI_FAKE_PRINTERS"), ",") {
		if strings.TrimSpace(n) == name {
			return true
		}
	}
	return false
}

func windowsPrinterProblem(name string) string {
	if !fakeKnown(name) {
		return "not installed on this PC"
	}
	if b, err := os.ReadFile(filepath.Join(fakeDir(), name+".problem")); err == nil {
		return strings.TrimSpace(string(b))
	}
	return ""
}

func sendWindowsRaw(name string, data []byte, doc string) (uint32, error) {
	if !fakeKnown(name) {
		return 0, errors.New("printer not found on this PC")
	}
	if p := windowsPrinterProblem(name); p == "refuses" {
		return 0, errors.New("Windows didn't take the print")
	}
	id := fakeSeq.Add(1)
	if d := fakeDir(); d != "" {
		_ = os.WriteFile(filepath.Join(d, name+"."+itoa(int(id))+".bin"), data, 0o644)
	}
	return id, nil
}

func watchWindowsJob(name string, id uint32, limit time.Duration) string {
	if b, err := os.ReadFile(filepath.Join(fakeDir(), name+".stuck")); err == nil {
		return strings.TrimSpace(string(b))
	}
	return ""
}

func cancelWindowsJob(name string, id uint32) {
	if d := fakeDir(); d != "" {
		_ = os.Rename(filepath.Join(d, name+"."+itoa(int(id))+".bin"), filepath.Join(d, name+"."+itoa(int(id))+".cancelled"))
	}
}
