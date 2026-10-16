package main

// Shared by the Windows service (supervisor) and the Console it runs.

import (
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"
)

// healthMark: after a minute of running fine, the Console tells the supervisor (a new version
// that gets here is kept; one that doesn't is rolled back).
func healthMark() {
	f := os.Getenv("BABAI_HEALTH_FILE")
	if f == "" {
		return
	}
	time.Sleep(60 * time.Second)
	prMu.Lock()
	wantRx := prOpts.Enabled
	prMu.Unlock()
	for i := 0; i < 30; i++ {
		if !wantRx || localPrinting() {
			_ = os.WriteFile(f, []byte(version), 0o644)
			return
		}
		time.Sleep(2 * time.Second) // receiver not open yet
	}
}

// askOldHubToQuit: the old Babai Hub (per-user program) still holds the port: ask it to close.
func askOldHubToQuit(port int) {
	req, _ := http.NewRequest(http.MethodPost, fmt.Sprintf("http://127.0.0.1:%d/quit", port), strings.NewReader("{}"))
	req.Header.Set("X-Babai-Hub", "1")
	c := &http.Client{Timeout: 2 * time.Second}
	if resp, err := c.Do(req); err == nil {
		resp.Body.Close()
	}
}

// supervised: started by the Windows service (updates are handed to it).
func supervised() bool { return os.Getenv("BABAI_HEALTH_FILE") != "" }
