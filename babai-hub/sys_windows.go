//go:build windows

package main

import (
	"os"
	"path/filepath"
)

func appDataDir() string {
	if d := os.Getenv("LOCALAPPDATA"); d != "" {
		return filepath.Join(d, "BabaiHub")
	}
	return filepath.Join(filepath.Dir(exePath()), "BabaiHubData")
}
