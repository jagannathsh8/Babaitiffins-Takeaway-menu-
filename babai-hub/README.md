# Babai Console (Babai Hub + Bridge Print)

One Windows program for any PC on the shop network (version 3.0 onwards; the Hub part is described
further down). Build: `./build.sh` makes `dist/babai-console.exe`.

**Install:** double-click `babai-console.exe` (from https://babaidosa.netlify.app/console/babai-console.exe)
and say Yes to the Windows admin prompt once. It installs to `C:\Program Files\Babai Console`, keeps
its data in `C:\ProgramData\BabaiConsole`, and:

- runs as the Windows service **Babai Console**: starts with the PC before anyone logs in; Windows
  and the service itself restart it if it stops (the service supervises `app\<version>\babai-console.exe --run`);
- opens its window (Edge in app mode, `http://localhost:8790/console`) at every log-in and reopens it
  within ~20 s if it is closed by mistake (tray icon → Close window to really close it);
- copies the old Babai Hub's settings and data, and stops the old Hub starting;
- switches the old Bridge Print off (scheduled task "Bridge print" disabled, files kept) and takes
  over its printer, settings and next token. Uninstall offers to switch it back on.

**Printing** (`pr_*.go`): Petpooja keeps printing to its "Bridge print" queue (this PC, TCP 9100).
Each print is saved, gets the next token, is matched to its Petpooja order (kitchen list read at once;
Windows OCR in the background as Bridge Print did) and printed straight to the Windows print queue
(winspool) or a network printer by IP, typically in a few milliseconds. Print rules (order type,
brand, items / categories, whole KOT or matching items, copies, token, order no., printer + fallback)
are on Bridge Print → Printers & rules. A printer that is offline / out of paper / stuck sends the
slip to its fallback printer with an alert.

**Bridge Print compatibility:** the old Bridge Print screen and API on `:8787` (TV page
`/display`, used by the Hub's rider feed too), the Android Token app lookup `:8788` and discovery
UDP `:8789` are the same as before.

**Areas:** Bridge Print (no PIN): Overview, Dispatch, Printers & rules, Print timing, Alerts, Apps.
Hub (PIN): Status, Petpooja & Firebase, Scanners & rider, Customers' page, This PC & updates.
Data (PIN): Metrics, Prep Live, Indent planner, Connections.

**Apps for phones and TVs:** `http://<this PC>:8790/apps` (Android KDS and Babai Scanner, kept
up to date from the Netlify site).

**Updates:** `console/version.json` on the site; switched to only between 3 and 5 am with no KOT for
5 minutes; the service goes back to the previous version by itself if the new one doesn't run fine
for a minute.

---

# Babai Hub

A small Windows program for any PC on the shop network. It reads the live kitchen orders from the
Petpooja billing station the same way the Petpooja KDS app on the TV does (read-only: it never marks,
dispatches or switches anything). It works out the dine-in dosa tokens with the same rules as the
Order Ready TV screen, and puts them in a free Firebase Realtime Database for the customers'
"Track your dosa" page (https://babaidosa.netlify.app, file `../dosa/index.html`), opened from a
printed QR code.

| Path | What | Who can read |
|---|---|---|
| `/babai/t/<token>` | one token: preparing/ready, orders before it, minutes left, its items | public, one token at a time |
| `/babai/meta` | dosa wait, dosas on the tawa, today's live facts, time of the last update | public |
| `/babai/live` | open KOTs in the kitchen (no customer names or phone numbers) | private |
| `/babai/stock` | item on/off list | private |
| `/babai/days/<date>/<kotId>` | every finished KOT: items, token, order type, platform order ID, created / ready / out times | private |
| `/babai/hub` | hub version and PC name | private |

Finished KOTs are also written on the PC in `%LOCALAPPDATA%\BabaiHub\days\<date>.jsonl`, and
today's dosa figures in `stats.json`, so a restart keeps them.

**Petpooja (as the KDS app):** `http://<billing PC>:3000/petpooja_server/kiosk_get_default_data`
gives the sync code; `get_kot_data_for_kitchen_view` is read every 4 s (all order types, all
categories, KOTs kept until dispatched); `touch_items` (item on/off) every 3 minutes.

**Free plan:** phones read only their own token (~0.5 KB) and the small meta every 5 s. The hub
writes only when something changed (tokens at most every 5 s, meta 15 s, open KOTs 15 s, stock 5 min),
each finished KOT once, with `print=silent`, and deletes online days older than 400 days. The hub
page shows this month's usage.

**Use:** see [SETUP.md](SETUP.md). **Build:** `./build.sh` (Go 1.24; tests run first) makes
`dist/babai-console.exe`.
