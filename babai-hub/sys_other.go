//go:build !windows

package main

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
)

func appDataDir() string {
	if d := os.Getenv("BABAI_HUB_DATA"); d != "" {
		return d
	}
	h, _ := os.UserHomeDir()
	return filepath.Join(h, ".babai-hub")
}

func openBrowser(url string) { _ = exec.Command("xdg-open", url).Start() }

func autostartEnabled() bool { return false }

func setAutostart(on bool) error { return errors.New("start with Windows works on Windows only") }
