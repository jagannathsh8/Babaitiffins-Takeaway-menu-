package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"html"
	"io"
	"net/http"
	"strings"
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
	io.WriteString(w, homeHTML)
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
	var in struct{ Server, Outlet string }
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
<pre id="rules">{"rules":{".read":false,".write":false,"babai":{"outlets":{".read":true},"o":{"$o":{"meta":{".read":true},"t":{"$tok":{".read":true}}}}}}}</pre>
<p class="hint">Customers can read the outlet names, one token at a time and the dosa wait. Everything else is private. The same rules, database and secret are used by every outlet.</p>
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
</section>
</div>

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
function post(u,b){return fetch(u,{method:"POST",headers:{"Content-Type":"application/json","X-Babai-Hub":"1"},body:JSON.stringify(b)}).then(function(r){return r.json()})}
function show(el,r){el.textContent=r.msg;el.className="msg "+(r.ok?"ok":"bad")}
function refresh(){fetch("/status.json").then(function(r){return r.json()}).then(function(s){
 if(!loaded){$("server").value=s.server||"";$("outlet").value=s.outlet||"";$("db").value=(s.db||"").replace(/^https:\/\//,"");$("page").value=s.page||"";loaded=true}
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
$("fp").addEventListener("submit",function(e){e.preventDefault();$("pmsg").textContent="Connecting…";$("pmsg").className="msg";
 post("/petpooja",{Server:$("server").value,Outlet:$("outlet").value}).then(function(r){show($("pmsg"),r);refresh()})});
$("f").addEventListener("submit",function(e){e.preventDefault();$("fmsg").textContent="Testing…";$("fmsg").className="msg";
 post("/settings",{DB:$("db").value,Secret:$("secret").value,Page:$("page").value}).then(function(r){show($("fmsg"),r);$("secret").value="";refresh()})});
$("test").addEventListener("click",function(){$("fmsg").textContent="Testing…";post("/test",{}).then(function(r){show($("fmsg"),r);refresh()})});
$("pauseBtn").addEventListener("click",function(){post("/pause",{Paused:$("pauseBtn").textContent==="Pause"}).then(function(r){show($("amsg"),r);refresh()})});
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
