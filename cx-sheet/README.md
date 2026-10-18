# Ratings & complaints dashboard (CX METRICS sheet)

Everything runs inside the CX METRICS Google Sheet, so it is always live.

1. In the sheet: **Extensions › Apps Script**. Replace `Code.gs` with `merge.gs`. Add an HTML file named
   `Dashboard` and paste `Dashboard.html`. Save.
2. Run `setup` once and allow access. It builds the **Merged** tab now and every 15 minutes and makes
   the Console key.
3. **Deploy › New deployment › Web app**: execute as *me*, access *anyone*. The link it gives you is
   the dashboard (it reads the sheet live and refreshes every 5 minutes; filters are remembered).
4. In each outlet's Babai Console (3.1 or newer): **Data › Ratings sheet**. Paste the web app link and
   the key (sheet menu **Babai › Show Console key**). From then on the Console sends its order times
   (punched, food ready = KPT, picked up = rider wait, delivered, slot) every 15 minutes into the
   **Console times** tab, joined to ratings and complaints on the order ID.

The **Merged** tab has one row per order: rating, complaint, customer history (new / repeat / regular,
90-day orders and issues, genuine or flagged), a one-line summary, the likely cause and the kitchen times.
Customer names, phones and addresses are never read or shown.

Outlet names: Whitefield = VTR, Sarjapur / Sarjapur Road = SJP, Arekere (Swiggy) and JP Nagar = JPN.
Meal times: breakfast 7–12, lunch 12–4 pm, snacks 4–7 pm, dinner 7 pm onwards. Low rating = 1–3★.
