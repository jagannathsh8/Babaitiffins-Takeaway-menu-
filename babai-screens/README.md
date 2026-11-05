# Babai Screens (Android)

Our own screen app, replacing the modified Petpooja KDS app on the outlet's TVs and tablets. It is a
small frame (about 125 KB) around the screens the **Babai Console** (4.6+) serves on the shop Wi-Fi:

| Screen | Page on the Console |
|---|---|
| Rider TV | `/screens/rider` |
| Dosa order status TV | `/screens/dosa` |
| Pick-up TV | `/screens/pickup` |
| Kitchen screen | `/screens/kitchen` |
| Dosa barker | `/barker` (one tawa: `?tawa=2`) |

All rules run on the Console, from its Petpooja read. The app:

* finds the Console on the Wi-Fi (or takes its address), asks for the screen once and the Console
  PIN (the screen gets its own key; a kitchen screen needs it to mark Food ready);
* shows the screen full screen, keeps it on, starts on power-on (kiosk mode: home app), reconnects
  by itself and finds the Console again if the PC's address changes;
* speaks the calls with the TV's voice, else plays the Console PC's Windows voice
  (`/screens/voice.wav`, for TVs without a voice such as the Panasonic kiosk), else a chime;
* updates itself from the Console (`/bsapp/`, copied from the Netlify site): at start or 3-6 am.

Settings on the TV: **MENU** on the remote, **Back 5 times**, or tap the top-left corner 5 times.

## Build (no Android SDK)

```
tools/get-tools.sh <tools dir>   # once: apktool (aapt2), dx, apksig, android-all from Maven Central
BS_KS=<path to babai-screens.p12> ./build.sh <tools dir>   # tests, compile, package, sign
```

The signing key (`babai-screens.p12`) is kept out of git: store it safely. Every update must be
signed with it, or the TVs refuse the update. Signing needs Java 8-11 for apksig 2.3.0's v1 signer
(`SIGN_JAVA`, or an installed Java 11). Raise `VERSION_CODE` in `build.sh` for each release, then
pack the Netlify site (`make-netlify-site.sh`).
