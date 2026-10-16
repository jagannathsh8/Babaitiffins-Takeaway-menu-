"use strict";
// Babai Console window. Bridge Print area is open to everyone; Hub and Data need the PIN.
var $ = function (id) { return document.getElementById(id); };
var LS = {
  get: function (k, d) { try { var v = localStorage.getItem("bc." + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
  set: function (k, v) { try { localStorage.setItem("bc." + k, JSON.stringify(v)); } catch (e) {} }
};
function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
function post(u, b) {
  return fetch(u, { method: "POST", headers: { "Content-Type": "application/json", "X-Babai-Hub": "1" }, body: JSON.stringify(b || {}) })
    .then(function (r) { if (r.status === 401) { S && (S.unlocked = false); render(); } return r.json().catch(function () { return { ok: false, msg: "HTTP " + r.status }; }); });
}
function getJ(u) {
  return fetch(u, { cache: "no-store" }).then(function (r) {
    if (r.status === 401) { if (S) S.unlocked = false; render(); throw new Error("locked"); }
    return r.json();
  });
}
function hm(ms) { if (!ms) return "–"; var d = new Date(ms); return ("0" + d.getHours()).slice(-2) + ":" + ("0" + d.getMinutes()).slice(-2); }
function hms(ms) { if (!ms) return "–"; var d = new Date(ms); return hm(ms) + ":" + ("0" + d.getSeconds()).slice(-2); }
function mmss(ms) { if (ms < 0) ms = 0; var s = Math.floor(ms / 1000), m = Math.floor(s / 60); return m + ":" + ("0" + (s % 60)).slice(-2); }
function typeName(t) { return { 1: "Delivery", 2: "Pick up", 3: "Dine in" }[t] || "–"; }
function brandColor(name, type) {
  var n = String(name || "").toLowerCase();
  if (n.indexOf("toing") >= 0) return "var(--toing)";
  if (n.indexOf("swiggy") >= 0) return "var(--swiggy)";
  if (n.indexOf("zomato") >= 0) return "var(--zomato)";
  if (n.indexOf("ownly") >= 0) return "var(--ownly)";
  if (type === 3) return "var(--dine)";
  if (type === 2) return "var(--pick)";
  return "var(--other)";
}
function last(s, n) { s = String(s || ""); return s.length > n ? "…" + s.slice(-n) : s; }

var AREAS = {
  bridge: { label: "Bridge Print", items: [["overview", "Overview"], ["dispatch", "Dispatch"], ["rules", "Printers & rules"], ["timing", "Print timing"], ["alerts", "Alerts"], ["apps", "Apps"]] },
  hub: { label: "Hub", items: [["status", "Status"], ["petpooja", "Petpooja & Firebase"], ["scanners", "Scanners & rider"], ["customers", "Customers' page"], ["pc", "This PC & updates"]] },
  data: { label: "Data", items: [["metrics", "Metrics"], ["prep", "Prep Live"], ["planner", "Indent planner"], ["sources", "Connections"]] }
};
var HUBFRAME = { status: "now,qr", petpooja: "pp,fb,raw", scanners: "scanners", customers: "qr,dishes", prep: "prep" };
var S = null, offset = 0, area = LS.get("area", "bridge"), tab = null, loop = null, counts = {};
if (!AREAS[area]) area = "bridge";
tab = LS.get("tab." + area, AREAS[area].items[0][0]);
(function fromHash() {
  var h = location.hash.replace("#", "");
  for (var a in AREAS) AREAS[a].items.forEach(function (it) { if (it[0] === h) { area = a; tab = h; } });
})();

function now() { return Date.now() + offset; }

// ---- frame: top bar, tabs, rail ---------------------------------------------------------------
function renderRail() {
  var html = "<h5>" + esc(AREAS[area].label) + "</h5>";
  AREAS[area].items.forEach(function (it) {
    var k = counts[it[0]];
    html += '<button class="tab" role="tab" data-tab="' + it[0] + '" aria-selected="' + (it[0] === tab) + '">' + esc(it[1]) +
      (k ? '<span class="k' + (it[0] === "alerts" ? " alert" : "") + '">' + k + "</span>" : "") + "</button>";
  });
  $("rail").innerHTML = html;
  document.querySelectorAll(".mt").forEach(function (b) { b.setAttribute("aria-selected", String(b.dataset.area === area)); });
}
$("rail").addEventListener("click", function (e) {
  var b = e.target.closest(".tab"); if (!b) return;
  tab = b.dataset.tab; LS.set("tab." + area, tab); render();
});
document.querySelector(".mtabs").addEventListener("click", function (e) {
  var b = e.target.closest(".mt"); if (!b) return;
  area = b.dataset.area; LS.set("area", area);
  tab = LS.get("tab." + area, AREAS[area].items[0][0]);
  if (!AREAS[area].items.some(function (it) { return it[0] === tab; })) tab = AREAS[area].items[0][0];
  render();
});
$("lockBtn").onclick = function () {
  post("/lock").then(function () { if (S) S.unlocked = false; area = "bridge"; tab = LS.get("tab.bridge", "overview"); LS.set("area", area); render(); });
};

function setFrame(src) {
  var f = $("frame");
  $("main").hidden = true; f.hidden = false;
  if (f.getAttribute("src") !== src) f.setAttribute("src", src);
}
function setMain(html) {
  $("frame").hidden = true; $("frame").removeAttribute("src");
  $("main").hidden = false; $("main").innerHTML = html;
}

var VIEWS = {};
function render() {
  if (loop) { clearInterval(loop); loop = null; }
  renderRail();
  $("lockBtn").hidden = !(S && S.unlocked);
  if (area !== "bridge" && !(S && S.unlocked)) { pinView(); return; }
  if (tab === "overview") { setFrame("http://127.0.0.1:8787/"); return; }
  if (HUBFRAME[tab]) { setFrame("/hub?show=" + HUBFRAME[tab]); return; }
  var v = VIEWS[tab];
  if (!v) { setMain('<p class="empty">Not found.</p>'); return; }
  setMain(v.html ? v.html() : "");
  if (v.init) v.init();
  if (v.load) { v.load(); if (v.every) loop = setInterval(v.load, v.every); }
}

function pinView() {
  setMain('<div class="panel pin"><h2>' + esc(AREAS[area].label) + ' is locked</h2><p class="help">Enter the PIN to open Hub and Data. Bridge Print is open to everyone.</p>' +
    '<form id="pinf"><input id="pin" type="password" inputmode="numeric" maxlength="8" autocomplete="off" aria-label="PIN" placeholder="PIN"><button class="btn p">Open</button></form>' +
    '<p class="msg bad" id="pinmsg" role="status"></p></div>');
  $("pin").focus();
  $("pinf").onsubmit = function (e) {
    e.preventDefault();
    post("/unlock", { Pin: $("pin").value }).then(function (r) {
      if (r.ok) { if (S) S.unlocked = true; else S = { unlocked: true }; render(); }
      else { $("pinmsg").textContent = r.msg || "Wrong PIN."; $("pin").value = ""; $("pin").focus(); }
    });
  };
}

// ---- status pills, banner, alerts sound ----------------------------------------------------------
var lastAlertSeen = LS.get("alertSeen", 0);
function beep() {
  try {
    var c = new (window.AudioContext || window.webkitAudioContext)(), o = c.createOscillator(), g = c.createGain();
    o.frequency.value = 880; o.connect(g); g.connect(c.destination); g.gain.value = 0.15; o.start();
    setTimeout(function () { o.frequency.value = 660; }, 180); setTimeout(function () { o.stop(); c.close(); }, 420);
  } catch (e) {}
}
function pill(cls, text) { return '<span class="pill ' + cls + '">' + esc(text) + "</span>"; }
function loadState() {
  return getJ("/console/api/state").then(function (s) {
    var wasUnlocked = S && S.unlocked;
    S = s; offset = s.now - Date.now();
    $("where").textContent = (s.outlet ? s.outlet + " · " : "") + (s.addrs && s.addrs.length ? "PC " + s.addrs.join(", ") : "") + " · v" + s.version;
    var p = s.print || {}, h = "";
    h += s.service ? pill("", "Service running") : pill("warn", "Not running as a service");
    h += p.receiving ? pill("", "Printing · next token " + p.nextToken) : (p.enabled ? pill("bad", "Not receiving prints") : pill("off", "Printing off"));
    h += !s.ppSet ? pill("off", "Petpooja not set") : s.ppOK ? pill("", "Petpooja ✓") : pill("bad", "Petpooja ✕");
    h += !s.cloudSet ? pill("off", "Firebase off") : s.cloudOK ? pill("", "Firebase ✓") : pill("warn", "Firebase ✕");
    if (p.uncertain > 0) h += pill("bad", p.uncertain + " to check");
    $("pills").innerHTML = h;
    var b = $("banner"), msg = "", ok = false;
    if (p.enabled && !p.receiving && p.rxMsg) msg = p.rxMsg;
    else if (s.lastAlert && s.lastAlert.lvl === "bad" && now() - s.lastAlert.t < 5 * 60000) msg = "⚠ " + s.lastAlert.msg;
    else if (s.rollback) { msg = s.rollback; }
    b.hidden = !msg; b.textContent = msg; b.className = "banner" + (ok ? " ok" : "");
    if (s.lastAlert && s.lastAlert.lvl === "bad" && s.lastAlert.t > lastAlertSeen) {
      lastAlertSeen = s.lastAlert.t; LS.set("alertSeen", lastAlertSeen);
      if (s.sound) beep();
    }
    counts.alerts = s.alertsRecent || "";
    if (!!wasUnlocked !== !!s.unlocked) render(); else renderRail();
  }).catch(function () {
    $("pills").innerHTML = pill("bad", "Console not answering");
  });
}
setInterval(function () { $("clock").textContent = new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" }); }, 1000);
if (/[?&]w=1/.test(location.search)) {
  var ping = function () { post("/window/ping").catch(function () {}); };
  ping(); setInterval(ping, 5000);
}

// ---- Dispatch ----------------------------------------------------------------------------------
var D = { mode: LS.get("d.mode", "open"), types: LS.get("d.types", [1, 2]), sort: LS.get("d.sort", "old"), q: "", data: null, hidden: {} };
function t0(o) { var sk = (D.data && D.data.skew) || 0; return o.punched ? o.punched + sk : o.seen; }
function tcls(ms) { var m = ms / 60000; return m >= 30 ? "dr" : m >= 15 ? "r" : m >= 10 ? "y" : ""; }
function dmatch(o) {
  var q = D.q.replace(/\D/g, "");
  if (!q) return true;
  var id = String(o.orderId || "").replace(/\D/g, "");
  return (id && id.slice(-q.length) === q) || String(o.kotNo || "") === q || String(otok(o) || "") === q;
}
VIEWS.dispatch = {
  html: function () {
    var tb = [1, 2, 3].map(function (t) { return '<button class="box" data-type="' + t + '" aria-pressed="' + (D.types.indexOf(t) >= 0) + '">' + typeName(t) + "</button>"; }).join("");
    return '<div class="bar"><div class="seg" role="group" aria-label="Show"><button data-mode="open" aria-pressed="' + (D.mode === "open") + '">Preparing</button><button data-mode="all" aria-pressed="' + (D.mode === "all") + '">All orders</button></div>' +
      tb + '<input type="search" id="dq" class="grow" placeholder="Search: last 4 digits of order ID, or KOT number" value="' + esc(D.q) + '" aria-label="Search">' +
      '<button class="box" id="dsort" aria-pressed="' + (D.sort === "old") + '">Longest waiting first</button><span class="mute" id="dcount"></span></div><div id="dlist"></div>';
  },
  init: function () {
    $("main").querySelectorAll("[data-mode]").forEach(function (b) { b.onclick = function () { D.mode = b.dataset.mode; LS.set("d.mode", D.mode); render(); }; });
    $("main").querySelectorAll("[data-type]").forEach(function (b) {
      b.onclick = function () {
        var t = +b.dataset.type, i = D.types.indexOf(t);
        if (i >= 0) D.types.splice(i, 1); else D.types.push(t);
        LS.set("d.types", D.types); b.setAttribute("aria-pressed", String(i < 0)); drawDispatch();
      };
    });
    $("dsort").onclick = function () { D.sort = D.sort === "old" ? "new" : "old"; LS.set("d.sort", D.sort); this.setAttribute("aria-pressed", String(D.sort === "old")); drawDispatch(); };
    $("dq").oninput = function () { D.q = this.value; drawDispatch(); };
    $("dlist").addEventListener("click", function (e) {
      var b = e.target.closest("[data-ready]"); if (!b) return;
      var key = b.dataset.ready, card = b.closest(".dc");
      b.disabled = true; b.textContent = "Marking…";
      post("/console/api/ready", /^\d+$/.test(key) ? { kot: +key } : { job: key }).then(function (r) {
        if (r.ok) { D.hidden[key] = Date.now(); if (card) { card.classList.add("gone"); setTimeout(drawDispatch, 260); } }
        else { b.disabled = false; b.textContent = "Food ready"; alert(r.msg); }
      });
    });
  },
  load: function () { getJ("/console/api/dispatch").then(function (d) { D.data = d; drawDispatch(); }).catch(function () {}); },
  every: 2000
};
function okey(o) { return o.kot ? String(o.kot) : o.job; }
function otok(o) { return o.token || o.slot || ""; }
function dispatchList() {
  var list = (D.data && D.data.orders || []).filter(function (o) {
    if (o.status === "cancelled" && D.mode === "open") return false;
    if (D.types.length && !o.printOnly && D.types.indexOf(o.type) < 0) return false; // a print-only order has no known type: always shown
    if (D.mode === "open" && (o.status !== "preparing" || D.hidden[okey(o)])) return false;
    return dmatch(o);
  });
  list.sort(function (a, b) { return D.sort === "old" ? t0(a) - t0(b) : t0(b) - t0(a); });
  return list;
}
function drawDispatch() {
  var el = $("dlist"); if (!el || !D.data) return;
  var list = dispatchList();
  counts.dispatch = (D.data.orders || []).filter(function (o) { return o.status === "preparing" && !D.hidden[okey(o)]; }).length || "";
  renderRail();
  $("dcount").textContent = list.length + (D.mode === "open" ? " preparing" : " orders");
  if (!list.length) { el.innerHTML = '<p class="empty">' + (D.q ? "No order matches “" + esc(D.q) + "”." : D.mode === "open" ? "Nothing waiting. New orders appear here as soon as Petpooja has them." : "No orders yet today.") + "</p>"; return; }
  if (D.mode === "all") { el.innerHTML = allOrdersTable(list); return; }
  el.className = "dgrid";
  el.innerHTML = list.map(function (o) {
    var c = brandColor(o.brand, o.type), start = t0(o), items = (o.items || []).filter(function (i) { return i.s !== -9; });
    return '<article class="dc" style="--c:' + c + '"><div class="t1"><div><div class="tk">' + (otok(o) || '<span class="chip warn" title="No KOT print reached this PC for this order">not printed</span>') + '</div><div class="bd">' + esc(o.brand || typeName(o.type)) + " · " + (o.printOnly ? '<span class="chip warn" title="Petpooja can\'t be read right now: shown from the print">from print</span>' : typeName(o.type)) +
      '</div></div><span class="tm ' + tcls(now() - start) + '" data-t0="' + start + '">' + mmss(now() - start) + "</span></div>" +
      '<div class="ids"><span>Order <b title="' + esc(o.orderId) + '">' + esc(last(o.orderId, 8) || "–") + "</b></span><span>KOT <b>" + esc(o.kotNo || "–") + "</b> · punched <b>" + hm(o.punched || o.seen) + "</b></span></div>" +
      "<ul>" + items.map(function (i) { return "<li><span>" + (i.q || 1) + "×</span>" + esc(i.n) + "</li>"; }).join("") + "</ul>" +
      '<div class="ft"><button class="btn g" data-ready="' + esc(okey(o)) + '">Food ready</button></div></article>';
  }).join("");
}
function allOrdersTable(list) {
  $("dlist").className = "";
  var st = { preparing: "warn", ready: "ok", dispatched: "", cancelled: "bad" };
  return '<div class="panel tw"><table><thead><tr><th>Token</th><th>Brand</th><th>Order ID</th><th>KOT</th><th>Type</th><th>Items</th><th>Punched</th><th>Printed</th><th>Food ready</th><th>Dispatched</th><th>Delivered</th><th>Status</th></tr></thead><tbody>' +
    list.map(function (o) {
      var items = (o.items || []).filter(function (i) { return i.s !== -9; }).map(function (i) { return (i.q || 1) + "× " + i.n; }).join(", ");
      return "<tr><td>" + (otok(o) ? '<span class="tok">' + esc(otok(o)) + "</span>" : '<span class="chip warn">not printed</span>') + '</td><td><span class="dot" style="--c:' + brandColor(o.brand, o.type) + '"></span> ' + esc(o.brand || "–") +
        '</td><td class="num" title="' + esc(o.orderId) + '">' + esc(last(o.orderId, 10) || "–") + '</td><td class="num">' + esc(o.kotNo || "–") + "</td><td>" + (o.printOnly ? '<span class="chip warn">from print</span>' : typeName(o.type)) +
        '</td><td class="wrap">' + esc(items) + '</td><td class="num">' + hms(o.punched || o.seen) + '</td><td class="num">' + (o.printed ? hms(o.printed) + (o.printMs ? ' <span class="mute">' + o.printMs + " ms</span>" : "") : "–") +
        '</td><td class="num">' + (o.ready ? hms(o.ready) + ' <span class="mute">' + esc(o.readyBy || "") + "</span>" : "–") + '</td><td class="num">' + hms(o.dispatched) + '</td><td class="num">' + hms(o.delivered) +
        '</td><td><span class="chip ' + (st[o.status] || "") + '">' + esc(o.status) + "</span></td></tr>";
    }).join("") + "</tbody></table></div>";
}
setInterval(function () {
  document.querySelectorAll(".tm[data-t0]").forEach(function (e) { var ms = now() - +e.dataset.t0; e.textContent = mmss(ms); e.className = "tm " + tcls(ms); });
}, 1000);

// ---- Printers & rules --------------------------------------------------------------------------
var R = null, M = null, RP = [], RMain = "";
VIEWS.rules = {
  html: function () { return '<p class="help">Rules decide which printer gets which KOT. Each KOT gets one token; every matching rule prints, then the main printer as set below.</p><div id="rp"></div><div id="rl" class="rules"></div><div class="savebar"><button class="btn p" id="rsave">Save</button><button class="btn" id="radd">+ Add rule</button><span class="msg" id="rmsg" role="status"></span></div>'; },
  init: function () {
    Promise.all([getJ("/console/api/rules"), getJ("/console/api/menu")]).then(function (a) {
      R = a[0].options; RP = a[0].printers || []; RMain = a[0].main || ""; M = a[1];
      R.rules = R.rules || []; R.printers = R.printers || {}; R.lan = R.lan || [];
      drawPrinters(); drawRules();
    });
    $("radd").onclick = function () { R.rules.push({ id: "", name: "Rule " + (R.rules.length + 1), on: true, types: [], brands: [], itemMode: "", items: [], cats: [], part: "bill", action: "print", token: true, orderNo: true, copies: 1, printer: "", fallback: "" }); drawRules(); };
    $("rsave").onclick = function () {
      var b = this; b.disabled = true;
      post("/console/api/rules", { rules: R.rules, main: R.main, printers: R.printers, lan: R.lan, identifyWait: R.identifyWait, enabled: R.enabled, mainPrinter: RMain }).then(function (r) {
        b.disabled = false; $("rmsg").textContent = r.msg; $("rmsg").className = "msg " + (r.ok ? "ok" : "bad");
      });
    };
  }
};
function printerOptions(sel, emptyLabel) {
  var o = '<option value="">' + esc(emptyLabel) + "</option>";
  RP.forEach(function (p) { o += '<option value="' + esc(p.Name) + '"' + (p.Name === sel ? " selected" : "") + ">" + esc(p.Name.replace(/^lan:/, "")) + (p.lan ? " (network)" : "") + "</option>"; });
  return o;
}
function drawPrinters() {
  var rows = RP.map(function (p, i) {
    var fb = (R.printers[p.Name] || {}).fallback || "";
    return "<tr><td><b>" + esc(p.Name.replace(/^lan:/, "")) + '</b><div class="mute">' + esc(p.DriverName || "") + " · " + esc(p.PortName || "") + "</div></td><td>" +
      (p.Problem ? '<span class="chip bad">' + esc(p.Problem) + "</span>" : '<span class="chip ok">Ready</span>') + (p.jobs ? ' <span class="mute">' + p.jobs + " in queue</span>" : "") +
      '</td><td><select data-fb="' + i + '">' + printerOptions(fb, "No fallback") + '</select></td><td><button class="btn sm ghost" data-test="' + i + '">Test print</button></td></tr>';
  }).join("");
  $("rp").innerHTML = '<div class="panel"><div class="hd"><h2>Printers</h2><span class="sub">Fallback: used straight away when the printer is offline, out of paper or stuck.</span></div>' +
    '<div class="rrow" style="margin-bottom:10px"><span class="lbl">Main printer</span><select id="rmainp">' + printerOptions(RMain, "Choose the main printer") + '</select>' +
    '<select id="rmain"><option value="always">prints every KOT</option><option value="unmatched">prints only when no rule matched</option><option value="never">doesn\'t print</option></select>' +
    '</div><div class="rrow" style="margin-bottom:10px"><span class="lbl">This PC</span><button class="sw" id="rrx" role="switch" aria-checked="' + (R.enabled !== false) + '"></button><span class="sel">receives Petpooja prints (port 9100)</span>' +
    '<span class="lbl" style="margin-left:12px">Wait</span><select id="rwait">' + [500, 1000, 1500, 2000, 3000].map(function (v) { return '<option value="' + v + '"' + (v === R.identifyWait ? " selected" : "") + ">" + (v / 1000) + " s</option>"; }).join("") + '</select><span class="sel">for order details, then print anyway</span></div>' +
    '<div class="tw printers"><table><thead><tr><th>Printer</th><th>State</th><th>Fallback printer</th><th></th></tr></thead><tbody>' + (rows || '<tr><td colspan="4" class="mute">No printers found on this PC.</td></tr>') + "</tbody></table></div>" +
    '<div class="rrow" style="margin-top:10px"><span class="lbl">Network</span><input type="text" id="lname" placeholder="Name, e.g. Packing" style="width:160px"><input type="text" id="lip" placeholder="IP, e.g. 192.168.0.50" style="width:160px"><input type="number" id="lport" value="9100" style="width:90px" aria-label="Port"><button class="btn sm" id="ladd">Add network printer</button>' +
    R.lan.map(function (l, i) { return '<span class="chip">' + esc(l.name) + " · " + esc(l.ip) + ' <button class="btn sm ghost danger" data-ldel="' + i + '" aria-label="Remove">×</button></span>'; }).join("") + '<span class="msg" id="pmsg"></span></div></div>';
  $("rmain").value = R.main || "always";
  $("rmain").onchange = function () { R.main = this.value; };
  $("rmainp").onchange = function () { RMain = this.value; };
  $("rwait").onchange = function () { R.identifyWait = +this.value; };
  $("rrx").onclick = function () { R.enabled = !(R.enabled !== false); this.setAttribute("aria-checked", String(R.enabled)); };
  $("rp").querySelectorAll("[data-fb]").forEach(function (s) { s.onchange = function () { var p = RP[+s.dataset.fb].Name; R.printers[p] = { fallback: s.value }; }; });
  $("rp").querySelectorAll("[data-test]").forEach(function (b) {
    b.onclick = function () { $("pmsg").textContent = "Printing…"; post("/console/api/testprint", { Printer: RP[+b.dataset.test].Name }).then(function (r) { $("pmsg").textContent = r.msg; $("pmsg").className = "msg " + (r.ok ? "ok" : "bad"); }); };
  });
  $("ladd").onclick = function () {
    var n = $("lname").value.trim(), ip = $("lip").value.trim(), port = +$("lport").value || 9100;
    if (!n || !/^\d+\.\d+\.\d+\.\d+$/.test(ip)) { $("pmsg").textContent = "Give a name and an IP address."; $("pmsg").className = "msg bad"; return; }
    R.lan.push({ name: n, ip: ip, port: port }); RP.push({ Name: "lan:" + n, DriverName: "Network printer", PortName: ip + ":" + port, lan: true });
    drawPrinters(); drawRules();
  };
  $("rp").querySelectorAll("[data-ldel]").forEach(function (b) {
    b.onclick = function () { var l = R.lan.splice(+b.dataset.ldel, 1)[0]; RP = RP.filter(function (p) { return p.Name !== "lan:" + l.name; }); drawPrinters(); drawRules(); };
  });
}
function toggleIn(arr, v) { var i = arr.indexOf(v); if (i >= 0) arr.splice(i, 1); else arr.push(v); return i < 0; }
function drawRules() {
  var el = $("rl");
  if (!R.rules.length) { el.innerHTML = '<p class="empty">No rules: every KOT prints on the main printer. Add a rule to send KOTs to other printers.</p>'; return; }
  var brands = (M.brands || []).slice();
  R.rules.forEach(function (r) { (r.brands || []).forEach(function (b) { if (brands.indexOf(b) < 0) brands.push(b); }); });
  el.innerHTML = R.rules.map(function (r, i) {
    r.types = r.types || []; r.brands = r.brands || []; r.items = r.items || []; r.cats = r.cats || [];
    var types = (M.types || []).map(function (t) { return '<button class="box" data-r="' + i + '" data-t="' + t.id + '" aria-pressed="' + (r.types.indexOf(t.id) >= 0) + '">' + esc(t.name) + "</button>"; }).join("");
    var bs = brands.map(function (b) { return '<button class="box" data-r="' + i + '" data-b="' + esc(b) + '" aria-pressed="' + (r.brands.indexOf(b) >= 0) + '">' + esc(b) + "</button>"; }).join("");
    var picker = "";
    if (r.itemMode) {
      picker = '<div class="picker">' + (M.cats || []).map(function (c) {
        var on = r.cats.indexOf(c.name) >= 0, n = c.items.filter(function (it) { return r.items.indexOf(it) >= 0; }).length;
        return '<details class="cat"' + (n ? " open" : "") + '><summary><button class="box' + (r.itemMode === "none" ? " no" : "") + '" data-r="' + i + '" data-c="' + esc(c.name) + '" aria-pressed="' + on + '">' + esc(c.name) + "</button>" +
          '<span class="sel">' + c.items.length + " items" + (n ? " · " + n + " picked" : "") + '</span></summary><div class="items">' +
          c.items.map(function (it) { return '<button class="box' + (r.itemMode === "none" ? " no" : "") + '" data-r="' + i + '" data-i="' + esc(it) + '" aria-pressed="' + (on || r.items.indexOf(it) >= 0) + '"' + (on ? " disabled" : "") + ">" + esc(it) + "</button>"; }).join("") + "</div></details>";
      }).join("") + ((M.cats || []).length ? "" : '<span class="sel">The menu appears here once Petpooja is connected.</span>') + "</div>";
    }
    var what = r.action === "skip" ? "skip" : r.part === "items" ? "items" : "bill";
    return '<div class="rule' + (r.on ? "" : " off") + '"><div class="rrow"><button class="sw" role="switch" aria-label="Rule on" data-r="' + i + '" data-on aria-checked="' + !!r.on + '"></button>' +
      '<input type="text" class="rname" data-r="' + i + '" data-name value="' + esc(r.name) + '" aria-label="Rule name"><button class="btn sm ghost" data-r="' + i + '" data-up' + (i ? "" : " disabled") + ">↑</button>" +
      '<button class="btn sm ghost danger" data-r="' + i + '" data-del>Delete</button></div>' +
      '<div class="rrow"><span class="lbl">Order type</span>' + types + '<span class="sel">' + (r.types.length ? "" : "any") + "</span></div>" +
      '<div class="rrow"><span class="lbl">Brand</span>' + bs + '<span class="sel">' + (r.brands.length ? "" : "any") + "</span></div>" +
      '<div class="rrow" style="align-items:flex-start"><span class="lbl" style="padding-top:8px">Items</span><select data-r="' + i + '" data-mode>' +
      [["", "Any items"], ["any", "Has any of"], ["none", "Has none of"], ["only", "Only these"]].map(function (m) { return '<option value="' + m[0] + '"' + (r.itemMode === m[0] ? " selected" : "") + ">" + m[1] + "</option>"; }).join("") + "</select>" + picker + "</div>" +
      '<div class="rrow"><span class="lbl">Print</span><select data-r="' + i + '" data-what><option value="bill"' + (what === "bill" ? " selected" : "") + '>Whole KOT</option><option value="items"' + (what === "items" ? " selected" : "") + '>Only the matching items</option><option value="skip"' + (what === "skip" ? " selected" : "") + ">Don't print (not even on the main printer)</option></select>" +
      (what === "skip" ? "" : '<label class="sel"><input type="checkbox" data-r="' + i + '" data-token' + (r.token ? " checked" : "") + '> Token</label><label class="sel"><input type="checkbox" data-r="' + i + '" data-orderno' + (r.orderNo ? " checked" : "") + '> Order no.</label>' +
        '<label class="sel">Copies <input type="number" min="1" max="5" value="' + (r.copies || 1) + '" data-r="' + i + '" data-copies style="width:58px"></label>' +
        '<select data-r="' + i + '" data-printer>' + printerOptions(r.printer, "Choose printer") + '</select><select data-r="' + i + '" data-fallback>' + printerOptions(r.fallback, "Printer's own fallback") + "</select>") +
      "</div></div>";
  }).join("");
}
document.addEventListener("click", function (e) {
  if (tab !== "rules" || !R) return;
  var b = e.target.closest("[data-r]"); if (!b || b.tagName === "SELECT" || b.tagName === "INPUT") return;
  var r = R.rules[+b.dataset.r]; if (!r) return;
  if ("t" in b.dataset) { toggleIn(r.types, +b.dataset.t); drawRules(); }
  else if ("b" in b.dataset) { toggleIn(r.brands, b.dataset.b); drawRules(); }
  else if ("c" in b.dataset) { e.preventDefault(); toggleIn(r.cats, b.dataset.c); drawRules(); }
  else if ("i" in b.dataset) { toggleIn(r.items, b.dataset.i); drawRules(); }
  else if ("on" in b.dataset) { r.on = !r.on; drawRules(); }
  else if ("del" in b.dataset) { if (confirm("Delete the rule “" + r.name + "”?")) { R.rules.splice(+b.dataset.r, 1); drawRules(); } }
  else if ("up" in b.dataset) { var i = +b.dataset.r; if (i > 0) { R.rules.splice(i - 1, 0, R.rules.splice(i, 1)[0]); drawRules(); } }
});
document.addEventListener("change", function (e) {
  if (tab !== "rules" || !R) return;
  var t = e.target, r = R.rules[+t.dataset.r]; if (!r) return;
  if ("mode" in t.dataset) { r.itemMode = t.value; drawRules(); }
  else if ("what" in t.dataset) { r.action = t.value === "skip" ? "skip" : "print"; r.part = t.value === "items" ? "items" : "bill"; drawRules(); }
  else if ("token" in t.dataset) r.token = t.checked;
  else if ("orderno" in t.dataset) r.orderNo = t.checked;
  else if ("copies" in t.dataset) r.copies = Math.max(1, Math.min(5, +t.value || 1));
  else if ("printer" in t.dataset) r.printer = t.value;
  else if ("fallback" in t.dataset) r.fallback = t.value;
});
document.addEventListener("input", function (e) {
  if (tab !== "rules" || !R) return;
  var t = e.target; if ("name" in t.dataset && R.rules[+t.dataset.r]) R.rules[+t.dataset.r].name = t.value;
});

// ---- Print timing ------------------------------------------------------------------------------
VIEWS.timing = {
  html: function () { return '<div class="stats" id="tst"></div><div class="panel"><div class="hd"><h2>Prints</h2><span class="sub">From the KOT reaching this PC to the printer queue · latest 200</span></div><div class="tw" id="ttab"></div></div>'; },
  load: function () {
    getJ("/console/api/jobs").then(function (d) {
      var jobs = d.jobs || [], ms = jobs.filter(function (j) { return j.printMs > 0; }).map(function (j) { return j.printMs; });
      var fast = ms.slice(0, 50).filter(function (v) { return v <= 300; }).length, by = {};
      jobs.forEach(function (j) { by[j.identBy || "–"] = (by[j.identBy || "–"] || 0) + 1; });
      var sorted = ms.slice().sort(function (a, b) { return a - b; }), med = sorted.length ? sorted[Math.floor(sorted.length / 2)] : 0;
      $("tst").innerHTML = '<div class="stat good"><div class="l">KOT → printer</div><div class="v">' + (med ? med + " ms" : "–") + '</div><div class="s">median</div></div>' +
        '<div class="stat"><div class="l">Under 0.3 s</div><div class="v">' + (ms.length ? Math.round(fast * 100 / Math.min(50, ms.length)) + "%" : "–") + '</div><div class="s">last 50 prints</div></div>' +
        '<div class="stat"><div class="l">Order found by</div><div class="v" style="font-size:16px">' + Object.keys(by).map(function (k) { return esc(k) + " " + by[k]; }).join(" · ") + '</div><div class="s">Petpooja list / OCR / slip text</div></div>' +
        '<div class="stat"><div class="l">Next token</div><div class="v">' + esc(d.print.nextToken) + '</div><div class="s">cycle 1–' + esc(d.print.tokenLimit) + "</div></div>";
      $("ttab").innerHTML = jobs.length ? "<table><thead><tr><th>Received</th><th>Token</th><th>Brand</th><th>Order ID</th><th>KOT</th><th>Found by</th><th>Order found</th><th>Printed</th><th>Slips</th><th>State</th></tr></thead><tbody>" +
        jobs.map(function (j) {
          var m = j.metadata || {}, outs = (j.outs || []).map(function (o) {
            return '<span class="chip ' + (o.ok ? (o.fallback ? "warn" : "ok") : "bad") + '" title="' + esc(o.error || "") + '">' + esc(o.rule) + " → " + esc(String(o.printer).replace(/^lan:@?/, "")) + (o.copies > 1 ? " ×" + o.copies : "") + (o.fallback ? " (fallback)" : "") + "</span>";
          }).join(" ");
          return '<tr><td class="num">' + hms(j.recv || Date.parse(j.createdAt)) + '</td><td><span class="tok">' + j.token + "</span></td><td>" + esc(m.brand || "–") + '</td><td class="num" title="' + esc(m.orderId) + '">' + esc(last(m.orderId, 10) || "–") +
            '</td><td class="num">' + esc(j.kotNo || "–") + "</td><td>" + esc(j.identBy || "–") + '</td><td class="num">' + (j.identMs ? j.identMs + " ms" : "–") + '</td><td class="num">' + (j.printMs ? "<b>" + j.printMs + " ms</b>" : "–") +
            '</td><td class="wrap">' + (outs || '<span class="mute">' + esc(j.destination || "") + "</span>") + '</td><td><span class="chip ' + ({ sent: "ok", uncertain: "bad", queued: "warn", reading: "warn", sending: "warn" }[j.status] || "") + '" title="' + esc(j.error || "") + '">' + esc(j.status) + "</span></td></tr>";
        }).join("") + "</tbody></table>" : '<p class="empty">No prints yet. They appear here as Petpooja sends KOTs to this PC.</p>';
    }).catch(function () {});
  },
  every: 3000
};

// ---- Alerts ------------------------------------------------------------------------------------
VIEWS.alerts = {
  html: function () { return '<div class="panel"><div class="hd"><h2>Alerts</h2><span class="sub">Printer problems, fallback prints and prints that need checking</span></div><div id="al"></div></div>'; },
  load: function () {
    getJ("/console/api/alerts").then(function (d) {
      var a = d.alerts || [];
      $("al").innerHTML = a.length ? '<div class="tw"><table><thead><tr><th>Time</th><th>Printer</th><th>What happened</th></tr></thead><tbody>' + a.map(function (x) {
        return '<tr><td class="num">' + hms(x.t) + "</td><td>" + esc(String(x.printer || "–").replace(/^lan:@?/, "")) + '</td><td class="wrap"><span class="chip ' + (x.lvl === "bad" ? "bad" : x.lvl) + '">' + (x.lvl === "bad" ? "!" : "i") + "</span> " + esc(x.msg) + "</td></tr>";
      }).join("") + "</tbody></table></div>" : '<p class="empty">No alerts. Printer problems show here, with a sound and a red line at the top.</p>';
    }).catch(function () {});
  },
  every: 3000
};

// ---- Apps --------------------------------------------------------------------------------------
VIEWS.apps = {
  html: function () { return '<p class="help">Phones, tablets and Android TVs on the shop Wi-Fi can install these from this PC. Scan the code with the phone camera.</p><div class="apps" id="apl"></div>'; },
  load: function () {
    getJ("/console/api/apps").then(function (d) {
      var link = (d.links || [])[0] || "";
      $("apl").innerHTML = '<div class="panel"><h2>Download page</h2><p class="help">Open on the phone or TV:</p><p><b>' + esc(link || "No network address") + '</b></p><div class="qr" id="qrall"></div></div>' +
        (d.apps || []).map(function (a) {
          return '<div class="panel"><h2>' + esc(a.Name) + '</h2><p class="help">' + esc(a.What) + "</p>" + (a.Ready ? '<p><span class="chip ok">Build ' + a.Build + " · " + esc(a.Size) + "</span></p>" : '<p><span class="chip warn">' + esc(a.State) + "</span></p>") + "</div>";
        }).join("");
      if (link && window.QRCode) new QRCode($("qrall"), { text: link, width: 160, height: 160, correctLevel: QRCode.CorrectLevel.M });
    }).catch(function () {});
  }
};

// ---- Hub: This PC & updates ---------------------------------------------------------------------
VIEWS.pc = {
  html: function () {
    return '<div class="panel"><div class="hd"><h2>Window on this PC</h2></div><div class="rrow"><button class="sw" id="wkeep" role="switch"></button><span>Keep the Console window open (reopens within ~20 s if closed by mistake)</span></div>' +
      '<div class="rrow" style="margin-top:8px"><button class="sw" id="wsound" role="switch"></button><span>Sound on printer alerts</span></div><p class="msg" id="wmsg"></p></div>' +
      '<iframe class="frame" style="min-height:900px;border:1px solid var(--line);border-radius:12px" src="/hub?show=pc" title="This PC"></iframe>';
  },
  init: function () {
    var k = $("wkeep"), s = $("wsound");
    k.setAttribute("aria-checked", String(!S || S.keepWindow)); s.setAttribute("aria-checked", String(!S || S.sound));
    function save(b) {
      post("/console/api/window", b).then(function (r) { $("wmsg").textContent = r.msg; $("wmsg").className = "msg " + (r.ok ? "ok" : "bad"); loadState(); });
    }
    k.onclick = function () { var v = k.getAttribute("aria-checked") !== "true"; k.setAttribute("aria-checked", String(v)); save({ keep: v }); };
    s.onclick = function () { var v = s.getAttribute("aria-checked") !== "true"; s.setAttribute("aria-checked", String(v)); save({ sound: v }); };
  }
};

// ---- Data -----------------------------------------------------------------------------------------
var metricsDay = "";
VIEWS.metrics = {
  html: function () { return '<div class="bar"><h2>Metrics</h2><select id="mday" aria-label="Day"></select><span class="mute">From this PC\'s order records: punched → food ready → dispatched</span></div><div class="stats" id="mst"></div><div class="panel"><div class="hd"><h2>Dosa prep time by hour</h2><span class="sub">median minutes, punched → food ready · amber 8+, red 10+</span></div><div id="mch"></div></div><div class="panel"><div class="hd"><h2>By brand</h2></div><div class="tw" id="mbr"></div></div>'; },
  load: function () {
    getJ("/console/api/metrics" + (metricsDay ? "?day=" + metricsDay : "")).then(function (d) {
      var sel = $("mday"); if (!sel) return;
      var days = d.days && d.days.length ? d.days : [d.day];
      sel.innerHTML = days.map(function (x) { return '<option value="' + x + '"' + (x === d.day ? " selected" : "") + ">" + x + "</option>"; }).join("");
      sel.onchange = function () { metricsDay = this.value; VIEWS.metrics.load(); };
      $("mst").innerHTML = '<div class="stat"><div class="l">Prep time</div><div class="v">' + (d.prepMin ? d.prepMin.toFixed(1) + " min" : "–") + '</div><div class="s">punched → food ready (median)</div></div>' +
        '<div class="stat"><div class="l">Ready → dispatch</div><div class="v">' + (d.waitMin ? d.waitMin.toFixed(1) + " min" : "–") + '</div><div class="s">rider wait (median)</div></div>' +
        '<div class="stat good"><div class="l">KOT → printer</div><div class="v">' + (d.printMs ? Math.round(d.printMs) + " ms" : "–") + '</div><div class="s">median</div></div>' +
        '<div class="stat"><div class="l">Orders</div><div class="v">' + d.orders + '</div><div class="s">' + d.dosaN + " with dosa</div></div>";
      var hs = d.hours || [];
      if (!hs.length) { $("mch").innerHTML = '<p class="empty">No dosa orders with a ready time on this day yet.</p>'; }
      else {
        var W = 760, H = 230, pad = 30, max = Math.max(12, Math.ceil(Math.max.apply(null, hs.map(function (h) { return h.min; })) / 2) * 2), bw = Math.min((W - pad * 2) / hs.length, 70), x0 = pad + ((W - pad * 2) - bw * hs.length) / 2;
        var svg = '<svg class="chart" viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="Dosa prep time by hour">';
        [0, max / 2, max].forEach(function (v) { var y = H - pad - (v / max) * (H - pad * 2); svg += '<line x1="' + pad + '" x2="' + (W - pad) + '" y1="' + y + '" y2="' + y + '" stroke="#e2e7ed"/><text x="' + (pad - 6) + '" y="' + (y + 4) + '" text-anchor="end">' + v + "</text>"; });
        hs.forEach(function (h, i) {
          var bh = (h.min / max) * (H - pad * 2), x = x0 + i * bw + bw * 0.18, y = H - pad - bh, c = h.min >= 10 ? "#dc2626" : h.min >= 8 ? "#d97706" : "#15803d";
          svg += '<rect x="' + x + '" y="' + y + '" width="' + bw * 0.64 + '" height="' + bh + '" rx="3" fill="' + c + '"><title>' + h.h + ":00 · " + h.min.toFixed(1) + " min · " + h.n + " orders</title></rect>" +
            '<text x="' + (x + bw * 0.32) + '" y="' + (H - pad + 14) + '" text-anchor="middle">' + h.h + "</text>";
        });
        $("mch").innerHTML = svg + "</svg>";
      }
      var rows = d.brands || [];
      $("mbr").innerHTML = rows.length ? "<table><thead><tr><th>Brand</th><th>Orders</th><th>Punched → ready</th><th>Ready → dispatch</th><th>Over 15 min</th><th>KOT → printer</th></tr></thead><tbody>" + rows.map(function (r) {
        return '<tr><td><span class="dot" style="--c:' + brandColor(r.brand) + '"></span> ' + esc(r.brand) + '</td><td class="num">' + r.orders + '</td><td class="num">' + (r.prepMin ? r.prepMin.toFixed(1) + " min" : "–") + '</td><td class="num">' + (r.readyMin ? r.readyMin.toFixed(1) + " min" : "–") + '</td><td class="num">' + r.over15 + '</td><td class="num">' + (r.printMs ? r.printMs + " ms" : "–") + "</td></tr>";
      }).join("") + "</tbody></table>" : '<p class="empty">No orders recorded on this day.</p>';
    }).catch(function () {});
  },
  every: 30000
};
VIEWS.sources = {
  html: function () { return '<div class="panel"><div class="hd"><h2>Connections</h2><span class="sub">Where the data comes from and goes to</span></div><div id="srcl"></div></div>'; },
  load: function () {
    getJ("/console/api/sources").then(function (d) {
      $("srcl").innerHTML = (d.sources || []).map(function (s) {
        return '<div class="src"><i class="' + (s.ok ? "" : "no") + '"></i><b>' + esc(s.name) + '</b><span class="chip ' + (s.ok ? "ok" : "") + '">' + esc(s.state) + '</span><div class="d">' + esc(s.what) + (s.detail ? " · " + esc(s.detail) : "") + "</div></div>";
      }).join("");
    }).catch(function () {});
  },
  every: 5000
};
VIEWS.planner = {
  html: function () { return '<div class="panel"><div class="hd"><h2>Indent planner</h2></div><p class="help">This PC sends each day\'s sales by hour for the planner. Open the planner to plan tomorrow\'s indent.</p><p id="plst" class="msg"></p><p><a class="btn p" href="https://babaidosa.netlify.app/indent/" target="_blank" rel="noopener">Open the indent planner</a></p></div>'; },
  load: function () {
    getJ("/console/api/sources").then(function (d) {
      (d.sources || []).forEach(function (s) { if (s.name === "Indent planner") { $("plst").textContent = "Planner: " + s.state; $("plst").className = "msg " + (s.ok ? "ok" : ""); } });
    }).catch(function () {});
  }
};

loadState().then(render);
setInterval(loadState, 3000);
window.addEventListener("hashchange", function () {
  var h = location.hash.replace("#", "");
  for (var a in AREAS) AREAS[a].items.forEach(function (it) { if (it[0] === h) { area = a; tab = h; } });
  render();
});
