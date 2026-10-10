package main

import (
	"encoding/json"
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

func handlePoster(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	u := qrURL()
	if u == "" {
		io.WriteString(w, `<!doctype html><meta charset="utf-8"><p style="font:18px system-ui;padding:24px">Set up the Firebase database on the hub page first, then open the poster again.</p>`)
		return
	}
	b, _ := json.Marshal(u)
	io.WriteString(w, strings.Replace(strings.Replace(posterHTML, "__URL_JSON__", string(b), 1), "__URL_TEXT__", html.EscapeString(u), 1))
}

const homeHTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Babai Hub</title>
<style>
:root{--bg:#f7f3ee;--card:#fff;--ink:#271c14;--muted:#76675a;--line:#e7ddd1;--accent:#ff5200;--ok:#1f8a4c;--bad:#b3261e;--warnbg:#fff3e6;
--f:"Segoe UI",system-ui,-apple-system,sans-serif;--mono:Consolas,"Cascadia Mono",ui-monospace,monospace}
@media (prefers-color-scheme:dark){:root{--bg:#16110d;--card:#211913;--ink:#f4ebe2;--muted:#b5a393;--line:#3a2d23;--accent:#ff7a33;--ok:#48c97f;--bad:#ff8a80;--warnbg:#33230f;color-scheme:dark}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 var(--f);padding-inline:16px;padding-block:20px 40px}
.wrap{max-width:980px;margin:0 auto;display:grid;gap:16px}
header{display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap}
h1{margin:0;font-size:1.6rem;letter-spacing:.2px}h1 span{color:var(--accent)}
h2{margin:0 0 8px;font-size:1.05rem}
.pill{display:inline-flex;align-items:center;gap:8px;border-radius:999px;padding:6px 14px;font-weight:600;background:var(--card);border:1px solid var(--line)}
.dot{width:10px;height:10px;border-radius:50%;background:var(--muted)}
.ok .dot{background:var(--ok)}.bad .dot{background:var(--bad)}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:16px}
section{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:16px 18px;min-width:0}
.step{font-size:.75rem;font-weight:700;letter-spacing:.12em;text-transform:uppercase;color:var(--accent)}
label{display:block;font-weight:600;margin:10px 0 4px}
input[type=text],input[type=password]{width:100%;font:inherit;padding:8px 10px;border-radius:8px;border:1px solid var(--line);background:var(--bg);color:var(--ink)}
button{font:inherit;font-weight:600;border:0;border-radius:8px;padding:9px 16px;background:var(--accent);color:#fff;cursor:pointer}
button.ghost{background:transparent;color:var(--ink);border:1px solid var(--line)}
button:focus-visible,input:focus-visible,a:focus-visible{outline:3px solid var(--accent);outline-offset:2px}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-top:12px}
.msg{margin-top:10px;font-weight:600}.msg.ok{color:var(--ok)}.msg.bad{color:var(--bad)}
.hint{color:var(--muted);font-size:.9rem;margin:6px 0 0}
code,pre{font-family:var(--mono);font-size:.85rem;word-break:break-all}
pre{background:var(--bg);border:1px solid var(--line);border-radius:8px;padding:10px;overflow-x:auto;margin:6px 0 0;white-space:pre-wrap;word-break:break-all}
.big{font-size:1.6rem;font-weight:700;font-family:var(--mono);color:var(--accent)}
.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:10px}
.stat{background:var(--bg);border-radius:10px;padding:10px 12px}
.stat b{display:block;font-size:1.4rem;font-variant-numeric:tabular-nums}.stat span{color:var(--muted);font-size:.85rem}
#qrbox{background:#fff;padding:10px;border-radius:10px;display:inline-block;margin-top:8px}
.warn{background:var(--warnbg);border-radius:10px;padding:10px 12px;margin-top:10px}
ul{margin:6px 0 0;padding-left:1.2em}
a{color:var(--accent)}
</style></head><body><div class="wrap">
<header><h1><span>Babai</span> Hub</h1><div class="pill" id="pill"><span class="dot"></span><span id="pillText">Starting…</span></div></header>

<section><h2>Right now</h2><div class="stats">
<div class="stat"><b id="sReady">–</b><span>dosa tokens ready</span></div>
<div class="stat"><b id="sPrep">–</b><span>dosa tokens preparing</span></div>
<div class="stat"><b id="sOpen">–</b><span>KOTs on the kitchen board</span></div>
<div class="stat"><b id="sDone">–</b><span>KOTs finished today (saved)</span></div>
<div class="stat"><b id="sTv">–</b><span>last data from the TV</span></div>
<div class="stat"><b id="sCloud">–</b><span>last cloud update</span></div>
</div><p class="hint" id="cloudErr"></p></section>

<div class="grid">
<section><div class="step">Step 1</div><h2>Firebase (free cloud database)</h2>
<form id="f" autocomplete="off">
<label for="db">Database address</label><input type="text" id="db" placeholder="babai-dosa-default-rtdb.asia-southeast1.firebasedatabase.app">
<label for="secret">Database secret</label><input type="password" id="secret" placeholder="leave empty to keep the saved one">
<label for="page">Customer page address</label><input type="text" id="page">
<div class="row"><button type="submit">Save &amp; test</button><button type="button" class="ghost" id="test">Test again</button></div>
<div class="msg" id="fmsg" role="status"></div></form>
<details><summary class="hint">How to get these (once, about 10 minutes)</summary><ol class="hint">
<li>console.firebase.google.com → <b>Add project</b> (Analytics not needed).</li>
<li>Build → <b>Realtime Database</b> → Create database → Singapore → <b>locked mode</b>.</li>
<li><b>Rules</b> tab → paste the rules below → Publish.</li>
<li>Copy the address shown above the data (…firebasedatabase.app).</li>
<li>⚙ Project settings → <b>Service accounts</b> → <b>Database secrets</b> → Show → copy.</li></ol>
<pre id="rules">{"rules":{".read":false,".write":false,"babai":{"dosa":{".read":true}}}}</pre>
<p class="hint">Only the dosa token status is readable by the public. Everything else is private.</p></details>
</section>

<section><div class="step">Step 2</div><h2>Kitchen TV</h2>
<p>On the <b>main kitchen TV only</b>: long-press <b>✦ DOSA LIVE</b> (hold OK on the remote) and enter</p>
<div class="big" id="addr">…</div>
<p class="hint">If the TV says "not answering": when Windows asked about Babai Hub and the firewall, it needed <b>Allow</b> on private networks. Windows Security → Firewall → Allow an app → tick Babai Hub (Private).</p>
<div id="devs" class="hint"></div>
</section>

<section><div class="step">Step 3</div><h2>QR code for customers</h2>
<div id="qrwrap" hidden><div id="qrbox"></div><div class="row"><a href="/poster" target="_blank"><button type="button">Open printable poster</button></a></div>
<p class="hint">Print it, stick it at the counter and on tables. Customers scan, type their token number and see Preparing / Ready live.</p></div>
<p id="qrnone" class="hint">Appears after Step 1.</p>
</section>

<section><h2>This PC</h2>
<div class="row"><label style="margin:0"><input type="checkbox" id="auto"> Start Babai Hub when Windows starts</label></div>
<div class="msg" id="amsg" role="status"></div>
<p class="hint">Cloud use this month (<span id="month"></span>): <span id="use"></span>. Firebase's free plan allows 10 GB downloaded a month and 1 GB stored.</p>
<p class="hint">Finished KOTs are also saved on this PC in <code id="dir"></code>\days</p>
</section>
</div></div>

<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<script>
var $=function(i){return document.getElementById(i)},loaded=false,qrDone="";
function ago(s){if(s<0)return "never";if(s<60)return s+" s ago";if(s<3600)return Math.round(s/60)+" min ago";return Math.round(s/3600)+" h ago"}
function post(u,b){return fetch(u,{method:"POST",headers:{"Content-Type":"application/json","X-Babai-Hub":"1"},body:JSON.stringify(b)}).then(function(r){return r.json()})}
function show(el,r){el.textContent=r.msg;el.className="msg "+(r.ok?"ok":"bad")}
function refresh(){fetch("/status.json").then(function(r){return r.json()}).then(function(s){
 if(!loaded){$("db").value=(s.db||"").replace(/^https:\/\//,"");$("page").value=s.page||"";$("auto").checked=!!s.autostart;loaded=true}
 $("sReady").textContent=s.ready;$("sPrep").textContent=s.preparing;$("sOpen").textContent=s.openKots;$("sDone").textContent=s.finishedToday;
 $("sTv").textContent=ago(s.ingestAgo);$("sCloud").textContent=s.configured?ago(s.cloudOkAgo):"not set up";
 $("cloudErr").textContent=s.cloudErrAgo>=0?"Cloud problem ("+ago(s.cloudErrAgo)+"): "+s.cloudErr:"";
 $("addr").textContent=(s.lan&&s.lan.length)?s.lan.map(function(a){return a+":"+s.port}).join("  or  "):"This PC isn't on the shop Wi-Fi / network";
 $("devs").innerHTML=(s.devices||[]).length?"Sending: "+s.devices.map(function(d){return d.name.replace(/[<>&]/g,"")+" ("+ago(d.ago)+")"}).join(", ")+(s.devices.filter(function(d){return d.ago<120}).length>1?"<div class=warn>More than one TV is sending. Set the hub address on one TV only.</div>":""):"No TV has sent data yet.";
 $("month").textContent=s.month||"–";$("use").textContent=(s.upMB||0).toFixed(1)+" MB sent, "+(s.downMB||0).toFixed(1)+" MB received, "+(s.writes||0)+" updates";
 $("dir").textContent=s.dataDir;
 var tvOk=s.ingestAgo>=0&&s.ingestAgo<90,cloud=s.configured&&s.cloudOkAgo>=0&&s.cloudErrAgo<0;
 $("pill").className="pill "+(tvOk&&cloud?"ok":(s.configured?"bad":""));
 $("pillText").textContent=!s.configured?"Set up Firebase (Step 1)":!tvOk?"Waiting for the kitchen TV":cloud?"Live: TV → cloud":"Cloud problem";
 if(s.qr&&s.qr!==qrDone&&window.QRCode){$("qrbox").innerHTML="";new QRCode($("qrbox"),{text:s.qr,width:180,height:180,correctLevel:QRCode.CorrectLevel.M});qrDone=s.qr}
 $("qrwrap").hidden=!s.qr;$("qrnone").hidden=!!s.qr;
}).catch(function(){$("pillText").textContent="Hub not responding"})}
$("f").addEventListener("submit",function(e){e.preventDefault();$("fmsg").textContent="Testing…";$("fmsg").className="msg";
 post("/settings",{DB:$("db").value,Secret:$("secret").value,Page:$("page").value}).then(function(r){show($("fmsg"),r);$("secret").value="";refresh()})});
$("test").addEventListener("click",function(){$("fmsg").textContent="Testing…";post("/test",{}).then(function(r){show($("fmsg"),r);refresh()})});
$("auto").addEventListener("change",function(){post("/autostart",{On:$("auto").checked}).then(function(r){show($("amsg"),r)})});
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
<div class="brand">Babai Tiffins</div>
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
