# Petpooja KDS 7.8 + Babai Dosa Board

The original Petpooja KDS 7.8 with one screen added: the **Babai Dosa Board** (the approved
design: DOSA PICKUP STATUS, READY FOR PICKUP, PREPARING, est. prep time, dosas in the making,
"Did you know?"). A patch, not a new app: everything else in the APK is Petpooja's, byte for byte.

## What the patch changes

| File | Change |
|---|---|
| `classes4.dex` | two calls added to Petpooja's code: `MainActivity.onCreate` → `Board.install(this)` (the button), `DashboardViewModel.<init>` → `Board.attach(this)` (the live board, read only) |
| `classes6.dex` | new: `com.babai.board` (this folder's `src/`) |
| `assets/babai_board/` | the design's fonts (Montserrat 800/900, Figtree 600/700/800; OFL), the Babai mascot and logo |

615 of Petpooja's 619 files are untouched (checked by the build). Nothing is marked or changed in
Petpooja: the board only reads what the KDS already shows.

## How it works at the outlet

* A **DOSA BOARD** button (green, the Babai logo) sits at the bottom right of the KDS. Drag it if it
  covers something; it stays where you leave it.
* Tap it: the board opens full screen. **Back** (remote or tablet), or a long press on the board,
  closes it.
* Long press the button: the board **opens by itself** whenever the KDS starts (for a TV). Long press
  again to turn that off.
* Orders: **dine in and take away** KOTs with a dosa (category containing "dosa"), only their dosas.
  * **Ready**: every dosa of the KOT is Food Ready. Newest first, and the newest is in the spotlight
    (yellow frame, "READY FOR PICKUP") for 5 s. "Just now" for a minute, then "N min ago". It leaves
    when it is dispatched, gone from the KDS, or after 30 min.
  * **Preparing**: oldest first, 7 rows and "+N more orders waiting". A dosa already Food Ready
    gets a ✓. The ring shows about how many minutes are left, with the Dosa screen's rule: today's
    average dosa time, and how fast tokens come off the tawa (about 4 at a time before that is
    measured).
  * **Est. prep time**: the same rule for an order placed now. **Dosas in the making**: the dosas still
    being made.
* The token is the KOT's token number, as on the KDS.

## Build

```
babai-screens/tools/get-tools.sh <tools dir>          # once
./build.sh <Petpooja_kds_7_8.apk> <out.apk> <tools dir>
```

Signed with the Babai Screens key (`babai-screens/keystore`, never in git): a phone or TV with
Petpooja's own KDS must **uninstall it first** (different signature); the KDS then asks for the
server once, as on a new device.

`preview/`: renders `BoardView.java` unchanged to PNG on a PC (Java 2D stand-ins for the few
Android drawing classes it uses), for checking the design without a device:

```
javac -d /tmp/p $(find preview/shim -name '*.java') src/com/babai/board/BoardModel.java src/com/babai/board/BoardView.java preview/Preview.java
java -cp /tmp/p Preview assets/babai_board <out dir>
```
