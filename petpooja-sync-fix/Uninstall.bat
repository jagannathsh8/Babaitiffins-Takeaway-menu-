@echo off
schtasks /Delete /TN "PetpoojaSyncLite 15min" /F
echo Scheduled task removed.
pause
