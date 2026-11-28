/**
 * SyncGuard.gs  —  stops PetpoojaSync from ever leaving the outlet tabs blank.
 *
 * HOW IT WORKS
 *   The existing upload code (renamed to doPost_original) is left untouched.
 *   This new doPost wraps it:
 *     1. Takes a script lock, so two uploads can never overlap
 *        (overlapping clear+write is a classic cause of a blank tab).
 *     2. Snapshots the outlet tabs (HSR, JPN, SJP, VTR).
 *     3. Runs the original upload.
 *     4. Checks every outlet tab. If a tab came back EMPTY (header only), or
 *        shrank by more than half in the middle of the month, the snapshot is
 *        written back. The last good data always stays on screen.
 *
 *   No other tab is touched: Summary, formulas, formatting and other sheets are not read or written.
 *
 * INSTALL (once, about 3 minutes)
 *   1. Open the CX METRICS sheet -> Extensions -> Apps Script.
 *   2. In the existing code, rename   function doPost(e)
 *                                 to   function doPost_original(e)
 *   3. Click + (Add a file) -> Script -> name it SyncGuard -> paste this whole file.
 *   4. Save.  Then Deploy -> Manage deployments -> pencil (Edit) on the
 *      existing Web app -> Version: "New version" -> Deploy.
 *      (Editing the existing deployment keeps the SAME /exec URL, so
 *       settings.json does not change.)
 */

// ---- settings --------------------------------------------------------------
var GUARD_TABS = ['HSR', 'JPN', 'SJP', 'VTR'];  // tabs the sync writes
var HEADER_ROWS = 1;                            // header rows on each tab
var SHRINK_LIMIT = 0.5;      // restore if new rows < 50% of old rows ...
var ROLLOVER_DAYS = 2;       // ... except on day 1-2 of a month (MTD restarts)
var TZ = 'Asia/Kolkata';
// ---------------------------------------------------------------------------

function doPost(e) {
  var lock = LockService.getScriptLock();
  // Wait up to 5 min for a previous upload to finish, never run two at once.
  if (!lock.tryLock(300000)) {
    return json_({ status: 'error', message: 'busy: previous upload still running' });
  }
  try {
    var ss = SpreadsheetApp.getActiveSpreadsheet() ||
             SpreadsheetApp.openById('1GSXkszg44LjsIlln4kPcnH6tiWV_MAJrPEGwtH1tDRA');
    var snap = snapshot_(ss);

    var result, err = null;
    try {
      result = doPost_original(e);
    } catch (ex) {
      err = ex;
    }
    SpreadsheetApp.flush();

    var restored = verifyAndRestore_(ss, snap);
    if (restored.length) {
      console.warn('SyncGuard restored last good data on: ' + restored.join(', '));
    }
    if (err) throw err;   // let the PC app see the real failure and retry
    return result;
  } finally {
    lock.releaseLock();
  }
}

// Keep GET working if the original script had a doGet that the app uses.
// (Delete this function if your original code already has doGet.)
// function doGet(e) { return doGet_original(e); }

function snapshot_(ss) {
  var snap = {};
  GUARD_TABS.forEach(function (name) {
    var sh = ss.getSheetByName(name);
    if (!sh) return;
    var lr = sh.getLastRow(), lc = sh.getLastColumn();
    snap[name] = (lr > 0 && lc > 0) ? sh.getRange(1, 1, lr, lc).getValues() : [];
  });
  return snap;
}

function dataRows_(sh) {
  return Math.max(0, sh.getLastRow() - HEADER_ROWS);
}

function verifyAndRestore_(ss, snap) {
  var day = Number(Utilities.formatDate(new Date(), TZ, 'd'));
  var restored = [];
  GUARD_TABS.forEach(function (name) {
    var sh = ss.getSheetByName(name);
    var old = snap[name];
    if (!sh || !old) return;
    var oldRows = Math.max(0, old.length - HEADER_ROWS);
    if (oldRows === 0) return;                       // nothing to protect

    var newRows = dataRows_(sh);
    var emptied = newRows === 0;
    var shrunk  = newRows < oldRows * SHRINK_LIMIT && day > ROLLOVER_DAYS;
    if (!(emptied || shrunk)) return;                // upload looks fine

    var lr = sh.getLastRow(), lc = sh.getLastColumn();
    if (lr > 0 && lc > 0) sh.getRange(1, 1, lr, lc).clearContent();
    sh.getRange(1, 1, old.length, old[0].length).setValues(old);
    restored.push(name + ' (' + newRows + ' -> ' + oldRows + ' rows)');
  });
  return restored;
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o))
    .setMimeType(ContentService.MimeType.JSON);
}
