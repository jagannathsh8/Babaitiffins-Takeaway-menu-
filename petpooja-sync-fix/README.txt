PetpoojaSync fix: no more blank tabs, runs every 15 minutes
=============================================================

WHY THE SHEET WENT BLANK
  The upload script in Google Sheets clears an outlet tab and then writes the
  new rows. If a run returns no rows (for example the Petpooja session expired),
  the upload fails partway, or two runs overlap, the tab is left with only the
  header row. Summary then shows #N/A and #DIV/0! until a later run succeeds.
  (On 28 Nov all four tabs, HSR/JPN/SJP/VTR, were header-only.)

PART A: Google Sheet (stops the blanking)
  1. Open CX METRICS -> Extensions -> Apps Script.
  2. In the existing code, rename  function doPost(e)  to  function doPost_original(e)
  3. Add a file -> Script -> "SyncGuard" -> paste SyncGuard.gs -> Save.
  4. Deploy -> Manage deployments -> Edit (pencil) -> Version: New version -> Deploy.
     The /exec URL stays the same, so settings.json does not change.
  After this, an upload can never leave a tab empty: if it would, the last good
  data is put back right away. Other tabs, formulas and formatting are not touched.

PART B: PC (runs every 15 minutes, no tray app)
  1. Copy Install-Every15Min.bat and Install-Every15Min.ps1 into the folder
     that has PetpoojaSyncLite.exe (e.g. C:\PetpoojaSyncLite).
  2. Double-click Install-Every15Min.bat.
     - Closes the tray copy and removes its "Start with Windows" entry
       (two copies running together is one cause of blank tabs).
     - Creates the Task Scheduler job "PetpoojaSyncLite 15min":
       every 15 min from 06:30 to 23:00. A run is never started twice, a run
       missed while the PC slept runs on wake, and a stuck run is stopped
       after 14 min.
  3. If Petpooja ever needs an OTP or captcha, run
       PetpoojaSyncLite.exe --login
     once and log in. The scheduled runs then use that saved login.

  The PC must be on and logged in (screen locked is fine).
  To remove the job: Uninstall.bat
