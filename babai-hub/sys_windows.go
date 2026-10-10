//go:build windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
)

const runKey = `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`

func appDataDir() string {
	if d := os.Getenv("LOCALAPPDATA"); d != "" {
		return filepath.Join(d, "BabaiHub")
	}
	return filepath.Join(filepath.Dir(exePath()), "BabaiHubData")
}

func exePath() string {
	p, err := os.Executable()
	if err != nil {
		return "babai-hub.exe"
	}
	return p
}

func hidden(name string, args ...string) *exec.Cmd {
	c := exec.Command(name, args...)
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
	return c
}

func openBrowser(url string) {
	_ = hidden("rundll32", "url.dll,FileProtocolHandler", url).Start()
}

// Start with Windows: a value under the user's Run key (no admin rights needed).
func autostartEnabled() bool {
	out, err := hidden("reg", "query", runKey, "/v", "BabaiHub").Output()
	return err == nil && strings.Contains(strings.ToLower(string(out)), strings.ToLower(filepath.Base(exePath())))
}

func setAutostart(on bool) error {
	if on {
		return hidden("reg", "add", runKey, "/v", "BabaiHub", "/t", "REG_SZ", "/d", `"`+exePath()+`"`, "/f").Run()
	}
	return hidden("reg", "delete", runKey, "/v", "BabaiHub", "/f").Run()
}
