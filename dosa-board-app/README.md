# Babai Dosa Board (app)

A separate Android app only for the dosa pick-up TV: the Babai Dosa Board (the approved design),
reading the orders straight from the Petpooja billing PC on the shop Wi-Fi, as the Petpooja KDS
does. No Console.

* Default: dine-in dosas only. Settings: Pick up, Self service, Online (all channels or chosen ones),
  ready tokens stay 5 / 10 / 15 / 30 min (default 10), voice calls.
* "Did you know?": today's numbers made from Petpooja's orders (favourite dosa, dosas made today,
  rush hour, top 3, yesterday's favourite; kept across restarts), then the facts list (set on the TV's settings page; a few neutral built-in lines until then, no
  recipe or ingredient claims).
* The TV's settings page: on a computer or phone on the shop Wi-Fi, open http://<TV address>:8080
  (shown on the TV's setup screen). PIN 1234 to save. Facts (add, change, hide, reorder), today's
  numbers on/off, which dosas, ready time, announcement on/off and how the token is said
  (whole number or digit by digit), a test announcement, and what the board reads from Petpooja now
  (token, dish status, KOT status, order status, shown or why not). Nothing leaves the shop network.
* Today's numbers count only the order types and channels chosen for the board.
* Settings on the TV: MENU on the remote, Back 5 times, or 5 taps in the top-left corner. Opens by itself
  when the TV starts.

Build: `./build.sh <tools dir>` (tools: `babai-screens/tools/get-tools.sh`). Shared code:
`babai-screens/src` (DosaBoard, DosaStats, Petpooja, Net, Voice) and `kds-78-board` (drawing code,
fonts, pictures). Signed with the Babai Screens key (never in git).
