//go:build windows

package main

// Babai Hub installs itself: the first double-click copies the program to
// %LOCALAPPDATA%\BabaiHub\app, makes it start with Windows (every sign-in, until uninstalled),
// adds it to Settings → Apps (with Uninstall) and to the Start menu, and starts the installed copy.
// No admin rights needed.

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows/registry"
)

const (
	runKeyPath       = `Software\Microsoft\Windows\CurrentVersion\Run`
	uninstallKeyPath = `Software\Microsoft\Windows\CurrentVersion\Uninstall\BabaiHub`
	appName          = "Babai Hub"
)

func installDir() string   { return filepath.Join(appDataDir(), "app") }
func installedExe() string { return filepath.Join(installDir(), "babai-hub.exe") }

func exePath() string {
	p, err := os.Executable()
	if err != nil {
		return "babai-hub.exe"
	}
	if r, err := filepath.EvalSymlinks(p); err == nil {
		p = r
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

// ensureInstalled returns true when this copy handed over to the installed one and should exit.
func ensureInstalled(port int) bool {
	self, target := exePath(), installedExe()
	if strings.EqualFold(filepath.Clean(self), filepath.Clean(target)) {
		_ = registerAutostart(target) // every start: keep "start with Windows" in place
		return false
	}
	// A newer exe was double-clicked: stop the running hub, replace it, start the new one.
	askRunningToQuit(port)
	if err := os.MkdirAll(installDir(), 0o755); err != nil {
		msgBox("Babai Hub couldn't be installed:\n"+err.Error(), true)
		return false
	}
	var err error
	for i := 0; i < 10; i++ {
		if err = copyFile(self, target); err == nil {
			break
		}
		time.Sleep(500 * time.Millisecond)
	}
	if err != nil {
		msgBox("Babai Hub couldn't be installed (is it still running?):\n"+err.Error(), true)
		return false
	}
	_ = registerAutostart(target)
	_ = registerUninstall(target)
	makeStartMenuShortcut(target)
	if err := exec.Command(target).Start(); err != nil {
		msgBox("Babai Hub was installed but couldn't start:\n"+err.Error(), true)
		return false
	}
	return true
}

func copyFile(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp := dst + ".new"
	out, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	_ = os.Remove(dst)
	return os.Rename(tmp, dst)
}

func askRunningToQuit(port int) {
	req, _ := http.NewRequest(http.MethodPost, fmt.Sprintf("http://127.0.0.1:%d/quit", port), strings.NewReader("{}"))
	req.Header.Set("X-Babai-Hub", "1")
	c := &http.Client{Timeout: 2 * time.Second}
	if resp, err := c.Do(req); err == nil {
		resp.Body.Close()
		time.Sleep(1500 * time.Millisecond)
	}
}

func registerAutostart(exe string) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	return k.SetStringValue("BabaiHub", `"`+exe+`"`)
}

func registerUninstall(exe string) error {
	k, _, err := registry.CreateKey(registry.CURRENT_USER, uninstallKeyPath, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	_ = k.SetStringValue("DisplayName", appName)
	_ = k.SetStringValue("DisplayVersion", version)
	_ = k.SetStringValue("Publisher", "Babai Tiffins")
	_ = k.SetStringValue("DisplayIcon", exe)
	_ = k.SetStringValue("InstallLocation", installDir())
	_ = k.SetStringValue("UninstallString", `"`+exe+`" --uninstall`)
	_ = k.SetDWordValue("NoModify", 1)
	return k.SetDWordValue("NoRepair", 1)
}

func startMenuShortcut() string {
	return filepath.Join(os.Getenv("APPDATA"), `Microsoft\Windows\Start Menu\Programs`, appName+".lnk")
}

func makeStartMenuShortcut(exe string) {
	ps := fmt.Sprintf(`$s=(New-Object -ComObject WScript.Shell).CreateShortcut('%s');$s.TargetPath='%s';$s.WorkingDirectory='%s';$s.Save()`,
		psq(startMenuShortcut()), psq(exe), psq(filepath.Dir(exe)))
	_ = hidden("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).Run()
}

func psq(s string) string { return strings.ReplaceAll(s, "'", "''") }

// uninstall is run by Settings → Apps → Babai Hub → Uninstall. Today's data folder stays.
func uninstall(port int) {
	if !confirmBox("Remove Babai Hub from this PC?\n\nThe customers' dosa page stops updating until it is installed again.") {
		return
	}
	askRunningToQuit(port)
	if k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE); err == nil {
		_ = k.DeleteValue("BabaiHub")
		k.Close()
	}
	_ = registry.DeleteKey(registry.CURRENT_USER, uninstallKeyPath)
	_ = os.Remove(startMenuShortcut())
	msgBox("Babai Hub was removed.\nIts saved data is still in "+appDataDir()+".", false)
	// The program can't delete itself while running: a hidden command removes it a moment later.
	_ = hidden("cmd", "/c", "ping -n 3 127.0.0.1 >nul & rmdir /s /q \""+installDir()+"\"").Start()
}

// Kept for the hub page: start with Windows is always on for the installed copy.
func autostartEnabled() bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	v, _, err := k.GetStringValue("BabaiHub")
	return err == nil && v != ""
}

func setAutostart(on bool) error {
	if on {
		return registerAutostart(exePath())
	}
	k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE)
	if err != nil {
		return err
	}
	defer k.Close()
	return k.DeleteValue("BabaiHub")
}

var (
	user32         = syscall.NewLazyDLL("user32.dll")
	procMessageBox = user32.NewProc("MessageBoxW")
)

func msgBox(text string, warn bool) {
	flags := uintptr(0x40) // MB_ICONINFORMATION
	if warn {
		flags = 0x30 // MB_ICONWARNING
	}
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString(appName)
	procMessageBox.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), flags|0x10000) // MB_SETFOREGROUND
}

func confirmBox(text string) bool {
	t, _ := syscall.UTF16PtrFromString(text)
	c, _ := syscall.UTF16PtrFromString(appName)
	r, _, _ := procMessageBox.Call(0, uintptr(unsafe.Pointer(t)), uintptr(unsafe.Pointer(c)), 0x4|0x20|0x10000) // MB_YESNO|MB_ICONQUESTION
	return r == 6                                                                                               // IDYES
}

// askText shows a small Windows input box (used by the tray for the Petpooja IP).
func askText(prompt, title, current string) (string, bool) {
	ps := fmt.Sprintf(`Add-Type -AssemblyName Microsoft.VisualBasic;[Console]::OutputEncoding=[Text.Encoding]::UTF8;`+
		`$r=[Microsoft.VisualBasic.Interaction]::InputBox('%s','%s','%s');Write-Output ('OK:'+$r)`, psq(prompt), psq(title), psq(current))
	out, err := hidden("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).Output()
	if err != nil {
		return "", false
	}
	s := strings.TrimSpace(string(out))
	if !strings.HasPrefix(s, "OK:") {
		return "", false
	}
	s = strings.TrimSpace(strings.TrimPrefix(s, "OK:"))
	return s, s != "" // Cancel gives an empty answer
}
