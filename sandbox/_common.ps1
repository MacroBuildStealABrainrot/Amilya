# Shared helpers for the sandbox scripts. Not meant to be run on its own.
$ErrorActionPreference = 'Stop'
$script:SandboxDir = $PSScriptRoot
$script:ProjectDir = Split-Path -Parent $PSScriptRoot
$script:BackendDir = Join-Path $ProjectDir 'backend'
$script:VenvPython = Join-Path $SandboxDir '.venv\Scripts\python.exe'
$script:Port = 18766

function Assert-SetupDone {
    if (-not (Test-Path -LiteralPath $VenvPython)) { throw 'Run .\Setup-Sandbox.ps1 first (one time).' }
    if (-not (Test-Path -LiteralPath (Join-Path $BackendDir 'server\main.py'))) { throw "Backend folder not found at $BackendDir" }
}
function Assert-PortFree {
    $busy = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($busy) { throw "Port $Port is already in use. Close the other sandbox window first (Ctrl+C)." }
}
function Set-PhoneLink {
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adb) { Write-Host 'adb was not found. Add Android SDK platform-tools to PATH, then run: adb reverse tcp:18766 tcp:18766' -ForegroundColor Yellow; return }
    $devices = & adb devices | Select-String -Pattern "`tdevice$"
    if (-not $devices) { Write-Host 'No phone found by adb. Plug in the Samsung, allow USB debugging, then run: adb reverse tcp:18766 tcp:18766' -ForegroundColor Yellow; return }
    & adb reverse tcp:18766 tcp:18766 | Out-Null
    Write-Host 'Phone linked: the phone can reach this PC test server at 127.0.0.1:18766 (USB only).' -ForegroundColor Green
}
