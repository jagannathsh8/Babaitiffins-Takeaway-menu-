//go:build windows

package main

// The Babai Console window, for whoever is logged in to the PC. Started at every log-in (and from
// the Start menu / desktop icon), it shows a tray icon and keeps the Console window open: the
// window (Microsoft Edge in app mode, no address bar) tells the Console it is open every few
// seconds; if that stops (closed by mistake, Edge crashed), it is opened again. "Close window"
// in the tray closes it until it is opened again; Settings → "Keep the window open" switches the
// reopening off on this PC. The window never affects printing: that is the service.

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"time"

	"fyne.io/systray"
	"golang.org/x/sys/windows"
)

//go:embed tray.ico
var trayIcon []byte

var (
	agentMu        sync.Mutex
	agentClosed    bool // closed from the tray: don't reopen until opened again
	agentLastOpen  time.Time
	agentWantFirst bool
)

func consoleURL(hash string) string {
	return fmt.Sprintf("http://localhost:%d/console?w=1%s", defaultPort, hash)
}

func runAgent(show bool) {
	name, _ := windows.UTF16PtrFromString(`Local\BabaiConsoleWindow`)
	h, err := windows.CreateMutex(nil, false, name)
	if err == windows.ERROR_ALREADY_EXISTS {
		if show {
			openConsoleWindow("") // the tray is already running: just show the window
		}
		return
	}
	defer windows.CloseHandle(h)
	agentWantFirst = true
	systray.Run(agentReady, func() {})
}

type windowState struct {
	Keep   bool   `json:"keep"`
	Age    int64  `json:"age"` // ms since the window last said it is open (-1: never)
	Status string `json:"status"`
}

func fetchWindowState() (windowState, bool) {
	c := &http.Client{Timeout: 2 * time.Second}
	resp, err := c.Get(fmt.Sprintf("http://127.0.0.1:%d/window/state", defaultPort))
	if err != nil {
		return windowState{}, false
	}
	defer resp.Body.Close()
	var st windowState
	if json.NewDecoder(resp.Body).Decode(&st) != nil {
		return windowState{}, false
	}
	return st, true
}

func agentReady() {
	systray.SetIcon(trayIcon)
	systray.SetTitle(appName)
	systray.SetTooltip(appName)
	status := systray.AddMenuItem("Starting…", "")
	status.Disable()
	systray.AddSeparator()
	open := systray.AddMenuItem("Open Babai Console", "")
	dispatch := systray.AddMenuItem("Dispatch screen", "")
	closeW := systray.AddMenuItem("Close window", "Closes the window until it is opened again (printing continues)")
	systray.AddSeparator()
	ver := systray.AddMenuItem("Babai Console "+version, "")
	ver.Disable()

	go func() {
		for {
			select {
			case <-open.ClickedCh:
				agentMu.Lock()
				agentClosed = false
				agentMu.Unlock()
				openConsoleWindow("")
			case <-dispatch.ClickedCh:
				agentMu.Lock()
				agentClosed = false
				agentMu.Unlock()
				openConsoleWindow("#dispatch")
			case <-closeW.ClickedCh:
				agentMu.Lock()
				agentClosed = true
				agentMu.Unlock()
				closeConsoleWindows()
			}
		}
	}()
	go func() {
		for {
			st, ok := fetchWindowState()
			if !ok {
				status.SetTitle("Babai Console is starting…")
				systray.SetTooltip(appName + " – starting")
			} else {
				status.SetTitle(st.Status)
				systray.SetTooltip(appName + " – " + st.Status)
				agentMu.Lock()
				want := agentWantFirst || (st.Keep && !agentClosed)
				due := time.Since(agentLastOpen) > 25*time.Second
				agentMu.Unlock()
				if want && due && (st.Age < 0 || st.Age > 15000) {
					openConsoleWindow("")
				}
			}
			time.Sleep(3 * time.Second)
		}
	}()
}

func edgePath() string {
	for _, d := range []string{os.Getenv("ProgramFiles(x86)"), os.Getenv("ProgramFiles"), os.Getenv("LOCALAPPDATA")} {
		if d == "" {
			continue
		}
		p := filepath.Join(d, `Microsoft\Edge\Application\msedge.exe`)
		if _, err := os.Stat(p); err == nil {
			return p
		}
	}
	return ""
}

func windowProfile() string {
	return filepath.Join(os.Getenv("LOCALAPPDATA"), "BabaiConsole", "window")
}

func openConsoleWindow(hash string) {
	agentMu.Lock()
	agentLastOpen, agentWantFirst = time.Now(), false
	agentMu.Unlock()
	u := consoleURL(hash)
	if e := edgePath(); e != "" {
		_ = os.MkdirAll(windowProfile(), 0o755)
		cmd := exec.Command(e, "--app="+u, "--user-data-dir="+windowProfile(), "--no-first-run",
			"--no-default-browser-check", "--disable-features=Translate", "--window-size=1366,860")
		if cmd.Start() == nil {
			go cmd.Wait()
			return
		}
	}
	openBrowser(u)
}

func closeConsoleWindows() {
	ps := fmt.Sprintf(`Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" | Where-Object { $_.CommandLine -like '*%s*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }`, psq(windowProfile()))
	_ = hidden("powershell", "-NoProfile", "-NonInteractive", "-Command", ps).Run()
}
