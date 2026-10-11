# Babai Hub – setup steps

About 30 minutes for the first outlet, 10 minutes for each further outlet (steps 3 and 5 only:
same Netlify page, same Firebase database and secret, one hub PC per outlet).
You need: any Windows 10/11 PC on the shop Wi-Fi/network that stays on
during business hours (the Bridge Print PC is fine), the IP address of the main Petpooja billing PC,
and a Google account.

## 1. Customer page (Netlify)

Already done: https://babaidosa.netlify.app. In step 4 you upload the final page made by the hub.

## 2. Firebase (free cloud database)

1. Go to https://console.firebase.google.com → **Create a project** → name `babai-dosa` →
   Google Analytics not needed → Create.
2. Left menu **Build → Realtime Database → Create database** → location **Singapore
   (asia-southeast1)** → **Start in locked mode** → Enable.
3. Open the **Rules** tab, replace everything with the line below, and click **Publish**:

   ```
   {"rules":{".read":false,".write":false,"babai":{"outlets":{".read":true},"o":{"$o":{"meta":{".read":true},"t":{"$tok":{".read":true}}}}}}}
   ```

4. Go back to the **Data** tab and copy the address at the top
   (like `https://babai-dosa-default-rtdb.asia-southeast1.firebasedatabase.app`).
5. ⚙ (next to Project Overview) → **Project settings → Service accounts → Database secrets** →
   **Show** → copy the secret. Keep it private.

## 3. Babai Hub on the PC

1. Copy `babai-hub.exe` to the PC (for example the Desktop) and double-click it.
   If Windows says "Windows protected your PC": **More info → Run anyway**.
   If Windows asks about the firewall, click **Allow**.
2. It installs itself (no admin needed): it now starts by itself every time the PC starts, sits
   in the **tray** next to the clock (Babai logo; click the ^ arrow if hidden), and appears in
   **Settings → Apps** and the Start menu. You can delete the copy you double-clicked.
3. The hub page opens in the browser (later: right-click the tray icon → **Open Babai Hub**).
4. **Step 1 – Outlet and Petpooja:** type the **outlet name exactly as on the bill** (VTR, Sarjapur,
   HSR, JPN…), the **outlet location** (in Google Maps long-press the shop and copy the two numbers,
   or paste a Maps link, or tap **Use this PC's location**; customers' phones then pick the nearest
   outlet by themselves), then the billing PC's IP (the same one typed on the KDS
   TV, for example `192.168.1.5`) → **Save & test** (or tray → **Petpooja server IP…**). You should
   see "Connected to Petpooja: N KOTs in the kitchen right now". The KOTs appear under
   **What Petpooja sent** at the bottom.
5. **Step 2 – Firebase:** paste the database address
   `babai-dosa-default-rtdb.asia-southeast1.firebasedatabase.app` and the secret.
   → **Save & test** → "Saved and connected to Firebase".
6. The top of the page and the tray tooltip should now say **Live**.

**Tray menu (right-click the Babai icon):** status · Open Babai Hub · Petpooja server IP… ·
Pause / Resume · Print QR poster · Restart Babai Hub.
**Updating:** double-click a newer `babai-hub.exe`; it replaces the installed one and keeps the
settings. **Removing:** Settings → Apps → Babai Hub → Uninstall.

## 4. Customer page with token entry (once)

1. On the hub page, Step 3 → **Download customer page**. This saves an `index.html` that already
   knows your Firebase database.
2. Put that file in a folder → Netlify → **babaidosa** → **Deploys** → drag the folder in.
3. Now https://babaidosa.netlify.app works both ways: customers can scan the QR code, or open the
   site and type their token number, or tap **📷 Scan my bill** and photograph the bill: the page
   reads "Token No.: 1400" from it (on the phone; the photo is not uploaded anywhere).

## 5. QR poster

1. On the hub page, Step 3 → **Open printable poster** → Ctrl+P → print (A4). Each outlet prints
   its own poster (it carries that outlet's name and code).
2. Stick it at the counter and on tables.
3. Test with your phone: scan, type a dine-in token that has a dosa → you see it preparing; when
   the kitchen marks the dosa ready, the phone pops up, chimes, speaks and vibrates.

## If something isn't right

- **"Can't reach the Petpooja billing PC"**: check the IP, that Petpooja is open on the billing PC,
  and that the hub PC is on the same network.
- **"Petpooja refused the request"** or no KOTs although the TV shows some: open
  **What Petpooja sent → Raw answer** and send a photo.
- **"Firebase refused the database secret"**: copy the secret again (Step 2.5).
- **Customer sees "Waiting for the kitchen"**: the token has no dine-in dosa yet, or the order
  hasn't reached the kitchen. Only dine-in orders with a dosa are tracked (same as the TV).
- The hub only needs to run on **one** PC. If that PC is off, the TVs work as usual; the phone page
  shows "updates paused".
