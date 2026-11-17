//go:build !windows

package main

import (
	"os"
	"strings"
)

// macOf: the hardware address of a device this PC has talked to (the kernel's ARP table).
func macOf(ip string) string {
	b, err := os.ReadFile("/proc/net/arp")
	if err != nil {
		return ""
	}
	for _, l := range strings.Split(string(b), "\n") {
		f := strings.Fields(l)
		if len(f) >= 4 && f[0] == ip && f[3] != "00:00:00:00:00:00" {
			return strings.ToUpper(f[3])
		}
	}
	return ""
}
