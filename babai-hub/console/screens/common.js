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
  BS.zoom = 1;
  function unit() {
    var w = window.innerWidth, h = window.innerHeight;
    BS.zoom = textZoom();
    document.documentElement.style.fontSize = Math.min(w / 100, h * 16 / 9 / 100) / BS.zoom + "px";
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
  BS.warn = function (msg) { warn.hidden = !msg; warn.textContent = msg || ""; };
  BS.poll = function (path, ms, onData) {
    var fails = 0, busy = false;
    function tick() {
      if (busy) return; busy = true;
      BS.get(path, function (err, j) {
        busy = false;
        if (err) { if (++fails >= 3) BS.warn("Reconnecting to the Babai Console… (showing the last orders)"); return; }
        fails = 0;
        BS.warn(j.ok === false ? "The Console can’t read Petpooja right now. New orders will show when it can." : "");
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
  BS.digits = function (s) { return String(s).split("").map(function (c) { return c === "0" ? "zero" : c; }).join(" "); };
})();
