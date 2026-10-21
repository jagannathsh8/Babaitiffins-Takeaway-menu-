//go:build windows

package main

// Finding the Console window on the screen, so the tray never opens a second one: the window's
// title is the page title, "Babai Console" (app window), or "Babai Console - Google Chrome" /
// "... - Microsoft Edge" when it is a tab of a normal browser window.

import (
	"strings"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	user32w                 = windows.NewLazySystemDLL("user32.dll")
	procGetWindowTextW      = user32w.NewProc("GetWindowTextW")
	procShowWindow          = user32w.NewProc("ShowWindow")
	procSetForegroundWindow = user32w.NewProc("SetForegroundWindow")
	procIsIconic            = user32w.NewProc("IsIconic")
	procPostMessageW        = user32w.NewProc("PostMessageW")
)

const (
	swRestore = 9
	wmClose   = 0x0010
)

type consoleWin struct {
	h   windows.HWND
	app bool // its own app window (title exactly "Babai Console"), not a tab of a browser window
}

func windowText(h windows.HWND) string {
	buf := make([]uint16, 256)
	n, _, _ := procGetWindowTextW.Call(uintptr(h), uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
	return windows.UTF16ToString(buf[:n])
}

// findConsoleWindows lists the Babai Console windows (Edge or Chrome) that are open, app windows first.
func findConsoleWindows() []consoleWin {
	var apps, tabs []consoleWin
	cb := syscall.NewCallback(func(h windows.HWND, _ uintptr) uintptr {
		if !windows.IsWindowVisible(h) {
			return 1
		}
		cls := make([]uint16, 64)
		if n, _ := windows.GetClassName(h, &cls[0], int32(len(cls))); n == 0 || windows.UTF16ToString(cls[:n]) != "Chrome_WidgetWin_1" {
			return 1
		}
		t := windowText(h)
		switch {
		case t == appName:
			apps = append(apps, consoleWin{h, true})
		case strings.HasPrefix(t, "Babai Console - ") || strings.HasPrefix(t, "Babai Console and "):
			tabs = append(tabs, consoleWin{h, false})
		}
		return 1
	})
	_ = windows.EnumWindows(cb, nil)
	return append(apps, tabs...)
}

// focusWindow brings a window to the front (restored if it was minimized).
func focusWindow(h windows.HWND) {
	if r, _, _ := procIsIconic.Call(uintptr(h)); r != 0 {
		procShowWindow.Call(uintptr(h), swRestore)
	}
	procSetForegroundWindow.Call(uintptr(h))
}

// closeExtraConsoleWindows keeps one Console window: extra app windows (opened twice by mistake)
// are closed. Browser windows are never closed (they may hold the user's other tabs).
func closeExtraConsoleWindows(ws []consoleWin) {
	kept := false
	for _, w := range ws {
		if !w.app {
			continue
		}
		if !kept {
			kept = true
			continue
		}
		procPostMessageW.Call(uintptr(w.h), wmClose, 0, 0)
	}
}
