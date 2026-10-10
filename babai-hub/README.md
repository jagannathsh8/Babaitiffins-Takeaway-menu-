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
`dist/babai-hub.exe`.
