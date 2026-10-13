package main

// PIN for the hub page: nothing on it opens, and nothing can be changed, without the PIN
// (default 1234, can be changed on the page). A correct PIN unlocks this browser for 12 hours.
// Scanner phones and the KDS rider screen don't use it (they have their own pairing / read-only feed).

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	defaultPin  = "1234"
	pinCookie   = "babai_hub_s"
	sessionLife = 12 * time.Hour
)

var (
	pinMu      sync.Mutex
	sessions   = map[string]time.Time{} // token -> expires
	pinWrong   int
	pinBlocked time.Time
)

func currentPin() string {
	if p := strings.TrimSpace(getConfig().Pin); p != "" {
		return p
	}
	return defaultPin
}

func newToken() string {
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func unlocked(r *http.Request) bool {
	c, err := r.Cookie(pinCookie)
	if err != nil || c.Value == "" {
		return false
	}
	pinMu.Lock()
	defer pinMu.Unlock()
	exp, ok := sessions[c.Value]
	if !ok || time.Now().After(exp) {
		delete(sessions, c.Value)
		return false
	}
	return true
}

// pinned: the handler only runs for an unlocked browser.
func pinned(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !unlocked(r) {
			// a page opened in the browser (also from the tray): the PIN screen, then this page again
			if r.Method == http.MethodGet && (r.URL.Path == "/" || strings.Contains(r.Header.Get("Accept"), "text/html")) {
				w.Header().Set("Content-Type", "text/html; charset=utf-8")
				w.Header().Set("Cache-Control", "no-store")
				io.WriteString(w, pinHTML)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			io.WriteString(w, `{"ok":false,"locked":true,"msg":"Enter the PIN first (reload the page)."}`)
			return
		}
		h(w, r)
	}
}

// handleUnlock: POST {pin}.
func handleUnlock(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Pin string }
	_ = json.NewDecoder(io.LimitReader(r.Body, 512)).Decode(&in)
	pinMu.Lock()
	if time.Now().Before(pinBlocked) {
		pinMu.Unlock()
		reply(w, false, "Too many wrong PINs. Wait a minute and try again.")
		return
	}
	pinMu.Unlock()
	if subtle.ConstantTimeCompare([]byte(strings.TrimSpace(in.Pin)), []byte(currentPin())) != 1 {
		pinMu.Lock()
		pinWrong++
		if pinWrong >= 5 {
			pinWrong, pinBlocked = 0, time.Now().Add(time.Minute)
		}
		pinMu.Unlock()
		reply(w, false, "Wrong PIN.")
		return
	}
	tok := newToken()
	pinMu.Lock()
	pinWrong = 0
	now := time.Now()
	for k, exp := range sessions {
		if now.After(exp) {
			delete(sessions, k)
		}
	}
	sessions[tok] = now.Add(sessionLife)
	pinMu.Unlock()
	http.SetCookie(w, &http.Cookie{Name: pinCookie, Value: tok, Path: "/", HttpOnly: true,
		SameSite: http.SameSiteStrictMode, MaxAge: int(sessionLife.Seconds())})
	reply(w, true, "Unlocked.")
}

// handleLock: POST, locks this browser again.
func handleLock(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	if c, err := r.Cookie(pinCookie); err == nil {
		pinMu.Lock()
		delete(sessions, c.Value)
		pinMu.Unlock()
	}
	http.SetCookie(w, &http.Cookie{Name: pinCookie, Value: "", Path: "/", MaxAge: -1})
	reply(w, true, "Locked.")
}

// handlePinChange: POST {old, new} (4-8 digits).
func handlePinChange(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Old, New string }
	_ = json.NewDecoder(io.LimitReader(r.Body, 512)).Decode(&in)
	if subtle.ConstantTimeCompare([]byte(strings.TrimSpace(in.Old)), []byte(currentPin())) != 1 {
		reply(w, false, "The current PIN is not right.")
		return
	}
	n := strings.TrimSpace(in.New)
	if len(n) < 4 || len(n) > 8 || digitsOnly(n) != n {
		reply(w, false, "The new PIN must be 4 to 8 digits.")
		return
	}
	c := getConfig()
	c.Pin = n
	if err := saveConfig(c); err != nil {
		reply(w, false, "Couldn't save: "+err.Error())
		return
	}
	reply(w, true, "PIN changed.")
}

const pinHTML = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Babai Hub</title><style>
:root{--bg:#f6f2ea;--card:#fff;--ink:#2a2118;--mute:#7b6f60;--acc:#e08a00;--bad:#c62828}
@media (prefers-color-scheme:dark){:root{--bg:#17130d;--card:#221c14;--ink:#f3eadb;--mute:#b3a68f}}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.4 system-ui,Segoe UI,sans-serif;display:grid;place-items:center;min-height:100vh}
form{background:var(--card);padding:28px 28px 22px;border-radius:16px;box-shadow:0 6px 30px #0002;width:min(320px,90vw);text-align:center}
h1{margin:0 0 4px;font-size:1.5rem}h1 span{color:var(--acc)}p{color:var(--mute);margin:0 0 18px}
input{font:inherit;font-size:2rem;letter-spacing:.5em;text-align:center;width:100%;box-sizing:border-box;padding:10px;border-radius:10px;border:1px solid #ccc;background:transparent;color:var(--ink)}
button{margin-top:14px;width:100%;font:inherit;font-weight:700;padding:12px;border:0;border-radius:10px;background:var(--acc);color:#1b1203;cursor:pointer}
#m{min-height:1.4em;margin-top:10px;color:var(--bad)}
</style></head><body><form id="f"><h1><span>Babai</span> Hub</h1><p>Enter the PIN</p>
<input id="pin" type="password" inputmode="numeric" autocomplete="off" maxlength="8" autofocus aria-label="PIN">
<button>Open</button><div id="m" role="status"></div></form>
<script>document.getElementById("f").addEventListener("submit",function(e){e.preventDefault();
fetch("/unlock",{method:"POST",headers:{"Content-Type":"application/json","X-Babai-Hub":"1"},body:JSON.stringify({Pin:document.getElementById("pin").value})})
.then(function(r){return r.json()}).then(function(r){if(r.ok)location.reload();else{document.getElementById("m").textContent=r.msg;document.getElementById("pin").value=""}})});</script>
</body></html>`
