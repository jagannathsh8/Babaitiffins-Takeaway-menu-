/* Babai Indent Planner – the calculations (no page code, so they can be tested on their own).
 *
 * BOM (Google Sheet "Master BOM", one row per ingredient):
 *   Product Name | Ingredients | SKUs | Ingredients UOM | Cost/Unit | RM Yield % | Recipe Qty | BOM Qty | Yield | Product UOM | Product SKU
 *   Ingredient kind by SKU: 1xxx raw material (RM), 2xxx central kitchen prep (CP), 3xxx outlet prep (OP).
 *   A dish uses CP / OP / RM per portion; an OP item is made at the outlet from its own rows (per 1 unit).
 *
 * Sales (from each outlet's Babai Hub): per date, dish -> qty per hour.
 * Projection for a date: same weekday 1-4 weeks back (past Sundays on a holiday),
 *   typical = 60% x 4 weeks back + 20% x average of the 4 + 20% x average of the latest 2,
 *   x recent pace (last 3 days vs their typical, half weight), + buffer, rounded to the unit.
 */
(function (root) {
  "use strict";

  var DROPS = [{ id: "d1", label: "6:30", from: 0, to: 10 }, { id: "d2", label: "11:00", from: 11, to: 16 },
    { id: "d3", label: "5:30", from: 17, to: 23 }];

  function norm(s) {
    return String(s == null ? "" : s).toLowerCase().replace(/&/g, " and ").replace(/[^a-z0-9]+/g, " ").trim();
  }
  function num(v) {
    if (typeof v === "number") return isFinite(v) ? v : 0;
    var s = String(v == null ? "" : v).replace(/[,\s₹]/g, "");
    if (/%$/.test(s)) return (parseFloat(s) || 0) / 100;
    var n = parseFloat(s);
    return isFinite(n) ? n : 0;
  }
  function kindOf(sku, name) {
    var d = String(sku == null ? "" : sku).replace(/\D/g, "");
    if (d.length >= 4) {
      if (d[0] === "1") return "RM";
      if (d[0] === "2") return "CP";
      if (d[0] === "3") return "OP";
    }
    var n = String(name).trim().toUpperCase();
    if (/^CP\s*-/.test(n) || /^FS\s*-/.test(n)) return "CP";
    if (/^OP\s*-/.test(n)) return "OP";
    return "RM";
  }

  /** {header:[...], rows:[[...]]} from the Apps Script feed -> model. */
  function buildModel(feed) {
    var h = (feed.header || []).map(function (x) { return norm(x); });
    function col(names) {
      for (var i = 0; i < names.length; i++) { var j = h.indexOf(norm(names[i])); if (j >= 0) return j; }
      return -1;
    }
    var C = {
      code: col(["Product Code"]), prod: col(["Product Name"]), ing: col(["Ingredients", "Ingredient"]),
      sku: col(["SKUs", "SKU"]), uom: col(["Ingredients UOM", "Ingredient UOM"]), cost: col(["Cost/Unit", "Cost per unit"]),
      yieldPct: col(["RM Yield %", "RM Yield"]), recipe: col(["Recipe Qty"]), bom: col(["BOM Qty"]),
      pyield: col(["Yield"]), puom: col(["Product UOM"]), psku: col(["Product SKU"])
    };
    if (C.prod < 0 || C.ing < 0 || (C.bom < 0 && C.recipe < 0)) throw new Error("The BOM sheet doesn't have the expected columns (Product Name, Ingredients, BOM Qty).");
    var products = {}, items = {};
    (feed.rows || []).forEach(function (r) {
      var pn = String(r[C.prod] == null ? "" : r[C.prod]).trim(), iname = String(r[C.ing] == null ? "" : r[C.ing]).trim();
      if (!pn || !iname) return;
      var key = norm(pn), p = products[key];
      if (!p) {
        p = products[key] = { name: pn, code: C.code >= 0 ? String(r[C.code]).trim() : "", sku: C.psku >= 0 ? String(r[C.psku]).trim() : "",
          uom: C.puom >= 0 ? String(r[C.puom]).trim() : "", yield: C.pyield >= 0 ? num(r[C.pyield]) || 1 : 1, lines: [] };
        p.kind = kindOf(p.sku, pn);
      }
      var bomQty = C.bom >= 0 ? num(r[C.bom]) : 0;
      if (!bomQty && C.recipe >= 0) {
        var y = C.yieldPct >= 0 ? num(r[C.yieldPct]) : 1;
        bomQty = num(r[C.recipe]) / (y > 0 ? y : 1);
      }
      var sku = C.sku >= 0 ? r[C.sku] : "", kind = kindOf(sku, iname), uom = C.uom >= 0 ? String(r[C.uom]).trim() : "";
      var cost = C.cost >= 0 ? num(r[C.cost]) : 0;
      p.lines.push({ name: iname, key: norm(iname), kind: kind, qty: bomQty, uom: uom });
      var it = items[norm(iname)];
      if (!it) it = items[norm(iname)] = { name: iname, kind: kind, uom: uom, cost: cost, sku: String(sku == null ? "" : sku).trim() };
      if (!it.cost && cost) it.cost = cost;
    });
    // dishes: products that are not CP / OP preparations
    var dishes = {};
    Object.keys(products).forEach(function (k) {
      if (!/^(cp|op) /.test(k)) dishes[k] = products[k];
    });
    return { products: products, items: items, dishes: dishes };
  }

  /** Sales dish name -> BOM dish (exact, then without brackets / sizes, then by code). */
  function matchDish(model, name) {
    var k = norm(name);
    if (model.dishes[k]) return model.dishes[k];
    var k2 = norm(String(name).replace(/\([^)]*\)/g, " "));
    if (model.dishes[k2]) return model.dishes[k2];
    var keys = Object.keys(model.dishes);
    for (var i = 0; i < keys.length; i++) {
      var d = keys[i];
      if (norm(model.dishes[d].name.replace(/\([^)]*\)/g, " ")) === k2) return model.dishes[d];
    }
    return null;
  }

  /**
   * One dish quantity -> what the outlet needs: CP items (sent from the central kitchen) and RM
   * (sent from the warehouse), going through OP items made at the outlet.
   */
  function explode(model, prod, qty, out, depth, seen) {
    depth = depth || 0;
    seen = seen || {};
    if (depth > 8 || seen[norm(prod.name)]) return;
    var nextSeen = Object.assign({}, seen);
    nextSeen[norm(prod.name)] = true;
    var per = qty / (prod.yield > 0 ? prod.yield : 1);
    prod.lines.forEach(function (l) {
      var q = per * l.qty;
      if (!q) return;
      if (l.kind === "OP") {
        var sub = model.products[l.key];
        if (sub && sub.lines.length) { explode(model, sub, q, out, depth + 1, nextSeen); return; }
        add(out.missing, l.name, q);           // OP without its own recipe rows
        return;
      }
      add(l.kind === "CP" ? out.cp : out.rm, l.key, q);
    });
  }
  function add(m, k, v) { m[k] = (m[k] || 0) + v; }

  /** CP quantities -> raw materials the central kitchen needs (CP recipe rows, through sub-preps). */
  function explodeCP(model, cpNeeds) {
    var rm = {}, missing = {};
    Object.keys(cpNeeds).forEach(function (k) {
      var p = model.products[k];
      if (!p || !p.lines.length) { add(missing, k, cpNeeds[k]); return; }
      (function walk(prod, q, depth) {
        var per = q / (prod.yield > 0 ? prod.yield : 1);
        prod.lines.forEach(function (l) {
          var v = per * l.qty;
          if (!v) return;
          var sub = model.products[l.key];
          if (l.kind !== "RM" && sub && sub.lines.length && depth < 8) walk(sub, v, depth + 1);
          else add(rm, l.key, v);
        });
      })(p, cpNeeds[k], 0);
    });
    return { rm: rm, missing: missing };
  }

  // ---- dates -------------------------------------------------------------------------------
  function ymd(d) {
    return d.getFullYear() + "-" + ("0" + (d.getMonth() + 1)).slice(-2) + "-" + ("0" + d.getDate()).slice(-2);
  }
  function parseYmd(s) { var p = s.split("-"); return new Date(+p[0], +p[1] - 1, +p[2], 12); }
  function addDays(s, n) { var d = parseYmd(s); d.setDate(d.getDate() + n); return ymd(d); }

  /** "7:3 8:4.5" -> [24] */
  function cells(str) {
    var a = new Array(24).fill(0);
    String(str || "").split(/\s+/).forEach(function (c) {
      var i = c.indexOf(":");
      if (i > 0) { var h = +c.slice(0, i); if (h >= 0 && h < 24) a[h] += num(c.slice(i + 1)); }
    });
    return a;
  }

  /** days: {date: {dish: [24]}}; -> {dish: [24]} typical for the date. */
  function typicalFor(days, date, holiday) {
    var src = [];
    if (holiday) {
      // the last 4 Sundays before the date
      var d = parseYmd(date);
      for (var i = 1; i <= 35 && src.length < 4; i++) {
        var x = new Date(d); x.setDate(d.getDate() - i);
        if (x.getDay() === 0) src.push(ymd(x));
      }
    } else {
      for (var w = 1; w <= 4; w++) src.push(addDays(date, -7 * w));
    }
    var have = src.filter(function (s) { return days[s]; });
    if (!have.length) {
      // not enough history yet: average of whatever days there are (latest 7)
      have = Object.keys(days).filter(function (s) { return s < date; }).sort().slice(-7);
      src = have;
    }
    var out = {};
    var dishes = {};
    have.forEach(function (s) { Object.keys(days[s]).forEach(function (n) { dishes[n] = 1; }); });
    Object.keys(dishes).forEach(function (n) {
      var t = new Array(24).fill(0);
      for (var h = 0; h < 24; h++) {
        var vals = src.map(function (s) { return days[s] ? (days[s][n] ? days[s][n][h] : 0) : null; });
        var avail = vals.filter(function (v) { return v !== null; });
        if (!avail.length) continue;
        var avg4 = avail.reduce(function (a, b) { return a + b; }, 0) / avail.length;
        var latest = vals.slice(0, 2).filter(function (v) { return v !== null; });
        var avg2 = latest.length ? latest.reduce(function (a, b) { return a + b; }, 0) / latest.length : avg4;
        var month = vals[3] !== null && vals[3] !== undefined ? vals[3] : (vals[vals.length - 1] !== null ? vals[vals.length - 1] : avg4);
        t[h] = src.length >= 4 ? 0.6 * month + 0.2 * avg4 + 0.2 * avg2 : avg4;
      }
      out[n] = t;
    });
    return { dishes: out, sources: have };
  }

  function total(m) {
    var s = 0;
    Object.keys(m).forEach(function (n) { m[n].forEach(function (v) { s += v; }); });
    return s;
  }

  /** Recent pace: last 3 days with data before the date vs their own typical, half weight, 0.7..1.4. */
  function paceFor(days, date) {
    var recent = Object.keys(days).filter(function (s) { return s < date; }).sort().slice(-3);
    var act = 0, typ = 0;
    recent.forEach(function (s) {
      var t = typicalFor(days, s, false);
      if (!t.sources.length || t.sources.indexOf(s) >= 0) return;
      act += total(days[s]);
      typ += total(t.dishes);
    });
    if (typ <= 0 || act <= 0) return { factor: 1, raw: 1 };
    var p = act / typ;
    var f = 1 + 0.5 * (p - 1);
    return { factor: Math.max(0.7, Math.min(1.4, f)), raw: p };
  }

  function roundQty(v, uom) {
    if (!(v > 0)) return 0;
    var u = String(uom || "").toLowerCase();
    if (/pc|pcs|nos|no|portion|piece|bottle/.test(u)) return Math.ceil(v / 5) * 5;
    if (/pac|pack|pkt/.test(u)) return Math.ceil(v);
    if (v < 1) return Math.ceil(v * 10) / 10;
    if (v < 5) return Math.ceil(v * 2) / 2;
    return Math.ceil(v);
  }

  function dropOf(h) {
    for (var i = 0; i < DROPS.length; i++) if (h >= DROPS[i].from && h <= DROPS[i].to) return i;
    return 0;
  }

  /**
   * Plan one outlet for one date.
   * days: {date: {dish: [24]}} (that outlet's sales), opts: {holiday, buffer (0.05)}.
   * Returns {cp: {key: [d1,d2,d3]}, rm: {key: qty}, unmatched: {dish: qty}, opMissing, sources, pace}
   */
  function planOutlet(model, days, date, opts) {
    opts = opts || {};
    var typ = typicalFor(days, date, !!opts.holiday);
    var pace = paceFor(days, date);
    var hol = 1;
    var buf = 1 + (opts.buffer == null ? 0.05 : opts.buffer);
    var cp = {}, rm = {}, unmatched = {}, opMissing = {}, dishQty = {};
    Object.keys(typ.dishes).forEach(function (n) {
      var d = matchDish(model, n);
      var hours = typ.dishes[n];
      var sum = hours.reduce(function (a, b) { return a + b; }, 0) * pace.factor * hol;
      if (!d) { if (sum > 0) unmatched[n] = sum; return; }
      dishQty[d.name] = (dishQty[d.name] || 0) + sum;
      for (var h = 0; h < 24; h++) {
        var q = hours[h] * pace.factor * hol;
        if (!q) continue;
        var out = { cp: {}, rm: {}, missing: {} };
        explode(model, d, q, out);
        var di = dropOf(h);
        Object.keys(out.cp).forEach(function (k) { (cp[k] = cp[k] || [0, 0, 0])[di] += out.cp[k] * buf; });
        Object.keys(out.rm).forEach(function (k) { rm[k] = (rm[k] || 0) + out.rm[k] * buf; });
        Object.keys(out.missing).forEach(function (k) { opMissing[k] = (opMissing[k] || 0) + out.missing[k]; });
      }
    });
    return { cp: cp, rm: rm, unmatched: unmatched, opMissing: opMissing, sources: typ.sources, pace: pace, dishQty: dishQty };
  }

  /** What a past day actually used (sales through the BOM), no buffer. */
  function usedOn(model, dayDishes) {
    var cp = {}, rm = {};
    Object.keys(dayDishes || {}).forEach(function (n) {
      var d = matchDish(model, n);
      if (!d) return;
      var q = dayDishes[n].reduce(function (a, b) { return a + b; }, 0);
      var out = { cp: {}, rm: {}, missing: {} };
      explode(model, d, q, out);
      Object.keys(out.cp).forEach(function (k) { cp[k] = (cp[k] || 0) + out.cp[k]; });
      Object.keys(out.rm).forEach(function (k) { rm[k] = (rm[k] || 0) + out.rm[k]; });
    });
    return { cp: cp, rm: rm };
  }

  /** Hub payload {"s","r":[[dish,"h:q ..."]]} -> {dish: [24]} */
  function dayFromHub(p) {
    var out = {};
    ((p && p.r) || []).forEach(function (row) { if (row && row[0]) out[row[0]] = cells(row[1]); });
    return out;
  }

  var api = { DROPS: DROPS, norm: norm, num: num, kindOf: kindOf, buildModel: buildModel, matchDish: matchDish,
    explode: explode, explodeCP: explodeCP, typicalFor: typicalFor, paceFor: paceFor, roundQty: roundQty,
    planOutlet: planOutlet, usedOn: usedOn, dayFromHub: dayFromHub, cells: cells, ymd: ymd, addDays: addDays, parseYmd: parseYmd };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.BabaiPlan = api;
})(this);
