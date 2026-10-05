# KDS V11 + Dosa Live Wait

Adds a **DOSA WAIT** button to the Petpooja KDS V11 (scanner build), next to the camera button.
Tapping it opens a live overlay:

```
DOSA LIVE STATUS
Current Dosa Orders:   32
Approx. Wait Time:     Approx. 15–18 min
Today Avg Prep Time:   12.4 min
Updated:               7:32 PM
```

It also shows the exact internal estimate, throughput and queue, plus a 7-day table
(`Date | Done | PrepMin | Avg | Peak/min`). Tap **CLOSE** or outside the card to dismiss.

Built APK: `dist/Petpooja_KDS_V11_DOSA_LIVE.apk`

## Rules

* Only **Dine-In** KOTs with at least one non-cancelled item whose **category contains "dosa"**
  (case-insensitive).
* **Current Dosa Orders**: those KOTs that are still Preparing (not Food Ready, not cancelled).
* **Prep time** = moment the KOT is seen turning Food Ready − KOT created time
  (the board is checked every 2 s). Over 150 min is ignored as an outlier.
* **Today Avg Prep** = total prep minutes ÷ completed Dosa KOTs (resets at midnight).
* **Throughput** = Dosa KOTs completed in the last 15 min ÷ 15 (min. 3 min window after start-up).
* **Approx. Wait**
  * queue = active ÷ throughput (e.g. 32 ÷ 2/min = 16 min), never below 60 % of today's average;
  * blended with today's average until ≥ 6 recent completions make the throughput reliable;
  * if nothing has completed recently, at least the age of the oldest waiting Dosa KOT;
  * no Dosa orders waiting → today's average (12 min default before the first completion);
  * capped at 90 min. Shown as a range: 16 → "15–18 min", 19 → "18–21 min", 43 → "40–45 min",
    under 5 → "Under 5 min".
* Updates live: new Dosa KOT → count up; Food Ready → count down + prep recorded; every 15 s the
  throughput window slides even with no new events.

## Memory / storage

No order history is kept. Active KOTs are re-read from the existing (300-capped) board. Only a
small daily record is saved in SharedPreferences `dosa_live_stats`: date, completed, total prep,
the ids already counted today (so restarts don't double count), completion times from the last
15 min and 7 history lines. When the board is at the 300 cap, a KOT that drops off the board is
not counted as completed.

## What changed in the APK

* `classes4.dex`: one line added at the end of `MainActivity.onCreate` →
  `DosaLive.install(this)`. Everything else in that dex is identical.
* `classes7.dex`: new, the `com.pp.kds.dosa` code in `src/`.
* All other dex files, resources and the manifest are copied unchanged. The scanner
  (`com.pp.kds.scan.*`) is not modified; the add-on only reads the board and never presses
  Food Ready.

## Rebuild

```
./build.sh <V11.apk> <out.apk> <tools-dir>
```

See the header of `build.sh` for the tools. `test/DosaStatsTest.java` runs first and covers the
32-orders/2-per-minute example, the ETA falling as the queue clears, restarts, cancellations,
outliers, cap drops and midnight rollover.

## PREP LIVE (production screen)

Second button next to DOSA LIVE. Every KOT on the board (all order types) is converted through the
BOM into usage of the fresh-prep items (Sambar, Peanut/Allam/Tomato chutney, Aloo masala, Sagu,
Upma, Pongal, Kesari bath, Dosa and Idli batter). Each card: next-hour projection, last hour,
used today, today-vs-typical hourly chart, and batch status once batches are logged. Tap a card
for "+ batch made" chips, undo, raw-ingredient needs for the next hour, and top driving dishes.

Projection = typical usage for this weekday/hour (Petpooja seed, blended 50/50 with this tablet's
own recorded days) scaled by how busy the last hour was vs typical, blended with the last-hour
trend. Backtest on a held-out day (5 Oct, 2,007 KOTs): ~18-22% error on high-volume items vs
31-38% for "same as last hour".

`assets/prep_profile.json` holds sales volumes and recipe quantities, so it is git-ignored.
Regenerate it (no prices, costs, KOT ids or staff names are kept):

```
python3 tools/make_prep_profile.py BOM_SCRAP.xlsx assets/prep_profile.json report1.xlsx [report2.xlsx ...]
```

Petpooja caps exports at 50,000 rows (~9 days here): export week by week and pass all files.
