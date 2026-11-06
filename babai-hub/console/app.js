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
  bridge: { label: "Bridge Print", items: [["overview", "Overview"], ["dispatch", "Dispatch"], ["kotprint", "KOT printing"], ["rules", "Printers & rules"], ["timing", "Print timing"], ["alerts", "Alerts"], ["apps", "Apps"], ["screens", "Screens"], ["barker", "Dosa barker"]] },
  hub: { label: "Hub", items: [["status", "Status"], ["petpooja", "Petpooja & Firebase"], ["scanners", "Scanners & rider"], ["pcscan", "PC scanner"], ["customers", "Customers' page"], ["pc", "This PC & updates"]] },
  metrics: { label: "Metrics", items: [["cx", "CX metrics"], ["ops", "Ops metrics"]], open: true },
  data: { label: "Data", items: [["metrics", "Metrics"], ["prep", "Prep Live"], ["planner", "Indent planner"], ["cxsheet", "Ratings sheet"], ["sources", "Connections"]] }
};
var HUBFRAME = { status: "now,qr", petpooja: "pp,fb,raw", scanners: "scanners", customers: "qr,dishes", prep: "prep" };
var S = null, offset = 0, area = LS.get("area", "bridge"), tab = null, loop = null, counts = {};
if (!AREAS[area]) area = "bridge";
tab = area === "metrics" ? "cx" : LS.get("tab." + area, AREAS[area].items[0][0]);
(function fromHash() {
  var h = location.hash.replace("#", "");
  for (var a in AREAS) AREAS[a].items.forEach(function (it) { if (it[0] === h) { area = a; tab = h; } });
})();

function now() { return Date.now() + offset; }

// ---- frame: top bar, tabs, rail ---------------------------------------------------------------
function renderRail() {
  var open = LS.get("railOpen", true);
  var html = '<button class="arrow" id="railArrow" aria-expanded="' + open + '" title="' + (open ? "Hide sections" : "Show sections") + '" aria-label="Sections">▼</button>';
  AREAS[area].items.forEach(function (it) {
    if (!open && it[0] !== tab) return; // closed: only the open section
    var k = counts[it[0]];
    html += '<button class="tab" role="tab" data-tab="' + it[0] + '" aria-selected="' + (it[0] === tab) + '">' + esc(it[1]) +
      (k ? '<span class="k' + (it[0] === "alerts" ? " alert" : "") + '">' + k + "</span>" : "") + "</button>";
  });
  $("rail").innerHTML = html;
  document.querySelectorAll(".mt").forEach(function (b) { b.setAttribute("aria-selected", String(b.dataset.area === area)); });
}
$("rail").addEventListener("click", function (e) {
  if (e.target.closest("#railArrow")) { LS.set("railOpen", !LS.get("railOpen", true)); renderRail(); return; }
  var b = e.target.closest(".tab"); if (!b) return;
  if (!LS.get("railOpen", true) && b.dataset.tab === tab) { LS.set("railOpen", true); renderRail(); return; }
  tab = b.dataset.tab; LS.set("tab." + area, tab); render();
});
document.querySelector(".mtabs").addEventListener("click", function (e) {
  var b = e.target.closest(".mt"); if (!b) return;
  area = b.dataset.area; LS.set("area", area);
  tab = LS.get("tab." + area, AREAS[area].items[0][0]);
  if (area === "metrics") { tab = "cx"; if (window.MetricsReset) MetricsReset(); } // Metrics always opens on CX, Today · All · All day
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
  if (area !== "bridge" && !AREAS[area].open && !(S && S.unlocked)) { pinView(); return; }
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
var D = { mode: LS.get("d.mode", "open"), types: LS.get("d.types3", [1, 2, 3]), sort: LS.get("d.sort", "old"), q: "", data: null, hidden: {} };
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
    var tb = '<span class="types" role="group" aria-label="Order type">' + [1, 2, 3].map(function (t) { return '<button class="box sm" data-type="' + t + '" aria-pressed="' + (D.types.indexOf(t) >= 0) + '">' + typeName(t) + ' <b data-tc="' + t + '"></b></button>'; }).join("") + "</span>";
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
        LS.set("d.types3", D.types); b.setAttribute("aria-pressed", String(i < 0)); drawDispatch();
      };
    });
    $("dsort").onclick = function () { D.sort = D.sort === "old" ? "new" : "old"; LS.set("d.sort", D.sort); this.setAttribute("aria-pressed", String(D.sort === "old")); drawDispatch(); };
    $("dq").oninput = function () { D.q = this.value; findSoon(); drawDispatch(); };
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
  load: function () {
    getJ("/console/api/dispatch").then(function (d) { D.data = d; drawDispatch(); }).catch(function () {});
    if (searching()) findNow();
  },
  every: 2000
};
// Search: 3+ digits look through every order of the last 3 days, whatever the filters show.
var findT = null;
function searching() { return D.q.replace(/\D/g, "").length >= 3; }
function findSoon() { clearTimeout(findT); D.found = null; if (searching()) findT = setTimeout(findNow, 250); }
function findNow() {
  var q = D.q.replace(/\D/g, "");
  getJ("/console/api/find?q=" + encodeURIComponent(q)).then(function (r) {
    if (r.q !== D.q.replace(/\D/g, "")) return; // typed on meanwhile
    D.found = r; drawDispatch();
  }).catch(function () {});
}
function detailCard(o, sk) {
  var c = brandColor(o.brand, o.type), items = (o.items || []).filter(function (i) { return i.s !== -9; });
  var st = { preparing: ["warn", "Preparing"], ready: ["ok", "Food ready"], dispatched: ["ok", "Picked up"], cancelled: ["bad", "Cancelled"] }[o.status] || ["", o.status];
  var p = o.punched ? o.punched + sk : o.seen, d0 = new Date(p), today = new Date(now());
  var day = d0.toDateString() === today.toDateString() ? "Today" : d0.toDateString() === new Date(now() - 864e5).toDateString() ? "Yesterday" : d0.toLocaleDateString();
  function step(label, t) { return "<div>" + esc(label) + "<b>" + (t ? hm(t) : "–") + "</b></div>"; }
  function mins(a, b) { return a && b && b > a ? Math.round((b - a) / 60000) + " min" : "–"; }
  return '<article class="det" style="--c:' + c + '"><div class="l"><div class="tk">' + (otok(o) || '<span class="chip warn">not printed</span>') + '</div><div class="bd">' + esc(o.brand || typeName(o.type)) + " · " + typeName(o.type) + "</div>" +
    '<div class="ids">' + idHtml(o) + "<span>KOT <b>" + esc(o.kotNo || "–") + "</b></span></div>" +
    "<ul>" + items.map(function (i) {
      return "<li>" + (i.q || 1) + "× " + esc(i.n) + (i.a || []).map(function (a) { return '<i class="ad">+ ' + (a.q || 1) + " × " + esc(a.n) + "</i>"; }).join("") + (i.nt ? '<i class="nt">Note: ' + esc(i.nt) + "</i>" : "") + "</li>";
    }).join("") + "</ul>" + (o.note ? '<div class="onote"><b>Note</b>' + esc(o.note) + "</div>" : "") + "</div>" +
    '<div class="r"><div class="rh"><b>' + esc(day) + '</b><span class="chip ' + st[0] + '">' + esc(st[1]) + "</span></div>" +
    '<div class="tl">' + step("Punched", p) + step("Printed" + (otok(o) ? " · slot " + otok(o) : ""), o.printed) + step("Food ready", o.ready) + step("Picked up", o.dispatched) + step("Delivered", o.delivered) + "</div>" +
    '<div class="kv"><span>Prep time <b>' + mins(p, o.ready) + "</b></span><span>Rider wait <b>" + mins(o.ready, o.dispatched) + "</b></span>" + (o.readyBy ? "<span>Ready by <b>" + esc(o.readyBy) + "</b></span>" : "") + (o.orderId ? "<span>Full ID <b>" + esc(o.orderId) + "</b></span>" : "") + "</div></div></article>";
}
// "Order …0453 [1673]": the last 4 digits, the ones riders say, in a neon box of the brand's colour
function idHtml(o) {
  var d = String(o.orderId || "").replace(/\D/g, "");
  if (d.length < 5) return "<span>Order <b>" + esc(o.orderId || "–") + "</b></span>";
  return '<span title="' + esc(o.orderId) + '">Order …' + esc(d.slice(-8, -4)) + '<b class="l4">' + esc(d.slice(-4)) + "</b></span>";
}
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
  if (searching()) {
    var f = D.found, n = f ? f.orders.length : 0;
    $("dcount").textContent = f ? n + " found" : "searching…";
    el.className = "dfound";
    el.innerHTML = '<p class="hit">Searching every order of the last 3 days, whatever filter is selected · clear the box to go back</p>' +
      (!f ? "" : n ? f.orders.map(function (o) { return detailCard(o, f.skew || 0); }).join("") : '<p class="empty">No order of the last 3 days ends with “' + esc(D.q.replace(/\D/g, "")) + "”, and no KOT or slot has that number.</p>");
    return;
  }
  var list = dispatchList();
  counts.dispatch = (D.data.orders || []).filter(function (o) { return o.status === "preparing" && !D.hidden[okey(o)]; }).length || "";
  renderRail();
  $("dcount").textContent = list.length + (D.mode === "open" ? " preparing" : " orders");
  [1, 2, 3].forEach(function (t) { // how many are preparing per order type
    var n = (D.data.orders || []).filter(function (o) { return o.type === t && o.status === "preparing" && !D.hidden[okey(o)]; }).length, e = document.querySelector('[data-tc="' + t + '"]');
    if (e) e.textContent = n || "";
  });
  if (!list.length) { el.innerHTML = '<p class="empty">' + (D.q ? "No order matches “" + esc(D.q) + "”." : D.mode === "open" ? "Nothing waiting. New orders appear here as soon as Petpooja has them." : "No orders yet today.") + "</p>"; return; }
  if (D.mode === "all") { el.innerHTML = allOrdersTable(list); return; }
  el.className = "dgrid";
  el.innerHTML = list.map(function (o) {
    var c = brandColor(o.brand, o.type), start = t0(o), items = (o.items || []).filter(function (i) { return i.s !== -9; });
    return '<article class="dc" style="--c:' + c + '"><div class="t1"><div><div class="tk">' + (otok(o) || '<span class="chip warn" title="No KOT print reached this PC for this order">not printed</span>') + '</div><div class="bd">' + esc(o.brand || typeName(o.type)) + " · " + (o.printOnly ? '<span class="chip warn" title="Petpooja can\'t be read right now: shown from the print">from print</span>' : typeName(o.type)) +
      '</div></div><span class="tm ' + tcls(now() - start) + '" data-t0="' + start + '">' + mmss(now() - start) + "</span></div>" +
      '<div class="ids">' + idHtml(o) + "<span>KOT <b>" + esc(o.kotNo || "–") + "</b> · punched <b>" + hm(o.punched || o.seen) + "</b></span></div>" +
      "<ul>" + items.map(function (i) {
        return "<li><span>" + (i.q || 1) + "×</span>" + esc(i.n) +
          (i.a || []).map(function (a) { return '<i class="ad" title="' + esc(a.g || "") + '">+ ' + (a.q || 1) + " × " + esc(a.n) + "</i>"; }).join("") +
          (i.nt ? '<i class="nt">Note: ' + esc(i.nt) + "</i>" : "") + "</li>";
      }).join("") + "</ul>" +
      (o.note ? '<div class="onote"><b>Note</b>' + esc(o.note) + "</div>" : "") +
      '<div class="ft"><button class="btn g" data-ready="' + esc(okey(o)) + '">Food ready</button></div></article>';
  }).join("");
}
function allOrdersTable(list) {
  $("dlist").className = "";
  var st = { preparing: "warn", ready: "ok", dispatched: "", cancelled: "bad" };
  return '<div class="panel tw"><table><thead><tr><th>Token</th><th>Brand</th><th>Order ID</th><th>KOT</th><th>Type</th><th>Items</th><th>Punched</th><th>Printed</th><th>Food ready</th><th>Dispatched</th><th>Delivered</th><th>Status</th></tr></thead><tbody>' +
    list.map(function (o) {
      var items = (o.items || []).filter(function (i) { return i.s !== -9; }).map(function (i) {
        return (i.q || 1) + "× " + i.n + (i.a || []).map(function (a) { return " + " + (a.q || 1) + "× " + a.n; }).join("") + (i.nt ? " (" + i.nt + ")" : "");
      }).join(", ") + (o.note ? " · Note: " + o.note : "");
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
// "Add your own": a category or dish Petpooja hasn't sent yet (a new item); picks the menu doesn't list show here too.
function ownPicks(r, i) {
  var cats = {}, items = {}, no = r.itemMode === "none" ? " no" : "";
  (M.cats || []).forEach(function (c) { cats[c.name.toLowerCase()] = 1; c.items.forEach(function (it) { items[it.toLowerCase()] = 1; }); });
  var own = r.cats.filter(function (c) { return !cats[c.toLowerCase()]; }).map(function (c) { return '<button class="box' + no + '" data-r="' + i + '" data-c="' + esc(c) + '" aria-pressed="true" title="Category">' + esc(c) + " (category)</button>"; })
    .concat(r.items.filter(function (it) { return !items[it.toLowerCase()]; }).map(function (it) { return '<button class="box' + no + '" data-r="' + i + '" data-i="' + esc(it) + '" aria-pressed="true" title="Item">' + esc(it) + "</button>"; }));
  return '<div class="own">' + (own.length ? '<span class="sel">Your own:</span>' + own.join("") : "") +
    '<input type="text" data-r="' + i + '" data-own placeholder="Add your own: a category or item name, exactly as in Petpooja" aria-label="Add your own category or item">' +
    '<button class="btn sm" data-r="' + i + '" data-addc>Add as category</button><button class="btn sm" data-r="' + i + '" data-addi>Add as item</button></div>';
}
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
      }).join("") + ((M.cats || []).length ? "" : '<span class="sel">The menu appears here once Petpooja is connected.</span>') + ownPicks(r, i) + "</div>";
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
  else if ("addc" in b.dataset || "addi" in b.dataset) {
    var inp = b.parentNode.querySelector("[data-own]"), v = inp ? inp.value.trim().replace(/\s+/g, " ") : "";
    if (!v) { if (inp) inp.focus(); return; }
    var arr = "addc" in b.dataset ? r.cats : r.items;
    if (!arr.some(function (x) { return x.toLowerCase() === v.toLowerCase(); })) arr.push(v);
    drawRules();
  }
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

// ---- Bridge: KOT printing by the Console (direct.go) -------------------------------------------------
var KP = null, KPmsg = null, KPhelp = false;
VIEWS.kotprint = {
  html: function () { return '<div id="kp"><p class="help">Loading…</p></div>'; },
  load: function () {
    getJ("/console/api/direct").then(function (d) {
      KP = d; var c = KP.cfg;
      c.lines = c.lines || []; c.subs = c.subs || []; c.mainBackups = c.mainBackups || []; c.types = c.types || []; c.mainSkip = c.mainSkip || [];
      c.subs.forEach(function (x) { x.cats = x.cats || []; x.items = x.items || []; x.backups = x.backups || []; x.skip = x.skip || []; });
      c.lines.forEach(function (x) { x.values = x.values || []; x.backups = x.backups || []; x.skip = x.skip || []; });
      kpDraw();
    }).catch(function () {});
  }
};
var KP_OT = [["D", "Delivery"], ["P", "Pick up"], ["I", "Dine in"], ["S", "Self service"]];
var KP_HELP = [
  ["1 · Choose who prints the KOTs", ["<b>Bridge Print only</b>: as before; Petpooja's slip prints through Bridge Print with the slot.", "<b>Console only</b>: only the Console's new slip prints; Petpooja's copy is not printed.", "<b>Console + Bridge backup</b> (start with this): the Console prints; if its slip doesn't go out in 10 seconds, Petpooja's slip prints instead, with the same slot."]],
  ["2 · Which orders the Console prints", ["<b>Delivery / Online</b> is always on. Tap <b>Pick up</b>, <b>Dine in</b>, <b>Self service</b> to add them, or <b>All</b>.", "Order types left off keep printing through Bridge Print."]],
  ["3 · Main and sub main printers (the whole bill with the slot)", ["Pick the <b>Main</b> printer: it prints <b>all other bills</b>.", "Press <b>+ Add sub main printer</b>, add categories or items (e.g. Dosa) and pick its printer: any bill that contains one of them prints <b>whole</b> on the sub main instead.", "Sub mains are checked top to bottom; a bill goes to the first one it matches. Each bill prints once.", "<b>Print the whole order</b> (on the main): off = the bill slip leaves out what a category / item printer already prints."]],
  ["4 · Category & item printers (a station's part, no slot)", ["<b>+ Add line</b>, choose <b>Category</b> or <b>Item</b>, add the names from the list (your Petpooja menu), pick the printer.", "That printer gets only those dishes, under the station name (e.g. DOSA), without the slot, with “Also on this order”.", "A dish named in an item line goes there, not to a category line."]],
  ["5 · “Prints for” buttons", ["Every printer has Delivery · Pick up · Dine in · Self service. Tap one to turn it off: that printer then skips those orders.", "A new printer starts with all on."]],
  ["6 · Backup printers (up to 3)", ["<b>+ Backup</b> adds one. They are tried in order when the printer is offline, out of paper or stuck; Alerts shows each time."]],
  ["7 · On / Off switch", ["Off pauses a printer and keeps its setup.", "A sub main that is off: its bills go to the Main. A line that is off: its dishes stay only on the bill slip.", "The Main off: bills no sub main takes print through Bridge Print, so nothing is lost."]],
  ["8 · Test print, paper size and cutting", ["<b>Test print</b> sends a sample slip. 80 or 58 mm is found from the printer's name; the printer cuts after each slip (printers without a cutter leave it to tear)."]],
  ["9 · What the slip shows", ["Bill slip (main / sub main): the big SLOT (online) or TOKEN, date and KOT, brand, order ID with the <b>last 4 big and bold</b>, DELIVERY / PICK UP / DINE IN, items with <b>add-ons in thin boxes</b> and notes, total, customer note, barcode “Scan to mark Food ready”.", "Station slip: the same without the slot, under the station name.", "A cancelled order prints a CANCELLED slip on the same printers."]],
  ["10 · If something goes wrong", ["<b>A printer is offline</b>: its backup prints; see Alerts.", "<b>Nothing prints</b>: is the Console reading Petpooja (top bar)? With Console + Bridge backup, Petpooja's slip prints anyway.", "<b>Two slips for one order</b>: a reprint from Petpooja (2 minutes or more later) prints on purpose."]]
];
function kpSel(val, cls, attr) {
  var o = '<option value="">Choose a printer</option>' + (KP.printers || []).map(function (p) { return '<option value="' + esc(p.name) + '"' + (p.name === val ? " selected" : "") + ">" + esc(p.label) + "</option>"; }).join("");
  if (val && !(KP.printers || []).some(function (p) { return p.name === val; })) o += '<option value="' + esc(val) + '" selected>' + esc(val) + " (not found)</option>";
  return '<select class="kp-sel ' + (cls || "") + '" ' + (attr || "") + ">" + o + "</select>";
}
function kpState(name) {
  var p = (KP.printers || []).filter(function (x) { return x.name === name; })[0];
  if (!name) return "";
  if (!p) return '<span class="kp-st bad">● not found</span>';
  return p.problem ? '<span class="kp-st bad">● ' + esc(p.problem) + "</span>" : '<span class="kp-st ok">● Ready · ' + p.mm + " mm · auto cut</span>";
}
// the printer objects by key: "m" main, "s0" sub main, "l0" line
function kpObj(k) {
  var c = KP.cfg;
  if (k === "m") return { get off() { return c.mainOff; }, set off(v) { c.mainOff = v; }, get skip() { return c.mainSkip; }, get backups() { return c.mainBackups; } };
  return k[0] === "s" ? c.subs[+k.slice(1)] : c.lines[+k.slice(1)];
}
function kpSwitch(k, off) { return '<button class="kp-tg' + (off ? "" : " on") + '" data-tog="' + k + '" aria-pressed="' + !off + '" aria-label="Printer on / off"><i></i></button><span class="kp-tgl' + (off ? " off" : "") + '">' + (off ? "Off" : "On") + "</span>"; }
function kpTypes(k, skip) {
  return '<div class="kp-row kp-ot"><span class="kp-lbl">Prints for</span>' + KP_OT.map(function (t) { var on = skip.indexOf(t[0]) < 0; return '<button class="kp-o' + (on ? " on" : "") + '" data-ot="' + k + ":" + t[0] + '" aria-pressed="' + on + '">' + (on ? "✓ " : "") + t[1] + "</button>"; }).join("") + "</div>";
}
function kpBackups(list, k) {
  return '<div class="kp-row"><span class="kp-lbl">Backup</span>' + list.map(function (b, i) { return '<span class="kp-bk">' + (i + 1) + " · " + kpSel(b, "", 'data-bk="' + k + ":" + i + '"') + '<button class="kp-x" data-bkdel="' + k + ":" + i + '" aria-label="Remove backup">×</button></span>'; }).join("") +
    (list.length < 3 ? '<button class="kp-add" data-bkadd="' + k + '">+ Backup</button>' : "") + "</div>";
}
function kpPick(attr, taken, cats, items) {
  var o = '<option value="">+</option>';
  if (cats) o += '<optgroup label="Categories">' + (KP.cats || []).filter(function (x) { return taken.indexOf("c:" + x) < 0; }).map(function (x) { return '<option value="c:' + esc(x) + '">' + esc(x) + "</option>"; }).join("") + "</optgroup>";
  if (items) o += '<optgroup label="Items">' + (KP.items || []).filter(function (x) { return taken.indexOf("i:" + x) < 0; }).map(function (x) { return '<option value="i:' + esc(x) + '">' + esc(x) + "</option>"; }).join("") + "</optgroup>";
  return '<select class="kp-sel kp-addval" ' + attr + ">" + o + "</select>";
}
function kpDraw() {
  var c = KP.cfg, T = { P: "Pick up", I: "Dine in", S: "Self service" }, t = KP.today || {};
  var modes = [["bridge", "Bridge Print only"], ["console", "Console only"], ["both", "Console + Bridge backup"]];
  var h = '<div class="panel"><div class="kp-hd"><h2>KOT printing</h2><button class="kp-help' + (KPhelp ? " on" : "") + '" id="kpHelp" aria-expanded="' + KPhelp + '">❓ Help</button></div>' +
    '<div class="kp-row"><div class="kp-seg">' + modes.map(function (m) { return '<button data-mode="' + m[0] + '" aria-pressed="' + (c.mode === m[0]) + '">' + m[1] + "</button>"; }).join("") + "</div>" +
    '<span class="mute kp-hint">' + { bridge: "Petpooja's slip through Bridge Print, as before", console: "Only the Console's slip prints", both: "Console prints; if it fails, Bridge Print prints" }[c.mode] + "</span></div>" +
    '<div class="kp-row kp-chips"><span class="kp-chip on">✓ Delivery / Online <i>always</i></span>' + ["P", "I", "S"].map(function (x) { return '<button class="kp-chip' + (c.types.indexOf(x) >= 0 ? " on" : "") + '" data-type="' + x + '">' + (c.types.indexOf(x) >= 0 ? "✓ " : "") + T[x] + "</button>"; }).join("") +
    '<button class="kp-chip all" data-type="all">All</button></div>' +
    '<p class="mute kp-today">Today: ' + (t.console || 0) + " slips by the Console · " + (t.bridge || 0) + " by Bridge Print · " + (t.skipped || 0) + " Petpooja slips not needed</p></div>";
  if (KPhelp) h += '<div class="panel kp-helpbox"><div class="kp-hd"><h2>❓ How to set up KOT printing</h2><a href="#" id="kpExpand">Expand all</a></div>' + KP_HELP.map(function (x) { return "<details><summary>" + x[0] + "</summary><ul>" + x[1].map(function (l) { return "<li>" + l + "</li>"; }).join("") + "</ul></details>"; }).join("") + "</div>";
  // main printers
  h += '<div class="panel"><h2>Main printers <span class="mute kp-sub">· the whole bill with the slot</span></h2>' + c.subs.map(function (sm, i) {
    var taken = sm.cats.map(function (x) { return "c:" + x; }).concat(sm.items.map(function (x) { return "i:" + x; }));
    return '<div class="kp-box sub' + (sm.off ? " off" : "") + '"><div class="kp-row"><span class="kp-pill">Sub main ' + (i + 1) + '</span><span class="mute kp-b">Bills that contain any of</span>' +
      sm.cats.map(function (v, vi) { return '<span class="kp-tag">' + esc(v) + ' · category<button class="kp-x" data-sdel="' + i + ":c:" + vi + '" aria-label="Remove">×</button></span>'; }).join("") +
      sm.items.map(function (v, vi) { return '<span class="kp-tag">' + esc(v) + ' · item<button class="kp-x" data-sdel="' + i + ":i:" + vi + '" aria-label="Remove">×</button></span>'; }).join("") +
      kpPick('data-sadd="' + i + '"', taken, true, true) + '<span class="kp-sp"></span>' + kpSel(sm.printer, "", 'data-sp="' + i + '"') + kpSwitch("s" + i, sm.off) + '<button class="kp-del" data-sxdel="' + i + '" aria-label="Delete sub main">🗑</button></div>' +
      kpTypes("s" + i, sm.skip) + kpBackups(sm.backups, "s" + i) + "</div>";
  }).join("") + '<div class="kp-row"><button class="kp-add" id="kpAddSub">+ Add sub main printer</button><span class="mute kp-small">a bill goes to the first sub main it matches</span></div>' +
    '<div class="kp-box' + (c.mainOff ? " off" : "") + '"><div class="kp-row"><span class="kp-pill dark">Main</span><span class="mute kp-b">All other bills</span><span class="kp-sp"></span>' + kpSel(c.main, "kp-main", 'data-main="1"') + kpSwitch("m", c.mainOff) + kpState(c.main) +
    '<button class="btn ghost sm" data-test="1">Test print</button></div>' + kpTypes("m", c.mainSkip) + kpBackups(c.mainBackups, "m") +
    '<label class="kp-sw2"><input type="checkbox" id="kpWhole"' + (c.mainRest ? "" : " checked") + "> Print the whole order</label></div></div>";
  // category & item printers
  h += '<div class="panel"><h2>Category & item printers</h2>' + c.lines.map(function (l, i) {
    var taken = l.values.map(function (x) { return (l.kind === "item" ? "i:" : "c:") + x; });
    return '<div class="kp-line' + (l.off ? " off" : "") + '"><div class="kp-row"><div class="kp-seg sm"><button data-kind="' + i + ':cat" aria-pressed="' + (l.kind !== "item") + '">Category</button><button data-kind="' + i + ':item" aria-pressed="' + (l.kind === "item") + '">Item</button></div>' +
      l.values.map(function (v, vi) { return '<span class="kp-tag">' + esc(v) + '<button class="kp-x" data-tagdel="' + i + ":" + vi + '" aria-label="Remove">×</button></span>'; }).join("") +
      kpPick('data-addval="' + i + '"', taken, l.kind !== "item", l.kind === "item") + '<span class="kp-sp"></span>' + kpSel(l.printer, "", 'data-lp="' + i + '"') + kpSwitch("l" + i, l.off) + '<button class="kp-del" data-ldel="' + i + '" aria-label="Delete line">🗑</button></div>' +
      kpTypes("l" + i, l.skip) + kpBackups(l.backups, "l" + i) + "</div>";
  }).join("") + '<div class="kp-row"><button class="kp-add" id="kpAddLine">+ Add line</button></div></div>';
  h += '<div class="kp-save"><button class="btn" id="kpSave">Save</button><span class="msg' + (KPmsg ? (KPmsg.ok ? " ok" : " bad") : "") + '" id="kpMsg">' + (KPmsg ? esc(KPmsg.msg) : "") + '</span><a class="mute" href="/console/api/direct/test" target="_blank" rel="noopener">See a sample slip</a></div>';
  h += '<div class="panel"><h2>Always on</h2><p class="help">✓ Starts with Windows, before anyone logs in &nbsp; ✓ Restarts within 2 s if it stops &nbsp; ✓ Starts again within a minute if someone stops it in Services &nbsp; ✓ This PC never sleeps or hibernates while the Console runs (the display may turn off)</p>' +
    '<p class="help">One-time job per PC: in the BIOS set <b>“Restore on AC power loss: Power On”</b>, so the PC switches itself on after a power cut.</p></div>';
  var open = [].map.call(document.querySelectorAll("#kp details"), function (d) { return d.open; });
  $("kp").innerHTML = h;
  document.querySelectorAll("#kp details").forEach(function (d, i) { if (open[i]) d.open = true; });
}
document.addEventListener("click", function (e) {
  if (!KP || !$("kp") || !$("kp").contains(e.target)) return;
  var c = KP.cfg;
  if (e.target.id === "kpExpand") { e.preventDefault(); var ds = document.querySelectorAll("#kp details"), all = [].every.call(ds, function (x) { return x.open; }); ds.forEach(function (x) { x.open = !all; }); e.target.textContent = all ? "Expand all" : "Collapse all"; return; }
  var b = e.target.closest("button"); if (!b) return;
  var d = b.dataset;
  if (b.id === "kpHelp") KPhelp = !KPhelp;
  else if (d.mode) c.mode = d.mode;
  else if (d.type) { if (d.type === "all") c.types = c.types.length === 3 ? [] : ["P", "I", "S"]; else { var i = c.types.indexOf(d.type); if (i >= 0) c.types.splice(i, 1); else c.types.push(d.type); } }
  else if (d.tog) { var o = kpObj(d.tog); o.off = !o.off; }
  else if (d.ot) { var q = d.ot.split(":"), sk = kpObj(q[0]).skip, si = sk.indexOf(q[1]); if (si >= 0) sk.splice(si, 1); else sk.push(q[1]); }
  else if (d.bkadd) { var L = kpObj(d.bkadd).backups; if (L.length < 3) L.push(""); }
  else if (d.bkdel) { var p = d.bkdel.split(":"); kpObj(p[0]).backups.splice(+p[1], 1); }
  else if (d.kind) { var r = d.kind.split(":"); if (c.lines[+r[0]].kind !== r[1]) { c.lines[+r[0]].kind = r[1]; c.lines[+r[0]].values = []; } }
  else if (d.tagdel) { var u = d.tagdel.split(":"); c.lines[+u[0]].values.splice(+u[1], 1); }
  else if (d.sdel) { var v = d.sdel.split(":"); c.subs[+v[0]][v[1] === "c" ? "cats" : "items"].splice(+v[2], 1); }
  else if (d.ldel) c.lines.splice(+d.ldel, 1);
  else if (d.sxdel) c.subs.splice(+d.sxdel, 1);
  else if (b.id === "kpAddLine") c.lines.push({ kind: "cat", values: [], printer: "", backups: [], skip: [] });
  else if (b.id === "kpAddSub") c.subs.push({ cats: [], items: [], printer: "", backups: [], skip: [] });
  else if (d.test) { post("/console/api/direct/test", { Printer: c.main }).then(function (r) { KPmsg = r; kpDraw(); }); return; }
  else if (b.id === "kpSave") {
    var out = JSON.parse(JSON.stringify(c));
    out.mainBackups = out.mainBackups.filter(Boolean);
    out.subs = out.subs.filter(function (x) { return x.printer || x.cats.length || x.items.length; }).map(function (x) { x.backups = x.backups.filter(Boolean); return x; });
    out.lines = out.lines.filter(function (l) { return l.printer || l.values.length; }).map(function (l) { l.backups = l.backups.filter(Boolean); return l; });
    post("/console/api/direct", out).then(function (r) { KPmsg = r; kpDraw(); if (r.ok) VIEWS.kotprint.load(); setTimeout(function () { KPmsg = null; }, 4000); });
    return;
  } else return;
  kpDraw();
});
document.addEventListener("change", function (e) {
  if (!KP || !$("kp") || !$("kp").contains(e.target)) return;
  var c = KP.cfg, t = e.target, d = t.dataset;
  if (d.main) c.main = t.value;
  else if (d.bk) { var p = d.bk.split(":"); kpObj(p[0]).backups[+p[1]] = t.value; }
  else if (d.lp !== undefined) c.lines[+d.lp].printer = t.value;
  else if (d.sp !== undefined) c.subs[+d.sp].printer = t.value;
  else if (d.addval !== undefined) { if (t.value) c.lines[+d.addval].values.push(t.value.slice(2)); }
  else if (d.sadd !== undefined) { if (t.value) c.subs[+d.sadd][t.value[0] === "c" ? "cats" : "items"].push(t.value.slice(2)); }
  else if (t.id === "kpWhole") c.mainRest = !t.checked;
  else return;
  kpDraw();
});

// ---- Bridge: Babai Screens (TVs and tablets running the Babai Screens app or a screen page) -------
VIEWS.screens = {
  every: 10000,
  html: function () {
    return '<div class="panel"><h2>Babai Screens</h2><p class="help">Every TV, tablet or monitor showing a Babai screen, and when it last checked in. Install the <b>Babai Screens</b> app from the Apps page (or by USB), open it, and pick the screen once. A kitchen screen asks for the Console PIN once.</p><div id="scl"></div></div>' +
      '<div class="panel"><h2>Screens</h2><div class="tw"><table><thead><tr><th>Screen</th><th>Device</th><th>App</th><th>Address</th><th>Last seen</th></tr></thead><tbody id="sct"></tbody></table></div></div>';
  },
  load: function () {
    getJ("/console/api/screens").then(function (d) {
      var base = (d.links || [])[0] || "";
      $("scl").innerHTML = base ? '<p>Screens in a browser: <b>' + esc(base) + '/screens/</b> · App update: ' + (d.app && d.app.ready ? "build " + d.app.b + " ready" : "not downloaded yet") + "</p>" : '<p class="help">No network address: is this PC on the shop Wi-Fi?</p>';
      var names = { rider: "Rider TV", dosa: "Dosa order status", pickup: "Pick-up TV", kitchen: "Kitchen", barker: "Dosa barker", hello: "Setting up" };
      $("sct").innerHTML = (d.screens || []).map(function (s) {
        var ago = Math.round((d.now - s.at) / 1000), off = ago > 60;
        return "<tr><td>" + esc(names[s.screen] || s.screen || "—") + (s.paired ? ' <span class="chip ok">paired</span>' : "") + "</td><td>" + esc(s.name || s.dev) + "</td><td>" + esc(s.app || "browser") + "</td><td>" + esc(s.ip) + '</td><td><span class="chip ' + (off ? "bad" : "ok") + '">' + (ago < 60 ? "now" : ago < 3600 ? Math.round(ago / 60) + " min ago" : Math.round(ago / 3600) + " h ago") + "</span></td></tr>";
      }).join("") || '<tr><td colspan="5" class="help">No screens yet.</td></tr>';
    }).catch(function () {});
  }
};

// ---- Bridge: Dosa barker (the dosa master's tawa screen, on any TV / tablet on the shop Wi-Fi) -----
VIEWS.barker = {
  html: function () {
    return '<div class="panel"><h2>Dosa barker on a TV or tablet</h2><p class="help">Open this address in the browser of any TV, tablet or monitor on the shop Wi-Fi. Tawas, channels and rules are set with ⚙ on the screen (the Console PIN is asked there).</p><div id="bkl"></div><div class="qr" id="bkqr"></div>' +
      '<p class="help">One tawa per screen: add <b>?tawa=2</b> to the address. Foot pedal: a USB / Bluetooth foot switch set to type 1 for Tawa 1, 2 for Tawa 2 … (any key on a one-tawa screen).</p></div>' +
      '<iframe class="frame" style="min-height:760px;border:1px solid var(--line);border-radius:12px" src="/barker" title="Dosa barker"></iframe>';
  },
  load: function () {
    getJ("/console/api/apps").then(function (d) {
      var link = ((d.links || [])[0] || "").replace(/\/apps$/, "/barker");
      $("bkl").innerHTML = link ? '<p><b>' + esc(link) + '</b></p><p class="help">Tawa 1 only: ' + esc(link) + '?tawa=1 · Tawa 2 only: ' + esc(link) + '?tawa=2</p>' : '<p class="help">No network address: is this PC on the shop Wi-Fi?</p>';
      if (link && window.QRCode) new QRCode($("bkqr"), { text: link, width: 140, height: 140, correctLevel: QRCode.CorrectLevel.M });
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
VIEWS.cxsheet = {
  html: function () {
    return '<div class="panel"><div class="hd"><h2>Ratings sheet</h2><span class="sub">Order times for the ratings &amp; complaints dashboard</span></div>' +
      '<p class="help">Every 15 minutes this PC sends each online order\'s times to the CX METRICS Google Sheet: punched, printed, food ready (KPT), picked up (rider wait), delivered and the slot. The sheet joins them with ratings and complaints on the order ID. Only times and order IDs are sent, never customer details.</p>' +
      '<label class="help" for="cxu">Web app link (from the sheet: Extensions › Apps Script › Deploy)</label><input id="cxu" type="url" placeholder="https://script.google.com/macros/s/…/exec" style="width:100%;margin:4px 0 10px">' +
      '<label class="help" for="cxk">Key (shown by setup() in the sheet\'s Apps Script)</label><input id="cxk" type="password" autocomplete="off" style="width:100%;margin:4px 0 10px">' +
      '<p><button class="btn p" id="cxs">Save</button> <button class="btn" id="cxn">Send now</button></p><p class="msg" id="cxm"></p><p class="msg" id="cxst"></p></div>';
  },
  init: function () {
    function msg(r) { $("cxm").textContent = r.msg; $("cxm").className = "msg " + (r.ok ? "ok" : "bad"); setTimeout(VIEWS.cxsheet.load, 3000); }
    $("cxs").onclick = function () { post("/console/api/cxsheet", { url: $("cxu").value, key: $("cxk").value }).then(function (r) { $("cxk").value = ""; msg(r); }); };
    $("cxn").onclick = function () { post("/console/api/cxsheet", { op: "send" }).then(msg); };
    getJ("/console/api/cxsheet").then(function (d) { $("cxu").value = d.url || ""; $("cxk").placeholder = d.keySet ? "saved (type to change)" : ""; }).catch(function () {});
  },
  load: function () {
    getJ("/console/api/cxsheet").then(function (d) { var e = $("cxst"); if (!e) return; e.textContent = "Sheet: " + d.state; e.className = "msg " + (d.ok ? "ok" : ""); }).catch(function () {});
  },
  every: 15000
};

// ---- barcode scanner on this PC: any tab marks Food Ready ---------------------------------------
var scanSeq = -1, scanTT = null;
function scanSound(ok) {
  try {
    var c = new (window.AudioContext || window.webkitAudioContext)(), t = c.currentTime;
    (ok ? [1320] : [330, 330]).forEach(function (f, i) {
      var o = c.createOscillator(), g = c.createGain();
      o.type = "square"; o.frequency.value = f; g.gain.value = 0.12;
      o.connect(g); g.connect(c.destination); o.start(t + i * 0.18); o.stop(t + i * 0.18 + 0.13);
    });
    setTimeout(function () { c.close(); }, 800);
  } catch (e) {}
}
function scanToast(r) {
  var good = r.ok && !r.already, el = $("scanToast");
  var who = r.b ? r.b + " …" + r.l + (r.s ? " · Slot " + r.s : "") : "…" + String(r.c || "").slice(-4);
  el.textContent = good ? "✓ FOOD READY · " + who : r.already ? "Already food ready · " + who : "✗ " + (r.m || "Not marked") + (r.b ? " · " + who : "");
  el.className = "scantoast show " + (good ? "ok" : r.already ? "warn" : "bad");
  scanSound(good);
  clearTimeout(scanTT); scanTT = setTimeout(function () { el.className = "scantoast"; }, 4000);
  if (tab === "pcscan" && VIEWS.pcscan.load) VIEWS.pcscan.load();
}
function pcScan(code) {
  post("/console/api/scan", { C: code }).then(scanToast).catch(function () { scanToast({ m: "Console not answering", c: code }); });
}
BabaiWedge(document, pcScan);
document.addEventListener("load", function (e) { // the pages shown inside the Console (same PC)
  if (e.target && e.target.tagName === "IFRAME") try { BabaiWedge(e.target.contentDocument, pcScan); } catch (x) {}
}, true);
window.addEventListener("message", function (e) { // the Bridge Print overview (port 8787)
  if (/^http:\/\/(127\.0\.0\.1|localhost):8787$/.test(e.origin) && e.data && typeof e.data.babaiScan === "string") pcScan(e.data.babaiScan);
});
function pollScanFeed() { // COM-mode scans, read by the Console in the background
  getJ("/console/api/scanfeed?after=" + scanSeq).then(function (r) {
    if (scanSeq >= 0) (r.items || []).forEach(scanToast);
    scanSeq = r.seq;
  }).catch(function () {});
}
pollScanFeed();
setInterval(pollScanFeed, 1000);

VIEWS.pcscan = {
  html: function () {
    return '<div class="panel"><div class="hd"><h2>Barcode scanner on this PC</h2><span class="sub">Scan a KOT or bill: the order is marked Food Ready in Petpooja</span></div>' +
      '<p class="help"><b>Keyboard mode</b> (as most scanners come): works on every tab of this Console while its window is in front. With Petpooja in front, the scan types into Petpooja instead. Nothing to set up.</p>' +
      '<p class="help"><b>COM mode</b>: works in the background whatever window is in front. Switch the scanner to “USB COM / virtual serial” with the setup barcode in its manual, plug it in, choose its port here.</p>' +
      '<div class="row" style="gap:8px;align-items:center;flex-wrap:wrap"><label for="comp" class="help">COM scanner</label><select id="comp"></select><button class="btn p" id="coms">Save</button><button class="btn" id="comr">Refresh ports</button></div>' +
      '<p class="msg" id="comm"></p><p class="msg" id="comst"></p>' +
      '<p class="help">Same rules as the scanner phones: any KOT or bill barcode of an open order; every KOT of the bill; scanning again says “already food ready”; each scan is in the scan log (Hub › Scanners &amp; rider).</p></div>';
  },
  init: function () {
    $("coms").onclick = function () {
      post("/console/api/comscan", { Port: $("comp").value }).then(function (r) { $("comm").textContent = r.msg; $("comm").className = "msg " + (r.ok ? "ok" : "bad"); setTimeout(VIEWS.pcscan.load, 1500); });
    };
    $("comr").onclick = function () { VIEWS.pcscan.ports = null; VIEWS.pcscan.load(); };
  },
  load: function () {
    getJ("/console/api/comscan").then(function (d) {
      var sel = $("comp"); if (!sel) return;
      var st = d.state || {};
      if (!VIEWS.pcscan.ports || document.activeElement !== sel) {
        var ports = d.ports || [], has = false;
        sel.innerHTML = '<option value="">Off (keyboard mode only)</option>' + ports.map(function (p) {
          if (p.name === st.port) has = true;
          return '<option value="' + esc(p.name) + '">' + esc(p.name) + (/usbser/i.test(p.dev) ? " · USB" : "") + "</option>";
        }).join("") + (st.port && !has ? '<option value="' + esc(st.port) + '">' + esc(st.port) + " (not plugged in)</option>" : "");
        sel.value = st.port || "";
        VIEWS.pcscan.ports = ports;
      }
      var e = $("comst");
      e.textContent = !st.port ? "COM scanner off." : st.open ? "Reading " + st.port + " · ready to scan" + (st.lastAt ? " · last scan " + hms(st.lastAt) + " (…" + String(st.lastCode).slice(-4) + ")" : "") : (st.err || "Opening " + st.port + "…");
      e.className = "msg " + (!st.port ? "" : st.open ? "ok" : "bad");
    }).catch(function () {});
  },
  every: 3000
};

loadState().then(render);
setInterval(loadState, 3000);
window.addEventListener("hashchange", function () {
  var h = location.hash.replace("#", "");
  for (var a in AREAS) AREAS[a].items.forEach(function (it) { if (it[0] === h) { area = a; tab = h; } });
  render();
});
