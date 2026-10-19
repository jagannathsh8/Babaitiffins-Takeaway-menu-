# Ratings & complaints dashboard (CX METRICS sheet)

Everything runs inside the CX METRICS Google Sheet, so it is always live.

1. In the sheet: **Extensions › Apps Script**. Replace everything in `Code.gs` with `merge.gs`. Its
   `doPost` keeps doing what the old Code.gs did (each tab name → rows that replace the tab, see
   `sheetsPost_`), so whatever feeds the sheet keeps working. Add an HTML file named `Dashboard` and
   paste `Dashboard.html`. Save.
2. Run `setup` once and allow access. It builds the **Merged** tab now and every 15 minutes and makes
   the Console key.
3. **Deploy › New deployment › Web app**: execute as *me*, access *anyone*. Later changes: **Deploy ›
   Manage deployments › ✏️ › New version**, so the link stays the same.
   The dashboard is on the website at **babaidosa.netlify.app/cx/**. The link is built in when the site
   is made: put it in `cx-sheet/web-app-url.txt` (not committed) or `CX_URL`, then run
   `make-netlify-site.sh`. The page opens at once from the copy saved on the phone and refreshes every
   5 minutes; the sheet keeps the data ready in its cache, rebuilt with the Merged tab every 15 minutes.
4. In each outlet's Babai Console (3.1 or newer): **Data › Ratings sheet**. Paste the web app link and
   the key (sheet menu **Babai › Show Console key**). From then on the Console sends its order times
   (punched, food ready = KPT, picked up = rider wait, delivered, slot) every 15 minutes into the
   **Console times** tab, joined to ratings and complaints on the order ID.

The **Merged** tab has one row per order: rating, complaint, customer history (new / repeat / regular,
90-day orders and issues, genuine or flagged), a one-line summary, the likely cause and the kitchen times.
Customer names, phones and addresses are never read or shown.

Swiggy complaints: order_id followed by complaint_id (4 digits) is the real order ID, the one on
the Petpooja bill.

Outlet names: Whitefield = VTR, Sarjapur / Sarjapur Road = SJP, Arekere (Swiggy) and JP Nagar = JPN.
Meal times: breakfast 7–12, lunch 12–4 pm, snacks 4–7 pm, dinner 7 pm onwards. Low rating = 1–3★.
