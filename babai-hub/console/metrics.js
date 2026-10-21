"use strict";
// Metrics area: CX metrics (ratings and complaints from the CX METRICS sheet) and Ops metrics (this
// PC's order times). Filters start at Today · All · All day on every open; targets are saved on the PC.
(function () {
  var PF = { S: "Swiggy", Z: "Zomato", O: "Ownly", X: "Other" }, PLAT = ["S", "Z", "O"];
  var MEALS = [["B", "Breakfast", "7–12"], ["L", "Lunch", "12–4"], ["S", "Snacks", "4–7"], ["D", "Dinner", "7 pm on"]];
  var MEALNAME = { B: "Breakfast", L: "Lunch", S: "Snacks", D: "Dinner" };
  var AP = '<span class="ap">≈</span>';
  // On the outlet PC the Console's own API; on another computer on the Wi-Fi (/metrics) a view-only copy.
  var API = window.MX_API || "/console/api/", RO = !!window.MX_READONLY;
  var M = { day: "today", from: "", to: "", p: [], m: [], ops: null, cx: null, opsKey: "", T: null, L: null };

  // ---- small helpers ---------------------------------------------------------------------------
  function ymd(d) { return d.getFullYear() + "-" + ("0" + (d.getMonth() + 1)).slice(-2) + "-" + ("0" + d.getDate()).slice(-2); }
  function range() {
    var t = new Date(), y = new Date(t); y.setDate(t.getDate() - 1);
    if (M.day === "yesterday") return [ymd(y), ymd(y)];
    if (M.day === "7") { var f = new Date(t); f.setDate(t.getDate() - 6); return [ymd(f), ymd(t)]; }
    if (M.day === "range" && M.from && M.to) return M.from <= M.to ? [M.from, M.to] : [M.to, M.from];
    return [ymd(t), ymd(t)];
  }
  function mealH(h) { return h < 12 ? "B" : h < 16 ? "L" : h < 19 ? "S" : "D"; }
  function q(xs, p) { if (!xs.length) return null; var s = xs.slice().sort(function (a, b) { return a - b; }); return s[Math.min(s.length - 1, Math.ceil(p * s.length) - 1)]; }
  function avg(xs) { return xs.length ? xs.reduce(function (a, b) { return a + b; }, 0) / xs.length : null; }
  function f1(x) { return x == null || isNaN(x) ? "–" : x.toFixed(1); }
  function dmon(ms) { var d = new Date(ms); return d.getDate() + " " + ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"][d.getMonth()]; }
  function digits(s) { return String(s || "").replace(/\D/g, ""); }
  function tc(v, g, o, high) { if (v == null || isNaN(v)) return ""; if (high) return v >= g ? "c-g" : v >= o ? "c-o" : "c-r"; return v <= g ? "c-g" : v <= o ? "c-o" : "c-r"; }
  function kc(v) { return tc(v, M.T.kptG, M.T.kptO); }
  function wc(v) { return tc(v, M.T.rwtG, M.T.rwtO); }
  function ac(v) { return tc(v, M.T.frG, M.T.frO, 1); }
  function rtgt(p) { return p === "S" ? M.T.rS : p === "Z" ? M.T.rZ : M.T.rO; }
  function rc(v, p) { var t = rtgt(p); return tc(v, t, t - M.T.rBand, 1); }
  function rcAll(v) { var t = Math.min(M.T.rS, M.T.rZ); return tc(v, t, t - M.T.rBand, 1); }
  function okp(p) { return !M.p.length || M.p.indexOf(p) >= 0; }
  function okm(m) { return !M.m.length || M.m.indexOf(m) >= 0; }
  function learnedOn() { return M.T.useLearned && M.L && M.L.days >= (M.ops ? M.ops.learnDays : 30); }
  function lim(p, meal) {
    if (learnedOn() && M.L.early && M.L.early[p] && M.L.early[p][meal]) return M.L.early[p][meal];
    return p === "S" ? M.T.eS : p === "Z" ? M.T.eZ : M.T.eO;
  }

  // ---- ops orders --------------------------------------------------------------------------------
  function derive(r) {
    var o = { r: r, k: r.k, id: r.id || String(r.k), p: r.p, b: r.b || "", slot: r.sl || "", items: (r.it || []).join(", "), pu: r.pu, rd: r.rd, pk: r.pk, by: r.by || "", ar: r.ar };
    var d = new Date(r.pu); o.meal = mealH(d.getHours()); o.day = ymd(d);
    o.kpt = r.rd > r.pu && r.rd - r.pu < 4 * 3600000 ? (r.rd - r.pu) / 60000 : null;
    o.gap = r.pk && r.rd && r.pk >= r.rd ? (r.pk - r.rd) / 60000 : null;
    o.rwt = !r.pk ? null : r.ar ? Math.max(0, (r.pk - r.ar) / 60000) : o.gap;
    if (o.rwt != null && o.rwt > 240) o.rwt = null;
    if (!r.pk) o.fr = "wait";
    else if (!r.rd || r.rd > r.pk) o.fr = "none";
    else if (o.gap < M.T.pick) o.fr = "late";
    else if (o.gap > lim(o.p, o.meal)) o.fr = "early";
    else o.fr = "ok";
    return o;
  }
  function opsOrders() { return M.ops ? M.ops.list.filter(function (o) { return o.p && okp(o.p) && okm(o.meal); }) : []; }
  function sum(xs) {
    var k = xs.map(function (o) { return o.kpt; }).filter(function (v) { return v != null; });
    var w = xs.map(function (o) { return o.rwt; }).filter(function (v) { return v != null; });
    var done = xs.filter(function (o) { return o.fr !== "wait"; }), ok = done.filter(function (o) { return o.fr === "ok"; }).length;
    function n(f) { return xs.filter(function (o) { return o.fr === f; }).length; }
    return { n: xs.length, ak: avg(k), pk: q(k, 0.8), aw: avg(w), pw: q(w, 0.8), acc: done.length ? ok * 100 / done.length : null, late: n("late"), early: n("early"), none: n("none") };
  }
  function frChip(f) {
    return f === "ok" ? '<span class="chip ok">Accurate</span>' : f === "late" ? '<span class="chip warn">Marked late</span>' : f === "early" ? '<span class="chip warn">Early mark?</span>' :
      f === "none" ? '<span class="chip bad">Not marked</span>' : f === "wait" ? '<span class="chip">Not picked up yet</span>' : '<span class="mute">–</span>';
  }

  // ---- shared pieces -----------------------------------------------------------------------------
  function plRows(list, pOf, fn) {
    return PLAT.map(function (p) {
      var t = list.filter(function (x) { return pOf(x) === p; }); if (!t.length) return "";
      var r = fn(t, p);
      return '<span class="pf ' + p + '">' + p + '</span><span>' + PF[p] + '</span><span class="a ' + r[0] + '">' + r[1] + '</span><span class="b">' + (r[2] != null ? r[2] : "") + "</span>";
    }).join("");
  }
  function blk(label, vcls, v, unit, sub, p8l, p8v, rows, run, tone) {
    tone = tone || { "c-r": "r", "c-o": "o", "c-g": "g" }[vcls] || "n";
    return '<div class="blk neon ' + tone + (run ? " run" : "") + '"><div class="l">' + label + '</div><div class="v ' + vcls + '">' + v + "<small>" + unit + '</small></div><div class="s">' + sub +
      '</div><div class="p8"><span>' + p8l + "</span><b>" + p8v + '</b></div><div class="pl">' + rows + "</div></div>";
  }
  // a summary card with its own body (rating, ratings bars, complaints bars)
  function card(label, vcls, run, inner, tone) {
    tone = tone || { "c-r": "r", "c-o": "o", "c-g": "g" }[vcls] || vcls || "n";
    return '<div class="blk neon ' + tone + (run ? " run" : "") + '"><div class="l">' + label + "</div>" + inner + "</div>";
  }
  // Google-style bars: [label, count, tone] rows, bar length = share of the total
  function bars(rows, total) {
    return '<div class="hb">' + rows.map(function (r) {
      var w = total ? Math.round(r[1] * 100 / total) : 0;
      return '<span class="hl">' + r[0] + '</span><span class="ht"><i class="' + r[2] + '" style="width:' + w + '%"></i></span><span class="hn">' + r[1] + "</span>";
    }).join("") + "</div>";
  }
  function stars(m) {
    var pct = m == null ? 0 : Math.max(0, Math.min(100, m * 20));
    return '<span class="stars" aria-label="' + (m == null ? "no rating" : m.toFixed(2) + " of 5") + '"><span class="s0">★★★★★</span><span class="s1" style="width:' + pct + '%">★★★★★</span></span>';
  }
  function slotTable(key, title, list, pOf, mOf, cell) {
    function row(lbl, sub, t, tot) {
      return "<tr" + (tot ? ' class="tot"' : "") + "><td>" + lbl + (sub ? ' <span class="mute">' + sub + "</span>" : "") + "</td>" +
        PLAT.map(function (p) { return '<td class="num">' + cell(t.filter(function (x) { return pOf(x) === p; })) + "</td>"; }).join("") + '<td class="num">' + cell(t) + "</td></tr>";
    }
    var open = !!M["open_" + key];
    return '<details class="panel slotp" data-k="' + key + '"' + (open ? " open" : "") + '><summary class="hd"><h2>' + title + '</h2><span class="sub">' + list.length + " total · tap to " + (open ? "hide" : "show") +
      '</span></summary><div class="tw"><table class="slt"><thead><tr><th>Slot</th>' + PLAT.map(function (p) { return '<th class="num"><span class="pf ' + p + '">' + p + "</span>" + PF[p] + "</th>"; }).join("") +
      '<th class="num">Total</th></tr></thead><tbody>' + MEALS.map(function (m) { return row(m[1], m[2], list.filter(function (x) { return mOf(x) === m[0]; })); }).join("") + row("Total", null, list, 1) + "</tbody></table></div></details>";
  }
  function dash(v) { return v == null || v === "" ? '<span class="mute">–</span>' : v; }
  function tableCopy(id, csv) {
    var t = document.getElementById(id); if (!t) return;
    var rows = [].slice.call(t.querySelectorAll("tr")).filter(function (tr) { return !tr.classList.contains("det"); }).map(function (tr) {
      return [].slice.call(tr.children).map(function (c) { var s = c.innerText.replace(/\s*\n\s*/g, " ").trim(); return csv ? '"' + s.replace(/"/g, '""') + '"' : s; }).join(csv ? "," : "\t");
    }).join("\n");
    if (csv) {
      var a = document.createElement("a"); a.href = URL.createObjectURL(new Blob([rows], { type: "text/csv" })); a.download = id + "-" + range().join("_") + ".csv"; a.click();
    } else if (navigator.clipboard) navigator.clipboard.writeText(rows);
  }

  // ---- the frame: outlet, filters, learning ------------------------------------------------------
  function head(withLearn) {
    var o = M.ops || {}, r = range();
    var segD = [["today", "Today"], ["yesterday", "Yesterday"], ["7", "7 days"], ["range", "Date range"]].map(function (x) { return '<button aria-pressed="' + (M.day === x[0]) + '" data-d="' + x[0] + '">' + x[1] + "</button>"; }).join("");
    var segP = '<button aria-pressed="' + !M.p.length + '" data-p="">All</button>' + PLAT.map(function (p) { return '<button aria-pressed="' + (M.p.indexOf(p) >= 0) + '" data-p="' + p + '">' + PF[p] + "</button>"; }).join("");
    var segM = '<button aria-pressed="' + !M.m.length + '" data-m="">All day</button>' + MEALS.map(function (m) { return '<button aria-pressed="' + (M.m.indexOf(m[0]) >= 0) + '" data-m="' + m[0] + '">' + m[1] + "</button>"; }).join("");
    var out = '<div class="bar"><span class="ol' + (o.outlet ? "" : " off") + '">● ' + (o.outlet ? esc((o.outletName || o.outlet) + " (" + o.outlet + ")") : "Outlet not set") + '</span><span class="mute">' +
      (o.outlet ? (RO ? "from the outlet PC" : "found from this PC's outlet setting") : "set the outlet name in Hub › Petpooja & Firebase") + "</span>" +
      (!RO && o.lan && o.lan.length ? '<span class="grow"></span><span class="mute" title="Other computers and phones on the shop Wi-Fi can open this (view only, with the PIN)">On the Wi-Fi: <b>' + esc(o.lan[0]) + "</b></span>" : "") + "</div>" +
      '<div class="flt">' + (RO ? "" : '<button class="gear" id="mxGear" title="Targets and colours" aria-label="Targets and colours">⚙</button>') + '<div class="seg" id="mxD">' + segD + "</div>" +
      '<span class="rng" id="mxR"' + (M.day === "range" ? "" : " hidden") + '><input type="date" id="mxF" value="' + (M.from || r[0]) + '" aria-label="From"> to <input type="date" id="mxT" value="' + (M.to || r[1]) + '" aria-label="To"></span>' +
      '<div class="seg" id="mxP">' + segP + '</div><div class="seg" id="mxM">' + segM + "</div></div>";
    if (withLearn && M.L) {
      var need = o.learnDays || 30, d = Math.min(M.L.days || 0, need);
      out += '<div class="learn"><b>Learning</b><span class="bar2"><i style="width:' + Math.round(d * 100 / need) + '%"></i></span><span>day ' + d + " of " + need + " · " +
        (learnedOn() ? "using learned early-mark limits" : M.T.useLearned ? "using starting limits (Swiggy " + M.T.eS + " min, Zomato " + M.T.eZ + " min) until a month of orders is in" : "using your limits (learned limits off)") +
        '</span><span class="grow"></span><span class="mute">keeps the last ' + (o.keepDays || 120) + " days on this PC</span></div>";
    }
    return out;
  }
  function bindHead(redraw) {
    $("mxD").onclick = function (e) {
      var b = e.target.closest("button"); if (!b) return; M.day = b.dataset.d;
      if (M.day === "range" && !M.from) { var r = range(); M.from = r[0]; M.to = r[1]; }
      M.openRow = null; fetchAll(redraw);
    };
    $("mxF").onchange = $("mxT").onchange = function () { M.from = $("mxF").value; M.to = $("mxT").value; if (M.from && M.to) fetchAll(redraw); };
    function multi(id, key, attr) {
      $(id).onclick = function (e) {
        var b = e.target.closest("button"); if (!b) return; var v = b.getAttribute(attr), a = M[key];
        if (!v) M[key] = []; else { var i = a.indexOf(v); if (i >= 0) a.splice(i, 1); else a.push(v); if (a.length === this.querySelectorAll("button").length - 1) M[key] = []; }
        M.openRow = null; redraw();
      };
    }
    multi("mxP", "p", "data-p"); multi("mxM", "m", "data-m");
    if ($("mxGear")) $("mxGear").onclick = openTargets;
  }

  // ---- data ------------------------------------------------------------------------------------
  function fetchAll(redraw, quiet) {
    var r = range(), key = r.join("_");
    var a = getJ(API + "ops?from=" + r[0] + "&to=" + r[1]).then(function (d) {
      M.T = d.targets; M.L = d.learn; d.list = (d.rows || []).map(derive); M.ops = d; M.opsKey = key;
    });
    var b = getJ(API + "cx" + (quiet ? "" : "")).then(function (d) {
      if (!d.ok) { M.cx = { err: d.msg, rows: [] }; return; }
      M.cx = { rows: (d.rows || []).map(function (row) { var o = {}; d.cols.forEach(function (c, i) { o[c] = row[i]; }); return o; }), updated: d.updated, stale: d.stale };
    }).catch(function () { M.cx = { err: "Can't reach the ratings sheet.", rows: [] }; });
    return Promise.all([a, b]).then(function () { redraw(); }).catch(function () { if (!quiet) $("main").innerHTML = '<p class="empty">Could not load the metrics. Is the Console running?</p>'; });
  }

  // ---- order details (both tabs) ---------------------------------------------------------------
  function cxFor(o) {
    if (!M.cx || !o) return null;
    var d = digits(o.id);
    for (var i = 0; i < M.cx.rows.length; i++) { var c = M.cx.rows[i]; if (d && (digits(c.id) === d || digits(c.sid) === d)) return c; }
    return null;
  }
  function opsFor(c) {
    if (!M.ops) return null;
    var a = digits(c.id), b = digits(c.sid);
    for (var i = 0; i < M.ops.list.length; i++) { var o = M.ops.list[i], d = digits(o.id); if (d && (d === a || d === b)) return o; }
    return null;
  }
  function cause(o, c, L) {
    var fl = [], txt = String((c && (c.text || c.feedback)) || "").toLowerCase(), wait = o ? o.gap : null, kpt = o ? o.kpt : c && c.kpt;
    var usual = L && L.usualKpt || null, busy = L && L.usualInKitchen ? L.inKitchen >= L.usualInKitchen * 1.4 && L.inKitchen >= 4 : false;
    var slow = kpt != null && kpt > M.T.kptO, mid = kpt != null && kpt > M.T.kptG && !slow;
    if (kpt != null) fl.push(slow ? ["r", "Slow kitchen" + (usual ? " +" + f1(kpt - usual) + " min" : " " + f1(kpt) + " min")] : mid ? ["o", "KPT above target"] : ["g", "KPT on target"]);
    if (busy) fl.push(["o", "Rush: " + L.inKitchen + " orders in kitchen"]);
    if (o && o.fr === "early") fl.push(["r", "Early Food Ready mark"]);
    if (o && o.fr === "late") fl.push(["o", "Food Ready marked late"]);
    if (o && o.fr === "none") fl.push(["r", "Not marked"]);
    if (wait != null && wait > M.T.rwtO) fl.push(["o", "Food waited " + f1(wait) + " min"]);
    if (c && c.ctype) fl.push([c.ctype === "New" ? "o" : "g", c.ctype + " customer"]);
    if (c && c.check && /^Check/.test(c.check)) fl.push(["r", c.check]);
    var head, sub = "", lvl = "ok";
    if (c && (c.complaint || (c.rating != null && c.rating <= 3))) {
      var said = c.text || c.feedback || c.complaint || "";
      var ty = c.complaint ? ctype(c) : -1, kTxt = slow ? "slow (" + f1(kpt) + " min)" : mid ? "above target (" + f1(kpt) + " min)" : "on target";
      if (ty === 0 || ty === 1) { lvl = "bad"; head = "Packing check: " + (ty === 0 ? "missing item" : "wrong item") + (c.item ? " — " + c.item : "") + (o && o.by ? ". Marked ready by " + o.by : "") + (busy ? " during a rush" : "") + "."; sub = "Kitchen time was " + kTxt + "; check the packing station for this slot."; }
      else if (ty === 3) { lvl = "mid"; head = "Packaging: " + (said || "container or spill") + "."; sub = "Kitchen time was " + kTxt + (wait != null ? "; picked up " + f1(wait) + " min after Food Ready." : "."); }
      else if (/cold|soggy|crisp/.test(txt)) { lvl = "bad"; head = wait != null && wait > M.T.rwtG ? "Food sat " + f1(wait) + " min after Food Ready before the rider took it — likely why it was “" + said + "”." : slow || mid ? "Kitchen time " + kTxt + " — likely why it was “" + said + "”." : "Kitchen and pickup times were on target — check the item itself (batch, tawa heat) and the packing."; }
      else if (/late|delay|time/.test(txt)) { lvl = "bad"; head = slow ? "Kitchen was slow (" + f1(kpt) + " min) — the main part of the delay." : wait != null && wait > M.T.rwtO ? "Kitchen was on time; the order waited " + f1(wait) + " min for the rider." : "Kitchen and pickup were on time — the delay was in delivery."; }
      else if (ty === 2) { lvl = "bad"; head = "Quality / taste" + (c.item ? " — " + c.item : "") + (said ? ": “" + said + "”" : "") + "."; sub = "Kitchen time was " + kTxt + (o && o.fr === "early" ? "; Food Ready was probably marked early, so it waited." : wait != null && wait > M.T.rwtO ? "; it waited " + f1(wait) + " min for the rider." : "."); }
      else if (c.complaint) { lvl = "mid"; head = (c.cause || "Complaint: " + c.complaint) + "."; sub = "Kitchen time was " + kTxt + "."; }
      else { lvl = "mid"; head = "Rated " + c.rating + "★" + (said ? " — “" + said + "”" : " with no comment") + ". " + (slow ? "Slow kitchen (" + f1(kpt) + " min) is the likely cause." : o && o.fr === "early" ? "Food was probably marked ready early, so it waited." : wait != null && wait > M.T.rwtO ? "Food waited " + f1(wait) + " min for the rider." : mid ? "Kitchen time " + f1(kpt) + " min was above the " + M.T.kptG + " min target; pickup was on time." : "Kitchen and pickup were on time — likely taste or delivery."); }
    } else if (o) {
      lvl = slow || o.fr === "early" || o.fr === "none" ? "bad" : mid || o.fr === "late" ? "mid" : "ok";
      head = slow ? "Slow: " + f1(kpt) + " min" + (usual ? " against ≈" + f1(usual) + " usual" : "") + (busy ? " during a rush" : "") + "." : o.fr === "early" ? "Food Ready probably pressed before the food was ready (picked up " + f1(wait) + " min later)." :
        o.fr === "none" ? "Food Ready was never marked." : mid ? "KPT " + f1(kpt) + " min, above the " + M.T.kptG + " min target." : o.fr === "late" ? "Food Ready tapped only when the rider came." : o.fr === "wait" ? "Not picked up yet." : "All good: on time, accurate Food Ready.";
    } else { lvl = "mid"; head = c && c.cause || "No kitchen times from this PC for this order."; }
    return [lvl, head, sub, fl];
  }
  function details(o, c, L) {
    var k = cause(o, c, L), cs = "";
    if (c && c.customer) cs = esc(c.customer) + (c.check ? " · " + esc(c.check) : "");
    var tl = o ? '<div class="tl"><div class="st"><i>Punched (KOT)</i><b>' + hm(o.pu) + "</b>" + dmon(o.pu) + "</div>" +
      '<div class="st"><i>KOT printed</i><b>' + hm(o.r.pr) + "</b>" + (o.r.pr && o.pu ? Math.max(0, (o.r.pr - o.pu) / 1000).toFixed(1) + " s after" : "") + "</div>" +
      '<div class="st k"><i>Food ready</i><b>' + hm(o.rd) + "</b>" + (o.kpt != null ? "KPT " + f1(o.kpt) + " min" : "") + "</div>" +
      '<div class="st"><i>Rider arrived (learned)</i><b>' + (o.ar ? "≈" + hm(o.ar) : "–") + "</b>" + (o.ar ? "estimate" : "learning") + "</div>" +
      '<div class="st k"><i>Picked up</i><b>' + hm(o.pk) + "</b>" + (o.gap != null ? f1(o.gap) + " min after ready" : "") + "</div></div>" : "";
    var said = c && (c.text || c.feedback);
    return '<div class="dbox"><div class="verdict ' + k[0] + '"><span class="ic">' + (k[0] === "ok" ? "✓" : "!") + "</span><div>" + esc(k[1]) + (k[2] ? "<small>" + esc(k[2]) + "</small>" : "") + "</div></div>" +
      (c && c.photoUrl ? '<div class="ph"><a href="' + esc(c.photoUrl) + '" target="_blank" rel="noopener" title="Customer photo (opens full size)"><img src="' + esc(c.photoUrl) + '" alt="Customer photo" loading="lazy" onerror="this.remove()"><span>Photo</span></a></div>' : "") +
      (c && (said || c.complaint) ? '<div class="quote">' + (c.rating != null ? c.rating + "★ · " : "") + (c.complaint ? esc(c.complaint) + " · " : "") + (said ? "“" + esc(said) + "”" : "") + (c.refund ? " · refund ₹" + c.refund : "") + "</div>" : "") +
      '<div class="flags">' + k[3].map(function (f) { return '<span class="flag ' + f[0] + '">' + esc(f[1]) + "</span>"; }).join("") + "</div>" + tl +
      (o ? '<button class="ldb">Kitchen load at that time ▸</button><div class="ld" hidden></div>' : "") +
      '<div class="dg"><div><span>Items</span>' + esc(o ? o.items : c && c.items || "–") + "</div><div><span>Brand · slot</span>" + esc(o ? (o.b || "–") + " · " + (o.slot || "–") : (c && c.slot) || "–") + "</div>" +
      (o ? "<div><span>Food ready</span>" + frChip(o.fr) + (o.by ? " by " + esc(o.by) : "") + "</div>" : "") + (cs ? "<div><span>Customer</span>" + cs + "</div>" : "") + "</div></div>";
  }
  function loadHtml(o, L) {
    if (!L) return '<p class="mute">Loading…</p>';
    var pct = L.usualInKitchen >= 2 ? Math.round((L.inKitchen / L.usualInKitchen - 1) * 100) : null, P = L.plat || {};
    return '<div class="vol"><div><b>' + L.vol + "</b>orders " + hm(L.from) + "–" + hm(L.to) + "</div>" +
      (L.usualVol >= 0.5 ? "<div><b>" + Math.round(L.usualVol) + "</b>usual in these 30 min</div>" : "") +
      "<div><b>" + (P.S || 0) + " · " + (P.Z || 0) + " · " + (P.O || 0) + " · " + (P.D || 0) + "</b>Swiggy · Zomato · Ownly · dine-in</div>" +
      "<div><b" + (pct != null ? ' class="' + (pct > 25 ? "c-r" : pct > 0 ? "c-o" : "c-g") + '"' : "") + ">" + L.inKitchen + (pct != null ? " (" + (pct > 0 ? "+" : "") + pct + "%)" : "") + "</b>orders in kitchen at punch" + (L.usualInKitchen >= 0.5 ? ", usual ≈" + Math.round(L.usualInKitchen) : "") + "</div></div>" +
      (L.dosaQueue ? '<div class="tawa">Dosa tawa: <b>' + Math.round(L.dosaQueue) + " dosa" + (L.dosaQueue >= 2 ? "s" : "") + "</b> waiting when this order was punched ≈ " + Math.ceil(L.dosaQueue / 8) + " round" + (Math.ceil(L.dosaQueue / 8) > 1 ? "s" : "") + " (8 a round, ~3 min each).</div>" : "") +
      '<div class="tw"><table><thead><tr><th>Item (all orders in these 30 min)</th><th class="num">Qty</th><th class="num">Waiting at punch</th><th class="num">Avg prep then</th><th class="num">Usual</th></tr></thead><tbody>' +
      (L.items || []).map(function (r) {
        var me = o.items.indexOf(r.n) >= 0;
        return "<tr" + (me ? ' class="me"' : "") + "><td>" + esc(r.n) + (me ? ' <span class="chip acc">this order</span>' : "") + '</td><td class="num">' + r.q + '</td><td class="num">' + r.w + '</td><td class="num ' +
          (r.a && r.u ? (r.a > r.u * 1.3 ? "c-r" : r.a > r.u * 1.1 ? "c-o" : "c-g") : "") + '"><b>' + (r.a ? f1(r.a) : "–") + '</b></td><td class="num mute">' + (r.u ? f1(r.u) : "–") + "</td></tr>";
      }).join("") + "</tbody></table></div>";
  }
  // one open row at a time; tap again closes
  function bindRows(rootId, find) {
    var root = $(rootId); if (!root) return;
    root.addEventListener("click", function (e) {
      var lb = e.target.closest(".ldb");
      if (lb) {
        var box = lb.nextElementSibling;
        if (!box.hidden) { box.hidden = true; lb.textContent = "Kitchen load at that time ▸"; return; }
        box.hidden = false; lb.textContent = "Kitchen load at that time ▾";
        var o = lb._o; box.innerHTML = loadHtml(o, lb._L);
        if (!lb._L) getJ(API + "opsload?kot=" + o.k + "&day=" + o.day).then(function (L) { lb._L = L; box.innerHTML = loadHtml(o, L); }).catch(function () { box.innerHTML = '<p class="mute">Kitchen load not available for this order.</p>'; });
        return;
      }
      var cp = e.target.closest("[data-copy]"); if (cp) { tableCopy(cp.dataset.copy, cp.dataset.csv === "1"); return; }
      var tr = e.target.closest("tr.row"); if (!tr) return;
      var was = tr.classList.contains("open");
      root.querySelectorAll("tr.det").forEach(function (x) { x.remove(); });
      root.querySelectorAll("tr.row.open").forEach(function (x) { x.classList.remove("open"); });
      M.openRow = null; if (was) return;
      var f = find(tr), o = f[0], c = f[1];
      var d = document.createElement("tr"); d.className = "det";
      d.innerHTML = '<td colspan="' + tr.children.length + '">' + details(o, c, null) + "</td>"; tr.after(d); tr.classList.add("open"); M.openRow = tr.dataset.key;
      var btn = d.querySelector(".ldb"); if (btn) btn._o = o;
      if (o) getJ(API + "opsload?kot=" + o.k + "&day=" + o.day).then(function (L) {
        if (!d.isConnected) return; var keep = d.querySelector(".ld"), open = keep && !keep.hidden;
        d.firstChild.innerHTML = details(o, c, L); var b2 = d.querySelector(".ldb"); b2._o = o; b2._L = L;
        if (open) { b2.nextElementSibling.hidden = false; b2.textContent = "Kitchen load at that time ▾"; b2.nextElementSibling.innerHTML = loadHtml(o, L); }
      }).catch(function () {});
    });
    root.addEventListener("toggle", function (e) {
      var dd = e.target; if (!dd.matches || !dd.matches("details.slotp")) return; M["open_" + dd.dataset.k] = dd.open;
      var s = dd.querySelector("summary .sub"); if (s) s.textContent = s.textContent.replace(/tap to \w+/, "tap to " + (dd.open ? "hide" : "show"));
    }, true);
  }

  // ---- Ops metrics -------------------------------------------------------------------------------
  function drawOps() {
    if (!M.ops) return;
    var xs = opsOrders(), s = sum(xs), byP = function (o) { return o.p; };
    var h = head(true) + '<div class="panel"><div class="hd"><h2>Summary</h2><span class="sub">all platforms, then each platform · P80 below each average</span></div><div class="sum4">' +
      blk("KPT · average", kc(s.ak), AP + f1(s.ak), "min", "order accepted → food ready", "P80", AP + f1(s.pk) + " min", plRows(xs, byP, function (t) { var u = sum(t); return [kc(u.ak), f1(u.ak), "P80 " + f1(u.pk)]; }), 1) +
      blk("RWT · average", wc(s.aw), AP + f1(s.aw), "min", "rider arrival (learned) → picked up", "P80", AP + f1(s.pw) + " min", plRows(xs, byP, function (t) { var u = sum(t); return [wc(u.aw), f1(u.aw), "P80 " + f1(u.pw)]; })) +
      blk("Food ready accuracy", ac(s.acc), AP + (s.acc == null ? "–" : Math.round(s.acc)), "%", "target " + M.T.frG + "%", "Late · early · not marked", s.late + " · " + s.early + " · " + s.none, plRows(xs, byP, function (t) { var u = sum(t); return [ac(u.acc), u.acc == null ? "–" : Math.round(u.acc) + "%", u.late + " · " + u.early + " · " + u.none]; }), 1) +
      blk("Orders", "", s.n, "", "platform orders", "Delayed (KPT over " + M.T.late + " min)", xs.filter(function (o) { return o.kpt > M.T.late; }).length, plRows(xs, byP, function (t) { return ["", t.length, t.filter(function (o) { return o.kpt > M.T.late; }).length + " delayed"]; })) +
      "</div></div>";
    h += slotTable("ops", "By slot · orders and average KPT", xs, byP, function (o) { return o.meal; }, function (t) {
      if (!t.length) return '<span class="mute">–</span>'; var k = avg(t.map(function (o) { return o.kpt; }).filter(function (v) { return v != null; }));
      return "<b>" + t.length + '</b><small class="' + kc(k) + '">' + (k == null ? "–" : f1(k) + "m") + "</small>";
    });
    var many = xs.length > 400;
    h += '<div class="panel"><div class="hd"><h2>Order by order</h2><span class="sub">' + xs.length + " orders · newest first · tap a row for the full order" + (many ? " · showing the newest 400 (Copy / CSV take all)" : "") +
      '</span><button class="tbtn" data-copy="mxOpsT">Copy</button><button class="tbtn" data-copy="mxOpsT" data-csv="1">CSV</button></div><div class="tw"><table id="mxOpsT"><thead><tr><th>Order ID</th><th class="c">Pf</th><th>Brand</th><th class="num">Slot</th><th>Items</th><th>Punched</th><th>Ready</th><th>Picked up</th><th class="num">KPT</th><th class="num">RWT</th><th>Ready status</th><th>Marked by</th></tr></thead><tbody>' +
      xs.map(function (o, i) {
        return '<tr class="row' + (i >= 400 ? " more" : "") + '" data-key="k' + o.k + '"><td class="id">' + esc(o.id) + '</td><td class="c"><span class="pf ' + o.p + '">' + o.p + "</span></td><td>" + esc(o.b || "–") + '</td><td class="num">' + esc(o.slot || "–") +
          '</td><td class="items">' + esc(o.items) + "</td><td>" + hm(o.pu) + '<span class="dt">' + dmon(o.pu) + "</span></td><td>" + hm(o.rd) + "</td><td>" + hm(o.pk) + '</td><td class="num">' + f1(o.kpt) + '</td><td class="num">' + f1(o.rwt) +
          "</td><td>" + frChip(o.fr) + '</td><td class="mute">' + esc(o.by || "—") + "</td></tr>";
      }).join("") + (xs.length ? "" : '<tr><td colspan="12" class="empty">No platform orders in this period.</td></tr>') + "</tbody></table></div></div>";
    $("main").innerHTML = '<div class="mx" id="mxRoot">' + h + "</div>" + (RO ? "" : targetsDialog());
    bindHead(drawOps); bindTargets();
    bindRows("mxRoot", function (tr) { var k = +tr.dataset.key.slice(1), o = M.ops.list.filter(function (x) { return x.k === k; })[0]; return [o, cxFor(o)]; });
  }

  // ---- CX metrics --------------------------------------------------------------------------------
  function pcode(s) { s = String(s || "").toLowerCase(); return s.indexOf("swiggy") >= 0 ? "S" : s.indexOf("zomato") >= 0 ? "Z" : "O"; }
  function cxRows() {
    var r = range();
    return (M.cx && M.cx.rows || []).filter(function (c) {
      c._p = pcode(c.platform); var hh = c.time ? +String(c.time).split(":")[0] : null;
      c._m = hh != null && !isNaN(hh) ? mealH(hh) : ({ Breakfast: "B", Lunch: "L", Snacks: "S", Dinner: "D" })[String(c.meal || "").split(" ")[0]] || "";
      return c.day && c.day >= r[0] && c.day <= r[1] && okp(c._p) && okm(c._m);
    });
  }
  function ctype(c) { var t = String(c.complaint || "").toUpperCase(); return t.indexOf("MISS") >= 0 ? 0 : t.indexOf("WRONG") >= 0 ? 1 : t.indexOf("QUAL") >= 0 || t.indexOf("TASTE") >= 0 ? 2 : t.indexOf("PACK") >= 0 ? 3 : 4; }
  function drawCx() {
    if (!M.ops || !M.cx) return;
    var xs = cxRows(), byP = function (c) { return c._p; };
    function rs(t) { var r = t.filter(function (v) { return v.rating != null; }); return { r: r, m: avg(r.map(function (v) { return +v.rating; })), low: r.filter(function (v) { return v.rating <= 3; }), c: t.filter(function (v) { return v.complaint; }) }; }
    var A = rs(xs), refund = A.c.reduce(function (a, v) { return a + (+v.refund || 0); }, 0);
    var unhappy = xs.filter(function (c) { return c.complaint || (c.rating != null && c.rating <= 3); });
    function kptOf(c) { var o = opsFor(c); return o && o.kpt != null ? o.kpt : c.kpt != null ? +c.kpt : null; }
    var uk = avg(unhappy.map(kptOf).filter(function (v) { return v != null; }));
    var allK = avg(M.ops.list.filter(function (o) { return o.p && okp(o.p) && okm(o.meal) && o.kpt != null; }).map(function (o) { return o.kpt; }));
    var h = head(false);
    if (M.cx.err) h += '<div class="banner">' + esc(M.cx.err) + "</div>";
    h += '<div class="panel"><div class="hd"><h2>Summary</h2><span class="sub">all platforms, then each platform' + (M.cx.updated ? " · sheet updated " + hm(Date.parse(M.cx.updated)) : "") + '</span></div><div class="sum4">' +
      card("Rating · average", M.p.length === 1 ? rc(A.m, M.p[0]) : rcAll(A.m), 1,
        '<div class="rbig"><span class="v ' + (M.p.length === 1 ? rc(A.m, M.p[0]) : rcAll(A.m)) + '">' + (A.m == null ? "–" : A.m.toFixed(2)) + "</span>" + stars(A.m) + "</div>" +
        '<div class="s">' + A.r.length + " rated orders</div>" +
        '<div class="pl pl3">' + PLAT.map(function (p) { var u = rs(xs.filter(function (c) { return c._p === p; })); if (!u.r.length) return "";
          return '<span class="pf ' + p + '">' + p + "</span><span>" + PF[p] + '</span><span class="a ' + rc(u.m, p) + '">' + u.m.toFixed(2) + '</span><span class="b">target ' + rtgt(p) + "</span>"; }).join("") + "</div>") +
      card("Ratings", A.low.length ? "o" : "g", 0,
        '<div class="s">' + A.r.length + " rated · " + A.low.length + " low (1–3★) · " + (A.r.length ? Math.round(A.low.length * 100 / A.r.length) : 0) + "%</div>" +
        bars([5, 4, 3, 2, 1].map(function (k) { return [k + "★", A.r.filter(function (v) { return v.rating == k; }).length, k >= 4 ? "g" : k === 3 ? "o" : "r"]; }), A.r.length) +
        '<div class="pl pl3">' + PLAT.map(function (p) { var u = rs(xs.filter(function (c) { return c._p === p; })); if (!u.r.length) return "";
          return '<span class="pf ' + p + '">' + p + "</span><span>" + PF[p] + '</span><span class="a">' + u.low.length + '</span><span class="b">low of ' + u.r.length + "</span>"; }).join("") + "</div>", A.low.length ? "o" : "g") +
      card("Complaints", A.c.length ? "r" : "g", 1,
        '<div class="rbig"><span class="v">' + A.c.length + '</span><span class="unit">refund ₹' + Math.round(refund) + "</span></div>" +
        bars([["Missing item", 0], ["Wrong item", 1], ["Quality / taste", 2], ["Packaging", 3], ["Other", 4]].map(function (t) { return [t[0], A.c.filter(function (v) { return ctype(v) === t[1]; }).length, "r"]; }), A.c.length) +
        '<div class="pl pl3">' + PLAT.map(function (p) { var u = rs(xs.filter(function (c) { return c._p === p; })); if (!u.c.length) return "";
          return '<span class="pf ' + p + '">' + p + "</span><span>" + PF[p] + '</span><span class="a">' + u.c.length + '</span><span class="b">₹' + Math.round(u.c.reduce(function (a, v) { return a + (+v.refund || 0); }, 0)) + "</span>"; }).join("") + "</div>", A.c.length ? "r" : "g") +
      blk("KPT on unhappy orders · avg", kc(uk), f1(uk), "min", "low rated + complaints", "All orders avg", f1(allK) + " min",
        plRows(xs, byP, function (t) { var u = rs(t), k = avg(u.low.concat(u.c).map(kptOf).filter(function (v) { return v != null; })); return [kc(k), f1(k), null]; })) +
      "</div></div>";
    h += slotTable("cx", "By slot · average rating and issues (1–3★ + complaints)", xs, byP, function (c) { return c._m; }, function (t) {
      if (!t.length) return '<span class="mute">–</span>'; var u = rs(t), i = t.filter(function (v) { return v.complaint || (v.rating != null && v.rating <= 3); }).length;
      return '<b class="' + (u.m != null ? rcAll(u.m) : "") + '">' + (u.m != null ? u.m.toFixed(2) + "★" : "–") + '</b><small class="' + (i ? "c-r" : "mute") + '">' + i + " issue" + (i === 1 ? "" : "s") + "</small>";
    });
    unhappy.sort(function (a, b) { return (b.day + (b.time || "")).localeCompare(a.day + (a.time || "")); });
    h += '<div class="panel"><div class="hd"><h2>Low ratings and complaints</h2><span class="sub">' + unhappy.length + ' orders · tap a row for the full order</span><button class="tbtn" data-copy="mxCxT">Copy</button><button class="tbtn" data-copy="mxCxT" data-csv="1">CSV</button></div>' +
      '<div class="tw"><table id="mxCxT"><thead><tr><th>Order ID</th><th class="c">Pf</th><th>Punched</th><th>Rating</th><th>Complaint / comment</th><th class="num">Refund</th><th>Items</th><th class="num">KPT</th><th class="num">RWT</th><th>Ready status</th></tr></thead><tbody>' +
      unhappy.map(function (c, i) {
        var o = opsFor(c), said = c.text || c.feedback || "";
        return '<tr class="row" data-key="c' + i + '"><td class="id">' + esc(c.id) + '</td><td class="c"><span class="pf ' + c._p + '">' + c._p + "</span></td><td>" + esc(c.time || "–") + '<span class="dt">' + esc(c.day ? dmon(Date.parse(c.day + "T12:00:00")) : "") + "</span></td><td>" +
          (c.complaint ? '<span class="chip bad">Complaint</span>' : "") + (c.rating != null ? ' <span class="star' + (c.rating <= 3 ? " lo" : "") + '">' + c.rating + "★</span>" : "") + '</td><td class="items" style="min-width:160px">' + (c.complaint ? "<b>" + esc(c.complaint) + "</b>" + (said ? " · " : "") : "") + (said ? esc(said) : c.complaint ? "" : '<span class="mute">–</span>') +
          '</td><td class="num">' + (c.refund ? "₹" + Math.round(c.refund) : '<span class="mute">–</span>') + '</td><td class="items">' + esc(o ? o.items : c.items || "–") + '</td><td class="num">' + f1(o && o.kpt != null ? o.kpt : c.kpt) +
          '</td><td class="num">' + f1(o ? o.rwt : c.wait) + "</td><td>" + (o ? frChip(o.fr) : '<span class="mute">–</span>') + "</td></tr>";
      }).join("") + (unhappy.length ? "" : '<tr><td colspan="10" class="empty">No low ratings or complaints in this period.</td></tr>') + "</tbody></table></div></div>";
    $("main").innerHTML = '<div class="mx" id="mxRoot">' + h + "</div>" + (RO ? "" : targetsDialog());
    bindHead(drawCx); bindTargets();
    bindRows("mxRoot", function (tr) { var c = unhappy[+tr.dataset.key.slice(1)]; return [opsFor(c), c]; });
  }

  // ---- ⚙ targets ---------------------------------------------------------------------------------
  function targetsDialog() {
    function inp(k, step) { return '<input data-k="' + k + '" type="number" step="' + (step || "0.5") + '">'; }
    var le = M.L && M.L.early || {}, mealTxt = function (p) { var m = le[p]; return m ? Object.keys(m).map(function (x) { return MEALNAME[x][0] + " " + m[x]; }).join(" · ") : "–"; };
    return '<dialog id="mxDlg"><form class="dlg" method="dialog"><h2>Targets and colours<span class="grow"></span><span class="mute" style="font-size:12px;font-weight:400">saved on this PC</span></h2><div class="tg">' +
      '<span class="h">Average (lower is better)</span><span class="h c-g">Green up to</span><span class="h c-o">Orange up to</span>' +
      "<span>KPT, min (above orange = red)</span>" + inp("kptG") + inp("kptO") + "<span>RWT, min</span>" + inp("rwtG") + inp("rwtO") +
      '<span class="h">Higher is better</span><span class="h c-g">Green from</span><span class="h c-o">Orange from</span><span>Food ready accuracy, %</span>' + inp("frG", "1") + inp("frO", "1") +
      '<span class="h">Rating targets</span><span class="h">Swiggy</span><span class="h">Zomato</span><span>Green at or above target</span>' + inp("rS", "0.05") + inp("rZ", "0.05") +
      "<span>Ownly target · orange band below target</span>" + inp("rO", "0.05") + inp("rBand", "0.05") +
      "<span>Delayed order when KPT is over (min)</span>" + inp("late", "1") + "<span></span>" +
      '<span>"Marked late" when Food Ready is marked less than this many min before pickup</span>' + inp("pick") + "<span></span>" +
      '<span>"Early mark?" when the rider picks up more than this many min after Food Ready · Swiggy / Zomato</span>' + inp("eS") + inp("eZ") + "<span>… Ownly</span>" + inp("eO") + "<span></span>" +
      '<span class="h">Learned from this outlet</span><span class="h">Swiggy</span><span class="h">Zomato</span><span>Early mark limit by meal (after 30 days)</span><span class="lv">' + mealTxt("S") + '</span><span class="lv">' + mealTxt("Z") + "</span>" +
      '<label class="sw2"><input type="checkbox" id="mxUL"> Use the learned limits once a month of data is in (untick to always use the numbers above)</label></div>' +
      '<p class="msg" id="mxTMsg"></p><div class="acts"><button value="reset" type="button" id="mxTReset">Reset</button><button value="cancel">Cancel</button><button value="save" class="pri" id="mxTSave" type="button">Save</button></div></form></dialog>';
  }
  function openTargets() {
    var d = $("mxDlg"); d.querySelectorAll("input[data-k]").forEach(function (i) { i.value = M.T[i.dataset.k]; }); $("mxUL").checked = !!M.T.useLearned; $("mxTMsg").textContent = ""; d.showModal();
  }
  function bindTargets() {
    if (RO) return;
    function save(t) {
      post(API + "metrics/targets", t).then(function (r) {
        if (!r.ok) { $("mxTMsg").className = "msg bad"; $("mxTMsg").textContent = r.msg; return; }
        M.T = t; M.ops.list = M.ops.rows.map(derive); $("mxDlg").close(); VIEWS[tab] && VIEWS[tab].draw();
      });
    }
    $("mxTSave").onclick = function () { var t = Object.assign({}, M.T); $("mxDlg").querySelectorAll("input[data-k]").forEach(function (i) { var n = parseFloat(i.value); if (!isNaN(n)) t[i.dataset.k] = n; }); t.useLearned = $("mxUL").checked; save(t); };
    $("mxTReset").onclick = function () { getJ(API + "metrics/targets").then(function (d) { save(d.defaults); }); };
  }

  // ---- views ------------------------------------------------------------------------------------
  function view(draw) {
    return {
      draw: draw,
      html: function () { return '<p class="empty">Loading metrics…</p>'; },
      init: function () { M.openRow = null; this._first = true; },
      load: function () {
        var first = this._first; this._first = false;
        // every minute: only while the period includes today and no order or ⚙ is open
        if (!first && (document.querySelector("#main tr.det") || document.querySelector("#mxDlg[open]") || range()[1] !== ymd(new Date()))) return;
        fetchAll(draw, !first);
      },
      every: 60000
    };
  }
  VIEWS.cx = view(drawCx);
  VIEWS.ops = view(drawOps);
  window.MetricsReset = function () { M.day = "today"; M.from = M.to = ""; M.p = []; M.m = []; M.open_ops = M.open_cx = false; };
})();
