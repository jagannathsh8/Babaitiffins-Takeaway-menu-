//go:build windows

package main

// COM-mode barcode scanner on Windows: the port is opened like any serial device (CreateFile
// \\.\COMn), DTR/RTS on (some USB scanners only send with DTR), reads return 50 ms after the
// last byte so one read is one scan.

import (
	"errors"
	"sort"
	"strconv"
	"strings"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

var (
	kernel32c        = windows.NewLazySystemDLL("kernel32.dll")
	procGetCommState = kernel32c.NewProc("GetCommState")
	procSetCommState = kernel32c.NewProc("SetCommState")
)

// winDCB is Windows' DCB (serial settings).
type winDCB struct {
	DCBlength  uint32
	BaudRate   uint32
	Flags      uint32
	wReserved  uint16
	XonLim     uint16
	XoffLim    uint16
	ByteSize   byte
	Parity     byte
	StopBits   byte
	XonChar    byte
	XoffChar   byte
	ErrorChar  byte
	EofChar    byte
	EvtChar    byte
	wReserved1 uint16
}

type comPort struct {
	Name string `json:"name"` // COM3
	Dev  string `json:"dev"`  // \Device\USBSER000 (USBSER: a USB scanner or phone in serial mode)
}

// comPorts: the serial ports Windows has now (HKLM\HARDWARE\DEVICEMAP\SERIALCOMM).
func comPorts() []comPort {
	out := []comPort{}
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, `HARDWARE\DEVICEMAP\SERIALCOMM`, registry.QUERY_VALUE)
	if err != nil {
		return out
	}
	defer k.Close()
	names, _ := k.ReadValueNames(0)
	for _, n := range names {
		if v, _, err := k.GetStringValue(n); err == nil && validComName(strings.ToUpper(v)) {
			out = append(out, comPort{Name: strings.ToUpper(v), Dev: n})
		}
	}
	sort.Slice(out, func(i, j int) bool {
		a, _ := strconv.Atoi(out[i].Name[3:])
		b, _ := strconv.Atoi(out[j].Name[3:])
		return a < b
	})
	return out
}

// readComPort reads scans until the port fails or stop() says the setting changed.
func readComPort(port string, stop func() bool) error {
	path, _ := windows.UTF16PtrFromString(`\\.\` + port)
	h, err := windows.CreateFile(path, windows.GENERIC_READ|windows.GENERIC_WRITE, 0, nil, windows.OPEN_EXISTING, 0, 0)
	if err != nil {
		if errors.Is(err, windows.ERROR_ACCESS_DENIED) {
			return errors.New(port + " is in use by another program")
		}
		if errors.Is(err, windows.ERROR_FILE_NOT_FOUND) {
			return errors.New(port + " not found: is the scanner plugged in and in COM mode?")
		}
		return errors.New(port + ": " + err.Error())
	}
	defer windows.CloseHandle(h)

	var dcb winDCB
	dcb.DCBlength = uint32(unsafe.Sizeof(dcb))
	if r, _, e := procGetCommState.Call(uintptr(h), uintptr(unsafe.Pointer(&dcb))); r == 0 {
		return errors.New(port + ": can't read its settings: " + e.Error())
	}
	dcb.BaudRate, dcb.ByteSize, dcb.Parity, dcb.StopBits = 9600, 8, 0, 0
	dcb.Flags = 0x0001 | 0x0010 | 0x1000 // binary, DTR on, RTS on
	if r, _, e := procSetCommState.Call(uintptr(h), uintptr(unsafe.Pointer(&dcb))); r == 0 {
		return errors.New(port + ": can't set it up: " + e.Error())
	}
	// a read returns 50 ms after the last byte (one scan), or after 500 ms with nothing
	if err := windows.SetCommTimeouts(h, &windows.CommTimeouts{ReadIntervalTimeout: 50, ReadTotalTimeoutConstant: 500}); err != nil {
		return errors.New(port + ": " + err.Error())
	}
	setComState(true, "")
	var sp lineSplitter
	buf := make([]byte, 256)
	for !stop() {
		var n uint32
		if err := windows.ReadFile(h, buf, &n, nil); err != nil {
			return errors.New(port + " stopped: " + err.Error() + " (unplugged?)")
		}
		if n == 0 {
			if v := sp.quiet(); v != "" { // a scanner that sends no Enter
				comLine(port, v)
			}
			continue
		}
		for _, line := range sp.feed(buf[:n]) {
			comLine(port, line)
		}
	}
	return nil
}
