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
  `DosaLive.install(this)`, and the board limit in `KotRepositoryImpl.fetchBoardRows` raised from
  300 to 1000 item lines. Everything else in that dex is identical.
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

Projection (owner's rule, backtested on Petpooja data 1 Sep - 5 Oct 2026):

* sources = same weekday 1-4 weeks back (past Sundays when HOLIDAY TODAY is on), skipping
  holidays and incomplete days; Petpooja seed days, then this tablet's own complete days
* typical = 60% x "same day last month" + 20% x avg of the 4 + 20% x avg of the latest 2
* pace = today's actual / typical over the last 2 hours; next hour = typical x (1 + 0.5 x (pace - 1))

| Week | same as last hour | 60/20/20 raw | + holiday handling + half pace |
|---|---|---|---|
| 22-28 Sep | 42% | 20% | 15% |
| 29 Sep - 5 Oct | 43% | 20% | 17% |

REPORT / EXCEL: pick any date (today live, 42 days of tablet history, Petpooja seed days),
view prep items or dishes per hour, export .xlsx (Summary, Prep items, Dishes sold) via the
system "Save as" screen, then share.

`assets/prep_profile.json` and `assets/prep_days.txt` hold sales volumes and recipe quantities, so they are git-ignored.
Regenerate it (no prices, costs, KOT ids or staff names are kept):

```
python3 tools/make_prep_profile.py BOM_SCRAP.xlsx assets/prep_profile.json report1.xlsx [report2.xlsx ...]
```

Petpooja caps exports at 50,000 rows (~9 days here): export week by week and pass all files.

## Rider screen: hands-free camera

Long-press **🛵 RIDER** → *Rider screen settings* → **📷 Camera**. Until it is changed there, it is on for the rider screen when the device has a camera, and off on devices without one (TV boxes behave as before, no camera permission is asked).

* **Food Ready:** hold a KOT barcode in front of the camera and that KOT is marked Food Ready. This works the same as the USB/Bluetooth scanner.
* **Rider phone:** while the rider screen is open, a rider's phone (Swiggy, Zomato, "Collect Food" app) held up to the camera opens a card. The card shows:
  * the brand
  * the full order ID, with the last 4 digits highlighted
  * the slot
  * READY or PREPARING
  * when the order was placed and how long it has been ready
  * the KOT items
  
  A different phone replaces the card. **OK** closes it, and it closes by itself after 20 seconds with nothing in front of the camera.
* **Camera options:** off, on while the rider screen is open, or always on. You can pick the back, front or USB camera, show or hide the small preview, and set the flash.
* **How it works:** the text is read on the device by Tesseract (`assets/ocr`, tesseract.js-core 5.1.1, Apache-2.0) in a hidden WebView. It needs no internet.
  * `RiderOcr` matches the digits read from the phone against the board's order IDs, comparing from the last digit backwards. Only orders on the board can match.
  * Camera and decoding run on their own threads. Watchdogs restart a camera that stops sending pictures or a reader that stops answering.
* **Bridge Print:** if no address is set, the KDS looks for it on the Petpooja PC (`intra_ip` in the KDS settings, port 8787) every 2 minutes.
* **Daily cache clear:** the app's cache folders are emptied once a day at the first start. Settings and KDS data are not touched.


## Rider screen: how it works

* **Orders and status come from Petpooja** (this KDS). An order shows as READY when its KOT is Food Ready. It leaves the screen only when Petpooja shows it dispatched (the rider picked it up) or cancelled.
* **Bridge Print only gives the pickup slot.** Without it, the slot shows "—".
* **No vanishing:**
  * An order that drops off the KDS board for a moment stays for 2 minutes.
  * When 4+ ready orders (and 30%+ of them) drop off in the same refresh, it is a short answer from Petpooja, not riders: they stay 20 minutes.
  * The KDS board itself keeps up to 1000 item lines from Petpooja (about 250-350 orders; still a ceiling, because the KDS board builds every card at once). Petpooja's own limit was 300 lines (about 80-120 orders), and the oldest orders silently dropped off both the KDS board and the rider screen on busy days.
  * There is no age rule and no time limit.
  * Ready orders still there after 3 hours are cleared (never dispatched).
* **Ready times are saved** (`rider_ready_at`), so a restart keeps the "ready for" timers.
* **Removal log:** the last 20 removals are listed in the long-press settings, and the bottom line shows the latest one.
* **Layout ("sunlight" design):**
  * Light screen.
  * A totals bar: TOTAL / READY / PREPARING, then each brand.
  * One section per brand, the brand with the most orders first.
  * Inside each section: READY cards (white, green bar, slot in a black box with yellow digits), then PREPARING cards (grey, amber bar), each in last-4-digit order.
  * Timers on the cards: ✓ since ready, 🔥 in kitchen, ⏱ since order.
* **Font:** Barlow Condensed (SIL OFL), stored in `assets/`.
