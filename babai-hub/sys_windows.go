//go:build windows

package main

import (
	"os"
	"path/filepath"
)

// appDataDir: C:\ProgramData\BabaiConsole (shared by the service and every user of the PC).
func appDataDir() string {
	if d := os.Getenv("BABAI_HUB_DATA"); d != "" {
		return d
	}
	if d := os.Getenv("ProgramData"); d != "" {
		return filepath.Join(d, "BabaiConsole")
	}
	return filepath.Join(filepath.Dir(exePath()), "BabaiConsoleData")
}
