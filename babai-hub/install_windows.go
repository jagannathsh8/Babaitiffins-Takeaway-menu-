//go:build windows

package main

// Installing Babai Console (double-click babai-console.exe; Windows asks for admin once):
//
//   - the program goes to C:\Program Files\Babai Console, its data to C:\ProgramData\BabaiConsole;
//   - a Windows service "Babai Console" runs it from PC start-up (before anyone logs in) and
//     Windows restarts it if it ever stops;
//   - the Console window opens at every log-in (and reopens if closed by mistake);
//   - the old Babai Hub's settings and data are copied in, and the old Hub stops starting;
//   - the old Bridge Print is switched off (kept installed, so it can be switched back on), and
//     its printer, settings and next token are taken over;
//   - firewall: the shop network may reach the print port, TV page, token app and scanners.
//
// Settings → Apps → Babai Console → Uninstall removes it (data is kept).

import (
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
	"golang.org/x/sys/windows/svc"
	"golang.org/x/sys/windows/svc/mgr"
)

const (
	serviceName      = "BabaiConsole"
	appName          = "Babai Console"
	runKeyPath       = `Software\Microsoft\Windows\CurrentVersion\Run`
	uninstallKeyPath = `Software\Microsoft\Windows\CurrentVersion\Uninstall\BabaiConsole`
	oldHubRunValue   = "BabaiHub"
)

func programDir() string {
	pf := os.Getenv("ProgramFiles")
	if pf == "" {
		pf = `C:\Program Files`
	}
	return filepath.Join(pf, appName)
}

func programExe() string { return filepath.Join(programDir(), "babai-console.exe") }
func appVersionsDir() string {
	return filepath.Join(appDataDir(), "app")
}

func exePath() string {
	p, err := os.Executable()
	if err != nil {
		return "babai-console.exe"
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

func hiddenCmd(name string, args ...string) *exec.Cmd { return hidden(name, args...) }

func ocrAvailable() bool { return true }

func openBrowser(url string) {
	_ = hidden("rundll32", "url.dll,FileProtocolHandler", url).Start()
}

func isAdmin() bool {
	return windows.GetCurrentProcessToken().IsElevated()
}

// platformMain: what this start of the exe is for.
func platformMain() bool {
	switch {
	case hasArg("--run"):
		return false // the Console itself (started by the service)
	case hasArg("--service"):
		runService()
	case hasArg("--window"):
		runAgent(hasArg("--show"))
	case hasArg("--install"):
		if err := doInstall(); err != nil {
			msgBox("Babai Console couldn't be installed:\n\n"+err.Error(), true)
			os.Exit(1)
		}
	case hasArg("--uninstall"):
		if !isAdmin() {
			_, _ = runElevated(exePath(), "--uninstall", false)
			return true
		}
		doUninstall()
	default:
		// double-clicked: install (or update) and open the window
		if strings.EqualFold(filepath.Clean(exePath()), filepath.Clean(programExe())) && serviceInstalled() {
			startAgent(true)
			return true
		}
		if isAdmin() {
			if err := doInstall(); err != nil {
				msgBox("Babai Console couldn't be installed:\n\n"+err.Error(), true)
				return true
			}
		} else {
			// as the logged-in user: the old Hub stops and won't start at log-in again, and the
			// installer (running as administrator) is told where this user's old Hub data is
			stopOldHub()
			code, err := runElevated(exePath(), `--install --from-hub "`+filepath.Join(os.Getenv("LOCALAPPDATA"), "BabaiHub")+`"`, true)
			if err != nil {
				msgBox("Babai Console needs Windows to say Yes (administrator) once to install.\n\n"+err.Error(), true)
				return true
			}
			if code != 0 {
				return true // the elevated installer showed what went wrong
			}
		}
		startAgent(true)
		msgBox("Babai Console "+version+" is installed and running.\n\nIt starts with Windows, keeps running in the background, and its window opens when you log in.", false)
	}
	return true
}

func startAgent(show bool) {
	args := []string{"--window"}
	if show {
		args = append(args, "--show")
	}
	_ = exec.Command(programExe(), args...).Start()
}

// runElevated starts exe with args through the Windows "Yes" (UAC) prompt; wait: until it ends.
func runElevated(exe, args string, wait bool) (uint32, error) {
	type shellExecuteInfo struct {
		cbSize       uint32
		fMask        uint32
		hwnd         uintptr
		lpVerb       *uint16
		lpFile       *uint16
		lpParameters *uint16
		lpDirectory  *uint16
		nShow        int32
		hInstApp     uintptr
		lpIDList     uintptr
		lpClass      *uint16
		hkeyClass    uintptr
		dwHotKey     uint32
		hIcon        uintptr
		hProcess     windows.Handle
	}
	verb, _ := windows.UTF16PtrFromString("runas")
	file, _ := windows.UTF16PtrFromString(exe)
	params, _ := windows.UTF16PtrFromString(args)
	info := shellExecuteInfo{fMask: 0x40 /* SEE_MASK_NOCLOSEPROCESS */, lpVerb: verb, lpFile: file, lpParameters: params, nShow: 1}
	info.cbSize = uint32(unsafe.Sizeof(info))
	proc := windows.NewLazySystemDLL("shell32.dll").NewProc("ShellExecuteExW")
	if r, _, err := proc.Call(uintptr(unsafe.Pointer(&info))); r == 0 {
		return 1, err
	}
	if info.hProcess == 0 {
		return 0, nil
	}
	defer windows.CloseHandle(info.hProcess)
	if !wait {
		return 0, nil
	}
	_, _ = windows.WaitForSingleObject(info.hProcess, windows.INFINITE)
	var code uint32
	_ = windows.GetExitCodeProcess(info.hProcess, &code)
	return code, nil
}

func installLog(format string, a ...any) {
	_ = os.MkdirAll(appDataDir(), 0o755)
	f, err := os.OpenFile(filepath.Join(appDataDir(), "install.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err == nil {
		fmt.Fprintf(f, "%s %s\n", time.Now().Format(time.RFC3339), fmt.Sprintf(format, a...))
		f.Close()
	}
}

// doInstall (as administrator) installs or updates Babai Console.
func doInstall() error {
	self := exePath()
	installLog("installing %s from %s", version, self)
	if err := stopService(30 * time.Second); err != nil {
		installLog("stop service: %v", err)
	}
	stopOldHub()
	stopOldBridgePrint()
	stopAgents()

	if err := os.MkdirAll(programDir(), 0o755); err != nil {
		return err
	}
	if !strings.EqualFold(filepath.Clean(self), filepath.Clean(programExe())) {
		var err error
		for i := 0; i < 20; i++ {
			if err = copyFile(self, programExe()); err == nil {
				break
			}
			time.Sleep(500 * time.Millisecond)
		}
		if err != nil {
			return fmt.Errorf("couldn't copy the program to %s: %v", programDir(), err)
		}
	}
	base := appDataDir()
	vdir := filepath.Join(appVersionsDir(), version)
	if err := os.MkdirAll(vdir, 0o755); err != nil {
		return err
	}
	if err := copyFile(self, filepath.Join(vdir, "babai-console.exe")); err != nil {
		return err
	}
	_ = os.WriteFile(filepath.Join(appVersionsDir(), "current.txt"), []byte(version), 0o644)
	_ = os.Remove(filepath.Join(appVersionsDir(), "next.txt"))
	unmarkBad(version)
	migrateOldHub()
	// only administrators and Windows itself may read or change the data (it holds the Firebase secret)
	_ = hidden("icacls", base, "/inheritance:r", "/grant:r", "*S-1-5-18:(OI)(CI)F", "*S-1-5-32-544:(OI)(CI)F").Run()

	if err := installService(); err != nil {
		return fmt.Errorf("Windows service: %v", err)
	}
	openFirewall()
	if k, _, err := registry.CreateKey(registry.LOCAL_MACHINE, runKeyPath, registry.SET_VALUE); err == nil {
		_ = k.SetStringValue(serviceName, `"`+programExe()+`" --window`)
		k.Close()
	}
	registerUninstall()
	makeShortcut()
	if err := startService(); err != nil {
		return fmt.Errorf("the service didn't start: %v", err)
	}
	installLog("installed %s", version)
	return nil
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
	if err := os.Rename(tmp, dst); err != nil {
		// the running exe can't be overwritten, but it can be renamed out of the way
		old := dst + ".old"
		_ = os.Remove(old)
		if e := os.Rename(dst, old); e != nil {
			return err
		}
		return os.Rename(tmp, dst)
	}
	return nil
}

func serviceInstalled() bool {
	m, err := mgr.Connect()
	if err != nil {
		return false
	}
	defer m.Disconnect()
	s, err := m.OpenService(serviceName)
	if err != nil {
		return false
	}
	s.Close()
	return true
}

func installService() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	conf := mgr.Config{StartType: mgr.StartAutomatic, DisplayName: appName,
		Description: "Babai Tiffins: Petpooja KOT printing with tokens, dispatch, scanners and the customers' dosa page."}
	s, err := m.OpenService(serviceName)
	if err != nil {
		s, err = m.CreateService(serviceName, programExe(), conf, "--service")
		if err != nil {
			return err
		}
	} else {
		cur, err := s.Config()
		if err == nil {
			cur.BinaryPathName = `"` + programExe() + `" --service`
			cur.StartType, cur.DisplayName, cur.Description = conf.StartType, conf.DisplayName, conf.Description
			_ = s.UpdateConfig(cur)
		}
	}
	defer s.Close()
	// restart by itself: after 2 s, 2 s, then every 5 s
	_ = s.SetRecoveryActions([]mgr.RecoveryAction{
		{Type: mgr.ServiceRestart, Delay: 2 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 2 * time.Second},
		{Type: mgr.ServiceRestart, Delay: 5 * time.Second},
	}, 86400)
	_ = s.SetRecoveryActionsOnNonCrashFailures(true)
	return nil
}

func startService() error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(serviceName)
	if err != nil {
		return err
	}
	defer s.Close()
	if st, err := s.Query(); err == nil && st.State == svc.Running {
		return nil
	}
	return s.Start()
}

func stopService(limit time.Duration) error {
	m, err := mgr.Connect()
	if err != nil {
		return err
	}
	defer m.Disconnect()
	s, err := m.OpenService(serviceName)
	if err != nil {
		return nil // not installed yet
	}
	defer s.Close()
	st, err := s.Control(svc.Stop)
	if err != nil {
		if st2, e := s.Query(); e == nil && st2.State == svc.Stopped {
			return nil
		}
	}
	deadline := time.Now().Add(limit)
	for st.State != svc.Stopped && time.Now().Before(deadline) {
		time.Sleep(300 * time.Millisecond)
		if st, err = s.Query(); err != nil {
			return err
		}
	}
	if st.State != svc.Stopped {
		return errors.New("the service didn't stop in time")
	}
	return nil
}

// stopOldHub: the old Babai Hub (per user) stops and no longer starts at log-in.
func stopOldHub() {
	askOldHubToQuit(defaultPort)
	if k, err := registry.OpenKey(registry.CURRENT_USER, runKeyPath, registry.SET_VALUE); err == nil {
		_ = k.DeleteValue(oldHubRunValue)
		k.Close()
	}
	_ = hidden("taskkill", "/F", "/IM", "babai-hub.exe").Run()
}

// stopOldBridgePrint switches the old Bridge Print off (kept installed: it can be switched back on).
func stopOldBridgePrint() {
	_ = hidden("schtasks", "/End", "/TN", "Bridge print").Run()
	_ = hidden("schtasks", "/Change", "/TN", "Bridge print", "/DISABLE").Run()
	dir := filepath.Join(os.Getenv("ProgramData"), "BridgePrint")
	ps := fmt.Sprintf(`Get-CimInstance Win32_Process -Filter "Name='node.exe' or Name='powershell.exe'" | Where-Object { ($_.ExecutablePath -and $_.ExecutablePath.StartsWith('%s',[StringComparison]::OrdinalIgnoreCase)) -or ($_.CommandLine -and $_.CommandLine -like '*Watchdog-BridgePrint*') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }`, psq(dir))
	_ = hidden("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).Run()
	time.Sleep(time.Second)
}

// stopAgents closes the Console windows' tray programs (they run from Program Files; the copy
// that was double-clicked to install is left alone).
func stopAgents() {
	ps := fmt.Sprintf(`Get-CimInstance Win32_Process -Filter "Name='babai-console.exe'" | Where-Object { $_.ProcessId -ne %d -and $_.ExecutablePath -and $_.ExecutablePath.StartsWith('%s',[StringComparison]::OrdinalIgnoreCase) } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }`, os.Getpid(), psq(programDir()))
	_ = hidden("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).Run()
}

// migrateOldHub copies the old Babai Hub's data (settings, Firebase, phones, Prep Live, history).
func migrateOldHub() {
	base := appDataDir()
	if _, err := os.Stat(filepath.Join(base, "config.json")); err == nil {
		return // already has its own settings
	}
	old := filepath.Join(os.Getenv("LOCALAPPDATA"), "BabaiHub")
	for i, a := range os.Args {
		if a == "--from-hub" && i+1 < len(os.Args) {
			old = os.Args[i+1]
		}
	}
	if _, err := os.Stat(filepath.Join(old, "config.json")); err != nil {
		return
	}
	installLog("copying the old Babai Hub data from %s", old)
	_ = filepath.Walk(old, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return nil
		}
		rel, _ := filepath.Rel(old, p)
		top := strings.Split(rel, string(filepath.Separator))[0]
		if top == "app" || top == "update" || strings.HasSuffix(rel, ".log") || strings.HasSuffix(rel, ".old") {
			if info.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		dst := filepath.Join(base, rel)
		if info.IsDir() {
			return os.MkdirAll(dst, 0o755)
		}
		_ = copyFile(p, dst)
		return nil
	})
}

func openFirewall() {
	_ = hidden("netsh", "advfirewall", "firewall", "delete", "rule", "name=Babai Console").Run()
	add := func(proto, ports string) {
		_ = hidden("netsh", "advfirewall", "firewall", "add", "rule", "name=Babai Console", "dir=in", "action=allow",
			"protocol="+proto, "localport="+ports, "remoteip=LocalSubnet", "profile=any").Run()
	}
	add("TCP", "9100,8787,8788,8790")
	add("UDP", "8789")
}

func registerUninstall() {
	k, _, err := registry.CreateKey(registry.LOCAL_MACHINE, uninstallKeyPath, registry.SET_VALUE)
	if err != nil {
		return
	}
	defer k.Close()
	_ = k.SetStringValue("DisplayName", appName)
	_ = k.SetStringValue("DisplayVersion", version)
	_ = k.SetStringValue("Publisher", "Babai Tiffins")
	_ = k.SetStringValue("DisplayIcon", programExe())
	_ = k.SetStringValue("InstallLocation", programDir())
	_ = k.SetStringValue("UninstallString", `"`+programExe()+`" --uninstall`)
	_ = k.SetDWordValue("NoModify", 1)
	_ = k.SetDWordValue("NoRepair", 1)
}

func shortcutPath() string {
	return filepath.Join(os.Getenv("ProgramData"), `Microsoft\Windows\Start Menu\Programs`, appName+".lnk")
}

func makeShortcut() {
	for _, lnk := range []string{shortcutPath(), filepath.Join(os.Getenv("PUBLIC"), "Desktop", appName+".lnk")} {
		ps := fmt.Sprintf(`$s=(New-Object -ComObject WScript.Shell).CreateShortcut('%s');$s.TargetPath='%s';$s.Arguments='--window --show';$s.WorkingDirectory='%s';$s.IconLocation='%s,0';$s.Save()`,
			psq(lnk), psq(programExe()), psq(programDir()), psq(programExe()))
		_ = hidden("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).Run()
	}
}

func psq(s string) string { return strings.ReplaceAll(s, "'", "''") }

// doUninstall (Settings → Apps → Uninstall). The data folder stays.
func doUninstall() {
	if !confirmBox("Remove Babai Console from this PC?\n\nKOT printing with tokens, the Dispatch screen and the customers' dosa page stop until it is installed again.") {
		return
	}
	_ = stopService(30 * time.Second)
	if m, err := mgr.Connect(); err == nil {
		if s, err := m.OpenService(serviceName); err == nil {
			_ = s.Delete()
			s.Close()
		}
		m.Disconnect()
	}
	if k, err := registry.OpenKey(registry.LOCAL_MACHINE, runKeyPath, registry.SET_VALUE); err == nil {
		_ = k.DeleteValue(serviceName)
		k.Close()
	}
	_ = registry.DeleteKey(registry.LOCAL_MACHINE, uninstallKeyPath)
	_ = hidden("netsh", "advfirewall", "firewall", "delete", "rule", "name=Babai Console").Run()
	_ = os.Remove(shortcutPath())
	_ = os.Remove(filepath.Join(os.Getenv("PUBLIC"), "Desktop", appName+".lnk"))
	stopAgents()
	if err := hidden("schtasks", "/Query", "/TN", "Bridge print").Run(); err == nil &&
		confirmBox("Switch the old Bridge Print back on?") {
		_ = hidden("schtasks", "/Change", "/TN", "Bridge print", "/ENABLE").Run()
		_ = hidden("schtasks", "/Run", "/TN", "Bridge print").Run()
	}
	msgBox("Babai Console was removed.\nIts data is still in "+appDataDir()+".", false)
	_ = hidden("cmd", "/c", "ping -n 3 127.0.0.1 >nul & rmdir /s /q \""+programDir()+"\"").Start()
}

// The Hub page used to switch "start with Windows"; the service now always starts with Windows.
func autostartEnabled() bool { return serviceInstalled() }

func setAutostart(on bool) error {
	if !on {
		return errors.New("Babai Console always starts with Windows (it is a Windows service)")
	}
	return nil
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

func init() { log.SetFlags(log.LstdFlags) }
