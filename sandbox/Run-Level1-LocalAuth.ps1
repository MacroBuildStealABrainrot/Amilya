# LEVEL 1 - the real backend code, empty throw-away database, AI switched OFF, billing OFF.
# Good for: sign-in, token (CSRF) checks, saving. It can NOT transcribe or write a report.
. "$PSScriptRoot\_common.ps1"
Assert-SetupDone; Assert-PortFree
Write-Host 'LEVEL 1: local backend, AI OFF, fake data only. Press Ctrl+C to stop.' -ForegroundColor Cyan
Set-PhoneLink
& $VenvPython (Join-Path $ProjectDir 'android\tools\local_contract_server.py')
