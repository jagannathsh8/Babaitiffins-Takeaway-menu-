package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"net/http"
	"strings"
	"time"
)

// Requests that change something must carry this header. A web page from elsewhere can't add it
// to a request to localhost without the browser asking first (and the hub never says yes).
func fromOwnPage(r *http.Request) bool {
	return r.Method == http.MethodPost && r.Header.Get("X-Babai-Hub") == "1"
}

func handleHome(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != "/" {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	io.WriteString(w, strings.Replace(homeHTML, "__RULES__", rulesJSON, 1))
}

func handleStatus(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	_ = json.NewEncoder(w).Encode(snapshotStatus())
}

func handleSettings(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ DB, Secret, Page string }
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&in); err != nil {
		reply(w, false, "Couldn't read the form.")
		return
	}
	c := getConfig()
	db, err := normaliseDB(in.DB)
	if err != nil {
		reply(w, false, err.Error())
		return
	}
	c.DB = db
	if s := strings.TrimSpace(in.Secret); s != "" {
		c.Secret = s
	}
	if db == "" {
		c.Secret = ""
	}
	page := strings.TrimSpace(in.Page)
	if page == "" {
		page = defaultPage
	}
	if !strings.HasPrefix(page, "https://") {
		reply(w, false, "The customer page address must start with https://")
		return
	}
	c.Page = page
	if err := saveConfig(c); err != nil {
		reply(w, false, "Couldn't save the settings: "+err.Error())
		return
	}
	rulesOK, rulesTryAt = false, time.Time{} // new database: check its rules and send the dishes
	markPromoDirty()
	if c.DB == "" || c.Secret == "" {
		reply(w, true, "Saved. Cloud sending is off until the database address and secret are filled in.")
		return
	}
	if err := testCloud(); err != nil {
		reply(w, false, "Saved, but the test failed: "+err.Error())
		return
	}
	reply(w, true, "Saved and connected to Firebase.")
}

func handlePetpooja(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Server, Outlet, Location string }
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&in); err != nil {
		reply(w, false, "Couldn't read the form.")
		return
	}
	c := getConfig()
	c.Server = strings.TrimSpace(in.Server)
	if name := strings.TrimSpace(in.Outlet); name != c.Outlet {
		if outletCode(name) == "" {
			reply(w, false, "Type the outlet name as printed on the bill, e.g. VTR or Sarjapur.")
			return
		}
		c.Outlet = name
		resetStreams()
	}
	lat, lng, err := resolveLocation(in.Location)
	if err != nil {
		reply(w, false, "Outlet location: "+err.Error())
		return
	}
	if strings.TrimSpace(in.Location) == "" {
		lat, lng = 0, 0
	}
	c.Lat, c.Lng = lat, lng
	if err := saveConfig(c); err != nil {
		reply(w, false, "Couldn't save: "+err.Error())
		return
	}
	resetPetpooja()
	if c.Server == "" {
		reply(w, true, "Saved. Reading Petpooja is off.")
		return
	}
	if err := pollOnce(c.Server); err != nil {
		notePP(err)
		reply(w, false, err.Error())
		return
	}
	ppMu.Lock()
	n, rows := len(ppKots), ppRows
	ppMu.Unlock()
	reply(w, true, fmt.Sprintf("Connected to Petpooja: %d KOTs in the kitchen right now (%d item lines).", n, rows))
}

func handlePause(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ Paused bool }
	_ = json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&in)
	setPaused(in.Paused)
	if in.Paused {
		reply(w, true, "Paused: Petpooja is not read and customers' phones show 'updates paused'.")
	} else {
		reply(w, true, "Running again.")
	}
}

func handleTest(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	if !configured() {
		reply(w, false, "Fill in the database address and secret first.")
		return
	}
	if err := testCloud(); err != nil {
		reply(w, false, err.Error())
		return
	}
	reply(w, true, "Connected to Firebase.")
}

func handleAutostart(w http.ResponseWriter, r *http.Request) {
	if !fromOwnPage(r) {
		http.Error(w, "forbidden", http.StatusForbidden)
		return
	}
	var in struct{ On bool }
	_ = json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&in)
	if err := setAutostart(in.On); err != nil {
		reply(w, false, err.Error())
		return
	}
	if in.On {
		reply(w, true, "Babai Hub will start by itself when this PC starts.")
	} else {
		reply(w, true, "Babai Hub will no longer start with Windows.")
	}
}

func reply(w http.ResponseWriter, ok bool, msg string) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": ok, "msg": msg})
}

// The customers' page (a copy of ../dosa/index.html, synced by build.sh).
//
//go:embed customer/index.html
var customerPage string

// handleCustomerPage gives index.html with this hub's Firebase address built in, so customers
// who open https://babaidosa.netlify.app without scanning can still type their token.
func handleCustomerPage(w http.ResponseWriter, r *http.Request) {
	c := getConfig()
	if c.DB == "" {
		http.Error(w, "Set up Firebase (Step 2) first.", http.StatusConflict)
		return
	}
	host := strings.TrimPrefix(c.DB, "https://")
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Disposition", `attachment; filename="index.html"`)
	io.WriteString(w, strings.Replace(customerPage, "__BABAI_DB__", host, 1))
}

func handlePoster(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	u := qrURL()
	if u == "" {
		io.WriteString(w, `<!doctype html><meta charset="utf-8"><p style="font:18px system-ui;padding:24px">Set up the Firebase database on the hub page first, then open the poster again.</p>`)
		return
	}
	b, _ := json.Marshal(u)
	out := ""
	if name := strings.TrimSpace(getConfig().Outlet); name != "" {
		out = ", " + html.EscapeString(name)
	}
	io.WriteString(w, strings.NewReplacer("__URL_JSON__", string(b), "__URL_TEXT__", html.EscapeString(u), "__OUTLET__", out).Replace(posterHTML))
}

const homeHTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Babai Hub</title>
<style>
:root{--bg:#f7f3ee;--card:#fff;--ink:#271c14;--muted:#76675a;--line:#e7ddd1;--accent:#e08a00;--ok:#1f8a4c;--bad:#b3261e;--warnbg:#fff3e6;
--f:"Segoe UI",system-ui,-apple-system,sans-serif;--mono:Consolas,"Cascadia Mono",ui-monospace,monospace}
@media (prefers-color-scheme:dark){:root{--bg:#16110d;--card:#211913;--ink:#f4ebe2;--muted:#b5a393;--line:#3a2d23;--accent:#f5b21b;--ok:#48c97f;--bad:#ff8a80;--warnbg:#33230f;color-scheme:dark}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 var(--f);padding-inline:16px;padding-block:20px 40px}
.wrap{max-width:1000px;margin:0 auto;display:grid;gap:16px}
header{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}
h1{margin:0;font-size:1.6rem}h1 span{color:var(--accent)}
h2{margin:0 0 8px;font-size:1.05rem}
.pill{display:inline-flex;align-items:center;gap:8px;border-radius:999px;padding:6px 14px;font-weight:600;background:var(--card);border:1px solid var(--line)}
.dot{width:10px;height:10px;border-radius:50%;background:var(--muted)}
.ok .dot{background:var(--ok)}.bad .dot{background:var(--bad)}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(290px,1fr));gap:16px}
section{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px 18px;min-width:0}
.step{font-size:.75rem;font-weight:700;letter-spacing:.12em;text-transform:uppercase;color:var(--accent)}
label{display:block;font-weight:600;margin:10px 0 4px}
input[type=text],input[type=password]{width:100%;font:inherit;padding:8px 10px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--ink)}
button{font:inherit;font-weight:600;border:0;border-radius:8px;padding:9px 16px;background:var(--accent);color:#1b1203;cursor:pointer}
button.ghost{background:transparent;color:var(--ink);border:1px solid var(--line)}
button:focus-visible,input:focus-visible,a:focus-visible{outline:3px solid var(--accent);outline-offset:2px}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-top:12px}
.msg{margin-top:10px;font-weight:600}.msg.ok{color:var(--ok)}.msg.bad{color:var(--bad)}
.hint{color:var(--muted);font-size:.9rem;margin:6px 0 0}
code,pre{font-family:var(--mono);font-size:.82rem;word-break:break-all}
pre{background:var(--bg);border:1px solid var(--line);border-radius:8px;padding:10px;overflow:auto;max-height:260px;margin:6px 0 0;white-space:pre-wrap}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:10px}
.stat{background:var(--bg);border-radius:10px;padding:10px 12px}
.dishes{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:12px;margin-top:10px}
.dish{background:var(--bg);border:1px solid var(--line);border-radius:12px;overflow:hidden;display:flex;flex-direction:column}
.dish img{width:100%;aspect-ratio:520/322;object-fit:cover;display:block;background:#000}
.dish .tx{padding:8px 10px;flex:1}.dish .tx b{display:block}.dish .tx span{color:var(--muted);font-size:.85rem}
.dish .bt{display:flex;gap:4px;padding:0 8px 8px;flex-wrap:wrap}.dish .bt button{padding:4px 9px;font-size:.85rem}
#dprev[hidden]{display:none}
#pqrbox{background:#fff;padding:10px;border-radius:10px;display:inline-block}
.ph{display:flex;align-items:center;gap:10px;padding:8px 0;border-bottom:1px solid #eee;flex-wrap:wrap}.ph b{min-width:140px}.ph .hint{flex:1;margin:0}
h3{margin:16px 0 6px;font-size:1rem}
#dprev{width:260px;max-width:100%;aspect-ratio:520/322;object-fit:cover;border-radius:10px;display:block;margin-top:8px;background:#000}
.stat b{display:block;font-size:1.4rem;font-variant-numeric:tabular-nums}.stat span{color:var(--muted);font-size:.85rem}
#qrbox{background:#fff;padding:10px;border-radius:10px;display:inline-block;margin-top:8px}
.tbl{overflow-x:auto;margin-top:8px}
table{border-collapse:collapse;width:100%;font-size:.88rem}
th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);vertical-align:top}
th{color:var(--muted);font-weight:600}
td.n{font-variant-numeric:tabular-nums;white-space:nowrap}
a{color:var(--accent)}
</style></head><body><div class="wrap">
<header><h1><span>Babai</span> Hub</h1><div class="pill" id="pill"><span class="dot"></span><span id="pillText">Starting…</span></div></header>

<section><h2>Right now</h2><div class="stats">
<div class="stat"><b id="sReady">–</b><span>dosa tokens ready</span></div>
<div class="stat"><b id="sPrep">–</b><span>dosa tokens preparing</span></div>
<div class="stat"><b id="sOpen">–</b><span>KOTs in the kitchen</span></div>
<div class="stat"><b id="sDone">–</b><span>KOTs finished today (saved)</span></div>
<div class="stat"><b id="sPP">–</b><span>last read from Petpooja</span></div>
<div class="stat"><b id="sCloud">–</b><span>last cloud update</span></div>
</div><p class="hint" id="problems"></p></section>

<div class="grid">
<section><div class="step">Step 1</div><h2>Petpooja billing station</h2>
<form id="fp" autocomplete="off">
<label for="outlet">Outlet name (exactly as on the bill, e.g. VTR, Sarjapur, HSR, JPN)</label>
<input type="text" id="outlet" placeholder="VTR" maxlength="30">
<p class="hint">One hub per outlet. Customers see this name, and their bill photo is matched to it.</p>
<label for="loc">Outlet location (so customers' phones pick the nearest outlet)</label>
<input type="text" id="loc" placeholder="12.9356, 77.7425  or a Google Maps link">
<div class="row" style="margin-top:6px"><button type="button" class="ghost" id="here">Use this PC's location</button></div>
<p class="hint">Google Maps: long-press the shop → copy the numbers shown at the top, or Share → Copy link.</p>
<label for="server">IP address of the main Petpooja billing PC</label>
<input type="text" id="server" placeholder="192.168.1.5">
<p class="hint">The same address you typed on the KDS TV. Port 3000 is used unless you add one (192.168.1.5:3000).</p>
<div class="row"><button type="submit">Save &amp; test</button></div>
<div class="msg" id="pmsg" role="status"></div></form>
</section>

<section><div class="step">Step 2</div><h2>Firebase (free cloud database)</h2>
<form id="f" autocomplete="off">
<label for="db">Database address</label><input type="text" id="db" placeholder="babai-dosa-default-rtdb.asia-southeast1.firebasedatabase.app">
<label for="secret">Database secret</label><input type="password" id="secret" placeholder="leave empty to keep the saved one">
<label for="page">Customer page address</label><input type="text" id="page">
<div class="row"><button type="submit">Save &amp; test</button><button type="button" class="ghost" id="test">Test again</button></div>
<div class="msg" id="fmsg" role="status"></div></form>
<p class="hint">Rules to paste in Firebase (Realtime Database → Rules):</p>
<pre id="rules">__RULES__</pre>
<p class="hint">Customers can read the outlet names, one token at a time, the dosa wait and the dish carousel. Everything else is private. The same rules, database and secret are used by every outlet. The hub also updates the rules by itself when it connects.</p>
</section>

<section><div class="step">Step 3</div><h2>QR code for customers</h2>
<div id="qrwrap" hidden><div id="qrbox"></div><div class="row"><a href="/poster" target="_blank"><button type="button">Open printable poster</button></a><a href="/customer-page"><button type="button" class="ghost">Download customer page</button></a></div>
<p class="hint">Upload the downloaded index.html to Netlify (babaidosa → Deploys) once: then customers can also open babaidosa.netlify.app and type their token without scanning.</p>
<p class="hint">Print it (Ctrl+P), stick it at the counter and on tables. Customers scan, type their token number and see their dosa live.</p></div>
<p id="qrnone" class="hint">Appears after Step 2.</p>
</section>

<section><h2>This PC</h2>
<p>Babai Hub starts by itself whenever this PC starts, and lives in the tray next to the clock (right-click the Babai icon for Petpooja IP, Pause / Resume, poster). To remove it: Windows Settings → Apps → Babai Hub → Uninstall.</p>
<div class="row"><button type="button" class="ghost" id="pauseBtn">Pause</button></div>
<div class="msg" id="amsg" role="status"></div>
<p class="hint">Cloud use this month (<span id="month"></span>): <span id="use"></span>. Firebase's free plan allows 10 GB downloaded a month and 1 GB stored.</p>
<p class="hint">Finished KOTs are also saved on this PC in <code id="dir"></code>\days</p>
<h3>PIN</h3><p class="hint">The page asks for the PIN (first time: 1234) and stays open in this browser for 12 hours.</p>
<form id="fpin"><label for="pold">Current PIN</label><input type="password" id="pold" inputmode="numeric" maxlength="8" autocomplete="off">
<label for="pnew">New PIN (4 to 8 digits)</label><input type="password" id="pnew" inputmode="numeric" maxlength="8" autocomplete="off">
<div class="row"><button>Change PIN</button><button type="button" class="ghost" id="lockBtn">Lock now</button></div></form><p id="pinmsg" class="msg"></p>
</section>
</div>

<section id="dishes"><div class="step">Step 4 · optional</div><h2>Dish carousel on customers' phones</h2>
<p class="hint">"Try next time" photos customers swipe through while they wait. Up to 10 dishes. With no dishes the carousel is hidden and customers only see the game.</p>
<p class="hint" id="dmode"></p>
<div class="dishes" id="dlist"></div>
<form id="fd" autocomplete="off">
<h2 id="dformTitle" style="margin-top:16px">Add a dish</h2>
<label for="dphoto">Photo</label><input type="file" id="dphoto" accept="image/*">
<img id="dprev" alt="" hidden>
<label for="dname">Dish name</label><input type="text" id="dname" maxlength="40" placeholder="Ghee Karam Dosa">
<label for="dline">Short line (optional)</label><input type="text" id="dline" maxlength="90" placeholder="Roasted chilli-garlic karam and ghee on a hot tawa">
<div class="row"><button type="submit" id="dsave">Add dish</button><button type="button" class="ghost" id="dcancel" hidden>Cancel</button></div>
<div class="msg" id="dmsg" role="status"></div></form>
<div class="row"><button type="button" class="ghost" id="dnone">Show no dishes (game only)</button><button type="button" class="ghost" id="ddefault">Use Babai's default dishes</button></div>
<p class="hint">Photos are made small here before they are sent (about 30 KB each), so the carousel stays inside Firebase's free plan.</p>
</section>

<section id="scanners"><div class="step">Step 5 · optional</div><h2>Scanner phones &amp; rider screen</h2>
<p class="hint">Babai Scanner phones scan a KOT barcode and the hub marks that online order <b>Food Ready</b> in Petpooja (the same request the KDS app sends). They use the shop Wi-Fi when they can, and the internet (Firebase) anywhere else.</p>
<h3>Bridge Print (pickup slots)</h3>
<form id="fb"><label for="baddr">Bridge Print address (leave empty to find it on the Petpooja PC)</label><input type="text" id="baddr" placeholder="e.g. 192.168.1.3:8787" autocomplete="off">
<div class="row"><button>Save &amp; test</button></div></form><p id="bstate" class="hint"></p><p id="bmsg" class="msg"></p>
<h3>Phones</h3>
<div id="plist"></div>
<form id="fph"><label for="pname">New phone's name</label><input type="text" id="pname" maxlength="30" placeholder="e.g. Counter phone, Packing 2"><div class="row"><button>Add phone</button></div></form>
<p id="phmsg" class="msg"></p>
<div id="pqrwrap" hidden><p class="hint" id="pqrfor"></p><div id="pqrbox"></div><p class="hint">Open Babai Scanner on the phone → <b>Pair with hub</b> → scan this code. Keep it private: whoever scans it can mark orders ready (remove the phone here to stop it).</p></div>
<p class="hint">Shop Wi-Fi: <b id="pwifi">–</b> · Internet: <b id="pnet">–</b></p>
<p class="hint">KDS rider screen: long-press 🛵 RIDER → settings → <b>Babai Hub</b> → <span id="pkds">–</span> (or let it find the hub). The first time, if Windows asks, allow Babai Hub on <b>private networks</b>.</p>
<details><summary class="hint">Scans today (newest first)</summary><table class="kt"><tbody id="slog"></tbody></table></details>
</section>

<section><h2>What Petpooja sent</h2>
<p class="hint" id="ppInfo">Nothing yet.</p>
<div class="tbl"><table><thead><tr><th>Token</th><th>Table</th><th>Type</th><th>Status</th><th>Items</th></tr></thead><tbody id="kots"></tbody></table></div>
<details><summary class="hint">Raw answer (send a photo of this if something looks wrong)</summary><pre id="raw"></pre></details>
</section>
</div>

<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<script>
var $=function(i){return document.getElementById(i)},loaded=false,qrDone="";
var OT={1:"Delivery",2:"Pick-up",3:"Dine-in"},ST={"9":"Food ready","10":"Dispatched","0":"Cancelled"};
function ago(s){if(s<0)return "never";if(s<60)return s+" s ago";if(s<3600)return Math.round(s/60)+" min ago";return Math.round(s/3600)+" h ago"}
function esc(s){return String(s==null?"":s).replace(/[&<>"]/g,function(c){return{"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]})}
function post(u,b){return fetch(u,{method:"POST",headers:{"Content-Type":"application/json","X-Babai-Hub":"1"},body:JSON.stringify(b)}).then(function(r){if(r.status===401){location.reload()}return r.json()})}
var _f=window.fetch;window.fetch=function(u,o){return _f(u,o).then(function(r){if(r.status===401&&!(o&&o.method==="POST"))location.reload();return r})};
function show(el,r){el.textContent=r.msg;el.className="msg "+(r.ok?"ok":"bad")}
function refresh(){fetch("/status.json").then(function(r){return r.json()}).then(function(s){
 if(!loaded){$("server").value=s.server||"";$("outlet").value=s.outlet||"";$("loc").value=(s.lat||s.lng)?(s.lat+", "+s.lng):"";$("db").value=(s.db||"").replace(/^https:\/\//,"");$("page").value=s.page||"";loaded=true}
 $("sReady").textContent=s.ready;$("sPrep").textContent=s.preparing;$("sOpen").textContent=s.openKots;$("sDone").textContent=s.finishedToday;
 $("sPP").textContent=s.server?ago(s.ppOkAgo):"not set up";$("sCloud").textContent=s.configured?ago(s.cloudOkAgo):"not set up";
 var probs=[];if(s.ppErrAgo>=0)probs.push("Petpooja ("+ago(s.ppErrAgo)+"): "+s.ppErr);if(s.cloudErrAgo>=0)probs.push("Cloud ("+ago(s.cloudErrAgo)+"): "+s.cloudErr);
 $("problems").textContent=probs.join("  •  ");
 $("month").textContent=s.month||"–";$("use").textContent=(s.upMB||0).toFixed(1)+" MB sent, "+(s.downMB||0).toFixed(1)+" MB received, "+(s.writes||0)+" updates";
 $("dir").textContent=s.dataDir;
 var ppOk=s.server&&s.ppOkAgo>=0&&s.ppOkAgo<30,cloud=s.configured&&s.cloudOkAgo>=0&&s.cloudErrAgo<0;
 $("pill").className="pill "+(ppOk&&cloud?"ok":((s.server||s.configured)?"bad":""));
 $("pauseBtn").textContent=s.paused?"Resume":"Pause";
 if(s.paused){$("pill").className="pill bad";$("pillText").textContent="Paused";}else
 $("pillText").textContent=!s.outlet?"Enter the outlet name (Step 1)":!s.server?"Enter the Petpooja IP (Step 1)":!ppOk?"Can't read Petpooja":!s.configured?"Set up Firebase (Step 2)":cloud?"Live: Petpooja → cloud":"Cloud problem";
 if(s.qr&&s.qr!==qrDone&&window.QRCode){$("qrbox").innerHTML="";new QRCode($("qrbox"),{text:s.qr,width:180,height:180,correctLevel:QRCode.CorrectLevel.M});qrDone=s.qr}
 $("qrwrap").hidden=!s.qr;$("qrnone").hidden=!!s.qr;
 $("ppInfo").textContent=s.ppOkAgo>=0?(s.ppRows+" item lines in "+(s.ppKots||[]).length+" KOTs"+(s.ppStock?", "+s.ppStock+" menu items ("+s.ppStockOff+" switched off)":"")):"Nothing yet.";
 $("kots").innerHTML=(s.ppKots||[]).map(function(k){return "<tr><td class=n>"+esc(k.tk||"–")+"</td><td>"+esc(k.tb)+"</td><td>"+esc(OT[k.ot]||k.ot)+(k.ch?" · "+esc(k.ch):"")+"</td><td>"+esc(ST[k.st]||"Preparing")+"</td><td>"+esc(k.items)+"</td></tr>"}).join("");
 $("raw").textContent=s.ppSample||"";
}).catch(function(){$("pillText").textContent="Hub not responding"})}
$("here").addEventListener("click",function(){if(!navigator.geolocation){show($("pmsg"),{ok:false,msg:"This browser can't give the location; type it instead."});return}
 $("pmsg").textContent="Finding this PC's location…";$("pmsg").className="msg";
 navigator.geolocation.getCurrentPosition(function(p){$("loc").value=p.coords.latitude.toFixed(5)+", "+p.coords.longitude.toFixed(5);
  show($("pmsg"),{ok:true,msg:"Found (accuracy about "+Math.round(p.coords.accuracy)+" m). Check it, then Save & test."})},
  function(){show($("pmsg"),{ok:false,msg:"Windows didn't give the location (Settings → Privacy → Location). Type it instead."})},{enableHighAccuracy:true,timeout:15000})});
$("fp").addEventListener("submit",function(e){e.preventDefault();$("pmsg").textContent="Connecting…";$("pmsg").className="msg";
 post("/petpooja",{Server:$("server").value,Outlet:$("outlet").value,Location:$("loc").value}).then(function(r){show($("pmsg"),r);refresh()})});
$("f").addEventListener("submit",function(e){e.preventDefault();$("fmsg").textContent="Testing…";$("fmsg").className="msg";
 post("/settings",{DB:$("db").value,Secret:$("secret").value,Page:$("page").value}).then(function(r){show($("fmsg"),r);$("secret").value="";refresh()})});
$("test").addEventListener("click",function(){$("fmsg").textContent="Testing…";post("/test",{}).then(function(r){show($("fmsg"),r);refresh()})});
$("pauseBtn").addEventListener("click",function(){post("/pause",{Paused:$("pauseBtn").textContent==="Pause"}).then(function(r){show($("amsg"),r);refresh()})});
var dish=null,editI=-1,dimg="";
function loadDishes(){fetch("/promo").then(function(r){return r.json()}).then(function(p){dish=p;var n=(p.dishes||[]).length;
 $("dmode").textContent=!p.custom?"Now: Babai's default dishes (5). Add a dish to use your own instead.":n?("Now: your "+n+" of "+p.max+" dishes \u2022 "+p.sync):"Now: no dishes \u2014 customers see only the game \u2022 "+p.sync;
 $("dlist").innerHTML=(p.dishes||[]).map(function(d,i){return '<div class="dish"><img alt="" src="/promo/img?k='+d.k+'"><div class="tx"><b>'+esc(d.t)+'</b><span>'+esc(d.d)+'</span></div><div class="bt">'
  +'<button type="button" class="ghost" data-op="up" data-i="'+i+'" aria-label="Move left">\u2190</button><button type="button" class="ghost" data-op="down" data-i="'+i+'" aria-label="Move right">\u2192</button>'
  +'<button type="button" class="ghost" data-op="edit" data-i="'+i+'">Edit</button><button type="button" class="ghost" data-op="delete" data-i="'+i+'">Delete</button></div></div>'}).join("");
 var full=p.custom&&n>=p.max&&editI<0;$("dsave").disabled=full;if(full&&editI<0)$("dmsg").textContent="10 dishes is the most. Delete one to add another.";
})}
function dishForm(i){editI=i;dimg="";$("dphoto").value="";$("dprev").hidden=true;
 var d=i>=0?dish.dishes[i]:{t:"",d:""};$("dname").value=d.t;$("dline").value=d.d;
 $("dformTitle").textContent=i>=0?"Edit dish "+(i+1):"Add a dish";$("dsave").textContent=i>=0?"Save changes":"Add dish";$("dcancel").hidden=i<0;
 if(i>=0){$("dprev").src="/promo/img?k="+d.k;$("dprev").hidden=false;$("dname").focus()}}
$("dlist").addEventListener("click",function(e){var b=e.target.closest("button");if(!b)return;var i=+b.dataset.i,op=b.dataset.op;
 if(op==="edit"){dishForm(i);return}
 if(op==="delete"&&!confirm("Delete "+dish.dishes[i].t+"?"))return;
 post("/promo",{Op:op,I:i}).then(function(r){show($("dmsg"),r);if(editI>=0)dishForm(-1);loadDishes()})});
// Photo -> 640 x 396 (the card's shape, centre cut), WebP (or JPEG): small enough for the free plan.
$("dphoto").addEventListener("change",function(){var f=this.files&&this.files[0];if(!f)return;var u=URL.createObjectURL(f),im=new Image();
 im.onload=function(){var W=640,H=396,c=document.createElement("canvas");c.width=W;c.height=H;var g=c.getContext("2d");
  var s=Math.max(W/im.naturalWidth,H/im.naturalHeight),w=im.naturalWidth*s,h=im.naturalHeight*s;g.drawImage(im,(W-w)/2,(H-h)/2,w,h);URL.revokeObjectURL(u);
  var q=0.72,d=c.toDataURL("image/webp",q);if(d.indexOf("data:image/webp")!==0)d=c.toDataURL("image/jpeg",0.78);
  while(d.length>110000&&q>0.4){q-=0.1;d=d.indexOf("data:image/webp")===0?c.toDataURL("image/webp",q):c.toDataURL("image/jpeg",q)}
  dimg=d;$("dprev").src=d;$("dprev").hidden=false;$("dmsg").textContent="Photo ready ("+Math.round(d.length*0.75/1024)+" KB).";$("dmsg").className="msg"};
 im.onerror=function(){show($("dmsg"),{ok:false,msg:"That file is not a photo this PC can open."})};im.src=u});
$("fd").addEventListener("submit",function(e){e.preventDefault();
 if(editI<0&&!dimg){show($("dmsg"),{ok:false,msg:"Choose a photo of the dish."});return}
 $("dmsg").textContent="Saving…";$("dmsg").className="msg";
 post("/promo",{Op:editI>=0?"edit":"add",I:editI,T:$("dname").value,D:$("dline").value,Image:dimg}).then(function(r){show($("dmsg"),r);if(r.ok)dishForm(-1);loadDishes()})
 .catch(function(){show($("dmsg"),{ok:false,msg:"Couldn't save (photo too big?)."})})});
$("dcancel").addEventListener("click",function(){dishForm(-1)});
$("dnone").addEventListener("click",function(){if(confirm("Hide the dish carousel? Customers will see only the game."))post("/promo",{Op:"none"}).then(function(r){show($("dmsg"),r);loadDishes()})});
$("ddefault").addEventListener("click",function(){if(confirm("Remove your dishes and use Babai's default dishes?"))post("/promo",{Op:"default"}).then(function(r){show($("dmsg"),r);dishForm(-1);loadDishes()})});
loadDishes();setInterval(loadDishes,15000);
var phQR="";
function pqr(t,who){$("pqrwrap").hidden=false;$("pqrfor").textContent="Pairing code for "+who;if(t!==phQR&&window.QRCode){$("pqrbox").innerHTML="";new QRCode($("pqrbox"),{text:t,width:220,height:220,correctLevel:QRCode.CorrectLevel.M});phQR=t}}
function loadPhones(){fetch("/phones").then(function(r){return r.json()}).then(function(p){
 if(!bLoaded){$("baddr").value=p.bridgeAddr||"";bLoaded=true}
 var b=p.bridge||{};$("bstate").textContent="Bridge Print: "+(b.addr?b.addr+" \u2022 ":"")+(b.state||"")+(b.auto&&b.note?" ("+b.note+")":"");
 $("pwifi").textContent=(p.wifi||[]).join(", ")||"no network found";$("pnet").textContent=p.internet;$("pkds").textContent=(p.wifi||[])[0]||"this PC's address";
 $("plist").innerHTML=(p.phones||[]).length?(p.phones||[]).map(function(x){return '<div class="ph"><b>'+esc(x.name)+'</b><span class="hint">'+(x.seen?"last used "+ago(Math.round((Date.now()-x.seen)/1000))+" via "+(x.route==="wifi"?"Wi-Fi":"internet"):"not used yet")+'</span><button type="button" class="ghost" data-k="'+esc(x.key)+'" data-op="qr">Show QR</button><button type="button" class="ghost" data-k="'+esc(x.key)+'" data-n="'+esc(x.name)+'" data-op="remove">Remove</button></div>'}).join(""):'<p class="hint">No phones yet.</p>';
 $("slog").innerHTML=(p.scans||[]).map(function(e){var d=new Date(e.t);return "<tr><td>"+d.toLocaleTimeString()+"</td><td>"+esc(e.d)+"</td><td>"+(e.r==="wifi"?"Wi-Fi":"internet")+"</td><td>"+(e.ok?"\u2713 ":"\u2715 ")+esc(e.m)+"</td></tr>"}).join("");
})}
var bLoaded=false;
$("fb").addEventListener("submit",function(e){e.preventDefault();$("bmsg").textContent="Testing…";$("bmsg").className="msg";post("/bridge",{Addr:$("baddr").value}).then(function(r){show($("bmsg"),r);loadPhones()})});
$("fph").addEventListener("submit",function(e){e.preventDefault();post("/phones",{Op:"add",Name:$("pname").value}).then(function(r){show($("phmsg"),r);if(r.ok){$("pname").value="";pqr(r.qr,r.msg.replace(/^.* on /,"").replace(/\.$/,""))}loadPhones()})});
$("plist").addEventListener("click",function(e){var b=e.target.closest("button");if(!b)return;
 if(b.dataset.op==="qr"){post("/phones",{Op:"qr",Key:b.dataset.k}).then(function(r){if(r.ok)pqr(r.qr,r.msg);else show($("phmsg"),r)});return}
 if(b.dataset.op==="remove"&&confirm("Remove "+b.dataset.n+"? It won't be able to mark orders any more.")){post("/phones",{Op:"remove",Key:b.dataset.k}).then(function(r){show($("phmsg"),r);$("pqrwrap").hidden=true;phQR="";loadPhones()})}});
loadPhones();setInterval(loadPhones,5000);
$("fpin").addEventListener("submit",function(e){e.preventDefault();post("/pin",{Old:$("pold").value,New:$("pnew").value}).then(function(r){show($("pinmsg"),r);if(r.ok){$("pold").value="";$("pnew").value=""}})});
$("lockBtn").addEventListener("click",function(){post("/lock",{}).then(function(){location.reload()})});
if(location.hash==="#scanners")setTimeout(function(){$("scanners").scrollIntoView()},300);

if(location.hash==="#dishes")setTimeout(function(){$("dishes").scrollIntoView()},300);
refresh();setInterval(refresh,3000);
</script></body></html>`

const posterHTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>Babai Tiffins – Track your dosa</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Baloo+2:wght@600;800&family=Mukta:wght@400;600&display=swap">
<style>
@page{size:A4;margin:14mm}
body{margin:0;font-family:Mukta,"Segoe UI",sans-serif;color:#2a1d14;background:#fff}
.sheet{max-width:180mm;margin:0 auto;padding:10mm 0;text-align:center}
.brand{font-family:"Baloo 2",sans-serif;font-weight:800;color:#ff5200;font-size:22pt;letter-spacing:.5px}
h1{font-family:"Baloo 2",sans-serif;font-weight:800;font-size:46pt;line-height:1;margin:4mm 0 2mm}
.sub{font-size:16pt;color:#6b5847;margin:0 0 8mm}
#qr{display:inline-block;padding:6mm;border:3px solid #ff5200;border-radius:8mm}
#qr img,#qr canvas{width:95mm!important;height:95mm!important}
ol{display:flex;justify-content:center;gap:8mm;list-style:none;padding:0;margin:9mm 0 6mm;counter-reset:s}
li{counter-increment:s;width:50mm;font-size:13pt;line-height:1.3}
li::before{content:counter(s);display:block;margin:0 auto 2mm;width:11mm;height:11mm;line-height:11mm;border-radius:50%;background:#ff5200;color:#fff;font-weight:700;font-size:15pt}
.url{font-size:8pt;color:#9a8878;word-break:break-all;margin-top:6mm}
.tip{font-size:11pt;color:#6b5847}
@media screen{.sheet{box-shadow:0 2px 20px rgba(0,0,0,.12);margin:20px auto;padding:14mm}body{background:#eee}}
</style></head><body><div class="sheet">
<div class="brand">Babai Tiffins__OUTLET__</div>
<h1>Where's my dosa?</h1>
<p class="sub">Live status on your phone. No app, no login.</p>
<div id="qr"></div>
<ol><li>Scan with your phone camera</li><li>Type the token number on your bill</li><li>We buzz your phone when it's ready</li></ol>
<p class="tip">Keep the page open while you wait.</p>
<div class="url">__URL_TEXT__</div>
</div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<script>new QRCode(document.getElementById("qr"),{text:__URL_JSON__,width:600,height:600,correctLevel:QRCode.CorrectLevel.M});</script>
</body></html>`
