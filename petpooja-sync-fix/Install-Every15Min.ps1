# Install-Every15Min.ps1
# Runs PetpoojaSyncLite.exe --once every 15 minutes, 06:30 to 23:00, every day,
# using Windows Task Scheduler. Put this file in the SAME folder as
# PetpoojaSyncLite.exe and run it with  Install-Every15Min.bat  (double-click).

$ErrorActionPreference = 'Stop'
$TaskName = 'PetpoojaSyncLite 15min'
$Dir      = Split-Path -Parent $MyInvocation.MyCommand.Path
$Exe      = Join-Path $Dir 'PetpoojaSyncLite.exe'

if (-not (Test-Path $Exe)) {
    Write-Host "PetpoojaSyncLite.exe not found in $Dir" -ForegroundColor Red
    Read-Host 'Press Enter to close'; exit 1
}

# 1. Stop the tray copy so the two do not run at the same time.
Get-Process PetpoojaSyncLite -ErrorAction SilentlyContinue | Stop-Process -Force
# Remove the tray app's "Start with Windows" entry (if it was ticked).
$run = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run'
(Get-Item $run).Property | Where-Object { $_ -like '*Petpooja*' } | ForEach-Object {
    Remove-ItemProperty -Path $run -Name $_ -ErrorAction SilentlyContinue
}
$startup = [Environment]::GetFolderPath('Startup')
Get-ChildItem $startup -Filter '*Petpooja*' -ErrorAction SilentlyContinue | Remove-Item -Force

# 2. Create the scheduled task.
$action  = New-ScheduledTaskAction -Execute $Exe -Argument '--once' -WorkingDirectory $Dir
# Daily at 06:30, repeat every 15 min for 16h30m (last run 23:00).
$trigger = New-ScheduledTaskTrigger -Daily -At '06:30'
$rep     = New-ScheduledTaskTrigger -Once -At '06:30' `
             -RepetitionInterval (New-TimeSpan -Minutes 15) `
             -RepetitionDuration (New-TimeSpan -Hours 16 -Minutes 30)
$trigger.Repetition = $rep.Repetition

$settings = New-ScheduledTaskSettingsSet `
    -MultipleInstances IgnoreNew `
    -StartWhenAvailable `
    -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
    -ExecutionTimeLimit (New-TimeSpan -Minutes 14) `
    -RestartCount 2 -RestartInterval (New-TimeSpan -Minutes 1)
# MultipleInstances IgnoreNew : a slow run is never doubled up
# StartWhenAvailable          : a run missed while the PC was asleep runs on wake
# ExecutionTimeLimit 14 min   : a stuck run is killed before the next one

# Run as the logged-in user (needs the saved Edge login in edge_profile\).
$principal = New-ScheduledTaskPrincipal -UserId "$env:USERDOMAIN\$env:USERNAME" -LogonType Interactive -RunLevel Limited

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger `
    -Settings $settings -Principal $principal -Force | Out-Null

Write-Host ''
Write-Host "Installed: '$TaskName' runs every 15 min, 06:30-23:00." -ForegroundColor Green
Write-Host 'Starting one run now as a test...'
Start-ScheduledTask -TaskName $TaskName
Write-Host 'Check app.log in this folder in about 2 minutes.'
Read-Host 'Press Enter to close'
