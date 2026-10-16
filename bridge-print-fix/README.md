# Bridge Print fix: Ownly slots

Two files for Bridge Print (replace them in its folder, next to `server.mjs`):

* `receipt.mjs`: reads the order number from the KOT even when Windows OCR splits it (Ownly KOTs print the last
  4 digits bold, OCR reads "179197608324181 1062") or reads 0/1 as O/l; falls back to the digits printed under
  the KOT barcode (13+ digits only, so phone numbers never count).
* `petpooja-live.mjs`: keeps 19-digit order IDs exact when reading Petpooja's order list (JSON.parse rounded
  1791982601318835770 to 1791982601318835700).

Test: `node --test babai-fix.test.mjs` (next to the two files). `*.diff` show the exact changes.
