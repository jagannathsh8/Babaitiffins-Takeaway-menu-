// Babai Screens: shared code for every screen page (ES5: old Android TV browsers too).
// The Babai Screens app opens a page as /screens/<screen>?dev=<id>&app=<version>&name=<name> and
// gives it window.BabaiApp (voice, the pairing key). In a normal browser the page still works.
(function () {
  "use strict";
  if (!Element.prototype.matches) Element.prototype.matches = Element.prototype.webkitMatchesSelector;
  if (!Element.prototype.closest) Element.prototype.closest = function (s) { var e = this; while (e && e.nodeType === 1) { if (e.matches(s)) return e; e = e.parentNode; } return null; };
  if (window.NodeList && !NodeList.prototype.forEach) NodeList.prototype.forEach = Array.prototype.forEach;

  var Q = {};
  location.search.replace(/^\?/, "").split("&").forEach(function (p) { if (!p) return; var i = p.indexOf("="); Q[decodeURIComponent(i < 0 ? p : p.slice(0, i))] = i < 0 ? "" : decodeURIComponent(p.slice(i + 1).replace(/\+/g, " ")); });
  var app = window.BabaiApp || null;
  var scr = (location.pathname.split("/").pop() || "").replace(/\.html$/, "");
  var BS = window.BS = { q: Q, app: app, screen: scr };

  function ls(k, v) { try { if (v === undefined) return localStorage.getItem(k); if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) {} return null; }
  BS.store = ls;

  // 1rem = 1% of the largest 16:9 box that fits the screen
  // Android TVs with a large system font size zoom every font of the page (the web view's text
  // zoom), which pushed the screen past the TV's edges: measure that zoom and cancel it.
  function textZoom() {
    var d = document.createElement("div");
    d.style.cssText = "position:absolute;visibility:hidden;left:-999px;top:0;font-size:100px;line-height:1;height:1em;padding:0;border:0";
    (document.body || document.documentElement).appendChild(d);
    var z = d.offsetHeight / 100;
    d.parentNode.removeChild(d);
    return z > 0.5 && z < 4 ? z : 1;
  }
  // The TV pages (a .tv box) are laid out at a fixed 1920 x 1080 and the whole picture is scaled to
  // the screen: on a phone or a small web view the letters stay full size in the layout, so the
  // browser's minimum font size can't blow them up past their boxes (the "over zoom").
  BS.zoom = 1;
  function unit() {
    var w = window.innerWidth, h = window.innerHeight;
    BS.zoom = textZoom();
    var tv = document.querySelectorAll(".tv");
    if (!tv.length) {
      document.documentElement.style.fontSize = Math.min(w / 100, h * 16 / 9 / 100) / BS.zoom + "px";
      return;
    }
    var rem = 19.2 / BS.zoom, k = Math.min(w / (100 * rem), h / (56.25 * rem));
    document.documentElement.style.fontSize = rem + "px";
    for (var i = 0; i < tv.length; i++) {
      var t = "translate(-50%,-50%) scale(" + k.toFixed(4) + ")";
      tv[i].style.webkitTransform = t; tv[i].style.transform = t;
    }
  }
  document.addEventListener("DOMContentLoaded", function () { unit(); if (BS.onresize) BS.onresize(); });
  unit(); window.addEventListener("resize", function () { unit(); if (BS.onresize) BS.onresize(); });

  BS.url = function (path) {
    var p = ["dev", "app", "name"].filter(function (k) { return Q[k]; }).map(function (k) { return k + "=" + encodeURIComponent(Q[k]); });
    p.push("scr=" + scr);
    return path + (path.indexOf("?") < 0 ? "?" : "&") + p.join("&");
  };
  function xhr(method, path, body, cb) {
    var x = new XMLHttpRequest();
    x.open(method, method === "GET" ? BS.url(path) : path, true);
    x.timeout = 8000;
    if (method === "POST") {
      x.setRequestHeader("Content-Type", "application/json");
      x.setRequestHeader("X-Babai-Hub", "1");
    }
    var k = BS.key(); if (k) x.setRequestHeader("X-Screen-Key", k);
    x.onload = function () { var j = null; try { j = JSON.parse(x.responseText); } catch (e) {} if (j) { j._status = x.status; cb(null, j); } else cb(new Error("HTTP " + x.status)); };
    x.onerror = x.ontimeout = function () { cb(new Error("network")); };
    x.send(body ? JSON.stringify(body) : null);
  }
  BS.get = function (path, cb) { xhr("GET", path, null, cb); };
  BS.post = function (path, body, cb) { xhr("POST", path, body, cb || function () {}); };

  // the pairing key (kitchen screens): kept by the app, or by this browser
  BS.key = function () { try { if (app && app.key) return app.key() || ""; } catch (e) {} return ls("bs.key") || ""; };
  BS.setKey = function (k) { try { if (app && app.setKey) { app.setKey(k); return; } } catch (e) {} ls("bs.key", k); };

  // ---- "can't reach the Console" bar; the screen keeps its last picture meanwhile
  var warn = document.createElement("div"); warn.className = "warn"; warn.hidden = true; warn.setAttribute("role", "status");
  document.addEventListener("DOMContentLoaded", function () { document.body.appendChild(warn); });
  BS.warn = function (msg, sub) { warn.hidden = !msg; warn.innerHTML = msg ? BS.esc(msg) + (sub ? "<small>" + BS.esc(sub) + "</small>" : "") : ""; };
  BS.poll = function (path, ms, onData) {
    var fails = 0, busy = false, lostAt = 0;
    function tick() {
      if (busy) return; busy = true;
      BS.get(path, function (err, j) {
        busy = false;
        if (err) { if (!fails) lostAt = Date.now(); if (++fails >= 3) BS.warn("⚠ CONSOLE OFFLINE · orders are not updating", "Lost " + BS.mmss((Date.now() - lostAt) / 1000) + " ago · showing the last orders · check that the Console PC is on"); return; }
        fails = 0;
        BS.warn(j.ok === false ? "⚠ PETPOOJA NOT READING · new orders will show when it can" : "", j.ok === false ? "The Console is on, but can’t read the Petpooja billing PC" : "");
        BS.skew = j.skew || 0; BS.srv = j.now ? j.now - Date.now() : 0;
        onData(j);
      });
    }
    tick(); return setInterval(tick, ms);
  };
  // server time now; billing-PC times (KOT created) + skew
  BS.now = function () { return Date.now() + (BS.srv || 0); };
  BS.since = function (t, billing) { return Math.max(0, (BS.now() - (t + (billing ? BS.skew || 0 : 0))) / 1000); };
  BS.mmss = function (sec) { sec = Math.max(0, Math.floor(sec)); return Math.floor(sec / 60) + ":" + ("0" + sec % 60).slice(-2); };
  BS.esc = function (s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); };
  BS.clock = function (el) { function t() { var d = new Date(), h = d.getHours() % 12 || 12; el.textContent = h + ":" + ("0" + d.getMinutes()).slice(-2); } t(); setInterval(t, 5000); };
  var BRAND = [["swiggy", "S", "var(--swiggy)"], ["zomato", "Z", "var(--zomato)"], ["ownly", "O", "var(--ownly)"], ["eatin", "E", "var(--eatin)"], ["toing", "T", "var(--toing)"]];
  BS.brand = function (p) { var l = String(p || "").toLowerCase().replace(/\s/g, ""); for (var i = 0; i < BRAND.length; i++) if (l.indexOf(BRAND[i][0]) >= 0) return { b: BRAND[i][1], c: BRAND[i][2] }; return { b: (String(p || "?").charAt(0) || "?").toUpperCase(), c: "var(--other)" }; };

  // ---- voice: the app's (TV voice, else the Console's voice, else a chime); in a browser, the
  // browser's voice, else the Console's
  BS.voiceOn = function (v) { if (v === undefined) return ls("bs.voice." + scr) !== "0"; ls("bs.voice." + scr, v ? "1" : "0"); return v; };
  BS.say = function (text, chime) {
    if (!BS.voiceOn() || !text) return;
    var wav = location.origin + "/screens/voice.wav?t=" + encodeURIComponent(text);
    try { if (app && app.speak) { app.speak(text, wav, !!chime); return; } } catch (e) {}
    try {
      if (window.speechSynthesis && speechSynthesis.getVoices().length) { var u = new SpeechSynthesisUtterance(text); u.lang = "en-IN"; u.rate = 0.9; speechSynthesis.speak(u); return; }
    } catch (e) {}
    try { var pl = new Audio(wav).play(); if (pl && pl.catch) pl.catch(function () {}); } catch (e) {}
  };
  BS.busy = function () { try { if (app && app.speaking) return app.speaking(); } catch (e) {} try { return window.speechSynthesis && speechSynthesis.speaking; } catch (e) {} return false; };
  // ---- ⚙ on a screen: how long ready tokens stay (Off / 30 / 45 / 60 min), with the Console PIN.
  // The same setting as on the Console's Screens page. BS.clr: the current value, from the feed.
  BS.clr = 30;
  var sett = null;
  BS.setOpen = function () { return !!sett; };
  BS.settings = function (onClose) {
    if (sett) return;
    var pick = BS.clr, opts = [[-1, "Off"], [30, "30 min"], [45, "45 min"], [60, "1 hour"]];
    sett = document.createElement("div"); sett.className = "sett"; sett.setAttribute("role", "dialog");
    sett.innerHTML = '<div class="box"><h3>⚙ Screen settings</h3><p>Ready tokens leave the screen after</p><div class="ch">' +
      opts.map(function (o) { return '<button data-v="' + o[0] + '" aria-pressed="' + (o[0] === pick) + '">' + o[1] + "</button>"; }).join("") +
      '</div><p>Off: a token stays until it is collected (triple tap, or Dispatch on a kitchen screen).</p>' +
      '<div class="act"><input type="password" inputmode="numeric" maxlength="8" placeholder="PIN" aria-label="Console PIN"><button class="go">Save</button><button class="no">Close</button></div><div class="m" role="status"></div></div>';
    (document.querySelector(".tv") || document.body).appendChild(sett);
    var m = sett.querySelector(".m"), pin = sett.querySelector("input");
    function close() { if (sett && sett.parentNode) sett.parentNode.removeChild(sett); sett = null; if (onClose) onClose(); }
    BS.closeSettings = close;
    sett.querySelectorAll(".ch button").forEach(function (b) {
      b.onclick = function () { pick = +b.getAttribute("data-v"); sett.querySelectorAll(".ch button").forEach(function (x) { x.setAttribute("aria-pressed", String(x === b)); }); pin.focus(); };
    });
    sett.querySelector(".no").onclick = close;
    function save() {
      m.className = "m"; m.textContent = "Saving…";
      BS.post("/screens/api/autoclear", { pin: pin.value, min: pick }, function (err, j) {
        if (err || !j || !j.ok) { m.className = "m bad"; m.textContent = err ? "Can’t reach the Console." : j.msg; pin.value = ""; pin.focus(); return; }
        BS.clr = pick; m.className = "m ok"; m.textContent = "Saved."; setTimeout(close, 900);
      });
    }
    sett.querySelector(".go").onclick = save;
    pin.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); save(); } if (e.key === "Escape") close(); });
    var cur = sett.querySelector('.ch button[aria-pressed="true"]') || sett.querySelector(".ch button"); cur.focus();
  };
  BS.digits = function (s) { return String(s).split("").map(function (c) { return c === "0" ? "zero" : c; }).join(" "); };
})();
