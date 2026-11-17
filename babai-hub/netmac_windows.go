//go:build windows

package main

import (
	"encoding/binary"
	"fmt"
	"net"
	"unsafe"

	"golang.org/x/sys/windows"
)

var procSendARP = windows.NewLazySystemDLL("iphlpapi.dll").NewProc("SendARP")

// macOf: the hardware address of a device on this PC's network (Windows asks for it by ARP),
// "" when it doesn't answer or isn't on the same network.
func macOf(ip string) string {
	p := net.ParseIP(ip).To4()
	if p == nil {
		return ""
	}
	var mac [8]byte
	n := uint32(len(mac))
	r, _, _ := procSendARP.Call(uintptr(binary.LittleEndian.Uint32(p)), 0, uintptr(unsafe.Pointer(&mac[0])), uintptr(unsafe.Pointer(&n)))
	if r != 0 || n < 6 {
		return ""
	}
	return fmt.Sprintf("%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5])
}
