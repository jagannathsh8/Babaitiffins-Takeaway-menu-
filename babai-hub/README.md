# Babai Hub

A small Windows program for one shop PC (for example the Bridge Print PC). The main kitchen TV
(Petpooja KDS with Dosa Live) sends its live data to it over the shop Wi-Fi, and the hub puts it in a
free Firebase Realtime Database:

| Path | What | Who can read |
|---|---|---|
| `/babai/dosa` | dine-in dosa token status (ready / preparing / minutes left) | public: the customers' "Track your dosa" page (`/dosa/` in this repo) |
| `/babai/live` | open KOTs on the kitchen board | private |
| `/babai/days/<date>/<kotId>` | every finished KOT: items, token, order type, platform order ID, created / ready / out times | private |
| `/babai/hub` | hub version and PC name | private |

Finished KOTs are also written on the PC in `%LOCALAPPDATA%\BabaiHub\days\<date>.jsonl`.
The hub never contacts Petpooja; it only receives what the TV already shows.

**Free plan:** the token status is sent at most every 5 s and only when it changed, plus a
1-minute heartbeat. Open KOTs are sent at most every 15 s, and each finished KOT once. Writes use
`print=silent`, so Firebase doesn't send the data back, and online days older than 400 days are
deleted. The hub page shows this month's usage.

**Use:** double-click `babai-hub.exe`; its page opens at http://localhost:8790 (on that PC only).
Step 1 is Firebase, Step 2 is the address to type into the TV, Step 3 is the printable QR poster.
Tick "Start Babai Hub when Windows starts".

**Build:** `./build.sh` (Go 1.24; the tests run first) makes `dist/babai-hub.exe`.
