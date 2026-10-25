# LEVEL 2 - a PRETEND server. It answers upload / transcript / report with fixed fake text, and can fail on purpose.
# Good for: proving the phone app's offline -> reconnect -> upload -> report -> "recording kept" behaviour.
# No AI, no cost, nothing leaves this PC. It does NOT prove the real AI or the real backend work.
. "$PSScriptRoot\_common.ps1"
Assert-SetupDone; Assert-PortFree
Write-Host 'LEVEL 2: pretend server, fixed fake answers. Press Ctrl+C to stop.' -ForegroundColor Cyan
Set-PhoneLink
& $VenvPython (Join-Path $ProjectDir 'test-tools\controlled_pipeline_api.py')
