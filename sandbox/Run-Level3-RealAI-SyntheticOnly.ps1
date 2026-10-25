# LEVEL 3 - real backend + REAL AI transcription and report writing, using FAKE (synthetic) speech only.
# Fake audio is sent to the AI companies whose keys you put in level3.env. They may charge you.
# Never play or record a real patient. Never use real names, dates of birth or health card numbers.
. "$PSScriptRoot\_common.ps1"
Assert-SetupDone; Assert-PortFree
Write-Host ''
Write-Host 'LEVEL 3 sends FAKE speech to outside AI companies using YOUR keys.' -ForegroundColor Yellow
Write-Host 'Type SYNTHETIC ONLY to confirm you will use no real patient information:' -ForegroundColor Yellow
if ((Read-Host) -cne 'SYNTHETIC ONLY') { throw 'Not confirmed. Nothing was started.' }
. "$PSScriptRoot\_level3_env.ps1"
Write-Host 'LEVEL 3: real AI, fake data only. Press Ctrl+C to stop.' -ForegroundColor Cyan
Set-PhoneLink
Push-Location $BackendDir
try { & $VenvPython -m uvicorn server.main:app --host 127.0.0.1 --port 18766 --no-access-log } finally { Pop-Location }
