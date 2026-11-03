"use strict";
// A barcode scanner in keyboard mode types the number and Enter in well under 0.1 s; a person
// can't. BabaiWedge(doc, onScan) catches that on a page (every tab of the Console, and the pages
// shown inside it). A scan typed into a box is taken back out of it, so nothing is left behind.
// Inside the Console's Bridge Print overview (another port), scans go up to the Console window.
(function (w) {
  var GAP = 60, PER_KEY = 35, MIN = 6;
  function attach(doc, onScan) {
    if (!doc || doc.__babaiWedge) return;
    doc.__babaiWedge = true;
    var buf = "", first = 0, lastT = 0, target = null, startVal = null, quiet = null;
    function isScan() { return buf.length >= MIN && /\d{4}/.test(buf.replace(/\D/g, "")) && (lastT - first) / (buf.length - 1) < PER_KEY; }
    function done() {
      var code = buf;
      buf = "";
      if (target && startVal !== null && "value" in target) {
        target.value = startVal;
        try { target.dispatchEvent(new Event("input", { bubbles: true })); } catch (e) {}
      }
      onScan(code);
    }
    doc.addEventListener("keydown", function (e) {
      var now = performance.now();
      clearTimeout(quiet);
      if (e.key === "Enter" || e.key === "Tab") {
        if (isScan()) { e.preventDefault(); e.stopPropagation(); done(); }
        buf = "";
        return;
      }
      if (!e.key || e.key.length !== 1 || e.ctrlKey || e.altKey || e.metaKey) return;
      if (buf && now - lastT > GAP) buf = "";
      if (!buf) {
        first = now; target = e.target;
        startVal = target && typeof target.value === "string" ? target.value : null;
      }
      buf += e.key; lastT = now;
      // scanners set to send nothing after the number: the scan ends when the typing stops
      quiet = setTimeout(function () { if (buf.length >= 8 && isScan()) done(); else buf = ""; }, 120);
    }, true);
  }
  w.BabaiWedge = attach;
  if (w.parent !== w) attach(document, function (code) { w.parent.postMessage({ babaiScan: code }, "*"); });
})(window);
