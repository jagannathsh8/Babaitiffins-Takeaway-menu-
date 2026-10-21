/**
 * Babai CX METRICS: one row per order (rating + complaint + customer + kitchen times), live.
 *
 * Install in the CX METRICS sheet: Extensions > Apps Script. Replace Code.gs with this file (its
 * doPost still does what the old Code.gs did, see sheetsPost_) and add Dashboard.html as an HTML
 * file named "Dashboard". Save, then run setup() once (allow access).
 *   - The "Merged" tab is rebuilt now and every 15 minutes.
 *   - Deploy > New deployment > Web app (execute as: me, access: your choice) gives the
 *     dashboard link. It reads the sheet live and refreshes itself every 5 minutes.
 * Customer names, phones and addresses are never read or shown.
 *
 * Reads:  "Rating raw data collae"  (Date, Channel, Outlet, Order_ID, Rating, Feedback, ...)
 *         "__probe__"               (platform, order_id, ..., outlet_name, order_date, ..., issue_type, ...)
 *         "Console times"           (written by doPost: each outlet's Babai Console sends its order
 *                                    times every 15 minutes: punched, food ready, picked up, slot)
 * Writes: "Merged", "Console times"
 *
 * Console link: run setup(), then Deploy > New deployment > Web app (execute as: me, access: anyone).
 * In each Console: Data > Ratings sheet, paste the web app link and the key from the Babai menu.
 */

var RATINGS = 'Rating raw data collae';
var COMPLAINTS = '__probe__';
var CONSOLE = 'Console times';
var MERGED = 'Merged';
var TZ = 'Asia/Kolkata';

// Complaint outlet names → the outlet codes the ratings use (checked on orders found in both tabs).
var OUTLET = {'HSR': 'HSR', 'WHITEFIELD': 'VTR', 'SARJAPUR': 'SJP', 'SARJAPUR ROAD': 'SJP', 'AREKERE': 'JPN', 'JP NAGAR': 'JPN'};

var CAUSE = {
  'MISSING': 'Packing: item not put in the bag',
  'WRONG ITEM': 'Packing: wrong item picked',
  'QUALITY / TASTE': 'Kitchen: taste or cooking',
  'PACKAGING': 'Packing: container or spill'
};
var GROUPS = [
  ['Missing / wrong item', /miss|not (receiv|deliver)|wrong|instead|did ?n.?t get|did not get|only one|received (just|only)|forgot/i],
  ['Cold / late', /cold|late|delay|time|slow|hour|wait/i],
  ['Taste / quality', /taste|salt|bland|stale|raw|uncook|oil|hard|bad|worst|quality|sour|smell|spic/i],
  ['Quantity / value', /quantity|small|less|price|cost|expens|value|money|portion/i],
  ['Packaging / spill', /spill|leak|pack|cover|container|box/i],
  ['Hygiene / health', /hair|insect|worm|cockroach|stomach|sick|vomit|plastic|stone/i]
];

// ---- setup, Merged tab, web app ----------------------------------------------------------------

function setup() {
  key_();
  ScriptApp.getProjectTriggers().forEach(function (t) {
    if (t.getHandlerFunction() === 'buildMerged') ScriptApp.deleteTrigger(t);
  });
  ScriptApp.newTrigger('buildMerged').timeBased().everyMinutes(15).create();
  buildMerged();
  Logger.log('Console key: ' + key_());
}

function onOpen() {
  SpreadsheetApp.getUi().createMenu('Babai')
    .addItem('Rebuild Merged now', 'buildMerged')
    .addItem('Show Console key', 'showKey')
    .addItem('Clean Console times now', 'cleanConsoleNow')
    .addToUi();
}

function cleanConsoleNow() {
  PropertiesService.getScriptProperties().deleteProperty('TRIM_AT');
  buildMerged();
  var sh = SpreadsheetApp.getActive().getSheetByName(CONSOLE);
  SpreadsheetApp.getUi().alert('Console times now has ' + (sh ? sh.getLastRow() - 1 : 0) + ' rows (all orders of the last ' + ALL_DAYS + ' days, rated or complained ones for ' + LINKED_DAYS + ' days).');
}

function showKey() {
  SpreadsheetApp.getUi().alert('Paste this key in each Babai Console (Data > Ratings sheet):\n\n' + key_());
}

// The key the Consoles send with their order times (made once, kept in the script's properties).
function key_() {
  var p = PropertiesService.getScriptProperties(), k = p.getProperty('CONSOLE_KEY');
  if (!k) {
    k = Utilities.getUuid().replace(/-/g, '');
    p.setProperty('CONSOLE_KEY', k);
  }
  return k;
}

var COLS = [
  ['Date', 'day'], ['Time', 'time'], ['Meal', 'meal'], ['Outlet', 'outlet'], ['Platform', 'platform'], ['Order_ID', 'id'], ['Swiggy complaint order_id', 'sid'],
  ['Rating', 'rating'], ['Complaint', 'complaint'], ['Kind', 'kind'], ['Customer', 'customer'], ['Customer check', 'check'],
  ['Summary', 'summary'], ['Likely cause', 'cause'], ['Status', 'status'], ['Refund', 'refund'],
  ['KPT min (to food ready)', 'kpt'], ['Rider wait min', 'wait'], ['Slot', 'slot'], ['Food ready by', 'readyBy'],
  ['Picked up', 'picked'], ['Pickup to delivery min', 'delivery']
];

function buildMerged() {
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) return;
  try {
    var list = orders_();
    cachePayload_(list); // the dashboard reads this, so it never waits for the merge
    trimConsole_(list);
    var ss = SpreadsheetApp.getActive();
    var sh = ss.getSheetByName(MERGED) || ss.insertSheet(MERGED);
    var out = [COLS.map(function (c) { return c[0]; })].concat(list.map(function (o) {
      return COLS.map(function (c) { var v = o[c[1]]; return v == null ? '' : v; });
    }));
    sh.clearContents();
    sh.getRange(1, 6, out.length, 1).setNumberFormat('@'); // keep long order IDs exact
    sh.getRange(1, 1, out.length, COLS.length).setValues(out);
    sh.setFrozenRows(1);
    sh.getRange(1, 1, 1, COLS.length).setFontWeight('bold');
    sh.getRange(1, COLS.length + 2).setValue('Updated ' + Utilities.formatDate(new Date(), TZ, 'dd MMM HH:mm'));
  } finally {
    lock.releaseLock();
  }
}

// Marks the Console times rows of rated / complained orders 'Keep' and drops old rows nobody needs.
// Runs with buildMerged (every 15 min) but rewrites the tab only every 6 hours or when it grew big.
function trimConsole_(list) {
  var ss = SpreadsheetApp.getActive(), sh = ss.getSheetByName(CONSOLE);
  if (!sh || sh.getLastRow() < 2) return;
  var p = PropertiesService.getScriptProperties(), last = +p.getProperty('TRIM_AT') || 0;
  if (Date.now() - last < 6 * 36e5 && sh.getLastRow() < 20000) return;
  var linked = {};
  list.forEach(function (o) { if (o.id) linked[id_(o.id)] = 1; if (o.cid) linked[id_(o.cid)] = 1; if (o.sid) linked[id_(o.sid)] = 1; });
  var data = sh.getDataRange().getValues();
  data[0] = CCOLS.slice();
  data = data.map(function (r) { while (r.length < CCOLS.length) r.push(''); return r.slice(0, CCOLS.length); });
  var now = Date.now(), before = data.length;
  for (var i = 1; i < data.length; i++) if (linked[id_(data[i][0])]) data[i][KEEP_COL] = 1;
  data = [data[0]].concat(data.slice(1).filter(function (r) { return keepRow_(r, now); }));
  sh.getRange(2, 1, Math.max(sh.getLastRow() - 1, 1), Math.max(sh.getLastColumn(), CCOLS.length)).clearContent();
  sh.getRange(1, 1, data.length, CCOLS.length).setValues(data);
  sh.getRange(1, 1, 1, CCOLS.length).setFontWeight('bold');
  if (sh.getMaxRows() > data.length + 500) sh.deleteRows(data.length + 201, sh.getMaxRows() - data.length - 200); // no empty grid
  p.setProperty('TRIM_AT', String(now));
  Logger.log('Console times: ' + (before - 1) + ' -> ' + (data.length - 1) + ' rows');
}

/** The dashboard page; ?format=json gives the orders as JSON instead. */
function doGet(e) {
  if (e && e.parameter && e.parameter.format === 'json') {
    return ContentService.createTextOutput(payload_()).setMimeType(ContentService.MimeType.JSON);
  }
  return HtmlService.createHtmlOutputFromFile('Dashboard').setTitle('Ratings & Complaints')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

// ---- order times from the Babai Consoles -------------------------------------------------------

var CCOLS = ['Order_ID', 'KOT', 'Outlet', 'Channel', 'Brand', 'Slot', 'Items', 'Punched', 'Printed', 'Food ready',
  'Ready by', 'Picked up', 'Delivered', 'Status', 'KPT min', 'Rider wait min', 'Print sec', 'Updated', 'Item names', 'Keep'];
var KEEP_COL = CCOLS.indexOf('Keep');
// The tab stays small: every order for 10 days (ratings and complaints come in by then), after that
// only the orders that got a rating or complaint (marked 'Keep' by buildMerged), for 120 days.
var ALL_DAYS = 10, LINKED_DAYS = 120;

function keepRow_(r, now) {
  if (!(r[7] instanceof Date)) return true;
  var age = now - r[7].getTime();
  return age < ALL_DAYS * 864e5 || (r[KEEP_COL] === 1 || r[KEEP_COL] === '1') && age < LINKED_DAYS * 864e5;
}

/**
 * One doPost for the sheet. A Babai Console sends {key, orders:[...]}; anything else is the
 * sheet's existing feed ({tab name: rows}), handled exactly as before by sheetsPost_.
 */
function doPost(e) {
  var body = JSON.parse(e.postData.contents);
  if (body && body.key != null && Array.isArray(body.orders)) return consolePost_(body);
  return sheetsPost_(body);
}

// The sheet's existing feed, unchanged: each key is a tab name, its value the rows that replace the tab.
function sheetsPost_(data) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  for (var sheetName in data) {
    var sheet = ss.getSheetByName(sheetName) || ss.insertSheet(sheetName);
    sheet.clear();
    var rows = data[sheetName];
    if (rows && rows.length > 0) {
      sheet.getRange(1, 1, rows.length, rows[0].length).setValues(rows);
    }
  }
  return ContentService.createTextOutput("OK");
}

// Order times from a Babai Console (see babai-hub/cxsheet.go). Rows are kept per order + KOT.
function consolePost_(body) {
  var out = function (o) { return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON); };
  if (body.key !== key_()) return out({ok: false, msg: 'wrong key: copy it again from the sheet (Babai > Show Console key)'});
  var list = (body.orders || []).slice(0, 1000);
  var lock = LockService.getScriptLock();
  if (!lock.tryLock(60000)) return out({ok: false, msg: 'sheet busy, will retry'});
  try {
    var ss = SpreadsheetApp.getActive();
    var sh = ss.getSheetByName(CONSOLE);
    if (!sh) {
      sh = ss.insertSheet(CONSOLE);
      sh.getRange(1, 1, 1, CCOLS.length).setValues([CCOLS]).setFontWeight('bold');
      sh.setFrozenRows(1);
      sh.getRange('A:A').setNumberFormat('@');
    }
    var data = sh.getDataRange().getValues(), at = {};
    data[0] = CCOLS.slice(); // header, also on tabs made before a column was added
    data = data.map(function (r) { while (r.length < CCOLS.length) r.push(''); return r.slice(0, CCOLS.length); });
    for (var i = 1; i < data.length; i++) at[id_(data[i][0]) + '|' + data[i][1]] = i;
    var d = function (ms) { return ms ? new Date(ms) : ''; };
    var now = new Date();
    list.forEach(function (o) {
      var id = id_(o.id);
      if (!/\d{6}/.test(id)) return;
      var row = [id, o.kot || '', String(o.outlet || ''), String(o.channel || ''), String(o.brand || ''), String(o.slot || ''), o.items || '',
        d(o.punched), d(o.printed), d(o.ready), String(o.readyBy || ''), d(o.dispatched), d(o.delivered), String(o.status || ''),
        o.kptMin || '', o.waitMin || '', o.printSec || '', now, String(o.names || '')];
      row.push('');
      var k = id + '|' + (o.kot || '');
      if (at[k] != null) { row[KEEP_COL] = data[at[k]][KEEP_COL]; data[at[k]] = row; } else { at[k] = data.length; data.push(row); }
    });
    var cut = Date.now() - LINKED_DAYS * 864e5; // the 10-day clean-up is trimConsole_'s (it knows the rated orders)
    data = [data[0]].concat(data.slice(1).filter(function (r) { return !(r[7] instanceof Date) || r[7].getTime() >= cut; }));
    sh.getRange(2, 1, Math.max(sh.getLastRow() - 1, 1), Math.max(sh.getLastColumn(), CCOLS.length)).clearContent();
    sh.getRange(1, 1, data.length, CCOLS.length).setValues(data);
    sh.getRange(1, 1, 1, CCOLS.length).setFontWeight('bold');
    return out({ok: true, n: list.length});
  } finally {
    lock.releaseLock();
  }
}

/** Called by the dashboard page every 5 minutes. */
function getData() {
  return JSON.parse(payload_());
}

// The dashboard's data: only the fields it shows, as rows under one list of column names (small and
// quick to send). Kept ready in the script cache, refreshed with the Merged tab every 15 minutes.
var DCOLS = ['id', 'sid', 'day', 'time', 'meal', 'outlet', 'platform', 'rating', 'feedback', 'complaint', 'item', 'items',
  'text', 'value', 'status', 'refund', 'ctype', 'check', 'cause', 'kpt', 'wait', 'slot', 'customer', 'photoUrl'];
var CHUNK = 90000;

function cachePayload_(list) {
  var json = JSON.stringify({updated: new Date().toISOString(), cols: DCOLS, rows: list.map(function (o) {
    return DCOLS.map(function (c) { var v = o[c]; return v === '' || v === undefined ? null : v; });
  })});
  var parts = {}, n = Math.ceil(json.length / CHUNK);
  for (var i = 0; i < n; i++) parts['dash_' + i] = json.slice(i * CHUNK, (i + 1) * CHUNK);
  parts.dash_n = String(n);
  try { CacheService.getScriptCache().putAll(parts, 21600); } catch (e) {} // too big for the cache: served fresh instead
  return json;
}

function payload_() {
  try {
    var cache = CacheService.getScriptCache(), n = +cache.get('dash_n');
    if (n) {
      var keys = []; for (var i = 0; i < n; i++) keys.push('dash_' + i);
      var got = cache.getAll(keys), out = '';
      for (var j = 0; j < n; j++) { if (got['dash_' + j] == null) { out = ''; break; } out += got['dash_' + j]; }
      if (out) return out;
    }
  } catch (e) {}
  return cachePayload_(orders_());
}

// ---- the merge -----------------------------------------------------------------------------------

function id_(v) {
  return String(v == null ? '' : v).replace(/\.0$/, '').trim();
}

function head_(rows) {
  var h = {};
  rows[0].forEach(function (k, i) { k = String(k).trim(); if (k !== '' && h[k] == null) h[k] = i; });
  return h;
}

function rows_(name) {
  var sh = SpreadsheetApp.getActive().getSheetByName(name);
  return sh ? sh.getDataRange().getValues() : null;
}

// "13 Oct'26, 07:18 AM" (Swiggy) or a real date (Zomato).
// A moment in India time (Asia/Kolkata), whatever the script's own time zone is.
function ist_(y, mo, d, h, mi) {
  return Utilities.parseDate(y + '-' + mo + '-' + d + ' ' + (h || 0) + ':' + (mi || 0), TZ, 'yyyy-M-d H:m');
}

var MON_ = 'janfebmaraprmayjunjulaugsepoctnovdec';

// An order time: a date cell, the platforms' "13 Oct'26, 07:18 AM", or text like
// "2026-10-21 08:15", "2026-10-21T08:15:00", "21/10/2026 8:15 PM". null when it can't be read.
function date_(v) {
  if (v instanceof Date) return v;
  var s = String(v || '').trim(), m;
  if (!s) return null;
  var hm = function (h, mi, ap) { h = +h || 0; if (ap) h = h % 12 + (/p/i.test(ap) ? 12 : 0); return [h, +mi || 0]; };
  if ((m = s.match(/^(\d{1,2}) (\w{3})'(\d\d), (\d+):(\d+) ?(AM|PM)/i))) {
    var t = hm(m[4], m[5], m[6]);
    return ist_(2000 + (+m[3]), MON_.indexOf(m[2].toLowerCase()) / 3 + 1, +m[1], t[0], t[1]);
  }
  if ((m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::\d{2})?\s*(AM|PM)?)?/i))) {
    var t2 = m[4] != null ? hm(m[4], m[5], m[6]) : [12, 0];
    return ist_(+m[1], +m[2], +m[3], t2[0], t2[1]);
  }
  if ((m = s.match(/^(\d{1,2})[\/-](\d{1,2})[\/-](\d{4})(?:,?\s+(\d{1,2}):(\d{2})(?::\d{2})?\s*(AM|PM)?)?/i))) {
    var t3 = m[4] != null ? hm(m[4], m[5], m[6]) : [12, 0];
    return ist_(+m[3], +m[2], +m[1], t3[0], t3[1]);
  }
  if ((m = s.match(/^(\d{1,2})[ -]([A-Za-z]{3})[a-z]*[\s,-]*(\d{2,4})(?:,?\s+(\d{1,2}):(\d{2})\s*(AM|PM)?)?/))) {
    var mon = MON_.indexOf(m[2].toLowerCase()) / 3;
    if (mon < 0) return null;
    var t4 = m[4] != null ? hm(m[4], m[5], m[6]) : [12, 0], yr = +m[3] < 100 ? 2000 + (+m[3]) : +m[3];
    return ist_(yr, mon + 1, +m[1], t4[0], t4[1]);
  }
  return null;
}

// A day: the same forms as date_ (a date without a time is taken at noon, so no time zone moves it).
function day_(v) {
  return date_(v);
}

// Platform names the same way in every tab: Swiggy, Zomato, Ownly…
function plat_(p) {
  p = String(p || '').trim();
  if (/swiggy/i.test(p)) return 'Swiggy';
  if (/zomato/i.test(p)) return 'Zomato';
  if (/ownly/i.test(p)) return 'Ownly';
  return p ? p.charAt(0).toUpperCase() + p.slice(1).toLowerCase() : '';
}

// Outlet code (VTR, SJP, JPN, HSR) from "VTR - Babai Tiffins", "Whitefield", "Sarjapur Road"…
function outlet_(s) {
  var n = String(s || '').trim().toUpperCase();
  if (!n) return '';
  if (OUTLET[n]) return OUTLET[n];
  if (/WHITEFIELD|VARTHUR|^VTR\b/.test(n)) return 'VTR';
  if (/SARJAPUR|^SJP\b/.test(n)) return 'SJP';
  if (/JP ?NAGAR|AREKERE|^JPN\b/.test(n)) return 'JPN';
  if (/HSR/.test(n)) return 'HSR';
  return n.split(/[\s-]+/)[0];
}

function num_(re, s) {
  var m = String(s || '').replace(/,/g, '').match(re);
  return m ? +m[1] : null;
}

// Breakfast 7 am–12, lunch 12–4 pm, snacks 4–7 pm, dinner 7 pm onwards.
function meal_(h) {
  if (h == null) return '';
  if (h >= 7 && h < 12) return 'Breakfast';
  if (h >= 12 && h < 16) return 'Lunch';
  if (h >= 16 && h < 19) return 'Snacks';
  return 'Dinner';
}

function cause_(type, said) {
  if (type && /missing|received (just|only)|did ?n.?t (get|receive)|not received/i.test(said)) return CAUSE['MISSING'];
  if (type && CAUSE[type]) return CAUSE[type];
  if (!said) return type ? 'Read the comment' : 'No comment given';
  var g = GROUPS.filter(function (x) { return x[1].test(said); }).map(function (x) { return x[0]; });
  return g.length ? g.join(', ') : 'Other / unclear';
}

// Swiggy: "Ordered at … | 4 items | Dosa | X 2 | ₹459 | ADD-ONS: Chutney | Vada | X 1 | ₹60" → "2 x Dosa (+ Chutney), 1 x Vada".
// Zomato already reads "1 x Dosa , 2 x Vada".
function items_(v) {
  var s = String(v || '').trim();
  if (!/^Ordered at/i.test(s)) return s.replace(/\s*,\s*/g, ', ');
  var seg = s.split('|').map(function (x) { return x.trim(); }), out = [];
  for (var i = 0; i < seg.length; i++) {
    var q = seg[i + 1] && seg[i + 1].match(/^X\s*(\d+)$/i);
    if (q) { out.push(q[1] + ' x ' + seg[i]); i += 2; continue; }
    var add = seg[i].match(/^ADD-ONS:\s*(.*)$/i);
    if (add && out.length && add[1]) out[out.length - 1] += ' (+ ' + add[1] + ')';
  }
  return out.join(', ');
}

// Only the parts of "affected item" that are dishes: Swiggy's field can also carry a person's name.
function affected_(v, items) {
  var dishes = items.toLowerCase();
  return String(v || '').split('|').map(function (x) { return x.trim(); }).filter(function (x) {
    var name = x.replace(/^\d+\s*x\s*/i, '').toLowerCase();
    return /^\d+\s*x\s/i.test(x) || (name.length > 2 && dishes.indexOf(name) >= 0);
  }).join(', ');
}

function money_(n) {
  return '₹' + Math.round(n).toLocaleString('en-IN');
}

/**
 * Customer history from the complaint (the platforms show it with each complaint).
 * New = first order with us in 90 days, Repeat = 2–5, Regular = 6 or more.
 */
function customer_(o) {
  if (o.n90 == null && o.pn == null) return {label: '', check: ''};
  var n = o.n90 == null ? 0 : o.n90;
  var type = n <= 1 ? 'New' : n <= 5 ? 'Repeat' : 'Regular';
  var bits = [type + (o.prem ? ' · Premium' : ''), n <= 1 ? 'first order with us in 90 days' : n + ' orders with us in 90 days'];
  if (o.v90) bits.push(money_(o.v90) + ' spent');
  if (o.pn != null) bits.push(o.pn + ' on ' + o.platform);
  if (o.i90 != null) bits.push(o.i90 + ' issue' + (o.i90 === 1 ? '' : 's') + ' in 90 days');
  var check;
  if (o.verdict) check = 'Genuine (' + o.platform + ' checked)';
  else if (o.i90 != null && o.i90 >= 3 && o.i90 >= n / 2) check = 'Check: complains often';
  else if (n <= 1 && o.pn != null && o.pn <= 2) check = 'Check: brand-new account';
  else check = 'Looks genuine';
  return {label: bits.join(' · '), check: check, type: type};
}

function orders_() {
  var byKey = {}, keys = [];
  function get(platform, id) {
    var k = platform + '|' + id;
    if (!byKey[k]) { byKey[k] = {platform: platform, id: id}; keys.push(k); }
    return byKey[k];
  }

  var r = rows_(RATINGS);
  if (r) {
    var h = head_(r);
    r.slice(1).forEach(function (x) {
      var id = id_(x[h.Order_ID]), p = plat_(x[h.Channel]);
      var rt = parseFloat(String(x[h.Rating] == null ? '' : x[h.Rating]).replace(',', '.'));
      if (!id || !p || !(rt >= 1 && rt <= 5)) return; // no rating, or not a 1-5 number
      var o = get(p, id);
      o.rdate = day_(x[h.Date]);
      o.outlet = o.outlet || outlet_(x[h.Outlet]);
      o.rating = rt;
      o.feedback = String(x[h.Feedback] || '').trim();
    });
  }

  var c = rows_(COMPLAINTS);
  if (c) {
    var hc = head_(c), links = links_(COMPLAINTS, hc.image_urls, c.length);
    c.slice(1).forEach(function (x, ci) {
      var id = id_(x[hc.order_id]), p = plat_(x[hc.platform]), shortId = '';
      if (!id || !p) return;
      // Swiggy: order_id + complaint_id (4 digits) is the real order ID, the one on the Petpooja bill
      var cid = hc.complaint_id != null ? id_(x[hc.complaint_id]) : '';
      if (/^swiggy$/i.test(p) && /^\d{1,4}$/.test(cid) && id.length <= 12) { shortId = id; id = id + ('0000' + cid).slice(-4); }
      var o = get(p, id);
      if (shortId) o.shortId = shortId;
      o.outlet = o.outlet || outlet_(x[hc.outlet_name]);
      o.ordered = date_(x[hc.order_date]);
      o.type = String(x[hc.issue_type] || 'OTHER').trim();
      o.items = items_(x[hc.order_items]);
      o.item = affected_(x[hc.affected_item], o.items);
      o.text = String(x[hc.customer_comment] || x[hc.complaint_text] || '').trim();
      o.status = String(x[hc.status] || '').trim();
      o.refund = num_(/([\d.]+)/, x[hc.recommended_refund]);
      o.value = num_(/([\d.]+)/, x[hc.item_total]);
      o.photo = !!x[hc.image_urls];
      o.photoUrl = links[ci + 1] || (/^https?:\/\//.test(String(x[hc.image_urls])) ? String(x[hc.image_urls]).split(/[\s,|]+/)[0] : '');
      // Customer history (numbers only; the name column is only checked for the PREMIUM badge).
      var cnt = String(x[hc.customer_order_count_90d] || '');
      o.n90 = num_(/Ordered (\d+) times/, cnt);
      if (o.n90 == null) o.n90 = num_(/(\d+) orders with you/, cnt);
      o.pn = num_(/(\d+) orders with Zomato/, cnt);
      if (o.n90 == null && o.pn != null) o.n90 = 0; // Zomato leaves "with you" out for a first order
      o.v90 = num_(/worth ₹?([\d.]+)/, x[hc.customer_order_value_90d]);
      o.i90 = num_(/Faced (\d+) issues/, x[hc.customer_issue_count_90d]);
      if (o.i90 == null) o.i90 = num_(/(\d+) complaints? in last/, cnt);
      o.prem = String(x[hc.customer_name] || '').trim() === 'PREMIUM';
      o.verdict = /genuine|restaurant error/i.test(String(x[hc.resolution_text] || ''));
    });
  }

  // Console times, one order can have several KOTs: first punch, last food ready, last pickup.
  var k = rows_(CONSOLE), kt = {};
  if (k) {
    var hk = head_(k);
    k.slice(1).forEach(function (x) {
      var id = id_(x[hk.Order_ID]);
      if (!id) return;
      var t = kt[id] || (kt[id] = {});
      var punched = x[hk.Punched], ready = x[hk['Food ready']], picked = x[hk['Picked up']], deliv = x[hk.Delivered];
      if (punched instanceof Date && (!t.punched || punched < t.punched)) t.punched = punched;
      if (ready instanceof Date && (!t.ready || ready > t.ready)) { t.ready = ready; t.readyBy = String(x[hk['Ready by']] || ''); }
      if (picked instanceof Date && (!t.picked || picked > t.picked)) t.picked = picked;
      if (deliv instanceof Date && (!t.deliv || deliv > t.deliv)) t.deliv = deliv;
      if (!t.slot && x[hk.Slot] !== '') t.slot = String(x[hk.Slot]);
      if (!t.outlet) t.outlet = String(x[hk.Outlet] || '');
      if (!t.channel) t.channel = String(x[hk.Channel] || '');
      var nm = hk['Item names'] != null ? String(x[hk['Item names']] || '') : '';
      if (nm) t.names = t.names ? t.names + ', ' + nm : nm;
    });
  }
  linkSwiggy_(byKey, keys, kt);
  var mins = function (a, b) {
    return a instanceof Date && b instanceof Date && b >= a && b - a < 4 * 36e5 ? Math.round((b - a) / 6e3) / 10 : null;
  };

  var hm = function (d) { return d instanceof Date ? Utilities.formatDate(d, TZ, 'HH:mm') : ''; };
  var list = keys.filter(function (key) { return !byKey[key].gone; }).map(function (key) {
    var o = byKey[key], t = kt[o.cid || o.id] || {};
    var exact = o.ordered || t.punched || null; // order time: the platform's, else when the KOT was punched
    var when = o.rdate || exact; // a rating counts on its Date (as in the outlet sheets), a complaint on its order date
    var hour = exact ? +Utilities.formatDate(exact, TZ, 'H') : null;
    var said = o.text || o.feedback || '';
    var cu = customer_(o);
    var low = o.rating != null && o.rating <= 3;
    var out = {
      at: when ? when.getTime() : 0,
      day: when ? Utilities.formatDate(when, TZ, 'yyyy-MM-dd') : '',
      time: exact ? Utilities.formatDate(exact, TZ, 'HH:mm') : '',
      meal: meal_(hour),
      outlet: o.outlet || '', platform: o.platform, id: o.id,
      rating: o.rating == null ? null : o.rating, feedback: o.feedback || '',
      complaint: o.type || '', item: o.item || '', text: o.text || '', status: o.status || '',
      refund: o.refund, value: o.value, photo: !!o.photo, photoUrl: o.photoUrl || '', items: o.items || t.names || '',
      cid: o.cid || '', sid: o.shortId || '', linked: o.cid || o.shortId ? 'outlet, time and items' : '',
      kind: o.type && o.rating != null ? 'Rated and complained' : o.type ? 'Complaint only' : 'Rating only',
      ctype: cu.type || '', customer: cu.label, check: cu.check,
      cause: (o.type || low) ? cause_(o.type, said) : '',
      kpt: mins(t.punched, t.ready), wait: mins(t.ready, t.picked), delivery: mins(t.picked, t.deliv),
      slot: t.slot || '', readyBy: t.readyBy || '', console: !!t.punched,
      punched: hm(t.punched), ready: hm(t.ready), picked: hm(t.picked)
    };
    out.summary = summary_(out, out.items);
    return out;
  });
  list.sort(function (a, b) { return b.at - a.at; });
  return list;
}

// Link URLs of a column (the sheet shows "Open complaint image" with the link behind it).
function links_(name, col, n) {
  var out = [];
  if (col == null) return out;
  try {
    var sh = SpreadsheetApp.getActive().getSheetByName(name);
    var rich = sh.getRange(1, col + 1, n, 1).getRichTextValues();
    var f = sh.getRange(1, col + 1, n, 1).getFormulas();
    for (var i = 0; i < n; i++) {
      var u = rich[i] && rich[i][0] && rich[i][0].getLinkUrl();
      if (!u && rich[i] && rich[i][0]) rich[i][0].getRuns().forEach(function (r) { u = u || r.getLinkUrl(); });
      if (!u && f[i] && f[i][0]) { var m = String(f[i][0]).match(/HYPERLINK\(\s*"([^"]+)"/i); if (m) u = m[1]; }
      out.push(u || '');
    }
  } catch (e) {}
  return out;
}

function dishes_(s) {
  return String(s || '').split(/\s*,\s*(?=\d+(?:\.\d+)?\s*x\s)/i).map(function (x) {
    return x.replace(/\(\+[^)]*\)+/g, '').replace(/^\d+(?:\.\d+)?\s*x\s*/i, '').replace(/\s+/g, ' ').trim().toLowerCase();
  }).filter(String);
}

/**
 * Older Swiggy complaints without a complaint_id carry only Swiggy's short order ID. Link such a
 * complaint to the Console order of the same outlet punched within 3 min before to 15 min after
 * the order time, with the most dishes in common. When that order was also rated, the two rows
 * become one.
 */
function linkSwiggy_(byKey, keys, kt) {
  var cand = Object.keys(kt).filter(function (id) { return kt[id].punched && /swiggy/i.test(kt[id].channel || ''); });
  if (!cand.length) return;
  keys.forEach(function (key) {
    var o = byKey[key];
    if (o.platform !== 'Swiggy' || !o.type || !o.ordered || kt[o.id] || o.shortId) return; // real ID known: no guessing
    var want = dishes_(o.items), best = null, bestScore = 0, bestGap = 1e15;
    cand.forEach(function (id) {
      var t = kt[id], gap = t.punched - o.ordered;
      if (gap < -3 * 6e4 || gap > 15 * 6e4) return;
      if (t.outlet && o.outlet && t.outlet.split(' ')[0].toUpperCase() !== o.outlet.toUpperCase()) return;
      var have = dishes_(t.names), score = 0;
      want.forEach(function (d) { if (have.indexOf(d) >= 0) score++; });
      if (!want.length || !have.length) score = 0.5; // nothing to compare: time alone, only when it is the one order
      if (score > bestScore || (score === bestScore && Math.abs(gap) < bestGap)) { best = id; bestScore = score; bestGap = Math.abs(gap); }
    });
    if (!best || bestScore < 1 && cand.filter(function (id) { var g = kt[id].punched - o.ordered; return g >= -18e4 && g <= 9e5; }).length > 1) return;
    var rated = byKey['Swiggy|' + best];
    if (rated && !rated.type) { // the same order was rated: one row
      ['ordered', 'type', 'items', 'item', 'text', 'status', 'refund', 'value', 'photo', 'photoUrl', 'n90', 'pn', 'v90', 'i90', 'prem', 'verdict'].forEach(function (f) { rated[f] = o[f]; });
      rated.shortId = o.id;
      o.gone = true;
    } else {
      o.cid = best;
    }
  });
}

// One line that says everything about the order.
function summary_(o, items) {
  var s = [];
  s.push((o.meal || 'Time not known') + ' · ' + o.outlet + ' · ' + o.platform);
  if (items) s.push('Ordered: ' + items);
  if (o.value) s.push('Bill ' + money_(o.value));
  if (o.complaint) s.push('Complaint: ' + o.complaint + (o.item ? ' (' + o.item + ')' : '') + (o.photo ? ', with photo' : ''));
  if (o.text) s.push('"' + o.text + '"');
  if (o.rating != null) s.push('Rated ' + o.rating + '★' + (o.feedback && o.feedback !== o.text ? ': "' + o.feedback + '"' : ''));
  if (o.customer) s.push('Customer: ' + o.customer + ' (' + o.check + ')');
  if (o.refund) s.push('Refund ' + money_(o.refund));
  if (o.status) s.push('Status ' + o.status);
  if (o.cause) s.push('Likely cause: ' + o.cause);
  s.push(o.console
    ? 'Kitchen: food ready in ' + (o.kpt == null ? '– (not marked)' : o.kpt + ' min') + (o.readyBy ? ' (' + o.readyBy + ')' : '') +
      ', waited ' + (o.wait == null ? '–' : o.wait + ' min') + ' for the rider' + (o.slot ? ', slot ' + o.slot : '') +
      (o.delivery != null ? ', delivered ' + o.delivery + ' min after pickup' : '')
    : 'Kitchen times: not from the Console (outlet not linked yet)');
  return s.join(' · ');
}
