// Babai Screens: a USB / Bluetooth barcode scanner plugged into any screen (Rider, Dosa status,
// Pick-up, Kitchen, Dosa barker) marks the KOT Food ready in the background, whatever the screen
// shows. Same codes as the Babai Scanner phones: the platform order ID (delivery) or the "00..."
// code on the Console's dine in / pick up slips. ES5, no other script needed (the barker page has
// none). The screen pairs once with the Console PIN (as a kitchen screen does).
//
// A scanner types the code very fast and ends with Enter (or Tab); a person or a remote types
// slowly. Keys less than 60 ms apart are a scan: they never reach the page (no search box, no
// pedal press); a slow key is passed on as usual.
(function () {
  "use strict";
  var app = window.BabaiApp || null;
  function ls(k, v) { try { if (v === undefined) return localStorage.getItem(k); localStorage.setItem(k, v); } catch (e) {} return null; }
  function key() { try { if (app && app.key) return app.key() || ""; } catch (e) {} return ls("bs.key") || ""; }
  function setKey(k) { try { if (app && app.setKey) { app.setKey(k); return; } } catch (e) {} ls("bs.key", k); }
  function q(name) { var m = new RegExp("[?&]" + name + "=([^&]*)").exec(location.search); return m ? decodeURIComponent(m[1].replace(/\+/g, " ")) : ""; }
  function dev() { var d = q("dev"); if (d) return d; d = ls("bs.dev"); if (!d) { d = "browser-" + Math.random().toString(36).slice(2, 8); ls("bs.dev", d); } return d; }
  function post(path, body, cb) {
    var x = new XMLHttpRequest();
    x.open("POST", path, true); x.timeout = 10000;
    x.setRequestHeader("Content-Type", "application/json"); x.setRequestHeader("X-Babai-Hub", "1");
    var k = key(); if (k) x.setRequestHeader("X-Screen-Key", k);
    x.onload = function () { var j = null; try { j = JSON.parse(x.responseText); } catch (e) {} cb(j ? null : new Error("HTTP " + x.status), j); };
    x.onerror = x.ontimeout = function () { cb(new Error("network")); };
    x.send(JSON.stringify(body));
  }

  // ---- the result, big for a moment (and a beep)
  var box = null, boxT = 0, actx = null;
  function el() {
    if (box) return box;
    box = document.createElement("div");
    box.setAttribute("role", "status");
    box.style.cssText = "position:fixed;left:50%;top:6vh;transform:translateX(-50%);z-index:99;min-width:34vw;max-width:90vw;padding:2.2vh 3vw;border-radius:2vh;" +
      "font-family:'Segoe UI',Roboto,Arial,sans-serif;text-align:center;color:#fff;box-shadow:0 1.5vh 5vh rgba(0,0,0,.55);display:none;line-height:1.2";
    document.body.appendChild(box);
    return box;
  }
  function beep(good) {
    try {
      actx = actx || new (window.AudioContext || window.webkitAudioContext)();
      var o = actx.createOscillator(), g = actx.createGain();
      o.frequency.value = good ? 1046 : 220; o.type = good ? "sine" : "square";
      g.gain.value = 0.15; o.connect(g); g.connect(actx.destination);
      o.start(); o.stop(actx.currentTime + (good ? 0.18 : 0.45));
    } catch (e) {}
  }
  function show(kind, title, sub, ms) {
    var b = el(), col = kind === "ok" ? "#0A8F4A" : kind === "wait" ? "#37474F" : kind === "pair" ? "#1d3557" : "#C62828";
    b.style.background = col;
    b.innerHTML = '<div style="font-weight:800;font-size:max(22px,3.6vw)">' + esc(title) + "</div>" + (sub ? '<div style="font-weight:600;font-size:max(15px,1.7vw);margin-top:.8vh;opacity:.95">' + esc(sub) + "</div>" : "");
    b.style.display = "block";
    clearTimeout(boxT);
    if (ms) boxT = setTimeout(function () { b.style.display = "none"; }, ms);
  }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }
  function tail(s) { var d = String(s).replace(/\D/g, ""); return d.slice(-4); }

  // ---- pairing, once per screen (the Console PIN)
  var pending = null, pairBox = null;
  function pair() {
    if (pairBox) return;
    pairBox = document.createElement("div");
    pairBox.style.cssText = "position:fixed;inset:0;z-index:100;background:rgba(10,10,10,.85);display:flex;align-items:center;justify-content:center;font-family:'Segoe UI',Roboto,Arial,sans-serif";
    pairBox.innerHTML = '<div style="background:#FFF6DF;color:#171717;border-radius:2vh;padding:3vh 3vw;min-width:40vw;display:flex;flex-direction:column;gap:1.6vh">' +
      '<div style="font-weight:800;font-size:max(20px,2.6vw)">🔒 Pair this screen for the scanner</div>' +
      '<div style="font-size:max(14px,1.4vw);color:#444">Once per screen: type the Console PIN. Then every scan here marks Food ready.</div>' +
      '<div style="display:flex;gap:1vw;align-items:center"><input type="password" inputmode="numeric" maxlength="8" placeholder="PIN" aria-label="Console PIN" style="font:800 max(20px,2.4vw) sans-serif;letter-spacing:.4em;width:14vw;min-width:140px;text-align:center;padding:1vh;border:3px solid #171717;border-radius:1vh">' +
      '<button data-a="go" style="font:800 max(16px,1.6vw) sans-serif;padding:1.2vh 1.6vw;border-radius:1vh;border:0;background:#0A8F4A;color:#fff">Pair</button>' +
      '<button data-a="no" style="font:800 max(16px,1.6vw) sans-serif;padding:1.2vh 1.6vw;border-radius:1vh;border:3px solid #171717;background:#fff">Cancel</button></div>' +
      '<div data-m style="font-weight:700;font-size:max(14px,1.3vw);min-height:2.5vh;color:#b91c1c"></div></div>';
    document.body.appendChild(pairBox);
    var pin = pairBox.querySelector("input"), m = pairBox.querySelector("[data-m]");
    function close() { if (pairBox && pairBox.parentNode) pairBox.parentNode.removeChild(pairBox); pairBox = null; }
    function go() {
      m.textContent = "Pairing…";
      post("/screens/api/pair", { pin: pin.value, dev: dev() }, function (err, j) {
        pin.value = "";
        if (err || !j || !j.ok) { m.textContent = err ? "Can’t reach the Console." : j.msg; pin.focus(); return; }
        setKey(j.key); close();
        var c = pending; pending = null;
        if (c) send(c); else show("ok", "Scanner ready", "Scans on this screen now mark Food ready", 3000);
      });
    }
    pairBox.querySelector('[data-a="go"]').onclick = go;
    pairBox.querySelector('[data-a="no"]').onclick = function () { pending = null; close(); };
    pin.addEventListener("keydown", function (e) { e.stopPropagation(); if (e.key === "Enter" || e.keyCode === 13) { e.preventDefault(); go(); } if (e.key === "Escape") { pending = null; close(); } });
    pin.focus();
  }

  // ---- one scan
  function send(code) {
    show("wait", "Sending …" + tail(code), "", 0);
    post("/screens/api/scan", { c: code, id: dev() + "-" + Date.now().toString(36) }, function (err, j) {
      if (err && !j) { beep(false); show("bad", "✕ Not sent", "Can’t reach the Console · scan again", 5000); return; }
      if (j.pair) { pending = code; show("pair", "Pair this screen first", "", 0); pair(); return; }
      var what = j.b ? j.b + (j.l ? (/Token$/.test(j.b) ? " " : " …") + j.l : "") + (j.s ? " · Slot " + j.s : "") : "…" + tail(code);
      if (j.ok) { beep(true); show("ok", j.already ? "✓ Already Food Ready" : "✓ FOOD READY", what, 3500); }
      else { beep(false); show("bad", "✕ " + (j.m || "Not marked"), "Barcode …" + tail(code), 5000); }
    });
  }

  // ---- the scanner's keys
  var buf = "", first = 0, last = 0, lastScan = 0;
  window.BSScan = {
    // a scan is coming in (or just ended): a page delays its own reaction to a first digit
    busy: function () { var n = Date.now(); return (buf.length > 1 && n - last < 150) || n - lastScan < 600; }
  };
  function charOf(e) {
    if (e.key && e.key.length === 1) return e.key;
    var c = e.keyCode || e.which || 0;
    if (c >= 48 && c <= 57) return String.fromCharCode(c);
    if (c >= 96 && c <= 105) return String.fromCharCode(c - 48);
    if (c >= 65 && c <= 90) return String.fromCharCode(c);
    return "";
  }
  window.addEventListener("keydown", function (e) {
    if (pairBox) return; // typing the PIN
    var now = Date.now(), gap = now - last;
    last = now;
    var end = e.key === "Enter" || e.key === "Tab" || e.keyCode === 13 || e.keyCode === 9;
    if (end) {
      var s = buf; buf = "";
      if (s.length >= 4 && s.replace(/\D/g, "").length >= 4 && gap < 150 && (now - first) / s.length < 60) {
        e.preventDefault(); e.stopPropagation(); lastScan = now; send(s);
      }
      return;
    }
    var ch = charOf(e);
    if (!ch) return;
    if (gap > 100 || !buf) { buf = ""; first = now; }
    buf += ch;
    if (buf.length > 80) buf = buf.slice(-80);
    if (buf.length > 1 && gap < 60) { e.preventDefault(); e.stopPropagation(); } // the scanner's, not the page's
  }, true);
})();
