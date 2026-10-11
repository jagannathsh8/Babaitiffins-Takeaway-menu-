//go:build windows

package main

// The Babai Hub icon in the Windows system tray (next to the clock).

import (
	_ "embed"
	"fmt"
	"os"
	"os/exec"
	"time"

	"fyne.io/systray"
)

//go:embed tray.ico
var trayIcon []byte

// runUI shows the tray icon and blocks for the life of the program.
func runUI(port int) {
	systray.Run(func() { trayReady(port) }, func() {})
}

func trayReady(port int) {
	page := fmt.Sprintf("http://localhost:%d/", port)
	systray.SetIcon(trayIcon)
	systray.SetTitle(appName)
	systray.SetTooltip("Babai Hub " + getConfig().Outlet)

	status := systray.AddMenuItem("Starting\u2026", "")
	status.Disable()
	systray.AddSeparator()
	open := systray.AddMenuItem("Open Babai Hub", "Settings and live status")
	outlet := systray.AddMenuItem("Outlet name\u2026", "The outlet name exactly as on the bill")
	ip := systray.AddMenuItem("Petpooja server IP\u2026", "IP address of the main Petpooja billing PC")
	pause := systray.AddMenuItem("Pause", "Stop reading Petpooja and updating customers' phones")
	poster := systray.AddMenuItem("Print QR poster", "")
	dishes := systray.AddMenuItem("Dish carousel\u2026", "Photos customers swipe through while they wait (up to 10)")
	systray.AddSeparator()
	restart := systray.AddMenuItem("Restart Babai Hub", "")

	refresh := func() {
		st := snapshotStatus()
		text := trayStatus(st)
		status.SetTitle(text)
		systray.SetTooltip("Babai Hub \u2013 " + text)
		if getConfig().Paused {
			pause.SetTitle("Resume")
		} else {
			pause.SetTitle("Pause")
		}
	}
	refresh()
	go func() {
		t := time.NewTicker(3 * time.Second)
		for {
			select {
			case <-t.C:
				refresh()
			case <-open.ClickedCh:
				openBrowser(page)
			case <-poster.ClickedCh:
				openBrowser(page + "poster")
			case <-dishes.ClickedCh:
				openBrowser(page + "#dishes")
			case <-pause.ClickedCh:
				setPaused(!getConfig().Paused)
				refresh()
			case <-outlet.ClickedCh:
				go func() {
					v, ok := askText("Outlet name exactly as printed on the bill\n(e.g. VTR, Sarjapur, HSR, JPN)", "Babai Hub \u2013 Outlet", getConfig().Outlet)
					if !ok || outletCode(v) == "" {
						return
					}
					c := getConfig()
					if c.Outlet != v {
						c.Outlet = v
						if err := saveConfig(c); err != nil {
							msgBox("Couldn't save: "+err.Error(), true)
							return
						}
						resetStreams()
					}
					msgBox("Outlet set to "+v+". Print the QR poster again for this outlet.", false)
					refresh()
				}()
			case <-ip.ClickedCh:
				go func() {
					v, ok := askText("IP address of the main Petpooja billing PC\n(the same one typed on the KDS TV, e.g. 192.168.1.5)",
						"Babai Hub \u2013 Petpooja server", getConfig().Server)
					if !ok {
						return
					}
					c := getConfig()
					c.Server = v
					if err := saveConfig(c); err != nil {
						msgBox("Couldn't save: "+err.Error(), true)
						return
					}
					resetPetpooja()
					if err := pollOnce(v); err != nil {
						notePP(err)
						msgBox("Saved "+v+", but Petpooja can't be read yet:\n"+err.Error(), true)
					} else {
						msgBox("Connected to Petpooja at "+v+".", false)
					}
					refresh()
				}()
			case <-restart.ClickedCh:
				_ = exec.Command(exePath(), "--restart").Start() // the new copy waits until this one has gone
				os.Exit(0)
			}
		}
	}()
}

func trayStatus(st map[string]any) string {
	c := getConfig()
	switch {
	case c.Paused:
		return "\u23F8 Paused"
	case c.Server == "":
		return "\u26A0 Enter the Petpooja IP"
	case outletCode(c.Outlet) == "":
		return "\u26A0 Enter the outlet name"
	}
	if ago, _ := st["ppOkAgo"].(int); ago < 0 || ago > 30 {
		return "\u26A0 Can't read Petpooja"
	}
	if !configured() {
		return "\u26A0 Set up Firebase"
	}
	if e, _ := st["cloudErrAgo"].(int); e >= 0 {
		return "\u26A0 Cloud problem"
	}
	return fmt.Sprintf("\u25CF Live \u2013 %v ready, %v preparing", st["ready"], st["preparing"])
}
