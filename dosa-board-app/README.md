# Babai Dosa Board (app)

A separate Android app only for the dosa pick-up TV: the Babai Dosa Board (the approved design),
reading the orders straight from the Petpooja billing PC on the shop Wi-Fi, as the Petpooja KDS
does. No Console.

* Default: dine-in dosas only. Settings: Pick up, Self service, Online (all channels or chosen ones),
  ready tokens stay 5 / 10 / 15 / 30 min (default 10), voice calls.
* "Did you know?": today's numbers made from Petpooja's orders (favourite dosa, dosas made today,
  rush hour, top 3, yesterday's favourite; kept across restarts), then the facts list (`FACTS` in
  `DosaApp.java`, to be replaced by the outlet's list).
* Settings: MENU on the remote, Back 5 times, or 5 taps in the top-left corner. Opens by itself
  when the TV starts.

Build: `./build.sh <tools dir>` (tools: `babai-screens/tools/get-tools.sh`). Shared code:
`babai-screens/src` (DosaBoard, DosaStats, Petpooja, Net, Voice) and `kds-78-board` (drawing code,
fonts, pictures). Signed with the Babai Screens key (never in git).
