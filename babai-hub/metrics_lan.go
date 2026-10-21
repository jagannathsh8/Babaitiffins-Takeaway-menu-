package main

// Metrics on the shop Wi-Fi: http://<outlet PC>:8790/metrics shows the CX and Ops metrics on any
// computer or phone on the same network, view only, after the Console PIN (12 hours per browser).
// Targets can't be changed from there, and nothing else of the Console is reachable from the Wi-Fi.

import (
	"io"
	"net"
	"net/http"
	"strings"
)

func metricsLANRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/metrics", handleMetricsLAN)
	mux.HandleFunc("/metrics/", handleMetricsLAN)
	mux.HandleFunc("/metrics/unlock", handleUnlock) // POST only, with the page's header; 5 wrong PINs = 1 min wait
	mux.HandleFunc("/metrics/lock", handleLock)
	mux.HandleFunc("/metrics/style.css", serveConsoleFile("style.css"))
	mux.HandleFunc("/metrics/metrics.js", serveConsoleFile("metrics.js"))
	mux.HandleFunc("/metrics/api/ops", getOnly(pinnedAPI(handleOps)))
	mux.HandleFunc("/metrics/api/opsload", getOnly(pinnedAPI(handleOpsLoad)))
	mux.HandleFunc("/metrics/api/cx", getOnly(pinnedAPI(handleCX)))
}

func getOnly(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "view only", http.StatusMethodNotAllowed)
			return
		}
		h(w, r)
	}
}

func handleMetricsLAN(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/metrics" && r.URL.Path != "/metrics/" {
		http.NotFound(w, r)
		return
	}
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		http.Redirect(w, r, "/console", http.StatusFound) // on the outlet PC: the Console itself
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if !unlocked(r) {
		io.WriteString(w, strings.Replace(strings.Replace(pinHTML, `fetch("/unlock"`, `fetch("/metrics/unlock"`, 1), "<title>Babai Hub</title>", "<title>Babai Metrics</title>", 1))
		return
	}
	b, err := consoleFiles.ReadFile("console/lan.html")
	if err != nil {
		http.NotFound(w, r)
		return
	}
	w.Write(b)
}
