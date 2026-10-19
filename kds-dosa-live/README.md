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
* **Approx. Wait = tawa rounds.** One round cooks 8 dosas in 3 minutes (the outlet's figure).
  * dosas still to cook = Dine-In dosa pieces on KOTs still Preparing (Swiggy/Zomato not counted);
  * a new order joins the next round with space: rounds = (dosas waiting + 1) ÷ 8, rounded up;
  * wait = rounds × round time. On a busy tawa (8+ waiting, 16+ dosas finished in the last 15 min)
    the round time follows the real pace, kept between 2.5 and 4.5 min;
  * never more than 10 min when 8 or fewer dosas wait, never more than 20 min when busy.
  * Example: 13 waiting → 2 rounds → 6 min. Nothing waiting → one round, 3 min ("Under 5 min").
  * Each token on the DOSA ORDER STATUS screen gets the same rule: the round its dosas land in × the
    round time.
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
  300 to 2000 item lines. Everything else in that dex is identical.
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

DATA ON THE BABAI HUB: when the Babai Hub PC is on the Wi-Fi, Prep Live keeps its data there, so every tablet uses the same history (`PrepSync`, `http://<hub PC>:8790/prep/...`).

* The first tablet sends the recipes and Petpooja history the APK came with.
* Every tablet sends its recorded days (and holiday plans); the hub keeps, per date, the best copy.
* On start a tablet reads all of it back: a new or reset tablet gets the whole history at once.
* New recipes uploaded on the hub page replace the APK's own (kept in files, so they still work with the hub off).
* The projection still runs on the tablet. With the hub off, nothing changes.

REPORT / EXCEL: pick any date (today live, 42 days of tablet history, Petpooja seed days),
view prep items or dishes per hour, export .xlsx (Summary, Prep items, Dishes sold) via the
system "Save as" screen, then share.

`assets/prep_profile.json` and `assets/prep_days.txt` hold sales volumes and recipe quantities, so they are git-ignored.
Regenerate it (no prices, costs, KOT ids or staff names are kept):

```
python3 tools/make_prep_profile.py BOM_SCRAP.xlsx assets/prep_profile.json report1.xlsx [report2.xlsx ...]
```

Petpooja caps exports at 50,000 rows (~9 days here): export week by week and pass all files.

## Rider screen camera: removed

The hands-free rider camera and its text reader (Tesseract, about 11 MB) were taken out (build 4) to keep
the app light on TV boxes. The KDS's own scan button and USB/Bluetooth scanners work as before.
* **Bridge Print:** if no address is set, the KDS looks for it on the Petpooja PC (`intra_ip` in the KDS settings, port 8787) every 2 minutes.
* **Daily cache clear:** the app's cache folders are emptied once a day at the first start. Settings and KDS data are not touched.


## Rider screen: how it works

* **Babai Hub (preferred source):** when the Babai Hub PC is on the Wi-Fi, the rider screen reads `http://<hub PC>:8790/rider.json` every 2 s.
  * It gets every online order from Petpooja (not only what this KDS board shows), Bridge Print slots, and "ready since" times kept on the PC.
  * The address can be set in the long-press settings, or is found by itself (Petpooja PC first, then the Wi-Fi).
  * If the hub doesn't answer for 10 s, the screen uses this KDS board again by itself. The bottom line shows which source is in use.

* **Orders and status come from Petpooja** (this KDS). An order shows as READY when its KOT is Food Ready. It leaves the screen only when Petpooja shows it dispatched (the rider picked it up) or cancelled.
* **Bridge Print only gives the pickup slot.** Without it, the slot shows "—".
* **No vanishing:**
  * An order that drops off the KDS board for a moment stays for 2 minutes.
  * When 4+ ready orders (and 30%+ of them) drop off in the same refresh, it is a short answer from Petpooja, not riders: they stay 20 minutes.
  * The KDS board itself keeps up to 2000 item lines from Petpooja (about double a 250-order peak day of ~900 lines; still a ceiling, because the KDS board builds every card at once). Petpooja's own limit was 300 lines (about 80-120 orders), and the oldest orders silently dropped off both the KDS board and the rider screen on busy days.
  * There is no age rule and no time limit.
  * Ready orders still there after 3 hours are cleared (never dispatched).
* **Ready times are saved** (`rider_ready_at`), so a restart keeps the "ready for" timers.
* **Removal log:** the last 20 removals are listed in the long-press settings, and the bottom line shows the latest one.
* **Brand blocks:** every brand is one block in its own solid colour (Swiggy orange, Zomato red, Ownly purple ...) with its name and counts on top.
* **Follows the device:** landscape on a TV, upright on a phone. Both fit everything on one screen with no scrolling; the number of columns is chosen for the biggest cards that fit.
* **Layout ("sunlight" design):**
  * Light screen.
  * A totals bar: TOTAL / READY / PREPARING, then each brand.
  * One section per brand, the brand with the most orders first.
  * Inside each section: READY cards (white, green bar, slot in a black box with yellow digits), then PREPARING cards (grey, amber bar), each in last-4-digit order.
  * Timers on the cards: ✓ since ready, 🔥 in kitchen, ⏱ since order.
* **Font:** Barlow Condensed (SIL OFL), stored in `assets/`.
