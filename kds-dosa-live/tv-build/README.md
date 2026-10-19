# TV build (the version the outlet runs on its TVs)

The outlet chose the KDS app from commit `c84a80c` (11 Oct, "Rider board: order search by last 4
digits") as the best version for its TVs. The TV build is that version with only two changes:

* Dosa wait = tawa rounds: Dine-In dosas still to cook, 8 a round, 3 min a round (follows the real
  pace on a busy tawa, kept within 2.5-4.5 min); never more than 10 min with 8 or fewer waiting,
  20 min when busy. The per-token minutes on the token screen use the same rule.
* "ORDER READY" reads "DOSA ORDER STATUS" (button and title).

Rebuild:

```
git archive c84a80c kds-dosa-live | tar -x -C /tmp/tv
cd /tmp/tv/kds-dosa-live && patch -p2 < <repo>/kds-dosa-live/tv-build/rounds-on-c84a80c.patch
cp <prep_days.txt + prep_profile.json> assets/      # business data, not in git
./build.sh <V11.apk> Petpooja_KDS_V11_DOSA_LIVE.apk <tools dir>
```

Checked against the outlet's APK: every file is identical except classes7.dex (these two changes)
and the signature files. This version has no self-update, so the Netlify /kds/ copy (newer builds
for tablets) never replaces it.
