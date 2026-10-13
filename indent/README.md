# Babai Indent Planner

Static website (Netlify, `https://babaidosa.netlify.app/indent/`) for ops: CP indent per outlet and drop, RM transfer order, central kitchen production and accuracy.

* **BOM:** read live from the Google Sheet "BOM SCRAP" → tab **Master BOM** through a read-only Apps Script web app (secret key typed once in Settings).
  * Ingredient kind by SKU: 1xxx raw material, 2xxx CP (central kitchen), 3xxx OP (made at the outlet, expanded through its own rows).
  * BOM Qty (Recipe Qty ÷ RM yield) per product unit ÷ product Yield.
* **Sales:** each outlet's Babai Hub (2.1+) sends dish counts per hour per day to Firebase `/babai/plan/<planner key>/o/<outlet>/d/<date>`. The planner key is shown on the Hub page.
* **Projection** (`plan.js`): same weekday 4 weeks back (past Sundays on a holiday), typical = 60% × 4 weeks back + 20% × average of the 4 + 20% × latest 2. Then × recent pace (half weight, 0.7–1.4), + buffer (Settings, default 5%), rounded to the unit.
* **Drops:** 6:30 = orders until 11:00 · 11:00 = 11:00–17:00 · 5:30 = 17:00 to close.
* **Changed numbers** are kept per browser. **Save as sent** stores the final indent in Firebase (`.../sent/<outlet>/<date>`) for the Accuracy tab.
* **Excel** export (SheetJS, shipped with the site) in the CP-sheet and transfer-order layouts; **Print**.
* **PIN:** default 1234, changeable in Settings (per browser). The keys are what protect the data.

Test: `node test/plan.test.js`
