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
    .addToUi();
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
  ['Date', 'day'], ['Time', 'time'], ['Meal', 'meal'], ['Outlet', 'outlet'], ['Platform', 'platform'], ['Order_ID', 'id'],
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

/** The dashboard page; ?format=json gives the orders as JSON instead. */
function doGet(e) {
  if (e && e.parameter && e.parameter.format === 'json') {
    return ContentService.createTextOutput(JSON.stringify(getData())).setMimeType(ContentService.MimeType.JSON);
  }
  return HtmlService.createHtmlOutputFromFile('Dashboard').setTitle('Babai Ratings & Complaints')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

// ---- order times from the Babai Consoles -------------------------------------------------------

var CCOLS = ['Order_ID', 'KOT', 'Outlet', 'Channel', 'Brand', 'Slot', 'Items', 'Punched', 'Printed', 'Food ready',
  'Ready by', 'Picked up', 'Delivered', 'Status', 'KPT min', 'Rider wait min', 'Print sec', 'Updated'];

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
    for (var i = 1; i < data.length; i++) at[id_(data[i][0]) + '|' + data[i][1]] = i;
    var d = function (ms) { return ms ? new Date(ms) : ''; };
    var now = new Date();
    list.forEach(function (o) {
      var id = id_(o.id);
      if (!/\d{6}/.test(id)) return;
      var row = [id, o.kot || '', String(o.outlet || ''), String(o.channel || ''), String(o.brand || ''), String(o.slot || ''), o.items || '',
        d(o.punched), d(o.printed), d(o.ready), String(o.readyBy || ''), d(o.dispatched), d(o.delivered), String(o.status || ''),
        o.kptMin || '', o.waitMin || '', o.printSec || '', now];
      var k = id + '|' + (o.kot || '');
      if (at[k] != null) data[at[k]] = row; else { at[k] = data.length; data.push(row); }
    });
    // keep 120 days
    var cut = Date.now() - 120 * 864e5;
    data = [data[0]].concat(data.slice(1).filter(function (r) { return !(r[7] instanceof Date) || r[7].getTime() >= cut; }));
    sh.getRange(2, 1, Math.max(sh.getLastRow() - 1, 1), CCOLS.length).clearContent();
    sh.getRange(1, 1, data.length, CCOLS.length).setValues(data);
    return out({ok: true, n: list.length});
  } finally {
    lock.releaseLock();
  }
}

/** Called by the dashboard page every 5 minutes. */
function getData() {
  return {updated: new Date().toISOString(), orders: orders_()};
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
function date_(v) {
  if (v instanceof Date) return v;
  var m = String(v || '').match(/(\d+) (\w{3})'(\d\d), (\d+):(\d+) (AM|PM)/);
  if (!m) return null;
  var mon = 'JanFebMarAprMayJunJulAugSepOctNovDec'.indexOf(m[2]) / 3;
  var h = (+m[4]) % 12 + (m[6] === 'PM' ? 12 : 0);
  return new Date(2000 + (+m[3]), mon, +m[1], h, +m[5]);
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
      var id = id_(x[h.Order_ID]), p = String(x[h.Channel] || '').trim();
      if (!id || !p || x[h.Rating] === '' || x[h.Rating] == null) return;
      var o = get(p, id);
      o.rdate = x[h.Date] instanceof Date ? x[h.Date] : null;
      o.outlet = o.outlet || String(x[h.Outlet] || '').split(' ')[0];
      o.rating = +x[h.Rating];
      o.feedback = String(x[h.Feedback] || '').trim();
    });
  }

  var c = rows_(COMPLAINTS);
  if (c) {
    var hc = head_(c);
    c.slice(1).forEach(function (x) {
      var id = id_(x[hc.order_id]), p = String(x[hc.platform] || '').trim();
      if (!id || !p) return;
      var o = get(p.charAt(0).toUpperCase() + p.slice(1).toLowerCase(), id);
      var name = String(x[hc.outlet_name] || '').trim().toUpperCase();
      o.outlet = o.outlet || OUTLET[name] || name;
      o.ordered = date_(x[hc.order_date]);
      o.type = String(x[hc.issue_type] || 'OTHER').trim();
      o.items = items_(x[hc.order_items]);
      o.item = affected_(x[hc.affected_item], o.items);
      o.text = String(x[hc.customer_comment] || x[hc.complaint_text] || '').trim();
      o.status = String(x[hc.status] || '').trim();
      o.refund = num_(/([\d.]+)/, x[hc.recommended_refund]);
      o.value = num_(/([\d.]+)/, x[hc.item_total]);
      o.photo = !!x[hc.image_urls];
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
    });
  }
  var mins = function (a, b) {
    return a instanceof Date && b instanceof Date && b >= a && b - a < 4 * 36e5 ? Math.round((b - a) / 6e3) / 10 : null;
  };

  var list = keys.map(function (key) {
    var o = byKey[key], t = kt[o.id] || {};
    var exact = o.ordered || t.punched || null; // order time: the platform's, else when the KOT was punched
    var when = exact || o.rdate;
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
      refund: o.refund, value: o.value, photo: !!o.photo,
      kind: o.type && o.rating != null ? 'Rated and complained' : o.type ? 'Complaint only' : 'Rating only',
      ctype: cu.type || '', customer: cu.label, check: cu.check,
      cause: (o.type || low) ? cause_(o.type, said) : '',
      kpt: mins(t.punched, t.ready), wait: mins(t.ready, t.picked), delivery: mins(t.picked, t.deliv),
      slot: t.slot || '', readyBy: t.readyBy || '', console: !!t.punched,
      picked: t.picked ? Utilities.formatDate(t.picked, TZ, 'HH:mm') : ''
    };
    out.summary = summary_(out, o.items);
    return out;
  });
  list.sort(function (a, b) { return b.at - a.at; });
  return list;
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
