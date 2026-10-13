# Babai Scanner

Android app: scan a KOT barcode and the Babai Hub marks that online order **Food Ready** in Petpooja.

* **Pairing:** Babai Hub page → Step 5 → Add phone → scan the QR code with the app (or paste the code).
* **Phone mode:** opens straight on the camera.
  * Reads level or upright KOTs; blurred, faded and grainy prints are read by averaging rows along the bars and with a thorough pass every few frames.
  * Learns the KOTs' barcode kind for a fast pass.
  * Torch button. The same KOT isn't sent twice within 4 s.
  * Beep and vibrate, then the result: ✓ FOOD READY · Swiggy …0744 · slot 40.
* **USB mode (Android box):** reads a USB barcode scanner directly in a background service, with any app on screen. It starts again after a restart and when the scanner is plugged in. A keyboard-type scanner also works on the app's own screen.
* **Routes:**
  * **Shop Wi-Fi first:** `POST http://<hub>/scan`.
  * **Internet second:** Firebase `sq/<key>/<id>`, answer in `sr/<key>/<id>`.
  * **Neither works:** scans wait in a queue and are sent later, up to 9 minutes. The hub marks each scan id once.
* **Barcode reader:** ZXing (`Decoder.java`). ML Kit can replace it once `dl.google.com` is reachable from the build machine.

Build: `./build.sh <tools dir> <zxing core-3.5.3.jar>`. It needs no Android SDK (apktool's aapt2, D8 from jadx, uber-apk-signer). Tests: `DecoderTest` (generated barcodes: blur, low contrast, noise, upright, pairing QR) and `HidTest` (USB keyboard reports).
