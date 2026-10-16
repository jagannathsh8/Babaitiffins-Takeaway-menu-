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

func exePath() string {
	p, _ := os.Executable()
	return p
}

func openBrowser(url string) { _ = exec.Command("xdg-open", url).Start() }

func autostartEnabled() bool { return false }

func setAutostart(on bool) error { return errors.New("start with Windows works on Windows only") }

// platformMain: outside Windows the program is just the Console.
func platformMain() bool { return false }

func hiddenCmd(name string, args ...string) *exec.Cmd { return exec.Command(name, args...) }

func ocrAvailable() bool { return false }
